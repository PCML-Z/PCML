package com.pmcl.core.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.i18n.I18n;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Foojay Disco API 的发行版 JDK 目录。
 * 只查询公开接口 {@code https://api.foojay.io/disco/v3.0/}，不内置厂商商标。
 */
public final class FoojayDisco {

    public static final String API = "https://api.foojay.io/disco/v3.0/";
    private static final Pattern PACKAGE_ID = Pattern.compile("[a-fA-F0-9]{16,64}");

    private static final List<Vendor> VENDORS = List.of(
            new Vendor("corretto", "Amazon", "Corretto"),
            new Vendor("zulu", "Azul", "Zulu"),
            new Vendor("liberica", "BellSoft", "Liberica JDK"),
            new Vendor("temurin", "Eclipse", "Temurin"),
            new Vendor("graalvm_community", "GraalVM", "Community Edition"),
            new Vendor("semeru", "IBM", "Semeru"),
            new Vendor("jetbrains", "JetBrains", "Runtime"),
            new Vendor("microsoft", "Microsoft", "OpenJDK"),
            new Vendor("oracle_open_jdk", "Oracle", "OpenJDK / GraalVM"),
            new Vendor("sap_machine", "SAP", "SapMachine")
    );

    private FoojayDisco() {}

    public static List<Vendor> vendors() {
        return VENDORS;
    }

    public static boolean isVendor(String id) {
        if (id == null || id.isBlank()) return false;
        for (Vendor vendor : VENDORS) {
            if (vendor.id.equals(id)) return true;
        }
        return false;
    }

    /** 当前系统上该发行版已有的 GA JDK，主版本从高到低，每个主版本只留一条。 */
    public static List<Build> listBuilds(DownloadManager downloads, String distro) throws IOException {
        if (downloads == null || !isVendor(distro)) {
            throw new IOException(I18n.t("settings.java_bad_package"));
        }
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        String libc = detectLibc(os);
        String archive = defaultArchive(os);
        String url;
        try {
            url = packagesUrl(distro, os, arch, libc, archive);
        } catch (IllegalArgumentException e) {
            throw new IOException(I18n.t("settings.java_platform_unsupported"), e);
        }
        List<Build> builds = fetchPackages(downloads, url);
        if (builds.isEmpty() && !"zip".equals(archive)) {
            builds = fetchPackages(downloads, packagesUrl(distro, os, arch, libc, "zip"));
        }
        return builds;
    }

    public static Resolved resolve(DownloadManager downloads, String packageId) throws IOException {
        if (downloads == null) throw new IOException(I18n.t("settings.java_bad_package"));
        String json = downloads.downloadStringSsrfChecked(packageInfoUrl(packageId));
        return parsePackageInfo(json);
    }

    public static String packagesUrl(String distro, String osName, String osArch, String libc, String archiveType) {
        if (!isVendor(distro)) throw new IllegalArgumentException("unknown distro");
        String os = foojayOs(osName);
        String arch = foojayArch(osArch);
        String archive = (archiveType == null || archiveType.isBlank()) ? defaultArchive(osName) : archiveType;
        if (!"tar.gz".equals(archive) && !"zip".equals(archive)) {
            throw new IllegalArgumentException("unsupported archive");
        }
        StringBuilder sb = new StringBuilder(API).append("packages");
        append(sb, "distro", distro, true);
        append(sb, "architecture", arch, false);
        append(sb, "operating_system", os, false);
        append(sb, "archive_type", archive, false);
        append(sb, "package_type", "jdk", false);
        append(sb, "release_status", "ga", false);
        append(sb, "latest", "available", false);
        append(sb, "javafx_bundled", "false", false);
        append(sb, "directly_downloadable", "true", false);
        if ("linux".equals(os)) {
            append(sb, "libc_type", "musl".equals(libc) ? "musl" : "glibc", false);
        }
        return sb.toString();
    }

    public static String packageInfoUrl(String id) {
        if (id == null || !PACKAGE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bad package id");
        }
        return API + "ids/" + id.toLowerCase(Locale.ROOT);
    }

    public static String detectLibc(String osName) {
        if (osName == null || !osName.toLowerCase(Locale.ROOT).contains("linux")) return "";
        if (Files.exists(Path.of("/lib/ld-musl-x86_64.so.1"))
                || Files.exists(Path.of("/lib/ld-musl-aarch64.so.1"))
                || Files.exists(Path.of("/lib/ld-musl-riscv64.so.1"))) {
            return "musl";
        }
        return "glibc";
    }

    public static List<Build> parsePackages(String json) throws IOException {
        JsonObject root = parseObject(json);
        if (!root.has("result") || !root.get("result").isJsonArray()) {
            throw new IOException(I18n.t("settings.java_bad_package"));
        }
        Map<Integer, Build> byMajor = new LinkedHashMap<>();
        for (JsonElement el : root.getAsJsonArray("result")) {
            if (!el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();
            if (!"jdk".equalsIgnoreCase(text(item, "package_type"))) continue;
            if (!"ga".equalsIgnoreCase(text(item, "release_status"))) continue;
            if (bool(item, "javafx_bundled")) continue;
            if (item.has("directly_downloadable")
                    && !item.get("directly_downloadable").isJsonNull()
                    && !bool(item, "directly_downloadable")) {
                continue;
            }
            int major = integer(item, "major_version");
            if (major < 8 || major > 40) continue;
            String id = text(item, "id");
            if (!PACKAGE_ID.matcher(id).matches()) {
                id = idFromInfoUri(item);
            }
            if (!PACKAGE_ID.matcher(id).matches()) continue;
            String filename = text(item, "filename");
            if (filename.isBlank()) filename = "jdk-" + major + ".tar.gz";
            String version = text(item, "java_version");
            if (version.isBlank()) version = Integer.toString(major);
            Build build = new Build(major, version, filename, longValue(item, "size"), id.toLowerCase(Locale.ROOT));
            Build prev = byMajor.get(major);
            if (prev == null || (bool(item, "latest_build_available") && !prev.latest)) {
                build.latest = bool(item, "latest_build_available");
                byMajor.put(major, build);
            }
        }
        List<Build> builds = new ArrayList<>(byMajor.values());
        builds.sort(Comparator.comparingInt((Build b) -> b.major).reversed());
        return builds;
    }

    public static Resolved parsePackageInfo(String json) throws IOException {
        JsonObject root = parseObject(json);
        JsonObject item = null;
        if (root.has("result") && root.get("result").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("result");
            if (!arr.isEmpty() && arr.get(0).isJsonObject()) item = arr.get(0).getAsJsonObject();
        } else if (root.has("result") && root.get("result").isJsonObject()) {
            item = root.getAsJsonObject("result");
        } else if (root.has("direct_download_uri")) {
            item = root;
        }
        if (item == null) throw new IOException(I18n.t("settings.java_bad_package"));
        String checksumType = text(item, "checksum_type");
        String checksum = text(item, "checksum").trim();
        if (!"sha256".equalsIgnoreCase(checksumType) || !checksum.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException(I18n.t("settings.java_bad_package"));
        }
        String url = text(item, "direct_download_uri");
        if (rejectDownloadUrl(url) != null) throw new IOException(I18n.t("settings.java_bad_package"));
        String filename = text(item, "filename");
        if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || filename.contains("..") || filename.isBlank()) {
            throw new IOException(I18n.t("settings.java_archive_unsupported"));
        }
        return new Resolved(url, checksum.toLowerCase(Locale.ROOT), filename);
    }

    /** 语法层拒绝非 https、带账号或字面量内网地址。下载时仍会做 DNS 级 SSRF 校验。 */
    public static String rejectDownloadUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 4096) return "url";
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (MalformedURLException e) {
            return "url";
        }
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) return "scheme";
        if (parsed.getUserInfo() != null && !parsed.getUserInfo().isEmpty()) return "userinfo";
        String host = parsed.getHost();
        if (host == null || host.isBlank()) return "host";
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")) return "local";
        if (isBlockedLiteral(h)) return "literal";
        return null;
    }

    private static List<Build> fetchPackages(DownloadManager downloads, String url) throws IOException {
        return parsePackages(downloads.downloadStringSsrfChecked(url));
    }

    private static JsonObject parseObject(String json) throws IOException {
        if (json == null || json.isBlank()) throw new IOException(I18n.t("settings.java_bad_package"));
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) throw new IOException(I18n.t("settings.java_bad_package"));
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(I18n.t("settings.java_bad_package"), e);
        }
    }

    private static String idFromInfoUri(JsonObject item) {
        if (!item.has("links") || !item.get("links").isJsonObject()) return "";
        String uri = text(item.getAsJsonObject("links"), "pkg_info_uri");
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : "";
    }

    private static void append(StringBuilder sb, String key, String value, boolean first) {
        sb.append(first ? '?' : '&').append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String foojayOs(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        throw new IllegalArgumentException("unsupported os");
    }

    private static String foojayArch(String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x64";
        if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) return "x86";
        if (arch.contains("riscv64")) return "riscv64";
        if (arch.contains("loongarch64") || arch.contains("la64")) return "loongarch64";
        if (arch.contains("mips64")) return "mips64el";
        if (arch.contains("ppc64le")) return "ppc64le";
        if (arch.contains("s390x")) return "s390x";
        throw new IllegalArgumentException("unsupported arch");
    }

    private static String defaultArchive(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("win") ? "zip" : "tar.gz";
    }

    private static boolean isBlockedLiteral(String host) {
        String h = host;
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) return true;
        if (h.indexOf(':') >= 0) return false;
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return false;
            for (int c = 0; c < parts[i].length(); c++) {
                if (!Character.isDigit(parts[i].charAt(c))) return false;
            }
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) return false;
        }
        if (n[0] == 0 || n[0] == 10 || n[0] == 127) return true;
        if (n[0] == 169 && n[1] == 254) return true;
        if (n[0] == 192 && n[1] == 168) return true;
        if (n[0] == 172 && n[1] >= 16 && n[1] <= 31) return true;
        return n[0] == 100 && n[1] >= 64 && n[1] <= 127;
    }

    private static String text(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive()) return "";
        return el.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive()) return false;
        if (el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
        return "true".equalsIgnoreCase(el.getAsString());
    }

    private static int integer(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return -1;
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long longValue(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0L;
        try {
            return o.get(key).getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    public static final class Vendor {
        private final String id;
        private final String brand;
        private final String name;

        private Vendor(String id, String brand, String name) {
            this.id = id;
            this.brand = brand;
            this.name = name;
        }

        public String getId() { return id; }
        public String getBrand() { return brand; }
        public String getName() { return name; }
    }

    public static final class Build {
        private final int major;
        private final String javaVersion;
        private final String filename;
        private final long size;
        private final String id;
        private boolean latest;

        private Build(int major, String javaVersion, String filename, long size, String id) {
            this.major = major;
            this.javaVersion = javaVersion;
            this.filename = filename;
            this.size = size;
            this.id = id;
        }

        public int getMajor() { return major; }
        public String getJavaVersion() { return javaVersion; }
        public String getFilename() { return filename; }
        public long getSize() { return size; }
        public String getId() { return id; }
    }

    public static final class Resolved {
        private final String url;
        private final String sha256;
        private final String filename;

        private Resolved(String url, String sha256, String filename) {
            this.url = url;
            this.sha256 = sha256;
            this.filename = filename;
        }

        public String getUrl() { return url; }
        public String getSha256() { return sha256; }
        public String getFilename() { return filename; }
    }
}
