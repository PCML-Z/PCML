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
        if (!isSupported()) {
            throw new IOException("当前平台不支持系统浏览器");
        }
        Desktop.getDesktop().browse(URI.create(url));
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
