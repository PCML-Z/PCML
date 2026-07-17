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

        public List<String> getMissing() { return missing; }
        public List<String> getHashMismatch() { return hashMismatch; }
        public List<String> getOk() { return ok; }

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
                if (client.has("sha1") && !client.get("sha1").isJsonNull()) {
                    Path clientJar = versionDir.resolve(versionId + ".jar");
                    verifyFile(clientJar, client.get("sha1").getAsString(), result);
                }
            }
        }

        // libraries
        if (root.has("libraries")) {
            for (var e : root.getAsJsonArray("libraries")) {
                JsonObject lib = e.getAsJsonObject();
                if (!lib.has("downloads")) continue;
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("artifact")) {
                    JsonObject art = downloads.getAsJsonObject("artifact");
                    if (art.has("path") && !art.get("path").isJsonNull()
                            && art.has("sha1") && !art.get("sha1").isJsonNull()) {
                        Path libFile = config.getLibrariesDir().resolve(art.get("path").getAsString());
                        verifyFile(libFile, art.get("sha1").getAsString(), result);
                    }
                }
                // native classifiers
                if (downloads.has("classifiers")) {
                    JsonObject cl = downloads.getAsJsonObject("classifiers");
                    for (var ce : cl.entrySet()) {
                        JsonObject a = ce.getValue().getAsJsonObject();
                        if (a.has("path") && !a.get("path").isJsonNull()
                                && a.has("sha1") && !a.get("sha1").isJsonNull()) {
                            Path libFile = config.getLibrariesDir().resolve(a.get("path").getAsString());
                            verifyFile(libFile, a.get("sha1").getAsString(), result);
                        }
                    }
                }
            }
        }

        return result;
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
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("SHA1 计算失败", e);
        }
    }
}
