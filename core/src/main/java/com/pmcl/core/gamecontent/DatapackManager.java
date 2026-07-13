package com.pmcl.core.gamecontent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * 数据包管理：扫描指定世界的 datapacks/ 子目录。
 * <p>
 * 数据包位于 {@code saves/<world>/datapacks/}，可以是 .zip 或目录。
 * 每个数据包根目录含 pack.mcmeta，pack_format 标识兼容版本。
 * <p>
 * 注意：本类只读不写启用状态。Minecraft 在加载时根据 enabled.json 决定启用列表，
 * 修改该文件需要游戏未运行，否则会被覆盖。
 */
public final class DatapackManager {

    public DatapackManager() {}

    public static final class Datapack {
        private final String name;
        private final Path path;
        private final int packFormat;
        private final String description;
        private final boolean isZip;
        private final boolean disabled;     // 是否被禁用（.zip.disabled 或 .disabled 目录）

        public Datapack(String name, Path path, int packFormat, String description,
                        boolean isZip, boolean disabled) {
            this.name = name; this.path = path;
            this.packFormat = packFormat; this.description = description;
            this.isZip = isZip;
            this.disabled = disabled;
        }
        public String getName() { return name; }
        public Path getPath() { return path; }
        public int getPackFormat() { return packFormat; }
        public String getDescription() { return description; }
        public boolean isZip() { return isZip; }
        public boolean isDisabled() { return disabled; }
    }

    /** 扫描指定世界的 datapacks 目录 */
    public List<Datapack> list(Path worldDir) throws IOException {
        Path dpDir = worldDir.resolve("datapacks");
        List<Datapack> result = new ArrayList<>();
        if (!Files.isDirectory(dpDir)) return result;
        try (Stream<Path> stream = Files.list(dpDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase();
                Datapack dp;
                if (lower.endsWith(".zip.disabled") && Files.isRegularFile(p)) {
                    dp = parseZip(p, true);
                } else if (lower.endsWith(".zip") && Files.isRegularFile(p)) {
                    dp = parseZip(p, false);
                } else if (Files.isDirectory(p)) {
                    boolean disabled = lower.endsWith(".disabled");
                    dp = parseDir(p, disabled);
                } else {
                    dp = null;
                }
                if (dp != null) result.add(dp);
            });
        }
        return result;
    }

    /**
     * 启用数据包：将 xxx.zip.disabled 重命名为 xxx.zip，或将 xxx.disabled 目录重命名为 xxx。
     * 若目标已存在，则删除 .disabled 副本。
     * @return 新文件名（启用后）
     */
    public String enable(Path worldDir, String fileName) throws IOException {
        if (!fileName.toLowerCase().endsWith(".disabled")) return fileName;
        Path dpDir = worldDir.resolve("datapacks");
        Path src = dpDir.resolve(fileName);
        String enabledName = fileName.substring(0, fileName.length() - ".disabled".length());
        Path dst = dpDir.resolve(enabledName);
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
     * 禁用数据包：将 xxx.zip 重命名为 xxx.zip.disabled，或将 xxx 目录重命名为 xxx.disabled。
     * 已禁用的文件不变。
     * @return 新文件名（禁用后）
     */
    public String disable(Path worldDir, String fileName) throws IOException {
        if (fileName.toLowerCase().endsWith(".disabled")) return fileName;
        Path dpDir = worldDir.resolve("datapacks");
        Path src = dpDir.resolve(fileName);
        Path dst = dpDir.resolve(fileName + ".disabled");
        if (!Files.exists(src)) throw new IOException("文件不存在: " + fileName);
        Files.move(src, dst);
        return dst.getFileName().toString();
    }

    public void delete(Datapack pack) throws IOException {
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

    private Datapack parseZip(Path zipPath, boolean disabled) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            // 显示名需去除 .disabled 后缀
            String display = disabled
                    ? stripDisabledSuffix(zipPath.getFileName().toString())
                    : zipPath.getFileName().toString();
            String name = stripZipSuffix(display);
            if (entry == null) return new Datapack(name, zipPath, 0, "", true, disabled);
            String meta;
            try (var in = zip.getInputStream(entry)) {
                meta = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return build(name, zipPath, meta, true, disabled);
        } catch (Throwable e) {
            return null;
        }
    }

    private Datapack parseDir(Path dir, boolean disabled) {
        Path meta = dir.resolve("pack.mcmeta");
        // 显示名需去除 .disabled 后缀
        String name = disabled
                ? stripDisabledSuffix(dir.getFileName().toString())
                : dir.getFileName().toString();
        if (!Files.exists(meta)) return new Datapack(name, dir, 0, "", false, disabled);
        try {
            return build(name, dir, Files.readString(meta), false, disabled);
        } catch (Throwable e) {
            return null;
        }
    }

    private Datapack build(String name, Path path, String mcmeta, boolean isZip, boolean disabled) {
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
        return new Datapack(name, path, packFormat, description, isZip, disabled);
    }

    private static String stripDisabledSuffix(String s) {
        return s.toLowerCase().endsWith(".disabled")
                ? s.substring(0, s.length() - ".disabled".length()) : s;
    }

    private static String stripZipSuffix(String s) {
        return s.toLowerCase().endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
    }
}
