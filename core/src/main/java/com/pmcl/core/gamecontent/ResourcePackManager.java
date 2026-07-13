package com.pmcl.core.gamecontent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * 资源包管理：扫描 resourcepacks 目录，解析 pack.mcmeta 获取格式版本与描述。
 * <p>
 * 资源包可以是目录或 .zip 文件，pack.mcmeta 位于根目录。
 */
public final class ResourcePackManager {

    private final Path resourcePacksDir;

    public ResourcePackManager(Path workDir) {
        this.resourcePacksDir = workDir.resolve("resourcepacks");
    }

    public Path getResourcePacksDir() { return resourcePacksDir; }

    public static final class Pack {
        private final String name;
        private final Path path;
        private final int packFormat;
        private final String description;
        private final boolean isZip;
        private final boolean disabled;     // 是否被禁用（.zip.disabled 或 .disabled 目录）
        private String source;              // 来源标签（"全局" / "外部" 等）

        public Pack(String name, Path path, int packFormat, String description,
                    boolean isZip, boolean disabled, String source) {
            this.name = name; this.path = path;
            this.packFormat = packFormat; this.description = description;
            this.isZip = isZip;
            this.disabled = disabled; this.source = source;
        }
        public String getName() { return name; }
        public Path getPath() { return path; }
        public int getPackFormat() { return packFormat; }
        public String getDescription() { return description; }
        public boolean isZip() { return isZip; }
        public boolean isDisabled() { return disabled; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    public List<Pack> list() throws IOException {
        return list(resourcePacksDir, "全局");
    }

    /** 扫描指定目录下的资源包，附带来源标签 */
    public List<Pack> list(Path dir, String source) throws IOException {
        List<Pack> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase();
                Pack pack;
                if (lower.endsWith(".zip.disabled") && Files.isRegularFile(p)) {
                    pack = parseZipPack(p, true);
                } else if (lower.endsWith(".zip") && Files.isRegularFile(p)) {
                    pack = parseZipPack(p, false);
                } else if (Files.isDirectory(p)) {
                    boolean disabled = lower.endsWith(".disabled");
                    pack = parseDirPack(p, disabled);
                } else {
                    pack = null;
                }
                if (pack != null) {
                    pack.setSource(source);
                    result.add(pack);
                }
            });
        }
        return result;
    }

    /**
     * 启用资源包：将 xxx.zip.disabled 重命名为 xxx.zip，或将 xxx.disabled 目录重命名为 xxx。
     * 若目标已存在，则删除 .disabled 副本。
     * @return 新文件名（启用后）
     */
    public String enable(String fileName) throws IOException {
        if (!fileName.toLowerCase().endsWith(".disabled")) return fileName;
        Path src = resourcePacksDir.resolve(fileName);
        String enabledName = fileName.substring(0, fileName.length() - ".disabled".length());
        Path dst = resourcePacksDir.resolve(enabledName);
        if (!Files.exists(src)) throw new IOException("文件不存在: " + fileName);
        // 目标已存在 → 删除禁用副本
        if (Files.exists(dst)) {
            if (Files.isDirectory(src)) {
                try (var s = Files.walk(src)) {
                    s.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.delete(p); } catch (IOException ignored) {}
                            });
                }
            } else {
                Files.delete(src);
            }
            return enabledName;
        }
        Files.move(src, dst);
        return enabledName;
    }

    /**
     * 禁用资源包：将 xxx.zip 重命名为 xxx.zip.disabled，或将 xxx 目录重命名为 xxx.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    public String disable(String fileName) throws IOException {
        if (fileName.toLowerCase().endsWith(".disabled")) return fileName;
        Path src = resourcePacksDir.resolve(fileName);
        Path dst = resourcePacksDir.resolve(fileName + ".disabled");
        if (!Files.exists(src)) throw new IOException("文件不存在: " + fileName);
        Files.move(src, dst);
        return dst.getFileName().toString();
    }

    public void delete(Pack pack) throws IOException {
        if (pack.isZip()) {
            Files.deleteIfExists(pack.getPath());
        } else {
            try (var s = Files.walk(pack.getPath())) {
                s.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
        }
    }

    private Pack parseZipPack(Path zipPath, boolean disabled) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            // 显示名需去除 .disabled 后缀
            String display = disabled
                    ? stripDisabledSuffix(zipPath.getFileName().toString())
                    : zipPath.getFileName().toString();
            if (entry == null) return new Pack(stripZipSuffix(display),
                    zipPath, 0, "", true, disabled, null);
            String meta;
            try (var in = zip.getInputStream(entry)) {
                meta = readAll(in);
            }
            return buildPack(display, zipPath, meta, true, disabled);
        } catch (Throwable e) {
            return null;
        }
    }

    private Pack parseDirPack(Path dir, boolean disabled) {
        Path meta = dir.resolve("pack.mcmeta");
        // 显示名需去除 .disabled 后缀
        String display = disabled
                ? stripDisabledSuffix(dir.getFileName().toString())
                : dir.getFileName().toString();
        if (!Files.exists(meta)) {
            return new Pack(display, dir, 0, "", false, disabled, null);
        }
        try {
            String content = Files.readString(meta);
            return buildPack(display, dir, content, false, disabled);
        } catch (Throwable e) {
            return null;
        }
    }

    private Pack buildPack(String fileName, Path path, String mcmeta, boolean isZip, boolean disabled) {
        int packFormat = 0;
        String description = "";
        try {
            JsonObject root = JsonParser.parseString(mcmeta).getAsJsonObject();
            if (root.has("pack")) {
                JsonObject pack = root.getAsJsonObject("pack");
                if (pack.has("pack_format") && pack.get("pack_format").isJsonPrimitive())
                    packFormat = pack.get("pack_format").getAsInt();
                if (pack.has("description") && pack.get("description").isJsonPrimitive())
                    description = pack.get("description").getAsString();
            }
        } catch (Throwable ignored) {}
        // fileName 已经是去除 .disabled 的显示名
        String name = isZip ? stripZipSuffix(fileName) : fileName;
        return new Pack(name, path, packFormat, description, isZip, disabled, null);
    }

    private static String stripDisabledSuffix(String s) {
        return s.toLowerCase().endsWith(".disabled")
                ? s.substring(0, s.length() - ".disabled".length()) : s;
    }

    private static String stripZipSuffix(String s) {
        return s.toLowerCase().endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
