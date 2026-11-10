package pzopt.zombiebuddy;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-time early agent entry, before official ZombieBuddy warms its Lua classes.
 * The normal approved ZombieBuddy PreMain remains an idempotent status/lifecycle entry.
 */
public final class Bootstrap {
    private Bootstrap() { }

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            Path own = PayloadRuntime.source(Bootstrap.class.getProtectionDomain());
            var nativeResource = loader.getResource("zombie/GitVersion.class");
            if (nativeResource == null || !(nativeResource.openConnection() instanceof JarURLConnection nativeConnection)) {
                throw new IOException("native game identity resolves outside a JAR; remove legacy loose classes");
            }
            Path game = Path.of(nativeConnection.getJarFileURL().toURI()).toRealPath();
            Path plannedBuddy = plannedBuddy(own);
            PayloadRuntime.install(instrumentation, own, game, plannedBuddy, loader);
        } catch (Exception | LinkageError failure) {
            PayloadRuntime.refused("early bootstrap", failure);
        }
    }

    private static Path plannedBuddy(Path own) throws Exception {
        Path configured = null;
        String explicit = System.getProperty("pzopt.zombiebuddy.jar");
        if (explicit != null && !explicit.isBlank()) configured = Path.of(explicit).toRealPath();
        boolean ownSeen = false;
        Path found = null;
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith("-javaagent:")) continue;
            String value = argument.substring("-javaagent:".length());
            int separator = value.indexOf('=');
            Path path = Path.of(separator < 0 ? value : value.substring(0, separator)).toRealPath();
            if (Files.isSameFile(path, own)) { ownSeen = true; continue; }
            String agentHash = PayloadRuntime.hash(path);
            if ((configured != null && Files.isSameFile(path, configured))
                    || path.getFileName().toString().equalsIgnoreCase("ZombieBuddy.jar")
                    || PayloadRuntime.ZB_234_SHA256.equals(agentHash)) {
                if (!PayloadRuntime.ZB_234_SHA256.equals(agentHash)) {
                    throw new IOException("planned ZombieBuddy agent is not the reviewed official 2.3.4 artifact");
                }
                if (!ownSeen) throw new IOException("PZ Optimization bootstrap must precede the ZombieBuddy -javaagent argument");
                if (found != null && !Files.isSameFile(found, path)) throw new IOException("multiple ZombieBuddy agents are ambiguous");
                found = path;
            }
        }
        if (found != null) return found;
        throw new IOException("no reviewed ZombieBuddy agent is planned; put this -javaagent before official ZombieBuddy, "
                + "using the reviewed Java-agent route; native-agent bootstrap ordering is not qualified");
    }
}
