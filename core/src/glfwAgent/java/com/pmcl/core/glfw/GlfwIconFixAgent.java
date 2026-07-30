package com.pmcl.core.glfw;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Java Agent：将 {@code GLFW.glfwSetWindowIcon} / {@code nglfwSetWindowIcon} 置空。
 * <p>
 * 现代 GLFW 在 macOS 上对窗口图标返回 {@code GLFW_FEATURE_UNAVAILABLE (65548)}，
 * 而 MC 1.13–1.16 会把任意 GLFW 错误当成致命异常。本 Agent 需能在 Java 8 游戏 JVM 上运行。
 */
public final class GlfwIconFixAgent {

    private GlfwIconFixAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        inst.addTransformer(new Transformer(), true);
        System.err.println("[PMCL GLFW] icon-fix agent loaded");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            if (className == null || !"org/lwjgl/glfw/GLFW".equals(className)) {
                return null;
            }
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (!"glfwSetWindowIcon".equals(name) && !"nglfwSetWindowIcon".equals(name)) {
                            return super.visitMethod(access, name, descriptor, signature, exceptions);
                        }
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (mv == null) return null;
                        mv.visitCode();
                        if (descriptor != null && descriptor.endsWith(")J")) {
                            mv.visitInsn(Opcodes.LCONST_0);
                            mv.visitInsn(Opcodes.LRETURN);
                        } else if (descriptor != null && descriptor.endsWith(")I")) {
                            mv.visitInsn(Opcodes.ICONST_0);
                            mv.visitInsn(Opcodes.IRETURN);
                        } else {
                            mv.visitInsn(Opcodes.RETURN);
                        }
                        mv.visitMaxs(0, 0);
                        mv.visitEnd();
                        return null; // 丢弃原方法体
                    }
                }, 0);
                System.err.println("[PMCL GLFW] patched org.lwjgl.glfw.GLFW window icon methods");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.err.println("[PMCL GLFW] patch failed: " + t.getMessage());
                return null;
            }
        }
    }
}
