package pzopt.zombiebuddy;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

/** Runs after the real official agent, without entering the game main method. */
public final class FullPayloadFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--baseline")) {
            Files.writeString(Path.of(args[1]), "{\"baseline\":true}\n");
            return;
        }
        if (args[0].equals("--expect-order-refusal")) {
            if (PayloadRuntime.active() || !PayloadRuntime.status().contains("must precede")) {
                throw new AssertionError("late bootstrap was not refused before payload registration: " + PayloadRuntime.status());
            }
            Files.writeString(Path.of(args[1]), "{\"orderRefusal\":true}\n");
            System.out.println("PASS official-agent-first order refused before payload registration");
            return;
        }
        Path mod = Path.of(args[0]).toRealPath(), gamePath = Path.of(args[1]).toRealPath();
        Path result = Path.of(args[2]);
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        // Exercise the ordinary approved lifecycle entry after the early bootstrap.
        PayloadRuntime.installFromZombieBuddy();
        if (!PayloadRuntime.active()) throw new AssertionError(PayloadRuntime.status());
        List<String> errors = new ArrayList<>(); int checked = 0, nativeCount = 0, signed = 0;
        try (JarFile archive = new JarFile(mod.toFile()); JarFile game = new JarFile(gamePath.toFile(), true)) {
            Set<String> names = new TreeSet<>();
            archive.stream().filter(entry -> entry.getName().endsWith(".class")).forEach(entry -> {
                String name = entry.getName();
                if (name.startsWith(PayloadRuntime.PAYLOAD)) name = name.substring(PayloadRuntime.PAYLOAD.length());
                names.add(name.substring(0, name.length() - 6));
            });
            names.add(StartupTransform.TARGET);
            for (String name : names) {
                try {
                    Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
                    type.getDeclaredConstructors(); type.getDeclaredMethods(); type.getDeclaredFields();
                    type.getSuperclass(); type.getInterfaces();
                    var base = game.getJarEntry(name + ".class");
                    if (base != null) {
                        nativeCount++;
                        try (var input = game.getInputStream(base)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
                        var origin = type.getProtectionDomain().getCodeSource();
                        if (origin == null || !Path.of(origin.getLocation().toURI()).toRealPath().equals(gamePath)) {
                            throw new AssertionError("native JAR provenance differs");
                        }
                        Set<String> expected = certs(base.getCertificates()), actual = certs(origin.getCertificates());
                        if (!expected.equals(actual)) throw new AssertionError("native signer set differs");
                        if (!actual.isEmpty()) signed++;
                    }
                    checked++;
                } catch (Throwable failure) {
                    errors.add(name + ": " + failure);
                }
            }
            String json = "{\n  \"checked\":" + checked + ",\n  \"inventory\":" + names.size()
                    + ",\n  \"nativeClasses\":" + nativeCount + ",\n  \"signedNativeClasses\":" + signed
                    + ",\n  \"errors\":[" + errors.stream().map(FullPayloadFixture::quote).collect(java.util.stream.Collectors.joining(","))
                    + "],\n  \"status\":" + quote(PayloadRuntime.status()) + "\n}\n";
            Files.writeString(result, json);
            if (!errors.isEmpty() || checked != names.size()) throw new AssertionError("full payload verification failed: " + errors);
            System.out.println("PASS full payload after actual official ZombieBuddy Agent.premain: " + checked + " definitions; " + signed + " original signed native definitions");
        }
    }
    private static Set<String> certs(java.security.cert.Certificate[] values) throws Exception {
        Set<String> result = new TreeSet<>();
        if (values != null) for (var cert : values) result.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        return result;
    }
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
