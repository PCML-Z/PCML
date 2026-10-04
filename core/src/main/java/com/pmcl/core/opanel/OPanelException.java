package com.pmcl.core.opanel;

import java.io.IOException;

/** 面板请求失败。{@code code} 对应 servers.opanel.* 文案，detail 只放面板返回的短错误。 */
public final class OPanelException extends IOException {
    public final String code;
    public final String detail;

    public OPanelException(String code) {
        this(code, "");
    }

    public OPanelException(String code, String detail) {
        super(code);
        this.code = code == null ? "rejected" : code;
        this.detail = detail == null ? "" : detail;
    }
}
