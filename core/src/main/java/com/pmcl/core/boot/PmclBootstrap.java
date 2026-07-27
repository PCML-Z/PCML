package com.pmcl.core.boot;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧版本启动入口类：解决 LaunchWrapper 在 Java 9+ 上的 URLClassLoader 兼容问题。
 * <p>
 * LaunchWrapper 1.12 的 Launch.&lt;init&gt; 执行：
 *   ((URLClassLoader) getClass().getClassLoader()).getURLs()
 * <p>
 * 注意：不能把 AppClassLoader 当作 parent。游戏 jar 已在 {@code -cp} 上时，
 * parent-first 会让 Launch 被 AppClassLoader 加载，{@code getClassLoader()} 仍不是
 * URLClassLoader，强转失败。因此 parent 使用 PlatformClassLoader，由本 URLClassLoader
 * 亲自加载 LaunchWrapper / 游戏类。
 * <p>
 * 更稳妥的做法是同时替换为 Java 9+ LaunchWrapper（见 {@code RetroWrapperSupport}），
 * 本类作为双保险保留。
 */
public class PmclBootstrap {

    /** 回退时尝试的 Minecraft 主类列表（按优先级） */
    private static final String[] MINECRAFT_MAIN_CLASSES = {
            "net.minecraft.client.main.Main",
            "net.minecraft.client.Minecraft",
            "net.minecraft.server.MinecraftServer"
    };

    public static void main(String[] args) throws Exception {
        URL[] urls = parseClasspath();
        // Platform parent: java.* 仍可用，但不会抢先加载 classpath 上的游戏/LaunchWrapper 类
        ClassLoader parent = ClassLoader.getPlatformClassLoader();
        URLClassLoader gameLoader = new URLClassLoader(urls, parent);
        Thread.currentThread().setContextClassLoader(gameLoader);

        System.err.println("[PmclBootstrap] URLClassLoader 已创建 (urls=" + urls.length
                + ", parent=platform)");

        String mainClassName = System.getProperty("pmcl.launch.mainclass",
                "net.minecraft.launchwrapper.Launch");
        System.err.println("[PmclBootstrap] 目标主类: " + mainClassName);

        try {
            Class<?> mainClass = Class.forName(mainClassName, true, gameLoader);
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ClassCastException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("URLClassLoader")) {
                boolean hasTweaker = false;
                for (String a : args) {
                    if ("--tweakClass".equals(a)) {
                        hasTweaker = true;
                        break;
                    }
                }
                System.err.println("[PmclBootstrap] LaunchWrapper URLClassLoader 强转失败");
                if (hasTweaker) {
                    throw new RuntimeException(
                            "[PmclBootstrap] LaunchWrapper 无法在当前 Java 上运行，且存在 --tweakClass"
                                    + "（如 OptiFine/Forge），不能跳过。请改用 Java 8，或确保已注入 Java 9+ LaunchWrapper。",
                            cause);
                }
                System.err.println("[PmclBootstrap] 尝试直接调用 Minecraft 主类（跳过 Forge/OptiFine）");
                tryDirectMinecraftLaunch(args, gameLoader);
            } else {
                throw e;
            }
        }
    }

    /**
     * 直接调用 Minecraft 主类（跳过 LaunchWrapper）。
     * 仅适用于原版/非 Forge 版本。Forge 整合包需 Java 8。
     */
    private static void tryDirectMinecraftLaunch(String[] args, ClassLoader gameLoader) throws Exception {
        for (String className : MINECRAFT_MAIN_CLASSES) {
            try {
                Class<?> mcMain = Class.forName(className, true, gameLoader);
                Method mainMethod = mcMain.getMethod("main", String[].class);
                System.err.println("[PmclBootstrap] 找到并调用: " + className);
                mainMethod.invoke(null, (Object) args);
                return;
            } catch (ClassNotFoundException ignored) {
                // 继续尝试下一个
            }
        }
        throw new RuntimeException(
                "[PmclBootstrap] 无法找到 Minecraft 主类，尝试过: "
                        + String.join(", ", MINECRAFT_MAIN_CLASSES)
                        + "。如使用 Forge，请安装 Java 8。");
    }

    private static URL[] parseClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        String separator = File.pathSeparator;
        String[] parts = classpath.split(separator);
        List<URL> urls = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            try {
                urls.add(new File(part).toURI().toURL());
            } catch (Exception ignored) {
            }
        }
        return urls.toArray(new URL[0]);
    }
}
