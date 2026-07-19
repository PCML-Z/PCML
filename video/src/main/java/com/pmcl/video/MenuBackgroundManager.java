package com.pmcl.video;

import com.pmcl.core.gamecontent.MenuBackgroundProvider;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * 主菜单背景视频处理器（video 模块实现）。
 * <p>
 * 用 JavaCV/FFmpeg 从视频中提取 6 帧，缩放裁剪为 1024x1024 正方形 PNG，
 * 打包成 Minecraft 资源包 zip，替换主菜单 panorama 全景图。
 * <p>
 * 缓存策略：按视频文件路径 + size + mtime 计算 SHA-256 作为缓存文件名，
 * 命中则直接复用，避免每次启动重新解码视频。
 * <p>
 * 6 张 panorama_0.png ~ panorama_5.png 对应 MC 立方体天空盒的 6 个面。
 * MC 的全景图本是环绕场景的 6 个面，但作为背景替换，把视频按时间均匀采样 6 帧，
 * 每帧裁剪为正方形，能让主菜单在旋转时显示不同画面，达到"动态"视觉效果。
 */
public final class MenuBackgroundManager implements MenuBackgroundProvider {

    private static final String PACK_FILE_NAME = "PMCL_MenuBg.zip";
    private static final int PANORAMA_SIZE = 1024;  // MC 1.13+ panorama 原始尺寸，再大会被 MC 自动缩小
    private static final int FRAME_COUNT = 6;
    /**
     * pack.mcmeta 结构版本：升级此值会让所有旧缓存 zip 失效，重新生成。
     * v2: 新增 supported_formats 范围字段，修复 MC 1.20.5+ 因 pack_format 不匹配拒绝加载的问题。
     */
    private static final int PACK_FORMAT_SCHEMA_VERSION = 2;

    @Override
    public String installTo(Path gameDir, Path videoPath, Path cacheDir, String mcVersion) {
        if (gameDir == null || videoPath == null) return null;
        if (!Files.isRegularFile(videoPath)) return null;

        try {
            Files.createDirectories(cacheDir);
            String cacheKey = buildCacheKey(videoPath);
            Path cachedZip = cacheDir.resolve("menubg_" + cacheKey + ".zip");

            // 缓存未命中：重新生成
            if (!Files.exists(cachedZip)) {
                if (!generatePack(videoPath, cachedZip, mcVersion)) {
                    return null;
                }
            }

            // 复制到 gameDir/resourcepacks/
            Path resourcePacksDir = gameDir.resolve("resourcepacks");
            Files.createDirectories(resourcePacksDir);
            Path target = resourcePacksDir.resolve(PACK_FILE_NAME);
            Files.copy(cachedZip, target, StandardCopyOption.REPLACE_EXISTING);
            return PACK_FILE_NAME;
        } catch (Throwable e) {
            System.err.println("[MenuBackground] 安装失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成资源包 zip 到 outputPath。
     * @return 成功返回 true
     */
    private boolean generatePack(Path videoPath, Path outputPath, String mcVersion) {
        List<BufferedImage> frames = extractFrames(videoPath, FRAME_COUNT);
        if (frames.isEmpty()) return false;

        int packFormat = resolvePackFormat(mcVersion);
        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(outputPath), StandardCharsets.UTF_8)) {
            // pack.mcmeta：用 supported_formats 范围覆盖 1.13-1.21+，避免 MC 1.20.5+ 因
            // pack_format 不匹配直接拒绝加载资源包。pack_format 字段仍填合理值作为 fallback
            // （旧版本 MC 不识别 supported_formats，只看 pack_format）。
            // MC 1.20.5+ 严格校验：pack_format 不匹配且无 supported_formats 时资源包不生效。
            String mcmeta = "{\"pack\":{\"pack_format\":" + packFormat
                    + ",\"supported_formats\":[4,99]"
                    + ",\"description\":\"PMCL Menu Background\"}}";
            zos.putNextEntry(new ZipEntry("pack.mcmeta"));
            zos.write(mcmeta.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 6 张 panorama 图
            // 路径 assets/minecraft/textures/gui/title/background/panorama_0.png ~ panorama_5.png
            // 1.13-1.21+ 路径一致，未变化
            for (int i = 0; i < FRAME_COUNT; i++) {
                BufferedImage src = frames.get(i % frames.size());
                BufferedImage square = cropToSquare(src, PANORAMA_SIZE);
                zos.putNextEntry(new ZipEntry(
                        "assets/minecraft/textures/gui/title/background/panorama_" + i + ".png"));
                ImageIO.write(square, "png", zos);
                zos.closeEntry();
            }
            return true;
        } catch (IOException e) {
            System.err.println("[MenuBackground] 生成资源包失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 用 FFmpeg 从视频中按时间均匀间隔提取 frameCount 帧。
     * @return 提取成功的帧列表（可能少于 frameCount，视频太短时）
     */
    private List<BufferedImage> extractFrames(Path videoPath, int frameCount) {
        List<BufferedImage> frames = new ArrayList<>();
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toString());
        Java2DFrameConverter converter = new Java2DFrameConverter();
        try {
            grabber.start();
            long totalFrames = grabber.getLengthInFrames();
            double durationSec = grabber.getLengthInTime() / 1_000_000.0;
            if (totalFrames <= 0 || durationSec <= 0) {
                // 时长未知，直接抓前 N 帧
                for (int i = 0; i < frameCount; i++) {
                    Frame f = grabber.grabImage();
                    if (f == null) break;
                    BufferedImage img = converter.convert(f);
                    if (img != null) frames.add(img);
                }
                return frames;
            }
            // 按时间均匀采样：跳过开头 5%（避免黑场），在 5%~95% 区间均匀取 N 帧
            for (int i = 0; i < frameCount; i++) {
                double targetSec = durationSec * (0.05 + 0.9 * i / (frameCount - 1));
                long targetMicro = (long) (targetSec * 1_000_000);
                grabber.setTimestamp(targetMicro);
                Frame f = null;
                // setTimestamp 后可能需要 grab 几次才能到目标帧
                for (int retry = 0; retry < 3; retry++) {
                    f = grabber.grabImage();
                    if (f != null) break;
                }
                if (f == null) continue;
                BufferedImage img = converter.convert(f);
                if (img != null) frames.add(img);
            }
        } catch (Throwable e) {
            System.err.println("[MenuBackground] 视频帧提取失败: " + e.getMessage());
        } finally {
            try { grabber.stop(); } catch (Throwable ignored) {}
            try { grabber.release(); } catch (Throwable ignored) {}
        }
        return frames;
    }

    /** 把图像裁剪并缩放为 size×size 正方形（中心裁剪 + 双线性缩放）。 */
    private BufferedImage cropToSquare(BufferedImage src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, size, size, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * 根据 MC 版本号推导资源包 pack_format。
     * 数据来源：https://minecraft.wiki/w/Pack_format（Java 版 resource pack 部分）
     * 未识别版本回退到 15（1.20.x），MC 会对不匹配的 format 显示警告但仍加载。
     */
    private int resolvePackFormat(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return 15;
        String v = mcVersion.toLowerCase(Locale.ROOT);
        // 解析主版本.次版本
        String[] parts = v.split("[.\\-]");
        int major = 0, minor = 0;
        try {
            if (parts.length >= 1) major = Integer.parseInt(parts[0]);
            if (parts.length >= 2) minor = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {}
        // MC Java 主版本 1
        if (major != 1) return 15;
        return switch (minor) {
            case 13, 14, 15 -> 4;       // 1.13-1.15
            case 16 -> 6;               // 1.16.x
            case 17 -> 8;               // 1.17.x
            case 18, 19 -> 9;           // 1.18-1.19.3（粗略，1.19.4+ 是 13）
            case 20 -> 15;              // 1.20.x
            case 21 -> 18;              // 1.21.x（粗略，1.21.2+ 是 26+，但 MC 会向下兼容）
            default -> 15;              // 未知版本回退
        };
    }

    /**
     * 缓存键：视频文件路径 + size + mtime + 生成器版本 的 SHA-256。
     * 视频文件被替换或修改时会重新生成；
     * PACK_FORMAT_SCHEMA_VERSION 升级时（如 pack.mcmeta 结构调整）也会让旧缓存失效。
     */
    private String buildCacheKey(Path videoPath) throws Exception {
        long size = Files.size(videoPath);
        long mtime = Files.getLastModifiedTime(videoPath).toMillis();
        String input = videoPath + "|" + size + "|" + mtime + "|v" + PACK_FORMAT_SCHEMA_VERSION;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).substring(0, 16);  // 16 字符够用
    }
}
