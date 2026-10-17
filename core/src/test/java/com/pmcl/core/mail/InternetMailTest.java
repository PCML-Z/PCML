package com.pmcl.core.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternetMailTest {

    @TempDir
    Path dir;

    @Test
    void decodesMimeAndStuffsDots() {
        byte[] message = """
                From: Ann <ann@example.com>\r
                Subject: =?UTF-8?B?5L2g5aW9?=\r
                Content-Type: multipart/alternative; boundary="b"\r
                \r
                --b\r
                Content-Type: text/plain; charset=UTF-8\r
                Content-Transfer-Encoding: quoted-printable\r
                \r
                =E4=BD=A0=E5=A5=BD\r
                --b\r
                Content-Type: text/html; charset=UTF-8\r
                \r
                <p>ignored</p>\r
                --b--\r
                """.replace("\n", "").replace("\r", "\r\n").getBytes(StandardCharsets.US_ASCII);
        MailCodec.Parsed parsed = MailCodec.parseMessage(message);
        assertEquals("你好", parsed.subject);
        assertEquals("ann@example.com", MailCodec.addressOnly(parsed.from));
        assertEquals("你好", parsed.text.trim());
        assertFalse(parsed.fromHtml);

        byte[] htmlOnly = """
                Subject: Hi\r
                Content-Type: text/html; charset=UTF-8\r
                \r
                <style>p{}</style><p>Hello &amp; co</p>\r
                """.replace("\n", "").replace("\r", "\r\n").getBytes(StandardCharsets.US_ASCII);
        MailCodec.Parsed html = MailCodec.parseMessage(htmlOnly);
        assertTrue(html.fromHtml);
        assertTrue(html.text.contains("Hello & co"));
        assertFalse(html.text.contains("<p>"));

        String stuffed = MailCodec.dotStuff("hello\n.hidden\n");
        assertTrue(stuffed.contains("\r\n..hidden\r\n"));
        assertEquals("qq", MailAccount.Preset.forAddress("someone@qq.com").id);
        assertEquals("imap.qq.com", MailAccount.Preset.QQ.imapHost);
        assertEquals(587, MailAccount.Preset.GMAIL.smtpPort);
    }

    @Test
    void storesThePasswordEncrypted() throws Exception {
        MailAccountStore store = new MailAccountStore(dir);
        MailAccount input = new MailAccount();
        input.address = "ann@qq.com";
        input.username = "ann@qq.com";
        input.password = "s3cret-mail";
        input.imapHost = "imap.qq.com";
        input.imapPort = 993;
        input.imapSecurity = "ssl";
        input.smtpHost = "smtp.qq.com";
        input.smtpPort = 465;
        input.smtpSecurity = "ssl";
        MailAccount saved = store.save(input);
        assertFalse(saved.toString().contains("s3cret-mail"));
        String json = Files.readString(dir.resolve("accounts.json"));
        assertTrue(json.contains("enc:v1:"));
        assertFalse(json.contains("s3cret-mail"));
        assertEquals("s3cret-mail", store.find(saved.id).password);

        input.id = saved.id;
        input.password = "";
        MailAccount again = store.save(input);
        assertEquals("s3cret-mail", again.password);
    }

    @Test
    void refusesPlaintextToThePublicInternet() {
        MailAccount account = new MailAccount();
        account.username = "ann@example.com";
        account.password = "secret";
        account.imapHost = "192.0.2.1";
        account.imapPort = 993;
        account.imapSecurity = "plain";
        MailException error = assertThrows(MailException.class, () -> MailClient.Session.open(account));
        assertEquals("friend.mail.err_connect", error.key);
    }

    @Test
    void readsAndSendsThroughALocalServer() throws Exception {
        AtomicBoolean moved = new AtomicBoolean(false);
        try (ServerSocket imap = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket smtp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread imapThread = new Thread(() -> serveImap(imap, moved));
            Thread smtpThread = new Thread(() -> serveSmtp(smtp));
            imapThread.start();
            smtpThread.start();

            MailAccount account = new MailAccount();
            account.address = "ann@example.com";
            account.username = "ann@example.com";
            account.password = "secret";
            account.imapHost = "127.0.0.1";
            account.imapPort = imap.getLocalPort();
            account.imapSecurity = "plain";
            account.smtpHost = "127.0.0.1";
            account.smtpPort = smtp.getLocalPort();
            account.smtpSecurity = "plain";

            try (MailClient.Session session = MailClient.Session.open(account)) {
                assertEquals("inbox", session.folders().get(0).role);
                assertEquals("Trash", session.trashFolder());
                var headers = session.messages("INBOX");
                assertEquals(1, headers.size());
                assertEquals("你好", headers.get(0).subject);
                assertTrue(headers.get(0).unread);
                MailClient.Letter letter = session.read("INBOX", headers.get(0).uid);
                assertTrue(letter.text.contains("Hello"));
                session.moveToTrash("INBOX", letter.uid);
            }
            assertTrue(moved.get());
            MailClient.send(account, "bob@example.com", "", "Hello", "Body", "<m1@example.com>");
            imapThread.join(5000);
            smtpThread.join(5000);
        }
    }

    private static void serveImap(ServerSocket server, AtomicBoolean moved) {
        try (Socket socket = server.accept()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            write(out, "* OK ready\r\n");
            String line;
            while ((line = readLine(in)) != null) {
                int space = line.indexOf(' ');
                if (space < 0) break;
                String tag = line.substring(0, space);
                String cmd = line.substring(space + 1);
                String verb = cmd.split(" ", 2)[0].toUpperCase();
                if (verb.equals("CAPABILITY")) {
                    write(out, "* CAPABILITY IMAP4rev1 MOVE UIDPLUS\r\n" + tag + " OK\r\n");
                } else if (verb.equals("LIST")) {
                    write(out, "* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n");
                    write(out, "* LIST (\\Sent) \"/\" \"Sent\"\r\n");
                    write(out, "* LIST (\\Trash) \"/\" \"Trash\"\r\n");
                    write(out, tag + " OK\r\n");
                } else if (verb.equals("UID") && cmd.toUpperCase().contains("SEARCH")) {
                    write(out, "* SEARCH 7\r\n" + tag + " OK\r\n");
                } else if (verb.equals("UID") && cmd.contains("HEADER.FIELDS")) {
                    byte[] header = ("From: Ann <ann@example.com>\r\n"
                            + "Subject: =?UTF-8?B?5L2g5aW9?=\r\n"
                            + "Date: Tue, 11 Oct 2026 01:02:03 +0000\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    write(out, "* 1 FETCH (UID 7 FLAGS () BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {"
                            + header.length + "}\r\n");
                    out.write(header);
                    write(out, ")\r\n" + tag + " OK\r\n");
                } else if (verb.equals("UID") && cmd.contains("BODY.PEEK[]")) {
                    byte[] message = ("From: Ann <ann@example.com>\r\nSubject: Hi\r\n"
                            + "Content-Type: text/plain; charset=UTF-8\r\n\r\nHello\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    write(out, "* 1 FETCH (BODY[] {" + message.length + "}\r\n");
                    out.write(message);
                    write(out, ")\r\n" + tag + " OK\r\n");
                } else if (verb.equals("UID") && cmd.toUpperCase().contains("MOVE")) {
                    moved.set(true);
                    write(out, tag + " OK\r\n");
                } else if (verb.equals("LOGOUT")) {
                    write(out, tag + " OK\r\n");
                    return;
                } else {
                    write(out, tag + " OK\r\n");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void serveSmtp(ServerSocket server) {
        try (Socket socket = server.accept()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            write(out, "220 ready\r\n");
            StringBuilder data = new StringBuilder();
            boolean collecting = false;
            int authStep = 0;
            String line;
            while ((line = readLine(in)) != null) {
                if (collecting) {
                    if (line.equals(".")) {
                        collecting = false;
                        String text = data.toString();
                        if (!text.contains("Subject: Hello") || !text.contains("In-Reply-To: <m1@example.com>")) {
                            write(out, "550 rejected\r\n");
                        } else {
                            write(out, "250 queued\r\n");
                        }
                        continue;
                    }
                    data.append(line).append('\n');
                    continue;
                }
                if (authStep == 1) {
                    authStep = 2;
                    write(out, "334 pass\r\n");
                    continue;
                }
                if (authStep == 2) {
                    authStep = 0;
                    write(out, "235 ok\r\n");
                    continue;
                }
                String upper = line.toUpperCase();
                if (upper.startsWith("EHLO")) write(out, "250-pmcl\r\n250 AUTH LOGIN\r\n");
                else if (upper.startsWith("AUTH LOGIN")) {
                    authStep = 1;
                    write(out, "334 user\r\n");
                } else if (upper.startsWith("MAIL FROM")) write(out, "250 ok\r\n");
                else if (upper.startsWith("RCPT TO")) write(out, "250 ok\r\n");
                else if (upper.equals("DATA")) {
                    collecting = true;
                    write(out, "354 go\r\n");
                } else if (upper.startsWith("QUIT")) {
                    write(out, "221 bye\r\n");
                    return;
                } else write(out, "250 ok\r\n");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
