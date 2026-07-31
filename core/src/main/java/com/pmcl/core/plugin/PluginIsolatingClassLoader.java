package com.pmcl.core.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;

/**
 * 插件隔离 ClassLoader。
 * <p>
 * 父 ClassLoader 固定为 {@link ClassLoader#getPlatformClassLoader()}，
 * <b>不</b>把应用 ClassLoader 设为 parent，从而阻止
 * {@code getClass().getClassLoader().getParent().loadClass("com.pmcl.core...")}
 * 绕过本加载器的包过滤（C1）。
 * <p>
 * 允许的宿主类（{@code com.pmcl.plugin.*}、Compose/Kotlin/Gson 等）通过私有
 * {@code hostClassLoader} 桥接加载；该引用不可经 {@link #getParent()} 取得。
 * <p>
 * 安全说明：这是深度防御，不是进程级沙箱。反射读取本类私有字段、JNI 等仍可能绕过；
 * {@code ProcessBuilder}/{@code Process*} 经 {@link #loadClass} 硬禁；敏感能力仍应以 typed API + 权限为准。
 * {@code Runtime} 对 HMCL 等插件为必需，故允许从 platform 加载。
 */
public final class PluginIsolatingClassLoader extends URLClassLoader {

    /** 允许经宿主 ClassLoader 加载的 PMCL 包前缀。 */
    private static final String[] ALLOWED_PMCL_PREFIXES = {
        "com.pmcl.plugin.",
    };

    /**
     * 允许经宿主 ClassLoader 加载的第三方前缀。
     * 不含 {@code java.*}/{@code javax.*}——那些走 platform parent。
     * <p>
     * {@code javafx.*} 必须走宿主：插件自带 JavaFX 时，QuantumToolkit 会经插件 CL
     * 解析 {@code java.lang.Runtime}，被安全策略拦截并表现为 “No toolkit found”。
     */
    private static final String[] ALLOWED_HOST_THIRD_PARTY_PREFIXES = {
        "androidx.compose.",
        "kotlin.",
        "kotlinx.",
        "org.jetbrains.annotations.",
        "org.intellij.lang.annotations.",
        "com.google.gson.",
        "org.slf4j.",
        "javafx.",
    };

    /** 额外硬禁：即使双亲委派也不允许插件主动 loadClass 这些内部 API。 */
    private static final String[] FORBIDDEN_JDK_PREFIXES = {
        "sun.misc.",
        "sun.reflect.",
        "jdk.internal.",
        "com.sun.jndi.",
        "com.sun.net.ssl.internal.",
    };

    /**
     * Exact JDK classes that enable process spawn / host escape.
     * Matched before platform-parent delegation so {@code Class.forName} cannot load them.
     * <p>
     * Note: {@code java.lang.Runtime} is intentionally allowed — HMCL (and many plugins)
     * need it for memory/CPU probes and download workers. {@code Runtime.exec} remains a
     * residual risk for signed plugins; prefer blocking {@code ProcessBuilder} instead.
     */
    private static final String[] FORBIDDEN_JDK_EXACT = {
        "java.lang.ProcessBuilder",
        "java.lang.Process",
        "java.lang.ProcessHandle",
        "java.lang.ProcessImpl",
        "java.lang.UNIXProcess",
    };

    /** 完全禁止的 PMCL core 包前缀。 */
    private static final String[] FORBIDDEN_PMCL_PREFIXES = {
        "com.pmcl.core.auth.",
        "com.pmcl.core.preferences.",
        "com.pmcl.core.update.",
    };

    private final String pluginId;
    /** 应用 ClassLoader；仅用于白名单包，不作为 {@link #getParent()}。 */
    private final ClassLoader hostClassLoader;

    public PluginIsolatingClassLoader(String pluginId, URL[] urls, ClassLoader host) {
        super(urls, ClassLoader.getPlatformClassLoader());
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        // Wrap so reflected access to this field still cannot load com.pmcl.core.*
        this.hostClassLoader = new FilteringHostClassLoader(Objects.requireNonNull(host, "host"));
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            if (isForbiddenJdk(name)) {
                throw new SecurityException("[Plugin:" + pluginId
                        + "] 加载 JDK 内部类被禁止: " + name);
            }

            // Hard-ban sensitive host packages even if a plugin ships a same-named class.
            if (isForbiddenPmcl(name)) {
                throw new SecurityException("[Plugin:" + pluginId
                        + "] 直接加载 PMCL 内部类被禁止: " + name
                        + "（请通过 PluginContext.getService 获取受控引用）");
            }

            Class<?> c;
            if (loadFromHost(name)) {
                // 宿主桥接：不经过 getParent()，避免插件沿 parent 链拿到 App CL
                c = hostClassLoader.loadClass(name);
            } else {
                try {
                    // Plugin-owned code may live under com.pmcl.* (e.g. com.pmcl.hmcl.*).
                    // Only block falling through to the host/platform for non-API PMCL names.
                    c = findClass(name);
                } catch (ClassNotFoundException localMiss) {
                    if (isBlockedPmcl(name)) {
                        throw new SecurityException("[Plugin:" + pluginId
                                + "] 直接加载 PMCL 内部类被禁止: " + name
                                + "（请通过 PluginContext.getService 获取受控引用）");
                    }
                    // java.* / javax.* 等：仅委派 platform parent（无法加载 com.pmcl.core.*）
                    try {
                        c = getParent().loadClass(name);
                    } catch (ClassNotFoundException parentMiss) {
                        throw localMiss;
                    }
                }
            }

            if (resolve) resolveClass(c);
            return c;
        }
    }

    private boolean loadFromHost(String name) {
        for (String prefix : ALLOWED_PMCL_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        for (String prefix : ALLOWED_HOST_THIRD_PARTY_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        // JavaFX implementation packages live under com.sun.* — bridge from host
        // so natives / toolkit share the same ClassLoader as WikiWebView.
        return isJavaFxImplementation(name);
    }

    private boolean isForbiddenPmcl(String name) {
        for (String prefix : FORBIDDEN_PMCL_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 非 API 的 com.pmcl.*：禁止从宿主加载；插件自身 classpath 上的同名前缀仍可通过 findClass 加载。 */
    private boolean isBlockedPmcl(String name) {
        if (!name.startsWith("com.pmcl.")) return false;
        for (String prefix : ALLOWED_PMCL_PREFIXES) {
            if (name.startsWith(prefix)) return false;
        }
        return true;
    }

    private boolean isForbiddenJdk(String name) {
        for (String exact : FORBIDDEN_JDK_EXACT) {
            if (name.equals(exact)) return true;
        }
        // Nested / impl types under Process* (ProcessHandle$Info, ProcessImpl, …)
        if (name.startsWith("java.lang.Process")) {
            return true;
        }
        for (String prefix : FORBIDDEN_JDK_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        // JavaFX implementation types (host-bridged) and JDK management APIs
        // (e.g. com.sun.management.OperatingSystemMXBean used by HMCL SystemInfo).
        if (isJavaFxImplementation(name) || name.startsWith("com.sun.management.")) {
            return false;
        }
        // Ban remaining sun.*/jdk.internal.*/com.sun.* internals; do not blanket-ban
        // all com.sun.* — that breaks legitimate JDK APIs HMCL needs at startup.
        return name.startsWith("sun.")
                || name.startsWith("jdk.internal.")
                || name.startsWith("jdk.vm.");
    }

    private static boolean isJavaFxImplementation(String name) {
        return name.startsWith("com.sun.javafx.")
                || name.startsWith("com.sun.glass.")
                || name.startsWith("com.sun.prism.")
                || name.startsWith("com.sun.scenario.")
                || name.startsWith("com.sun.openpisces.")
                || name.equals("com.sun.util.PropertyHelper");
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
