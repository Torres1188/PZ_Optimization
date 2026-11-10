package pzopt.zombiebuddy;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/** Adds only the three reviewed performance hooks to the current native main screen. */
final class StartupTransform {
    static final String TARGET = "zombie/gameStates/MainScreenState";
    private static final ClassDesc TILE_DEFS = ClassDesc.of("pzopt.TileDefPreload");
    private static final ClassDesc OPTIONS = ClassDesc.of("pzopt.UserOptions");
    private static final ClassDesc FILE_SYSTEM = ClassDesc.of("zombie.ZomboidFileSystem");
    private static final ClassDesc HOOKS = ClassDesc.of("pzopt.zombiebuddy.StartupHooks");
    private StartupTransform() { }

    static byte[] transform(byte[] original) throws IOException {
        ClassFile file = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(StartupTransform.class.getClassLoader()).cached()));
        var model = file.parse(original);
        int[] main = {0}, update = {0}, exit = {0};
        CodeTransform mainHook = (builder, element) -> {
            if (element instanceof InvokeInstruction call && call.owner().asInternalName().equals(TARGET)
                    && call.name().equalsString("printSpecs") && call.type().equalsString("()V")) {
                builder.getstatic(FILE_SYSTEM, "instance", FILE_SYSTEM)
                        .invokevirtual(FILE_SYSTEM, "getCacheDir", MethodTypeDesc.of(ConstantDescs.CD_String))
                        .invokestatic(OPTIONS, "checkCacheDir", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
                main[0]++;
            }
            builder.with(element);
        };
        CodeTransform updateHook = new CodeTransform() {
            @Override public void atStart(CodeBuilder builder) {
                builder.invokestatic(TILE_DEFS, "bindSome", MethodTypeDesc.of(ConstantDescs.CD_void));
                update[0]++;
            }
            @Override public void accept(CodeBuilder builder, CodeElement element) { builder.with(element); }
        };
        CodeTransform exitHook = (builder, element) -> {
            builder.with(element);
            if (exit[0] == 0 && element instanceof InvokeInstruction call
                    && call.owner().asInternalName().equals("java/util/Calendar")
                    && call.name().equalsString("getTimeInMillis") && call.type().equalsString("()J")) {
                builder.aload(0).invokestatic(HOOKS, "beforeFade", MethodTypeDesc.of(ConstantDescs.CD_long,
                        ConstantDescs.CD_long, ConstantDescs.CD_Object));
                exit[0]++;
            }
        };
        byte[] result = file.transformClass(model, (builder, element) -> {
            if (element instanceof MethodModel method) {
                String signature = method.methodName().stringValue() + method.methodType().stringValue();
                CodeTransform hook = switch (signature) {
                    case "main([Ljava/lang/String;)V" -> mainHook;
                    case "update()Lzombie/gameStates/GameStateMachine$StateAction;" -> updateHook;
                    case "exit()V" -> exitHook;
                    default -> null;
                };
                if (hook != null) { builder.transformMethod(method, MethodTransform.transformingCode(hook)); return; }
            }
            builder.with(element);
        });
        if (main[0] != 1 || update[0] != 1 || exit[0] != 1) {
            throw new IOException("native startup hook shape differs: main=" + main[0] + ", update=" + update[0] + ", exit=" + exit[0]);
        }
        var errors = file.verify(result);
        if (!errors.isEmpty()) throw new IOException("native startup hook verification failed: " + errors);
        return result;
    }
}
