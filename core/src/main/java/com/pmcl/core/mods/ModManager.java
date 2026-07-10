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
        Path target = modsDir.resolve(jarFileName);
        if (!Files.exists(target)) return false;
        Files.delete(target);
        return true;
    }

    /**
     * 禁用 mod：将 xxx.jar 重命名为 xxx.jar.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    public String disableMod(String jarFileName) throws IOException {
        // 已禁用的文件直接返回
        if (jarFileName.toLowerCase().endsWith(".disabled")) return jarFileName;
        Path src = modsDir.resolve(jarFileName);
        Path dst = modsDir.resolve(jarFileName + ".disabled");
        if (!Files.exists(src)) throw new IOException("文件不存在: " + jarFileName);
        Files.move(src, dst);
        return dst.getFileName().toString();
    }

    /**
     * 启用 mod：将 xxx.jar.disabled 重命名为 xxx.jar。
     * 已启用的文件不变。
     * @return 新文件名（启用后）
     */
    public String enableMod(String jarFileName) throws IOException {
        if (!jarFileName.toLowerCase().endsWith(".disabled")) return jarFileName;
        Path src = modsDir.resolve(jarFileName);
        String enabledName = jarFileName.substring(0, jarFileName.length() - ".disabled".length());
        Path dst = modsDir.resolve(enabledName);
        if (!Files.exists(src)) throw new IOException("文件不存在: " + jarFileName);
        // 目标已存在（同名 jar 已启用）→ 删除禁用副本
        if (Files.exists(dst)) {
            Files.delete(src);
            return enabledName;
        }
        Files.move(src, dst);
        return enabledName;
    }

    /**
     * 判断指定 mod 文件当前是否被禁用。
     */
    public boolean isDisabled(String jarFileName) {
        return jarFileName != null && jarFileName.toLowerCase().endsWith(".disabled");
    }

    /**
     * 检测同名 mod 是否已安装（按 modId 匹配，避免重复下载）。
     */
    public boolean isModInstalled(String modId) throws IOException {
        List<ModMeta> mods = ModScanner.scanDirectory(modsDir);
        for (ModMeta m : mods) {
            if (modId.equals(m.getModId()) && !m.isDisabled()) return true;
        }
        return false;
    }
}
