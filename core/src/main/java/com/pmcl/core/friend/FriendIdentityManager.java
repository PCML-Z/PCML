package com.pmcl.core.friend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * 管理好友身份：生成、加载、持久化、二维码生成。
 * <p>
 * 数据目录：{@code ~/.pmcl/friend-data/}
 * <ul>
 *   <li>{@code identity.json} — 我的身份信息</li>
 *   <li>{@code avatar.png} — 头像（可选）</li>
 * </ul>
 */
public final class FriendIdentityManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataDir;
    private FriendIdentity identity;
    private String displayName;
    private String backgroundPath;
    private byte[] qrCodeBytes;
    /** QR 码原始矩阵（用于 Canvas 自定义渲染） */
    private boolean[] qrModules;
    private int qrSize;

    public FriendIdentityManager(Path dataDir) {
        this.dataDir = dataDir;
    }

    /** 初始化：从磁盘加载或生成新身份 */
    public void initialize() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建好友数据目录: " + dataDir, e);
        }

        Path identityFile = dataDir.resolve("identity.json");
        if (Files.exists(identityFile)) {
            loadIdentity(identityFile);
        } else {
            generateNewIdentity();
            saveIdentity();
        }

        generateQrCode();
    }

    /** 我的身份 ID */
    public FriendIdentity getIdentity() {
        return identity;
    }

    /** 显示名称 */
    public String getDisplayName() {
        return displayName;
    }

    /** 设置显示名称 */
    public void setDisplayName(String name) {
        this.displayName = name;
        saveIdentity();
    }

    /** 二维码 PNG 字节 */
    public byte[] getQrCodeBytes() {
        return qrCodeBytes;
    }

    /** QR 码矩阵数据：每个元素 true=深色模块, false=浅色模块 */
    public boolean[] getQrModules() {
        return qrModules;
    }

    /** QR 码矩阵边长（模块数） */
    public int getQrSize() {
        return qrSize;
    }

    /** 卡片背景图片路径（本地文件） */
    public String getBackgroundPath() {
        return backgroundPath;
    }

    /** 设置卡片背景图片路径并持久化 */
    public void setBackgroundPath(String path) {
        this.backgroundPath = path;
        saveIdentity();
    }

    /** 分享文本：用于生成二维码，"pmcl-friend:" 协议 */
    public String getShareText() {
        return "pmcl-friend:" + identity.toString() + ":" + urlEncode(displayName);
    }

    /** 从邀请文本解析好友身份 */
    public static IdentityInfo parseInvite(String invite) {
        if (invite == null || invite.isEmpty()) return null;
        String content;
        if (invite.startsWith("pmcl-friend:")) {
            content = invite.substring(12);
        } else {
            content = invite;
        }
        String[] parts = content.split(":", 2);
        String idStr = parts[0].trim();
        if (!FriendIdentity.isValid(idStr)) return null;
        FriendIdentity id = FriendIdentity.parse(idStr);
        String name = parts.length > 1 ? urlDecode(parts[1].trim()) : id.toString().substring(0, 10);
        return new IdentityInfo(id, name);
    }

    /** 从分享文本解析好友身份（便捷方法） */
    public static IdentityInfo fromShareText(String text) {
        return parseInvite(text);
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    private void generateNewIdentity() {
        String seed = UUID.randomUUID().toString() + System.nanoTime() + new SecureRandom().nextLong();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            this.identity = FriendIdentity.encode(hash);
        } catch (Exception e) {
            this.identity = FriendIdentity.fallback(seed);
        }
        this.displayName = System.getProperty("user.name", "Player");
    }

    private void loadIdentity(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> data = GSON.fromJson(json, type);
            String idStr = data.getOrDefault("id", "");
            if (!FriendIdentity.isValid(idStr)) {
                generateNewIdentity();
                return;
            }
            this.identity = FriendIdentity.parse(idStr);
            this.displayName = data.getOrDefault("name", System.getProperty("user.name", "Player"));
            this.backgroundPath = data.getOrDefault("bg", null);
        } catch (Exception e) {
            generateNewIdentity();
        }
    }

    private void saveIdentity() {
        try {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("id", identity.toString());
            data.put("name", displayName);
            if (backgroundPath != null && !backgroundPath.isEmpty()) {
                data.put("bg", backgroundPath);
            }
            String json = GSON.toJson(data);
            Files.writeString(dataDir.resolve("identity.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[FriendIdentity] 保存身份失败: " + e.getMessage());
        }
    }

    private void generateQrCode() {
        try {
            String text = getShareText();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300, hints);

            // 提取矩阵数据
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            this.qrSize = Math.max(w, h);
            this.qrModules = new boolean[qrSize * qrSize];
            for (int y = 0; y < qrSize; y++) {
                for (int x = 0; x < qrSize; x++) {
                    qrModules[y * qrSize + x] = matrix.get(x, y);
                }
            }

            // 同时生成 PNG（向后兼容）
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            this.qrCodeBytes = baos.toByteArray();
        } catch (WriterException | IOException e) {
            System.err.println("[FriendIdentity] 二维码生成失败: " + e.getMessage());
            this.qrCodeBytes = new byte[0];
            this.qrModules = new boolean[0];
            this.qrSize = 0;
        }
    }

    private static String urlEncode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append("%").append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }

    private static String urlDecode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------------------
    // 内部类型
    // ---------------------------------------------------------------------------

    /** 从分享文本解析出的好友信息 */
    public static final class IdentityInfo {
        public final FriendIdentity identity;
        public final String displayName;

        IdentityInfo(FriendIdentity identity, String displayName) {
            this.identity = identity;
            this.displayName = displayName;
        }
    }
}
