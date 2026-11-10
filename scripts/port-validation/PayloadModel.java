/* Port-validation tool adapted from PZ Optimized scripts/payload/PayloadModel.java.
 * Source SHA-256: a7c5f67dd07d8a8fd50282139813947ac57c15cf38807a801fca8ccdc3d6ec73.
 * 2026-11-10: donor namespace pzopt; strict linkage rules retained.
 */
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Data-only model shared by the 42.22 transplant and strict audit tools. */
public final class PayloadModel {
    public static final String PREFIX = "pzopt/";
    public final Map<String, ClassNode> oldStock = new TreeMap<>(), optimized = new TreeMap<>(), newStock = new TreeMap<>();
    public final Map<String, byte[]> oldBytes = new TreeMap<>(), optimizedBytes = new TreeMap<>(), newBytes = new TreeMap<>();
    public PayloadModel(Path oldJar, Path payload, Path newJar) throws Exception {
        loadJar(oldJar, oldStock, oldBytes); loadDirectory(payload, optimized, optimizedBytes); loadJar(newJar, newStock, newBytes);
    }
    static ClassNode node(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
    static void loadJar(Path path, Map<String, ClassNode> nodes, Map<String, byte[]> bytes) throws Exception {
        try (ZipFile z = new ZipFile(path.toFile())) {
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement(); if (!e.getName().endsWith(".class")) continue;
                byte[] b = z.getInputStream(e).readAllBytes(); ClassNode n = node(b); nodes.put(n.name,n); bytes.put(n.name,b);
            }
        }
    }
    static void loadDirectory(Path path, Map<String, ClassNode> nodes, Map<String, byte[]> bytes) throws Exception {
        try (var fs = Files.walk(path)) { for (Path p : fs.filter(p -> p.toString().endsWith(".class")).toList()) {
            byte[] b = Files.readAllBytes(p); ClassNode n = node(b); nodes.put(n.name,n); bytes.put(n.name,b);
        }}
    }
    public static String key(MethodNode m) { return m.name+m.desc; }
    public static MethodNode method(ClassNode n, String key) { return n == null ? null : n.methods.stream().filter(m -> key(m).equals(key)).findFirst().orElse(null); }
    public static Map<String, MethodNode> methods(ClassNode n) { Map<String,MethodNode> r = new TreeMap<>(); if(n!=null) for(var m:n.methods)r.put(key(m),m); return r; }
    public static boolean marked(String s) { return s.contains(PREFIX) || s.contains("pzopt/") || s.toLowerCase(Locale.ROOT).contains("pzoptimized") || s.startsWith("pzopt"); }
    public static boolean hooked(MethodNode m) {
        if (marked(m.name)) return true;
        for(var i:m.instructions) {
            if(i instanceof MethodInsnNode x && (marked(x.owner)||marked(x.name)))return true;
            if(i instanceof FieldInsnNode x && (marked(x.owner)||marked(x.name)))return true;
            if(i instanceof TypeInsnNode x && marked(x.desc))return true;
        }
        return false;
    }
    static String constant(Object x) {
        if(x instanceof Type t)return "type:"+t.getDescriptor();
        if(x instanceof Handle h)return "handle:"+h.getTag()+":"+h.getOwner()+"."+h.getName()+h.getDesc()+":"+h.isInterface();
        if(x instanceof ConstantDynamic c){List<String>a=new ArrayList<>();for(int j=0;j<c.getBootstrapMethodArgumentCount();j++)a.add(constant(c.getBootstrapMethodArgument(j)));return "condy:"+c.getName()+c.getDescriptor()+constant(c.getBootstrapMethod())+a;}
        return x==null?"null":x.getClass().getSimpleName()+":"+x;
    }
    public static List<String> tokens(MethodNode m) {
        if(m==null)return List.of("<absent>");
        Map<LabelNode,Integer> labels=new IdentityHashMap<>();int pos=0;
        for(var i:m.instructions){if(i instanceof LabelNode l)labels.put(l,pos);if(i.getOpcode()>=0)pos++;}
        List<String> r=new ArrayList<>();
        for(var i:m.instructions){int op=i.getOpcode();if(op<0)continue;String s=String.valueOf(op);
            if(i instanceof VarInsnNode x)s+=" v"+x.var;
            else if(i instanceof IntInsnNode x)s+=" "+x.operand;
            else if(i instanceof TypeInsnNode x)s+=" "+x.desc;
            else if(i instanceof FieldInsnNode x)s+=" "+x.owner+"."+x.name+":"+x.desc;
            else if(i instanceof MethodInsnNode x)s+=" "+x.owner+"."+x.name+x.desc+":"+x.itf;
            else if(i instanceof InvokeDynamicInsnNode x)s+=" "+x.name+x.desc+" "+constant(x.bsm)+" "+Arrays.stream(x.bsmArgs).map(PayloadModel::constant).toList();
            else if(i instanceof JumpInsnNode x)s+=" @"+labels.get(x.label);
            else if(i instanceof LdcInsnNode x)s+=" "+constant(x.cst);
            else if(i instanceof IincInsnNode x)s+=" v"+x.var+" "+x.incr;
            else if(i instanceof TableSwitchInsnNode x)s+=" "+x.min+":"+x.max+":"+labels.get(x.dflt)+":"+x.labels.stream().map(labels::get).toList();
            else if(i instanceof LookupSwitchInsnNode x)s+=" "+x.keys+":"+labels.get(x.dflt)+":"+x.labels.stream().map(labels::get).toList();
            else if(i instanceof MultiANewArrayInsnNode x)s+=" "+x.desc+":"+x.dims;
            r.add(s);
        }
        for(var t:m.tryCatchBlocks)r.add("catch "+labels.get(t.start)+":"+labels.get(t.end)+":"+labels.get(t.handler)+":"+t.type);
        return r;
    }
    public static String hash(byte[] bytes) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static String hash(MethodNode m) throws Exception{return hash(String.join("\n",tokens(m)).getBytes(StandardCharsets.UTF_8));}
    public static String json(Object x){
        if(x==null)return "null";
        if(x instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
        if(x instanceof Map<?,?> m){List<String>a=new ArrayList<>();m.forEach((k,v)->a.add(json(String.valueOf(k))+":"+json(v)));return "{"+String.join(",",a)+"}";}
        if(x instanceof Collection<?> c)return "["+String.join(",",c.stream().map(PayloadModel::json).toList())+"]";
        return x.toString();
    }
    public static void main(String[] args) throws Exception {
        PayloadModel p=new PayloadModel(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]));Path out=Path.of(args[3]);Files.createDirectories(out);
        List<Object> classes=new ArrayList<>();int hooks=0,conflicts=0;
        for(var opt:p.optimized.values()){
            var old=p.oldStock.get(opt.name);if(old==null)continue;var fresh=p.newStock.get(opt.name);
            List<Object> ms=new ArrayList<>();
            for(var m:opt.methods){String k=key(m);var a=method(old,k);var b=method(fresh,k);boolean hooked=hooked(m),changed=!tokens(a).equals(tokens(b));
                if(hooked)hooks++;if(hooked&&changed)conflicts++;
                Map<String,Object> row=new LinkedHashMap<>();row.put("key",k);row.put("hooked",hooked);row.put("added",a==null);row.put("gameChanged",changed);row.put("optimizedChanged",!tokens(a).equals(tokens(m)));row.put("oldHash",hash(a));row.put("optHash",hash(m));row.put("newHash",hash(b));ms.add(row);
                if(hooked&&changed){Path dir=out.resolve("bytecode").resolve(opt.name);Files.createDirectories(dir);String file=m.name.replace('<','_').replace('>','_')+"-"+hash(k.getBytes(StandardCharsets.UTF_8)).substring(0,12);Files.writeString(dir.resolve(file+".txt"),k+"\nOLD STOCK\n"+String.join("\n",tokens(a))+"\nOPTIMIZED\n"+String.join("\n",tokens(m))+"\nNEW STOCK\n"+String.join("\n",tokens(b)));}
            }
            classes.add(Map.of("name",opt.name,"methods",ms));
        }
        Files.writeString(out.resolve("model.json"),json(Map.of("classes",classes,"hookMethods",hooks,"conflictHooks",conflicts)));
        System.out.println("MODEL classes="+classes.size()+" hooks="+hooks+" changedHookMethods="+conflicts+" output="+out);
    }
}
