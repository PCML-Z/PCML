package com.pmcl.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.StunCandidateHarvester;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 视频通话会话：基于 ice4j (ICE) + JavaCV (摄像头采集) + JPEG/UDP 传输。
 * <p>
 * 流程：
 * <ol>
 *   <li>创建 ice4j Agent，收集本地 ICE 候选</li>
 *   <li>通过信令通道（FriendManager）交换候选</li>
 *   <li>ICE 连通性检查完成后，获取选定的 UDP socket</li>
 *   <li>启动摄像头采集线程：FFmpegFrameGrabber → BufferedImage → JPEG → UDP 发送</li>
 *   <li>启动接收线程：UDP 接收 → JPEG 解压 → BufferedImage → 回调 UI 渲染</li>
 *   <li>通话结束时释放所有资源</li>
 * </ol>
 */
public final class VideoCallSession {

    // ---------------------------------------------------------------------------
    // 通话状态
    // ---------------------------------------------------------------------------

    public enum State {
        RINGING,
        NEGOTIATING,
        IN_CALL,
        ENDED
    }

    public enum MediaType {
        AUDIO_VIDEO,
        AUDIO_ONLY,
        SCREEN_SHARE
    }

    /** 通话事件回调 */
    public interface CallListener {
        void onStateChanged(State state);
        /** 本地候选就绪，附带本地 ICE ufrag 和 password */
        void onLocalCandidate(String candidateSdp, String ufrag, String pwd);
        /** 远端视频帧到达（BufferedImage），component 为 null 表示视频结束 */
        void onRemoteFrame(BufferedImage frame);
        /** 本地视频帧就绪（BufferedImage），用于预览 */
        void onLocalFrame(BufferedImage frame);
        /** 本地视频端口就绪，需要发送给远端 */
        void onVideoPortReady(int port);
        void onError(String message);
    }

    // ---------------------------------------------------------------------------
    // 字段
    // ---------------------------------------------------------------------------

    private final String callId;
    private final String remoteIdentity;
    private final String remoteName;
    private final boolean isInitiator;
    private final MediaType mediaType;
    private final List<CallListener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.RINGING;
    private Agent iceAgent;

    // 摄像头采集
    private FFmpegFrameGrabber grabber;
    private Java2DFrameConverter frameConverter;
    private Thread captureThread;
    private Thread receiveThread;
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private volatile boolean cameraEnabled = true;
    private volatile boolean muted = false;

    // 视频参数
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_FPS = 15;
    private static final int JPEG_QUALITY = 60;  // JPEG 质量 (0-100)
    private static final int MAX_PACKET_SIZE = 65000;  // UDP 最大包大小

    /** STUN 服务器列表 */
    private static final String[] DEFAULT_STUN_SERVERS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
    };

    // 视频传输 socket（独立于 ICE）
    private DatagramSocket videoSocket;
    private volatile boolean localPortReady = false;

    // ---------------------------------------------------------------------------
    // 构造
    // ---------------------------------------------------------------------------

    public VideoCallSession(String callId, String remoteIdentity, String remoteName,
                            boolean isInitiator, MediaType mediaType) {
        this.callId = callId;
        this.remoteIdentity = remoteIdentity;
        this.remoteName = remoteName;
        this.isInitiator = isInitiator;
        this.mediaType = mediaType;
    }

    // ---------------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------------

    public String getCallId() { return callId; }
    public String getRemoteIdentity() { return remoteIdentity; }
    public String getRemoteName() { return remoteName; }
    public boolean isInitiator() { return isInitiator; }
    public MediaType getMediaType() { return mediaType; }
    public State getState() { return state; }

    public void addListener(CallListener listener) { listeners.add(listener); }
    public void removeListener(CallListener listener) { listeners.remove(listener); }

    private void setState(State newState) {
        this.state = newState;
        for (CallListener l : listeners) {
            try { l.onStateChanged(newState); } catch (Exception ignored) {}
        }
    }

    private void fireError(String msg) {
        System.err.println("[VideoCall] 错误: " + msg);
        for (CallListener l : listeners) {
            try { l.onError(msg); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------------------
    // ICE 协商
    // ---------------------------------------------------------------------------

    public synchronized void startIceNegotiation() {
        System.out.println("[VideoCall] 开始 ICE 协商 callId=" + callId + " initiator=" + isInitiator);
        if (!VideoCallManager.isInitialized()) {
            VideoCallManager.init();
        }

        try {
            iceAgent = new Agent();
            iceAgent.setControlling(isInitiator);

            for (String stun : DEFAULT_STUN_SERVERS) {
                String[] parts = stun.split(":");
                try {
                    iceAgent.addCandidateHarvester(new StunCandidateHarvester(
                            new TransportAddress(parts[0], Integer.parseInt(parts[1]), Transport.UDP)));
                } catch (Exception e) {
                    System.err.println("[VideoCall] 添加 STUN 收集器失败 " + stun + ": " + e.getMessage());
                }
            }

            // 创建视频媒体流（使用 RTCP mux，只需一个 Component）
            if (mediaType != MediaType.AUDIO_ONLY) {
                IceMediaStream videoStream = iceAgent.createMediaStream("video");
                agentCreateComponent(iceAgent, videoStream, "video");
            }

            // 收集本地候选并通知
            String localUfrag = iceAgent.getLocalUfrag();
            String localPwd = iceAgent.getLocalPassword();
            int localCandidateCount = 0;
            for (IceMediaStream stream : iceAgent.getStreams()) {
                for (Component comp : stream.getComponents()) {
                    for (LocalCandidate cand : comp.getLocalCandidates()) {
                        String sdp = stream.getName() + ":" + formatCandidateSdp(cand);
                        localCandidateCount++;
                        for (CallListener l : listeners) {
                            try { l.onLocalCandidate(sdp, localUfrag, localPwd); } catch (Exception ignored) {}
                        }
                    }
                }
            }
            System.out.println("[VideoCall] 本地候选收集完成，共 " + localCandidateCount + " 个");

            setState(State.NEGOTIATING);

            Thread monitor = new Thread(this::monitorIceState, "VideoCall-ICE-Monitor");
            monitor.setDaemon(true);
            monitor.start();

        } catch (Exception e) {
            fireError("ICE 协商启动失败: " + e.getMessage());
        }
    }

    private void agentCreateComponent(Agent agent, IceMediaStream stream, String name) {
        try {
            // SELECTED_AND_TCP: 对已选配对发送 keepalive，保持 ICE 连接不被超时终止
            agent.createComponent(stream, KeepAliveStrategy.SELECTED_AND_TCP, true);
        } catch (Exception e) {
            System.err.println("[VideoCall] 创建 ICE Component " + name + " 失败: " + e.getMessage());
        }
    }

    private String formatCandidateSdp(LocalCandidate cand) {
        return String.format("candidate:%s %d %s %d %s %d typ %s",
                cand.getFoundation(),
                cand.getParentComponent().getComponentID(),
                cand.getTransport().toString().toLowerCase(),
                cand.getPriority(),
                cand.getTransportAddress().getAddress().getHostAddress(),
                cand.getTransportAddress().getPort(),
                cand.getType().toString().toLowerCase());
    }

    private void monitorIceState() {
        try {
            int waited = 0;
            while (!iceConnectivityStarted && iceAgent != null && waited < 35000) {
                Thread.sleep(200);
                waited += 200;
            }
            if (!iceConnectivityStarted) {
                fireError("等待远端 ICE 候选超时");
                setState(State.ENDED);
                return;
            }

            waited = 0;
            while (iceAgent != null && iceAgent.getState() == IceProcessingState.RUNNING && waited < 30000) {
                Thread.sleep(200);
                waited += 200;
            }

            if (iceAgent == null) {
                fireError("ICE agent 在协商期间被释放");
                setState(State.ENDED);
                return;
            }

            boolean hasSelectedPair = false;
            for (IceMediaStream stream : iceAgent.getStreams()) {
                for (Component comp : stream.getComponents()) {
                    if (comp.getSelectedPair() != null) {
                        hasSelectedPair = true;
                        break;
                    }
                }
                if (hasSelectedPair) break;
            }

            if (!hasSelectedPair) {
                fireError("ICE 协商失败或超时，无选定的 CandidatePair");
                setState(State.ENDED);
                return;
            }

            System.out.println("[VideoCall] ICE 协商成功");
            startVideoStreaming();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fireError("ICE 监控异常: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // 远端候选处理
    // ---------------------------------------------------------------------------

    private volatile boolean remoteCandidateReceived = false;
    private volatile boolean iceConnectivityStarted = false;
    private volatile int remoteCandidateCount = 0;

    public synchronized void addRemoteCandidate(String candidateSdp, String remoteUfrag, String remotePwd) {
        if (iceAgent == null) {
            System.err.println("[VideoCall] 忽略 ICE 候选：agent 为空 callId=" + callId);
            return;
        }
        System.out.println("[VideoCall] 收到远端 ICE 候选 #" + (remoteCandidateCount + 1) + " callId=" + callId);

        try {
            if (remoteUfrag != null && !remoteUfrag.isEmpty()) {
                for (IceMediaStream stream : iceAgent.getStreams()) {
                    if (stream.getRemoteUfrag() == null) {
                        stream.setRemoteUfrag(remoteUfrag);
                        if (remotePwd != null && !remotePwd.isEmpty()) {
                            stream.setRemotePassword(remotePwd);
                        }
                    }
                }
            }

            String streamName = null;
            String line = candidateSdp;
            int colonIdx = candidateSdp.indexOf(':');
            if (colonIdx > 0 && !candidateSdp.startsWith("candidate:")) {
                streamName = candidateSdp.substring(0, colonIdx);
                line = candidateSdp.substring(colonIdx + 1);
            }

            line = line.startsWith("candidate:") ? line.substring(10) : line;
            String[] parts = line.split("\\s+");
            if (parts.length < 8) return;

            String foundation = parts[0];
            int componentId = Integer.parseInt(parts[1]);
            String transport = parts[2];
            long priority = Long.parseLong(parts[3]);
            String address = parts[4];
            int port = Integer.parseInt(parts[5]);
            String typeStr = parts[7].toLowerCase();

            TransportAddress ta = new TransportAddress(address, port, Transport.parse(transport));

            if (streamName != null) {
                IceMediaStream stream = iceAgent.getStream(streamName);
                if (stream != null) {
                    Component comp = stream.getComponent(componentId);
                    if (comp != null) {
                        CandidateType type = CandidateType.parse(typeStr);
                        RemoteCandidate rc = new RemoteCandidate(ta, comp, type, foundation, priority, null);
                        comp.addRemoteCandidate(rc);
                    }
                }
            } else {
                for (IceMediaStream stream : iceAgent.getStreams()) {
                    Component comp = stream.getComponent(componentId);
                    if (comp != null) {
                        CandidateType type = CandidateType.parse(typeStr);
                        RemoteCandidate rc = new RemoteCandidate(ta, comp, type, foundation, priority, null);
                        comp.addRemoteCandidate(rc);
                    }
                }
            }

            remoteCandidateCount++;
            if (!remoteCandidateReceived) {
                remoteCandidateReceived = true;
                Thread scheduler = new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        synchronized (VideoCallSession.this) {
                            if (iceAgent != null && state == State.NEGOTIATING && !iceConnectivityStarted) {
                                iceConnectivityStarted = true;
                                System.out.println("[VideoCall] 收到 " + remoteCandidateCount + " 个远端候选，启动 ICE 连通性检查");
                                iceAgent.startConnectivityEstablishment();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "VideoCall-ICE-Scheduler");
                scheduler.setDaemon(true);
                scheduler.start();
            }

        } catch (Exception e) {
            System.err.println("[VideoCall] 解析远端候选失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // 视频流传输
    // ---------------------------------------------------------------------------

    /** ICE 完成后，获取远端地址，创建独立视频 socket */
    private synchronized void startVideoStreaming() {
        try {
            IceMediaStream videoIce = iceAgent.getStream("video");
            if (videoIce == null) {
                fireError("视频 ICE 流为空");
                return;
            }

            Component rtpComp = videoIce.getComponent(Component.RTP);
            if (rtpComp == null || rtpComp.getSelectedPair() == null) {
                fireError("视频 RTP Component 或 CandidatePair 为空");
                return;
            }

            // 获取 ICE 协商出的远端地址
            InetSocketAddress iceRemote = rtpComp.getSelectedPair().getRemoteCandidate().getTransportAddress();
            
            // 使用 FriendPage 预先创建的 videoSocket（端口已通过信令交换），如果不存在才创建新的
            if (videoSocket == null || videoSocket.isClosed()) {
                videoSocket = new DatagramSocket();
            }
            int port = videoSocket.getLocalPort();
            System.out.println("[VideoCall] 视频 socket 就绪: 本地端口=" + port + " 等待远端端口...");
            
            // 通知上层（通过信令发送端口号给远端）
            for (CallListener l : listeners) {
                try { l.onVideoPortReady(port); } catch (Exception ignored) {}
            }
            
            // 立即释放 ICE agent（不再需要，避免 keepalive 超时干扰）
            if (iceAgent != null) {
                try { iceAgent.free(); } catch (Exception ignored) {}
                iceAgent = null;
            }

            setState(State.IN_CALL);

            capturing.set(true);

            // 启动接收线程
            startReceiveThread(videoSocket);

            // 保存 ICE 远端地址
            this.pendingIceRemote = iceRemote;
            
            // 如果远端视频端口已通过信令收到，立即启动采集发送
            if (remoteVideoPort > 0) {
                startCaptureWithRemotePort(remoteVideoPort);
            }

        } catch (Exception e) {
            fireError("启动视频流失败: " + e.getMessage());
        }
    }
    
    private InetSocketAddress pendingIceRemote;
    private volatile int remoteVideoPort = 0;

    /** 收到远端的视频端口后，启动采集线程发送视频 */
    public synchronized void onRemoteVideoPort(int port) {
        if (port <= 0) return;
        this.remoteVideoPort = port;
        // 如果 ICE 尚未完成（pendingIceRemote 为空），等待 startVideoStreaming 中再启动
        if (videoSocket != null && !videoSocket.isClosed() && pendingIceRemote != null && !capturing.get()) {
            startCaptureWithRemotePort(port);
        }
    }
    
    private void startCaptureWithRemotePort(int port) {
        InetSocketAddress remote;
        String remoteIp = pendingIceRemote.getAddress().getHostAddress();
        if (remoteIp.startsWith("240e:") || remoteIp.equals("::1") || remoteIp.equals("127.0.0.1") || remoteIp.startsWith("fe80")) {
            remote = new InetSocketAddress("127.0.0.1", port);
        } else {
            remote = new InetSocketAddress(pendingIceRemote.getAddress(), port);
        }
        System.out.println("[VideoCall] 远端视频端口就绪: " + remote + " 开始发送");
        startCaptureThread(videoSocket, remote);
    }

    /** 启动摄像头采集 + 发送线程 */
    private void startCaptureThread(DatagramSocket socket, InetSocketAddress remoteAddress) {
        frameConverter = new Java2DFrameConverter();

        captureThread = new Thread(() -> {
            try {
                grabber = VideoCallManager.createCameraGrabber(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS);
                grabber.start();
                System.out.println("[VideoCall] 摄像头采集已启动");

                long frameCount = 0;
                long sentCount = 0;
                long skippedCount = 0;
                long audioFrameCount = 0;
                long lastLogTime = System.currentTimeMillis();

                while (capturing.get() && !Thread.currentThread().isInterrupted()) {
                    if (!cameraEnabled) {
                        Thread.sleep(1000 / VIDEO_FPS);
                        continue;
                    }

                    Frame frame = grabber.grab();
                    if (frame == null) continue;
                    
                    // 跳过音频帧（avfoundation 会同时采集音频和视频）
                    if (frame.image == null) {
                        audioFrameCount++;
                        continue;
                    }

                    BufferedImage img = frameConverter.convert(frame);
                    if (img == null) continue;

                    frameCount++;

                    // 通知本地预览
                    for (CallListener l : listeners) {
                        try { l.onLocalFrame(img); } catch (Exception ignored) {}
                    }

                    // JPEG 压缩（带降级重试：quality 60 → 40 → 25）
                    byte[] jpegData = compressJpeg(img, JPEG_QUALITY);
                    if (jpegData != null && jpegData.length > MAX_PACKET_SIZE) {
                        // 降级重试
                        jpegData = compressJpeg(img, 40);
                    }
                    if (jpegData != null && jpegData.length > MAX_PACKET_SIZE) {
                        jpegData = compressJpeg(img, 25);
                    }
                    if (jpegData == null || jpegData.length > MAX_PACKET_SIZE) {
                        skippedCount++;
                        continue;
                    }

                    // UDP 发送
                    try {
                        DatagramPacket packet = new DatagramPacket(jpegData, jpegData.length, remoteAddress);
                        socket.send(packet);
                        sentCount++;
                    } catch (Exception e) {
                        // 发送失败可能是网络问题，忽略
                    }

                    // 每 5 秒打印一次帧统计
                    if (System.currentTimeMillis() - lastLogTime > 5000) {
                        System.out.println("[VideoCall] 帧统计: 采集=" + frameCount
                                + " 发送=" + sentCount + " 丢弃=" + skippedCount
                                + " 音频帧跳过=" + audioFrameCount
                                + " 大小~" + (sentCount > 0 ? jpegData.length : 0) + "B");
                        if (frameCount == 0 && audioFrameCount > 0) {
                            System.err.println("[VideoCall] 警告: 只收到音频帧无视频帧！请检查 macOS 摄像头权限（系统设置→隐私与安全性→摄像头→允许 Terminal）");
                        }
                        lastLogTime = System.currentTimeMillis();
                    }
                    try {
                        DatagramPacket packet = new DatagramPacket(jpegData, jpegData.length, remoteAddress);
                        socket.send(packet);
                    } catch (Exception e) {
                        // 发送失败可能是网络问题，忽略
                    }

                    // 控制帧率
                    Thread.sleep(1000 / VIDEO_FPS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                fireError("摄像头采集失败: " + e.getMessage());
            } finally {
                if (grabber != null) {
                    try { grabber.stop(); } catch (Exception ignored) {}
                    try { grabber.close(); } catch (Exception ignored) {}
                    grabber = null;
                }
            }
        }, "VideoCall-Capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /** 启动接收线程 */
    private void startReceiveThread(DatagramSocket socket) {
        System.out.println("[VideoCall] 启动视频接收线程 callId=" + callId);
        receiveThread = new Thread(() -> {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            long receivedCount = 0;
            long lastLogTime = System.currentTimeMillis();
            while (capturing.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    if (packet.getLength() == 0) continue;

                    // JPEG 解压
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                    BufferedImage img = decompressJpeg(data);
                    if (img == null) continue;

                    receivedCount++;
                    if (receivedCount == 1) {
                        System.out.println("[VideoCall] 收到首帧! size=" + packet.getLength() + "B");
                    }
                    if (System.currentTimeMillis() - lastLogTime > 5000 && receivedCount > 0) {
                        System.out.println("[VideoCall] 接收帧统计: " + receivedCount + " 帧");
                        lastLogTime = System.currentTimeMillis();
                    }

                    // 通知 UI 渲染
                    for (CallListener l : listeners) {
                        try { l.onRemoteFrame(img); } catch (Exception ignored) {}
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // 超时继续
                } catch (Exception e) {
                    // 接收失败可能是 socket 关闭
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "VideoCall-Receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /** JPEG 压缩，使用指定的 quality (0-100)，返回压缩字节或 null */
    private byte[] compressJpeg(BufferedImage img, int quality) {
        try {
            java.util.Iterator<javax.imageio.ImageWriter> writers = 
                javax.imageio.ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) return null;
            javax.imageio.ImageWriter writer = writers.next();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            
            javax.imageio.plugins.jpeg.JPEGImageWriteParam param = 
                new javax.imageio.plugins.jpeg.JPEGImageWriteParam(null);
            param.setCompressionMode(javax.imageio.plugins.jpeg.JPEGImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100.0f);
            
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            writer.dispose();
            ios.close();
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** JPEG 解压 */
    private BufferedImage decompressJpeg(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            return javax.imageio.ImageIO.read(bais);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------
    // 通话控制
    // ---------------------------------------------------------------------------

    public synchronized void end() {
        if (state == State.ENDED) return;

        capturing.set(false);

        try {
            if (captureThread != null) {
                captureThread.interrupt();
                captureThread = null;
            }
            if (receiveThread != null) {
                receiveThread.interrupt();
                receiveThread = null;
            }
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
                try { grabber.close(); } catch (Exception ignored) {}
                grabber = null;
            }
            if (iceAgent != null) {
                try { iceAgent.free(); } catch (Exception ignored) {}
                iceAgent = null;
            }
        } finally {
            setState(State.ENDED);
            System.out.println("[VideoCall] 通话已结束: " + callId);
        }
    }

    public void setMute(boolean mute) {
        this.muted = mute;
    }

    public void setCameraEnabled(boolean enabled) {
        this.cameraEnabled = enabled;
    }
}
