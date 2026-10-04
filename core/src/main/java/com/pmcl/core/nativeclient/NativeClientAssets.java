package com.pmcl.core.nativeclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从本机已经安装的 Minecraft 准备社区客户端资源。不从第三方地址重新下载游戏文件。
 */
final class NativeClientAssets {

    private NativeClientAssets() {}

    static final class Located {
        final Path jar;
        final Path indexFile;
        final Path objectsDir;

        Located(Path jar, Path indexFile, Path objectsDir) {
            this.jar = jar;
            this.indexFile = indexFile;
            this.objectsDir = objectsDir;
        }
    }

    static Located locate(Path pmclHome, String versionId) throws IOException {
        Path official = officialMinecraft();
        Path dotted = official.resolveSibling(".minecraft");
        Path[] versionRoots = new Path[]{
                pmclHome.resolve("versions"),
                official.resolve("versions"),
                dotted.resolve("versions")
        };
        Path[] assetRoots = new Path[]{
                pmclHome.resolve("assets"),
                official.resolve("assets"),
                dotted.resolve("assets")
        };
        for (int i = 0; i < versionRoots.length; i++) {
            Path versionDir = versionRoots[i].resolve(versionId);
            Path jar = versionDir.resolve(versionId + ".jar");
            if (!Files.isRegularFile(jar)) continue;
            String indexId = indexId(versionDir.resolve(versionId + ".json"), versionId);
            Path index = assetRoots[i].resolve("indexes").resolve(indexId + ".json");
            Path objects = assetRoots[i].resolve("objects");
            if (Files.isRegularFile(index) && Files.isDirectory(objects)) {
                return new Located(jar, index, objects);
            }
        }
        return null;
    }

    static void stage(NativeClientCatalog.Spec spec, Path workDir, Located source) throws IOException {
        if (spec.layout == NativeClientCatalog.AssetLayout.NONE || source == null) return;
        Path assetsRoot = spec.layout == NativeClientCatalog.AssetLayout.LOGICAL
                ? workDir.resolve("runtime").resolve("assets")
                : workDir.resolve("assets");
        Files.createDirectories(assetsRoot);
        if (spec.layout == NativeClientCatalog.AssetLayout.HASH_STORE) {
            Path indexes = assetsRoot.resolve("indexes");
            Files.createDirectories(indexes);
            Files.copy(source.indexFile, indexes.resolve(source.indexFile.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            linkOrCopyObjects(source.indexFile, source.objectsDir, assetsRoot.resolve("objects"));
        } else {
            restoreLogical(source.indexFile, source.objectsDir, assetsRoot);
        }
        extractClientAssets(source.jar, assetsRoot);
    }

    /**
     * Pomme 读取 {@code assets/indexes/{version}.json}、{@code assets/objects}，
     * 以及 {@code versions/{version}/extracted/assets}。
     */
    static void stagePomme(Path runtime, String version, Located source) throws IOException {
        Path assets = runtime.resolve("assets");
        Path indexes = assets.resolve("indexes");
        Files.createDirectories(indexes);
        Files.copy(source.indexFile, indexes.resolve(version + ".json"), StandardCopyOption.REPLACE_EXISTING);
        linkOrCopyObjects(source.indexFile, source.objectsDir, assets.resolve("objects"));
        Path extracted = runtime.resolve("versions").resolve(version).resolve("extracted").resolve("assets");
        Files.createDirectories(extracted);
        extractClientAssets(source.jar, extracted);
        Files.writeString(runtime.resolve(".assets-ready"), version);
    }

    static String indexId(Path versionJson, String versionId) {
        if (Files.isRegularFile(versionJson)) {
            try {
                JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
                if (root.has("assetIndex") && root.get("assetIndex").isJsonObject()) {
                    JsonObject index = root.getAsJsonObject("assetIndex");
                    if (index.has("id") && !index.get("id").getAsString().isBlank()) {
                        return index.get("id").getAsString();
                    }
                }
            } catch (Exception ignored) {
                // 坏掉的版本 JSON 退回按版本号猜索引名
            }
        }
        if (versionId.startsWith("1.8")) return "1.8";
        if (versionId.startsWith("1.12")) return "1.12";
        return versionId;
    }

    private static void linkOrCopyObjects(Path indexFile, Path objectsDir, Path link) throws IOException {
        try {
            if (Files.isSymbolicLink(link) || Files.exists(link)) {
                deleteTree(link);
            }
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, objectsDir.toAbsolutePath().normalize());
        } catch (IOException | UnsupportedOperationException e) {
            if (Files.isSymbolicLink(link)) Files.deleteIfExists(link);
            copyReferenced(indexFile, objectsDir, link);
        }
    }

    private static void copyReferenced(Path indexFile, Path objectsDir, Path destObjects) throws IOException {
        JsonObject objects = objects(indexFile);
        for (var entry : objects.entrySet()) {
            String hash = hashOf(entry.getValue());
            if (hash == null) continue;
            Path from = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.isRegularFile(from)) continue;
            Path to = destObjects.resolve(hash.substring(0, 2)).resolve(hash);
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void restoreLogical(Path indexFile, Path objectsDir, Path assetsRoot) throws IOException {
        JsonObject objects = objects(indexFile);
        Path root = assetsRoot.toAbsolutePath().normalize();
        for (var entry : objects.entrySet()) {
            String logical = entry.getKey().replace('\\', '/');
            if (!safeRelative(logical)) continue;
            String hash = hashOf(entry.getValue());
            if (hash == null) continue;
            Path from = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.isRegularFile(from)) continue;
            Path dest = root.resolve(logical).normalize();
            if (!dest.startsWith(root)) continue;
            Files.createDirectories(dest.getParent());
            Files.copy(from, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void extractClientAssets(Path jar, Path assetsRoot) throws IOException {
        Path root = assetsRoot.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith("assets/minecraft/")) continue;
                String relative = name.substring("assets/".length());
                if (!safeRelative(relative)) continue;
                Path dest = root.resolve(relative).normalize();
                if (!dest.startsWith(root)) {
                    throw new IOException("bad jar entry");
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static JsonObject objects(Path indexFile) throws IOException {
        JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        if (!root.has("objects") || !root.get("objects").isJsonObject()) {
            throw new IOException("asset index has no objects");
        }
        return root.getAsJsonObject("objects");
    }

    private static String hashOf(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        if (!object.has("hash")) return null;
        String hash = object.get("hash").getAsString().toLowerCase(java.util.Locale.ROOT);
        if (hash.length() != 40) return null;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return null;
        }
        return hash;
    }

    static boolean safeRelative(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) return false;
        if (name.startsWith("/") || name.startsWith("\\")) return false;
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    static Path officialMinecraft() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null && !appdata.isBlank()) return Path.of(appdata, ".minecraft");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft");
        return Path.of(home, ".minecraft");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root) && !Files.isSymbolicLink(root)) return;
        if (Files.isSymbolicLink(root) || Files.isRegularFile(root)) {
            Files.deleteIfExists(root);
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 单个残留文件留到下次安装再覆盖
                }
            });
        }
    }
}
