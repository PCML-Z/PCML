import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 从 Compose 打包出的 app 目录生成已签名的热更新清单。
 * 规范载荷必须和 HotUpdateManifest.canonical 一致：
 * PMCL-HOTUPDATE-V1、版本、按路径排序的 path、sha256、size、url、os、arch。
 *
 * Usage:
 *   java tools/BuildHotUpdateManifest.java pack <binaries-dir> <version> <os> <arch> <tag> <repo> <out-dir>
 * Env: PMCL_UPDATE_ED25519_PRIVATE_KEY = Base64 PKCS#8 Ed25519 private key.
 *      PMCL_UPDATE_MANDATORY = "true" 时清单置 mandatory，客户端更新框不可跳过。
 *      PMCL_UPDATE_NOTES_FILE = 更新说明文本文件，写入清单 notes（对话框展示，不进签名载荷）。
 */
public final class BuildHotUpdateManifest {
    public static void main(String[] args) throws Exception {
        if (args.length != 8 || !"pack".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Usage: BuildHotUpdateManifest pack <binaries-dir> <version> <os> <arch> <tag> <repo> <out-dir>");
        }
        String version = args[2];
        if (!version.matches("[A-Za-z0-9._+-]+")) {
            throw new IllegalArgumentException("版本号非法: " + version);
        }
        String os = normOs(args[3]);
        String arch = normArch(args[4]);
        String tag = args[5];
        String repo = args[6];
        if (!repo.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+") || tag.isBlank() || tag.indexOf('/') >= 0) {
            throw new IllegalArgumentException("仓库或 tag 非法");
        }
        Path binaries = Path.of(args[1]);
        Path out = Path.of(args[7]);
        Files.createDirectories(out);
        Path appDir = findAppDirectory(binaries);
        System.out.println("Hot update app dir: " + appDir);
        List<FileRow> rows = new ArrayList<>();
        try (var walk = Files.walk(appDir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalStateException("拒绝符号链接: " + file);
                }
                String name = file.getFileName().toString();
                if (name.equals(".DS_Store") || name.startsWith(".")) continue;
                String relative = "app/" + appDir.relativize(file).toString().replace('\\', '/');
                validatePath(relative);
                String sha = sha256(file);
                long size = Files.size(file);
                String asset = "hot-" + os + "-" + arch + "-" + relative.replace('/', '_');
                String url = "https://github.com/" + repo + "/releases/download/" + tag + "/" + asset;
                Files.copy(file, out.resolve(asset), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                rows.add(new FileRow(relative, sha, size, url, os, arch));
            }
        }
        if (rows.isEmpty()) throw new IllegalStateException("app 目录里没有可发布的文件");
        rows.sort(Comparator.comparing(row -> row.path));
        String signature = sign(canonical(version, rows));
        String jsonName = "pmcl-hotupdate-" + os + "-" + arch + ".json";
        boolean mandatory = "true".equalsIgnoreCase(
                System.getenv().getOrDefault("PMCL_UPDATE_MANDATORY", "").trim());
        String notes = readNotes(System.getenv("PMCL_UPDATE_NOTES_FILE"));
        Files.writeString(out.resolve(jsonName),
                toJson(version, signature, rows, notes, mandatory), StandardCharsets.UTF_8);
        System.out.println("Wrote " + jsonName + " files=" + rows.size()
                + " mandatory=" + mandatory + " notesChars=" + notes.length());
    }

    /** 更新说明取本地文件（GitHub Release body 同源），读不到就留空——对话框不显示即可。 */
    private static String readNotes(String path) {
        if (path == null || path.isBlank()) return "";
        try {
            Path p = Path.of(path);
            if (!Files.isRegularFile(p)) return "";
            String text = Files.readString(p, StandardCharsets.UTF_8).trim();
            // 客户端解析时会截断到 8000，这里先裁一次，避免清单无谓膨胀
            return text.length() <= 8000 ? text : text.substring(0, 8000);
        } catch (Exception e) {
            System.out.println("Warning: could not read notes file " + path + ": " + e.getMessage());
            return "";
        }
    }

    static Path findAppDirectory(Path binaries) throws Exception {
        if (!Files.isDirectory(binaries)) {
            throw new IllegalStateException("找不到打包目录: " + binaries);
        }
        Path best = null;
        int bestCount = 0;
        try (var walk = Files.walk(binaries, 10)) {
            for (Path dir : walk.filter(Files::isDirectory).toList()) {
                if (dir.getFileName() == null || !"app".equals(dir.getFileName().toString())) continue;
                String full = dir.toString().replace('\\', '/');
                if (full.contains("/runtime/")) continue;
                if (containsRuntimeDir(dir)) continue;
                int jars = 0;
                try (var inner = Files.walk(dir, 4)) {
                    jars = (int) inner.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .count();
                }
                if (jars > bestCount) {
                    bestCount = jars;
                    best = dir;
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("找不到包含 jar 的 app 目录: " + binaries);
        }
        return best;
    }

    /** 安装包根目录也叫 app，里面有 jlink 的 runtime。热更新只收真正的 app 目录。 */
    private static boolean containsRuntimeDir(Path dir) throws Exception {
        try (var walk = Files.walk(dir, 6)) {
            return walk.anyMatch(path -> Files.isDirectory(path)
                    && "runtime".equals(String.valueOf(path.getFileName())));
        }
    }

    static String canonical(String version, List<FileRow> rows) {
        StringBuilder sb = new StringBuilder("PMCL-HOTUPDATE-V1\n");
        sb.append(version).append('\n');
        for (FileRow row : rows) {
            sb.append(row.path).append('\t')
                    .append(row.sha256).append('\t')
                    .append(row.size).append('\t')
                    .append(row.url).append('\t')
                    .append(row.os).append('\t')
                    .append(row.arch).append('\n');
        }
        return sb.toString();
    }

    private static String toJson(String version, String signature, List<FileRow> rows,
                                String notes, boolean mandatory) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":\"").append(version)
                .append("\",\"notes\":").append(jsonString(notes))
                .append(",\"signature\":\"").append(signature).append("\"");
        // 不进 canonical() 签名载荷：旧客户端用同一规范串验签，加进去会让它们验签失败、
        // 连本次更新都装不上。清单整体仍受 signature 保护，只有发布方能置 true。
        if (mandatory) sb.append(",\"mandatory\":true");
        sb.append(",\"files\":[");
        for (int i = 0; i < rows.size(); i++) {
            FileRow row = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"path\":\"").append(row.path)
                    .append("\",\"sha256\":\"").append(row.sha256)
                    .append("\",\"size\":").append(row.size)
                    .append(",\"url\":\"").append(row.url)
                    .append("\",\"os\":\"").append(row.os)
                    .append("\",\"arch\":\"").append(row.arch)
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 发布说明是任意文本，必须转义；path/url 等已受字符集校验，无需转义。 */
    private static String jsonString(String raw) {
        if (raw == null || raw.isEmpty()) return "\"\"";
        StringBuilder sb = new StringBuilder(raw.length() + 16).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String sign(String payload) throws Exception {
        String privateKeyB64 = System.getenv("PMCL_UPDATE_ED25519_PRIVATE_KEY");
        if (privateKeyB64 == null || privateKeyB64.isBlank()) {
            throw new IllegalStateException("Missing PMCL_UPDATE_ED25519_PRIVATE_KEY");
        }
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyB64.replaceAll("\\s+", ""));
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static void validatePath(String path) {
        if (!path.startsWith("app/") || path.contains("..") || path.contains("//")) {
            throw new IllegalStateException("热更新路径无效: " + path);
        }
        for (String seg : path.split("/")) {
            if (!seg.matches("[A-Za-z0-9._+-]+")) {
                throw new IllegalStateException("热更新路径含非法字符: " + path);
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String normOs(String os) {
        String value = os.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "macos", "mac", "osx" -> "macos";
            case "windows", "win" -> "windows";
            case "linux" -> "linux";
            default -> throw new IllegalArgumentException("不认识的系统: " + os);
        };
    }

    private static String normArch(String arch) {
        String value = arch.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "arm64", "aarch64" -> "aarch64";
            case "amd64", "x86_64", "x64" -> "x64";
            default -> throw new IllegalArgumentException("不认识的架构: " + arch);
        };
    }

    private record FileRow(String path, String sha256, long size, String url, String os, String arch) {}

    private BuildHotUpdateManifest() {}
}
