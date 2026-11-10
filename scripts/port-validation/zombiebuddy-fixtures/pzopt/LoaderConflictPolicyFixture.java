package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.List;
import java.util.Map;

/** Reads official annotations through the production scanner; no Config/game initialization. */
public final class LoaderConflictPolicyFixture {
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]), report = Path.of(args[1]);
        Path directory = report.getParent().resolve("loader-policy-fixtures");
        Files.createDirectories(directory);
        Path ordinary = directory.resolve("ZombieBuddy.jar"), renamed = directory.resolve("renamed-loader.jar"), altered = directory.resolve("altered-loader.jar");
        Files.copy(original, ordinary); Files.copy(original, renamed);
        byte[] changed = Files.readAllBytes(original);
        boolean editedMetadata = false;
        for (int i = 0; i < changed.length - 16; i++) {
            if (changed[i] == 'P' && changed[i + 1] == 'K' && changed[i + 2] == 1 && changed[i + 3] == 2) {
                changed[i + 12] ^= 1; // Central-directory timestamp: same valid class contents and ZIP length.
                editedMetadata = true; break;
            }
        }
        if (!editedMetadata) throw new AssertionError("fixture ZIP metadata missing");
        Files.write(altered, changed);
        var observations = new StringBuilder();
        check("ordinary", "ZombieBuddy.jar", ordinary.toFile(), Map.of(), true, null, observations);
        check("renamed", "renamed-loader.jar", renamed.toFile(), Map.of(), true, null, observations);
        check("altered", "altered-loader.jar", altered.toFile(), Map.of(), false, null, observations);
        check("explicit source override", "ZombieBuddy.jar", ordinary.toFile(), Map.of("ZombieBuddy.jar", "off:luaPrecompile"), false, "luaPrecompile", observations);
        check("canonical user override", "renamed-loader.jar", renamed.toFile(), Map.of("ZombieBuddy", "off:bootPump"), false, "bootPump", observations);
        check("unknown foreign", "ForeignMod", altered.toFile(), Map.of(), false, null, observations);
        Files.writeString(report, observations.toString());
        System.out.print(observations);
    }

    private static void check(String label, String source, File jar, Map<String, String> user, boolean expectEmpty,
                              String onlyKey, StringBuilder observations) throws Exception {
        var edited = ModCompat.editedMethods();
        var shadowed = new HashSet<String>(edited.keySet());
        var policy = new HashMap<String, String>();
        for (String[] row : ModCompat.KNOWN) policy.put(row[0], row[1]);
        policy.putAll(user);
        ModCompat.applyManagedLoaderPolicy(Map.of(source, List.of(jar)), policy);
        var hits = new ArrayList<ModCompat.Hit>();
        ModCompat.scanJar(source, jar, shadowed, edited, hits);
        var disabled = new Properties();
        ModCompat.applyForTest(hits, edited, policy, disabled, "auto");
        if (hits.size() != 14 || disabled.isEmpty() != expectEmpty
                || onlyKey != null && (disabled.size() != 1 || !"false".equals(disabled.getProperty(onlyKey)))) {
            throw new AssertionError(label + ": hits=" + hits.size() + " disabled=" + disabled + " policy=" + policy);
        }
        observations.append("PASS ").append(label).append(": hits=").append(hits.size()).append(" disabled=").append(disabled).append('\n');
    }
}
