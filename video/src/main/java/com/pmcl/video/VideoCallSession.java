package com.pmcl.video;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.util.event.VideoEvent;
import org.jitsi.util.event.VideoListener;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 视频通话会话：管理一次通话的完整生命周期。
 * <p>
 * 流程：
 * <ol>
 *   <li>创建 ice4j Agent，收集本地 ICE 候选</li>
 *   <li>通过信令通道（FriendManager）交换候选</li>
 *   <li>ICE 连通性检查完成后，用选定的 CandidatePair 创建 MediaStream</li>
 *   <li>启动 libjitsi 媒体流，开始音视频收发</li>
 *   <li>通话结束时释放所有资源</li>
 * </ol>
 */
public final class VideoCallSession {

    // ---------------------------------------------------------------------------
    // 通话状态
    // ---------------------------------------------------------------------------

    public enum State {
        /** 通话邀请已发出/收到，等待应答 */
        RINGING,
        /** ICE 协商中 */
        NEGOTIATING,
        /** 通话进行中 */
        IN_CALL,
        /** 通话已结束 */
        ENDED
    }

    public enum MediaType {
        AUDIO_VIDEO,
        AUDIO_ONLY,
        SCREEN_SHARE
    }

    /** 通话事件 */
    public interface CallListener {
        void onStateChanged(State state);
        void onLocalCandidate(String candidateSdp);
        /** 远端视频组件就绪（或被移除，component=null） */
        void onRemoteVideoComponent(java.awt.Component component);
        /** 本地视频预览组件就绪（或被移除，component=null） */
        void onLocalVideoComponent(java.awt.Component component);
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
    private MediaStream audioStream;
    private MediaStream videoStream;
    private final AtomicReference<java.awt.Component> remoteVideoComponent = new AtomicReference<>();

    /** STUN 服务器列表（公共 STUN） */
    private static final String[] DEFAULT_STUN_SERVERS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
    };

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

    /** 获取远端视频组件（可能为 null，尚未就绪） */
    public java.awt.Component getRemoteVideoComponent() {
        return remoteVideoComponent.get();
    }

    /** 获取本地视频预览组件（可能为 null） */
    public java.awt.Component getLocalVideoComponent() {
        if (videoStream instanceof VideoMediaStream) {
            try {
                return ((VideoMediaStream) videoStream).getLocalVisualComponent();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

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

    /**
     * 启动 ICE 协商：创建 Agent，收集本地候选，开始连通性检查。
     * 本地候选生成后通过 onLocalCandidate 回调通知上层发送给远端。
     */
    public synchronized void startIceNegotiation() {
        if (!VideoCallManager.isInitialized()) {
            VideoCallManager.init();
        }

        try {
            iceAgent = new Agent();
            iceAgent.setControlling(isInitiator);

            // 添加 STUN 收集器（用于外网 NAT 穿透）
            for (String stun : DEFAULT_STUN_SERVERS) {
                String[] parts = stun.split(":");
                try {
                    iceAgent.addCandidateHarvester(new StunCandidateHarvester(
                            new TransportAddress(parts[0], Integer.parseInt(parts[1]), Transport.UDP)));
                } catch (Exception e) {
                    System.err.println("[VideoCall] 添加 STUN 收集器失败 " + stun + ": " + e.getMessage());
                }
            }

            // 创建音频媒体流（RTP + RTCP 两个 Component）
            if (mediaType != MediaType.SCREEN_SHARE || mediaType == MediaType.AUDIO_VIDEO) {
                IceMediaStream audioStream = iceAgent.createMediaStream("audio");
                agentCreateComponent(iceAgent, audioStream, Component.RTP, "audio-rtp");
                agentCreateComponent(iceAgent, audioStream, Component.RTCP, "audio-rtcp");
            }

            // 创建视频媒体流
            if (mediaType != MediaType.AUDIO_ONLY) {
                IceMediaStream videoStream = iceAgent.createMediaStream("video");
                agentCreateComponent(iceAgent, videoStream, Component.RTP, "video-rtp");
                agentCreateComponent(iceAgent, videoStream, Component.RTCP, "video-rtcp");
            }

            // 收集本地候选并通知
            for (IceMediaStream stream : iceAgent.getStreams()) {
                for (Component comp : stream.getComponents()) {
                    for (LocalCandidate cand : comp.getLocalCandidates()) {
                        String sdp = formatCandidateSdp(cand);
                        for (CallListener l : listeners) {
                            try { l.onLocalCandidate(sdp); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            setState(State.NEGOTIATING);

            // 启动连通性检查
            iceAgent.startConnectivityEstablishment();

            // 启动监听线程等待 ICE 完成
            Thread monitor = new Thread(this::monitorIceState, "VideoCall-ICE-Monitor");
            monitor.setDaemon(true);
            monitor.start();

        } catch (Exception e) {
            fireError("ICE 协商启动失败: " + e.getMessage());
        }
    }

    /** 创建 Component 并处理端口绑定 */
    private void agentCreateComponent(Agent agent, IceMediaStream stream, int componentId, String name) {
        try {
            // createComponent 的 componentID 由 IceMediaStream 自动分配（RTP=1, RTCP=2），
            // 三个 int 参数分别为 preferredPort、minPort、maxPort
            agent.createComponent(stream, 0, 5000, 9000);
        } catch (Exception e) {
            System.err.println("[VideoCall] 创建 ICE Component " + name + " 失败: " + e.getMessage());
        }
    }

    /** 格式化 ICE 候选为 SDP 字符串 */
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

    /** 监听 ICE 状态，完成后启动媒体流 */
    private void monitorIceState() {
        try {
            // 等待 ICE 完成（最多 30 秒）
            int waited = 0;
            while (iceAgent != null && iceAgent.getState() == IceProcessingState.RUNNING && waited < 30000) {
                Thread.sleep(200);
                waited += 200;
            }

            if (iceAgent == null || iceAgent.getState() != IceProcessingState.COMPLETED) {
                fireError("ICE 协商失败或超时，状态: " + (iceAgent != null ? iceAgent.getState() : "null"));
                setState(State.ENDED);
                return;
            }

            // ICE 完成，启动媒体流
            startMediaStreams();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fireError("ICE 监控异常: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // 远端候选处理
    // ---------------------------------------------------------------------------

    /**
     * 添加远端 ICE 候选（从信令通道接收）。
     *
     * @param candidateSdp SDP 格式的候选字符串
     */
    public synchronized void addRemoteCandidate(String candidateSdp) {
        if (iceAgent == null) return;

        try {
            // 解析 "candidate:foundation component transport priority address port typ type"
            String line = candidateSdp.startsWith("candidate:") ? candidateSdp.substring(10) : candidateSdp;
            String[] parts = line.split("\\s+");
            if (parts.length < 8) return;

            String foundation = parts[0];
            int componentId = Integer.parseInt(parts[1]);
            String transport = parts[2];
            long priority = Long.parseLong(parts[3]);
            String address = parts[4];
            int port = Integer.parseInt(parts[5]);
            // parts[6] = "typ"
            String typeStr = parts[7].toLowerCase();

            TransportAddress ta = new TransportAddress(address, port, Transport.parse(transport));

            // 找到对应的 stream 和 component
            for (IceMediaStream stream : iceAgent.getStreams()) {
                Component comp = stream.getComponent(componentId);
                if (comp != null) {
                    CandidateType type = CandidateType.parse(typeStr);
                    RemoteCandidate rc = new RemoteCandidate(ta, comp, type, foundation, priority, null);
                    comp.addRemoteCandidate(rc);
                    break;
                }
            }

        } catch (Exception e) {
            System.err.println("[VideoCall] 解析远端候选失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // 媒体流启动
    // ---------------------------------------------------------------------------

    /** ICE 完成后，用选定的 CandidatePair 创建并启动 MediaStream */
    private synchronized void startMediaStreams() {
        try {
            // 音频流
            if (mediaType != MediaType.SCREEN_SHARE || mediaType == MediaType.AUDIO_VIDEO) {
                IceMediaStream audioIce = iceAgent.getStream("audio");
                if (audioIce != null) {
                    StreamConnector audioConn = createConnectorFromIce(audioIce);
                    MediaStreamTarget audioTarget = createTargetFromIce(audioIce);
                    audioStream = VideoCallManager.createAudioStream(audioConn, audioTarget);
                    audioStream.start();
                    System.out.println("[VideoCall] 音频流已启动");
                }
            }

            // 视频流
            if (mediaType != MediaType.AUDIO_ONLY) {
                IceMediaStream videoIce = iceAgent.getStream("video");
                if (videoIce != null) {
                    StreamConnector videoConn = createConnectorFromIce(videoIce);
                    MediaStreamTarget videoTarget = createTargetFromIce(videoIce);
                    boolean screenShare = (mediaType == MediaType.SCREEN_SHARE);
                    videoStream = VideoCallManager.createVideoStream(videoConn, videoTarget, screenShare, screenShare);

                    // 注册 VideoListener 捕获远端视频组件
                    if (videoStream instanceof VideoMediaStream) {
                        VideoMediaStream vms = (VideoMediaStream) videoStream;
                        vms.addVideoListener(new VideoListener() {
                            @Override
                            public void videoAdded(VideoEvent e) {
                                java.awt.Component comp = e.getVisualComponent();
                                if (comp != null) {
                                    if (e.getOrigin() == VideoEvent.REMOTE) {
                                        System.out.println("[VideoCall] 远端视频组件就绪: " + comp.getSize());
                                        remoteVideoComponent.set(comp);
                                        for (CallListener l : listeners) {
                                            try { l.onRemoteVideoComponent(comp); } catch (Exception ignored) {}
                                        }
                                    } else if (e.getOrigin() == VideoEvent.LOCAL) {
                                        System.out.println("[VideoCall] 本地视频组件就绪: " + comp.getSize());
                                        for (CallListener l : listeners) {
                                            try { l.onLocalVideoComponent(comp); } catch (Exception ignored) {}
                                        }
                                    }
                                }
                            }

                            @Override
                            public void videoRemoved(VideoEvent e) {
                                if (e.getOrigin() == VideoEvent.REMOTE) {
                                    remoteVideoComponent.set(null);
                                    for (CallListener l : listeners) {
                                        try { l.onRemoteVideoComponent(null); } catch (Exception ignored) {}
                                    }
                                } else if (e.getOrigin() == VideoEvent.LOCAL) {
                                    for (CallListener l : listeners) {
                                        try { l.onLocalVideoComponent(null); } catch (Exception ignored) {}
                                    }
                                }
                            }

                            @Override
                            public void videoUpdate(VideoEvent e) {}
                        });

                        // 尝试获取已存在的本地视频组件（摄像头预览）
                        try {
                            java.awt.Component localComp = vms.getLocalVisualComponent();
                            if (localComp != null) {
                                System.out.println("[VideoCall] 本地视频预览组件已就绪");
                                for (CallListener l : listeners) {
                                    try { l.onLocalVideoComponent(localComp); } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    videoStream.start();
                    System.out.println("[VideoCall] 视频流已启动, screenShare=" + screenShare);
                }
            }

            setState(State.IN_CALL);

        } catch (Exception e) {
            fireError("启动媒体流失败: " + e.getMessage());
        }
    }

    /** 从 ICE CandidatePair 创建 StreamConnector */
    private StreamConnector createConnectorFromIce(IceMediaStream iceStream) {
        Component rtpComp = iceStream.getComponent(Component.RTP);
        Component rtcpComp = iceStream.getComponent(Component.RTCP);

        if (rtpComp == null) {
            throw new IllegalStateException("ICE RTP Component 为空");
        }

        // 使用 Component 的 CandidatePair socket
        java.net.DatagramSocket rtpSocket = rtpComp.getSelectedPair() != null
                ? rtpComp.getSelectedPair().getDatagramSocket()
                : null;

        if (rtpSocket == null) {
            // 回退到 ComponentSocket
            rtpSocket = rtpComp.getSocket();
        }

        java.net.DatagramSocket rtcpSocket = null;
        if (rtcpComp != null) {
            rtcpSocket = rtcpComp.getSocket();
        }

        return new DefaultStreamConnector(rtpSocket, rtcpSocket);
    }

    /** 从 ICE CandidatePair 创建 MediaStreamTarget */
    private MediaStreamTarget createTargetFromIce(IceMediaStream iceStream) {
        Component rtpComp = iceStream.getComponent(Component.RTP);
        Component rtcpComp = iceStream.getComponent(Component.RTCP);

        InetSocketAddress rtpTarget = null;
        InetSocketAddress rtcpTarget = null;

        if (rtpComp != null && rtpComp.getSelectedPair() != null) {
            InetSocketAddress remote = rtpComp.getSelectedPair().getRemoteCandidate().getTransportAddress();
            rtpTarget = new InetSocketAddress(remote.getAddress(), remote.getPort());
        }
        if (rtcpComp != null && rtcpComp.getSelectedPair() != null) {
            InetSocketAddress remote = rtcpComp.getSelectedPair().getRemoteCandidate().getTransportAddress();
            rtcpTarget = new InetSocketAddress(remote.getAddress(), remote.getPort());
        }

        return new MediaStreamTarget(rtpTarget, rtcpTarget);
    }

    // ---------------------------------------------------------------------------
    // 通话控制
    // ---------------------------------------------------------------------------

    /** 结束通话，释放所有资源 */
    public synchronized void end() {
        if (state == State.ENDED) return;

        try {
            if (audioStream != null) {
                try { audioStream.stop(); } catch (Exception ignored) {}
                try { audioStream.close(); } catch (Exception ignored) {}
                audioStream = null;
            }
            if (videoStream != null) {
                try { videoStream.stop(); } catch (Exception ignored) {}
                try { videoStream.close(); } catch (Exception ignored) {}
                videoStream = null;
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

    /** 切换静音/取消静音 */
    public void setMute(boolean mute) {
        if (audioStream != null) {
            audioStream.setMute(mute);
        }
    }

    /** 切换摄像头开/关 */
    public void setCameraEnabled(boolean enabled) {
        if (videoStream != null) {
            videoStream.setDirection(enabled ? MediaDirection.SENDRECV : MediaDirection.RECVONLY);
        }
    }
}
