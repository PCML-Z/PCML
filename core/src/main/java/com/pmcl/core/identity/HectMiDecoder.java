package com.pmcl.core.identity;

import com.pmcl.core.LauncherCore;
import java.util.List;

/**
 * HECT-MI 解码器：解析识别码格式并展示生成因子。
 *
 * <p>由于识别码基于 SHA-256 单向哈希生成，无法从码本身逆向提取因子。
 * 解码器通过重新收集当前环境的 8 个因子来"解码"：
 * <ul>
 *   <li>{@link #decode(LauncherCore)} — 收集当前环境因子 + 生成的识别码 + 格式解析</li>
 *   <li>{@link #verify(LauncherCore, String)} — 验证给定码是否属于当前环境</li>
 *   <li>{@link #parseFormat(String)} — 纯格式解析（长度/字符/分段校验），不依赖环境</li>
 * </ul>
 */
public final class HectMiDecoder {

    public static final int EXPECTED_DIGIT_COUNT = 19;
    public static final int EXPECTED_LETTER_COUNT = 275;
    public static final int EXPECTED_TOTAL_LENGTH = EXPECTED_DIGIT_COUNT + 3 + EXPECTED_LETTER_COUNT; // 297

    private HectMiDecoder() {}

    /** 解码结果 */
    public static final class DecodeResult {
        /** 当前环境生成的识别码 */
        public final String currentCode;
        /** 当前环境的 8 个因子详情 */
        public final List<HectMiGenerator.Factor> factors;
        /** 格式解析结果 */
        public final FormatInfo formatInfo;

        public DecodeResult(String currentCode, List<HectMiGenerator.Factor> factors, FormatInfo formatInfo) {
            this.currentCode = currentCode;
            this.factors = factors;
            this.formatInfo = formatInfo;
        }
    }

    /** 格式解析结果 */
    public static final class FormatInfo {
        /** 输入的原始码 */
        public final String rawCode;
        /** 是否格式合法 */
        public final boolean valid;
        /** 失败原因（valid=false 时有值） */
        public final String error;
        /** 数字部分（含连字符），如 "123456-789012-345678-9" */
        public final String digitSection;
        /** 字母部分（275 位大写字母） */
        public final String letterSection;
        /** 数字部分长度 */
        public final int digitLength;
        /** 字母部分长度 */
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

    /**
     * 解码：收集当前环境因子 + 生成识别码 + 格式解析。
     *
     * @param core 启动器内核实例
     * @return 解码结果
     */
    public static DecodeResult decode(LauncherCore core) {
        List<HectMiGenerator.Factor> factors = HectMiGenerator.collectFactors(core);
        String code = HectMiGenerator.generate(core);
        FormatInfo formatInfo = parseFormat(code);
        return new DecodeResult(code, factors, formatInfo);
    }

    /**
     * 验证给定的识别码是否属于当前环境。
     *
     * @param core 启动器内核实例
     * @param code 待验证的识别码
     * @return true 表示该码由当前环境生成
     */
    public static boolean verify(LauncherCore core, String code) {
        if (code == null || code.isBlank()) return false;
        String current = HectMiGenerator.generate(core);
        return current.equals(code.trim());
    }

    /**
     * 纯格式解析：校验长度、字符集、分段结构，不依赖运行环境。
     *
     * @param code 待解析的识别码
     * @return 格式解析结果
     */
    public static FormatInfo parseFormat(String code) {
        if (code == null || code.isBlank()) {
            return new FormatInfo("", false, "识别码为空", "", "", 0, 0);
        }

        String trimmed = code.trim();

        // 总长度校验：19 数字 + 3 连字符 + 275 字母 = 297
        if (trimmed.length() != EXPECTED_TOTAL_LENGTH) {
            return new FormatInfo(trimmed, false,
                    "长度不合法：期望 " + EXPECTED_TOTAL_LENGTH + " 字符，实际 " + trimmed.length() + " 字符",
                    "", "", 0, 0);
        }

        // 数字部分：XXXXXX-XXXXXX-XXXXXX-X（位置 0-21，含 3 个连字符）
        String digitSection = trimmed.substring(0, 22); // 6+1+6+1+6+1+1 = 22
        String letterSection = trimmed.substring(22);    // 275 字母

        // 校验数字部分格式：^\d{6}-\d{6}-\d{6}-\d$
        if (!digitSection.matches("\\d{6}-\\d{6}-\\d{6}-\\d")) {
            return new FormatInfo(trimmed, false,
                    "数字部分格式错误：期望 XXXXXX-XXXXXX-XXXXXX-X",
                    digitSection, letterSection,
                    countDigits(digitSection), letterSection.length());
        }

        // 校验字母部分：275 位大写字母
        if (!letterSection.matches("[A-Z]{275}")) {
            // 找到非法字符
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

    /** 计算字符串中数字字符的数量 */
    private static int countDigits(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) count++;
        }
        return count;
    }
}
