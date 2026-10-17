package com.pmcl.core.mail;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMAP 收信和 SMTP 发信。密码只出现在发往用户自己填写的服务器的口令命令里，不写日志。
 */
public final class MailClient {
    public static final int LIST_LIMIT = 40;
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 20_000;
    private static final int MAX_LINE = 16_384;
    private static final int MAX_LITERAL = 1_500_000;
    private static final Pattern LITERAL = Pattern.compile("\\{(\\d+)}\\s*$");
    private static final Pattern UID = Pattern.compile("UID (\\d+)");
    private static final Pattern FLAGS = Pattern.compile("FLAGS \\(([^)]*)\\)");

    private MailClient() {}

    public static final class Folder {
        public final String name;
        public final String role;

        public Folder(String name, String role) {
            this.name = name;
            this.role = role;
        }
    }

    public static final class Header {
        public final long uid;
        public final String from;
        public final String subject;
        public final String date;
        public final boolean unread;

        public Header(long uid, String from, String subject, String date, boolean unread) {
            this.uid = uid;
            this.from = from == null ? "" : from;
            this.subject = subject == null ? "" : subject;
            this.date = date == null ? "" : date;
            this.unread = unread;
        }
    }

    public static final class Letter {
        public final long uid;
        public final String from;
        public final String to;
        public final String cc;
        public final String subject;
        public final String date;
        public final String messageId;
        public final String text;
        public final boolean fromHtml;
        public final List<MailCodec.Attachment> attachments;

        Letter(long uid, MailCodec.Parsed parsed) {
            this.uid = uid;
            this.from = parsed.from;
            this.to = parsed.to;
            this.cc = parsed.cc;
            this.subject = parsed.subject;
            this.date = parsed.date;
            this.messageId = parsed.messageId;
            this.text = parsed.text;
            this.fromHtml = parsed.fromHtml;
            this.attachments = parsed.attachments;
        }
    }

    public static final class Session implements AutoCloseable {
        private final MailAccount account;
        private final Wire wire;
        private final boolean move;
        private final boolean uidPlus;
        private String trash = "";
        private volatile boolean open = true;

        private Session(MailAccount account, Wire wire, boolean move, boolean uidPlus) {
            this.account = account;
            this.wire = wire;
            this.move = move;
            this.uidPlus = uidPlus;
        }

        public static Session open(MailAccount account) throws MailException {
            if (account == null || account.username == null || account.username.isBlank()
                    || account.password == null || account.password.isEmpty()) {
                throw new MailException("friend.mail.err_account", "");
            }
            Wire wire = null;
            try {
                wire = Wire.open(account.imapHost, account.imapPort, account.imapSecurity);
                String greeting = wire.readLine();
                if (greeting == null || !(greeting.startsWith("* OK") || greeting.startsWith("* PREAUTH"))) {
                    throw new MailException("friend.mail.err_protocol", MailCodec.safeDetail(greeting, account.password));
                }
                if ("starttls".equals(account.imapSecurity)) {
                    Reply cap = command(wire, account, "CAPABILITY");
                    if (!cap.text.toUpperCase(Locale.ROOT).contains("STARTTLS")) {
                        throw new MailException("friend.mail.err_tls", "");
                    }
                    Reply tls = command(wire, account, "STARTTLS");
                    if (!tls.ok) throw new MailException("friend.mail.err_tls", tls.detail(account.password));
                    wire.upgrade(account.imapHost);
                }
                Reply login = command(wire, account, "LOGIN " + MailCodec.imapQuote(account.username)
                        + " " + MailCodec.imapQuote(account.password));
                if (!login.ok) throw new MailException("friend.mail.err_auth", login.detail(account.password));
                command(wire, account, "ID (\"name\" \"PMCL\" \"version\" \"2.2\" \"vendor\" \"PMCL\")");
                Reply after = command(wire, account, "CAPABILITY");
                String caps = after.text.toUpperCase(Locale.ROOT);
                return new Session(account, wire, caps.contains(" MOVE") || caps.contains("MOVE "),
                        caps.contains("UIDPLUS"));
            } catch (MailException e) {
                if (wire != null) wire.close();
                throw e;
            } catch (IOException e) {
                if (wire != null) wire.close();
                throw io(e);
            }
        }

        public synchronized List<Folder> folders() throws MailException {
            ensureOpen();
            try {
                Reply reply = command("LIST " + MailCodec.imapQuote("") + " " + MailCodec.imapQuote("*"));
                if (!reply.ok) throw new MailException("friend.mail.err_protocol", reply.detail(account.password));
                List<Folder> folders = new ArrayList<>();
                for (String line : reply.lines) {
                    if (!line.regionMatches(true, 0, "* LIST ", 0, 7)) continue;
                    Folder folder = parseList(line);
                    if (folder != null) folders.add(folder);
                    if ("trash".equals(folder == null ? "" : folder.role) && trash.isEmpty()) trash = folder.name;
                }
                boolean hasInbox = folders.stream().anyMatch(folder -> "inbox".equals(folder.role));
                if (!hasInbox) folders.add(new Folder("INBOX", "inbox"));
                folders.sort(Comparator.comparingInt((Folder folder) -> roleOrder(folder.role))
                        .thenComparing(folder -> folder.name, String.CASE_INSENSITIVE_ORDER));
                return folders;
            } catch (IOException e) {
                open = false;
                throw io(e);
            }
        }

        public synchronized List<Header> messages(String folder) throws MailException {
            ensureOpen();
            try {
                select(folder);
                Reply search = command("UID SEARCH UNDELETED");
                if (!search.ok) throw new MailException("friend.mail.err_protocol", search.detail(account.password));
                List<Long> uids = new ArrayList<>();
                for (String line : search.lines) {
                    if (!line.regionMatches(true, 0, "* SEARCH", 0, 8)) continue;
                    for (String token : line.substring(8).trim().split("\\s+")) {
                        if (token.isEmpty()) continue;
                        try {
                            uids.add(Long.parseLong(token));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (uids.size() > LIST_LIMIT) uids = new ArrayList<>(uids.subList(uids.size() - LIST_LIMIT, uids.size()));
                if (uids.isEmpty()) return List.of();
                StringBuilder set = new StringBuilder();
                for (int i = 0; i < uids.size(); i++) {
                    if (i > 0) set.append(',');
                    set.append(uids.get(i));
                }
                Reply fetched = command("UID FETCH " + set
                        + " (UID FLAGS BODY.PEEK[HEADER.FIELDS (FROM TO CC SUBJECT DATE MESSAGE-ID)])");
                if (!fetched.ok) throw new MailException("friend.mail.err_protocol", fetched.detail(account.password));
                List<Header> headers = parseHeaders(fetched);
                headers.sort(Comparator.comparingLong((Header header) -> header.uid).reversed());
                return headers;
            } catch (IOException e) {
                open = false;
                throw io(e);
            }
        }

        public synchronized Letter read(String folder, long uid) throws MailException {
            ensureOpen();
            try {
                select(folder);
                Reply fetched = command("UID FETCH " + uid + " (BODY.PEEK[])");
                if (!fetched.ok || fetched.literals.isEmpty()) {
                    throw new MailException("friend.mail.err_protocol", fetched.detail(account.password));
                }
                Letter letter = new Letter(uid, MailCodec.parseMessage(fetched.literals.get(fetched.literals.size() - 1)));
                command("UID STORE " + uid + " +FLAGS.SILENT (\\Seen)");
                return letter;
            } catch (IOException e) {
                open = false;
                throw io(e);
            }
        }

        public synchronized void moveToTrash(String folder, long uid) throws MailException {
            ensureOpen();
            try {
                select(folder);
                if (!trash.isEmpty() && !trash.equalsIgnoreCase(folder) && move) {
                    Reply reply = command("UID MOVE " + uid + " " + MailCodec.imapQuote(trash));
                    if (!reply.ok) throw new MailException("friend.mail.err_protocol", reply.detail(account.password));
                    return;
                }
                if (!trash.isEmpty() && !trash.equalsIgnoreCase(folder)) {
                    Reply copy = command("UID COPY " + uid + " " + MailCodec.imapQuote(trash));
                    if (!copy.ok) throw new MailException("friend.mail.err_protocol", copy.detail(account.password));
                }
                Reply deleted = command("UID STORE " + uid + " +FLAGS.SILENT (\\Deleted)");
                if (!deleted.ok) throw new MailException("friend.mail.err_protocol", deleted.detail(account.password));
                if (uidPlus) command("UID EXPUNGE " + uid);
            } catch (IOException e) {
                open = false;
                throw io(e);
            }
        }

        public synchronized void saveDraft(String to, String cc, String subject, String body) throws MailException {
            ensureOpen();
            String drafts = draftsFolder();
            if (drafts.isEmpty()) throw new MailException("friend.mail.err_protocol", "");
            byte[] message = MailCodec.draftMessage(account.address, to, cc, subject, body);
            try {
                String tag = "A" + wire.nextTag();
                wire.writeLine(tag + " APPEND " + MailCodec.imapQuote(drafts) + " (\\Draft) {" + message.length + "}");
                String cont = wire.readLine();
                if (cont == null || !cont.startsWith("+")) {
                    throw new MailException("friend.mail.err_protocol", MailCodec.safeDetail(cont, account.password));
                }
                wire.writeRaw(message);
                wire.writeRaw(new byte[]{'\r', '\n'});
                Reply reply = Reply.read(wire, tag);
                if (!reply.ok) throw new MailException("friend.mail.err_protocol", reply.detail(account.password));
            } catch (IOException e) {
                open = false;
                throw io(e);
            }
        }

        public String trashFolder() {
            return trash;
        }

        @Override
        public void close() {
            open = false;
            try {
                wire.writeLine("LOGOUT");
            } catch (IOException ignored) {}
            wire.close();
        }

        private String draftsFolder() throws MailException {
            for (Folder folder : folders()) {
                if ("drafts".equals(folder.role)) return folder.name;
            }
            return "";
        }

        private void select(String folder) throws IOException, MailException {
            Reply reply = command("SELECT " + MailCodec.imapQuote(folder));
            if (!reply.ok) throw new MailException("friend.mail.err_protocol", reply.detail(account.password));
        }

        private Reply command(String args) throws IOException, MailException {
            return command(wire, account, args);
        }

        private void ensureOpen() throws MailException {
            if (!open) throw new MailException("friend.mail.err_connect", "");
        }

        private static Reply command(Wire wire, MailAccount account, String args) throws IOException, MailException {
            String tag = "A" + wire.nextTag();
            wire.writeLine(tag + " " + args);
            return Reply.read(wire, tag);
        }
    }

    public static void send(MailAccount account, String to, String cc, String subject,
                            String body, String inReplyTo) throws MailException {
        List<String> tos = MailCodec.addresses(to);
        List<String> ccs = MailCodec.addresses(cc);
        if (account == null || account.username == null || account.password == null || account.password.isEmpty()) {
            throw new MailException("friend.mail.err_account", "");
        }
        if (tos.isEmpty()) throw new MailException("friend.mail.err_address", "");
        for (String address : ccs) {
            if (!MailCodec.validAddress(address)) throw new MailException("friend.mail.err_address", "");
        }
        String text = MailCodec.clip(body, MailCodec.BODY_MAX);
        String title = MailCodec.clip(subject, MailCodec.SUBJECT_MAX);
        if (title.isEmpty() && text.isEmpty()) throw new MailException("friend.mail.need_text", "");
        Wire wire = null;
        try {
            wire = Wire.open(account.smtpHost, account.smtpPort, account.smtpSecurity);
            expect(wire.readSmtp(), '2', account);
            if ("starttls".equals(account.smtpSecurity)) {
                wire.writeLine("EHLO pmcl.local");
                expect(wire.readSmtp(), '2', account);
                wire.writeLine("STARTTLS");
                expect(wire.readSmtp(), '2', account);
                wire.upgrade(account.smtpHost);
            }
            wire.writeLine("EHLO pmcl.local");
            String ehlo = wire.readSmtp();
            expect(ehlo, '2', account);
            String upper = ehlo.toUpperCase(Locale.ROOT);
            if (!MailCodec.validAddress(account.address)) {
                throw new MailException("friend.mail.err_address", "");
            }
            if (upper.contains("AUTH") && upper.contains("PLAIN")) {
                String token = "\0" + account.username + "\0" + account.password;
                String b64 = java.util.Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                wire.writeLine("AUTH PLAIN");
                expect(wire.readSmtp(), '3', account);
                wire.writeLine(b64);
                expectAuth(wire.readSmtp(), account);
            } else {
                wire.writeLine("AUTH LOGIN");
                expect(wire.readSmtp(), '3', account);
                wire.writeLine(java.util.Base64.getEncoder().encodeToString(account.username.getBytes(StandardCharsets.UTF_8)));
                expect(wire.readSmtp(), '3', account);
                wire.writeLine(java.util.Base64.getEncoder().encodeToString(account.password.getBytes(StandardCharsets.UTF_8)));
                expectAuth(wire.readSmtp(), account);
            }
            wire.writeLine("MAIL FROM:<" + account.address + ">");
            expect(wire.readSmtp(), '2', account);
            for (String address : tos) {
                wire.writeLine("RCPT TO:<" + address + ">");
                expect(wire.readSmtp(), '2', account);
            }
            for (String address : ccs) {
                wire.writeLine("RCPT TO:<" + address + ">");
                expect(wire.readSmtp(), '2', account);
            }
            wire.writeLine("DATA");
            expect(wire.readSmtp(), '3', account);
            wire.writeRaw(MailCodec.smtpData(account.address, tos, ccs, title, text, inReplyTo).getBytes(StandardCharsets.UTF_8));
            wire.writeRaw(".\r\n".getBytes(StandardCharsets.UTF_8));
            expect(wire.readSmtp(), '2', account);
            wire.writeLine("QUIT");
        } catch (MailException e) {
            throw e;
        } catch (IOException e) {
            throw io(e);
        } finally {
            if (wire != null) wire.close();
        }
    }

    private static void expect(String reply, char code, MailAccount account) throws MailException {
        if (reply == null || reply.isEmpty() || reply.charAt(0) != code) {
            throw new MailException(code == '2' || code == '3' ? "friend.mail.err_send" : "friend.mail.err_protocol",
                    MailCodec.safeDetail(reply, account.password));
        }
    }

    private static void expectAuth(String reply, MailAccount account) throws MailException {
        if (reply == null || reply.isEmpty() || reply.charAt(0) != '2') {
            throw new MailException("friend.mail.err_auth", MailCodec.safeDetail(reply, account.password));
        }
    }

    private static MailException io(IOException e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("SSL") || name.contains("Certificate")) {
            return new MailException("friend.mail.err_tls", "");
        }
        return new MailException("friend.mail.err_connect", "");
    }

    private static int roleOrder(String role) {
        return switch (role) {
            case "inbox" -> 0;
            case "sent" -> 1;
            case "drafts" -> 2;
            case "junk" -> 3;
            case "trash" -> 4;
            default -> 5;
        };
    }

    static Folder parseList(String line) {
        int open = line.indexOf('(');
        int close = line.indexOf(')', open + 1);
        String attrs = open >= 0 && close > open ? line.substring(open, close + 1) : "";
        String rest = close > 0 ? line.substring(close + 1).trim() : "";
        String name = lastQuoted(rest);
        if (name == null || name.isEmpty()) {
            String[] parts = rest.split("\\s+");
            name = parts.length == 0 ? "" : parts[parts.length - 1];
        }
        if (name.isEmpty()) return null;
        return new Folder(name, roleOf(attrs, name));
    }

    static String roleOf(String attrs, String name) {
        String flags = attrs == null ? "" : attrs.toLowerCase(Locale.ROOT);
        String folder = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (flags.contains("\\inbox") || folder.equals("inbox")) return "inbox";
        if (flags.contains("\\sent") || folder.equals("sent") || folder.endsWith("/sent")
                || folder.endsWith(".sent") || folder.contains("sent items") || folder.contains("sent messages")
                || folder.contains("已发送")) return "sent";
        if (flags.contains("\\drafts") || folder.equals("drafts") || folder.endsWith("/drafts")
                || folder.contains("已草稿") || folder.contains("草稿")) return "drafts";
        if (flags.contains("\\trash") || folder.equals("trash") || folder.contains("deleted")
                || folder.contains("已删除") || folder.contains("废纸")) return "trash";
        if (flags.contains("\\junk") || folder.contains("junk") || folder.contains("spam")
                || folder.contains("垃圾")) return "junk";
        return "other";
    }

    private static String lastQuoted(String text) {
        String last = null;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '"') continue;
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    sb.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    last = sb.toString();
                    break;
                }
                sb.append(c);
                i++;
            }
        }
        return last;
    }

    private static List<Header> parseHeaders(Reply reply) {
        StringBuilder meta = new StringBuilder();
        List<String> headerBlocks = new ArrayList<>();
        for (String line : reply.lines) {
            Matcher marker = Pattern.compile("%%LIT(\\d+)%%").matcher(line);
            int from = 0;
            while (marker.find()) {
                meta.append(line, from, marker.start()).append('\n');
                int index = Integer.parseInt(marker.group(1));
                String block = index < reply.literals.size()
                        ? new String(reply.literals.get(index), StandardCharsets.ISO_8859_1) : "";
                headerBlocks.add(block);
                meta.append("%%HDR%%\n");
                from = marker.end();
            }
            meta.append(line.substring(from)).append('\n');
        }
        String[] chunks = meta.toString().split("(?=\\* \\d+ FETCH)");
        List<Header> headers = new ArrayList<>();
        int used = 0;
        for (String chunk : chunks) {
            if (!chunk.contains("FETCH")) continue;
            Matcher uid = UID.matcher(chunk);
            if (!uid.find()) continue;
            Matcher flags = FLAGS.matcher(chunk);
            String flagText = flags.find() ? flags.group(1) : "";
            String block = chunk.contains("%%HDR%%") && used < headerBlocks.size() ? headerBlocks.get(used++) : "";
            String from = "";
            String subject = "";
            String date = "";
            String unfolded = block.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n[ \t]+", " ");
            for (String row : unfolded.split("\n")) {
                int colon = row.indexOf(':');
                if (colon <= 0) continue;
                String key = row.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = MailCodec.decodeHeader(row.substring(colon + 1));
                switch (key) {
                    case "from" -> from = value;
                    case "subject" -> subject = value;
                    case "date" -> date = value;
                    default -> {}
                }
            }
            boolean unread = !flagText.toLowerCase(Locale.ROOT).contains("\\seen");
            headers.add(new Header(Long.parseLong(uid.group(1)), from, subject, date, unread));
        }
        return headers;
    }

    private static final class Reply {
        final boolean ok;
        final String status;
        final List<String> lines;
        final List<byte[]> literals;
        final String text;

        Reply(boolean ok, String status, List<String> lines, List<byte[]> literals) {
            this.ok = ok;
            this.status = status == null ? "" : status;
            this.lines = lines;
            this.literals = literals;
            this.text = String.join("\n", lines);
        }

        String detail(String secret) {
            return MailCodec.safeDetail(status, secret);
        }

        static Reply read(Wire wire, String tag) throws IOException, MailException {
            List<String> lines = new ArrayList<>();
            List<byte[]> literals = new ArrayList<>();
            while (lines.size() < 4000) {
                String line = wire.readLine();
                if (line == null) throw new MailException("friend.mail.err_protocol", "");
                Matcher literal = LITERAL.matcher(line);
                if (literal.find()) {
                    int size = Integer.parseInt(literal.group(1));
                    if (size > MAX_LITERAL) throw new MailException("friend.mail.err_large", "");
                    byte[] bytes = wire.readExact(size);
                    literals.add(bytes);
                    line = line.substring(0, literal.start()) + "%%LIT" + (literals.size() - 1) + "%%";
                    String rest = wire.readLine();
                    if (rest == null) throw new MailException("friend.mail.err_protocol", "");
                    if (rest.startsWith(tag + " ")) {
                        lines.add(line);
                        lines.add(rest);
                        return finish(rest, lines, literals);
                    }
                    line = line + rest;
                }
                lines.add(line);
                if (line.startsWith(tag + " ")) return finish(line, lines, literals);
            }
            throw new MailException("friend.mail.err_protocol", "");
        }

        private static Reply finish(String status, List<String> lines, List<byte[]> literals) {
            boolean ok = status.regionMatches(true, status.indexOf(' ') + 1, "OK", 0, 2);
            return new Reply(ok, status, lines, literals);
        }
    }

    static final class Wire implements AutoCloseable {
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private int tags;

        private Wire(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        static Wire open(String host, int port, String security) throws IOException {
            String name;
            try {
                name = normalizeHost(host);
            } catch (RuntimeException e) {
                throw new IOException("bad host");
            }
            if (port < 1 || port > 65535) throw new IOException("bad port");
            String mode = security == null ? "" : security;
            if ("plain".equals(mode)) {
                if (!isLoopback(name)) throw new IOException("plain mail is only for loopback");
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(name, port), CONNECT_TIMEOUT);
                socket.setSoTimeout(READ_TIMEOUT);
                return new Wire(socket);
            }
            if ("ssl".equals(mode)) return new Wire(ssl(name, port, null));
            if ("starttls".equals(mode)) {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(name, port), CONNECT_TIMEOUT);
                socket.setSoTimeout(READ_TIMEOUT);
                return new Wire(socket);
            }
            throw new IOException("bad security");
        }

        void upgrade(String host) throws IOException {
            SSLSocket secure = ssl(normalizeHost(host), socket.getPort(), socket);
            this.socket = secure;
            this.in = secure.getInputStream();
            this.out = secure.getOutputStream();
        }

        int nextTag() {
            return ++tags;
        }

        void writeLine(String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void writeRaw(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            while (sb.length() < MAX_LINE) {
                int c = in.read();
                if (c < 0) return sb.length() == 0 ? null : sb.toString();
                if (c == '\n') return sb.toString();
                if (c != '\r') sb.append((char) c);
            }
            throw new IOException("line too long");
        }

        String readSmtp() throws IOException {
            String line = readLine();
            if (line == null) return "";
            StringBuilder sb = new StringBuilder(line);
            while (line.length() >= 4 && line.charAt(3) == '-') {
                line = readLine();
                if (line == null) break;
                sb.append('\n').append(line);
            }
            return sb.toString();
        }

        byte[] readExact(int size) throws IOException {
            byte[] buf = new byte[size];
            int got = 0;
            while (got < size) {
                int n = in.read(buf, got, size - got);
                if (n < 0) throw new IOException("eof");
                got += n;
            }
            return buf;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }

        private static SSLSocket ssl(String host, int port, Socket existing) throws IOException {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket socket;
            if (existing == null) {
                socket = (SSLSocket) factory.createSocket();
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            } else {
                socket = (SSLSocket) factory.createSocket(existing, host, port, true);
            }
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.setSoTimeout(READ_TIMEOUT);
            socket.startHandshake();
            return socket;
        }
    }

    static String normalizeHost(String host) {
        if (host == null) throw new IllegalArgumentException("bad host");
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty() || value.length() > 253 || value.indexOf(' ') >= 0 || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException("bad host");
        }
        return IDN.toASCII(value);
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equals(host)) return true;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
