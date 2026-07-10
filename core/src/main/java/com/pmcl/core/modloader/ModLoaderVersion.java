package com.pmcl.core.modloader;

/**
 * 一个模组加载器的可用版本。
 */
public final class ModLoaderVersion {

    private ModLoader loader;
    private String gameVersion;   // 如 "1.20.4"
    private String loaderVersion; // 如 "0.15.7"（fabric）或 "47.2.0"（forge）
    private boolean stable;

    public ModLoaderVersion(ModLoader loader, String gameVersion, String loaderVersion, boolean stable) {
        this.loader = loader;
        this.gameVersion = gameVersion;
        this.loaderVersion = loaderVersion;
        this.stable = stable;
    }

    public ModLoader getLoader() { return loader; }
    public String getGameVersion() { return gameVersion; }
    public String getLoaderVersion() { return loaderVersion; }
    public boolean isStable() { return stable; }

    @Override
    public String toString() {
        return loader + " " + loaderVersion + " (MC " + gameVersion + ")";
    }
}
