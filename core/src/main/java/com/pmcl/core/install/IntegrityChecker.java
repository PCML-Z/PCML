package com.pmcl.core.install;

import com.pmcl.core.LauncherConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动前完整性校验：根据版本 JSON 校验 client.jar 与所有 libraries 的 SHA1。
 * <p>
 * 缺失或哈希不匹配的文件会被收集到 {@link Result} 中，UI 可提示用户重新下载。
 */
public final class IntegrityChecker {

    private final LauncherConfig config;

    public IntegrityChecker(LauncherConfig config) {
        this.config = config;
    }

    public static final class Result {
        private final List<String> missing = new ArrayList<>();
        private final List<String> hashMismatch = new ArrayList<>();
        private final List<String> ok = new ArrayList<>();
        /** 存在但无法哈希校验（无 sha1 / maven 仅路径） */
        private final List<String> unverifiable = new ArrayList<>();

        public List<String> getMissing() { return missing; }
        public List<String> getHashMismatch() { return hashMismatch; }
        public List<String> getOk() { return ok; }
        public List<String> getUnverifiable() { return unverifiable; }

        public boolean isOk() { return missing.isEmpty() && hashMismatch.isEmpty(); }

        public int getIssueCount() { return missing.size() + hashMismatch.size(); }
    }

    /**
     * 校验指定版本。
     */
    public Result check(String versionId) throws IOException {
        Result result = new Result();
        Path versionDir = config.getVersionsDir().resolve(versionId);
        Path versionJson = versionDir.resolve(versionId + ".json");
        if (!Files.exists(versionJson)) {
            result.getMissing().add("versions/" + versionId + "/" + versionId + ".json");
            return result;
        }

        JsonObject root = JsonParser.parseString(Files.readString(versionJson, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();

        // client.jar
        if (root.has("downloads")) {
            JsonObject dl = root.getAsJsonObject("downloads");
            if (dl.has("client")) {
                JsonObject client = dl.getAsJsonObject("client");
                Path clientJar = versionDir.resolve(versionId + ".jar");
                if (client.has("sha1") && !client.get("sha1").isJsonNull()) {
                    verifyFile(clientJar, client.get("sha1").getAsString(), result);
                } else if (Files.exists(clientJar)) {
                    result.getUnverifiable().add(clientJar + " (client.jar 无 sha1)");
                } else {
                    result.getMissing().add(clientJar.toString());
                }
            }
        }

        // libraries
        if (root.has("libraries")) {
            for (var e : root.getAsJsonArray("libraries")) {
                JsonObject lib = e.getAsJsonObject();
                if (!lib.has("downloads")) {
                    // M84: Fabric/Forge/NeoForge 第三方库格式（只有顶层 name + url，无 downloads/sha1）
                    // 按 maven 规则拼接路径，仅校验文件存在（无 SHA1 可比对）→ unverifiable
                    if (lib.has("name") && !lib.get("name").isJsonNull()) {
                        Library parsed = Library.parse(lib);
                        String path = parsed.getPath();
                        if (!path.isEmpty()) {
                            Path libFile = config.getLibrariesDir().resolve(path);
                            if (!Files.exists(libFile)) {
                                result.getMissing().add(libFile.toString());
                            } else {
                                result.getUnverifiable().add(libFile + " (无 sha1)");
                            }
                        }
                    }
                    continue;
                }
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    JsonObject art = downloads.getAsJsonObject("artifact");
                    if (art.has("path") && !art.get("path").isJsonNull()) {
                        Path libFile = config.getLibrariesDir().resolve(art.get("path").getAsString());
                        if (art.has("sha1") && !art.get("sha1").isJsonNull()) {
                            verifyFile(libFile, art.get("sha1").getAsString(), result);
                        } else if (Files.exists(libFile)) {
                            result.getUnverifiable().add(libFile + " (artifact 无 sha1)");
                        } else {
                            result.getMissing().add(libFile.toString());
                        }
                    }
                }
                // native classifiers
                if (downloads.has("classifiers")) {
                    JsonObject cl = downloads.getAsJsonObject("classifiers");
                    for (var ce : cl.entrySet()) {
                        JsonObject a = ce.getValue().getAsJsonObject();
                        if (!a.has("path") || a.get("path").isJsonNull()) continue;
                        Path libFile = config.getLibrariesDir().resolve(a.get("path").getAsString());
                        if (a.has("sha1") && !a.get("sha1").isJsonNull()) {
                            verifyFile(libFile, a.get("sha1").getAsString(), result);
                        } else if (Files.exists(libFile)) {
                            result.getUnverifiable().add(libFile + " (classifier 无 sha1)");
                        } else {
                            result.getMissing().add(libFile.toString());
                        }
                    }
                }
            }
        }

        // assets：校验 assetIndex 文件 + objects 哈希（hash 即 sha1）
        checkAssets(root, result);

        return result;
    }

    private void checkAssets(JsonObject root, Result result) {
        if (!root.has("assetIndex") || root.get("assetIndex").isJsonNull()) return;
        JsonObject ai = root.getAsJsonObject("assetIndex");
        String id = ai.has("id") && !ai.get("id").isJsonNull() ? ai.get("id").getAsString() : null;
        if (id == null || id.isBlank()) return;
        Path indexPath = config.getAssetsDir().resolve("indexes").resolve(id + ".json");
        if (ai.has("sha1") && !ai.get("sha1").isJsonNull()) {
            verifyFile(indexPath, ai.get("sha1").getAsString(), result);
        } else if (Files.exists(indexPath)) {
            result.getUnverifiable().add(indexPath + " (assetIndex 无 sha1)");
        } else {
            result.getMissing().add(indexPath.toString());
            return;
        }
        if (!Files.exists(indexPath)) return;
        try {
            JsonObject idx = JsonParser.parseString(
                    Files.readString(indexPath, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!idx.has("objects")) return;
            JsonObject objects = idx.getAsJsonObject("objects");
            Path objectsDir = config.getAssetsDir().resolve("objects");
            for (var e : objects.entrySet()) {
                JsonObject obj = e.getValue().getAsJsonObject();
                if (!obj.has("hash") || obj.get("hash").isJsonNull()) {
                    result.getUnverifiable().add("assets object " + e.getKey() + " (无 hash)");
                    continue;
                }
                String hash = obj.get("hash").getAsString();
                if (hash.length() < 2) {
                    result.getUnverifiable().add("assets object " + e.getKey() + " (非法 hash)");
                    continue;
                }
                Path file = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                verifyFile(file, hash, result);
            }
        } catch (Exception ex) {
            result.getHashMismatch().add(indexPath + " (解析失败: " + ex.getMessage() + ")");
        }
    }

    private void verifyFile(Path file, String expectedSha1, Result result) {
        if (!Files.exists(file)) {
            result.getMissing().add(file.toString());
            return;
        }
        try {
            String actual = sha1(file);
            if (!actual.equalsIgnoreCase(expectedSha1)) {
                result.getHashMismatch().add(file.toString() + " (期望=" + expectedSha1 + " 实际=" + actual + ")");
            } else {
                result.getOk().add(file.toString());
            }
        } catch (IOException e) {
            result.getHashMismatch().add(file.toString() + " (计算哈希失败: " + e.getMessage() + ")");
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (var is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            // H13: b & 0xff 防止 byte 符号扩展为 int 时产生 ffffffff 而非 ff
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("SHA1 计算失败", e);
        }
    }
}
