package com.pmcl.core.modloader;

/**
 * 模组加载器类型。
 */
public enum ModLoader {
    VANILLA("Vanilla", true),
    FORGE("Forge", true),
    NEOFORGE("NeoForge", true),
    FABRIC("Fabric", true),
    QUILT("Quilt", true),
    OPTIFINE("OptiFine", true),
    LITELOADER("LiteLoader", true),
    LEGACY_FABRIC("Legacy Fabric", true),
    BABRIC("Babric", true),
    /** Better Than Adventure 专用 Babric 通道（同一 Meta，标签区分）。 */
    BTA_BABRIC("BTA (Babric)", true),
    ORNITHE("Ornithe", true),
    RIFT("Rift", true),
    JAVA_AGENT("Java Agent", true),
    RISUGAMI("Risugami's ModLoader", true),
    NILLOADER("NilLoader", true);

    private final String displayName;
    private final boolean installable;

    ModLoader(String displayName, boolean installable) {
        this.displayName = displayName;
        this.installable = installable;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 是否已接入安装器（可列出版本并安装）。 */
    public boolean isInstallable() {
        return installable;
    }
}
