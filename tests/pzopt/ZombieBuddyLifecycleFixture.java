package pzopt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs with the managed profile resource and compiled production helpers on its classpath. */
public final class ZombieBuddyLifecycleFixture {
   public static void main(String[] args) throws Exception {
      if (!LegacyInstallGuard.managed()) {
         throw new AssertionError("fixture must include the adapter's profile resource");
      }
      Path disposable = Path.of(args[0]);
      Files.createDirectories(disposable);
      Path sentinel = disposable.resolve("foreign-sentinel.txt");
      Files.writeString(sentinel, "preserve");
      if (Updater.canInstall()) throw new AssertionError("legacy install admitted");
      Updater.check();
      if (!Updater.state().equals("error") || !Updater.message().contains("ZombieBuddy preload")) {
         throw new AssertionError("managed update explanation missing");
      }
      if (Updater.install()) throw new AssertionError("legacy updater started");
      if (Uninstall.unavailableReason().isEmpty() || Uninstall.start()) throw new AssertionError("legacy removal started");
      if (BootRepair.run(new String[0])) throw new AssertionError("legacy repair started");
      java.io.File own = java.nio.file.Path.of(LegacyInstallGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile();
      if (!LegacyInstallGuard.owns(own)) throw new AssertionError("exact managed JAR not recognized");
      var hits = new java.util.ArrayList<ModCompat.Hit>();
      var shadowed = java.util.Set.of("zombie/fixture/Target");
      var edited = java.util.Map.of("zombie/fixture/Target", java.util.Map.of("value", new String[] { "fixtureFeature" }));
      ModCompat.scanJar("PZ_Optimization", own, shadowed, edited, hits);
      if (!hits.isEmpty()) throw new AssertionError("own adapter treated as a foreign conflict");
      if (args.length > 1) {
         java.io.File foreign = new java.io.File(args[1]);
         if (LegacyInstallGuard.owns(foreign)) throw new AssertionError("unrelated JAR trusted by name");
         ModCompat.scanJar("foreign", foreign, shadowed, edited, hits);
         if (hits.isEmpty()) throw new AssertionError("real foreign patch ignored");
      }
      try {
         Updater.applyPrepared(null, disposable, "fixture", "fixture");
         throw new AssertionError("low-level legacy apply admitted");
      } catch (IOException expected) {
         if (!expected.getMessage().contains("ZombieBuddy preload")) throw expected;
      }
      try (var entries = Files.list(disposable)) {
         if (entries.count() != 1 || !Files.readString(sentinel).equals("preserve")) {
            throw new AssertionError("managed refusal changed a target file");
         }
      }
      System.out.println("PASS managed legacy updater/install/repair/removal refusal, exact self identity, foreign conflicts retained, no target writes");
   }
}

