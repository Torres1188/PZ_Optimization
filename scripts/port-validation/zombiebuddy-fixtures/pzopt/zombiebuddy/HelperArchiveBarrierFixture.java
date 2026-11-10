package pzopt.zombiebuddy;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

/** Proves the archive barrier changes neither helper schema nor executable code. */
public final class HelperArchiveBarrierFixture {
    public static void main(String[] args) throws Exception {
        int classes = 0, methods = 0;
        ClassFile file = ClassFile.of();
        try (JarFile jar = new JarFile(args[0])) {
            for (var entry : jar.stream().filter(e -> e.getName().startsWith("pzopt/")
                    && !e.getName().startsWith("pzopt/zombiebuddy/") && e.getName().endsWith(".class")).toList()) {
                byte[] original;
                try (var input = jar.getInputStream(entry)) { original = input.readAllBytes(); }
                byte[] changed = HelperArchiveBarrier.transform(original);
                ClassModel before = file.parse(original), after = file.parse(changed);
                if (Arrays.equals(original, changed) || before.flags().flagsMask() != after.flags().flagsMask()
                        || !before.superclass().toString().equals(after.superclass().toString())
                        || before.fields().size() != after.fields().size() || before.methods().size() != after.methods().size()) {
                    throw new AssertionError("helper archive marker changed class shape: " + entry.getName());
                }
                for (int i = 0; i < before.fields().size(); i++) {
                    var a = before.fields().get(i); var b = after.fields().get(i);
                    if (!a.fieldName().equalsString(b.fieldName().stringValue())
                            || !a.fieldType().equalsString(b.fieldType().stringValue()) || a.flags().flagsMask() != b.flags().flagsMask()) {
                        throw new AssertionError("helper field changed: " + entry.getName());
                    }
                }
                for (int i = 0; i < before.methods().size(); i++) {
                    var a = before.methods().get(i); var b = after.methods().get(i);
                    if (!a.methodName().equalsString(b.methodName().stringValue())
                            || !a.methodType().equalsString(b.methodType().stringValue()) || a.flags().flagsMask() != b.flags().flagsMask()
                            || a.code().isPresent() != b.code().isPresent()
                            || a.code().isPresent() && !Arrays.equals(((CodeAttribute)a.code().get()).codeArray(), ((CodeAttribute)b.code().get()).codeArray())) {
                        throw new AssertionError("helper method changed: " + entry.getName() + ":" + a.methodName());
                    }
                    methods++;
                }
                classes++;
            }
        }
        Files.writeString(Path.of(args[1]), "{\"helperClasses\":" + classes + ",\"unchangedMethods\":" + methods + "}\n");
        System.out.println("PASS helper AOT barrier: " + classes + " unchanged schemas; " + methods + " unchanged method code arrays");
    }
}
