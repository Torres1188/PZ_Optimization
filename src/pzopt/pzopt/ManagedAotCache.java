package pzopt;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native-JAR-first AOT cycle for the approved ZombieBuddy adapter; no loose-class archive is built. */
public final class ManagedAotCache {
   static final String DIRECTORY = "pzopt/aot-zombiebuddy";
   static final String MARKER = "-Dpzopt.zombiebuddyAot=true";
   static final String INDY_MARKER = "-Dpzopt.zombiebuddyAotAddedNoIndy=true";
   static final String UNLOCK_MARKER = "-Dpzopt.zombiebuddyAotAddedUnlock=true";
   private static final String BACKUP = "ProjectZomboid64.json.pzopt-zombiebuddy-aot-backup";

   private ManagedAotCache() { }

   static void step() throws Exception {
      Path game = Updater.gameDir().toRealPath();
      Path json = game.resolve("ProjectZomboid64.json");
      if (!Files.isRegularFile(json)) return;
      if (!Config.AOT_CACHE || !Overrides.enabled()
            || HarnessFlags.get("mode") != null && !Config.DEV_AOT_CACHE_HARNESS) {
         if (reset(game)) Log.info("aot: managed cache disabled; native-first classpath and loader preserved");
         return;
      }
      JSONObject launcher = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
      if (hasForeignAotArguments(launcher, game)) {
         Log.warn("aot: existing foreign/legacy AOT configuration preserved; restore its owner before enabling managed AOT");
         return;
      }
      Path helper = Path.of(LegacyInstallGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
      if (!helper.getFileName().toString().endsWith(".jar")) throw new IOException("managed helper is not a packaged JAR");
      Path directory = game.resolve(DIRECTORY);
      if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent())
            || Files.exists(directory.getParent()) && !directory.getParent().toRealPath().startsWith(game)) {
         throw new IOException("redirected managed AOT directory");
      }
      Files.createDirectories(directory);
      if (!directory.toRealPath().startsWith(game)) throw new IOException("redirected managed AOT directory");
      Path cache = directory.resolve("pzopt-zombiebuddy.aot");
      Path stateFile = directory.resolve("state.properties");
      Properties state = new Properties();
      if (Files.isRegularFile(stateFile)) try (var input = Files.newInputStream(stateFile)) { state.load(input); }
      String fingerprint = fingerprint(game, helper, launcher);
      boolean same = fingerprint.equals(state.getProperty("fingerprint"));
      List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
      boolean recording = arguments.contains("-XX:AOTCacheOutput=" + cache);
      boolean using = arguments.contains("-XX:AOTCache=" + cache);
      boolean useNext = same && (recording || using && Files.isRegularFile(cache) && cacheWasUsed(directory.resolve("aot.log")));
      JSONObject next = plan(launcher, game, helper, useNext, Config.AOT_NO_INDY);
      if (!next.toString().equals(launcher.toString())) save(game, next);
      state.setProperty("fingerprint", fingerprint);
      state.setProperty("mode", useNext ? "use" : "record");
      Path temporary = Files.createTempFile(directory, "state-", ".tmp");
      try (var output = Files.newOutputStream(temporary)) { state.store(output, "PZ Optimization ZombieBuddy AOT identity"); }
      AotCache.replaceJson(temporary, stateFile);
      Log.info("aot: managed next launch=" + (useNext ? "use" : "record")
            + "; native jar first, loader agents preserved; the JVM caches eligible classes and skips transformed targets");
   }

   /** Pure launcher plan, shared by the original preference and tested with real files in isolated fixtures. */
   static JSONObject plan(JSONObject original, Path game, Path helper, boolean use, boolean noIndy) throws IOException {
      JSONObject result = new JSONObject(original.toString());
      Path nativeJar = game.resolve("projectzomboid.jar").toRealPath();
      Path ownJar = helper.toRealPath();
      LinkedHashSet<String> paths = new LinkedHashSet<>();
      paths.add(nativeJar.toString());
      paths.add(ownJar.toString());
      JSONArray previous = result.getJSONArray("classpath");
      for (int i = 0; i < previous.length(); i++) {
         String value = previous.getString(i);
         Path entry = game.resolve(value).normalize();
         if (value.equals(".")) {
            if (bindableClasses(game)) throw new IOException("AOT leaves a foreign loose-class installation unchanged");
            continue;
         }
         if (!Files.isRegularFile(entry) || !entry.getFileName().toString().endsWith(".jar")) {
            throw new IOException("AOT requires a JAR-only classpath; left unchanged: " + value);
         }
         Path real = entry.toRealPath();
         if (!real.equals(nativeJar) && !real.equals(ownJar)) paths.add(value);
      }
      result.put("classpath", new JSONArray(paths));
      removeOwnedArguments(result, game);
      JSONArray args = result.optJSONArray("vmArgs");
      if (args == null) { args = new JSONArray(); result.put("vmArgs", args); }
      args.put(MARKER);
      if (noIndy) {
         if (contains(args, "-XX:-UnlockDiagnosticVMOptions") || contains(args, "-XX:+AOTInvokeDynamicLinking")) {
            throw new IOException("Explicit JVM flags conflict with aotNoIndy; launcher left unchanged");
         }
         if (!contains(args, "-XX:+UnlockDiagnosticVMOptions")) {
            args.put("-XX:+UnlockDiagnosticVMOptions"); args.put(UNLOCK_MARKER);
         }
         if (!contains(args, "-XX:-AOTInvokeDynamicLinking")) {
            args.put("-XX:-AOTInvokeDynamicLinking"); args.put(INDY_MARKER);
         }
      }
      Path directory = game.resolve(DIRECTORY);
      args.put((use ? "-XX:AOTCache=" : "-XX:AOTCacheOutput=") + directory.resolve("pzopt-zombiebuddy.aot"));
      args.put("-Xlog:aot=info:file=" + directory.resolve("aot.log") + "::filecount=0");
      return result;
   }

   static boolean reset(Path game) throws IOException {
      Path path = game.resolve("ProjectZomboid64.json");
      if (!Files.isRegularFile(path)) return false;
      JSONObject json = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
      if (!owned(json)) return false;
      removeOwnedArguments(json, game);
      save(game, json);
      return true;
   }

   static boolean owned(JSONObject json) {
      final boolean[] found = { false };
      visitArguments(json, args -> {
         for (int i = 0; i < args.length(); i++) if (MARKER.equals(args.optString(i))) found[0] = true;
      });
      return found[0];
   }

   private static boolean hasForeignAotArguments(JSONObject json, Path game) {
      final boolean[] found = { false };
      boolean managed = owned(json);
      String cache = game.resolve(DIRECTORY).resolve("pzopt-zombiebuddy.aot").toString();
      visitArguments(json, args -> {
         for (int i = 0; i < args.length(); i++) {
            String value = args.optString(i);
            if (value.startsWith("-XX:AOT") && !(managed
                  && (value.equals("-XX:AOTCache=" + cache) || value.equals("-XX:AOTCacheOutput=" + cache)))) found[0] = true;
         }
      });
      return found[0];
   }

   /** Cache existence is insufficient: the JVM may reject an incompatible/corrupt cache and fall back. */
   static boolean cacheWasUsed(Path log) throws IOException {
      if (!Files.isRegularFile(log)) return false;
      boolean used = false;
      try (var reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
         String line;
         for (int n = 0; n < 20000 && (line = reader.readLine()) != null; n++) {
            if (line.contains("Using AOT-linked classes:")) used = line.contains("Using AOT-linked classes: true");
            if (line.contains("Specified AOT cache not found")) used = false;
         }
      }
      return used;
   }

   static void removeOwnedArguments(JSONObject json, Path game) {
      if (!owned(json)) return;
      String cache = game.resolve(DIRECTORY).resolve("pzopt-zombiebuddy.aot").toString();
      String log = "-Xlog:aot=info:file=" + game.resolve(DIRECTORY).resolve("aot.log") + "::filecount=0";
      visitArguments(json, args -> {
         // Only our marker and exact scoped options are removed. Preserve user agents, GC, heap and other AOT owners.
         if (contains(args, INDY_MARKER)) removeLast(args, "-XX:-AOTInvokeDynamicLinking");
         if (contains(args, UNLOCK_MARKER)) removeLast(args, "-XX:+UnlockDiagnosticVMOptions");
         for (int i = args.length() - 1; i >= 0; i--) {
            String value = args.optString(i);
            if (value.equals(MARKER) || value.equals(INDY_MARKER) || value.equals(UNLOCK_MARKER) || value.equals("-XX:AOTCache=" + cache)
                  || value.equals("-XX:AOTCacheOutput=" + cache) || value.equals(log)
                  ) args.remove(i);
         }
      });
   }

   private static boolean contains(JSONArray args, String value) {
      for (int i = 0; i < args.length(); i++) if (value.equals(args.optString(i))) return true;
      return false;
   }

   private static void removeLast(JSONArray args, String value) {
      for (int i = args.length() - 1; i >= 0; i--) if (value.equals(args.optString(i))) { args.remove(i); return; }
   }

   private static void visitArguments(JSONObject json, java.util.function.Consumer<JSONArray> action) {
      JSONArray args = json.optJSONArray("vmArgs");
      if (args != null) action.accept(args);
      JSONObject windows = json.optJSONObject("windows");
      if (windows != null) for (String key : windows.keySet()) {
         JSONObject section = windows.optJSONObject(key);
         if (section != null) { JSONArray nested = section.optJSONArray("vmArgs"); if (nested != null) action.accept(nested); }
      }
   }

   private static boolean bindableClasses(Path root) throws IOException {
      try (var paths = Files.walk(root)) {
         for (Path path : paths.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p)).toList()) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            try {
               String binary = ClassFile.of().parse(Files.readAllBytes(path)).thisClass().asInternalName() + ".class";
               if (relative.equals(binary)) return true;
            } catch (IllegalArgumentException invalid) { throw new IOException("unreviewed loose class: " + path, invalid); }
         }
      }
      return false;
   }

   private static String fingerprint(Path game, Path helper, JSONObject launcher) throws Exception {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path path : List.of(game.resolve("projectzomboid.jar"), helper)) updateFile(digest, path);
      Properties profile = new Properties();
      try (var stream = ManagedAotCache.class.getResourceAsStream("/META-INF/pzopt-zombiebuddy/profile.properties")) {
         if (stream == null) throw new IOException("managed loader profile absent");
         profile.load(stream);
      }
      digest.update(profile.getProperty("zombiebuddy.sha256", "missing").getBytes(StandardCharsets.UTF_8));
      digest.update(System.getProperty("java.runtime.version", "").getBytes(StandardCharsets.UTF_8));
      digest.update(System.getProperty("java.vm.version", "").getBytes(StandardCharsets.UTF_8));
      digest.update(System.getProperty("java.home", "").getBytes(StandardCharsets.UTF_8));
      digest.update(Boolean.toString(Config.AOT_NO_INDY).getBytes(StandardCharsets.UTF_8));
      for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
         if (argument.startsWith("-XX:AOT") || argument.startsWith("-Xlog:aot=") || argument.equals(MARKER)
               || argument.equals(INDY_MARKER) || argument.equals(UNLOCK_MARKER)
               || argument.equals("-XX:+UnlockDiagnosticVMOptions") || argument.equals("-XX:-AOTInvokeDynamicLinking")) continue;
         digest.update((argument + "\n").getBytes(StandardCharsets.UTF_8));
      }
      // Preserve explicit extra classpath JARs and invalidate when their contents change.
      JSONArray cp = launcher.getJSONArray("classpath");
      for (int i = 0; i < cp.length(); i++) {
         Path path = game.resolve(cp.getString(i)).normalize();
         if (Files.isRegularFile(path) && path.toString().endsWith(".jar")
               && !path.toRealPath().equals(helper) && !path.toRealPath().equals(game.resolve("projectzomboid.jar").toRealPath())) updateFile(digest, path);
      }
      return HexFormat.of().formatHex(digest.digest());
   }

   private static void updateFile(MessageDigest digest, Path path) throws IOException {
      digest.update(path.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
      try (var input = Files.newInputStream(path)) {
         byte[] buffer = new byte[65536]; int n;
         while ((n = input.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
      }
   }

   private static void save(Path game, JSONObject json) throws IOException {
      Path launcher = game.resolve("ProjectZomboid64.json");
      if (Files.isSymbolicLink(launcher)) throw new IOException("redirected launcher");
      Path backup = game.resolve(BACKUP);
      if (!Files.exists(backup)) Files.copy(launcher, backup);
      Path temporary = Files.createTempFile(game, "pzopt-zombiebuddy-aot-", ".tmp");
      Files.writeString(temporary, json.toString(1) + "\n", StandardCharsets.UTF_8);
      new JSONObject(Files.readString(temporary, StandardCharsets.UTF_8));
      AotCache.replaceJson(temporary, launcher);
   }
}
