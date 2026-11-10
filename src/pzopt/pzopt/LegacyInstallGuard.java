package pzopt;

/** Prevents the legacy loose-file installer from mutating an instrumentation-managed installation. */
public final class LegacyInstallGuard {
   private static final boolean MANAGED = LegacyInstallGuard.class.getResource(
         "/META-INF/pzopt-zombiebuddy/profile.properties") != null;

   private LegacyInstallGuard() { }

   public static boolean managed() {
      return MANAGED;
   }

   /** Exact package identity only; unrelated mods with a similar name must still be scanned. */
   static boolean owns(java.io.File jar) {
      if (!MANAGED) return false;
      try {
         var source = LegacyInstallGuard.class.getProtectionDomain().getCodeSource();
         return source != null && java.nio.file.Path.of(source.getLocation().toURI()).toRealPath()
               .equals(jar.toPath().toRealPath());
      } catch (Exception error) {
         return false;
      }
   }

   /** Exact reviewed loader bytes only; a filename or mod ID never establishes compatibility. */
   static boolean reviewedBuddyJar(java.io.File jar) {
      if (!MANAGED || !jar.isFile() || jar.length() != 13254999L) return false;
      try (var input = java.nio.file.Files.newInputStream(jar.toPath())) {
         var digest = java.security.MessageDigest.getInstance("SHA-256");
         byte[] buffer = new byte[65536];
         int count;
         while ((count = input.read(buffer)) != -1) if (count > 0) digest.update(buffer, 0, count);
         return "eb79b9876332010733a8e0d7ce4fe0377846059a9e28859029e2a0fb449f6cf2"
               .equals(java.util.HexFormat.of().formatHex(digest.digest()));
      } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
         return false;
      }
   }

   public static String reason() {
      return "This build is managed by ZombieBuddy preload. Update its mod package while the game is closed; "
            + "run the supplied install-zombiebuddy.py undo before disabling or removing its JAR, then fully restart. "
            + "The early Java agent remains installed until that undo; the legacy game-folder installer is unavailable.";
   }
}
