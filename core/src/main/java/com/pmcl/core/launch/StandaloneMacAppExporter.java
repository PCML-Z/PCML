package com.pmcl.core.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.auth.Account;
import com.pmcl.core.install.AssetIndex;
import com.pmcl.core.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 把已安装的 Minecraft 版本打成可双击的 macOS {@code .app}。
 * <p>
 * 不是把游戏编译成原生程序：bundle 内带 JRE、libraries、assets、natives、模组、光影与资源包，
 * 入口脚本用相对路径启动与启动器相同的 Java 命令。正版 token 不写入 App（离线名）。
 */
public final class StandaloneMacAppExporter {

    static final String CONTENTS_TOKEN = "__PMCL_CONTENTS__";

    private static final String[] GAME_DIRS = {
            "config", "datapacks",
            "kubejs", "scripts", "defaultconfigs", "patchouli_books",
            "journeymap", "local", ".fabric"
    };
    private static final String[] GAME_FILES = {
            "options.txt", "optionsof.txt", "optionsshaders.txt",
            "options.json", "servers.dat", "usercache.json"
    };
    private static final Set<String> SKIP_GAME_DIRS = Set.of(
            "libraries", "assets", "versions", "natives", "bin",
            "logs", "crash-reports", "runtimes", "java",
            "mods", "resourcepacks", "shaderpacks"
    );

    public static boolean isMacOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    public static Account offlineCopy(Account account) {
        String name = "Player";
        String uuid;
        if (account != null && account.getUsername() != null && !account.getUsername().isBlank()) {
            name = account.getUsername();
        }
        if (account != null && account.getUuid() != null && !account.getUuid().isBlank()) {
            uuid = account.getUuid();
        } else {
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");
        }
        return new Account(name, uuid, "0", Account.AccountType.OFFLINE);
    }

    public static String sanitizeAppName(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.endsWith(".app")) s = s.substring(0, s.length() - 4);
        s = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        s = s.replaceAll("^[._]+", "").replaceAll("[._]+$", "").trim();
        if (s.isEmpty()) s = "Minecraft";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    public static Path withAppSuffix(Path target) {
        if (target == null) throw new IllegalArgumentException("target");
        String name = target.getFileName().toString();
        if (!name.endsWith(".app")) {
            return target.resolveSibling(name + ".app");
        }
        return target;
    }

    public void export(LaunchProfile profile, String javaExecutable, Path targetApp,
                       boolean includeSaves, boolean allowNonMac,
                       Consumer<String> onProgress) throws IOException {
        export(profile, javaExecutable, targetApp, includeSaves, allowNonMac, null, onProgress);
    }

    public void export(LaunchProfile profile, String javaExecutable, Path targetApp,
                       boolean includeSaves, boolean allowNonMac, Path extraModsRoot,
                       Consumer<String> onProgress) throws IOException {
        if (!allowNonMac && !isMacOs()) {
            throw new IOException("独立游戏 App 目前仅支持在 macOS 上导出");
        }
        if (profile == null) throw new IOException("启动配置为空");
        if (javaExecutable == null || javaExecutable.isBlank()) {
            throw new IOException("未找到可用于该版本的 Java");
        }
        Path app = withAppSuffix(targetApp.toAbsolutePath().normalize());
        String appName = sanitizeAppName(app.getFileName().toString());
        progress(onProgress, "准备 " + appName + ".app");

        List<String> cmd = new ArrayList<>(profile.buildCommand(javaExecutable));
        stripOnlineAuth(cmd);
        expandClasspathArgfile(cmd);

        Path gameDir = profile.getGameDir();
        Path nativesDir = findFlagValue(cmd, "-Djava.library.path=");
        if (nativesDir == null) nativesDir = findFlagValue(cmd, "-Dorg.lwjgl.librarypath=");
        Path assetsDir = findGameArg(cmd, "--assetsDir");
        if (assetsDir == null) assetsDir = findGameArg(cmd, "--assetsDir".toLowerCase(Locale.ROOT));

        Path javaHome = resolveJavaHome(javaExecutable);
        Path contents = app.resolve("Contents");
        Path macos = contents.resolve("MacOS");
        Path resources = contents.resolve("Resources");
        Path runtime = contents.resolve("Runtime");
        Path game = contents.resolve("Game");
        if (Files.exists(app)) {
            FileUtils.deleteRecursively(app);
        }
        Files.createDirectories(macos);
        Files.createDirectories(resources);
        Files.createDirectories(game);

        progress(onProgress, "复制 Java 运行时");
        copyTree(javaHome, runtime);
        markJavaExecutable(runtime);

        Map<String, String> rewrites = new LinkedHashMap<>();
        Path nativesDest = game.resolve("natives");
        if (nativesDir != null && Files.isDirectory(nativesDir)) {
            progress(onProgress, "复制 natives");
            copyTree(nativesDir, nativesDest);
            rewrites.put(nativesDir.toAbsolutePath().normalize().toString(),
                    CONTENTS_TOKEN + "/Game/natives");
        }

        Path assetsDest = game.resolve("assets");
        if (assetsDir != null && Files.isDirectory(assetsDir)) {
            progress(onProgress, "复制游戏资源");
            copyAssets(assetsDir, assetsDest, cmd, onProgress);
            rewrites.put(assetsDir.toAbsolutePath().normalize().toString(),
                    CONTENTS_TOKEN + "/Game/assets");
        }

        Path gamedata = game.resolve("gamedata");
        if (gameDir != null && Files.isDirectory(gameDir)) {
            progress(onProgress, "复制模组与配置");
            copyGameContent(gameDir, gamedata, includeSaves);
            rewrites.put(gameDir.toAbsolutePath().normalize().toString(),
                    CONTENTS_TOKEN + "/Game/gamedata");
        }

        progress(onProgress, "复制 libraries");
        copyCommandFiles(cmd, game, rewrites);

        progress(onProgress, "复制模组、光影与资源包");
        copyModsAndLoader(profile, cmd, game, gamedata, extraModsRoot);

        List<String> rewritten = rewriteCommand(cmd, rewrites);
        if (!rewritten.isEmpty()) {
            rewritten.set(0, CONTENTS_TOKEN + "/Runtime/bin/java");
        }
        Path workDir = extraModsRoot != null ? extraModsRoot : com.pmcl.core.LauncherConfig.pmclHome();
        progress(onProgress, "写入本机 PMCL 强绑定");
        writeHostBind(workDir, resources);

        writeJvmArgs(resources.resolve("jvm.args"), rewritten);
        writeLauncher(macos.resolve("launch"), appName);
        writeInfoPlist(contents.resolve("Info.plist"), appName);
        writeReadme(resources.resolve("README.txt"), appName);
        setExecutable(macos.resolve("launch"));
        adHocSign(app);
        progress(onProgress, "已写出 " + app);
    }

    static Path resolveJavaHome(String javaExecutable) throws IOException {
        Path exe = Path.of(javaExecutable).toAbsolutePath();
        try {
            exe = exe.toRealPath();
        } catch (IOException ignored) {
        }
        Path bin = exe.getParent();
        if (bin == null || !"bin".equals(bin.getFileName().toString())) {
            throw new IOException("Java 路径不是 bin/java: " + javaExecutable);
        }
        Path home = bin.getParent();
        String javaName = exe.getFileName().toString();
        if (home == null || !Files.isRegularFile(home.resolve("bin").resolve(javaName))) {
            throw new IOException("无法解析 JAVA_HOME: " + javaExecutable);
        }
        return home;
    }

    static void stripOnlineAuth(List<String> cmd) {
        for (int i = 0; i < cmd.size() - 1; i++) {
            String a = cmd.get(i);
            if ("--accessToken".equals(a) || "--clientId".equals(a)) {
                cmd.set(i + 1, "0");
            } else if ("--userType".equals(a)) {
                cmd.set(i + 1, "legacy");
            }
        }
    }

    static void expandClasspathArgfile(List<String> cmd) {
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (!"-cp".equals(cmd.get(i)) && !"-classpath".equals(cmd.get(i))) continue;
            String cp = cmd.get(i + 1);
            if (cp.startsWith("@")) {
                Path file = Path.of(cp.substring(1));
                try {
                    String body = Files.readString(file, StandardCharsets.UTF_8).trim();
                    if (body.startsWith("\"") && body.endsWith("\"") && body.length() >= 2) {
                        body = body.substring(1, body.length() - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                    }
                    cmd.set(i + 1, body);
                } catch (IOException ignored) {
                }
            }
        }
    }

    static List<String> rewriteCommand(List<String> cmd, Map<String, String> rewrites) {
        List<Map.Entry<String, String>> ordered = new ArrayList<>(rewrites.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        List<String> out = new ArrayList<>(cmd.size());
        for (String arg : cmd) {
            String next = arg;
            for (Map.Entry<String, String> e : ordered) {
                if (next.contains(e.getKey())) {
                    next = next.replace(e.getKey(), e.getValue());
                }
            }
            out.add(next);
        }
        return out;
    }

    static String quoteArg(String s) {
        if (s == null) s = "";
        boolean need = s.isEmpty();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'') {
                need = true;
                break;
            }
        }
        if (!need) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void copyCommandFiles(List<String> cmd, Path game, Map<String, String> rewrites) throws IOException {
        Path extra = game.resolve("extra");
        Path libraries = game.resolve("libraries");
        Path versions = game.resolve("versions");
        Path agents = game.resolve("agents");
        for (int i = 0; i < cmd.size(); i++) {
            String arg = cmd.get(i);
            if ("-cp".equals(arg) || "-classpath".equals(arg)) {
                if (i + 1 >= cmd.size()) continue;
                String sep = System.getProperty("path.separator");
                String[] parts = cmd.get(i + 1).split(java.util.regex.Pattern.quote(sep));
                for (String part : parts) {
                    copyOneFile(Path.of(part), libraries, versions, extra, agents, rewrites);
                }
                continue;
            }
            if (arg.startsWith("-javaagent:")) {
                String rest = arg.substring("-javaagent:".length());
                int eq = rest.indexOf('=');
                String path = eq >= 0 ? rest.substring(0, eq) : rest;
                copyOneFile(Path.of(path), libraries, versions, extra, agents, rewrites);
                continue;
            }
            Path maybe = extractPathFromArg(arg);
            if (maybe != null && Files.isRegularFile(maybe)) {
                copyOneFile(maybe, libraries, versions, extra, agents, rewrites);
            }
        }
    }

    private static Path extractPathFromArg(String arg) {
        int eq = arg.indexOf('=');
        String candidate = eq > 0 ? arg.substring(eq + 1) : arg;
        if (candidate.startsWith("-")) return null;
        if (!(candidate.startsWith("/") || (candidate.length() > 2 && candidate.charAt(1) == ':'))) {
            return null;
        }
        Path p = Path.of(candidate);
        return Files.exists(p) ? p.toAbsolutePath().normalize() : null;
    }

    private void copyOneFile(Path src, Path libraries, Path versions, Path extra, Path agents,
                             Map<String, String> rewrites) throws IOException {
        if (src == null) return;
        Path abs = src.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) return;
        String unix = abs.toString().replace('\\', '/');
        Path dest;
        if (unix.contains("/libraries/")) {
            dest = libraries.resolve(unix.substring(unix.indexOf("/libraries/") + "/libraries/".length()));
        } else if (unix.contains("/versions/")) {
            dest = versions.resolve(unix.substring(unix.indexOf("/versions/") + "/versions/".length()));
        } else if (unix.contains("/agents/") || unix.endsWith(".jar") && unix.contains("agent")) {
            dest = agents.resolve(abs.getFileName().toString());
        } else {
            dest = extra.resolve(abs.getFileName().toString());
        }
        Files.createDirectories(dest.getParent());
        if (!Files.exists(dest)) {
            Files.copy(abs, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        rewrites.putIfAbsent(abs.toString(), CONTENTS_TOKEN + "/Game/" + unixRel(gameRoot(libraries), dest));
    }

    private static Path gameRoot(Path libraries) {
        return libraries.getParent();
    }

    private static String unixRel(Path root, Path dest) {
        return root.relativize(dest).toString().replace('\\', '/');
    }

    private void copyAssets(Path assetsDir, Path dest, List<String> cmd, Consumer<String> onProgress)
            throws IOException {
        Files.createDirectories(dest);
        String assetsId = findGameArgString(cmd, "--assetIndex");
        Path indexesSrc = assetsDir.resolve("indexes");
        Path indexesDst = dest.resolve("indexes");
        if (assetsId != null) {
            Path idx = indexesSrc.resolve(assetsId + ".json");
            if (Files.isRegularFile(idx)) {
                Files.createDirectories(indexesDst);
                Files.copy(idx, indexesDst.resolve(idx.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                copyAssetObjects(idx, assetsDir.resolve("objects"), dest.resolve("objects"), onProgress);
                return;
            }
        }
        if (Files.isDirectory(indexesSrc)) {
            copyTree(indexesSrc, indexesDst);
            Path objects = assetsDir.resolve("objects");
            if (Files.isDirectory(objects)) {
                copyTree(objects, dest.resolve("objects"));
            }
        }
        Path logConfigs = assetsDir.resolve("log_configs");
        if (Files.isDirectory(logConfigs)) {
            copyTree(logConfigs, dest.resolve("log_configs"));
        }
    }

    private void copyAssetObjects(Path indexJson, Path objectsSrc, Path objectsDst,
                                  Consumer<String> onProgress) throws IOException {
        String json = Files.readString(indexJson, StandardCharsets.UTF_8);
        AssetIndex idx = AssetIndex.parse(json);
        Files.createDirectories(objectsDst);
        int n = 0;
        int total = idx.getAssets().size();
        for (AssetIndex.Asset a : idx.getAssets().values()) {
            Path src = objectsSrc.resolve(a.getPath());
            if (!Files.isRegularFile(src)) continue;
            Path dst = objectsDst.resolve(a.getPath());
            Files.createDirectories(dst.getParent());
            if (!Files.exists(dst)) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            n++;
            if (n % 400 == 0) progress(onProgress, "复制资源 " + n + "/" + total);
        }
    }

    private void copyModsAndLoader(LaunchProfile profile, List<String> cmd, Path game,
                                   Path gamedata, Path extraModsRoot) throws IOException {
        Files.createDirectories(gamedata);
        Path destMods = gamedata.resolve("mods");
        Files.createDirectories(destMods);
        String versionId = profile.getVersionId();
        Path mcRoot = inferMcRoot(cmd);
        Path versionDir = findVersionDir(cmd, versionId, mcRoot, extraModsRoot);
        String inherits = versionDir != null ? readInheritsFrom(versionDir) : null;

        Path destVersions = game.resolve("versions");
        copyVersionDirsFromClasspath(cmd, destVersions);
        if (versionDir != null && Files.isDirectory(versionDir)) {
            copyTree(versionDir, destVersions.resolve(versionDir.getFileName().toString()));
        }
        if (inherits != null && !inherits.isBlank()) {
            Path parent = null;
            if (mcRoot != null) parent = mcRoot.resolve("versions").resolve(inherits);
            if ((parent == null || !Files.isDirectory(parent)) && extraModsRoot != null) {
                parent = extraModsRoot.resolve("versions").resolve(inherits);
            }
            if (parent != null && Files.isDirectory(parent)) {
                copyTree(parent, destVersions.resolve(inherits));
            }
        }

        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        if (profile.getGameDir() != null) roots.add(profile.getGameDir());
        if (extraModsRoot != null) roots.add(extraModsRoot);
        if (mcRoot != null) roots.add(mcRoot);
        if (versionDir != null) roots.add(versionDir);

        List<String> keys = new ArrayList<>();
        if (versionId != null && !versionId.isBlank()) keys.add(versionId);
        if (inherits != null && !inherits.isBlank()) keys.add(inherits);

        Path destRp = gamedata.resolve("resourcepacks");
        Path destSh = gamedata.resolve("shaderpacks");
        for (Path root : roots) {
            harvestMods(root.resolve("mods"), destMods, keys);
            harvestPacks(root.resolve("resourcepacks"), destRp, keys, false);
            harvestPacks(root.resolve("shaderpacks"), destSh, keys, true);
            Path fabric = root.resolve(".fabric");
            if (Files.isDirectory(fabric) && !Files.exists(gamedata.resolve(".fabric"))) {
                copyTree(fabric, gamedata.resolve(".fabric"));
            }
            for (String file : GAME_FILES) {
                Path src = root.resolve(file);
                Path dst = gamedata.resolve(file);
                if (Files.isRegularFile(src) && !Files.exists(dst)) {
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static void harvestMods(Path srcMods, Path destMods, List<String> versionKeys) throws IOException {
        if (!Files.isDirectory(srcMods)) return;
        copyLooseModJars(srcMods, destMods);
        if (versionKeys == null) return;
        Path srcAbs = srcMods.toAbsolutePath().normalize();
        for (String key : versionKeys) {
            if (key == null || key.isBlank() || key.contains("..") || key.contains("/") || key.contains("\\")) {
                continue;
            }
            Path nested = srcMods.resolve(key).toAbsolutePath().normalize();
            if (Files.isDirectory(nested) && nested.startsWith(srcAbs)) {
                copyLooseModJars(nested, destMods);
            }
        }
    }

    static void harvestPacks(Path srcDir, Path destDir, List<String> versionKeys, boolean shaderExtras)
            throws IOException {
        if (!Files.isDirectory(srcDir)) return;
        Files.createDirectories(destDir);
        copyLoosePacks(srcDir, destDir, versionKeys, shaderExtras);
        if (versionKeys == null) return;
        Path srcAbs = srcDir.toAbsolutePath().normalize();
        for (String key : versionKeys) {
            if (key == null || key.isBlank() || key.contains("..") || key.contains("/") || key.contains("\\")) {
                continue;
            }
            Path nested = srcDir.resolve(key).toAbsolutePath().normalize();
            if (Files.isDirectory(nested) && nested.startsWith(srcAbs)) {
                copyLoosePacks(nested, destDir, null, shaderExtras);
            }
        }
    }

    private static void copyLoosePacks(Path srcDir, Path destDir, List<String> skipDirNames,
                                       boolean shaderExtras) throws IOException {
        Path destAbs = destDir.toAbsolutePath().normalize();
        try (var stream = Files.list(srcDir)) {
            for (Path src : (Iterable<Path>) stream::iterator) {
                if (Files.isSymbolicLink(src)) continue;
                String name = src.getFileName().toString();
                Path dest = destAbs.resolve(name).normalize();
                if (!dest.startsWith(destAbs) || Files.exists(dest)) continue;
                if (Files.isRegularFile(src) && isPackFile(name, shaderExtras)) {
                    Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES);
                } else if (Files.isDirectory(src) && !isVersionKeyName(name, skipDirNames)) {
                    copyTree(src, dest);
                }
            }
        }
    }

    private static boolean isPackFile(String name, boolean shaderExtras) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip") || n.endsWith(".zip.disabled")) return true;
        return shaderExtras && (n.endsWith(".txt") || n.endsWith(".properties"));
    }

    private static boolean isVersionKeyName(String name, List<String> keys) {
        if (keys == null || name == null) return false;
        for (String key : keys) {
            if (name.equals(key)) return true;
        }
        return false;
    }

    private static void copyLooseModJars(Path srcDir, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Path destAbs = destDir.toAbsolutePath().normalize();
        try (var stream = Files.list(srcDir)) {
            for (Path src : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(src) || Files.isSymbolicLink(src)) continue;
                String name = src.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".jar") || name.endsWith(".jar.disabled") || name.endsWith(".zip"))) {
                    continue;
                }
                Path dest = destAbs.resolve(src.getFileName().toString()).normalize();
                if (!dest.startsWith(destAbs) || Files.exists(dest)) continue;
                Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void copyVersionDirsFromClasspath(List<String> cmd, Path destVersions) throws IOException {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        for (Path file : classpathFiles(cmd)) {
            String unix = file.toAbsolutePath().normalize().toString().replace('\\', '/');
            int idx = unix.indexOf("/versions/");
            if (idx < 0) continue;
            Path versionDir = file.getParent();
            if (versionDir != null && Files.isDirectory(versionDir)) dirs.add(versionDir);
        }
        for (Path dir : dirs) {
            copyTree(dir, destVersions.resolve(dir.getFileName().toString()));
        }
    }

    private static List<Path> classpathFiles(List<String> cmd) {
        List<Path> out = new ArrayList<>();
        String sep = System.getProperty("path.separator");
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (!"-cp".equals(cmd.get(i)) && !"-classpath".equals(cmd.get(i))) continue;
            for (String part : cmd.get(i + 1).split(java.util.regex.Pattern.quote(sep))) {
                if (part.isBlank()) continue;
                Path p = Path.of(part);
                if (Files.isRegularFile(p)) out.add(p);
            }
        }
        return out;
    }

    private static Path inferMcRoot(List<String> cmd) {
        for (Path file : classpathFiles(cmd)) {
            String unix = file.toAbsolutePath().normalize().toString().replace('\\', '/');
            int lib = unix.indexOf("/libraries/");
            if (lib >= 0) return Path.of(unix.substring(0, lib));
            int ver = unix.indexOf("/versions/");
            if (ver >= 0) return Path.of(unix.substring(0, ver));
        }
        return null;
    }

    private static Path findVersionDir(List<String> cmd, String versionId, Path mcRoot, Path extraRoot) {
        if (versionId != null && !versionId.isBlank()) {
            for (Path root : new Path[] { mcRoot, extraRoot }) {
                if (root == null) continue;
                Path dir = root.resolve("versions").resolve(versionId);
                if (Files.isDirectory(dir)) return dir;
            }
        }
        for (Path file : classpathFiles(cmd)) {
            String unix = file.toAbsolutePath().normalize().toString().replace('\\', '/');
            int idx = unix.indexOf("/versions/");
            if (idx < 0) continue;
            Path dir = file.getParent();
            if (dir != null && Files.isDirectory(dir)) return dir;
        }
        return null;
    }

    private static String readInheritsFrom(Path versionDir) {
        if (versionDir == null || !Files.isDirectory(versionDir)) return null;
        Path named = versionDir.resolve(versionDir.getFileName().toString() + ".json");
        String from = parseInheritsFrom(named);
        if (from != null) return from;
        try (var stream = Files.list(versionDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.getFileName().toString().endsWith(".json")) {
                    from = parseInheritsFrom(p);
                    if (from != null) return from;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String parseInheritsFrom(Path json) {
        if (json == null || !Files.isRegularFile(json)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("inheritsFrom") && !o.get("inheritsFrom").isJsonNull()) {
                String v = o.get("inheritsFrom").getAsString();
                return v == null || v.isBlank() ? null : v;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void copyGameContent(Path gameDir, Path dest, boolean includeSaves) throws IOException {
        Files.createDirectories(dest);
        boolean mcRoot = Files.isDirectory(gameDir.resolve("libraries"))
                && Files.isDirectory(gameDir.resolve("versions"));
        for (String dir : GAME_DIRS) {
            Path src = gameDir.resolve(dir);
            if (Files.isDirectory(src)) copyTree(src, dest.resolve(dir));
        }
        for (String file : GAME_FILES) {
            Path src = gameDir.resolve(file);
            if (Files.isRegularFile(src)) {
                Files.copy(src, dest.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (includeSaves) {
            Path saves = gameDir.resolve("saves");
            if (Files.isDirectory(saves)) copyTree(saves, dest.resolve("saves"));
            Path shots = gameDir.resolve("screenshots");
            if (Files.isDirectory(shots)) copyTree(shots, dest.resolve("screenshots"));
        }
        if (!mcRoot) {
            try (var stream = Files.list(gameDir)) {
                stream.filter(Files::isDirectory).forEach(p -> {
                    String name = p.getFileName().toString();
                    if (SKIP_GAME_DIRS.contains(name)) return;
                    for (String known : GAME_DIRS) {
                        if (known.equals(name)) return;
                    }
                    if ("saves".equals(name) || "screenshots".equals(name)) return;
                    try {
                        if (!Files.exists(dest.resolve(name))) {
                            copyTree(p, dest.resolve(name));
                        }
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    static void copyTree(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) return;
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Path target = rel.toString().isEmpty() ? dst : dst.resolve(rel);
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dst.resolve(src.relativize(file));
                Files.createDirectories(target.getParent());
                if (Files.isSymbolicLink(file)) {
                    Files.copy(file, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void markJavaExecutable(Path runtime) {
        Path java = runtime.resolve("bin").resolve("java");
        setExecutable(java);
        Path spawn = runtime.resolve("lib").resolve("jspawnhelper");
        setExecutable(spawn);
    }

    private static void setExecutable(Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
        }
    }

    private static void writeJvmArgs(Path file, List<String> cmd) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < cmd.size(); i++) {
            sb.append(quoteArg(cmd.get(i))).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeHostBind(Path workDir, Path resources) throws IOException {
        StandaloneBindGate.HostSecret host = StandaloneBindGate.ensureHostSecret(workDir);
        Path launcher = StandaloneBindGate.detectLauncherPath();
        if (launcher == null || !Files.exists(launcher)) {
            // Gradle / IDE / 未安装 .app 时：用本机 PMCL 工作目录作为安装身份
            launcher = workDir;
        }
        StandaloneBindGate.writeTicket(
                resources.resolve(StandaloneBindGate.TICKET_FILE_NAME),
                StandaloneBindGate.hostFile(workDir),
                launcher,
                host,
                StandaloneBindGate.deviceId());
        StandaloneBindGate.writeGateJar(resources.resolve(StandaloneBindGate.GATE_JAR_NAME));
    }

    private static void writeLauncher(Path file, String appName) throws IOException {
        String safe = appName.replace("'", "");
        String script = """
                #!/bin/bash
                set -euo pipefail
                CONTENTS="$(cd "$(dirname "$0")/.." && pwd)"
                JAVA="$CONTENTS/Runtime/bin/java"
                if [ ! -x "$JAVA" ]; then
                  osascript -e 'display dialog "找不到内置 Java，无法启动 %s。" buttons {"好"} with icon stop' || true
                  exit 1
                fi
                xattr -dr com.apple.quarantine "$CONTENTS" >/dev/null 2>&1 || true
                ERR="$CONTENTS/Resources/bind-last-error.txt"
                if ! "$JAVA" -cp "$CONTENTS/Resources/bind-gate.jar" com.pmcl.core.launch.StandaloneBindGate "$CONTENTS" >"$ERR" 2>&1; then
                  DETAIL=$(tr -d '"\\\\' < "$ERR" | tr '\\n' ' ' | cut -c1-240)
                  if [ -z "$DETAIL" ]; then
                    DETAIL="此游戏已与本机 PMCL 绑定。删除启动器、或把 App 拷到其他电脑后无法启动。"
                  fi
                  osascript -e "display dialog \\"$DETAIL\\" buttons {\\"好\\"} with icon stop" >/dev/null 2>&1 || true
                  exit 2
                fi
                ARGS="$(mktemp -t pmcl-launch)"
                sed "s|__PMCL_CONTENTS__|$CONTENTS|g" "$CONTENTS/Resources/jvm.args" > "$ARGS"
                cd "$CONTENTS/Game/gamedata" 2>/dev/null || cd "$CONTENTS/Game"
                exec "$JAVA" @"$ARGS"
                """.formatted(safe);
        Files.writeString(file, script, StandardCharsets.UTF_8);
    }

    private static void writeInfoPlist(Path file, String appName) throws IOException {
        String id = appName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "");
        if (id.isEmpty()) id = "game";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>CFBundleDevelopmentRegion</key><string>zh_CN</string>
                  <key>CFBundleExecutable</key><string>launch</string>
                  <key>CFBundleIdentifier</key><string>com.pmcl.standalone.%s</string>
                  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
                  <key>CFBundleName</key><string>%s</string>
                  <key>CFBundleDisplayName</key><string>%s</string>
                  <key>CFBundlePackageType</key><string>APPL</string>
                  <key>CFBundleShortVersionString</key><string>1.0</string>
                  <key>CFBundleVersion</key><string>1</string>
                  <key>LSMinimumSystemVersion</key><string>11.0</string>
                  <key>LSApplicationCategoryType</key><string>public.app-category.games</string>
                  <key>NSHighResolutionCapable</key><true/>
                </dict>
                </plist>
                """.formatted(id, escapeXml(appName), escapeXml(appName));
        Files.writeString(file, xml, StandardCharsets.UTF_8);
    }

    private static void writeReadme(Path file, String appName) throws IOException {
        Files.writeString(file, """
                %s
                由 PMCL 导出的独立游戏包。双击即可启动，无需打开启动器界面。
                已与本机 PMCL 强绑定：删除本机启动器，或把此 App 分发到其他电脑，将无法启动。
                使用当前玩家名的离线会话；正版服务器 / Realms 请仍用 PMCL 启动。
                这是导出时的快照，模组与版本不会自动更新。
                """.formatted(appName), StandardCharsets.UTF_8);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void adHocSign(Path app) {
        if (!isMacOs()) return;
        try {
            Process p = new ProcessBuilder("codesign", "--force", "--deep", "-s", "-", app.toString())
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    private static Path findFlagValue(List<String> cmd, String prefix) {
        for (String arg : cmd) {
            if (arg.startsWith(prefix)) {
                String v = arg.substring(prefix.length());
                if (!v.isBlank()) return Path.of(v);
            }
        }
        return null;
    }

    private static Path findGameArg(List<String> cmd, String flag) {
        String v = findGameArgString(cmd, flag);
        return v == null ? null : Path.of(v);
    }

    private static String findGameArgString(List<String> cmd, String flag) {
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (flag.equals(cmd.get(i))) return cmd.get(i + 1);
        }
        return null;
    }

    private static void progress(Consumer<String> onProgress, String msg) {
        if (onProgress != null) onProgress.accept(msg);
    }
}
