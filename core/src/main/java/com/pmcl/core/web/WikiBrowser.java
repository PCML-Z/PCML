package com.pmcl.core.web;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Wiki / 网页浏览辅助：在系统默认浏览器中打开 URL，或构造搜索 URL。
 * <p>
 * 桌面端嵌入式 WebView 需要 JavaFX 依赖，为保持轻量，
 * 本类采用「在系统浏览器打开」方案，UI 仅提供入口按钮与搜索框。
 */
public final class WikiBrowser {

    private WikiBrowser() {}

    /** 是否支持系统浏览器 */
    public static boolean isSupported() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
    }

    /** 在系统浏览器打开指定 URL */
    public static void open(String url) throws IOException {
        // 优先尝试 Desktop API（部分平台/线程下会静默失败）
        if (isSupported()) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            } catch (Throwable t) {
                System.err.println("[WikiBrowser] Desktop.browse 失败，尝试系统命令兜底: " + t);
            }
        }
        // Fallback：用平台特定命令打开浏览器
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("mac")) {
            cmd = new String[]{"open", url};
        } else if (os.contains("win")) {
            // Windows 需要对 URL 中的 & 等特殊字符做保护，rundll32 单参数模式
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            // Linux / Unix
            cmd = new String[]{"xdg-open", url};
        }
        try {
            Runtime.getRuntime().exec(cmd);
        } catch (IOException ioe) {
            System.err.println("[WikiBrowser] 系统命令打开失败: " + ioe.getMessage());
            throw ioe;
        }
    }

    /** 构造 Modrinth 项目页 URL */
    public static String modrinthProjectUrl(String slug) {
        return "https://modrinth.com/mod/" + slug;
    }

    /** 构造 CurseForge 项目页 URL（projectId） */
    public static String curseForgeProjectUrl(int projectId) {
        return "https://www.curseforge.com/minecraft/mc-mods/" + projectId;
    }

    /** 构造 Google 搜索 URL */
    public static String searchUrl(String query) {
        return "https://www.google.com/search?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    /** 构造 Minecraft Wiki 搜索 URL */
    public static String minecraftWikiSearchUrl(String query) {
        return "https://minecraft.wiki/?search=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
    }
}
