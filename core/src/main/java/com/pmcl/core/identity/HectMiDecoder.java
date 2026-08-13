package com.pmcl.core.identity;

import com.pmcl.core.LauncherCore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HECT-MI 解码器：从识别码逆向还原 8 个因子的原始数据。
 *
 * <p>识别码采用可逆编码（DEFLATE + Base-26），解码器可以完整还原因子数据：
 * <ol>
 *   <li>解析 19 位数字段：CRC32 校验 + 压缩长度 + 原始长度 + 编码标志</li>
 *   <li>Base-26 解码 275 位字母段 → 压缩字节数组</li>
 *   <li>DEFLATE 解压 → 原始 payload 字符串</li>
 *   <li>按 \n 分割为 13 个因子值</li>
 *   <li>CRC32 校验验证数据完整性</li>
 * </ol>
 *
 * <p>flag=1 时为哈希回退模式，无法解码因子数据。
 */
public final class HectMiDecoder {

    public static final int EXPECTED_DIGIT_COUNT = 19;
    public static final int EXPECTED_LETTER_COUNT = 275;
    public static final int EXPECTED_TOTAL_LENGTH = EXPECTED_DIGIT_COUNT + 3 + EXPECTED_LETTER_COUNT; // 297

    private HectMiDecoder() {}

    // ===== 解码结果类型 =====

    /** 解码出的因子数据 */
    public static final class DecodedData {
        /** 是否可解码（flag=0 = 可解码，flag=1 = 哈希回退不可解码） */
        public final boolean decodable;
        /** 不可解码时的错误信息 */
        public final String error;
        /** 13 个因子的标签（中英文） */
        public final String[][] labels;
        /** 13 个因子的解码值 */
        public final List<String> values;
        /** CRC32 校验是否通过 */
        public final boolean crcValid;

        private DecodedData(boolean decodable, String error, String[][] labels,
                            List<String> values, boolean crcValid) {
            this.decodable = decodable;
            this.error = error;
            this.labels = labels;
            this.values = values;
            this.crcValid = crcValid;
        }

        static DecodedData error(String msg) {
            return new DecodedData(false, msg, null, null, false);
        }

        static DecodedData ok(List<String> values, boolean crcValid) {
            return new DecodedData(true, null, HectMiGenerator.FACTOR_LABELS, values, crcValid);
        }
    }

    /** 格式解析结果 */
    public static final class FormatInfo {
        public final String rawCode;
        public final boolean valid;
        public final String error;
        public final String digitSection;
        public final String letterSection;
        public final int digitLength;
        public final int letterLength;

        public FormatInfo(String rawCode, boolean valid, String error,
                          String digitSection, String letterSection,
                          int digitLength, int letterLength) {
            this.rawCode = rawCode;
            this.valid = valid;
            this.error = error;
            this.digitSection = digitSection;
            this.letterSection = letterSection;
            this.digitLength = digitLength;
            this.letterLength = letterLength;
        }
    }

    // ===== 核心方法 =====

    /**
     * 解码 HECT-MI 识别码，还原 13 个因子值。
     *
     * @param code HECT-MI 识别码
     * @return 解码结果（DecodedData.decodable=true 时 values 非空）
     */
    public static DecodedData decodeFactors(String code) {
        // 先校验格式
        FormatInfo fmt = parseFormat(code);
        if (!fmt.valid) {
            return DecodedData.error(fmt.error);
        }

        // 解析数字段（去除连字符）
        String rawDigits = fmt.digitSection.replace("-", "");
        long expectedCrc = Long.parseLong(rawDigits.substring(0, 6));
        int compressedLen = Integer.parseInt(rawDigits.substring(6, 12));
        int originalLen = Integer.parseInt(rawDigits.substring(12, 18));
        int flag = rawDigits.charAt(18) - '0';

        if (flag == 1) {
            return DecodedData.error("此识别码使用哈希回退模式生成，无法解码因子数据");
        }

        if (flag != 0) {
            return DecodedData.error("未知的编码标志: " + flag);
        }

        // Base-26 解码字母段
        byte[] compressed = HectMiGenerator.base26Decode(fmt.letterSection, compressedLen);

        // DEFLATE 解压
        byte[] decompressed = HectMiGenerator.inflate(compressed, originalLen);
        if (decompressed.length == 0) {
            return DecodedData.error("解压失败：数据可能已损坏");
        }

        // CRC32 校验
        long actualCrc = HectMiGenerator.crc32(decompressed) % 1000000;
        boolean crcValid = (actualCrc == expectedCrc);

        // 转为字符串并分割
        String payload = new String(decompressed, StandardCharsets.UTF_8);
        String[] parts = payload.split("\n", -1);

        List<String> values = new ArrayList<>();
        for (String part : parts) {
            values.add(part);
        }

        // 补齐到 13 个值（防止数据不完整）
        while (values.size() < HectMiGenerator.VALUE_COUNT) {
            values.add("");
        }

        return DecodedData.ok(values, crcValid);
    }

    /**
     * 验证给定的识别码是否属于当前环境。
     */
    public static boolean verify(LauncherCore core, String code) {
        if (code == null || code.isBlank()) return false;
        String current = HectMiGenerator.generate(core);
        return current.equals(code.trim());
    }

    /**
     * 纯格式解析：校验长度、字符集、分段结构。
     */
    public static FormatInfo parseFormat(String code) {
        if (code == null || code.isBlank()) {
            return new FormatInfo("", false, "识别码为空", "", "", 0, 0);
        }

        String trimmed = code.trim();

        if (trimmed.length() != EXPECTED_TOTAL_LENGTH) {
            return new FormatInfo(trimmed, false,
                    "长度不合法：期望 " + EXPECTED_TOTAL_LENGTH + " 字符，实际 " + trimmed.length() + " 字符",
                    "", "", 0, 0);
        }

        String digitSection = trimmed.substring(0, 22);
        String letterSection = trimmed.substring(22);

        if (!digitSection.matches("\\d{6}-\\d{6}-\\d{6}-\\d")) {
            return new FormatInfo(trimmed, false,
                    "数字部分格式错误：期望 XXXXXX-XXXXXX-XXXXXX-X",
                    digitSection, letterSection,
                    countDigits(digitSection), letterSection.length());
        }

        if (!letterSection.matches("[A-Z]{275}")) {
            int badIdx = -1;
            char badChar = 0;
            for (int i = 0; i < letterSection.length(); i++) {
                char c = letterSection.charAt(i);
                if (c < 'A' || c > 'Z') {
                    badIdx = i;
                    badChar = c;
                    break;
                }
            }
            String errMsg = badIdx >= 0
                    ? "字母部分含非法字符 '" + badChar + "'（位置 " + badIdx + "）"
                    : "字母部分长度错误：期望 275 位大写字母，实际 " + letterSection.length();
            return new FormatInfo(trimmed, false, errMsg,
                    digitSection, letterSection,
                    countDigits(digitSection), letterSection.length());
        }

        return new FormatInfo(trimmed, true, null,
                digitSection, letterSection,
                EXPECTED_DIGIT_COUNT, EXPECTED_LETTER_COUNT);
    }

    private static int countDigits(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) count++;
        }
        return count;
    }
}
