/* Port-validation tool adapted from PZ Optimized scripts/payload/AuditPortPayload.java.
 * Source SHA-256: 40e4c1f0aecad25423df1c871c884710c8fce525cd9eb0fa02a148e88e70ea4d.
 * 2026-11-10: donor namespace pzopt; strict linkage rules retained.
 */
import org.objectweb.asm.*;
import org.objectweb.asm.signature.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Strict, data-only 42.22 class/member linkage audit. Never initializes game classes. */
public final class AuditPortPayload implements AutoCloseable {
    private static final Set<String> GAME_SHAS = Set.of(
            "90c54b84ebc160ae053cbefe30c7713826ca4fdb4934fde53c9ebde0ff9892f3",
            "531daad77c92dcb93534e66ff37becf86dc119f55c62345c622c52021170723e",
            // B42.22.2 / 3d39148846: all 135 retained stock targets match
            // the exact 42.22.1 baseline. Evidence: PZ Optimized's
            // docs/prerelease-3d39148846-review.json (see tool provenance above).
            "3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287");
    private static final String PREFIX = "pzopt/";
    private static final List<String> RETIRED = List.of("UnitTests", "ClimbSheetRopeState", "ClimbDownSheetRopeState", "PZArrayList");
    private final Path root;
    private final ZipFile game;
    private final Map<String, ClassNode> payload = new TreeMap<>(), external = new HashMap<>();
    private final Map<String, byte[]> bytes = new TreeMap<>();
    private final Set<String> unavailable = new TreeSet<>(), errorKeys = new HashSet<>();
    private final List<Map<String, Object>> errors = new ArrayList<>();
    private long classReferences, fieldReferences, methodReferences, constants;
    private int jdkClasses, gameClasses, concreteMethods;
    private boolean configPreserved, npcGuardPresent, npcBaselinePreserved;

    private AuditPortPayload(Path root, Path jar) throws Exception {
        this.root = root.toAbsolutePath().normalize();
        this.game = new ZipFile(jar.toFile());
        try (var files = Files.walk(this.root)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".class")).sorted().toList()) {
                byte[] data = Files.readAllBytes(p); ClassNode n = parse(data, 0);
                String rel = this.root.relativize(p).toString().replace('\\', '/');
                if (!rel.equals(n.name + ".class")) error(rel, "class-path", n.name);
                if (payload.putIfAbsent(n.name, n) != null) error(rel, "duplicate-class", n.name);
                bytes.put(n.name, data);
                String raw = new String(data, StandardCharsets.ISO_8859_1);
                for (String retired : RETIRED) if (raw.contains(retired)) error(n.name, "retired-reference", retired);
            }
        }
    }

    private static ClassNode parse(byte[] data, int flags) { ClassNode n = new ClassNode(); new ClassReader(data).accept(n, flags); return n; }
    private void error(String where, String kind, String symbol) {
        if (errorKeys.add(where + '\n' + kind + '\n' + symbol)) errors.add(Map.of("where", where, "kind", kind, "symbol", symbol));
    }

    private ClassNode resolve(String name) {
        if (name == null || name.startsWith("[")) return null;
        ClassNode n = payload.get(name); if (n != null) return n;
        n = external.get(name); if (n != null || unavailable.contains(name)) return n;
        try {
            var entry = game.getEntry(name + ".class"); byte[] data = null;
            if (entry != null) {
                try (var in = game.getInputStream(entry)) { data = in.readAllBytes(); }
                gameClasses++;
            } else {
                // Never make the auditor's ASM/tool classpath an accidental
                // runtime dependency. Only authenticated jrt module resources
                // count as JDK definitions.
                URL resource = ClassLoader.getPlatformClassLoader().getResource(name + ".class");
                if (resource != null && resource.getProtocol().equals("jrt")) {
                    try (var in = resource.openStream()) { data = in.readAllBytes(); }
                    jdkClasses++;
                }
            }
            if (data == null) { unavailable.add(name); return null; }
            n = parse(data, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!name.equals(n.name)) throw new IllegalStateException("Class identity differs: " + name + " / " + n.name);
            external.put(name, n); return n;
        } catch (Exception e) {
            unavailable.add(name); error(name, "class-read-error", e.toString()); return null;
        }
    }

    private static String packageName(String name) { int i = name.lastIndexOf('/'); return i < 0 ? "" : name.substring(0, i); }
    private String nestHost(String name) { ClassNode n = resolve(name); return n == null || n.nestHostClass == null ? name : n.nestHostClass; }
    private boolean sameNest(String a, String b) {
        String host = nestHost(a); if (!host.equals(nestHost(b))) return false;
        ClassNode h = resolve(host); if (h == null) return false;
        return (a.equals(host) || h.nestMembers != null && h.nestMembers.contains(a))
                && (b.equals(host) || h.nestMembers != null && h.nestMembers.contains(b));
    }
    private boolean subclass(String child, String parent, Set<String> seen) {
        if (child.equals(parent)) return true;
        if (!seen.add(child)) return false;
        ClassNode n = resolve(child); if (n == null) return false;
        if (n.superName != null && subclass(n.superName, parent, seen)) return true;
        for (String i : n.interfaces) if (subclass(i, parent, seen)) return true;
        return false;
    }

    private void classRef(String where, String name, String caller) {
        if (name == null) return;
        if (name.startsWith("[")) { type(where, Type.getType(name), caller); return; }
        classReferences++;
        ClassNode n = resolve(name);
        if (n == null) { error(where, "missing-class", name); return; }
    }
    private void accessibleClass(String where, String name, String caller) {
        if (name == null) return;
        if (name.startsWith("[")) {
            Type element = Type.getType(name).getElementType();
            if (element.getSort() == Type.OBJECT) accessibleClass(where, element.getInternalName(), caller);
            return;
        }
        ClassNode n = resolve(name);
        if (n != null && (n.access & Opcodes.ACC_PUBLIC) == 0 && !packageName(name).equals(packageName(caller)) && !sameNest(name, caller))
            error(where, "inaccessible-class", name);
    }
    private void type(String where, Type t, String caller) {
        if (t.getSort() == Type.ARRAY) type(where, t.getElementType(), caller);
        else if (t.getSort() == Type.OBJECT) classRef(where, t.getInternalName(), caller);
        else if (t.getSort() == Type.METHOD) {
            for (Type a : t.getArgumentTypes()) type(where, a, caller);
            type(where, t.getReturnType(), caller);
        }
    }
    private void descriptor(String where, String d, String caller) {
        try { type(where, Type.getType(d), caller); }
        catch (RuntimeException e) { error(where, "bad-descriptor", d + " / " + e); }
    }
    private void signature(String where, String s, String caller, boolean typeOnly) {
        if (s == null) return;
        try {
            SignatureVisitor v = signatureVisitor(where, caller);
            if (typeOnly) new SignatureReader(s).acceptType(v); else new SignatureReader(s).accept(v);
        } catch (RuntimeException e) { error(where, "bad-signature", s + " / " + e); }
    }
    private SignatureVisitor signatureVisitor(String where, String caller) {
        return new SignatureVisitor(Opcodes.ASM9) {
            private String enclosing;
            private SignatureVisitor next() { return signatureVisitor(where, caller); }
            @Override public void visitClassType(String name) { enclosing = name; classRef(where, name, caller); }
            @Override public void visitInnerClassType(String name) { enclosing += "$" + name; classRef(where, enclosing, caller); }
            @Override public SignatureVisitor visitArrayType() { return next(); }
            @Override public SignatureVisitor visitClassBound() { return next(); }
            @Override public SignatureVisitor visitInterfaceBound() { return next(); }
            @Override public SignatureVisitor visitSuperclass() { return next(); }
            @Override public SignatureVisitor visitInterface() { return next(); }
            @Override public SignatureVisitor visitParameterType() { return next(); }
            @Override public SignatureVisitor visitReturnType() { return next(); }
            @Override public SignatureVisitor visitExceptionType() { return next(); }
            @Override public SignatureVisitor visitTypeArgument(char wildcard) { return next(); }
        };
    }

    private record Member(String owner, int access, String descriptor) { }
    private Member field(String owner, String name, String desc, Set<String> seen) {
        if (!seen.add(owner) || owner.startsWith("[")) return null;
        ClassNode n = resolve(owner); if (n == null) return null;
        for (FieldNode f : n.fields) if (f.name.equals(name) && f.desc.equals(desc)) return new Member(owner, f.access, f.desc);
        // JVM field resolution consults interfaces before the superclass.
        for (String i : n.interfaces) { Member f = field(i, name, desc, seen); if (f != null) return f; }
        return n.superName == null ? null : field(n.superName, name, desc, seen);
    }
    private Member method(String owner, String name, String desc, boolean allowInterfaces, Set<String> seen) {
        if (owner.startsWith("[")) return method("java/lang/Object", name, desc, false, seen);
        if (!seen.add(owner)) return null;
        ClassNode n = resolve(owner); if (n == null) return null;
        for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) return new Member(owner, m.access, m.desc);
        // Constructors and interface static methods are never inherited.
        if (name.equals("<init>") || name.equals("<clinit>")) return null;
        if (signaturePolymorphic(owner, name)) {
            MethodNode m = n.methods.stream().filter(x -> x.name.equals(name) && (x.access & Opcodes.ACC_NATIVE) != 0 && (x.access & Opcodes.ACC_VARARGS) != 0).findFirst().orElse(null);
            if (m != null) return new Member(owner, m.access, desc);
        }
        if ((n.access & Opcodes.ACC_INTERFACE) != 0) {
            ClassNode object = resolve("java/lang/Object");
            for (MethodNode m : object.methods) if (m.name.equals(name) && m.desc.equals(desc)
                    && (m.access & Opcodes.ACC_PUBLIC) != 0 && (m.access & Opcodes.ACC_STATIC) == 0)
                return new Member(object.name, m.access, m.desc);
        } else if (n.superName != null) {
            Member m = method(n.superName, name, desc, allowInterfaces, seen); if (m != null) return m;
        }
        if (allowInterfaces) for (String i : n.interfaces) {
            Member m = method(i, name, desc, true, seen);
            if (m != null && (m.access & Opcodes.ACC_STATIC) == 0) return m;
        }
        return null;
    }
    private boolean signaturePolymorphic(String owner, String name) {
        if (owner.equals("java/lang/invoke/MethodHandle") && !(name.equals("invoke") || name.equals("invokeExact"))) return false;
        if (!owner.equals("java/lang/invoke/MethodHandle") && !owner.equals("java/lang/invoke/VarHandle")) return false;
        ClassNode n = resolve(owner);
        return n != null && n.methods.stream().anyMatch(m -> m.name.equals(name) && (m.access & Opcodes.ACC_NATIVE) != 0
                && (m.access & Opcodes.ACC_VARARGS) != 0 && Type.getArgumentTypes(m.desc).length == 1
                && Type.getArgumentTypes(m.desc)[0].getDescriptor().equals("[Ljava/lang/Object;"));
    }
    private void accessible(String where, String caller, Member member, String symbol) {
        int a = member.access;
        if ((a & Opcodes.ACC_PUBLIC) != 0) return;
        if ((a & Opcodes.ACC_PRIVATE) != 0) {
            if (!caller.equals(member.owner) && !sameNest(caller, member.owner)) error(where, "inaccessible-private-member", symbol);
        } else if ((a & Opcodes.ACC_PROTECTED) != 0) {
            if (!packageName(caller).equals(packageName(member.owner)) && !subclass(caller, member.owner, new HashSet<>()))
                error(where, "inaccessible-protected-member", symbol);
        } else if (!packageName(caller).equals(packageName(member.owner))) error(where, "inaccessible-package-member", symbol);
    }
    private void reference(String where, String caller, String callerMethod, String owner, String name, String desc, int opcode, boolean itf) {
        classRef(where, owner, caller); accessibleClass(where, owner, caller); descriptor(where, desc, caller);
        String symbol = owner + "." + name + desc;
        boolean method = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL || opcode == Opcodes.INVOKEINTERFACE;
        if (method) {
            methodReferences++;
            if (name.equals("<clinit>")) { error(where, "illegal-class-initializer-call", symbol); return; }
            ClassNode n = owner.startsWith("[") ? null : resolve(owner);
            if (n != null && itf != ((n.access & Opcodes.ACC_INTERFACE) != 0)) error(where, "method-owner-interface-flag", symbol);
            if (opcode == Opcodes.INVOKEVIRTUAL && n != null && (n.access & Opcodes.ACC_INTERFACE) != 0) error(where, "invokevirtual-interface-owner", symbol);
            if (opcode == Opcodes.INVOKEINTERFACE && (n == null || (n.access & Opcodes.ACC_INTERFACE) == 0)) error(where, "invokeinterface-owner", symbol);
            if (owner.startsWith("[") && (opcode != Opcodes.INVOKEVIRTUAL || name.startsWith("<"))) error(where, "illegal-array-member-call", symbol);
            if (name.equals("<init>") && opcode != Opcodes.INVOKESPECIAL) error(where, "constructor-opcode", symbol);
            Member found = method(owner, name, desc, opcode != Opcodes.INVOKESTATIC, new HashSet<>());
            if (found == null) { error(where, "missing-method", symbol); return; }
            boolean isStatic = (found.access & Opcodes.ACC_STATIC) != 0;
            if (isStatic != (opcode == Opcodes.INVOKESTATIC)) error(where, "method-static-opcode", symbol);
            boolean arrayClone = owner.startsWith("[") && name.equals("clone") && desc.equals("()Ljava/lang/Object;");
            if (!arrayClone) accessible(where, caller, found, symbol);
        } else {
            fieldReferences++;
            Member found = field(owner, name, desc, new HashSet<>());
            if (found == null) { error(where, "missing-field", symbol); return; }
            boolean staticOpcode = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
            if (((found.access & Opcodes.ACC_STATIC) != 0) != staticOpcode) error(where, "field-static-opcode", symbol);
            accessible(where, caller, found, symbol);
            if ((found.access & Opcodes.ACC_FINAL) != 0 && (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)
                    && (!caller.equals(found.owner) || !(opcode == Opcodes.PUTFIELD ? callerMethod.equals("<init>") : callerMethod.equals("<clinit>"))))
                error(where, "illegal-final-write", symbol);
        }
    }

    private void constant(String where, String caller, String callerMethod, Object value) {
        constants++;
        if (value instanceof Type t) {
            type(where, t, caller);
            if (t.getSort() == Type.OBJECT) accessibleClass(where, t.getInternalName(), caller);
            else if (t.getSort() == Type.ARRAY) accessibleClass(where, t.getDescriptor(), caller);
        }
        else if (value instanceof Handle h) {
            int opcode = switch (h.getTag()) {
                case Opcodes.H_GETFIELD -> Opcodes.GETFIELD; case Opcodes.H_GETSTATIC -> Opcodes.GETSTATIC;
                case Opcodes.H_PUTFIELD -> Opcodes.PUTFIELD; case Opcodes.H_PUTSTATIC -> Opcodes.PUTSTATIC;
                case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC; case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                default -> throw new IllegalArgumentException("Unknown method-handle tag " + h.getTag());
            };
            if (h.getTag() == Opcodes.H_NEWINVOKESPECIAL && !h.getName().equals("<init>")) error(where, "new-handle-name", h.toString());
            if (h.getName().equals("<init>") && h.getTag() != Opcodes.H_NEWINVOKESPECIAL) error(where, "constructor-handle-kind", h.toString());
            reference(where, caller, callerMethod, h.getOwner(), h.getName(), h.getDesc(), opcode, h.isInterface());
        } else if (value instanceof ConstantDynamic d) {
            descriptor(where, d.getDescriptor(), caller); constant(where, caller, callerMethod, d.getBootstrapMethod());
            for (int i = 0; i < d.getBootstrapMethodArgumentCount(); i++) constant(where, caller, callerMethod, d.getBootstrapMethodArgument(i));
        }
    }
    private void annotationValue(String where, String caller, Object value) {
        if (value instanceof AnnotationNode n) annotation(where, caller, n);
        else if (value instanceof Type t) type(where, t, caller);
        else if (value instanceof String[] e && e.length == 2) {
            descriptor(where, e[0], caller);
            Type enumType = Type.getType(e[0]);
            if (enumType.getSort() != Type.OBJECT) error(where, "annotation-enum-type", Arrays.toString(e));
            else reference(where, caller, "annotation", enumType.getInternalName(), e[1], e[0], Opcodes.GETSTATIC, false);
        }
        else if (value instanceof List<?> list) for (Object x : list) annotationValue(where, caller, x);
    }
    private void annotation(String where, String caller, AnnotationNode n) {
        descriptor(where, n.desc, caller);
        if (n.values != null) for (int i = 1; i < n.values.size(); i += 2) annotationValue(where, caller, n.values.get(i));
    }
    private void annotations(String where, String caller, List<? extends AnnotationNode> nodes) { if (nodes != null) for (AnnotationNode n : nodes) annotation(where, caller, n); }

    private void inspect(ClassNode n) {
        String caller = n.name;
        classRef(caller, n.superName, caller); accessibleClass(caller, n.superName, caller);
        for (String i : n.interfaces) { classRef(caller, i, caller); accessibleClass(caller, i, caller); }
        signature(caller, n.signature, caller, false);
        classRef(caller, n.nestHostClass, caller);
        if (n.nestMembers != null) for (String i : n.nestMembers) classRef(caller, i, caller);
        if (n.permittedSubclasses != null) for (String i : n.permittedSubclasses) classRef(caller, i, caller);
        classRef(caller, n.outerClass, caller); if (n.outerMethodDesc != null) descriptor(caller, n.outerMethodDesc, caller);
        if (n.innerClasses != null) for (InnerClassNode inner : n.innerClasses) {
            classRef(caller, inner.name, caller); classRef(caller, inner.outerName, caller);
        }
        annotations(caller, caller, n.visibleAnnotations); annotations(caller, caller, n.invisibleAnnotations);
        annotations(caller, caller, n.visibleTypeAnnotations); annotations(caller, caller, n.invisibleTypeAnnotations);
        if (n.recordComponents != null) for (RecordComponentNode r : n.recordComponents) {
            String where = caller + ".record:" + r.name;
            descriptor(where, r.descriptor, caller); signature(where, r.signature, caller, true);
            annotations(where, caller, r.visibleAnnotations); annotations(where, caller, r.invisibleAnnotations);
            annotations(where, caller, r.visibleTypeAnnotations); annotations(where, caller, r.invisibleTypeAnnotations);
        }
        Set<String> fieldKeys = new HashSet<>(), methodKeys = new HashSet<>();
        for (FieldNode f : n.fields) {
            String where = caller + "." + f.name + ":" + f.desc;
            if (!fieldKeys.add(f.name + f.desc)) error(where, "duplicate-field", f.name + f.desc);
            descriptor(where, f.desc, caller); signature(where, f.signature, caller, true); constant(where, caller, "<clinit>", f.value);
            annotations(where, caller, f.visibleAnnotations); annotations(where, caller, f.invisibleAnnotations);
            annotations(where, caller, f.visibleTypeAnnotations); annotations(where, caller, f.invisibleTypeAnnotations);
        }
        for (MethodNode m : n.methods) {
            String where = caller + "." + m.name + m.desc;
            if (!methodKeys.add(m.name + m.desc)) error(where, "duplicate-method", m.name + m.desc);
            descriptor(where, m.desc, caller); signature(where, m.signature, caller, false);
            if (m.exceptions != null) for (String e : m.exceptions) classRef(where, e, caller);
            annotations(where, caller, m.visibleAnnotations); annotations(where, caller, m.invisibleAnnotations);
            annotations(where, caller, m.visibleTypeAnnotations); annotations(where, caller, m.invisibleTypeAnnotations);
            if (m.visibleParameterAnnotations != null) for (var a : m.visibleParameterAnnotations) annotations(where, caller, a);
            if (m.invisibleParameterAnnotations != null) for (var a : m.invisibleParameterAnnotations) annotations(where, caller, a);
            annotationValue(where, caller, m.annotationDefault);
            if (m.localVariables != null) for (LocalVariableNode l : m.localVariables) {
                descriptor(where, l.desc, caller); signature(where, l.signature, caller, true);
            }
            annotations(where, caller, m.visibleLocalVariableAnnotations); annotations(where, caller, m.invisibleLocalVariableAnnotations);
            for (TryCatchBlockNode t : m.tryCatchBlocks) {
                classRef(where, t.type, caller); annotations(where, caller, t.visibleTypeAnnotations); annotations(where, caller, t.invisibleTypeAnnotations);
            }
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                concreteMethods++;
                try { new Analyzer<>(new BasicVerifier()).analyze(caller, m); }
                catch (AnalyzerException e) { error(where, "instruction-stack", e.getMessage()); }
            }
            for (AbstractInsnNode i : m.instructions) {
                annotations(where, caller, i.visibleTypeAnnotations); annotations(where, caller, i.invisibleTypeAnnotations);
                if (i instanceof MethodInsnNode c) reference(where, caller, m.name, c.owner, c.name, c.desc, c.getOpcode(), c.itf);
                else if (i instanceof FieldInsnNode f) reference(where, caller, m.name, f.owner, f.name, f.desc, f.getOpcode(), false);
                else if (i instanceof TypeInsnNode t) {
                    classRef(where, t.desc, caller); accessibleClass(where, t.desc, caller);
                    if (t.getOpcode() == Opcodes.NEW) {
                        ClassNode allocated = resolve(t.desc);
                        if (t.desc.startsWith("[") || allocated != null && (allocated.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0)
                            error(where, "illegal-instantiation-target", t.desc);
                    }
                }
                else if (i instanceof MultiANewArrayInsnNode a) { descriptor(where, a.desc, caller); accessibleClass(where, a.desc, caller); }
                else if (i instanceof LdcInsnNode l) constant(where, caller, m.name, l.cst);
                else if (i instanceof InvokeDynamicInsnNode d) {
                    descriptor(where, d.desc, caller); constant(where, caller, m.name, d.bsm);
                    for (Object a : d.bsmArgs) constant(where, caller, m.name, a);
                } else if (i instanceof FrameNode f) {
                    for (Object v : f.local == null ? List.of() : f.local) if (v instanceof String s) classRef(where, s, caller);
                    for (Object v : f.stack == null ? List.of() : f.stack) if (v instanceof String s) classRef(where, s, caller);
                }
            }
        }
    }

    private void preservation(Path baseline) throws Exception {
        ClassNode config = payload.get(PREFIX + "Config");
        if (config == null) error("payload", "missing-config", PREFIX + "Config");
        if (baseline != null) {
            Path oldFile = baseline.resolve(PREFIX + "Config.class");
            if (!Files.isRegularFile(oldFile)) error("baseline", "missing-baseline-config", oldFile.toString());
            else if (config != null) {
                ClassNode old = parse(Files.readAllBytes(oldFile), 0);
                List<String> before = old.fields.stream().map(f -> f.name + f.desc + ":" + String.valueOf(f.value)).sorted().toList();
                List<String> after = config.fields.stream().map(f -> f.name + f.desc + ":" + String.valueOf(f.value)).sorted().toList();
                boolean fields = before.equals(after);
                boolean defaults = PayloadModel.tokens(PayloadModel.method(old, "<clinit>()V")).equals(PayloadModel.tokens(PayloadModel.method(config, "<clinit>()V")));
                configPreserved = fields && defaults;
                if (!fields) error(config.name, "config-declarations-changed", "Existing config field/default constants differ from baseline");
                if (!defaults) error(config.name, "config-defaults-changed", "Config static initializer differs from baseline");
            }
        }
        // Donor source keeps the existing direct call, guarded by a local null check.
        // Require the receiver to be the checked getter result; a null branch elsewhere
        // in this large method is not evidence that the network call is safe.
        ClassNode character = payload.get("zombie/characters/IsoGameCharacter");
        MethodNode rest = character == null ? null : PayloadModel.method(character, "pzoptRestContext()V");
        List<AbstractInsnNode> addedGuard = directNpcGuard(rest);
        npcGuardPresent = addedGuard != null;
        if (!npcGuardPresent) error("NPC rest context", "invalid-npc-null-guard",
                "Expected one direct postUpdate call on the same getter result checked by IFNULL, with no incoming bypass");
        if (baseline != null && npcGuardPresent) {
            Path prior = baseline.resolve("zombie/characters/IsoGameCharacter.class");
            MethodNode original = Files.isRegularFile(prior)
                    ? PayloadModel.method(parse(Files.readAllBytes(prior), 0), "pzoptRestContext()V") : null;
            MethodNode normalized = new MethodNode(rest.access, rest.name, rest.desc, rest.signature,
                    rest.exceptions == null ? null : rest.exceptions.toArray(String[]::new));
            rest.accept(normalized);
            List<AbstractInsnNode> removable = directNpcGuard(normalized);
            for (AbstractInsnNode instruction : removable) normalized.instructions.remove(instruction);
            npcBaselinePreserved = original != null
                    && PayloadModel.tokens(original).equals(PayloadModel.tokens(normalized));
            if (!npcBaselinePreserved) error("NPC rest context", "npc-rest-context-body-changed",
                    "Removing only the four null-guard instructions must recover the original donor method tokens");
        }
    }

    private static List<AbstractInsnNode> operations(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static int labelPosition(List<AbstractInsnNode> operations, LabelNode label) {
        for (AbstractInsnNode instruction = label; instruction != null; instruction = instruction.getNext())
            if (instruction.getOpcode() >= 0) return operations.indexOf(instruction);
        return operations.size();
    }

    /** Return only the inserted store/check/reload instructions, or null when the call can be unguarded. */
    private static List<AbstractInsnNode> directNpcGuard(MethodNode method) {
        if (method == null) return null;
        List<AbstractInsnNode> code = operations(method);
        int call = -1;
        for (int index = 0; index < code.size(); index++)
            if (code.get(index) instanceof MethodInsnNode invoked
                    && invoked.owner.equals("zombie/characters/NetworkCharacterAI") && invoked.name.equals("postUpdate")) {
                if (call >= 0 || invoked.getOpcode() != Opcodes.INVOKEVIRTUAL || !invoked.desc.equals("()V")) return null;
                call = index;
            }
        if (call < 6
                || !(code.get(call - 6) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(code.get(call - 5) instanceof MethodInsnNode getter) || getter.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !getter.owner.equals("zombie/characters/IsoGameCharacter") || !getter.name.equals("getNetworkCharacterAI")
                || !getter.desc.equals("()Lzombie/characters/NetworkCharacterAI;")
                || !(code.get(call - 4) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE || store.var == 0
                || !(code.get(call - 3) instanceof VarInsnNode checked) || checked.getOpcode() != Opcodes.ALOAD || checked.var != store.var
                || !(code.get(call - 2) instanceof JumpInsnNode guard) || guard.getOpcode() != Opcodes.IFNULL
                || labelPosition(code, guard.label) <= call
                || !(code.get(call - 1) instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != store.var)
            return null;
        for (AbstractInsnNode instruction : code) {
            List<LabelNode> entries = new ArrayList<>();
            if (instruction instanceof JumpInsnNode jump) entries.add(jump.label);
            else if (instruction instanceof TableSwitchInsnNode table) { entries.add(table.dflt); entries.addAll(table.labels); }
            else if (instruction instanceof LookupSwitchInsnNode lookup) { entries.add(lookup.dflt); entries.addAll(lookup.labels); }
            for (LabelNode entry : entries) {
                int position = labelPosition(code, entry);
                if (position >= call - 4 && position <= call) return null;
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks == null ? List.<TryCatchBlockNode>of() : method.tryCatchBlocks) {
            int position = labelPosition(code, block.handler);
            if (position >= call - 4 && position <= call) return null;
        }
        return List.of(code.get(call - 4), code.get(call - 3), code.get(call - 2), code.get(call - 1));
    }

    private Map<String, Object> runJvm(Path gameJar, Path receipt) throws Exception {
        Path log = receipt.resolveSibling(receipt.getFileName() + ".jvm.log"), result = receipt.resolveSibling(receipt.getFileName() + ".jvm.json");
        String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String absoluteClasspath = String.join(java.io.File.pathSeparator,
                Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                        .map(p -> Path.of(p).toAbsolutePath().normalize().toString()).toList());
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", exe).toString(), "-Xverify:all", "-Xmx1g",
                "-cp", absoluteClasspath, VerifyMain.class.getName(), root.toString(), gameJar.toAbsolutePath().toString(), result.toString());
        ProcessBuilder launch = new ProcessBuilder(command).directory(receipt.getParent().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        launch.environment().keySet().removeIf(k -> k.startsWith("FJL_") || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").contains(k));
        Process p = launch.start(); boolean completed = p.waitFor(55, TimeUnit.SECONDS);
        if (!completed) { p.destroyForcibly().waitFor(5, TimeUnit.SECONDS); error("JVM verify", "timeout", log.toString()); }
        else if (p.exitValue() != 0) error("JVM verify", "failed", "Exit " + p.exitValue() + "; see " + result + " and " + log);
        return Map.of("completed", completed, "exit", completed ? p.exitValue() : -1, "receipt", result.toString(), "log", log.toString(), "gameInitialization", false);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Usage: AuditPortPayload <payloadRoot> <game.jar> <receipt.json> [--baseline-payload=<dir>] [--verify-jvm]");
        Path payload = Path.of(args[0]).toAbsolutePath(), jar = Path.of(args[1]).toAbsolutePath(), receipt = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(receipt.getParent()); Path baseline = null; boolean verify = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("--baseline-payload=")) baseline = Path.of(args[i].substring("--baseline-payload=".length()));
            else if (args[i].equals("--verify-jvm")) verify = true;
            else throw new IllegalArgumentException("Unknown option " + args[i]);
        }
        try (AuditPortPayload audit = new AuditPortPayload(payload, jar)) {
            String hash = PayloadModel.hash(Files.readAllBytes(jar));
            if (!GAME_SHAS.contains(hash)) audit.error(jar.toString(), "unreviewed-game-jar", hash);
            for (ClassNode n : audit.payload.values()) audit.inspect(n);
            audit.preservation(baseline);
            Map<String, Object> vm = verify ? audit.runJvm(jar, receipt) : Map.of("requested", false);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("passed", audit.errors.isEmpty()); report.put("gameJarSha256", hash);
            report.put("payload", payload.toString()); report.put("classCount", audit.payload.size()); report.put("concreteMethods", audit.concreteMethods);
            report.put("classReferences", audit.classReferences); report.put("methodReferences", audit.methodReferences); report.put("fieldReferences", audit.fieldReferences);
            report.put("bootstrapAndConstantValues", audit.constants); report.put("gameMetadataClassesRead", audit.gameClasses); report.put("jdkMetadataClassesRead", audit.jdkClasses);
            report.put("strictExternalMemberResolution", true); report.put("constructorsNotInherited", true); report.put("toolClasspathNotUsedForTargetResolution", true);
            report.put("unavailableClasses", audit.unavailable); report.put("retiredReferenceScan", RETIRED); report.put("configBaselineProvided", baseline != null);
            report.put("configDeclarationsAndDefaultsPreserved", audit.configPreserved); report.put("npcNullGuardPreserved", audit.npcGuardPresent);
            report.put("npcNullGuardForm", "direct-local-null-check"); report.put("npcRestContextBaselineBodyPreserved", audit.npcBaselinePreserved);
            report.put("jvmVerification", vm); report.put("errorCount", audit.errors.size()); report.put("errors", audit.errors);
            Files.writeString(receipt, PayloadModel.json(report) + "\n", StandardCharsets.UTF_8);
            audit.errors.stream().limit(25).forEach(e -> System.out.println(PayloadModel.json(e)));
            System.out.println("AUDIT_PORT_422 " + (audit.errors.isEmpty() ? "PASS" : "FAIL") + " classes=" + audit.payload.size()
                    + " methods=" + audit.concreteMethods + " errors=" + audit.errors.size() + " receipt=" + receipt);
            if (!audit.errors.isEmpty()) System.exit(1);
        }
    }
    @Override public void close() throws Exception { game.close(); }

    /** Separate CPU-only JVM. Resolves metadata and links classes with initialization disabled. */
    public static final class VerifyMain {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]), jar = Path.of(args[1]), result = Path.of(args[2]);
            List<Map<String, String>> errors = new ArrayList<>(); int checked = 0;
            class Loader extends URLClassLoader {
                Loader() throws Exception { super(new URL[] { root.toUri().toURL(), jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader()); }
                Class<?> verify(String name) throws Exception { Class<?> c = Class.forName(name, false, this); resolveClass(c); return c; }
            }
            try (Loader loader = new Loader(); var files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    String name = root.relativize(file).toString().replace('\\', '/').replace('/', '.'); name = name.substring(0, name.length() - 6);
                    try {
                        Class<?> c = loader.verify(name); c.getDeclaredFields(); c.getDeclaredConstructors(); c.getDeclaredMethods(); checked++;
                    } catch (Throwable problem) { errors.add(Map.of("class", name, "error", problem.toString())); }
                }
            }
            Files.writeString(result, PayloadModel.json(Map.of("passed", errors.isEmpty(), "classesVerified", checked, "errors", errors,
                    "initialize", false, "mainCalled", false, "nativeCalls", false)) + "\n");
            errors.stream().limit(15).forEach(e -> System.out.println(PayloadModel.json(e)));
            System.out.println("JVM_VERIFY " + (errors.isEmpty() ? "PASS" : "FAIL") + " classes=" + checked + " errors=" + errors.size());
            if (!errors.isEmpty()) System.exit(1);
        }
    }
}
