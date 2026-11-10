package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Managed cache plans preserve startup hooks, native origin and independently owned JVM options. */
public final class ManagedAotCacheTest {
   static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
   static boolean has(JSONArray values, String value) {
      for (int i = 0; i < values.length(); i++) if (value.equals(values.getString(i))) return true;
      return false;
   }
   public static void main(String[] args) throws Exception {
      Path game = Files.createTempDirectory("pzopt-managed-aot-test-");
      Path nativeJar = Files.writeString(game.resolve("projectzomboid.jar"), "fixture native jar");
      Path own = Files.writeString(game.resolve("optimization.jar"), "fixture helper jar");
      Files.writeString(game.resolve("another.jar"), "fixture independent jar");
      JSONObject original = new JSONObject("{\"classpath\":[\".\",\"projectzomboid.jar\",\"another.jar\"],"
            + "\"mainClass\":\"native.Main\",\"vmArgs\":[\"-javaagent:ZombieBuddy.jar\",\"-Xmx7g\",\"-Dother=kept\",\"-XX:+UnlockDiagnosticVMOptions\"],"
            + "\"windows\":{\"10\":{\"vmArgs\":[\"-XX:+UseZGC\"]}}}");
      JSONObject record = ManagedAotCache.plan(original, game, own, false, true);
      check(record.getJSONArray("classpath").getString(0).equals(nativeJar.toRealPath().toString()), "native jar first");
      check(record.getJSONArray("classpath").getString(1).equals(own.toRealPath().toString()), "helper is a JAR");
      check(has(record.getJSONArray("classpath"), "another.jar"), "independent classpath preserved");
      check(!has(record.getJSONArray("classpath"), "."), "JAR-only cache path");
      for (String value : new String[] {"-javaagent:ZombieBuddy.jar", "-Xmx7g", "-Dother=kept"})
         check(has(record.getJSONArray("vmArgs"), value), "preserved " + value);
      check(record.getJSONObject("windows").toString().equals(original.getJSONObject("windows").toString()), "platform GC settings preserved");
      check(has(original.getJSONArray("classpath"), "."), "input not mutated");
      JSONObject use = ManagedAotCache.plan(record, game, own, true, true);
      check(has(use.getJSONArray("vmArgs"), "-XX:AOTCache=" + game.resolve(ManagedAotCache.DIRECTORY).resolve("pzopt-zombiebuddy.aot")), "record to use");
      ManagedAotCache.removeOwnedArguments(use, game);
      check(!ManagedAotCache.owned(use), "owned options removed");
      check(has(use.getJSONArray("vmArgs"), "-XX:+UnlockDiagnosticVMOptions"), "user diagnostic option survives cleanup");
      check(!has(use.getJSONArray("vmArgs"), "-XX:-AOTInvokeDynamicLinking"), "only our added no-indy option removed");
      Path log = game.resolve("usage.log");
      Files.writeString(log, "[info][aot] Using AOT-linked classes: true\n");
      check(ManagedAotCache.cacheWasUsed(log), "actual JVM usage accepted");
      Files.writeString(log, "[info][aot] Using AOT-linked classes: false\n");
      check(!ManagedAotCache.cacheWasUsed(log), "JVM fallback triggers recording again");
      JSONObject conflict = new JSONObject(original.toString());
      conflict.getJSONArray("vmArgs").put("-XX:+AOTInvokeDynamicLinking");
      try { ManagedAotCache.plan(conflict, game, own, false, true); throw new AssertionError("JVM preference conflict accepted"); }
      catch (java.io.IOException expected) { }
      Path foreign = game.resolve("pzopt/ManagedAotCacheTest.class");
      Files.createDirectories(foreign.getParent());
      try (var input = ManagedAotCacheTest.class.getResourceAsStream("/pzopt/ManagedAotCacheTest.class")) { Files.copy(input, foreign); }
      try { ManagedAotCache.plan(original, game, own, false, true); throw new AssertionError("foreign loose classes dropped"); }
      catch (java.io.IOException expected) { }
      System.out.println("ManagedAotCacheTest PASS native-first/agents/GC/heap/other-mods/owned-cleanup/refusals");
   }
}
