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

        public Pack(String name, Path path, int packFormat, String description, boolean isZip) {
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

    public List<Pack> list() throws IOException {
        List<Pack> result = new ArrayList<>();
        if (!Files.isDirectory(resourcePacksDir)) return result;
        try (Stream<Path> stream = Files.list(resourcePacksDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                Pack pack;
                if (name.toLowerCase().endsWith(".zip") && Files.isRegularFile(p)) {
                    pack = parseZipPack(p);
                } else if (Files.isDirectory(p)) {
                    pack = parseDirPack(p);
                } else {
                    pack = null;
                }
                if (pack != null) result.add(pack);
            });
        }
        return result;
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

    private Pack parseZipPack(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            if (entry == null) return new Pack(stripZipSuffix(zipPath.getFileName().toString()),
                    zipPath, 0, "", true);
            String meta;
            try (var in = zip.getInputStream(entry)) {
                meta = readAll(in);
            }
            return buildPack(zipPath.getFileName().toString(), zipPath, meta, true);
        } catch (IOException e) {
            return null;
        }
    }

    private Pack parseDirPack(Path dir) {
        Path meta = dir.resolve("pack.mcmeta");
        if (!Files.exists(meta)) {
            return new Pack(dir.getFileName().toString(), dir, 0, "", false);
        }
        try {
            String content = Files.readString(meta);
            return buildPack(dir.getFileName().toString(), dir, content, false);
        } catch (IOException e) {
            return null;
        }
    }

    private Pack buildPack(String fileName, Path path, String mcmeta, boolean isZip) {
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
        } catch (Exception ignored) {}
        String name = isZip ? stripZipSuffix(fileName) : fileName;
        return new Pack(name, path, packFormat, description, isZip);
    }

    private static String stripZipSuffix(String s) {
        return s.toLowerCase().endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
