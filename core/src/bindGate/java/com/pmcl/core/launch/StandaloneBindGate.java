package com.pmcl.core.launch;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 独立游戏 App 与本机 PMCL 的强绑定（Java 8，可在游戏自带 JRE 上运行）。
 */
public final class StandaloneBindGate {

    public static final String HOST_FILE_NAME = "standalone-host.key";
    public static final String TICKET_FILE_NAME = "bind.ticket";
    public static final String GATE_JAR_NAME = "bind-gate.jar";

    private static final String HMAC = "HmacSHA256";
    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private StandaloneBindGate() {}

    public static void main(String[] args) {
        if (args == null || args.length < 1) {
            fail("缺少绑定参数");
        }
        try {
            verify(Paths.get(args[0]));
        } catch (Exception e) {
            fail(e.getMessage() != null ? e.getMessage() : "绑定校验失败");
        }
    }

    public static Path hostFile(Path workDir) {
        return workDir.resolve(HOST_FILE_NAME);
    }

    public static HostSecret ensureHostSecret(Path workDir) throws IOException {
        Files.createDirectories(workDir);
        Path file = hostFile(workDir);
        if (Files.isRegularFile(file)) {
            Map<String, String> kv = readKv(file);
            String id = kv.get("installId");
            String secret = kv.get("secret");
            if (notEmpty(id) && notEmpty(secret)) {
                return new HostSecret(id, Base64.getDecoder().decode(secret));
            }
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String id = UUID.randomUUID().toString();
        Map<String, String> kv = new LinkedHashMap<String, String>();
        kv.put("installId", id);
        kv.put("secret", Base64.getEncoder().encodeToString(secret));
        writeKv(file, kv);
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
        }
        return new HostSecret(id, secret);
    }

    public static Path detectLauncherPath() {
        String override = System.getProperty("pmcl.bind.launcher");
        if (notEmpty(override)) {
            Path p = Paths.get(override).toAbsolutePath().normalize();
            if (Files.exists(p)) return p;
        }
        Path found = findKnownPmclApp();
        if (found != null) return found;
        found = findPmclApp(Paths.get(System.getProperty("java.home", ".")));
        if (found != null) return found;
        found = findPmclApp(Paths.get(System.getProperty("user.dir", ".")));
        if (found != null) return found;
        found = findFromCodeSource();
        if (found != null) return found;
        found = findFromJavaCommand();
        if (found != null) return found;
        found = findFromClassPath();
        if (found != null) return found;
        found = scanCommonFolders();
        if (found != null) return found;
        return findComposeDevApp();
    }

    public static boolean isPmclPresent(String recordedLauncher) {
        if (notEmpty(recordedLauncher) && Files.exists(Paths.get(recordedLauncher))) {
            return true;
        }
        return findKnownPmclApp() != null;
    }

    public static String deviceId() {
        String override = System.getProperty("pmcl.bind.device");
        if (notEmpty(override)) return sha256Hex(override);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String raw = "";
        try {
            if (os.contains("mac") || os.contains("darwin")) {
                raw = runCapture(new String[] {"ioreg", "-rd1", "-c", "IOPlatformExpertDevice"});
                Matcher m = UUID_RE.matcher(raw == null ? "" : raw);
                if (m.find()) raw = m.group();
            } else if (os.contains("win")) {
                raw = runCapture(new String[] {"wmic", "csproduct", "get", "uuid"});
                Matcher m = UUID_RE.matcher(raw == null ? "" : raw);
                if (m.find()) raw = m.group();
            } else {
                Path mid = Paths.get("/etc/machine-id");
                if (Files.isRegularFile(mid)) {
                    raw = new String(Files.readAllBytes(mid), StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Exception ignored) {
        }
        if (!notEmpty(raw)) {
            raw = System.getProperty("user.name", "") + "|"
                    + System.getProperty("os.arch", "") + "|"
                    + System.getProperty("user.home", "");
        }
        return sha256Hex(raw.trim());
    }

    public static void writeTicket(Path ticketFile, Path hostFile, Path launcher,
                                   HostSecret host, String device) throws IOException {
        if (launcher == null || !Files.exists(launcher)) {
            throw new IOException("找不到本机 PMCL 启动器，无法做强绑定");
        }
        String launcherAbs = launcher.toAbsolutePath().normalize().toString();
        String hostAbs = hostFile.toAbsolutePath().normalize().toString();
        String mac = hmacHex(host.secret, host.installId + "\n" + hostAbs + "\n" + launcherAbs + "\n" + device);
        Map<String, String> kv = new LinkedHashMap<String, String>();
        kv.put("v", "1");
        kv.put("installId", host.installId);
        kv.put("host", hostAbs);
        kv.put("launcher", launcherAbs);
        kv.put("device", device);
        kv.put("mac", mac);
        writeKv(ticketFile, kv);
    }

    public static void verify(Path contentsDir) throws IOException {
        Path ticket = contentsDir.resolve("Resources").resolve(TICKET_FILE_NAME);
        if (!Files.isRegularFile(ticket)) {
            throw new IOException("此游戏已与本机 PMCL 绑定，缺少绑定票据，无法启动");
        }
        Map<String, String> t = readKv(ticket);
        String hostPath = t.get("host");
        String launcherPath = t.get("launcher");
        String device = t.get("device");
        String installId = t.get("installId");
        String mac = t.get("mac");
        if (hostPath == null || launcherPath == null || device == null || installId == null || mac == null) {
            throw new IOException("绑定票据损坏，无法启动");
        }
        Path hostFile = Paths.get(hostPath);
        if (!Files.isRegularFile(hostFile)) {
            throw new IOException("未找到本机 PMCL（工作目录密钥已丢失）。删除启动器后无法启动此游戏");
        }
        Map<String, String> host = readKv(hostFile);
        if (!installId.equals(host.get("installId"))) {
            throw new IOException("本机 PMCL 安装身份不匹配，无法启动此游戏");
        }
        String secretB64 = host.get("secret");
        if (!notEmpty(secretB64)) {
            throw new IOException("本机 PMCL 绑定密钥损坏，无法启动");
        }
        if (!isPmclPresent(launcherPath)) {
            throw new IOException("未找到本机 PMCL 启动器。删除或移走启动器后无法启动此游戏");
        }
        String nowDevice = deviceId();
        if (!device.equals(nowDevice)) {
            throw new IOException("此游戏已绑定到导出它的那台电脑上的 PMCL，无法在其他设备启动");
        }
        byte[] secret = Base64.getDecoder().decode(secretB64);
        String expect = hmacHex(secret, installId + "\n" + hostFile.toAbsolutePath().normalize()
                + "\n" + Paths.get(launcherPath).toAbsolutePath().normalize() + "\n" + device);
        if (!constantEquals(expect, mac)) {
            throw new IOException("绑定校验失败：此游戏只能由本机 PMCL 授权启动");
        }
    }

    public static void writeGateJar(Path dest) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        addClassBytes(classes, "StandaloneBindGate.class");
        addClassBytes(classes, "StandaloneBindGate$HostSecret.class");
        try {
            java.net.URL self = StandaloneBindGate.class.getResource("StandaloneBindGate.class");
            if (self != null && "file".equals(self.getProtocol())) {
                Path dir = Paths.get(self.toURI()).getParent();
                DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "StandaloneBindGate*.class");
                try {
                    for (Path p : stream) {
                        addClassBytes(classes, p.getFileName().toString());
                    }
                } finally {
                    stream.close();
                }
            }
        } catch (Exception ignored) {
        }
        if (classes.isEmpty()) throw new IOException("找不到绑定校验类");
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());
        ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dest));
        try {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zos.putNextEntry(new ZipEntry("com/pmcl/core/launch/" + e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\nMain-Class: com.pmcl.core.launch.StandaloneBindGate\n"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } finally {
            zos.close();
        }
    }

    private static void addClassBytes(Map<String, byte[]> dest, String fileName) throws IOException {
        if (dest.containsKey(fileName)) return;
        InputStream in = StandaloneBindGate.class.getResourceAsStream(fileName);
        if (in == null) {
            if ("StandaloneBindGate.class".equals(fileName)) {
                throw new IOException("找不到绑定校验类");
            }
            return;
        }
        try {
            dest.put(fileName, readFully(in));
        } finally {
            in.close();
        }
    }

    public static final class HostSecret {
        public final String installId;
        public final byte[] secret;

        public HostSecret(String installId, byte[] secret) {
            this.installId = installId;
            this.secret = secret;
        }
    }

    static Path findKnownPmclApp() {
        if ("false".equalsIgnoreCase(System.getProperty("pmcl.bind.known", "true"))) {
            return null;
        }
        String home = System.getProperty("user.home", "");
        Path[] candidates = new Path[] {
                Paths.get("/Applications/pmcl.app"),
                Paths.get("/Applications/PMCL.app"),
                Paths.get("/Applications/pcml.app"),
                Paths.get("/Applications/PCML.app"),
                Paths.get(home, "Applications", "pmcl.app"),
                Paths.get(home, "Applications", "PMCL.app"),
                Paths.get(home, "Applications", "pcml.app"),
                Paths.get(home, "Applications", "PCML.app")
        };
        for (int i = 0; i < candidates.length; i++) {
            if (Files.exists(candidates[i])) return candidates[i];
        }
        return null;
    }

    private static Path findPmclApp(Path start) {
        if (start == null) return null;
        Path p = start.toAbsolutePath().normalize();
        for (int i = 0; i < 12 && p != null; i++) {
            Path namePath = p.getFileName();
            String name = namePath != null ? namePath.toString().toLowerCase(Locale.ROOT) : "";
            if (name.endsWith(".app") && looksLikePmclName(name) && Files.exists(p)) return p;
            p = p.getParent();
        }
        return null;
    }

    private static Path findFromCodeSource() {
        try {
            java.security.CodeSource src = StandaloneBindGate.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return null;
            Path p = Paths.get(src.getLocation().toURI()).toAbsolutePath().normalize();
            Path app = findPmclApp(p);
            if (app != null) return app;
            if (Files.isRegularFile(p) && isLauncherJar(fileName(p))) return p;
            if (Files.isRegularFile(p) && fileName(p).toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return p;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path findFromJavaCommand() {
        try {
            String cmd = System.getProperty("sun.java.command", "");
            if (!notEmpty(cmd)) return null;
            String first = cmd.trim().split("\\s+")[0];
            Path p = Paths.get(first);
            if (!p.isAbsolute()) {
                p = Paths.get(System.getProperty("user.dir", "."), first);
            }
            p = p.toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && isLauncherJar(fileName(p))) return p;
            if (Files.isRegularFile(p) && fileName(p).toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return p;
            }
            Path app = findPmclApp(p);
            if (app != null) return app;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path findFromClassPath() {
        try {
            String cp = System.getProperty("java.class.path", "");
            if (!notEmpty(cp)) return null;
            String sep = System.getProperty("path.separator", ":");
            if (cp.indexOf(sep) < 0) {
                Path p = Paths.get(cp).toAbsolutePath().normalize();
                if (Files.isRegularFile(p) && fileName(p).toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    return p;
                }
                return null;
            }
            String[] parts = cp.split(Pattern.quote(sep));
            for (int i = 0; i < parts.length; i++) {
                Path p = Paths.get(parts[i]).toAbsolutePath().normalize();
                if (Files.isRegularFile(p) && isLauncherJar(fileName(p))) return p;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path scanCommonFolders() {
        String home = System.getProperty("user.home", "");
        Path[] dirs = new Path[] {
                Paths.get("/Applications"),
                Paths.get(home, "Applications"),
                Paths.get(home, "Desktop"),
                Paths.get(home, "Downloads")
        };
        for (int i = 0; i < dirs.length; i++) {
            Path hit = firstPmclAppIn(dirs[i]);
            if (hit != null) return hit;
        }
        return null;
    }

    private static Path findComposeDevApp() {
        Path dir = Paths.get(System.getProperty("user.dir", "."),
                "ui", "build", "compose", "binaries", "main", "app");
        Path hit = firstPmclAppIn(dir);
        if (hit != null) return hit;
        Path named = dir.resolve("pmcl.app");
        return Files.exists(named) ? named : null;
    }

    private static Path firstPmclAppIn(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.app");
            try {
                for (Path p : stream) {
                    if (looksLikePmclName(fileName(p))) return p.toAbsolutePath().normalize();
                }
            } finally {
                stream.close();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean looksLikePmclName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("pmcl") || n.contains("pcml");
    }

    private static boolean isLauncherJar(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".jar")) return false;
        if (n.contains("glfw") || n.contains("bind-gate") || n.contains("agent")) return false;
        return looksLikePmclName(n);
    }

    private static String fileName(Path p) {
        Path n = p == null ? null : p.getFileName();
        return n == null ? "" : n.toString();
    }

    private static Map<String, String> readKv(Path file) throws IOException {
        Map<String, String> kv = new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!notEmpty(line) || line.trim().startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            kv.put(line.substring(0, eq).trim(), line.substring(eq + 1));
        }
        return kv;
    }

    private static void writeKv(Path file, Map<String, String> kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String runCapture(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = readFully(p.getInputStream());
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) >= 0) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    private static void fail(String msg) {
        System.err.println("[PMCL] " + msg);
        System.exit(2);
    }
}
