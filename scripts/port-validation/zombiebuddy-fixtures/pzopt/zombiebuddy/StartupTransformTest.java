package pzopt.zombiebuddy;

import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Static check against the actual native main screen; no game classes are defined or initialized. */
public final class StartupTransformTest {
    public static void main(String[] args) throws Exception {
        byte[] original;
        try (JarFile game = new JarFile(args[0])) {
            try (var input = game.getInputStream(game.getJarEntry(StartupTransform.TARGET + ".class"))) {
                original = input.readAllBytes();
            }
        }
        byte[] transformed = StartupTransform.transform(original);
        Map<String, MethodModel> before = methods(original), after = methods(transformed);
        if (!before.keySet().equals(after.keySet())) throw new AssertionError("native method inventory changed");
        Set<String> allowed = Set.of("main([Ljava/lang/String;)V", "exit()V", "update()Lzombie/gameStates/GameStateMachine$StateAction;");
        int untouched = 0, changed = 0;
        for (String name : before.keySet()) {
            var left = before.get(name).code(); var right = after.get(name).code();
            if (left.isPresent() != right.isPresent()) throw new AssertionError("native code attribute changed: " + name);
            if (left.isEmpty()) continue;
            boolean equal = Arrays.equals(((CodeAttribute)left.get()).codeArray(), ((CodeAttribute)right.get()).codeArray());
            if (allowed.contains(name)) {
                if (equal) throw new AssertionError("performance hook missing: " + name);
                changed++;
            } else {
                if (!equal) throw new AssertionError("unrelated native method changed: " + name);
                untouched++;
            }
        }
        if (changed != 3) throw new AssertionError("expected exactly three changed startup methods");
        if (!PayloadRuntime.protectedTarget("zombie/core/Core$Any")
                || !PayloadRuntime.protectedTarget("zombie/core/IntegrityReport")
                || !PayloadRuntime.protectedTarget("zombie/network/GameServer$Any")
                || !PayloadRuntime.protectedTarget(StartupTransform.TARGET)) throw new AssertionError("protected family gate missing");
        Files.write(Path.of(args[1]), transformed);
        System.out.println("PASS native startup: exactly 3 performance-hook methods changed; " + untouched
                + " other method code arrays, including integrity/version reporting and class initialization, unchanged");
    }
    private static Map<String, MethodModel> methods(byte[] bytes) {
        Map<String, MethodModel> result = new LinkedHashMap<>();
        for (var method : ClassFile.of().parse(bytes).methods()) {
            result.put(method.methodName().stringValue() + method.methodType().stringValue(), method);
        }
        return result;
    }
}
