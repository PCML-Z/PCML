package com.pmcl.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * macOS 系统听写。识别跑在单独的 .app 里，权限框才能出现。
 * 本机支持时要求在本机完成；否则用系统听写。录音不发给对话模型。
 * 不写 preferences.json。check 参数不打开麦克风。
 */
public final class MacDictation {
    private static final String RESOURCE = "/native/macos/PMCLDictate.swift";
    private static final int ACCEPT_MS = 20_000;
    private static final int STOP_READ_MS = 8_000;

    private MacDictation() {}

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String code);

        void onStatus(String status);
    }

    public record Event(String kind, String text) {}

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    public static Session start(Listener listener) throws IOException {
        return start(LauncherConfig.pmclHome(), listener);
    }

    public static Session start(Path home, Listener listener) throws IOException {
        if (!supported()) throw new IOException("not_mac");
        if (listener == null) throw new IOException("failed");
        Path app = install(home);
        String token = token();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(ACCEPT_MS);
        ProcessBuilder open = new ProcessBuilder(
                "open", "-n", app.toString(), "--args",
                Integer.toString(server.getLocalPort()), token);
        open.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        open.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process opener = open.start();
        Session session = new Session(server, token, listener, opener);
        session.begin();
        return session;
    }

    /** 把听写插进光标处。已有文字和这句话之间补一个空格。 */
    public static String join(String base, String spoken, String tail) {
        String head = base == null ? "" : base;
        String say = spoken == null ? "" : spoken;
        String rest = tail == null ? "" : tail;
        if (say.isEmpty()) return head + rest;
        return head + (needsGap(head, say) ? " " : "") + say + rest;
    }

    public static int caret(String base, String spoken) {
        String head = base == null ? "" : base;
        String say = spoken == null ? "" : spoken;
        if (say.isEmpty()) return head.length();
        return head.length() + (needsGap(head, say) ? 1 : 0) + say.length();
    }

    public static Event parse(String line) {
        if (line == null || line.isBlank()) return new Event("", "");
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            for (String key : new String[] {"hello", "partial", "final", "error", "status"}) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    return new Event(key, object.get(key).getAsString());
                }
            }
        } catch (RuntimeException ignored) {
            return new Event("", "");
        }
        return new Event("", "");
    }

    static String infoPlist() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>CFBundleExecutable</key><string>PMCLDictate</string>
                  <key>CFBundleIdentifier</key><string>com.pmcl.dictate</string>
                  <key>CFBundleName</key><string>PMCL</string>
                  <key>CFBundleDisplayName</key><string>PMCL</string>
                  <key>CFBundlePackageType</key><string>APPL</string>
                  <key>CFBundleShortVersionString</key><string>1.0</string>
                  <key>CFBundleVersion</key><string>1</string>
                  <key>LSUIElement</key><true/>
                  <key>NSHighResolutionCapable</key><true/>
                  <key>NSMicrophoneUsageDescription</key>
                  <string>PMCL 使用麦克风把你说的话转成文字。</string>
                  <key>NSSpeechRecognitionUsageDescription</key>
                  <string>PMCL 把语音转成输入框中的文字。</string>
                </dict>
                </plist>
                """;
    }

    static Path install(Path home) throws IOException {
        byte[] sourceBytes;
        try (InputStream in = MacDictation.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("failed");
            sourceBytes = in.readAllBytes();
        }
        Path root = home.resolve("dictate");
        Path app = root.resolve("PMCLDictate.app");
        Path contents = app.resolve("Contents");
        Path macos = contents.resolve("MacOS");
        Files.createDirectories(macos);
        Path source = root.resolve("PMCLDictate.swift");
        Path binary = macos.resolve("PMCLDictate");
        Path plist = contents.resolve("Info.plist");
        String xml = infoPlist();
        boolean swiftSame = Files.exists(source) && Arrays.equals(sourceBytes, Files.readAllBytes(source));
        boolean plistSame = Files.exists(plist) && xml.equals(Files.readString(plist));
        if (!swiftSame) Files.write(source, sourceBytes);
        if (!plistSame) Files.writeString(plist, xml);
        if (!swiftSame || !plistSame || !Files.isRegularFile(binary)) {
            compile(plist, binary, source);
            sign(app);
        }
        if (!binary.toFile().setExecutable(true, false)) {
            throw new IOException("failed");
        }
        clearQuarantine(app);
        return app;
    }

    private static void compile(Path plist, Path binary, Path source) throws IOException {
        Process compile = new ProcessBuilder(
                "xcrun", "swiftc",
                "-framework", "Speech",
                "-framework", "AVFoundation",
                "-framework", "AppKit",
                "-framework", "Foundation",
                "-Xlinker", "-sectcreate",
                "-Xlinker", "__TEXT",
                "-Xlinker", "__info_plist",
                "-Xlinker", plist.toString(),
                "-o", binary.toString(),
                source.toString())
                .redirectErrorStream(true)
                .start();
        byte[] log = compile.getInputStream().readAllBytes();
        try {
            if (!compile.waitFor(90, TimeUnit.SECONDS) || compile.exitValue() != 0) {
                System.err.println("[PMCL] 听写助手编译失败: " + new String(log, StandardCharsets.UTF_8));
                throw new IOException("failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("failed");
        }
    }

    private static void sign(Path app) throws IOException {
        Process sign = new ProcessBuilder(
                "codesign", "--force", "--sign", "-",
                "--identifier", "com.pmcl.dictate",
                app.toString())
                .redirectErrorStream(true)
                .start();
        try {
            byte[] log = sign.getInputStream().readAllBytes();
            if (!sign.waitFor(30, TimeUnit.SECONDS) || sign.exitValue() != 0) {
                System.err.println("[PMCL] 听写助手签名失败: " + new String(log, StandardCharsets.UTF_8));
                throw new IOException("failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("failed");
        }
    }

    private static void clearQuarantine(Path app) {
        try {
            Process clear = new ProcessBuilder("xattr", "-dr", "com.apple.quarantine", app.toString())
                    .redirectErrorStream(true)
                    .start();
            clear.getInputStream().readAllBytes();
            clear.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 本地编译的助手没有隔离属性。
        }
    }

    private static boolean needsGap(String head, String say) {
        if (head.isEmpty() || say.isEmpty()) return false;
        return !Character.isWhitespace(head.charAt(head.length() - 1))
                && !Character.isWhitespace(say.charAt(0));
    }

    private static String token() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static final class Session {
        private final ServerSocket server;
        private final String token;
        private final Listener listener;
        private final Process opener;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean signaled = new AtomicBoolean();
        private final AtomicReference<Socket> socket = new AtomicReference<>();
        private volatile Thread reader;

        Session(ServerSocket server, String token, Listener listener, Process opener) {
            this.server = server;
            this.token = token;
            this.listener = listener;
            this.opener = opener;
        }

        void begin() {
            Thread thread = new Thread(this::read, "pmcl-dictate");
            thread.setDaemon(true);
            reader = thread;
            thread.start();
        }

        /** 结束这一句。已经写进输入框的部分保留。 */
        public void stop() {
            stopped.set(true);
            Socket current = socket.get();
            if (current != null) signal(current);
            else closeQuietly(server);
        }

        private void signal(Socket current) {
            if (!signaled.compareAndSet(false, true)) return;
            try {
                current.setSoTimeout(STOP_READ_MS);
                current.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                current.getOutputStream().flush();
                current.shutdownOutput();
            } catch (IOException e) {
                closeQuietly(current);
            }
        }

        private void read() {
            boolean ended = false;
            try {
                if (!opener.waitFor(10, TimeUnit.SECONDS) || opener.exitValue() != 0) {
                    if (!stopped.get()) listener.onError("failed");
                    return;
                }
                if (stopped.get()) return;
                Socket accepted = server.accept();
                accepted.setTcpNoDelay(true);
                socket.set(accepted);
                if (stopped.get()) {
                    signal(accepted);
                }
                BufferedReader lines = new BufferedReader(
                        new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                boolean hello = false;
                String line;
                while ((line = lines.readLine()) != null) {
                    Event event = parse(line);
                    if (!hello) {
                        if (!"hello".equals(event.kind()) || !same(token, event.text())) {
                            listener.onError("failed");
                            ended = true;
                            break;
                        }
                        hello = true;
                        continue;
                    }
                    switch (event.kind()) {
                        case "partial" -> listener.onPartial(event.text());
                        case "final" -> {
                            listener.onFinal(event.text());
                            ended = true;
                        }
                        case "error" -> {
                            listener.onError(event.text().isBlank() ? "failed" : event.text());
                            ended = true;
                        }
                        case "status" -> listener.onStatus(event.text());
                        default -> {
                        }
                    }
                    if (ended) break;
                }
            } catch (SocketTimeoutException e) {
                if (!ended && stopped.get()) listener.onFinal("");
                else if (!ended) listener.onError("failed");
                ended = true;
            } catch (IOException e) {
                if (!stopped.get() && !ended) listener.onError("failed");
                ended = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!stopped.get() && !ended) listener.onError("failed");
                ended = true;
            } finally {
                closeQuietly(socket.get());
                closeQuietly(server);
            }
        }
    }

    private static void closeQuietly(ServerSocket server) {
        if (server == null) return;
        try { server.close(); } catch (IOException ignored) {}
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
