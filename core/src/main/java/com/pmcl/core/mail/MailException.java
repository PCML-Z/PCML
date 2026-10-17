package com.pmcl.core.mail;

/** 邮箱错误。{@link #detail} 是服务器原文，不会带上密码。 */
public final class MailException extends Exception {
    public final String key;
    public final String detail;

    public MailException(String key, String detail) {
        super(key);
        this.key = key;
        this.detail = detail == null ? "" : detail;
    }
}
