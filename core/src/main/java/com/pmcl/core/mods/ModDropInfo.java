package com.pmcl.core.mods;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 拖入启动器窗口的 mod jar 的解析结果。
 * <p>
 * 包含从 jar 内 fabric.mod.json / mods.toml 解析出的基本信息，
 * 以及通过 SHA1 反查 Modrinth API 拿到的 game_versions / loaders 列表。
 * <p>
 * 当 Modrinth 反查失败或未匹配时，{@link #modrinthFound} 为 false，
 * gameVersions / loaders 为空列表，UI 应允许用户手动选择目标版本。
 */
public final class ModDropInfo {

    private final String modId;
    private final String name;
    private final String version;
    private final String loader;          // fabric / forge / quilt / neoforge / unknown
    private final String authors;
    private final String description;
    private final Path jarPath;
    private final String sha1;
    private final List<String> gameVersions;  // Modrinth 返回的兼容游戏版本列表
    private final List<String> loaders;       // Modrinth 返回的兼容加载器列表
    private final boolean modrinthFound;      // Modrinth 是否反查到匹配
    private final String parseError;          // 解析失败原因，null 表示解析成功

    public ModDropInfo(String modId, String name, String version, String loader,
                       String authors, String description, Path jarPath, String sha1,
                       List<String> gameVersions, List<String> loaders,
                       boolean modrinthFound, String parseError) {
        this.modId = modId;
        this.name = name;
        this.version = version;
        this.loader = loader;
        this.authors = authors;
        this.description = description;
        this.jarPath = jarPath;
        this.sha1 = sha1;
        this.gameVersions = gameVersions == null ? Collections.emptyList()
                : Collections.unmodifiableList(gameVersions);
        this.loaders = loaders == null ? Collections.emptyList()
                : Collections.unmodifiableList(loaders);
        this.modrinthFound = modrinthFound;
        this.parseError = parseError;
    }

    public String getModId() { return modId; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getLoader() { return loader; }
    public String getAuthors() { return authors; }
    public String getDescription() { return description; }
    public Path getJarPath() { return jarPath; }
    public String getSha1() { return sha1; }
    public List<String> getGameVersions() { return gameVersions; }
    public List<String> getLoaders() { return loaders; }
    public boolean isModrinthFound() { return modrinthFound; }
    public String getParseError() { return parseError; }

    /** 解析是否成功（仅判断 ModScanner 是否拿到了 modId） */
    public boolean isParsed() {
        return parseError == null && modId != null && !modId.isEmpty();
    }

    @Override
    public String toString() {
        return name + " (" + modId + " v" + version + ", " + loader + ")";
    }
}
