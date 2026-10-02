package com.pmcl.core.ime;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.security.ProtectionDomain;

/**
 * Java Agent：在游戏的 GLFW 提供输入法接口时打开系统输入法，并把候选框放在窗口底部。
 * <p>
 * 只挂钩 {@code glfwCreateWindow}。接口不存在或函数地址为 0 时直接返回，避免旧版 GLFW
 * 因未知输入模式记下一枚错误。需要能在 Java 8 的游戏 JVM 上运行。
 */
public final class ImeFixAgent {

    /** GLFW_IME，与 GLFW 3.4 的输入模式常量一致。 */
    private static final int GLFW_IME = 0x33007;

    private static volatile long enabledWindow;
    private static volatile Object callbackRef;
    private static volatile Object previousCallback;
    private static volatile boolean loggedSkip;

    private ImeFixAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        inst.addTransformer(new Transformer(), true);
        System.err.println("[PMCL IME] agent loaded");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /** 由改写后的 {@code glfwCreateWindow} 在返回前调用。失败不得抛回游戏。 */
    public static void onWindow(long window) {
        try {
            if (window == 0L || window == enabledWindow) return;
            enable(window);
            enabledWindow = window;
        } catch (Throwable t) {
            System.err.println("[PMCL IME] 启用失败: " + t);
        }
    }

    /** 预编辑回调。只更新候选框位置，已提交的文字仍走游戏原来的字符回调。 */
    public static void onPreedit(long window, int count, long stringPtr, int blockCount,
                                 long blockSizes, int focused, int caret) {
        try {
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            place(glfw, window);
            Object prev = previousCallback;
            if (prev != null && prev != callbackRef) {
                Method invoke = prev.getClass().getMethod("invoke",
                        long.class, int.class, long.class, int.class, long.class, int.class, int.class);
                invoke.invoke(prev, Long.valueOf(window), Integer.valueOf(count), Long.valueOf(stringPtr),
                        Integer.valueOf(blockCount), Long.valueOf(blockSizes),
                        Integer.valueOf(focused), Integer.valueOf(caret));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void enable(long window) throws Exception {
        Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
        if (function(glfw, "SetPreeditCallback") == 0L) {
            if (!loggedSkip) {
                loggedSkip = true;
                System.err.println("[PMCL IME] 当前 GLFW 没有输入法接口，已跳过");
            }
            return;
        }
        installCallback(glfw, window);
        Method setMode = glfw.getMethod("glfwSetInputMode", long.class, int.class, int.class);
        setMode.invoke(null, Long.valueOf(window), Integer.valueOf(GLFW_IME), Integer.valueOf(1));
        place(glfw, window);
        clearError(glfw);
        System.err.println("[PMCL IME] 已为窗口启用输入法");
    }

    private static void installCallback(Class<?> glfw, long window) {
        if (function(glfw, "SetPreeditCallback") == 0L) return;
        try {
            Class.forName("org.lwjgl.glfw.GLFWPreeditCallback", false, glfw.getClassLoader());
        } catch (Throwable t) {
            System.err.println("[PMCL IME] 没有预编辑回调类，仅打开输入法开关");
            return;
        }
        Method setter = null;
        Method[] methods = glfw.getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            Class<?>[] params = m.getParameterTypes();
            if ("glfwSetPreeditCallback".equals(m.getName())
                    && params.length == 2
                    && params[0] == long.class
                    && !params[1].isPrimitive()) {
                setter = m;
                break;
            }
        }
        if (setter == null) return;
        try {
            if (callbackRef == null) {
                Class<?> type = defineCallback(glfw, callbackBytes());
                callbackRef = type.getDeclaredConstructor().newInstance();
            }
            Object prev = setter.invoke(null, Long.valueOf(window), callbackRef);
            if (prev != null && prev != callbackRef) previousCallback = prev;
        } catch (Throwable t) {
            System.err.println("[PMCL IME] 预编辑回调未装上: " + t.getClass().getSimpleName());
        }
    }

    private static void place(Class<?> glfw, long window) {
        if (function(glfw, "SetPreeditCursorRectangle") == 0L) return;
        try {
            int[] wh = windowSize(glfw, window);
            int width = wh[0] > 0 ? wh[0] : 800;
            int height = wh[1] > 0 ? wh[1] : 600;
            int rectW = Math.min(480, Math.max(120, width - 24));
            int x = 12;
            int y = Math.max(0, height - 56);
            Method m = glfw.getMethod("glfwSetPreeditCursorRectangle",
                    long.class, int.class, int.class, int.class, int.class);
            m.invoke(null, Long.valueOf(window), Integer.valueOf(x), Integer.valueOf(y),
                    Integer.valueOf(rectW), Integer.valueOf(28));
        } catch (Throwable ignored) {
        }
    }

    private static int[] windowSize(Class<?> glfw, long window) {
        int[] wh = new int[]{0, 0};
        try {
            Method m = glfw.getMethod("glfwGetWindowSize", long.class, IntBuffer.class, IntBuffer.class);
            IntBuffer w = IntBuffer.allocate(1);
            IntBuffer h = IntBuffer.allocate(1);
            m.invoke(null, Long.valueOf(window), w, h);
            wh[0] = w.get(0);
            wh[1] = h.get(0);
        } catch (Throwable ignored) {
        }
        return wh;
    }

    private static long function(Class<?> glfw, String name) {
        try {
            Class<?> functions = Class.forName("org.lwjgl.glfw.GLFW$Functions", false, glfw.getClassLoader());
            Field field = functions.getField(name);
            return field.getLong(null);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void clearError(Class<?> glfw) {
        try {
            Method[] methods = glfw.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if ("glfwGetError".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    m.invoke(null, new Object[]{null});
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> defineCallback(Class<?> glfw, byte[] bytes) throws Exception {
        ClassLoader loader = glfw.getClassLoader();
        if (loader == null) loader = ImeFixAgent.class.getClassLoader();
        try {
            return Class.forName("com.pmcl.core.ime.PmclPreeditCallback", false, loader);
        } catch (ClassNotFoundException ignored) {
        }
        if (loader == ImeFixAgent.class.getClassLoader()) {
            try {
                Method define = MethodHandles.Lookup.class.getMethod("defineClass", byte[].class);
                return (Class<?>) define.invoke(MethodHandles.lookup(), new Object[]{bytes});
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field uf = unsafeClass.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object unsafe = uf.get(null);
        Method define = unsafeClass.getMethod("defineClass", String.class, byte[].class, int.class, int.class,
                ClassLoader.class, ProtectionDomain.class);
        return (Class<?>) define.invoke(unsafe, new Object[]{
                "com.pmcl.core.ime.PmclPreeditCallback", bytes, Integer.valueOf(0), Integer.valueOf(bytes.length),
                loader, null
        });
    }

    /** 版本 49，避免生成 stack map。运行时再链到游戏里的 GLFWPreeditCallback。 */
    private static byte[] callbackBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/pmcl/core/ime/PmclPreeditCallback", null,
                "org/lwjgl/glfw/GLFWPreeditCallback", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/lwjgl/glfw/GLFWPreeditCallback", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor invoke = cw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "(JIJIJII)V", null, null);
        invoke.visitCode();
        invoke.visitVarInsn(Opcodes.LLOAD, 1);
        invoke.visitVarInsn(Opcodes.ILOAD, 3);
        invoke.visitVarInsn(Opcodes.LLOAD, 4);
        invoke.visitVarInsn(Opcodes.ILOAD, 6);
        invoke.visitVarInsn(Opcodes.LLOAD, 7);
        invoke.visitVarInsn(Opcodes.ILOAD, 9);
        invoke.visitVarInsn(Opcodes.ILOAD, 10);
        invoke.visitMethodInsn(Opcodes.INVOKESTATIC, "com/pmcl/core/ime/ImeFixAgent", "onPreedit", "(JIJIJII)V", false);
        invoke.visitInsn(Opcodes.RETURN);
        invoke.visitMaxs(10, 11);
        invoke.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            if (className == null || !"org/lwjgl/glfw/GLFW".equals(className)) return null;
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (mv == null) return null;
                        if (!"glfwCreateWindow".equals(name)
                                || descriptor == null
                                || !descriptor.endsWith(")J")
                                || (access & Opcodes.ACC_NATIVE) != 0
                                || (access & Opcodes.ACC_ABSTRACT) != 0) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.LRETURN) {
                                    super.visitInsn(Opcodes.DUP2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "com/pmcl/core/ime/ImeFixAgent", "onWindow", "(J)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                System.err.println("[PMCL IME] patched glfwCreateWindow");
                return cw.toByteArray();
            } catch (Throwable t) {
                System.err.println("[PMCL IME] patch failed: " + t.getMessage());
                return null;
            }
        }
    }
}
