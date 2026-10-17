package com.pmcl.core.mail;

import java.util.List;
import java.util.Locale;

/** 一个互联网邮箱账户。{@link #toString()} 不包含密码。 */
public final class MailAccount {
    public String id = "";
    public String address = "";
    public String username = "";
    public String password = "";
    public String imapHost = "";
    public int imapPort = 993;
    public String imapSecurity = "ssl";
    public String smtpHost = "";
    public int smtpPort = 465;
    public String smtpSecurity = "ssl";

    @Override
    public String toString() {
        return "MailAccount{" + address + "}";
    }

    public static final class Preset {
        public final String id;
        public final String labelKey;
        public final String imapHost;
        public final int imapPort;
        public final String imapSecurity;
        public final String smtpHost;
        public final int smtpPort;
        public final String smtpSecurity;

        private Preset(String id, String labelKey, String imapHost, int imapPort, String imapSecurity,
                       String smtpHost, int smtpPort, String smtpSecurity) {
            this.id = id;
            this.labelKey = labelKey;
            this.imapHost = imapHost;
            this.imapPort = imapPort;
            this.imapSecurity = imapSecurity;
            this.smtpHost = smtpHost;
            this.smtpPort = smtpPort;
            this.smtpSecurity = smtpSecurity;
        }

        public static final Preset QQ = new Preset("qq", "friend.mail.preset.qq",
                "imap.qq.com", 993, "ssl", "smtp.qq.com", 465, "ssl");
        public static final Preset NETEASE_163 = new Preset("163", "friend.mail.preset.163",
                "imap.163.com", 993, "ssl", "smtp.163.com", 465, "ssl");
        public static final Preset NETEASE_126 = new Preset("126", "friend.mail.preset.126",
                "imap.126.com", 993, "ssl", "smtp.126.com", 465, "ssl");
        public static final Preset GMAIL = new Preset("gmail", "friend.mail.preset.gmail",
                "imap.gmail.com", 993, "ssl", "smtp.gmail.com", 587, "starttls");
        public static final Preset OUTLOOK = new Preset("outlook", "friend.mail.preset.outlook",
                "outlook.office365.com", 993, "ssl", "smtp.office365.com", 587, "starttls");
        public static final Preset ICLOUD = new Preset("icloud", "friend.mail.preset.icloud",
                "imap.mail.me.com", 993, "ssl", "smtp.mail.me.com", 587, "starttls");
        public static final Preset CUSTOM = new Preset("custom", "friend.mail.preset.custom",
                "", 993, "ssl", "", 465, "ssl");

        public static List<Preset> all() {
            return List.of(QQ, NETEASE_163, NETEASE_126, GMAIL, OUTLOOK, ICLOUD, CUSTOM);
        }

        public static Preset byId(String id) {
            for (Preset preset : all()) {
                if (preset.id.equals(id)) return preset;
            }
            return CUSTOM;
        }

        public static Preset forAddress(String address) {
            if (address == null) return null;
            int at = address.lastIndexOf('@');
            if (at < 0 || at >= address.length() - 1) return null;
            String domain = address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
            String id = switch (domain) {
                case "qq.com", "foxmail.com" -> "qq";
                case "163.com" -> "163";
                case "126.com" -> "126";
                case "gmail.com", "googlemail.com" -> "gmail";
                case "outlook.com", "hotmail.com", "live.com" -> "outlook";
                case "icloud.com", "me.com", "mac.com" -> "icloud";
                default -> null;
            };
            return id == null ? null : byId(id);
        }
    }
}
