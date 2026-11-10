package pzopt.zombiebuddy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Installs reviewed whole-class definitions before their first load, while the JVM still
 * obtains every native class and its signers from the unmodified game JAR.
 *
 * The non-retransformable group is deliberate. Official ZombieBuddy 2.3.4 installs
 * retransformable ByteBuddy transformers, so its hooks run after this payload even
 * when ZombieBuddy registered them before invoking the mod's PreMain. Do not change
 * this flag or admit another loader revision without repeating the composition test.
 */
public final class PayloadRuntime {
    static final String PROFILE = "META-INF/pzopt-zombiebuddy/profile.properties";
    static final String PAYLOAD = "META-INF/pzopt-zombiebuddy/payload/";
    public static final String ZB_234_SHA256 = "eb79b9876332010733a8e0d7ce4fe0377846059a9e28859029e2a0fb449f6cf2";
    private static volatile String state = "not preloaded; a full restart is required";
    private static volatile boolean installed;
    private static JarFile retainedHelpers;
    private static Replacement transformer;

    private PayloadRuntime() { }

    public static boolean active() { return installed; }
    public static String status() { return state; }

    public static synchronized void installFromZombieBuddy() {
        try {
            ClassLoader loader = PayloadRuntime.class.getClassLoader();
            Class<?> buddy = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, loader);
            Path buddyJar = source(buddy.getProtectionDomain());
            if (!ZB_234_SHA256.equals(hash(buddyJar))) {
                throw new IOException("unsupported ZombieBuddy JAR; this adapter is reviewed with official 2.3.4 SHA-256 "
                        + ZB_234_SHA256 + "; actual=" + hash(buddyJar));
            }
            if (installed) {
                System.err.println("[pzopt/ZombieBuddy] early bootstrap confirmed by the reviewed official loader; " + state);
                return;
            }
            Field field = buddy.getDeclaredField("g_instrumentation");
            if (!field.trySetAccessible()) throw new IOException("ZombieBuddy instrumentation is inaccessible");
            Object value = field.get(null);
            if (!(value instanceof Instrumentation instrumentation)) throw new IOException("ZombieBuddy instrumentation is absent");
            Path ownJar = source(PayloadRuntime.class.getProtectionDomain());
            URL nativeResource = loader.getResource("zombie/GitVersion.class");
            if (nativeResource == null || !(nativeResource.openConnection() instanceof JarURLConnection connection)) {
                throw new IOException("native game identity resolves outside a JAR; remove the old loose-class install first");
            }
            Path gameJar = Path.of(connection.getJarFileURL().toURI()).toRealPath();
            install(instrumentation, ownJar, gameJar, buddyJar, loader);
        } catch (Exception | LinkageError error) {
            refused("ZombieBuddy preload", error);
        }
    }

    static void refused(String stage, Throwable error) {
        state = "REFUSED " + stage + " before payload registration: " + error;
        System.err.println("[pzopt/ZombieBuddy] " + state);
    }

    /** Package-private so isolated fixtures can exercise the exact install path. */
    static synchronized void install(Instrumentation instrumentation, Path ownJar, Path gameJar,
                                     Path buddyJar, ClassLoader nativeLoader) throws Exception {
        if (installed) return;
        if (instrumentation == null) throw new IOException("instrumentation is absent");
        if (!ZB_234_SHA256.equals(hash(buddyJar))) throw new IOException("unreviewed ZombieBuddy artifact");
        String command = System.getProperty("sun.java.command", "");
        if (command.matches("(?s).*\\bzombie\\.network\\.GameServer\\b.*")) {
            throw new IOException("client payload cannot be installed in a dedicated-server process");
        }
        Plan plan = prepare(ownJar, gameJar, nativeLoader);
        List<String> late = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (plan.replacements.containsKey(type.getName().replace('.', '/'))) late.add(type.getName());
        }
        if (!late.isEmpty()) {
            Collections.sort(late);
            throw new IOException("game targets loaded before preload: " + String.join(", ", late)
                    + "; the PZ Optimization bootstrap -javaagent must run before official ZombieBuddy's agent; fully restart");
        }
        // All identity, byte, inventory, origin and late-load checks complete before either mutation.
        Replacement replacement = new Replacement(plan, nativeLoader);
        JarFile helpers = new JarFile(ownJar.toFile());
        try {
            instrumentation.appendToSystemClassLoaderSearch(helpers);
            instrumentation.addTransformer(replacement, false);
        } catch (RuntimeException | Error failure) {
            helpers.close();
            throw failure;
        }
        retainedHelpers = helpers;
        transformer = replacement;
        installed = true;
        state = "active: " + plan.replacements.size() + " reviewed native definitions; game SHA-256="
                + plan.gameHash + "; ZombieBuddy 2.3.4 hooks follow the payload";
        System.err.println("[pzopt/ZombieBuddy] " + state);
    }

    static Plan prepare(Path ownJar, Path gameJar, ClassLoader nativeLoader) throws Exception {
        ownJar = ownJar.toRealPath(); gameJar = gameJar.toRealPath();
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        Map<String, String> stock = new LinkedHashMap<>();
        Properties profile = new Properties();
        String gameHash = hash(gameJar);
        try (JarFile own = new JarFile(ownJar.toFile()); JarFile game = new JarFile(gameJar.toFile())) {
            JarEntry profileEntry = own.getJarEntry(PROFILE);
            if (profileEntry == null) throw new IOException("missing exact-build profile");
            try (InputStream input = own.getInputStream(profileEntry)) { profile.load(input); }
            if (!"1".equals(profile.getProperty("schema"))) throw new IOException("unsupported payload profile schema");
            if (!gameHash.equals(profile.getProperty("game.sha256"))) {
                throw new IOException("unreviewed game JAR: actual=" + gameHash + "; expected=" + profile.getProperty("game.sha256"));
            }
            if (!ZB_234_SHA256.equals(profile.getProperty("zombiebuddy.sha256"))) throw new IOException("profile loader identity differs");
            Set<String> names = new TreeSet<>();
            for (String key : profile.stringPropertyNames()) if (key.startsWith("payload.")) names.add(key.substring(8));
            if (names.isEmpty()) throw new IOException("empty optimization inventory");
            for (String name : names) {
                if (!validName(name) || protectedTarget(name)) throw new IOException("refused native inventory target: " + name);
                String member = name + ".class";
                JarEntry base = game.getJarEntry(member);
                if (base == null) throw new IOException("new helper in native package must be relocated to pzopt: " + name);
                byte[] original;
                try (InputStream input = game.getInputStream(base)) { original = input.readAllBytes(); }
                String originalHash = hash(original);
                if (!originalHash.equals(profile.getProperty("stock." + name))) throw new IOException("native target hash differs: " + name);
                URL effective = nativeLoader.getResource(member);
                if (effective == null || !(effective.openConnection() instanceof JarURLConnection connection)
                        || !Path.of(connection.getJarFileURL().toURI()).toRealPath().equals(gameJar)) {
                    throw new IOException("target resolves outside the reviewed native JAR: " + name + "; remove loose-class installs");
                }
                JarEntry entry = own.getJarEntry(PAYLOAD + member);
                if (entry == null) throw new IOException("payload member missing: " + name);
                byte[] bytes;
                try (InputStream input = own.getInputStream(entry)) { bytes = input.readAllBytes(); }
                if (!hash(bytes).equals(profile.getProperty("payload." + name))) throw new IOException("payload member changed: " + name);
                replacements.put(name, bytes);
                stock.put(name, originalHash);
            }
            String startupHash = profile.getProperty("startup.stock");
            if (startupHash != null) {
                String member = StartupTransform.TARGET + ".class";
                JarEntry base = game.getJarEntry(member);
                if (base == null) throw new IOException("native main screen is absent");
                byte[] original;
                try (InputStream input = game.getInputStream(base)) { original = input.readAllBytes(); }
                if (!hash(original).equals(startupHash)) throw new IOException("native main screen bytes differ");
                URL effective = nativeLoader.getResource(member);
                if (effective == null || !(effective.openConnection() instanceof JarURLConnection connection)
                        || !Path.of(connection.getJarFileURL().toURI()).toRealPath().equals(gameJar)) {
                    throw new IOException("native main screen resolves outside the reviewed game JAR");
                }
                replacements.put(StartupTransform.TARGET, StartupTransform.transform(original));
                stock.put(StartupTransform.TARGET, startupHash);
            }
            for (JarEntry entry : Collections.list(own.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;
                if (name.startsWith(PAYLOAD)) {
                    String binary = name.substring(PAYLOAD.length(), name.length() - 6);
                    if (!names.contains(binary)) throw new IOException("uninventoried native payload: " + binary);
                } else if (!name.startsWith("pzopt/")) {
                    throw new IOException("native classes must not shadow the game on the helper classpath: " + name);
                }
            }
        }
        return new Plan(ownJar, gameJar, gameHash, Map.copyOf(replacements), Map.copyOf(stock));
    }

    static boolean protectedTarget(String name) {
        return protectedFamily(name, "zombie/gameStates/MainScreenState")
                || protectedFamily(name, "zombie/core/Core") || protectedFamily(name, "zombie/core/IntegrityReport")
                || protectedFamily(name, "zombie/network/GameServer")
                || name.equals("zombie/GitVersion") || name.startsWith("zombie/network/Integrity")
                || name.startsWith("zombie/core/integrity/");
    }

    private static boolean protectedFamily(String name, String family) {
        return name.equals(family) || name.startsWith(family + "$");
    }

    private static boolean validName(String name) {
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)+");
    }

    static Path source(ProtectionDomain domain) throws Exception {
        if (domain == null || domain.getCodeSource() == null) throw new IOException("class provenance is absent");
        return Path.of(domain.getCodeSource().getLocation().toURI()).toRealPath();
    }

    static String hash(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = sha256();
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    static String hash(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    record Plan(Path helperJar, Path gameJar, String gameHash, Map<String, byte[]> replacements, Map<String, String> stockHashes) { }

    static final class Replacement implements ClassFileTransformer {
        private final Plan plan;
        private final ClassLoader nativeLoader;
        Replacement(Plan plan, ClassLoader nativeLoader) { this.plan = plan; this.nativeLoader = nativeLoader; }

        @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                         ProtectionDomain domain, byte[] original) {
            byte[] replacement = plan.replacements.get(name);
            if (redefining != null) return null;
            try {
                if (replacement == null) {
                    if (name != null && name.startsWith("pzopt/") && !name.startsWith("pzopt/zombiebuddy/")
                            && loader == nativeLoader && source(domain).equals(plan.helperJar)) {
                        return HelperArchiveBarrier.transform(original);
                    }
                    return null;
                }
                if (loader != nativeLoader || !source(domain).equals(plan.gameJar)) throw new IOException("native provenance differs");
                if (!hash(original).equals(plan.stockHashes.get(name))) throw new IOException("earlier non-retransformable transformer changed native input");
                return replacement.clone();
            } catch (Exception failure) {
                installed = false;
                state = "FAILED during native definition " + name + ": " + failure;
                System.err.println("[pzopt/ZombieBuddy] " + state + "; target definition is stopped, fully exit and inspect loader order");
                // Exceptions from ClassFileTransformer are swallowed by the JVM. Invalid class bytes
                // deliberately stop this definition instead of silently running a partial payload.
                return new byte[] { 0 };
            }
        }
    }
}
