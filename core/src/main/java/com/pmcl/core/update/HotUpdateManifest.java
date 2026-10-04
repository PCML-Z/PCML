package com.pmcl.core.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 热更新清单。发布到 GitHub Release 的资产名是 {@code pmcl-hotupdate.json}。
 * 签名覆盖版本和每个文件的路径、SHA-256、大小、URL、系统与架构，不覆盖说明文字。
 */
public final class HotUpdateManifest {
    static final String ASSET_NAME = "pmcl-hotupdate.json";

    /** 某个系统的清单优先于通用 {@link #ASSET_NAME}。 */
    public static String platformAssetName(Host host) {
        return "pmcl-hotupdate-" + host.os() + "-" + host.arch() + ".json";
    }
    private static final int MAX_FILES = 5000;
    private static final int MAX_NOTES = 8000;

    private final String version;
    private final String notes;
    private final String signature;
    private final List<FileEntry> files;

    private HotUpdateManifest(String version, String notes, String signature, List<FileEntry> files) {
        this.version = version;
        this.notes = notes;
        this.signature = signature;
        this.files = List.copyOf(files);
    }

    public String version() { return version; }
    public String notes() { return notes; }
    public String signature() { return signature; }
    public List<FileEntry> files() { return files; }

    public List<FileEntry> filesFor(Host host) {
        List<FileEntry> matched = new ArrayList<>();
        for (FileEntry file : files) {
            if (file.applies(host)) matched.add(file);
        }
        return matched;
    }

    public static HotUpdateManifest parse(String json) throws IOException {
        if (json == null || json.isBlank()) throw new IOException("热更新清单为空");
        if (json.length() > 1_048_576) throw new IOException("热更新清单过大");
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("热更新清单不是 JSON 对象", e);
        }
        String version = text(o, "version");
        if (!version.matches("[A-Za-z0-9._+-]+")) {
            throw new IOException("热更新版本号非法");
        }
        String notes = text(o, "notes");
        if (notes.length() > MAX_NOTES) notes = notes.substring(0, MAX_NOTES);
        String signature = text(o, "signature");
        if (!o.has("files") || !o.get("files").isJsonArray()) {
            throw new IOException("热更新清单缺少 files");
        }
        JsonArray array = o.getAsJsonArray("files");
        if (array.isEmpty() || array.size() > MAX_FILES) {
            throw new IOException("热更新文件数量无效");
        }
        List<FileEntry> files = new ArrayList<>();
        for (var el : array) {
            if (!el.isJsonObject()) throw new IOException("热更新文件项无效");
            files.add(FileEntry.parse(el.getAsJsonObject()));
        }
        files.sort(Comparator.comparing(FileEntry::path));
        for (int i = 1; i < files.size(); i++) {
            if (files.get(i).path().equals(files.get(i - 1).path())) {
                throw new IOException("热更新清单路径重复: " + files.get(i).path());
            }
        }
        HotUpdateManifest manifest = new HotUpdateManifest(version, notes, signature, files);
        UpdateSignatureVerifier.verifyHotUpdate(manifest.canonical(), signature);
        return manifest;
    }

    /** 已完成验签后的清单。测试和内存构造使用，发布通道必须走 {@link #parse}。 */
    static HotUpdateManifest verified(String version, String notes, String signature, List<FileEntry> files) {
        List<FileEntry> copy = new ArrayList<>(files);
        copy.sort(Comparator.comparing(FileEntry::path));
        return new HotUpdateManifest(version, notes == null ? "" : notes, signature, copy);
    }

    public String canonical() {
        StringBuilder sb = new StringBuilder("PMCL-HOTUPDATE-V1\n");
        sb.append(version).append('\n');
        List<FileEntry> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(FileEntry::path));
        for (FileEntry file : sorted) {
            sb.append(file.path()).append('\t')
                    .append(file.sha256()).append('\t')
                    .append(file.size()).append('\t')
                    .append(file.url()).append('\t')
                    .append(file.os()).append('\t')
                    .append(file.arch()).append('\n');
        }
        return sb.toString();
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", version);
        o.addProperty("notes", notes);
        o.addProperty("signature", signature);
        JsonArray array = new JsonArray();
        for (FileEntry file : files) array.add(file.toJson());
        o.add("files", array);
        return o.toString();
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    public static final class FileEntry {
        private final String path;
        private final String sha256;
        private final long size;
        private final String url;
        private final String os;
        private final String arch;

        public FileEntry(String path, String sha256, long size, String url, String os, String arch) {
            this.path = path;
            this.sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
            this.size = size;
            this.url = url == null ? "" : url;
            this.os = os == null ? "" : os;
            this.arch = arch == null ? "" : arch;
        }

        public String path() { return path; }
        public String sha256() { return sha256; }
        public long size() { return size; }
        public String url() { return url; }
        public String os() { return os; }
        public String arch() { return arch; }

        public boolean applies(Host host) {
            if (!os.isEmpty() && !os.equals(host.os())) return false;
            return arch.isEmpty() || arch.equals(host.arch());
        }

        static FileEntry parse(JsonObject o) throws IOException {
            String path = text(o, "path").replace('\\', '/');
            validatePath(path);
            String sha = text(o, "sha256").toLowerCase(Locale.ROOT);
            if (!sha.matches("[0-9a-f]{64}")) throw new IOException("热更新 SHA-256 无效: " + path);
            if (!o.has("size") || o.get("size").isJsonNull()) throw new IOException("热更新缺少大小: " + path);
            long size = o.get("size").getAsLong();
            if (size < 0) throw new IOException("热更新大小无效: " + path);
            String url = text(o, "url");
            requireHttps(url);
            String os = text(o, "os").toLowerCase(Locale.ROOT);
            String arch = text(o, "arch").toLowerCase(Locale.ROOT);
            if (!os.isEmpty() && !os.equals("macos") && !os.equals("windows") && !os.equals("linux")) {
                throw new IOException("热更新系统标记无效: " + path);
            }
            if (arch.equals("arm64") || arch.equals("aarch64")) arch = "aarch64";
            if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) arch = "x64";
            if (!arch.isEmpty() && !arch.equals("aarch64") && !arch.equals("x64")) {
                throw new IOException("热更新架构标记无效: " + path);
            }
            return new FileEntry(path, sha, size, url, os, arch);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("path", path);
            o.addProperty("sha256", sha256);
            o.addProperty("size", size);
            o.addProperty("url", url);
            if (!os.isEmpty()) o.addProperty("os", os);
            if (!arch.isEmpty()) o.addProperty("arch", arch);
            return o;
        }

        static void validatePath(String path) throws IOException {
            if (path == null || !path.startsWith("app/") || path.length() > 512
                    || path.contains("\\") || path.contains("//") || path.endsWith("/")) {
                throw new IOException("热更新路径无效: " + path);
            }
            for (String seg : path.split("/")) {
                if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                    throw new IOException("热更新路径无效: " + path);
                }
                if (!seg.matches("[A-Za-z0-9._+-]+")) {
                    throw new IOException("热更新路径含非法字符: " + path);
                }
            }
        }

        private static void requireHttps(String url) throws IOException {
            if (url == null || url.isBlank() || url.length() > 2000) {
                throw new IOException("热更新 URL 无效");
            }
            try {
                String scheme = URI.create(url).getScheme();
                if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
                    throw new IOException("热更新 URL 必须使用 HTTPS");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("热更新 URL 非法", e);
            }
        }

        private static String text(JsonObject o, String key) {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
        }
    }

    public static final class Host {
        private final String os;
        private final String arch;

        public Host(String os, String arch) {
            this.os = os == null ? "" : os;
            this.arch = arch == null ? "" : arch;
        }

        public String os() { return os; }
        public String arch() { return arch; }

        public static Host current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String os = name.contains("mac") ? "macos" : name.contains("win") ? "windows" : "linux";
            String norm = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x64";
            return new Host(os, norm);
        }
    }
}
