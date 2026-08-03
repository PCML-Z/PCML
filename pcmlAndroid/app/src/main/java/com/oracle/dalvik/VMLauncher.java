package com.oracle.dalvik;

/**
 * JVM 启动器 — JNI 桥接到 libpojavexec.so。
 * 注意：native 库仅在 launchJVM 调用前才加载，避免启动时 native crash。
 */
public final class VMLauncher {
    private VMLauncher() {}

    private static volatile boolean loaded = false;

    private static synchronized void ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("pojavexec");
            loaded = true;
        }
    }

    public static boolean isNativeAvailable() {
        try { ensureLoaded(); return true; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    public static native int launchJVM(String[] args);
}
