package com.pmcl.core.theme;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 桌面壁纸取色器：获取系统壁纸并提取主色调（莫奈取色）。
 * <p>
 * 使用 java.awt.Robot 直接截取屏幕，无需外部进程，避免 macOS 权限问题。
 * <p>
 * 提取算法：将图片缩放到 64x64 采样，把每个像素映射到量化色相桶（12 个色相 × 4 个明度档），
 * 统计出现频率最高的桶作为种子色，避开过于暗/灰/亮的像素。
 */
public final class WallpaperColorProvider {

    private static volatile int cachedSeedColor = -1;
    private static volatile long cacheTime = 0;
    private static final long CACHE_TTL_MS = 3000; // 3 秒缓存

    /** 诊断日志路径 */
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
        diag("fetchSeedColor: start");
        try {
            int color = fetchSeedColorInternal();
            diag("fetchSeedColor: result=" + color + " (#" + (color == -1 ? "FAIL" : Integer.toHexString(color)) + ")");
            if (color != -1) {
                cachedSeedColor = color;
                cacheTime = now;
            }
            return color;
        } catch (Throwable t) {
            diag("fetchSeedColor: EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            return -1;
        }
    }

    private static int fetchSeedColorInternal() throws Exception {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        diag("screenSize=" + screenSize.width + "x" + screenSize.height);
        Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException e) {
            diag("Robot create failed: " + e.getMessage());
            return -1;
        }

        // 只截取屏幕边缘区域（避开 PMCL 自己的窗口），壁纸在这些区域可见
        int w = screenSize.width;
        int h = screenSize.height;
        int edgeW = w / 5;  // 边缘宽度 = 屏幕宽度的 20%
        int edgeH = h / 5;  // 边缘高度 = 屏幕高度的 20%

        // 四个边缘区域 + 四个角
        Rectangle[] regions = {
            new Rectangle(0, 0, w, edgeH),                    // 顶部条
            new Rectangle(0, h - edgeH, w, edgeH),             // 底部条
            new Rectangle(0, edgeH, edgeW, h - 2 * edgeH),     // 左侧条
            new Rectangle(w - edgeW, edgeH, edgeW, h - 2 * edgeH) // 右侧条
        };

        // 合并边缘区域到一个图片
        int totalPixels = 0;
        Map<Integer, int[]> buckets = new HashMap<>();
        for (Rectangle region : regions) {
            BufferedImage part = robot.createScreenCapture(region);
            collectColorBuckets(part, buckets);
            totalPixels += region.width * region.height;
        }
        diag("fetchSeedColorInternal: edge capture done, totalPixels=" + totalPixels + " buckets=" + buckets.size());

        if (buckets.isEmpty()) return -1;

        // 找频率最高的桶
        int bestKey = -1, bestCount = 0;
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
        int result = (r << 16) | (g << 8) | b;
        diag("fetchSeedColorInternal: bestBucket=" + bestKey + " count=" + bestCount + " color=#" + Integer.toHexString(result));
        return result;
    }

    /** 收集图片像素到色桶 */
    private static void collectColorBuckets(BufferedImage img, Map<Integer, int[]> buckets) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y += 2) {  // 隔行采样加速
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

    /**
     * 从图片中提取主导色。
     * 算法：缩放到 64x64 → 量化每个像素到色相/明度桶 → 取频率最高且足够鲜艳的桶。
     */
    private static int extractDominantColor(BufferedImage img) {
        int sampleSize = 64;
        BufferedImage scaled = scaleDown(img, sampleSize, sampleSize);
        int w = scaled.getWidth();
        int h = scaled.getHeight();
        diag("extractDominantColor: scaled=" + w + "x" + h);

        // 量化桶：色相(12档) × 明度(4档) = 48 个桶
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

                // 跳过过暗/过亮/过灰的像素
                if (lit < 0.15f || lit > 0.9f) { skipped++; continue; }
                if (sat < 0.12f) { skipped++; continue; }

                int hueBucket = (int) (hue / 30f) % 12;
                int litBucket = (int) (lit * 4);
                int key = hueBucket * 4 + litBucket;

                int[] agg = buckets.computeIfAbsent(key, k -> new int[4]);
                agg[0] += r; agg[1] += g; agg[2] += b; agg[3]++;
            }
        }
        diag("extractDominantColor: buckets=" + buckets.size() + " skipped=" + skipped + "/" + (w*h));

        if (buckets.isEmpty()) {
            int center = scaled.getRGB(w / 2, h / 2) & 0xFFFFFF;
            diag("extractDominantColor: all skipped, center=#" + Integer.toHexString(center));
            return center;
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
        int result = (r << 16) | (g << 8) | b;
        diag("extractDominantColor: bestBucket=" + bestKey + " count=" + bestCount + " color=#" + Integer.toHexString(result));
        return result;
    }

    /** 简单的最近邻缩放 */
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

    /** RGB → HSL，返回 [hue(0-360), sat(0-1), lit(0-1)] */
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

    /** HSL → RGB int (0xRRGGBB) */
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

    /**
     * 根据种子色生成 Material3 风格的调色板。
     * @param seedRgb 种子色 (0xRRGGBB)
     * @param dark 是否为暗色模式
     * @return [primary, secondary, tertiary, background, surface] 的 RGB int 数组
     */
    public static int[] generatePalette(int seedRgb, boolean dark) {
        float[] hsl = rgbToHsl(
                (seedRgb >> 16) & 0xFF,
                (seedRgb >> 8) & 0xFF,
                seedRgb & 0xFF
        );
        float hue = hsl[0];
        // 饱和度太低时提升到 0.5，确保有明显的色调；太高时限制到 0.7
        float sat = hsl[1];
        if (sat < 0.3f) sat = 0.5f;
        sat = Math.min(sat, 0.7f);
        diag("generatePalette: seed=#" + Integer.toHexString(seedRgb) + " hsl=[" + hue + "," + hsl[1] + "," + hsl[2] + "] -> sat=" + sat + " dark=" + dark);

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
        diag("generatePalette: primary=#" + Integer.toHexString(primary) +
             " bg=#" + Integer.toHexString(background) +
             " surface=#" + Integer.toHexString(surface));
        return new int[]{primary, secondary, tertiary, background, surface};
    }

    /** 公共诊断日志方法 */
    public static void diagLog(String msg) { diag(msg); }

    /** 强制清除缓存（切换壁纸后刷新用） */
    public static void clearCache() {
        cachedSeedColor = -1;
        cacheTime = 0;
    }
}
