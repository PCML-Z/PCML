package com.pmcl.core.launch;

import com.pmcl.core.download.DownloadManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Legacy Minecraft translation layer based on open-source RetroWrapper.
 * <p>
 * Enables launching old (LWJGL 2 / LaunchWrapper era) Minecraft with modern
 * Java 21+ — including Apple Silicon arm64 Java — without requiring x86 Java 8
 * via Rosetta.
 * <p>
 * Dependencies are <b>bundled</b> under
 * {@code classpath:/com/pmcl/core/retrowrapper/} and extracted into the work
 * directory on first use. Network download is only a last-resort fallback.
 * <p>
 * Upstream projects:
 * <ul>
 *   <li><a href="https://github.com/NeRdTheNed/RetroWrapper">NeRdTheNed/RetroWrapper</a> (MIT)</li>
 *   <li><a href="https://github.com/LightWayUp/LegacyLauncher">LightWayUp/LegacyLauncher</a></li>
 *   <li><a href="https://github.com/NeRdTheNed/FrankenLWJGL">NeRdTheNed/FrankenLWJGL</a></li>
 *   <li><a href="https://github.com/r58Playz/jinput-m1">r58Playz/jinput-m1</a></li>
 * </ul>
 */
public final class RetroWrapperSupport {

    public static final String VERSION = "1.7.8";

    private static final String RESOURCE_PREFIX = "com/pmcl/core/retrowrapper/";

    private static final String RETRO_WRAPPER_URL =
            "https://github.com/NeRdTheNed/RetroWrapper/releases/download/v1.7.8%2BneRd/RetroWrapper-1.7.8.jar";
    private static final String LAUNCHWRAPPER_URL =
            "https://github.com/LightWayUp/LegacyLauncher/releases/download/v1.13-java-9-and-above/launchwrapper-1.13-java-9-and-above.jar";
    private static final String FRANKEN_LWJGL_URL =
            "https://github.com/NeRdTheNed/FrankenLWJGL/releases/download/2.9.4-nightly-20150209%2B2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar";
    private static final String JINPUT_M1_URL =
            "https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.jar";
    /** Match Franken natives; RetroWrapper warns that macOS needs LWJGL ≥ 2.9.3. */
    private static final String LWJGL_294 = "2.9.4-nightly-20150209";
    private static final String LWJGL_294_URL =
            "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl/"
                    + LWJGL_294 + "/lwjgl-" + LWJGL_294 + ".jar";
    private static final String LWJGL_UTIL_294_URL =
            "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl_util/"
                    + LWJGL_294 + "/lwjgl_util-" + LWJGL_294 + ".jar";

    /** Java 9+ LaunchWrapper / LogWrapper hard-depends on log4j (alpha/beta have none). */
    private static final String LOG4J_VER = "2.17.2";
    private static final String LOG4J_API_URL =
            "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/"
                    + LOG4J_VER + "/log4j-api-" + LOG4J_VER + ".jar";
    private static final String LOG4J_CORE_URL =
            "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/"
                    + LOG4J_VER + "/log4j-core-" + LOG4J_VER + ".jar";

    private static final String[] MAVEN_JARS = {
            "asm-9.7.jar",
            "asm-commons-9.7.jar",
            "asm-tree-9.7.jar",
            "asm-analysis-9.7.jar",
            "asm-util-9.7.jar",
            "jopt-simple-5.0.4.jar",
    };

    private static final String[] MAVEN_URLS = {
            "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar",
            "https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar",
            "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar",
            "https://repo1.maven.org/maven2/org/ow2/asm/asm-analysis/9.7/asm-analysis-9.7.jar",
            "https://repo1.maven.org/maven2/org/ow2/asm/asm-util/9.7/asm-util-9.7.jar",
            "https://repo1.maven.org/maven2/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar",
    };

    private static final Pattern RELEASE_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})(?:\\.\\d{1,2})?");

    private RetroWrapperSupport() {}

    /**
     * Classic / alpha / beta ids that predate numbered releases.
     */
    public static boolean isPreReleaseLegacyId(String versionId) {
        if (versionId == null || versionId.isBlank()) return false;
        String s = versionId.toLowerCase(Locale.ROOT);
        return s.startsWith("c0.") || s.startsWith("c1.")
                || s.startsWith("a0.") || s.startsWith("a1.")
                || s.startsWith("b1.") || s.startsWith("rd-")
                || s.startsWith("inf-") || s.startsWith("in-")
                || s.contains("classic");
    }

    /**
     * Approximate Minecraft release as {@code major * 1000 + minor}.
     * Returns {@code 0} for classic/alpha/beta, {@code -1} if unknown.
     */
    public static int estimateMinecraftRelease(String versionId) {
        if (versionId == null || versionId.isBlank()) return -1;
        if (isPreReleaseLegacyId(versionId)) return 0;
        Matcher m = RELEASE_PATTERN.matcher(versionId);
        if (!m.find()) return -1;
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        // Ignore year-like false positives (e.g. 2024.x); MC releases are 0.x / 1.x
        if (major > 1) return -1;
        return major * 1000 + minor;
    }

    /**
     * RetroTweaker / MinecraftApplet path — only for pre-1.6 (applet era).
     * Applying this to 1.6+ (especially 1.13 OptiFine) fatally looks for MinecraftApplet.
     */
    public static boolean needsRetroTweaker(String versionId) {
        int rel = estimateMinecraftRelease(versionId);
        if (rel >= 0) return rel < 1006; // < 1.6
        return isPreReleaseLegacyId(versionId);
    }

    /**
     * LWJGL 2 era (&lt; 1.13). May need FrankenLWJGL on Apple Silicon; not RetroTweaker.
     */
    public static boolean isLwjgl2Era(String versionId) {
        int rel = estimateMinecraftRelease(versionId);
        if (rel >= 0) return rel < 1013; // < 1.13
        return isPreReleaseLegacyId(versionId);
    }

    /**
     * Official macOS arm64 natives arrived around 1.17. Versions below need x86_64 Java
     * (Rosetta) on Apple Silicon — LWJGL 3.1.x {@code natives-macos} is x86_64-only.
     */
    public static boolean needsRosettaOnAppleSilicon(String versionId) {
        int rel = estimateMinecraftRelease(versionId);
        if (rel >= 0) return rel < 1017; // < 1.17
        return isPreReleaseLegacyId(versionId);
    }

    /** True when RetroWrapper / FrankenLWJGL translation may help this version. */
    public static boolean isTranslationEligible(String versionId) {
        return isLwjgl2Era(versionId);
    }

    /**
     * Replace stock LaunchWrapper 1.12 with a Java 9+ compatible build that falls back to
     * {@code java.class.path} when the system loader is not a {@link java.net.URLClassLoader}.
     * Required for OptiFine 1.13+ on Java 9+.
     */
    public static void ensureModernLaunchWrapper(LaunchProfile profile, Path workDir,
                                                 DownloadManager downloads,
                                                 Set<String> seenClasspath) throws IOException {
        Path libRoot = workDir.resolve("libraries").resolve("retrowrapper").resolve(VERSION);
        Files.createDirectories(libRoot);
        Path launchwrapperJar = ensureLocal(libRoot.resolve("launchwrapper-1.13-java-9-and-above.jar"),
                "launchwrapper-1.13-java-9-and-above.jar", LAUNCHWRAPPER_URL, downloads);
        removeStockLaunchWrapper(profile, seenClasspath);
        addClasspath(profile, seenClasspath, launchwrapperJar);
        ensureLog4jForLaunchWrapper(profile, workDir, downloads, seenClasspath, libRoot);
        System.err.println("[PMCL 兼容层] 已替换为 Java 9+ LaunchWrapper: "
                + launchwrapperJar.getFileName());
    }

    /**
     * LaunchWrapper's {@code LogWrapper} references {@code org.apache.logging.log4j.Level}.
     * Classic/alpha/beta classpaths do not include log4j — inject it when missing.
     */
    public static void ensureLog4jForLaunchWrapper(LaunchProfile profile, Path workDir,
                                                   DownloadManager downloads,
                                                   Set<String> seenClasspath,
                                                   Path libRoot) throws IOException {
        if (classpathHas(seenClasspath, profile, "log4j-api")
                && classpathHas(seenClasspath, profile, "log4j-core")) {
            return;
        }
        Path api = resolveOrFetchLog4j(workDir, libRoot, "log4j-api", LOG4J_API_URL, downloads);
        Path core = resolveOrFetchLog4j(workDir, libRoot, "log4j-core", LOG4J_CORE_URL, downloads);
        addClasspath(profile, seenClasspath, api);
        addClasspath(profile, seenClasspath, core);
        System.err.println("[PMCL 兼容层] 已补入 LaunchWrapper 所需 log4j "
                + LOG4J_VER);
    }

    private static boolean classpathHas(Set<String> seen, LaunchProfile profile, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        if (seen != null) {
            for (String s : seen) {
                if (s != null && s.toLowerCase(Locale.ROOT).contains(n)) return true;
            }
        }
        for (String s : profile.getClasspathMutable()) {
            if (s != null && s.toLowerCase(Locale.ROOT).contains(n)) return true;
        }
        return false;
    }

    private static Path resolveOrFetchLog4j(Path workDir, Path libRoot, String artifact,
                                            String url, DownloadManager downloads)
            throws IOException {
        // Prefer an already-downloaded copy under libraries/ (e.g. from MC 1.12+)
        Path libraries = workDir.resolve("libraries").resolve("org/apache/logging/log4j")
                .resolve(artifact);
        if (Files.isDirectory(libraries)) {
            try (var walk = Files.walk(libraries, 3)) {
                Path found = walk.filter(p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return Files.isRegularFile(p) && name.startsWith(artifact.toLowerCase(Locale.ROOT))
                            && name.endsWith(".jar") && !name.contains("sources")
                            && !name.contains("javadoc");
                }).findFirst().orElse(null);
                if (found != null && Files.size(found) > 100) return found;
            }
        }
        String fileName = artifact + "-" + LOG4J_VER + ".jar";
        return ensureLocal(libRoot.resolve(fileName), fileName, url, downloads);
    }

    /** True when the version likely needs LWJGL2 / LaunchWrapper-era fixes. */
    public static boolean isLegacyVersion(int requiredJavaMajor, String mainClass) {
        if (requiredJavaMajor > 0 && requiredJavaMajor < 11) return true;
        if (mainClass != null && mainClass.toLowerCase(Locale.ROOT).contains("launchwrapper")) return true;
        return false;
    }

    /**
     * Whether translation should be applied for this launch.
     *
     * @param mode preference: {@code OFF} / {@code ON} / {@code AUTO}
     */
    public static boolean shouldApply(String mode, int requiredJavaMajor, int actualJavaMajor,
                                      String mainClass, String javaArch, String versionId) {
        if (actualJavaMajor < 9) return false;
        if (!isTranslationEligible(versionId)) {
            if (mode != null && "ON".equalsIgnoreCase(mode.trim())) {
                System.err.println("[PMCL 转译] 跳过 RetroWrapper：" + versionId
                        + " 为 1.13+ / LWJGL3，仅使用 PmclBootstrap（如有 LaunchWrapper）");
            }
            return false;
        }
        if (!isLegacyVersion(requiredJavaMajor, mainClass) && !needsRetroTweaker(versionId)) {
            return false;
        }
        String m = mode == null ? "AUTO" : mode.trim().toUpperCase(Locale.ROOT);
        if ("OFF".equals(m)) return false;
        if ("ON".equals(m)) return true;
        // AUTO: modern Java on legacy version, or Apple Silicon arm64 Java
        if (actualJavaMajor >= 17) return true;
        return isAppleSiliconArm64Java(javaArch);
    }

    /** @deprecated use {@link #shouldApply(String, int, int, String, String, String)} */
    @Deprecated
    public static boolean shouldApply(String mode, int requiredJavaMajor, int actualJavaMajor,
                                      String mainClass, String javaArch) {
        return shouldApply(mode, requiredJavaMajor, actualJavaMajor, mainClass, javaArch, null);
    }

    public static boolean isAppleSiliconArm64Java(String javaArch) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return false;
        if (!JavaRuntimeFinder.isAppleSiliconMac()) return false;
        String a = javaArch != null ? javaArch.toLowerCase(Locale.ROOT) : "";
        return a.contains("aarch64") || a.contains("arm64");
    }

    /**
     * Extract bundled RetroWrapper + deps into {@code workDir/libraries/retrowrapper/…}
     * and inject classpath / tweaker / M1 natives into the profile.
     */
    public static void apply(LaunchProfile profile, Path workDir, Path nativesDir,
                             DownloadManager downloads, Set<String> seenClasspath,
                             int javaMajor, String javaArch) throws IOException {
        apply(profile, workDir, nativesDir, downloads, seenClasspath, javaMajor, javaArch, null);
    }

    public static void apply(LaunchProfile profile, Path workDir, Path nativesDir,
                             DownloadManager downloads, Set<String> seenClasspath,
                             int javaMajor, String javaArch, String versionId) throws IOException {
        Path libRoot = workDir.resolve("libraries").resolve("retrowrapper").resolve(VERSION);
        Files.createDirectories(libRoot);

        boolean useRetroTweaker = needsRetroTweaker(versionId);
        Path launchwrapperJar = ensureLocal(libRoot.resolve("launchwrapper-1.13-java-9-and-above.jar"),
                "launchwrapper-1.13-java-9-and-above.jar", LAUNCHWRAPPER_URL, downloads);

        List<Path> extra = new ArrayList<>();
        if (useRetroTweaker) {
            Path retroJar = ensureLocal(libRoot.resolve("RetroWrapper-" + VERSION + ".jar"),
                    "RetroWrapper-1.7.8.jar", RETRO_WRAPPER_URL, downloads);
            extra.add(retroJar);
            for (int i = 0; i < MAVEN_JARS.length; i++) {
                extra.add(ensureLocal(libRoot.resolve(MAVEN_JARS[i]), MAVEN_JARS[i], MAVEN_URLS[i], downloads));
            }
        }
        extra.add(launchwrapperJar);

        // Drop stock Mojang LaunchWrapper only (keep optifine/launchwrapper-of if present;
        // our Java 9+ LaunchWrapper is still added and takes precedence when listed later).
        removeStockLaunchWrapper(profile, seenClasspath);

        for (Path p : extra) {
            addClasspath(profile, seenClasspath, p);
        }

        ensureLog4jForLaunchWrapper(profile, workDir, downloads, seenClasspath, libRoot);

        // OptiFine jars under libraries/ — only for OptiFine versions (do not pollute alpha/beta)
        ensureLocalTweakerJars(profile, workDir, seenClasspath, versionId);

        // Applet-era / RetroTweaker needs LaunchWrapper as entry (PmclBootstrap may wrap later)
        if (useRetroTweaker) {
            String main = profile.getMainClass();
            if (main == null || main.isBlank()
                    || (!main.contains("launchwrapper") && !main.contains("PmclBootstrap"))) {
                profile.setMainClass("net.minecraft.launchwrapper.Launch");
            }
            ensureTweaker(profile, "com.zero.retrowrapper.RetroTweaker");
            // 勿默认开启 -Dretrowrapper.hack：会弹出传送调试窗（Finding player…）
        }

        // Apple Silicon 色补丁策略（RetroWrapper M1ColorTweakInjector）：
        // - Applet 时代：ForceEnable 常把画面改成纯黑 → ForceDisable
        // - 1.6–1.12 窗口色偏：EnableWindowedInverted + experimental
        if (isAppleSiliconArm64Java(javaArch) && isLwjgl2Era(versionId)) {
            if (useRetroTweaker) {
                profile.addJvmArg("-Dretrowrapper.forceM1PatchToValue=ForceDisable");
            } else {
                profile.addJvmArg("-Dretrowrapper.enableExperimentalPatches=true");
                profile.addJvmArg("-Dretrowrapper.forceM1PatchToValue=EnableWindowedInverted");
            }
            applyAppleSiliconNatives(nativesDir, downloads, libRoot);
            replaceLwjglJarsForFranken(profile, seenClasspath, libRoot, downloads);
        }

        if (useRetroTweaker) {
            System.err.println("[PMCL 转译] RetroWrapper " + VERSION
                    + " 已注入（RetroTweaker，Java " + javaMajor + ", arch=" + javaArch
                    + ", version=" + versionId + "）");
        } else {
            System.err.println("[PMCL 转译] LWJGL2 兼容已注入（无 RetroTweaker，Java "
                    + javaMajor + ", arch=" + javaArch + ", version=" + versionId + "）");
        }
    }

    /**
     * Ensure OptiFine jars under {@code libraries/optifine/} are on the classpath.
     * OptiFine version JSON often only declares {@code name} without url/downloads.
     * Only runs for OptiFine version ids / tweak classes — never for classic/alpha/beta.
     */
    private static void ensureLocalTweakerJars(LaunchProfile profile, Path workDir,
                                              Set<String> seen, String versionId) {
        String vid = versionId != null ? versionId.toLowerCase(Locale.ROOT) : "";
        boolean wantsOptifine = vid.contains("optifine");
        if (!wantsOptifine) {
            for (String a : profile.getGameArgsView()) {
                if (a != null && a.toLowerCase(Locale.ROOT).contains("optifine")) {
                    wantsOptifine = true;
                    break;
                }
            }
        }
        if (!wantsOptifine) return;

        Path optifineRoot = workDir.resolve("libraries").resolve("optifine");
        if (!Files.isDirectory(optifineRoot)) return;
        try (var walk = Files.walk(optifineRoot, 6)) {
            walk.filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return Files.isRegularFile(p) && name.endsWith(".jar")
                                && !name.contains("launchwrapper");
                    })
                    .forEach(p -> {
                        addClasspath(profile, seen, p);
                        System.err.println("[PMCL 转译] 已补入本地库: " + p.getFileName());
                    });
        } catch (IOException e) {
            System.err.println("[PMCL 转译] 扫描 optifine 库失败: " + e.getMessage());
        }
    }

    /** Remove only Mojang/net.minecraft LaunchWrapper jars, not OptiFine's launchwrapper-of. */
    private static void removeStockLaunchWrapper(LaunchProfile profile, Set<String> seen) {
        Iterator<String> it = profile.getClasspathMutable().iterator();
        while (it.hasNext()) {
            String p = it.next();
            if (p == null) continue;
            String lower = p.toLowerCase(Locale.ROOT).replace('\\', '/');
            boolean stock = lower.contains("/net/minecraft/launchwrapper/")
                    || lower.matches(".*[/\\\\]launchwrapper-1[._].*\\.jar$");
            boolean optifineOf = lower.contains("launchwrapper-of");
            if (stock && !optifineOf) {
                it.remove();
                seen.remove(p);
            }
        }
    }

    private static void applyAppleSiliconNatives(Path nativesDir, DownloadManager downloads, Path libRoot)
            throws IOException {
        if (nativesDir == null) return;
        Files.createDirectories(nativesDir);
        Path franken = ensureLocal(libRoot.resolve("lwjgl-platform-franken-osx.jar"),
                "lwjgl-platform-franken-osx.jar", FRANKEN_LWJGL_URL, downloads);
        Path jinput = ensureLocal(libRoot.resolve("jinput-platform-m1-osx.jar"),
                "jinput-platform-m1-osx.jar", JINPUT_M1_URL, downloads);
        extractZipOverwrite(franken, nativesDir);
        extractZipOverwrite(jinput, nativesDir);
        // Franken ships liblwjgl.dylib; stock natives leave x86-only .jnilib — unify both names.
        Path lwjglDylib = nativesDir.resolve("liblwjgl.dylib");
        Path lwjglJnilib = nativesDir.resolve("liblwjgl.jnilib");
        if (Files.isRegularFile(lwjglDylib)) {
            Files.copy(lwjglDylib, lwjglJnilib, StandardCopyOption.REPLACE_EXISTING);
        }
        // M1 jinput ships .jnilib only; leftover stock libjinput-osx.dylib is x86 and loads first.
        Path jinputJnilib = nativesDir.resolve("libjinput-osx.jnilib");
        Path jinputDylib = nativesDir.resolve("libjinput-osx.dylib");
        if (Files.isRegularFile(jinputJnilib)) {
            Files.copy(jinputJnilib, jinputDylib, StandardCopyOption.REPLACE_EXISTING);
        }
        System.err.println("[PMCL 转译] 已覆盖 Apple Silicon 通用 LWJGL/jinput natives → " + nativesDir);
    }

    /**
     * Alpha/beta often ship LWJGL 2.9.1 jars; Franken natives are 2.9.4.
     * Mismatch yields RetroWrapper's macOS warning and can leave a black Display.
     */
    private static void replaceLwjglJarsForFranken(LaunchProfile profile, Set<String> seen,
                                                   Path libRoot, DownloadManager downloads)
            throws IOException {
        Path lwjgl = ensureLocal(libRoot.resolve("lwjgl-" + LWJGL_294 + ".jar"),
                "lwjgl-" + LWJGL_294 + ".jar", LWJGL_294_URL, downloads);
        Path util = ensureLocal(libRoot.resolve("lwjgl_util-" + LWJGL_294 + ".jar"),
                "lwjgl_util-" + LWJGL_294 + ".jar", LWJGL_UTIL_294_URL, downloads);
        List<String> cp = profile.getClasspathMutable();
        Iterator<String> it = cp.iterator();
        while (it.hasNext()) {
            String p = it.next();
            if (p == null) continue;
            String lower = p.toLowerCase(Locale.ROOT).replace('\\', '/');
            String name = lower.substring(lower.lastIndexOf('/') + 1);
            boolean utilJar = name.contains("lwjgl_util") || name.contains("lwjgl-util")
                    || lower.contains("/lwjgl_util/");
            boolean platform = name.contains("lwjgl-platform") || lower.contains("lwjgl-platform");
            boolean lwjglJar = !utilJar && !platform && name.endsWith(".jar")
                    && (name.startsWith("lwjgl-2.") || lower.contains("/lwjgl/lwjgl/lwjgl/"));
            if (lwjglJar || utilJar) {
                it.remove();
                seen.remove(p);
            }
        }
        // 插到 classpath 前部（紧随 client jar），避免旧 LWJGL 被其它路径抢先加载
        String lwjglPath = lwjgl.toAbsolutePath().toString();
        String utilPath = util.toAbsolutePath().toString();
        int insertAt = Math.min(1, cp.size());
        cp.add(insertAt, utilPath);
        cp.add(insertAt, lwjglPath);
        seen.add(lwjglPath);
        seen.add(utilPath);
        System.err.println("[PMCL 转译] 已将 classpath LWJGL 升级为 " + LWJGL_294
                + " (" + lwjgl.getFileName() + ")");
    }

    /**
     * Prefer classpath-bundled resource; keep an already-extracted file;
     * only then fall back to network download.
     */
    private static Path ensureLocal(Path target, String resourceFileName, String fallbackUrl,
                                    DownloadManager downloads) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) > 100) {
            return target;
        }
        Files.createDirectories(target.getParent());

        String resourcePath = RESOURCE_PREFIX + resourceFileName;
        try (InputStream in = openBundled(resourcePath)) {
            if (in != null) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                if (Files.isRegularFile(target) && Files.size(target) > 100) {
                    return target;
                }
            }
        }

        System.err.println("[PMCL 转译] 内置资源缺失 " + resourcePath + "，尝试联网下载…");
        if (downloads != null) {
            downloads.downloadTo(fallbackUrl, target);
        } else {
            try (InputStream in = java.net.URI.create(fallbackUrl).toURL().openStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!Files.isRegularFile(target) || Files.size(target) < 100) {
            throw new IOException("RetroWrapper dependency unavailable (bundled+download failed): "
                    + resourceFileName);
        }
        return target;
    }

    private static InputStream openBundled(String resourcePath) {
        ClassLoader cl = RetroWrapperSupport.class.getClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(resourcePath) : null;
        if (in != null) return in;
        in = RetroWrapperSupport.class.getResourceAsStream("/" + resourcePath);
        if (in != null) return in;
        return ClassLoader.getSystemResourceAsStream(resourcePath);
    }

    private static void extractZipOverwrite(Path zip, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("META-INF/") || name.contains("..")) continue;
                Path out = dest.resolve(Path.of(name).getFileName().toString());
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void ensureTweaker(LaunchProfile profile, String tweaker) {
        List<String> args = profile.getGameArgsView();
        for (int i = 0; i < args.size() - 1; i++) {
            if ("--tweakClass".equals(args.get(i)) && tweaker.equals(args.get(i + 1))) {
                return;
            }
        }
        // LaunchWrapper accepts multiple --tweakClass; prepend RetroTweaker
        profile.prependGameArg(tweaker);
        profile.prependGameArg("--tweakClass");
    }

    private static void addClasspath(LaunchProfile profile, Set<String> seen, Path path) {
        if (path == null) return;
        String s = path.toAbsolutePath().normalize().toString();
        if (seen.add(s)) {
            profile.addClasspath(path);
        }
    }
}
