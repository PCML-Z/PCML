package com.pmcl.core.mail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.auth.TokenEncryptor;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 邮箱账户和本地草稿。密码加密后写在 {@code mail/accounts.json}。 */
public final class MailAccountStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_DRAFTS = 40;

    private final Path dir;
    private final Path accountsFile;
    private final Path draftsFile;

    public MailAccountStore(Path dir) {
        this.dir = dir;
        this.accountsFile = dir.resolve("accounts.json");
        this.draftsFile = dir.resolve("mail-drafts.json");
    }

    public static MailAccountStore open() {
        return new MailAccountStore(LauncherConfig.pmclHome().resolve("mail"));
    }

    public synchronized List<MailAccount> accounts() {
        List<MailAccount> out = new ArrayList<>();
        for (Stored stored : readAccounts()) {
            MailAccount account = stored.toAccount();
            if (!account.address.isBlank()) out.add(account);
        }
        return out;
    }

    public synchronized MailAccount find(String id) {
        if (id == null) return null;
        for (MailAccount account : accounts()) {
            if (id.equals(account.id)) return account;
        }
        return null;
    }

    /** 校验并补全。已有账户的密码留空时沿用原来的密码。 */
    public synchronized MailAccount prepare(MailAccount input) throws MailException {
        if (input == null) throw new MailException("friend.mail.err_account", "");
        MailAccount previous = find(input.id);
        MailAccount account = new MailAccount();
        account.id = input.id == null || input.id.isBlank() ? UUID.randomUUID().toString() : input.id.trim();
        account.address = input.address == null ? "" : input.address.trim();
        account.username = input.username == null || input.username.isBlank() ? account.address : input.username.trim();
        String password = input.password == null ? "" : input.password;
        if (password.isEmpty() && previous != null) password = previous.password;
        account.password = password;
        try {
            account.imapHost = MailClient.normalizeHost(input.imapHost);
            account.smtpHost = MailClient.normalizeHost(input.smtpHost);
        } catch (RuntimeException e) {
            throw new MailException("friend.mail.err_account", "");
        }
        account.imapPort = input.imapPort;
        account.smtpPort = input.smtpPort;
        account.imapSecurity = security(input.imapSecurity);
        account.smtpSecurity = security(input.smtpSecurity);
        if (!MailCodec.validAddress(account.address) || !cleanSecret(account.username) || !cleanSecret(account.password)
                || account.imapPort < 1 || account.imapPort > 65535
                || account.smtpPort < 1 || account.smtpPort > 65535) {
            throw new MailException("friend.mail.err_account", "");
        }
        return account;
    }

    public synchronized MailAccount save(MailAccount input) throws MailException {
        MailAccount account = prepare(input);
        String encrypted = TokenEncryptor.encrypt(account.password);
        if (encrypted == null || encrypted.isEmpty() || !TokenEncryptor.isEncrypted(encrypted)) {
            throw new MailException("friend.mail.err_account", "");
        }
        List<Stored> all = readAccounts();
        all.removeIf(stored -> account.id.equals(stored.id));
        Stored stored = Stored.from(account);
        stored.password = encrypted;
        all.add(stored);
        try {
            write(accountsFile, GSON.toJson(all));
        } catch (IOException e) {
            throw new MailException("friend.mail.err_account", "");
        }
        return copy(account);
    }

    public synchronized void delete(String id) {
        if (id == null || id.isBlank()) return;
        List<Stored> all = readAccounts();
        all.removeIf(stored -> id.equals(stored.id));
        try {
            write(accountsFile, GSON.toJson(all));
            List<Draft> drafts = readDrafts();
            drafts.removeIf(draft -> id.equals(draft.accountId));
            write(draftsFile, GSON.toJson(drafts));
        } catch (IOException e) {
            System.err.println("[Mail] 删除邮箱账户失败");
        }
    }

    public synchronized List<Draft> drafts(String accountId) {
        List<Draft> out = new ArrayList<>();
        for (Draft draft : readDrafts()) {
            if (accountId != null && accountId.equals(draft.accountId)) out.add(draft);
        }
        out.sort(Comparator.comparingLong((Draft draft) -> draft.updatedAt).reversed());
        return out;
    }

    public synchronized Draft saveDraft(String id, String accountId, String to, String cc,
                                        String subject, String body) throws MailException {
        if (accountId == null || find(accountId) == null) throw new MailException("friend.mail.err_account", "");
        List<Draft> all = readDrafts();
        Draft draft = null;
        if (id != null) {
            for (Draft existing : all) {
                if (id.equals(existing.id)) draft = existing;
            }
        }
        if (draft == null) {
            draft = new Draft();
            draft.id = UUID.randomUUID().toString();
            all.add(draft);
        }
        draft.accountId = accountId;
        draft.to = MailCodec.clip(to, 500).replace('\n', ' ');
        draft.cc = MailCodec.clip(cc, 500).replace('\n', ' ');
        draft.subject = MailCodec.clip(subject, MailCodec.SUBJECT_MAX).replace('\n', ' ');
        draft.body = MailCodec.clip(body, MailCodec.BODY_MAX);
        draft.updatedAt = System.currentTimeMillis();
        all.sort(Comparator.comparingLong((Draft item) -> item.updatedAt).reversed());
        if (all.size() > MAX_DRAFTS) all.subList(MAX_DRAFTS, all.size()).clear();
        try {
            write(draftsFile, GSON.toJson(all));
        } catch (IOException e) {
            throw new MailException("friend.mail.err_account", "");
        }
        return draft;
    }

    public synchronized void deleteDraft(String id) {
        List<Draft> all = readDrafts();
        all.removeIf(draft -> draft.id != null && draft.id.equals(id));
        try {
            write(draftsFile, GSON.toJson(all));
        } catch (IOException ignored) {
            System.err.println("[Mail] 删除草稿失败");
        }
    }

    private List<Stored> readAccounts() {
        if (!Files.exists(accountsFile)) return new ArrayList<>();
        try {
            String json = Files.readString(accountsFile, StandardCharsets.UTF_8);
            Type type = new TypeToken<List<Stored>>() {}.getType();
            List<Stored> stored = GSON.fromJson(json, type);
            List<Stored> out = new ArrayList<>();
            if (stored == null) return out;
            boolean rewrite = false;
            for (Stored item : stored) {
                if (item == null || item.address == null) continue;
                if (item.password != null && !item.password.isEmpty() && !TokenEncryptor.isEncrypted(item.password)) {
                    String encrypted = TokenEncryptor.encrypt(item.password);
                    if (TokenEncryptor.isEncrypted(encrypted)) {
                        item.password = encrypted;
                        rewrite = true;
                    }
                }
                out.add(item);
            }
            if (rewrite) {
                try {
                    write(accountsFile, GSON.toJson(out));
                } catch (IOException e) {
                    System.err.println("[Mail] 加密保存邮箱账户失败");
                }
            }
            return out;
        } catch (Exception e) {
            System.err.println("[Mail] 读取邮箱账户失败");
            return new ArrayList<>();
        }
    }

    private List<Draft> readDrafts() {
        if (!Files.exists(draftsFile)) return new ArrayList<>();
        try {
            String json = Files.readString(draftsFile, StandardCharsets.UTF_8);
            Type type = new TypeToken<List<Draft>>() {}.getType();
            List<Draft> drafts = GSON.fromJson(json, type);
            return drafts == null ? new ArrayList<>() : new ArrayList<>(drafts);
        } catch (Exception e) {
            System.err.println("[Mail] 读取草稿失败");
            return new ArrayList<>();
        }
    }

    private void write(Path file, String json) throws IOException {
        Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        TokenEncryptor.hardenFilePermissions(file);
    }

    private static boolean cleanSecret(String value) {
        if (value == null || value.isBlank() || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127) return false;
        }
        return true;
    }

    private static String security(String value) {
        return "starttls".equals(value) ? "starttls" : "ssl";
    }

    private static MailAccount copy(MailAccount src) {
        MailAccount account = new MailAccount();
        account.id = src.id;
        account.address = src.address;
        account.username = src.username;
        account.password = src.password;
        account.imapHost = src.imapHost;
        account.imapPort = src.imapPort;
        account.imapSecurity = src.imapSecurity;
        account.smtpHost = src.smtpHost;
        account.smtpPort = src.smtpPort;
        account.smtpSecurity = src.smtpSecurity;
        return account;
    }

    public static final class Draft {
        public String id = "";
        public String accountId = "";
        public String to = "";
        public String cc = "";
        public String subject = "";
        public String body = "";
        public long updatedAt;
    }

    private static final class Stored {
        String id;
        String address;
        String username;
        String password;
        String imapHost;
        int imapPort;
        String imapSecurity;
        String smtpHost;
        int smtpPort;
        String smtpSecurity;

        static Stored from(MailAccount account) {
            Stored stored = new Stored();
            stored.id = account.id;
            stored.address = account.address;
            stored.username = account.username;
            stored.imapHost = account.imapHost;
            stored.imapPort = account.imapPort;
            stored.imapSecurity = account.imapSecurity;
            stored.smtpHost = account.smtpHost;
            stored.smtpPort = account.smtpPort;
            stored.smtpSecurity = account.smtpSecurity;
            return stored;
        }

        MailAccount toAccount() {
            MailAccount account = new MailAccount();
            account.id = id == null ? "" : id;
            account.address = address == null ? "" : address;
            account.username = username == null || username.isBlank() ? account.address : username;
            String plain = password == null ? "" : TokenEncryptor.decrypt(password);
            account.password = plain == null ? "" : plain;
            account.imapHost = imapHost == null ? "" : imapHost;
            account.imapPort = imapPort;
            account.imapSecurity = imapSecurity == null ? "ssl" : imapSecurity;
            account.smtpHost = smtpHost == null ? "" : smtpHost;
            account.smtpPort = smtpPort;
            account.smtpSecurity = smtpSecurity == null ? "ssl" : smtpSecurity;
            return account;
        }
    }
}
