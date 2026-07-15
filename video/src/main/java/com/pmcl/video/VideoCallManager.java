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
        String osName = System.getProperty("os.name").toLowerCase();
        FFmpegFrameGrabber grabber;

        if (osName.contains("mac")) {
            // macOS: avfoundation, 格式为 "video:audio"
            // "0:none" = 视频设备0（默认摄像头），无音频
            grabber = new FFmpegFrameGrabber("0:none");
            grabber.setFormat("avfoundation");
            grabber.setOption("framerate", "30");
            grabber.setFrameRate(30);
        } else if (osName.contains("win")) {
            // Windows: dshow, 设备名需要枚举，先用 "video=0" 占位
            grabber = new FFmpegFrameGrabber("video=0");
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

        return grabber;
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
