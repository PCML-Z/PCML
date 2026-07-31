package com.pmcl.core.friend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile FriendIdentity identity;
    private volatile String displayName;
    private volatile String backgroundPath;
    private volatile byte[] qrCodeBytes;
    /** QR 码原始矩阵（用于 Canvas 自定义渲染） */
    private volatile boolean[] qrModules;
    private volatile int qrSize;
    /** 版本号：displayName/backgroundPath 变化时递增，供 Compose 观察 */
    private final AtomicLong version = new AtomicLong(0);

    private volatile byte[] ed25519Private;
    private volatile byte[] ed25519Public;
    private volatile byte[] x25519Private;
    private volatile byte[] x25519Public;

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
        ensureKeypairs();
        generateQrCode();
    }

    public byte[] getEd25519Public() { return ed25519Public != null ? ed25519Public.clone() : null; }
    public byte[] getX25519Public() { return x25519Public != null ? x25519Public.clone() : null; }
    byte[] getEd25519Private() { return ed25519Private; }
    byte[] getX25519Private() { return x25519Private; }

    public FriendSecureChannel.LocalIdentity asLocalIdentity() {
        return new FriendSecureChannel.LocalIdentity() {
            @Override public String identity() { return FriendIdentityManager.this.identity.toString(); }
            @Override public byte[] edPrivate() { return ed25519Private; }
            @Override public byte[] edPublic() { return ed25519Public; }
            @Override public byte[] xPrivate() { return x25519Private; }
            @Override public byte[] xPublic() { return x25519Public; }
        };
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
        if (name == null || name.equals(this.displayName)) return;
        this.displayName = name;
        saveIdentity();
        generateQrCode();
        version.incrementAndGet();
    }

    /**
     * 直接设置身份（用于基于账户 UUID 派生）。
     * 如果身份与当前相同则仅更新名称，否则覆盖身份、重新生成 QR。
     */
    public void setIdentity(FriendIdentity newIdentity, String displayName) {
        boolean identityChanged = this.identity == null || !newIdentity.equals(this.identity);
        boolean nameChanged = displayName != null && !displayName.equals(this.displayName);
        if (!identityChanged && !nameChanged) return;

        this.identity = newIdentity;
        if (displayName != null) this.displayName = displayName;
        if (identityChanged) {
            generateKeypairs();
        } else {
            ensureKeypairs();
        }
        saveIdentity();
        generateQrCode();
        version.incrementAndGet();
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
        version.incrementAndGet();
    }

    /** 获取版本号（displayName/backgroundPath 变化时递增） */
    public long getVersion() {
        return version.get();
    }

    /**
     * @deprecated 机器绑定 HMAC 已废弃；跨设备认证改用 Ed25519/X25519（见 {@link FriendSecureChannel}）。
     */
    @Deprecated
    public String deriveSecret() {
        if (identity == null) return null;
        try {
            String purposeKey = com.pmcl.core.auth.TokenEncryptor.derivePurposeKey("friend-identity");
            String seed = identity.toString() + "|" + purposeKey;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[FriendIdentity] 密钥派生失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 分享文本 / 二维码：{@code pmcl-friend:ID:name:edPub:xPub}
     * 公钥经带外分发，用于端到端认证与 ECDH（C9）。
     */
    public String getShareText() {
        if (identity == null) return "";
        ensureKeypairs();
        return "pmcl-friend:" + identity.toString()
                + ":" + urlEncode(displayName)
                + ":" + FriendCrypto.b64(ed25519Public)
                + ":" + FriendCrypto.b64(x25519Public);
    }

    /** 从邀请文本解析好友身份（含可选公钥） */
    public static IdentityInfo parseInvite(String invite) {
        if (invite == null || invite.isEmpty()) return null;
        String content;
        if (invite.startsWith("pmcl-friend:")) {
            content = invite.substring(12);
        } else {
            content = invite;
        }
        String[] parts = content.split(":", 4);
        String idStr = parts[0].trim();
        if (!FriendIdentity.isValid(idStr)) return null;
        FriendIdentity id = FriendIdentity.parse(idStr);
        String name = parts.length > 1 ? urlDecode(parts[1].trim()) : id.toString().replace("-", "").substring(0, 8);
        byte[] ed = null;
        byte[] x = null;
        if (parts.length >= 4) {
            try {
                ed = FriendCrypto.b64d(parts[2].trim());
                x = FriendCrypto.b64d(parts[3].trim());
            } catch (Exception ignored) {
                ed = null;
                x = null;
            }
        }
        return new IdentityInfo(id, name, ed, x);
    }

    /** 从分享文本解析好友身份（便捷方法） */
    public static IdentityInfo fromShareText(String text) {
        return parseInvite(text);
    }

    /**
     * 从图片解码二维码内容。支持 PNG/JPG 等常见格式。
     * @param image 已解码的图片
     * @return 解码出的文本，失败返回 null
     */
    public static String decodeQrCode(java.awt.image.BufferedImage image) {
        if (image == null) return null;
        try {
            var source = new BufferedImageLuminanceSource(image);
            var bitmap = new BinaryBitmap(new HybridBinarizer(source));
            var hints = new java.util.HashMap<DecodeHintType, Object>();
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            var reader = new MultiFormatReader();
            var result = reader.decode(bitmap, hints);
            return result.getText();
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            System.err.println("[FriendIdentity] 二维码解码失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从图片文件解码二维码内容。
     * @param file 图片文件
     * @return 解码出的文本，失败返回 null
     */
    public static String decodeQrCode(java.io.File file) {
        if (file == null || !file.exists()) return null;
        try {
            var image = ImageIO.read(file);
            return decodeQrCode(image);
        } catch (Exception e) {
            System.err.println("[FriendIdentity] 读取图片失败: " + e.getMessage());
            return null;
        }
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
        generateKeypairs();
    }

    private void ensureKeypairs() {
        if (ed25519Private != null && ed25519Public != null
                && x25519Private != null && x25519Public != null) {
            return;
        }
        generateKeypairs();
        saveIdentity();
    }

    private void generateKeypairs() {
        FriendCrypto.KeyBundle kb = FriendCrypto.generateKeyBundle();
        this.ed25519Private = kb.ed25519PrivatePkcs8;
        this.ed25519Public = kb.ed25519PublicSpki;
        this.x25519Private = kb.x25519PrivatePkcs8;
        this.x25519Public = kb.x25519PublicSpki;
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
            loadKeysFromMap(data);
        } catch (Exception e) {
            generateNewIdentity();
        }
    }

    private void loadKeysFromMap(Map<String, String> data) {
        try {
            String edPrivEnc = data.get("ed25519Priv");
            String edPub = data.get("ed25519Pub");
            String xPrivEnc = data.get("x25519Priv");
            String xPub = data.get("x25519Pub");
            if (edPrivEnc == null || edPub == null || xPrivEnc == null || xPub == null) {
                return;
            }
            String edPrivPlain = com.pmcl.core.auth.TokenEncryptor.decrypt(edPrivEnc);
            String xPrivPlain = com.pmcl.core.auth.TokenEncryptor.decrypt(xPrivEnc);
            if (edPrivPlain == null || xPrivPlain == null) return;
            this.ed25519Private = FriendCrypto.b64d(edPrivPlain);
            this.ed25519Public = FriendCrypto.b64d(edPub);
            this.x25519Private = FriendCrypto.b64d(xPrivPlain);
            this.x25519Public = FriendCrypto.b64d(xPub);
        } catch (Exception e) {
            System.err.println("[FriendIdentity] 加载密钥对失败，将重新生成: " + e.getMessage());
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
            if (ed25519Private != null && ed25519Public != null
                    && x25519Private != null && x25519Public != null) {
                String edPrivEnc = com.pmcl.core.auth.TokenEncryptor.encrypt(FriendCrypto.b64(ed25519Private));
                String xPrivEnc = com.pmcl.core.auth.TokenEncryptor.encrypt(FriendCrypto.b64(x25519Private));
                if (edPrivEnc == null || xPrivEnc == null) {
                    throw new IOException("私钥加密失败，拒绝明文落盘");
                }
                data.put("ed25519Priv", edPrivEnc);
                data.put("ed25519Pub", FriendCrypto.b64(ed25519Public));
                data.put("x25519Priv", xPrivEnc);
                data.put("x25519Pub", FriendCrypto.b64(x25519Public));
            }
            String json = GSON.toJson(data);
            Path target = dataDir.resolve("identity.json");
            Path tmp = dataDir.resolve("identity.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
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

    /** 从分享文本解析出的好友信息（含可选长期公钥） */
    public static final class IdentityInfo {
        public final FriendIdentity identity;
        public final String displayName;
        public final byte[] ed25519Public;
        public final byte[] x25519Public;

        IdentityInfo(FriendIdentity identity, String displayName) {
            this(identity, displayName, null, null);
        }

        IdentityInfo(FriendIdentity identity, String displayName, byte[] ed25519Public, byte[] x25519Public) {
            this.identity = identity;
            this.displayName = displayName;
            this.ed25519Public = ed25519Public;
            this.x25519Public = x25519Public;
        }

        public boolean hasPublicKeys() {
            return ed25519Public != null && x25519Public != null
                    && ed25519Public.length > 0 && x25519Public.length > 0;
        }
    }
}
