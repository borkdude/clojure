package clojure.lang;

import clojure.asm.*;
import clojure.asm.commons.GeneratorAdapter;

import java.io.FileOutputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static clojure.asm.Opcodes.*;

public class LambdaMetafactory {

    static byte[] generateClass(Class intf, String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER + ACC_SYNTHETIC, internalName, null, "java/lang/Object", new String[]{intf.getName().replace(".", "/")});
        cw.visitField(ACC_PRIVATE + ACC_FINAL,
                "delegate", "Lclojure/lang/IFn;", null, null);
        generateConstructor(cw, internalName);
        generateStaticFactory(cw, internalName, intf);
        generateForwarder(cw, internalName, intf);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static void generateConstructor(ClassWriter cw, String internalName) {
        MethodVisitor ctor = cw.visitMethod(ACC_PRIVATE, "<init>",
                "(Lclojure/lang/IFn;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE), false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitFieldInsn(PUTFIELD, internalName, "delegate", "Lclojure/lang/IFn;");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(-1,-1);
        ctor.visitEnd();
    }

    static void generateStaticFactory(ClassWriter cw, String internalName, Class intf) {
        String desc = "(Ljava/lang/Object;)L"+intf.getName().replace(".", "/")+";";
        MethodVisitor factory = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "convert",
                desc, null, null);
        GeneratorAdapter gen = new GeneratorAdapter(factory, ACC_PUBLIC + ACC_STATIC, "convert", desc);
        gen.visitCode();
        Label nullLabel = new Label();
        Label castLabel = new Label();
        gen.loadArg(0);
        gen.ifNull(nullLabel);
        gen.loadArg(0);
        gen.instanceOf(Type.getType(intf));
        gen.ifZCmp(IFEQ, castLabel);

        gen.loadArg(0);
        gen.checkCast(Type.getType(intf));
        gen.returnValue();

        gen.mark(castLabel);
        gen.visitTypeInsn(NEW, internalName);
        gen.visitInsn(DUP);
        gen.visitVarInsn(ALOAD, 0);
        gen.checkCast(Type.getType("Lclojure/lang/IFn;"));
        gen.visitMethodInsn(INVOKESPECIAL, internalName, "<init>", "(Lclojure/lang/IFn;)V", false);
        gen.visitInsn(ARETURN);

        gen.mark(nullLabel);
        gen.visitInsn(ACONST_NULL);
        gen.returnValue();
        gen.visitMaxs(-1,-1); // why is this necessary?
        gen.visitEnd();
    }

    static void generateForwarder(ClassWriter cw, String internalName, Class intf) {
        Method m = samMethod(intf);
        Class retc = m.getReturnType();
        Class[] ptypes = m.getParameterTypes();
        // throw if > 20 params
        GeneratorAdapter gen = new GeneratorAdapter(ACC_PUBLIC, clojure.asm.commons.Method.getMethod(m), null, null, cw);

        gen.loadThis();
        gen.visitFieldInsn(GETFIELD, internalName, "delegate", "Lclojure/lang/IFn;");
        for (int i = 0; i < m.getParameterCount(); i ++) {
            gen.loadArg(i);
            if (ptypes[i].isPrimitive()) {
                boxPrimitive(gen, ptypes[i]);
            } else { // clear references
                gen.visitInsn(ACONST_NULL);
                gen.storeArg(i);
            }
        }
        gen.visitMethodInsn(INVOKEINTERFACE,
                "clojure/lang/IFn",
                "invoke",
                MethodType.genericMethodType(m.getParameterCount()).toMethodDescriptorString() ,
                true);

        if (retc.equals(Void.TYPE)) {
            gen.pop();
        } else if (retc.isPrimitive()) {
            gen.unbox(Type.getType(retc));
        } else {
            gen.checkCast(Type.getType(retc));
        }
        gen.returnValue();
        gen.endMethod();
    }

    // TODO finish boxing
    static void boxPrimitive(GeneratorAdapter gen, Class primc) {
        if (primc == boolean.class) {
            gen.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false);
        } else if (primc == char.class) {

        } else if (primc == int.class) {
            gen.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
        } else if (primc == float.class) {

        } else if (primc == double.class) {

        } else if (primc == long.class) {
            gen.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false);
        } else if (primc == byte.class) {

        } else if (primc == short.class) {

        }
    }

    static Method samMethod(Class intf) {
        // TODO Correct for interfaces like Comparable that redeclare Object methods
        return Arrays.stream(intf.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .findFirst()
                .get();
    }

    static boolean isSAM(Class target) {
        if (!target.isInterface())
            return false;
        if (Arrays.stream(target.getMethods()).filter(m -> Modifier.isAbstract(m.getModifiers())).count() == 1)
            return true;
        return false;
    }

    private static String factoryName(Class intf) {
        return String.format("%s.LambdaFactory$%s", RT.CURRENT_NS.deref(), intf.getName().replace(".", "$"))
                .replace(".", "/");
    }

    static IPersistentVector prepare(Class intf) {
        String internalName = factoryName(intf);
        return Tuple.create(internalName, generateClass(intf, internalName));
    }

    //public static void main(String[] args) {
    //    byte[] bs = generateClass(java.util.function.Supplier.class, "clojure/lang/GhadiTest");
    //    try (FileOutputStream f = new FileOutputStream("target/classes/clojure/lang/GhadiTest.class")) {
    //        f.write(bs);
    //    } catch (Exception e) {
    //        e.printStackTrace();
    //    }
    //}
}
