package com.pmcl.core.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.InstallProgress;
import com.pmcl.core.util.SafeZipExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge / NeoForge client-side processor 执行器。
 * <p>
 * 跳过 {@code sides=["server"]} 的步骤；解析 {@code data.*.client}、builtins
 * （{@code ROOT/INSTALLER/MINECRAFT_JAR/SIDE/MIRRORLIST}）以及 {@code [maven]} / {@code /data/...} 参数，
 * 按顺序用外部 Java 进程跑完 jarsplitter → AutoRenamingTool → binarypatcher 等。
 */
final class ForgeProcessorRunner {

    private static final long PROCESSOR_TIMEOUT_MIN = 15;

    private final Path workDir;
    private final Path librariesDir;
    private final Path versionsDir;
    private final Path installerJar;
    private final Path clientJar;
    private final String javaExe;
    private final Path extractDir;

    ForgeProcessorRunner(Path workDir, Path librariesDir, Path versionsDir,
                         Path installerJar, Path clientJar, String javaExe) throws IOException {
        this.workDir = workDir;
        this.librariesDir = librariesDir;
        this.versionsDir = versionsDir;
        this.installerJar = installerJar.toAbsolutePath().normalize();
        this.clientJar = clientJar.toAbsolutePath().normalize();
        this.javaExe = javaExe;
        this.extractDir = Files.createTempDirectory("forge-proc-");
        if (this.versionsDir == null) throw new IOException("versionsDir required");
    }

    void cleanup() {
        try {
            com.pmcl.core.util.FileUtils.deleteRecursively(extractDir);
        } catch (Throwable ignored) {}
    }

    /**
     * 执行 client 侧 processors；结束后校验 {@code PATCHED}（及可选 SHA）存在。
     */
    void runClient(JsonObject profile, Consumer<InstallProgress> onProgress) throws IOException {
        if (!profile.has("processors") || !profile.get("processors").isJsonArray()) return;
        JsonArray processors = profile.getAsJsonArray("processors");
        if (processors.size() == 0) return;

        if (!Files.isRegularFile(clientJar)) {
            throw new IOException("运行 Forge processors 需要原版 client.jar: " + clientJar);
        }

        Map<String, String> vars = buildVarMap(profile);
        int total = 0;
        for (JsonElement e : processors) {
            if (e.isJsonObject() && isClientProcessor(e.getAsJsonObject())) total++;
        }
        int done = 0;
        for (JsonElement e : processors) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InstallInterruptedException("Forge processor 被中断");
            }
            if (!e.isJsonObject()) continue;
            JsonObject proc = e.getAsJsonObject();
            if (!isClientProcessor(proc)) continue;
            done++;
            if (onProgress != null) {
                onProgress.accept(new InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_LIBRARIES, done, Math.max(total, 1),
                        "执行 Forge processor " + done + "/" + total));
            }
            if (outputsSatisfied(proc, vars)) {
                continue;
            }
            runOne(proc, vars);
        }

        // NeoForge 常无 outputs：必须有 PATCHED 产物
        String patched = vars.get("PATCHED");
        if (patched != null && !patched.isBlank()) {
            Path patchedPath = Path.of(patched);
            if (!Files.isRegularFile(patchedPath) || Files.size(patchedPath) < 32) {
                throw new IOException("Forge processor 未生成 PATCHED 客户端 jar: " + patchedPath);
            }
            String patchedSha = vars.get("PATCHED_SHA");
            if (patchedSha != null && patchedSha.matches("[0-9a-fA-F]{40}")) {
                String actual = sha1Hex(patchedPath);
                if (!patchedSha.equalsIgnoreCase(actual)) {
                    throw new IOException("PATCHED SHA-1 不匹配: 期望=" + patchedSha + " 实际=" + actual);
                }
            }
        }
    }

    private Map<String, String> buildVarMap(JsonObject profile) throws IOException {
        Map<String, String> vars = new HashMap<>();
        vars.put("ROOT", workDir.toAbsolutePath().normalize().toString());
        vars.put("INSTALLER", installerJar.toString());
        vars.put("MINECRAFT_JAR", clientJar.toString());
        vars.put("SIDE", "client");
        vars.put("MIRRORLIST", profile.has("mirrorList") && !profile.get("mirrorList").isJsonNull()
                ? profile.get("mirrorList").getAsString()
                : "https://files.minecraftforge.net/mirrors-2.0.json");
        String mc = profile.has("minecraft") && !profile.get("minecraft").isJsonNull()
                ? profile.get("minecraft").getAsString() : "";
        vars.put("MINECRAFT_VERSION", mc);
        vars.put("LIBRARY_DIR", librariesDir.toAbsolutePath().normalize().toString());

        if (profile.has("data") && profile.get("data").isJsonObject()) {
            JsonObject data = profile.getAsJsonObject("data");
            for (Map.Entry<String, JsonElement> e : data.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject side = e.getValue().getAsJsonObject();
                String client = side.has("client") && !side.get("client").isJsonNull()
                        ? side.get("client").getAsString() : null;
                if (client == null) continue;
                vars.put(e.getKey(), resolveToken(client, vars));
            }
        }
        // 第二遍：允许 data 值互相引用（少见但安全）
        for (String key : new ArrayList<>(vars.keySet())) {
            vars.put(key, resolveToken(vars.get(key), vars));
        }
        return vars;
    }

    private String resolveToken(String raw, Map<String, String> vars) throws IOException {
        if (raw == null) return "";
        String token = ForgeMavenCoords.stripQuotes(raw.trim());
        if (token.isEmpty()) return "";

        // {VAR}
        if (token.startsWith("{") && token.endsWith("}") && token.length() >= 2) {
            String key = token.substring(1, token.length() - 1);
            String v = vars.get(key);
            if (v != null) return v;
            return token;
        }

        // [maven coords]
        if (token.startsWith("[") && token.endsWith("]")) {
            String path = ForgeMavenCoords.toPath(token);
            Path abs = librariesDir.resolve(path).toAbsolutePath().normalize();
            Files.createDirectories(abs.getParent());
            return abs.toString();
        }

        // /data/... inside installer（ZipSlip：目标必须落在 extractDir 内）
        if (token.startsWith("/data/") || token.startsWith("data/")) {
            String entry = token.startsWith("/") ? token.substring(1) : token;
            if (entry.contains("..") || entry.startsWith("/") || entry.startsWith("\\")) {
                throw new IOException("非法 installer data 路径: " + token);
            }
            Path extractBase = extractDir.toAbsolutePath().normalize();
            Path out = extractBase.resolve(entry).normalize();
            if (!out.startsWith(extractBase)) {
                throw new IOException("ZipSlip: installer data 路径越界: " + token);
            }
            if (!Files.isRegularFile(out)) {
                extractFromInstaller(entry, out);
            }
            return out.toString();
        }

        // relative libraries path
        if (token.startsWith("libraries/")) {
            return workDir.resolve(token).toAbsolutePath().normalize().toString();
        }

        return token;
    }

    private void extractFromInstaller(String entryName, Path target) throws IOException {
        Path extractBase = extractDir.toAbsolutePath().normalize();
        Path dest = target.toAbsolutePath().normalize();
        if (!dest.startsWith(extractBase)) {
            throw new IOException("ZipSlip: 拒绝解压到 extractDir 外: " + entryName);
        }
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("installer 中缺少 " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                SafeZipExtractor.copyLimited(in, dest, SafeZipExtractor.DEFAULT_MAX_ENTRY_SIZE);
            }
        }
    }

    private static boolean isClientProcessor(JsonObject proc) {
        if (!proc.has("sides") || !proc.get("sides").isJsonArray()) return true;
        JsonArray sides = proc.getAsJsonArray("sides");
        if (sides.size() == 0) return true;
        boolean hasClient = false;
        boolean hasServer = false;
        for (JsonElement s : sides) {
            if (!s.isJsonPrimitive()) continue;
            String v = s.getAsString();
            if ("client".equalsIgnoreCase(v)) hasClient = true;
            if ("server".equalsIgnoreCase(v)) hasServer = true;
        }
        return hasClient || !hasServer;
    }

    private boolean outputsSatisfied(JsonObject proc, Map<String, String> vars) throws IOException {
        if (!proc.has("outputs") || !proc.get("outputs").isJsonObject()) return false;
        JsonObject outputs = proc.getAsJsonObject("outputs");
        if (outputs.size() == 0) return false;
        for (Map.Entry<String, JsonElement> e : outputs.entrySet()) {
            String outPath = resolveToken(e.getKey(), vars);
            Path p = Path.of(outPath);
            if (!Files.isRegularFile(p) || Files.size(p) < 16) return false;
            String expected = resolveToken(e.getValue().getAsString(), vars);
            expected = ForgeMavenCoords.stripQuotes(expected);
            // 声明了哈希但未解析成 40 位 hex（残留 {SHA} 等）→ 不可跳过，强制重跑
            if (expected != null && !expected.isBlank()) {
                if (!expected.matches("[0-9a-fA-F]{40}")) {
                    return false;
                }
                if (!expected.equalsIgnoreCase(sha1Hex(p))) return false;
            } else if (Files.size(p) < 1024 || !looksLikeZip(p)) {
                // 无哈希时至少要求像合法 jar，避免中断留下的半成品被当成完成
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeZip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(2);
            return magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private void runOne(JsonObject proc, Map<String, String> vars) throws IOException {
        if (!proc.has("jar") || proc.get("jar").isJsonNull()) {
            throw new IOException("processor 缺少 jar");
        }
        Path mainJar = Path.of(resolveToken("[" + ForgeMavenCoords.stripBrackets(proc.get("jar").getAsString()) + "]", vars));
        // jar field may already be bare coords
        if (!Files.isRegularFile(mainJar)) {
            mainJar = librariesDir.resolve(ForgeMavenCoords.toPath(proc.get("jar").getAsString()));
        }
        if (!Files.isRegularFile(mainJar)) {
            throw new IOException("processor jar 不存在: " + mainJar);
        }

        List<Path> cp = new ArrayList<>();
        cp.add(mainJar);
        if (proc.has("classpath") && proc.get("classpath").isJsonArray()) {
            for (JsonElement c : proc.getAsJsonArray("classpath")) {
                if (!c.isJsonPrimitive()) continue;
                Path lib = librariesDir.resolve(ForgeMavenCoords.toPath(c.getAsString()));
                if (!Files.isRegularFile(lib)) {
                    throw new IOException("processor classpath 缺失: " + lib);
                }
                cp.add(lib);
            }
        }

        String mainClass = readMainClass(mainJar);
        if (mainClass == null || mainClass.isBlank()) {
            throw new IOException("processor jar 无 Main-Class: " + mainJar);
        }

        List<String> args = new ArrayList<>();
        if (proc.has("args") && proc.get("args").isJsonArray()) {
            for (JsonElement a : proc.getAsJsonArray("args")) {
                if (!a.isJsonPrimitive()) continue;
                args.add(substituteArg(a.getAsString(), vars));
            }
        }

        // Ensure parent dirs for output-like args that look like absolute paths ending .jar/.txt/.lzma
        for (String arg : args) {
            if (arg.startsWith("/") || (arg.length() > 2 && arg.charAt(1) == ':')) {
                Path p = Path.of(arg);
                if (arg.contains(".") && p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-cp");
        cmd.add(joinClasspath(cp));
        cmd.add(mainClass);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 边读边等，避免 stdout 填满管道导致子进程死锁；输出截断以防 OOM
        final java.io.ByteArrayOutputStream outputBuf = new java.io.ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    synchronized (outputBuf) {
                        int room = 64 * 1024 - outputBuf.size();
                        if (room > 0) outputBuf.write(buf, 0, Math.min(n, room));
                    }
                }
            } catch (IOException ignored) {}
        }, "forge-processor-drain");
        drainer.setDaemon(true);
        drainer.start();
        boolean finished;
        try {
            finished = p.waitFor(PROCESSOR_TIMEOUT_MIN, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new InstallInterruptedException("Forge processor 被中断", e);
        }
        try {
            drainer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new InstallInterruptedException("Forge processor 被中断", e);
        }
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Forge processor 超时 (" + PROCESSOR_TIMEOUT_MIN + "min): " + mainClass);
        }
        if (p.exitValue() != 0) {
            String preview;
            synchronized (outputBuf) {
                preview = outputBuf.toString(StandardCharsets.UTF_8).trim();
            }
            if (preview.length() > 800) preview = preview.substring(preview.length() - 800);
            throw new IOException("Forge processor 失败 exit=" + p.exitValue()
                    + " main=" + mainClass
                    + (preview.isEmpty() ? "" : ("\n" + preview)));
        }
    }

    private String substituteArg(String raw, Map<String, String> vars) throws IOException {
        String s = raw;
        // Replace {VAR} occurrences
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf('{', i);
            if (start < 0) {
                sb.append(s.substring(i));
                break;
            }
            sb.append(s, i, start);
            int end = s.indexOf('}', start + 1);
            if (end < 0) {
                sb.append(s.substring(start));
                break;
            }
            String key = s.substring(start + 1, end);
            String val = vars.get(key);
            if (val != null) {
                sb.append(val);
            } else {
                // try resolve as full token
                sb.append(resolveToken("{" + key + "}", vars));
            }
            i = end + 1;
        }
        String replaced = sb.toString();
        // Whole-arg maven coords
        if (replaced.startsWith("[") && replaced.endsWith("]")) {
            return resolveToken(replaced, vars);
        }
        if (replaced.startsWith("/data/") || replaced.startsWith("data/")) {
            return resolveToken(replaced, vars);
        }
        return replaced;
    }

    private static String joinClasspath(List<Path> cp) {
        String sep = System.getProperty("path.separator", ":");
        StringBuilder sb = new StringBuilder();
        for (Path p : cp) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.toAbsolutePath().normalize());
        }
        return sb.toString();
    }

    private static String readMainClass(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return null;
            return mf.getMainAttributes().getValue("Main-Class");
        }
    }

    private static String sha1Hex(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
