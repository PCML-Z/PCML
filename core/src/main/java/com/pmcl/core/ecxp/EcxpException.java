package com.pmcl.core.ecxp;

/** ECXP 应用安装或启动失败。code 对应 i18n 键 ecxp.error.&lt;code&gt;。 */
public final class EcxpException extends Exception {

    private final String code;
    private final String detail;

    public EcxpException(String code) {
        this(code, "");
    }

    public EcxpException(String code, String detail) {
        super(code);
        this.code = code == null ? "invalid_package" : code;
        this.detail = detail == null ? "" : detail;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
