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
        Files.writeString(out.resolve(jsonName), toJson(version, signature, rows), StandardCharsets.UTF_8);
        System.out.println("Wrote " + jsonName + " files=" + rows.size());
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

    private static String toJson(String version, String signature, List<FileRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":\"").append(version)
                .append("\",\"notes\":\"\",\"signature\":\"").append(signature)
                .append("\",\"files\":[");
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
