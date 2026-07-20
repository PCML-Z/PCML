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
        if (url == null || url.isEmpty()) {
            throw new IOException("URL is null or empty");
        }
        System.err.println("[WikiBrowser] Opening URL: " + url);
        String os = System.getProperty("os.name", "").toLowerCase();
        // macOS：使用完整路径 /usr/bin/open 避免 GUI 进程 PATH 受限。
        // 不用 Desktop.browse()——它对含 ?&= 的 OAuth URL 可能误识别为文件路径而打开访达。
        if (os.contains("mac")) {
            IOException last = null;
            // 方案 1：/usr/bin/open（直接传 URL，open 命令对 http(s):// 开头的参数会交给浏览器）
            try {
                new ProcessBuilder("/usr/bin/open", url).start();
                return;
            } catch (IOException ioe) {
                last = ioe;
                System.err.println("[WikiBrowser] /usr/bin/open 失败: " + ioe.getMessage());
            }
            // 方案 2：osascript + open location（明确语义为打开 URL）
            try {
                String script = "open location \"" + url.replace("\"", "\\\"") + "\"";
                new ProcessBuilder("/usr/bin/osascript", "-e", script).start();
                return;
            } catch (IOException ioe) {
                last = ioe;
                System.err.println("[WikiBrowser] osascript 失败: " + ioe.getMessage());
            }
            // 方案 3：Desktop API（最后手段）
            if (isSupported()) {
                try {
                    Desktop.getDesktop().browse(URI.create(url));
                    return;
                } catch (Throwable t) {
                    System.err.println("[WikiBrowser] Desktop.browse 失败: " + t);
                    throw new IOException("macOS 打开浏览器失败: open=" + (last != null ? last.getMessage() : "N/A") + ", Desktop=" + t.getMessage());
                }
            }
            throw new IOException("macOS 打开浏览器失败: " + (last != null ? last.getMessage() : "unknown"));
        }
        // Windows / Linux
        if (isSupported()) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            } catch (Throwable t) {
                System.err.println("[WikiBrowser] Desktop.browse 失败，尝试系统命令兜底: " + t);
            }
        }
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else {
            cmd = new String[]{"xdg-open", url};
        }
        new ProcessBuilder(cmd).start();
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
