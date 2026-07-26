package com.pmcl.core.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Mod 文件管理：删除 / 禁用 / 启用 / 打开 mods 目录。
 * <p>
 * 禁用机制：将 jar 重命名为 .jar.disabled（MC 加载器不会加载此后缀的文件）。
 * 启用机制：去掉 .disabled 后缀还原。
 * <p>
 * 所有操作均针对 mods 根目录（即 config.getWorkDir().resolve("mods")），
 * 与 MC 加载器约定一致。MC 1.20+ 也支持子目录分类，但禁用操作只处理根目录文件。
 */
public final class ModManager {

    private final Path modsDir;

    /** scanDirectory 结果缓存（按 mods 目录 mtime 失效） */
    private volatile List<ModMeta> cachedMods;
    /** 缓存对应的 mods 目录 mtime（毫秒） */
    private volatile long cachedModsTime;

    public ModManager(Path modsDir) {
        this.modsDir = modsDir;
    }

    public Path getModsDir() { return modsDir; }

    /** 确保 mods 目录存在 */
    public void ensureModsDir() throws IOException {
        if (!Files.isDirectory(modsDir)) {
            Files.createDirectories(modsDir);
        }
    }

    /**
     * 删除指定 jar 文件（按文件名定位，支持 .disabled 后缀）。
     * @return true 删除成功
     */
    public boolean deleteMod(String jarFileName) throws IOException {
        return deleteModAt(resolveJar(jarFileName));
    }

    /**
     * 按绝对路径删除 jar（支持版本目录 / 实例目录下的 mod）。
     */
    public boolean deleteModAt(Path jarPath) throws IOException {
        if (jarPath == null || !Files.exists(jarPath)) return false;
        Files.delete(jarPath);
        invalidateCache();
        return true;
    }

    /**
     * 禁用 mod：将 xxx.jar 重命名为 xxx.jar.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    public String disableMod(String jarFileName) throws IOException {
        return disableModAt(resolveJar(jarFileName));
    }

    /**
     * 按绝对路径禁用 mod（支持版本目录 / 实例目录）。
     */
    public String disableModAt(Path jarPath) throws IOException {
        if (jarPath == null) throw new IOException("文件路径为空");
        String name = jarPath.getFileName().toString();
        if (name.toLowerCase().endsWith(".disabled")) return name;
        Path dst = jarPath.resolveSibling(name + ".disabled");
        if (!Files.exists(jarPath)) throw new IOException("文件不存在: " + jarPath);
        Files.move(jarPath, dst);
        invalidateCache();
        return dst.getFileName().toString();
    }

    /**
     * 启用 mod：将 xxx.jar.disabled 重命名为 xxx.jar。
     * 已启用的文件不变。
     * @return 新文件名（启用后）
     */
    public String enableMod(String jarFileName) throws IOException {
        return enableModAt(resolveJar(jarFileName));
    }

    /**
     * 按绝对路径启用 mod（支持版本目录 / 实例目录）。
     */
    public String enableModAt(Path jarPath) throws IOException {
        if (jarPath == null) throw new IOException("文件路径为空");
        String name = jarPath.getFileName().toString();
        if (!name.toLowerCase().endsWith(".disabled")) return name;
        String enabledName = name.substring(0, name.length() - ".disabled".length());
        Path dst = jarPath.resolveSibling(enabledName);
        if (!Files.exists(jarPath)) throw new IOException("文件不存在: " + jarPath);
        // 目标已存在（同名 jar 已启用）→ 删除禁用副本
        if (Files.exists(dst)) {
            Files.delete(jarPath);
            invalidateCache();
            return enabledName;
        }
        Files.move(jarPath, dst);
        invalidateCache();
        return enabledName;
    }

    /** 优先按绝对路径；否则回退到全局 mods 目录下的文件名 */
    private Path resolveJar(String jarFileName) {
        if (jarFileName == null || jarFileName.isEmpty()) {
            throw new IllegalArgumentException("jar 文件名为空");
        }
        Path asPath = Path.of(jarFileName);
        if (asPath.isAbsolute()) return asPath;
        return modsDir.resolve(jarFileName);
    }

    /**
     * 判断指定 mod 文件当前是否被禁用。
     */
    public boolean isDisabled(String jarFileName) {
        return jarFileName != null && jarFileName.toLowerCase().endsWith(".disabled");
    }

    /**
     * 检测同名 mod 是否已安装（按 modId 匹配，避免重复下载）。
     * 利用 mods 目录 mtime 缓存扫描结果，目录未变动时直接复用缓存。
     */
    public boolean isModInstalled(String modId) throws IOException {
        List<ModMeta> mods = getCachedMods();
        for (ModMeta m : mods) {
            if (modId.equals(m.getModId()) && !m.isDisabled()) return true;
        }
        return false;
    }

    /**
     * 获取（必要时扫描并缓存）mods 目录下的 mod 列表。
     * 当 mods 目录 mtime 与缓存一致时直接返回缓存，避免重复扫描。
     */
    private List<ModMeta> getCachedMods() throws IOException {
        long currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(modsDir).toMillis();
        } catch (IOException e) {
            // 目录不存在等异常：直接扫描不缓存
            return ModScanner.scanDirectory(modsDir);
        }
        List<ModMeta> cached = cachedMods;
        if (cached != null && cachedModsTime == currentMtime) {
            return cached;
        }
        List<ModMeta> mods = ModScanner.scanDirectory(modsDir);
        cachedMods = mods;
        cachedModsTime = currentMtime;
        return mods;
    }

    /**
     * 清除扫描结果缓存。应在 mod 安装/卸载/增删后调用，确保下次查询重新扫描。
     */
    public void invalidateCache() {
        cachedMods = null;
        cachedModsTime = 0;
    }
}
