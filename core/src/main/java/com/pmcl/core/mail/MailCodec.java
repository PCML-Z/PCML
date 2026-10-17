package com.pmcl.core.mail;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 邮件头、MIME 正文和 SMTP DATA 的编解码。不访问网络。 */
public final class MailCodec {
    public static final int SUBJECT_MAX = 120;
    public static final int BODY_MAX = 100_000;

    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]+$");
    private static final DateTimeFormatter RFC5322 = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            .withZone(ZoneOffset.UTC);
    private static final byte[] CRLFCRLF = new byte[]{'\r', '\n', '\r', '\n'};
    private static final byte[] LFLF = new byte[]{'\n', '\n'};

    private MailCodec() {}

    public static final class Attachment {
        public final String name;
        public final byte[] data;

        public Attachment(String name, byte[] data) {
            this.name = name == null || name.isBlank() ? "attachment.bin" : name;
            this.data = data;
        }
    }

    public static final class Parsed {
        public final String from;
        public final String to;
        public final String cc;
        public final String subject;
        public final String date;
        public final String messageId;
        public final String text;
        public final boolean fromHtml;
        public final List<Attachment> attachments;

        Parsed(String from, String to, String cc, String subject, String date, String messageId,
               String text, boolean fromHtml, List<Attachment> attachments) {
            this.from = from;
            this.to = to;
            this.cc = cc;
            this.subject = subject;
            this.date = date;
            this.messageId = messageId;
            this.text = text;
            this.fromHtml = fromHtml;
            this.attachments = attachments;
        }
    }

    public static Parsed parseMessage(byte[] raw) {
        byte[] data = raw == null ? new byte[0] : raw;
        int split = indexOf(data, CRLFCRLF, 0);
        int gap = 4;
        if (split < 0) {
            split = indexOf(data, LFLF, 0);
            gap = 2;
        }
        byte[] headBytes = split < 0 ? data : slice(data, 0, split);
        byte[] body = split < 0 ? new byte[0] : slice(data, split + gap, data.length);
        Map<String, String> headers = parseHeaders(new String(headBytes, StandardCharsets.ISO_8859_1));
        Body best = new Body();
        walk(headers, body, best, 0);
        String text = best.plain != null ? best.plain : (best.html != null ? htmlToText(best.html) : "");
        if (text.length() > BODY_MAX) text = text.substring(0, BODY_MAX);
        return new Parsed(
                decodeHeader(headers.get("from")),
                decodeHeader(headers.get("to")),
                decodeHeader(headers.get("cc")),
                decodeHeader(headers.get("subject")),
                decodeHeader(headers.get("date")),
                decodeHeader(headers.get("message-id")),
                text,
                best.plain == null && best.html != null,
                List.copyOf(best.attachments));
    }

    public static String decodeHeader(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String unfolded = raw.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n[ \t]+", " ");
        Matcher matcher = ENCODED_WORD.matcher(unfolded);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean previousWord = false;
        while (matcher.find()) {
            String gap = unfolded.substring(last, matcher.start());
            if (!(previousWord && gap.isBlank())) sb.append(gap);
            sb.append(decodeWord(matcher.group(1), matcher.group(2), matcher.group(3)));
            previousWord = true;
            last = matcher.end();
        }
        sb.append(unfolded.substring(last));
        return stripControls(sb.toString()).trim();
    }

    public static List<String> addresses(String header) {
        List<String> out = new ArrayList<>();
        if (header == null || header.isBlank()) return out;
        for (String part : header.split("[,;]")) {
            String one = addressOnly(part);
            if (!one.isEmpty() && !out.contains(one)) out.add(one);
        }
        return out;
    }

    public static String addressOnly(String header) {
        if (header == null) return "";
        String text = decodeHeader(header);
        int open = text.lastIndexOf('<');
        int close = text.lastIndexOf('>');
        String candidate = open >= 0 && close > open ? text.substring(open + 1, close) : text;
        candidate = candidate.trim();
        return validAddress(candidate) ? candidate : "";
    }

    public static boolean validAddress(String address) {
        if (address == null) return false;
        String value = address.trim();
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 33 || c > 126) return false;
        }
        return true;
    }

    public static String clip(String raw, int max) {
        if (raw == null || max <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < raw.length() && count < max; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\t') {
                sb.append(cp == '\n' ? '\n' : ' ');
                count++;
                continue;
            }
            if (cp < 32 || cp == 127) continue;
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.toString().trim();
    }

    /** SMTP DATA，不含结尾的单独句点。以点开头的行会多加一个点。 */
    public static String smtpData(String from, List<String> to, List<String> cc, String subject,
                                  String body, String inReplyTo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Date: ").append(RFC5322.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        sb.append("From: ").append(from).append("\r\n");
        sb.append("To: ").append(String.join(", ", to)).append("\r\n");
        if (cc != null && !cc.isEmpty()) sb.append("Cc: ").append(String.join(", ", cc)).append("\r\n");
        sb.append("Subject: ").append(encodeSubject(subject)).append("\r\n");
        sb.append("Message-ID: <").append(UUID.randomUUID()).append("@pmcl.local>\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            String id = inReplyTo.trim();
            sb.append("In-Reply-To: ").append(id).append("\r\n");
            sb.append("References: ").append(id).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n");
        sb.append("\r\n");
        String encoded = Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
                .encodeToString(clip(body, BODY_MAX).getBytes(StandardCharsets.UTF_8));
        sb.append(encoded);
        if (!encoded.endsWith("\r\n")) sb.append("\r\n");
        return dotStuff(sb.toString());
    }

    public static byte[] draftMessage(String from, String to, String cc, String subject, String body) {
        List<String> recipients = addresses(to);
        if (recipients.isEmpty()) {
            recipients = List.of(validAddress(from) ? from : "draft@localhost");
        }
        String data = smtpData(from, recipients, addresses(cc), subject, body, null);
        return data.getBytes(StandardCharsets.UTF_8);
    }

    static String dotStuff(String data) {
        String normalized = data.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(normalized.length() + 16);
        int start = 0;
        while (start <= normalized.length()) {
            int end = normalized.indexOf('\n', start);
            String line = end < 0 ? normalized.substring(start) : normalized.substring(start, end);
            if (line.startsWith(".")) sb.append('.');
            sb.append(line).append("\r\n");
            if (end < 0) break;
            start = end + 1;
        }
        return sb.toString();
    }

    static String imapQuote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') continue;
            if (c == '\\' || c == '"') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    static String safeDetail(String serverLine, String secret) {
        if (serverLine == null) return "";
        String text = serverLine.replace('\r', ' ').replace('\n', ' ').trim();
        if (secret != null && secret.length() >= 3 && text.contains(secret)) {
            text = text.replace(secret, "***");
        }
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    private static void walk(Map<String, String> headers, byte[] body, Body best, int depth) {
        if (depth > 8 || body == null) return;
        String type = headers.getOrDefault("content-type", "text/plain");
        String mime = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        byte[] decoded = transferDecode(body, headers.get("content-transfer-encoding"));
        if (mime.startsWith("multipart/")) {
            String boundary = parameter(type, "boundary");
            if (boundary.isEmpty()) return;
            for (byte[] part : splitMultipart(decoded, boundary)) {
                int split = indexOf(part, CRLFCRLF, 0);
                int gap = 4;
                if (split < 0) {
                    split = indexOf(part, LFLF, 0);
                    gap = 2;
                }
                if (split < 0) continue;
                Map<String, String> partHeaders = parseHeaders(new String(slice(part, 0, split), StandardCharsets.ISO_8859_1));
                walk(partHeaders, slice(part, split + gap, part.length), best, depth + 1);
            }
            return;
        }
        if (mime.startsWith("text/plain") && best.plain == null) {
            best.plain = new String(decoded, charset(parameter(type, "charset")));
            return;
        }
        if (mime.startsWith("text/html") && best.html == null) {
            best.html = new String(decoded, charset(parameter(type, "charset")));
            return;
        }
        if (best.attachments.size() >= 12) return;
        if (decoded.length > 1_000_000) {
            best.attachments.add(new Attachment(filename(headers), null));
            return;
        }
        if (!mime.startsWith("text/")) {
            best.attachments.add(new Attachment(filename(headers), decoded));
        }
    }

    private static List<byte[]> splitMultipart(byte[] body, String boundary) {
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        List<byte[]> parts = new ArrayList<>();
        int from = indexOf(body, marker, 0);
        while (from >= 0) {
            int start = from + marker.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') break;
            if (start + 1 < body.length && body[start] == '\r') start += 2;
            else if (start < body.length && body[start] == '\n') start += 1;
            int next = indexOf(body, marker, start);
            if (next < 0) break;
            int end = next;
            if (end >= 2 && body[end - 2] == '\r' && body[end - 1] == '\n') end -= 2;
            else if (end >= 1 && body[end - 1] == '\n') end -= 1;
            if (end > start) parts.add(slice(body, start, end));
            from = next;
        }
        return parts;
    }

    private static byte[] transferDecode(byte[] body, String encoding) {
        if (body == null) return new byte[0];
        String kind = encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
        try {
            if (kind.equals("base64")) {
                return Base64.getMimeDecoder().decode(body);
            }
            if (kind.equals("quoted-printable")) return decodeQuotedPrintable(body);
        } catch (IllegalArgumentException ignored) {
            return body;
        }
        return body;
    }

    private static byte[] decodeQuotedPrintable(byte[] body) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length);
        for (int i = 0; i < body.length; i++) {
            int c = body[i] & 0xFF;
            if (c == '=' && i + 1 < body.length && (body[i + 1] == '\n' || body[i + 1] == '\r')) {
                i++;
                if (i < body.length && body[i] == '\r' && i + 1 < body.length && body[i + 1] == '\n') i++;
                continue;
            }
            if (c == '=' && i + 2 < body.length && isHex(body[i + 1]) && isHex(body[i + 2])) {
                int hi = Character.digit((char) body[i + 1], 16);
                int lo = Character.digit((char) body[i + 2], 16);
                out.write((hi << 4) + lo);
                i += 2;
                continue;
            }
            out.write(c);
        }
        return out.toByteArray();
    }

    private static String htmlToText(String html) {
        StringBuilder sb = new StringBuilder(html.length());
        boolean skip = false;
        String skipKind = "";
        for (int i = 0; i < html.length(); ) {
            if (html.charAt(i) == '<') {
                int end = html.indexOf('>', i);
                if (end < 0) break;
                String tag = html.substring(i + 1, end).trim().toLowerCase(Locale.ROOT);
                String name = tag.replace("/", "").split("\\s+", 2)[0];
                if (name.equals("script") || name.equals("style")) {
                    if (tag.startsWith("/")) {
                        skip = false;
                    } else {
                        skip = true;
                        skipKind = name;
                    }
                } else if (!skip && (name.equals("br") || name.equals("p") || name.equals("div")
                        || name.equals("tr") || name.equals("li"))) {
                    sb.append('\n');
                }
                i = end + 1;
                continue;
            }
            if (!skip) sb.append(html.charAt(i));
            else if (html.regionMatches(true, i, "</" + skipKind, 0, skipKind.length() + 2)) {
                skip = false;
            }
            i++;
        }
        return decodeEntities(sb.toString()).replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\n{3,}", "\n\n").trim();
    }

    private static String decodeEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static Map<String, String> parseHeaders(String block) {
        Map<String, String> headers = new LinkedHashMap<>();
        String unfolded = block.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n[ \t]+", " ");
        for (String line : unfolded.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }
        return headers;
    }

    private static String filename(Map<String, String> headers) {
        String disposition = headers.getOrDefault("content-disposition", "");
        String type = headers.getOrDefault("content-type", "");
        String star = parameter(disposition, "filename*");
        if (star.isEmpty()) star = parameter(type, "filename*");
        if (!star.isEmpty()) {
            int mark = star.indexOf("''");
            String encoded = mark >= 0 ? star.substring(mark + 2) : star;
            try {
                return cleanFilename(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                return cleanFilename(encoded);
            }
        }
        String name = parameter(disposition, "filename");
        if (name.isEmpty()) name = parameter(type, "name");
        return cleanFilename(decodeHeader(name));
    }

    static String cleanFilename(String name) {
        if (name == null || name.isBlank()) return "attachment.bin";
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = stripControls(base).replace("..", "").trim();
        return base.isEmpty() ? "attachment.bin" : base;
    }

    private static String parameter(String header, String name) {
        if (header == null) return "";
        Pattern pattern = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|([^;\\s]+))");
        Matcher matcher = pattern.matcher(header);
        if (!matcher.find()) return "";
        String quoted = matcher.group(2);
        return quoted != null ? quoted : matcher.group(3);
    }

    private static String encodeSubject(String subject) {
        String clean = clip(subject, SUBJECT_MAX).replace('\n', ' ');
        boolean ascii = true;
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) > 126) {
                ascii = false;
                break;
            }
        }
        if (ascii) return clean;
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String decodeWord(String charsetName, String coding, String text) {
        Charset charset = charset(charsetName);
        try {
            if (coding.equalsIgnoreCase("B")) {
                return new String(Base64.getMimeDecoder().decode(text), charset);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '_') {
                    out.write(' ');
                } else if (c == '=' && i + 2 < text.length()) {
                    int value = Integer.parseInt(text.substring(i + 1, i + 3), 16);
                    out.write(value);
                    i += 2;
                } else {
                    out.write((byte) c);
                }
            }
            return new String(out.toByteArray(), charset);
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static Charset charset(String name) {
        if (name == null || name.isBlank()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(name.trim().replace("\"", ""));
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String stripControls(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t' || cp >= 32 && cp != 127) sb.appendCodePoint(cp);
        });
        return sb.toString();
    }

    private static boolean isHex(byte value) {
        int c = value & 0xFF;
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        if (pattern.length == 0 || from < 0) return -1;
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] slice(byte[] data, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(data.length, Math.max(start, to));
        byte[] out = new byte[end - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }

    private static final class Body {
        String plain;
        String html;
        final List<Attachment> attachments = new ArrayList<>();
    }
}
