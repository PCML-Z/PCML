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

        public Datapack(String name, Path path, int packFormat, String description, boolean isZip) {
            this.name = name; this.path = path;
            this.packFormat = packFormat; this.description = description;
            this.isZip = isZip;
        }
        public String getName() { return name; }
        public Path getPath() { return path; }
        public int getPackFormat() { return packFormat; }
        public String getDescription() { return description; }
        public boolean isZip() { return isZip; }
    }

    /** 扫描指定世界的 datapacks 目录 */
    public List<Datapack> list(Path worldDir) throws IOException {
        Path dpDir = worldDir.resolve("datapacks");
        List<Datapack> result = new ArrayList<>();
        if (!Files.isDirectory(dpDir)) return result;
        try (Stream<Path> stream = Files.list(dpDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                Datapack dp;
                if (name.toLowerCase().endsWith(".zip") && Files.isRegularFile(p)) {
                    dp = parseZip(p);
                } else if (Files.isDirectory(p)) {
                    dp = parseDir(p);
                } else {
                    dp = null;
                }
                if (dp != null) result.add(dp);
            });
        }
        return result;
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

    private Datapack parseZip(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            String name = stripZipSuffix(zipPath.getFileName().toString());
            if (entry == null) return new Datapack(name, zipPath, 0, "", true);
            String meta = new String(zip.getInputStream(entry).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return build(name, zipPath, meta, true);
        } catch (IOException e) {
            return null;
        }
    }

    private Datapack parseDir(Path dir) {
        Path meta = dir.resolve("pack.mcmeta");
        String name = dir.getFileName().toString();
        if (!Files.exists(meta)) return new Datapack(name, dir, 0, "", false);
        try {
            return build(name, dir, Files.readString(meta), false);
        } catch (IOException e) {
            return null;
        }
    }

    private Datapack build(String name, Path path, String mcmeta, boolean isZip) {
        int packFormat = 0;
        String description = "";
        try {
            JsonObject root = JsonParser.parseString(mcmeta).getAsJsonObject();
            if (root.has("pack")) {
                JsonObject pack = root.getAsJsonObject("pack");
                if (pack.has("pack_format")) packFormat = pack.get("pack_format").getAsInt();
                if (pack.has("description")) description = pack.get("description").getAsString();
            }
        } catch (Exception ignored) {}
        return new Datapack(name, path, packFormat, description, isZip);
    }

    private static String stripZipSuffix(String s) {
        return s.toLowerCase().endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
    }
}
