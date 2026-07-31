package com.pmcl.core.theme;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面壁纸取色器：获取系统壁纸并提取主色调（莫奈取色）。
 * <p>
 * macOS：优先通过 AppleScript / sips 读取壁纸文件取色，<b>绝不</b>调用
 * {@link Robot#createScreenCapture}（会反复弹出「录屏」权限，且未签名包每次重建 TCC 身份不同）。
 * 其他平台：先尝试读取壁纸文件，失败再退回边缘截屏采样。
 * <p>
 * 提取算法：将图片缩放到 64x64 采样，把每个像素映射到量化色相桶（12 个色相 × 4 个明度档），
 * 统计出现频率最高的桶作为种子色，避开过于暗/灰/亮的像素。
 */
public final class WallpaperColorProvider {

    private static volatile int cachedSeedColor = -1;
    private static volatile long cacheTime = 0;
    private static final long CACHE_TTL_MS = 300_000; // 5 分钟缓存

    /** 本进程内若截屏被拒 / macOS 禁止截屏，则不再尝试 Robot */
    private static final AtomicBoolean SCREEN_CAPTURE_DISABLED = new AtomicBoolean(false);

    private static final Path LOG_FILE = Paths.get(System.getProperty("user.home"), ".pmcl", "monet-diag.txt");

    private static void diag(String msg) {
        try {
            String line = System.currentTimeMillis() + " " + msg + "\n";
            Files.createDirectories(LOG_FILE.getParent());
            Files.writeString(LOG_FILE, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }

    /**
     * 获取当前桌面壁纸的种子色（RGB int，0xRRGGBB）。
     * @return 种子色，失败时返回 -1。
     */
    public static int fetchSeedColor() {
        long now = System.currentTimeMillis();
        if (cachedSeedColor != -1 && (now - cacheTime) < CACHE_TTL_MS) {
            diag("fetchSeedColor: cache hit #" + Integer.toHexString(cachedSeedColor));
            return cachedSeedColor;
        }
        return fetchSeedColorForce();
    }

    /**
     * 强制重新采样壁纸种子色，绕过缓存。
     */
    public static int fetchSeedColorForce() {
        diag("fetchSeedColorForce: start");
        try {
            int color = fetchSeedColorInternal();
            diag("fetchSeedColorForce: result=" + color + " (#" + (color == -1 ? "FAIL" : Integer.toHexString(color)) + ")");
            if (color != -1) {
                cachedSeedColor = color;
                cacheTime = System.currentTimeMillis();
            }
            return color;
        } catch (Throwable t) {
            diag("fetchSeedColorForce: EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            return -1;
        }
    }

    private static int fetchSeedColorInternal() throws Exception {
        BufferedImage wallpaper = tryLoadDesktopWallpaper();
        if (wallpaper != null) {
            diag("fetchSeedColorInternal: using wallpaper file "
                    + wallpaper.getWidth() + "x" + wallpaper.getHeight());
            return extractDominantColor(wallpaper);
        }

        // macOS：禁止 Robot 截屏，避免录屏弹窗循环
        if (isMac()) {
            diag("fetchSeedColorInternal: macOS wallpaper file unavailable, skip Robot");
            SCREEN_CAPTURE_DISABLED.set(true);
            return -1;
        }

        if (SCREEN_CAPTURE_DISABLED.get()) {
            diag("fetchSeedColorInternal: screen capture disabled for this process");
            return -1;
        }

        return fetchViaRobotEdges();
    }

    private static int fetchViaRobotEdges() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        diag("screenSize=" + screenSize.width + "x" + screenSize.height);
        Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException e) {
            diag("Robot create failed: " + e.getMessage());
            SCREEN_CAPTURE_DISABLED.set(true);
            return -1;
        }

        int w = screenSize.width;
        int h = screenSize.height;
        int edgeW = w / 5;
        int edgeH = h / 5;
        int bottomInset = 48;

        Rectangle[] regions = {
            new Rectangle(0, 0, w, edgeH),
            new Rectangle(0, h - edgeH - bottomInset, w, edgeH),
            new Rectangle(0, edgeH, edgeW, h - 2 * edgeH - bottomInset),
            new Rectangle(w - edgeW, edgeH, edgeW, h - 2 * edgeH - bottomInset)
        };

        int totalPixels = 0;
        Map<Integer, int[]> buckets = new HashMap<>();
        try {
            for (Rectangle region : regions) {
                if (region.width <= 0 || region.height <= 0) continue;
                BufferedImage part = robot.createScreenCapture(region);
                collectColorBuckets(part, buckets);
                totalPixels += region.width * region.height;
            }
        } catch (SecurityException se) {
            diag("Robot capture denied: " + se.getMessage());
            SCREEN_CAPTURE_DISABLED.set(true);
            return -1;
        } catch (Throwable t) {
            diag("Robot capture failed: " + t.getClass().getName() + ": " + t.getMessage());
            SCREEN_CAPTURE_DISABLED.set(true);
            return -1;
        }
        diag("fetchViaRobotEdges: done, totalPixels=" + totalPixels + " buckets=" + buckets.size());

        if (buckets.isEmpty()) return -1;

        int[][] topBuckets = buckets.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue()[3], a.getValue()[3]))
            .limit(3)
            .map(Map.Entry::getValue)
            .toArray(int[][]::new);

        long totalWeight = 0;
        long weightedR = 0, weightedG = 0, weightedB = 0;
        for (int[] agg : topBuckets) {
            int count = agg[3];
            weightedR += (long) (agg[0] / count) * count;
            weightedG += (long) (agg[1] / count) * count;
            weightedB += (long) (agg[2] / count) * count;
            totalWeight += count;
        }
        if (totalWeight == 0) return -1;
        int r = (int) (weightedR / totalWeight);
        int g = (int) (weightedG / totalWeight);
        int b = (int) (weightedB / totalWeight);
        return (r << 16) | (g << 8) | b;
    }

    private static BufferedImage tryLoadDesktopWallpaper() {
        try {
            Path path = resolveDesktopWallpaperPath();
            if (path == null) {
                diag("wallpaper path: null");
                return null;
            }
            diag("wallpaper path: " + path);
            if (!Files.isRegularFile(path)) {
                diag("wallpaper path not a file");
                return null;
            }
            Path readable = path;
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".heic") || name.endsWith(".heif") || name.endsWith(".tif")
                    || name.endsWith(".tiff")) {
                Path converted = convertWallpaperViaSips(path);
                if (converted == null) return null;
                readable = converted;
            }
            BufferedImage img = ImageIO.read(readable.toFile());
            if (img == null) {
                diag("ImageIO.read returned null for " + readable);
            }
            return img;
        } catch (Throwable t) {
            diag("tryLoadDesktopWallpaper failed: " + t.getClass().getName() + ": " + t.getMessage());
            return null;
        }
    }

    private static Path resolveDesktopWallpaperPath() {
        if (isMac()) {
            Path fromScript = macWallpaperPathViaOsascript();
            if (fromScript != null) return fromScript;
            return macWallpaperPathFromDefaults();
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return windowsWallpaperPath();
        }
        return null;
    }

    private static Path macWallpaperPathViaOsascript() {
        String[] scripts = {
            "tell application \"System Events\" to get picture of current desktop",
            "tell application \"System Events\" to tell every desktop to get picture as text"
        };
        for (String script : scripts) {
            String out = runCapture("osascript", "-e", script);
            if (out == null || out.isBlank()) continue;
            for (String line : out.split("\n")) {
                String p = sanitizePath(line.trim());
                if (p.isEmpty()) continue;
                Path path = Paths.get(p);
                if (Files.isRegularFile(path)) return path;
            }
        }
        return null;
    }

    private static Path macWallpaperPathFromDefaults() {
        String out = runCapture("defaults", "read", "com.apple.desktop", "Background");
        if (out == null) return null;
        int idx = out.indexOf("ImageFilePath");
        if (idx < 0) idx = out.indexOf("LastName");
        if (idx < 0) return null;
        int q1 = out.indexOf('"', idx);
        int q2 = q1 >= 0 ? out.indexOf('"', q1 + 1) : -1;
        if (q1 < 0 || q2 < 0) return null;
        Path path = Paths.get(out.substring(q1 + 1, q2));
        return Files.isRegularFile(path) ? path : null;
    }

    private static Path windowsWallpaperPath() {
        String out = runCapture("reg", "query",
                "HKCU\\Control Panel\\Desktop", "/v", "WallPaper");
        if (out == null) return null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!t.contains("WallPaper")) continue;
            int pos = t.toUpperCase(Locale.ROOT).lastIndexOf("REG_SZ");
            if (pos < 0) continue;
            String p = sanitizePath(t.substring(pos + 6).trim());
            if (p.isEmpty()) continue;
            Path path = Paths.get(p);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    private static Path convertWallpaperViaSips(Path src) {
        try {
            Path tmp = Paths.get(System.getProperty("java.io.tmpdir"), "pmcl-wallpaper-sample.jpg");
            String out = runCapture("sips", "-s", "format", "jpeg",
                    src.toAbsolutePath().toString(), "--out", tmp.toAbsolutePath().toString());
            diag("sips convert: " + (out == null ? "null" : out.trim()));
            if (Files.isRegularFile(tmp) && Files.size(tmp) > 64) return tmp;
        } catch (Throwable t) {
            diag("sips convert failed: " + t.getMessage());
        }
        return null;
    }

    private static String sanitizePath(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        if (p.startsWith("file://")) p = p.substring(7);
        if ((p.startsWith("\"") && p.endsWith("\"")) || (p.startsWith("'") && p.endsWith("'"))) {
            p = p.substring(1, p.length() - 1);
        }
        return p;
    }

    private static String runCapture(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            boolean finished = p.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                diag("cmd timeout: " + String.join(" ", cmd));
                return null;
            }
            if (p.exitValue() != 0) {
                diag("cmd exit " + p.exitValue() + ": " + String.join(" ", cmd) + " -> " + sb);
                return null;
            }
            return sb.toString();
        } catch (Throwable t) {
            diag("cmd failed: " + String.join(" ", cmd) + " -> " + t.getMessage());
            return null;
        }
    }

    private static void collectColorBuckets(BufferedImage img, Map<Integer, int[]> buckets) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                float[] hsl = rgbToHsl(r, g, b);
                if (hsl[2] < 0.1f || hsl[2] > 0.95f) continue;
                if (hsl[1] < 0.08f) continue;

                int hueBucket = (int) (hsl[0] / 30f) % 12;
                int litBucket = (int) (hsl[2] * 4);
                int key = hueBucket * 4 + litBucket;

                int[] agg = buckets.computeIfAbsent(key, k -> new int[4]);
                agg[0] += r; agg[1] += g; agg[2] += b; agg[3]++;
            }
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static int extractDominantColor(BufferedImage img) {
        int sampleSize = 64;
        BufferedImage scaled = scaleDown(img, sampleSize, sampleSize);
        int w = scaled.getWidth();
        int h = scaled.getHeight();
        diag("extractDominantColor: scaled=" + w + "x" + h);

        Map<Integer, int[]> buckets = new HashMap<>();
        int skipped = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                float[] hsl = rgbToHsl(r, g, b);
                float hue = hsl[0];
                float sat = hsl[1];
                float lit = hsl[2];

                if (lit < 0.15f || lit > 0.9f) { skipped++; continue; }
                if (sat < 0.12f) { skipped++; continue; }

                int hueBucket = (int) (hue / 30f) % 12;
                int litBucket = (int) (lit * 4);
                int key = hueBucket * 4 + litBucket;

                int[] agg = buckets.computeIfAbsent(key, k -> new int[4]);
                agg[0] += r; agg[1] += g; agg[2] += b; agg[3]++;
            }
        }
        diag("extractDominantColor: buckets=" + buckets.size() + " skipped=" + skipped + "/" + (w * h));

        if (buckets.isEmpty()) {
            if (w > 0 && h > 0) {
                int center = scaled.getRGB(w / 2, h / 2) & 0xFFFFFF;
                diag("extractDominantColor: all skipped, center=#" + Integer.toHexString(center));
                return center;
            }
            return 0;
        }

        int bestKey = -1;
        int bestCount = 0;
        for (Map.Entry<Integer, int[]> e : buckets.entrySet()) {
            if (e.getValue()[3] > bestCount) {
                bestCount = e.getValue()[3];
                bestKey = e.getKey();
            }
        }

        int[] agg = buckets.get(bestKey);
        int r = agg[0] / agg[3];
        int g = agg[1] / agg[3];
        int b = agg[2] / agg[3];
        return (r << 16) | (g << 8) | b;
    }

    private static BufferedImage scaleDown(BufferedImage src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= targetW && srcH <= targetH) return src;
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < targetH; y++) {
            int sy = y * srcH / targetH;
            for (int x = 0; x < targetW; x++) {
                int sx = x * srcW / targetW;
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
    }

    private static float[] rgbToHsl(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h, s, l = (max + min) / 2f;
        if (delta == 0) {
            h = 0; s = 0;
        } else {
            s = delta / (1 - Math.abs(2 * l - 1));
            if (max == rf) {
                h = 60f * (((gf - bf) / delta) % 6);
            } else if (max == gf) {
                h = 60f * ((bf - rf) / delta + 2);
            } else {
                h = 60f * ((rf - gf) / delta + 4);
            }
            if (h < 0) h += 360;
        }
        return new float[]{h, s, l};
    }

    public static int hslToRgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = l - c / 2;
        float r, g, b;
        if (h < 60)       { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }
        int ri = Math.round((r + m) * 255);
        int gi = Math.round((g + m) * 255);
        int bi = Math.round((b + m) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    public static int[] generatePalette(int seedRgb, boolean dark) {
        float[] hsl = rgbToHsl(
                (seedRgb >> 16) & 0xFF,
                (seedRgb >> 8) & 0xFF,
                seedRgb & 0xFF
        );
        float hue = hsl[0];
        float sat = hsl[1];
        if (sat < 0.3f) sat = 0.5f;
        sat = Math.min(sat, 0.7f);

        int primary, secondary, tertiary, background, surface;
        if (dark) {
            primary    = hslToRgb(hue, sat, 0.70f);
            secondary  = hslToRgb(hue, sat * 0.85f, 0.60f);
            tertiary   = hslToRgb((hue + 60) % 360, sat * 0.9f, 0.65f);
            background = hslToRgb(hue, sat * 0.25f, 0.10f);
            surface    = hslToRgb(hue, sat * 0.2f, 0.16f);
        } else {
            primary    = hslToRgb(hue, sat, 0.42f);
            secondary  = hslToRgb(hue, sat * 0.85f, 0.52f);
            tertiary   = hslToRgb((hue + 60) % 360, sat * 0.9f, 0.48f);
            background = hslToRgb(hue, sat * 0.2f, 0.95f);
            surface    = hslToRgb(hue, sat * 0.15f, 0.98f);
        }
        return new int[]{primary, secondary, tertiary, background, surface};
    }

    public static FullPalette generateFullPalette(int seedRgb, boolean dark) {
        float[] hsl = rgbToHsl(
                (seedRgb >> 16) & 0xFF,
                (seedRgb >> 8) & 0xFF,
                seedRgb & 0xFF
        );
        float hue = hsl[0];
        float sat = hsl[1];
        if (sat < 0.3f) sat = 0.5f;
        sat = Math.min(sat, 0.7f);

        if (dark) {
            return new FullPalette(
                hslToRgb(hue, sat, 0.70f),
                hslToRgb(hue, sat * 0.3f, 0.10f),
                hslToRgb(hue, sat * 0.5f, 0.30f),
                hslToRgb(hue, sat * 0.3f, 0.90f),
                hslToRgb(hue, sat * 0.85f, 0.60f),
                hslToRgb(hue, sat * 0.3f, 0.10f),
                hslToRgb((hue + 60) % 360, sat * 0.9f, 0.65f),
                hslToRgb(hue, sat * 0.25f, 0.10f),
                hslToRgb(hue, sat * 0.1f, 0.90f),
                hslToRgb(hue, sat * 0.2f, 0.16f),
                hslToRgb(hue, sat * 0.1f, 0.90f),
                hslToRgb(hue, sat * 0.15f, 0.22f),
                hslToRgb(hue, sat * 0.1f, 0.70f),
                hslToRgb(hue, sat * 0.1f, 0.55f),
                hslToRgb(0, 0.7f, 0.65f),
                hslToRgb(0, 0.3f, 0.10f)
            );
        } else {
            return new FullPalette(
                hslToRgb(hue, sat, 0.42f),
                0xFFFFFFFF,
                hslToRgb(hue, sat * 0.7f, 0.90f),
                hslToRgb(hue, sat * 0.5f, 0.20f),
                hslToRgb(hue, sat * 0.85f, 0.52f),
                0xFFFFFFFF,
                hslToRgb((hue + 60) % 360, sat * 0.9f, 0.48f),
                hslToRgb(hue, sat * 0.2f, 0.95f),
                hslToRgb(hue, sat * 0.3f, 0.10f),
                hslToRgb(hue, sat * 0.15f, 0.98f),
                hslToRgb(hue, sat * 0.3f, 0.10f),
                hslToRgb(hue, sat * 0.1f, 0.90f),
                hslToRgb(hue, sat * 0.2f, 0.30f),
                hslToRgb(hue, sat * 0.1f, 0.45f),
                hslToRgb(0, 0.7f, 0.45f),
                0xFFFFFFFF
            );
        }
    }

    public static final class FullPalette {
        public final int primary, onPrimary, primaryContainer, onPrimaryContainer;
        public final int secondary, onSecondary, tertiary;
        public final int background, onBackground, surface, onSurface;
        public final int surfaceVariant, onSurfaceVariant, outline;
        public final int error, onError;

        public FullPalette(int primary, int onPrimary, int primaryContainer, int onPrimaryContainer,
                           int secondary, int onSecondary, int tertiary,
                           int background, int onBackground, int surface, int onSurface,
                           int surfaceVariant, int onSurfaceVariant, int outline,
                           int error, int onError) {
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.primaryContainer = primaryContainer;
            this.onPrimaryContainer = onPrimaryContainer;
            this.secondary = secondary;
            this.onSecondary = onSecondary;
            this.tertiary = tertiary;
            this.background = background;
            this.onBackground = onBackground;
            this.surface = surface;
            this.onSurface = onSurface;
            this.surfaceVariant = surfaceVariant;
            this.onSurfaceVariant = onSurfaceVariant;
            this.outline = outline;
            this.error = error;
            this.onError = onError;
        }
    }

    public static void diagLog(String msg) { diag(msg); }

    public static void clearCache() {
        cachedSeedColor = -1;
        cacheTime = 0;
    }
}
