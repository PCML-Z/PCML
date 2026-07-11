package com.pmcl.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动器全局配置：工作目录、内存、网络等。
 */
public final class LauncherConfig {

    private Path workDir;
    private int minMemoryMb = 512;
    private int maxMemoryMb = 2048;
    private int downloadThreads = 16;

    public LauncherConfig() {
        this(Paths.get(System.getProperty("user.home"), ".pmcl"));
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
