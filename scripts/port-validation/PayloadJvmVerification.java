import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Validation-only initial-definition transformer. The actual signed game JAR
 * defines native classes; only their bytes are replaced, as an instrumentation
 * adapter would do. No custom signer metadata or game initialization is used.
 * This checks JVM verification, not ZombieBuddy transformer composition.
 */
public final class PayloadJvmVerification {
    private static final String EXPECTED_JAR = "3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287";
    private static final Set<String> PROTECTED = Set.of("zombie/core/Core", "zombie/core/IntegrityReport",
            "zombie/gameStates/MainScreenState", "zombie/network/GameServer");
    private static final Set<String> transformed = ConcurrentHashMap.newKeySet();
    private static final List<String> transformErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static Path payload, gameJar;

    private static boolean protectedName(String name) {
        return PROTECTED.stream().anyMatch(owner -> name.equals(owner) || name.startsWith(owner + "$"));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Set<String> certificates(Certificate[] certificates) throws Exception {
        Set<String> hashes = new TreeSet<>();
        if (certificates != null) for (Certificate certificate : certificates) hashes.add(sha(certificate.getEncoded()));
        return hashes;
    }

    public static void premain(String unused, Instrumentation instrumentation) throws Exception {
        payload = Path.of(System.getProperty("pzopt.verify.payload")).toRealPath();
        gameJar = Path.of(System.getProperty("pzopt.verify.gameJar")).toRealPath();
        if (!sha(Files.readAllBytes(gameJar)).equals(EXPECTED_JAR)) throw new IllegalStateException("Unreviewed game JAR");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] original) {
                if (name == null || name.startsWith("pzopt/") || protectedName(name)) return null;
                Path replacement = payload.resolve(name + ".class");
                if (!Files.isRegularFile(replacement)) return null;
                try {
                    CodeSource source = domain == null ? null : domain.getCodeSource();
                    if (redefined != null || source == null
                            || !Path.of(source.getLocation().toURI()).toRealPath().equals(gameJar)) {
                        throw new IllegalStateException("Native definition did not originate from the game JAR: " + name);
                    }
                    transformed.add(name);
                    return Files.readAllBytes(replacement);
                } catch (Exception failure) {
                    transformErrors.add(name + ": " + failure);
                    throw new IllegalStateException(failure);
                }
            }
        }, false);
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    public static void main(String[] args) throws Exception {
        Path result = Path.of(args[0]);
        List<String> names;
        try (var files = Files.walk(payload)) {
            names = files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> payload.relativize(path).toString().replace('\\', '/'))
                    .map(path -> path.substring(0, path.length() - 6)).sorted().toList();
        }
        List<String> errors = new ArrayList<>();
        int checked = 0, nativeClasses = 0, signedNative = 0, protectedClasses = 0;
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        try (JarFile jar = new JarFile(gameJar.toFile(), true)) {
            for (String name : names) {
                try {
                    Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
                    type.getDeclaredConstructors();
                    type.getDeclaredMethods();
                    type.getDeclaredFields();
                    type.getSuperclass();
                    type.getInterfaces();
                    var entry = jar.getJarEntry(name + ".class");
                    if (entry != null) {
                        nativeClasses++;
                        try (var input = jar.getInputStream(entry)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
                        CodeSource source = type.getProtectionDomain().getCodeSource();
                        if (source == null || !Path.of(source.getLocation().toURI()).toRealPath().equals(gameJar))
                            throw new AssertionError("Native class defined outside the signed game JAR");
                        Set<String> originalSigners = certificates(entry.getCertificates());
                        if (!originalSigners.equals(certificates(source.getCertificates())))
                            throw new AssertionError("Original game signers changed");
                        if (!originalSigners.isEmpty()) signedNative++;
                        if (protectedName(name)) {
                            protectedClasses++;
                            if (transformed.contains(name)) throw new AssertionError("Protected reporting class was transformed");
                        } else if (!transformed.contains(name)) throw new AssertionError("Expected replacement was not applied");
                    } else {
                        if (!name.startsWith("pzopt/")) throw new AssertionError("New helper in a native package");
                        if (!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(payload))
                            throw new AssertionError("Mod helper loaded from another implementation");
                    }
                    checked++;
                } catch (Throwable failure) {
                    errors.add(name + ": " + failure);
                }
            }
        }
        errors.addAll(transformErrors);
        String report = "{\"passed\":" + errors.isEmpty() + ",\"classCount\":" + names.size()
                + ",\"checked\":" + checked + ",\"nativeClasses\":" + nativeClasses
                + ",\"signedNativeClasses\":" + signedNative + ",\"transformedNativeClasses\":" + transformed.size()
                + ",\"protectedNativeClasses\":" + protectedClasses + ",\"gameMainCalled\":false"
                + ",\"classInitializationRequested\":false,\"signersComparedToVerifiedJarEntries\":true"
                + ",\"zombieBuddyCompositionTest\":false,\"errors\":["
                + String.join(",", errors.stream().map(PayloadJvmVerification::quoted).toList()) + "]}";
        Files.writeString(result, report + "\n");
        System.out.println("PAYLOAD_JVM_VERIFY " + (errors.isEmpty() ? "PASS" : "FAIL") + " checked=" + checked
                + "/" + names.size() + " signedNative=" + signedNative + " transformed=" + transformed.size()
                + " errors=" + errors.size());
        errors.stream().limit(30).forEach(System.out::println);
        if (!errors.isEmpty()) System.exit(1);
    }
}
