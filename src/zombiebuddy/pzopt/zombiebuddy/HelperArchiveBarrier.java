package pzopt.zombiebuddy;

import java.lang.classfile.AttributeMapper;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.BufWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassReader;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CustomAttribute;

/**
 * Marks our native-referencing helpers as transformed without changing executable code.
 * JDK AOT otherwise resolves their native supertypes/fields before any agent premain,
 * too early for the native optimization schema. Eligible JDK types remain cacheable.
 */
final class HelperArchiveBarrier {
    private HelperArchiveBarrier() { }

    static byte[] transform(byte[] original) {
        ClassFile file = ClassFile.of();
        return file.transformClass(file.parse(original), ClassTransform.endHandler(builder -> builder.with(new Marker())));
    }

    private static final class Marker extends CustomAttribute<Marker> {
        private static final AttributeMapper<Marker> MAPPER = new AttributeMapper<>() {
            @Override public String name() { return "PzoptAgentHelper"; }
            @Override public AttributeStability stability() { return AttributeStability.STATELESS; }
            @Override public Marker readAttribute(AttributedElement enclosing, ClassReader reader, int position) { return new Marker(); }
            @Override public void writeAttribute(BufWriter writer, Marker attribute) {
                writer.writeIndex(attribute.attributeName());
                writer.writeInt(0);
            }
        };
        Marker() { super(MAPPER); }
    }
}
