package com.pmcl.core.kernel;

/** 内核安装或启动失败。code 对应 i18n 键 kernel.error.&lt;code&gt;。 */
public final class KernelException extends Exception {

    private final String code;
    private final String detail;

    public KernelException(String code) {
        this(code, "", null);
    }

    public KernelException(String code, String detail) {
        this(code, detail, null);
    }

    public KernelException(String code, Throwable cause) {
        this(code, "", cause);
    }

    private KernelException(String code, String detail, Throwable cause) {
        super(code, cause);
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
