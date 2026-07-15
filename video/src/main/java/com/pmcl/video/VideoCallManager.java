package com.pmcl.video;

import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.service.neomedia.device.ScreenDevice;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.service.neomedia.format.MediaFormatFactory;
import org.jitsi.utils.MediaType;

import java.awt.Component;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 视频通话管理器：基于 libjitsi 实现的 P2P 音视频通话。
 * <p>
 * 职责：
 * <ul>
 *   <li>初始化/释放 libjitsi</li>
 *   <li>枚举音视频设备（摄像头、麦克风、屏幕）</li>
 *   <li>创建 MediaStream 并管理 RTP 收发</li>
 *   <li>提供视频渲染组件（AWT Component）供 UI 嵌入</li>
 * </ul>
 * <p>
 * ICE/信令由 {@link VideoCallSession} 协调，本类只负责媒体层。
 */
public final class VideoCallManager {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // ---------------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------------

    /** 初始化 libjitsi（全局只需一次） */
    public static synchronized void init() {
        if (initialized.compareAndSet(false, true)) {
            try {
                LibJitsi.start();
                System.out.println("[VideoCall] libjitsi 已初始化");
            } catch (Throwable e) {
                initialized.set(false);
                System.err.println("[VideoCall] libjitsi 初始化失败: " + e.getMessage());
                throw new RuntimeException("libjitsi 初始化失败", e);
            }
        }
    }

    /** 释放 libjitsi（应用退出时调用） */
    public static synchronized void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            try {
                LibJitsi.stop();
                System.out.println("[VideoCall] libjitsi 已释放");
            } catch (Throwable e) {
                System.err.println("[VideoCall] libjitsi 释放失败: " + e.getMessage());
            }
        }
    }

    public static boolean isInitialized() {
        return initialized.get();
    }

    public static MediaService getMediaService() {
        if (!initialized.get()) {
            throw new IllegalStateException("libjitsi 未初始化，请先调用 init()");
        }
        return LibJitsi.getMediaService();
    }

    // ---------------------------------------------------------------------------
    // 设备枚举
    // ---------------------------------------------------------------------------

    /** 获取默认音频设备（麦克风） */
    public static MediaDevice getDefaultAudioDevice() {
        try {
            return getMediaService().getDefaultDevice(MediaType.AUDIO, MediaUseCase.CALL);
        } catch (Exception e) {
            System.err.println("[VideoCall] 获取音频设备失败: " + e.getMessage());
            return null;
        }
    }

    /** 获取默认视频设备（摄像头） */
    public static MediaDevice getDefaultVideoDevice() {
        try {
            return getMediaService().getDefaultDevice(MediaType.VIDEO, MediaUseCase.CALL);
        } catch (Exception e) {
            System.err.println("[VideoCall] 获取视频设备失败: " + e.getMessage());
            return null;
        }
    }

    /** 获取默认屏幕设备（用于屏幕共享） */
    public static ScreenDevice getDefaultScreenDevice() {
        try {
            return getMediaService().getDefaultScreenDevice();
        } catch (Exception e) {
            System.err.println("[VideoCall] 获取屏幕设备失败: " + e.getMessage());
            return null;
        }
    }

    /** 获取局部桌面流设备（用于区域屏幕共享） */
    public static MediaDevice getPartialDesktopDevice(int width, int height, int x, int y) {
        try {
            return getMediaService().getMediaDeviceForPartialDesktopStreaming(width, height, x, y);
        } catch (Exception e) {
            System.err.println("[VideoCall] 获取局部桌面设备失败: " + e.getMessage());
            return null;
        }
    }

    /** 枚举所有音频设备 */
    public static List<MediaDevice> listAudioDevices() {
        try {
            return getMediaService().getDevices(MediaType.AUDIO, MediaUseCase.CALL);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 枚举所有视频设备 */
    public static List<MediaDevice> listVideoDevices() {
        try {
            return getMediaService().getDevices(MediaType.VIDEO, MediaUseCase.CALL);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------------------
    // 格式工具
    // ---------------------------------------------------------------------------

    /** 创建音频格式（PCMU/8000Hz，默认） */
    public static MediaFormat createAudioFormat() {
        return getMediaService().getFormatFactory()
                .createMediaFormat("opus", 48000);
    }

    /** 创建视频格式（H264） */
    public static MediaFormat createVideoFormat() {
        return getMediaService().getFormatFactory()
                .createMediaFormat("H264", MediaFormatFactory.CLOCK_RATE_NOT_SPECIFIED);
    }

    // ---------------------------------------------------------------------------
    // 媒体流创建
    // ---------------------------------------------------------------------------

    /**
     * 创建音频 MediaStream（SENDRECV）。
     *
     * @param connector RTP/RTCP 连接器
     * @param target    远端地址
     * @return 已配置但未启动的 MediaStream
     */
    public static MediaStream createAudioStream(StreamConnector connector, MediaStreamTarget target) {
        MediaDevice device = getDefaultAudioDevice();
        if (device == null) {
            throw new IllegalStateException("无可用音频设备");
        }

        MediaStream stream = getMediaService().createMediaStream(connector, device);
        stream.setDirection(MediaDirection.SENDRECV);
        stream.setName(MediaType.AUDIO.toString());

        MediaFormat format = createAudioFormat();
        stream.addDynamicRTPPayloadType((byte) 111, format);
        stream.setFormat(format);

        stream.setConnector(connector);
        stream.setTarget(target);

        return stream;
    }

    /**
     * 创建视频 MediaStream。
     *
     * @param connector   RTP/RTCP 连接器
     * @param target      远端地址
     * @param sendOnly    true=仅发送（屏幕共享场景），false=双向
     * @param useScreen   true=使用屏幕设备，false=使用摄像头
     * @return 已配置但未启动的 MediaStream
     */
    public static MediaStream createVideoStream(StreamConnector connector, MediaStreamTarget target,
                                                 boolean sendOnly, boolean useScreen) {
        MediaDevice device;
        if (useScreen) {
            device = getDefaultScreenDevice() != null
                    ? getMediaService().getDefaultDevice(MediaType.VIDEO, MediaUseCase.CALL)
                    : getDefaultVideoDevice();
        } else {
            device = getDefaultVideoDevice();
        }
        if (device == null) {
            throw new IllegalStateException("无可用视频设备");
        }

        MediaStream stream = getMediaService().createMediaStream(connector, device);
        stream.setDirection(sendOnly ? MediaDirection.SENDONLY : MediaDirection.SENDRECV);
        stream.setName(MediaType.VIDEO.toString());

        MediaFormat format = createVideoFormat();
        stream.addDynamicRTPPayloadType((byte) 96, format);
        stream.setFormat(format);

        stream.setConnector(connector);
        stream.setTarget(target);

        return stream;
    }

    /** 创建简单的 StreamConnector（基于两个 DatagramSocket） */
    public static StreamConnector createConnector(int localRtpPort, int localRtcpPort) {
        try {
            java.net.DatagramSocket rtp = new java.net.DatagramSocket(localRtpPort);
            java.net.DatagramSocket rtcp = new java.net.DatagramSocket(localRtcpPort);
            return new DefaultStreamConnector(rtp, rtcp);
        } catch (Exception e) {
            throw new RuntimeException("创建连接器失败: " + e.getMessage(), e);
        }
    }

    /** 创建 MediaStreamTarget（远端 RTP/RTCP 地址） */
    public static MediaStreamTarget createTarget(String remoteIp, int remoteRtpPort, int remoteRtcpPort) {
        return new MediaStreamTarget(
                new InetSocketAddress(remoteIp, remoteRtpPort),
                new InetSocketAddress(remoteIp, remoteRtcpPort)
        );
    }
}
