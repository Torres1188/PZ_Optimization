import java.lang.reflect.Method;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** CPU-only adversarial checks for the donor audit's direct network-AI null-guard proof. */
public final class AuditNpcGuardTest {
    private static final String CHARACTER = "zombie/characters/IsoGameCharacter";
    private static final String NETWORK = "zombie/characters/NetworkCharacterAI";

    private static MethodNode fixture(int checkedLocal, int receiverLocal, boolean guarded) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "pzoptRestContext", "()V", null, null);
        LabelNode after = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CHARACTER, "getNetworkCharacterAI", "()L" + NETWORK + ";", false));
        if (guarded) {
            method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, checkedLocal));
            method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, after));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        }
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NETWORK, "postUpdate", "()V", false));
        method.instructions.add(after);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static void expect(Method proof, MethodNode method, boolean expected, String message) throws Exception {
        boolean found = proof.invoke(null, method) != null;
        if (found != expected) throw new AssertionError(message + ": expected=" + expected + ", found=" + found);
    }

    public static void main(String[] args) throws Exception {
        Method proof = AuditPortPayload.class.getDeclaredMethod("directNpcGuard", MethodNode.class);
        proof.setAccessible(true);
        expect(proof, fixture(1, 1, true), true, "same stored receiver is guarded");
        MethodNode clone = new MethodNode();
        fixture(1, 1, true).accept(clone);
        expect(proof, clone, true, "cloned method without exception handlers is guarded");
        expect(proof, fixture(1, 1, false), false, "old unguarded direct call is rejected");
        expect(proof, fixture(2, 1, true), false, "checking another local does not guard this receiver");
        expect(proof, fixture(1, 2, true), false, "calling another receiver bypasses the check");

        MethodNode unrelated = fixture(1, 1, false);
        LabelNode unrelatedAfter = new LabelNode();
        InsnList prelude = new InsnList();
        prelude.add(new InsnNode(Opcodes.ACONST_NULL));
        prelude.add(new JumpInsnNode(Opcodes.IFNULL, unrelatedAfter));
        prelude.add(unrelatedAfter);
        unrelated.instructions.insert(prelude);
        expect(proof, unrelated, false, "an unrelated null branch is insufficient");

        MethodNode duplicate = fixture(1, 1, true);
        duplicate.instructions.insertBefore(duplicate.instructions.getLast(), new VarInsnNode(Opcodes.ALOAD, 1));
        duplicate.instructions.insertBefore(duplicate.instructions.getLast(),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NETWORK, "postUpdate", "()V", false));
        expect(proof, duplicate, false, "a second unguarded postUpdate is rejected");

        MethodNode bypass = fixture(1, 1, true);
        LabelNode receiverEntry = new LabelNode();
        bypass.instructions.insertBefore(bypass.instructions.get(5), receiverEntry);
        bypass.instructions.insert(new JumpInsnNode(Opcodes.GOTO, receiverEntry));
        expect(proof, bypass, false, "incoming jump cannot enter after the null check");
        System.out.println("AuditNpcGuardTest ok (8 cases)");
    }
}
