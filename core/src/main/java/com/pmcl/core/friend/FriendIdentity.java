package com.pmcl.core.friend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * 好友身份标识：XXXXX-XXXXX-XXXXX-XXXXX-XXXXX 格式。
 * <p>
 * 编码表排除了易混淆字符 {@code 0, 1, O, I}，共 32 个可用字符（Base32 Compatible）。
 * <p>
 * 生成方式：SHA-256({@code username + randomUUID + salt}) → 取前 125 bits → 编码为 25 个字符。
 */
public final class FriendIdentity {
    /** 每个段 5 字符，共 5 段 = 25 字符 */
    public static final int SEGMENT_LENGTH = 5;
    public static final int SEGMENT_COUNT = 5;
    public static final int TOTAL_LENGTH = SEGMENT_COUNT * SEGMENT_LENGTH; // 25

    /** Base32 不易混淆字符集（32 字符：A-Z 除去 O、0-9 除去 0/1） */
    static final char[] ALPHABET = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
            'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            '2', '3', '4', '5', '6', '7', '8', '9'
    };

    private final String raw;

    private FriendIdentity(String raw) {
        this.raw = raw;
    }

    /** 回退到基于用户名的伪随机 ID（向后兼容，仅用于迁移） */
    public static FriendIdentity fallback(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            return encode(hash);
        } catch (Exception e) {
            // 极度不可能：回退到 UUID 派生
            byte[] bytes = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
            return encode(bytes);
        }
    }

    /**
     * 基于种子确定性派生身份 ID。同一种子始终产生同一身份。
     * 用于基于 Minecraft 账户 UUID 派生好友身份，确保跨设备一致。
     */
    public static FriendIdentity derive(String seed) {
        return fallback(seed);
    }

    /** 获取原始字符串 */
    public String getRaw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FriendIdentity that = (FriendIdentity) o;
        return raw.equals(that.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /**
     * 从字符串解析已存在的身份。
     *
     * @param raw 25 字符的身份字符串（XXXXX-XXXXX-XXXXX-XXXXX-XXXXX）
     * @return 解析后的身份
     * @throws IllegalArgumentException 格式无效时抛出
     */
    public static FriendIdentity parse(String raw) {
        String cleaned = raw.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (cleaned.length() != TOTAL_LENGTH) {
            throw new IllegalArgumentException("身份 ID 长度必须为 " + TOTAL_LENGTH + " 字符，实际: " + cleaned.length());
        }
        for (char c : cleaned.toCharArray()) {
            if (!isValidChar(c)) {
                throw new IllegalArgumentException("无效字符 '" + c + "'（不允许 0/1/O/I）");
            }
        }
        // 标准化为带分隔符的格式
        return new FriendIdentity(format(cleaned));
    }

    /**
     * 验证一个字符串是否为有效的身份 ID。
     */
    public static boolean isValid(String raw) {
        if (raw == null) return false;
        String cleaned = raw.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (cleaned.length() != TOTAL_LENGTH) return false;
        for (char c : cleaned.toCharArray()) {
            if (!isValidChar(c)) return false;
        }
        return true;
    }

    /** 为给定的字节数组生成身份 ID */
    static FriendIdentity encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(TOTAL_LENGTH);
        int bitBuffer = 0;
        int bitsInBuffer = 0;

        for (byte b : bytes) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                int index = (bitBuffer >> bitsInBuffer) & 0x1F;
                sb.append(ALPHABET[index]);
            }
        }

        // 填充剩余不足 5 位的部分
        if (bitsInBuffer > 0) {
            int index = (bitBuffer << (5 - bitsInBuffer)) & 0x1F;
            sb.append(ALPHABET[index]);
        }

        // 确保恰好 25 字符
        while (sb.length() < TOTAL_LENGTH) {
            sb.append(ALPHABET[0]);
        }
        if (sb.length() > TOTAL_LENGTH) {
            sb.setLength(TOTAL_LENGTH);
        }

        return new FriendIdentity(format(sb.toString()));
    }

    private static boolean isValidChar(char c) {
        for (char valid : ALPHABET) {
            if (valid == c) return true;
        }
        return false;
    }

    static String format(String cleaned) {
        if (cleaned.length() != TOTAL_LENGTH) return cleaned;
        return cleaned.substring(0, 5) + "-" +
                cleaned.substring(5, 10) + "-" +
                cleaned.substring(10, 15) + "-" +
                cleaned.substring(15, 20) + "-" +
                cleaned.substring(20, 25);
    }
}
