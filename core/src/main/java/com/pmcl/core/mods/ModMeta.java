package com.pmcl.core.mods;

import java.util.Collections;
import java.util.List;

/**
 * 一个已安装的 mod 元数据（从 jar 内 fabric.mod.json / mods.toml / META-INF 解析）。
 */
public final class ModMeta {

    private String modId;
    private String version;
    private String name;
    private String description;
    private String authors;
    private String loader;          // fabric / forge / quilt / neoforge / unknown
    private List<String> depends;   // 依赖的 modId
    private List<String> conflicts; // 冲突的 modId
    private String jarFile;         // jar 文件名（含 .disabled 后缀则被禁用）
    private boolean disabled;       // 是否被禁用（.jar.disabled 后缀）

    public ModMeta(String modId, String version, String name, String description,
                   String authors, String loader, List<String> depends,
                   List<String> conflicts, String jarFile) {
        this(modId, version, name, description, authors, loader, depends, conflicts,
                jarFile, jarFile != null && jarFile.toLowerCase().endsWith(".disabled"));
    }

    public ModMeta(String modId, String version, String name, String description,
                   String authors, String loader, List<String> depends,
                   List<String> conflicts, String jarFile, boolean disabled) {
        this.modId = modId;
        this.version = version;
        this.name = name;
        this.description = description;
        this.authors = authors;
        this.loader = loader;
        this.depends = depends == null ? Collections.emptyList() : Collections.unmodifiableList(depends);
        this.conflicts = conflicts == null ? Collections.emptyList() : Collections.unmodifiableList(conflicts);
        this.jarFile = jarFile;
        this.disabled = disabled;
    }

    public String getModId() { return modId; }
    public String getVersion() { return version; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getAuthors() { return authors; }
    public String getLoader() { return loader; }
    public List<String> getDepends() { return depends; }
    public List<String> getConflicts() { return conflicts; }
    public String getJarFile() { return jarFile; }
    public boolean isDisabled() { return disabled; }

    @Override
    public String toString() {
        return name + " (" + modId + " v" + version + ", " + loader
                + (disabled ? ", 已禁用" : "") + ")";
    }
}
