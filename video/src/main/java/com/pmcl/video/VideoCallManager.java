package com.pmcl.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频通话管理器：基于 JavaCV 的摄像头采集。
 * <p>
 * 职责：
 * <ul>
 *   <li>初始化/释放 JavaCV 摄像头采集器</li>
 *   <li>采集摄像头帧（BufferedImage）</li>
 *   <li>JPEG 压缩/解压辅助</li>
 * </ul>
 * <p>
 * ICE/信令由 {@link VideoCallSession} 协调，本类只负责媒体采集层。
 */
public final class VideoCallManager {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // ---------------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------------

    /** 初始化（全局只需一次） */
    public static synchronized void init() {
        if (initialized.compareAndSet(false, true)) {
            System.out.println("[VideoCall] JavaCV 视频模块已初始化");
        }
    }

    /** 释放（应用退出时调用） */
    public static synchronized void shutdown() {
        initialized.set(false);
        System.out.println("[VideoCall] JavaCV 视频模块已释放");
    }

    public static boolean isInitialized() {
        return initialized.get();
    }

    // ---------------------------------------------------------------------------
    // 摄像头采集
    // ---------------------------------------------------------------------------

    /**
     * 创建摄像头采集器。
     * macOS 用 avfoundation，Linux 用 video4linux2，Windows 用 dshow。
     */
    public static FFmpegFrameGrabber createCameraGrabber(int width, int height, int fps) {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        FFmpegFrameGrabber grabber;

        try {
            if (osName.contains("mac")) {
                // macOS: avfoundation, 格式为 "video:audio"
                // "0:none" = 视频设备0（默认摄像头），无音频
                // macOS 摄像头权限：Java 无法自动申请/检测授权，未授权时打开会失败，
                // 仅提示用户在「系统设置 > 隐私与安全性 > 摄像头」中授权运行 PMCL 的应用。
                System.err.println("[VideoCall] macOS 摄像头提示：若无法打开，请在「系统设置 > 隐私与安全性 > 摄像头」中授权运行 PMCL 的应用。");
                grabber = new FFmpegFrameGrabber("0:none");
                grabber.setFormat("avfoundation");
                grabber.setOption("framerate", String.valueOf(fps));
                grabber.setFrameRate(fps);
            } else if (osName.contains("win")) {
                // Windows: dshow 需要真实设备名；可用 -Dpmcl.camera.dshow=设备名 覆盖
                String device = System.getProperty("pmcl.camera.dshow", "").trim();
                if (device.isEmpty()) device = detectFirstDshowVideoDevice();
                if (device.isEmpty()) device = "Integrated Camera";
                System.err.println("[VideoCall] Windows dshow 设备: " + device);
                grabber = new FFmpegFrameGrabber("video=" + device);
                grabber.setFormat("dshow");
                grabber.setFrameRate(fps);
            } else {
                // Linux: video4linux2, /dev/video0
                grabber = new FFmpegFrameGrabber("/dev/video0");
                grabber.setFormat("v4l2");
                grabber.setFrameRate(fps);
            }

            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
        } catch (Throwable t) {
            throw new RuntimeException("摄像头打开失败：" + t.getMessage()
                    + "。请检查摄像头是否被占用、驱动是否正常、权限是否已授予。", t);
        }

        return grabber;
    }

    /**
     * 用 ffmpeg -list_devices 枚举第一个 DirectShow 视频设备名。
     * ffmpeg 不在 PATH 或枚举失败时返回空串。
     */
    static String detectFirstDshowVideoDevice() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (java.io.InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            // 典型行： [dshow @ ...] "Integrated Camera" (video)
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"\\s*\\(video\\)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(output);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (Exception e) {
            System.err.println("[VideoCall] dshow 设备枚举失败: " + e.getMessage());
        }
        return "";
    }

    /**
     * 创建屏幕采集器（SCREEN_SHARE）。
     * macOS: avfoundation Capture screen；Windows: gdigrab；Linux: x11grab。
     */
    public static FFmpegFrameGrabber createScreenGrabber(int width, int height, int fps) {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        try {
            FFmpegFrameGrabber grabber;
            if (osName.contains("mac")) {
                // avfoundation 屏幕设备通常为 "Capture screen 0"，索引因系统而异；
                // 也可用 -Dpmcl.screen.avfoundation=1:none 覆盖
                String input = System.getProperty("pmcl.screen.avfoundation", "1:none").trim();
                grabber = new FFmpegFrameGrabber(input);
                grabber.setFormat("avfoundation");
                grabber.setOption("framerate", String.valueOf(fps));
                grabber.setFrameRate(fps);
            } else if (osName.contains("win")) {
                grabber = new FFmpegFrameGrabber("desktop");
                grabber.setFormat("gdigrab");
                grabber.setOption("framerate", String.valueOf(fps));
                grabber.setFrameRate(fps);
            } else {
                String display = System.getenv().getOrDefault("DISPLAY", ":0.0");
                grabber = new FFmpegFrameGrabber(display);
                grabber.setFormat("x11grab");
                grabber.setOption("framerate", String.valueOf(fps));
                grabber.setFrameRate(fps);
            }
            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
            return grabber;
        } catch (Throwable t) {
            throw new RuntimeException("屏幕采集打开失败：" + t.getMessage()
                    + "。请检查录屏权限/DISPLAY，或改用摄像头通话。", t);
        }
    }

    /** Frame 转 BufferedImage 的转换器（线程安全） */
    public static Java2DFrameConverter createFrameConverter() {
        return new Java2DFrameConverter();
    }

    /** 将 Frame 转为 BufferedImage */
    public static BufferedImage frameToBufferedImage(Frame frame, Java2DFrameConverter converter) {
        return converter.convert(frame);
    }
}
