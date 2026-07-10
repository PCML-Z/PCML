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
 * LaunchWrapper（Forge 1.6-1.12.2 使用）的 Launch.<init> 执行：
 *   ((URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs()
 * Java 9+ 的 AppClassLoader 不再继承 URLClassLoader，强转失败。
 * <p>
 * 此问题在架构层面无法绕过（Java 9+ 模块系统限制），只能：
 * 1. 用 Java 8 启动（推荐）
 * 2. 跳过 LaunchWrapper，直接调用 Minecraft 主类（仅原版/非 Forge 版本可用）
 * <p>
 * 本类作为入口点，用 URLClassLoader 加载游戏类，并尝试：
 * - 直接调用原主类的 main 方法
 * - 如果原主类是 LaunchWrapper 且强转失败，回退到直接调用 Minecraft 主类
 *   （跳过 Forge mod 加载，原版可玩，Forge 整合包需 Java 8）
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
        ClassLoader sysLoader = ClassLoader.getSystemClassLoader();
        URLClassLoader gameLoader = new URLClassLoader(urls, sysLoader);
        Thread.currentThread().setContextClassLoader(gameLoader);

        System.err.println("[PmclBootstrap] URLClassLoader 已创建 (urls=" + urls.length + ")");

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
                // LaunchWrapper 强转失败，尝试直接调用 Minecraft 主类
                System.err.println("[PmclBootstrap] LaunchWrapper URLClassLoader 强转失败");
                System.err.println("[PmclBootstrap] 尝试直接调用 Minecraft 主类（跳过 Forge）");
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
