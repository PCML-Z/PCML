package com.pmcl.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动器全局配置：工作目录、内存、网络等。
 * <p>
 * M9 修复：默认值与 {@link com.pmcl.core.preferences.Preferences} 保持一致
 * （maxMemoryMb=4096，minMemoryMb=512）。
 * <p>
 * M10 修复：所有可变字段使用 {@code volatile} 保证跨线程可见性。
 * 不可变字段（workDir）使用 {@code final}。
 */
public final class LauncherConfig {

    /** 工作目录，构建后不可变（final） */
    private final Path workDir;
    /** 最小内存（MB），与 Preferences 默认值一致 */
    private volatile int minMemoryMb = 512;
    /** 最大内存（MB），M9 修复：与 Preferences.maxMemoryMb 默认 4096 一致 */
    private volatile int maxMemoryMb = 4096;
    /** 下载线程数 */
    private volatile int downloadThreads = 16;

    public LauncherConfig() {
        this(resolveDefaultWorkDir());
    }

    /**
     * 解析默认工作目录：优先使用 -Dpmcl.workdir 系统属性（用于绕过 macOS TCC
     * 对 ~/.pmcl 目录的 com.apple.provenance 限制），回退到 ~/.pmcl。
     * 注意：不修改 user.home，确保 TokenEncryptor 的密钥派生不受影响。
     */
    private static Path resolveDefaultWorkDir() {
        return pmclHome();
    }

    /**
     * PMCL 主目录：优先 -Dpmcl.workdir（绕过 macOS TCC com.apple.provenance），
     * 回退到 ~/.pmcl。所有模块应使用此方法替代硬编码 user.home/.pmcl。
     * 注意：TokenEncryptor 的 .keyfile 例外，必须用 user.home/.pmcl/.keyfile
     * 保证密钥派生一致性。
     */
    public static Path pmclHome() {
        String override = System.getProperty("pmcl.workdir");
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".pmcl");
    }

    public LauncherConfig(Path workDir) {
        this.workDir = workDir;
    }

    public Path getWorkDir() {
        return workDir;
    }

    public Path getVersionsDir() {
        return workDir.resolve("versions");
    }

    public Path getLibrariesDir() {
        return workDir.resolve("libraries");
    }

    public Path getAssetsDir() {
        return workDir.resolve("assets");
    }

    public Path getRuntimesDir() {
        return workDir.resolve("runtimes");
    }

    /** 实例根目录 {@code ~/.pmcl/instances/}，用于独立实例管理 */
    public Path getInstancesDir() {
        return workDir.resolve("instances");
    }

    public int getMinMemoryMb() {
        return minMemoryMb;
    }

    public void setMinMemoryMb(int minMemoryMb) {
        this.minMemoryMb = minMemoryMb;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    public int getDownloadThreads() {
        return downloadThreads;
    }

    public void setDownloadThreads(int downloadThreads) {
        this.downloadThreads = downloadThreads;
    }
}
