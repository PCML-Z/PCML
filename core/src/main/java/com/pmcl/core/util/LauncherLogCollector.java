package com.pmcl.core.util;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * 启动器自身运行日志收集器：tee 拦截 [System.out]/[System.err]，
 * 输出原样透传到控制台的同时收集到内存环形缓冲，供 UI「复制启动器日志」使用。
 *
 * <p>与 [com.pmcl.core.launch.GameLogger]（游戏进程日志）互补：本类收集的是启动器
 * JVM 自身的输出——异常堆栈（printStackTrace）、插件错误、Prism/Skiko 诊断信息等。
 *
 * <p>线程安全：PrintStream 的 write 自身持锁串行调用，行缓冲与环形缓冲在同一把
 * 锁内更新。单行超限截断、总行数超限淘汰最旧行，内存上限约
 * {@value #MAX_LINES} 行 x {@value #MAX_LINE_BYTES} 字节。
 */
public final class LauncherLogCollector {

    private static final int MAX_LINES = 4000;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(MAX_LINES);
    private static final Object LOCK = new Object();
    private static volatile boolean installed = false;

    private LauncherLogCollector() {}

    /** 安装 stdout/stderr tee 拦截；幂等，越早调用越好（捕获后续全部输出） */
    public static void install() {
        if (installed) return;
        installed = true;
        System.setOut(new PrintStream(new TeeOutputStream(System.out), true));
        System.setErr(new PrintStream(new TeeOutputStream(System.err), true));
    }

    /** 当前收集到的完整日志文本（换行分隔）；未安装或无输出返回空串 */
    public static String getText() {
        synchronized (LOCK) {
            if (LINES.isEmpty()) return "";
            return String.join("\n", LINES);
        }
    }

    /** 收集的行数 */
    public static int lineCount() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final PrintStream original;
        // 行缓冲：无锁访问由外层 PrintStream 的锁串行化保证
        private ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(128);

        TeeOutputStream(PrintStream original) {
            this.original = original;
        }

        @Override
        public void write(int b) {
            original.write(b);
            if (b == '\n') {
                flushLine();
            } else {
                lineBuf.write(b);
                if (lineBuf.size() >= MAX_LINE_BYTES) {
                    flushLine();
                }
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
            original.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    flushLine();
                } else {
                    lineBuf.write(b[i]);
                    if (lineBuf.size() >= MAX_LINE_BYTES) {
                        flushLine();
                    }
                }
            }
        }

        @Override
        public void flush() {
            original.flush();
        }

        /** 结束当前行：截断尾部超长行并入环形缓冲（丢弃孤立的 '\r'） */
        private void flushLine() {
            byte[] bytes = lineBuf.toByteArray();
            lineBuf.reset();
            if (bytes.length == 1 && bytes[0] == '\r') return;
            String line = new String(bytes, StandardCharsets.UTF_8);
            synchronized (LOCK) {
                LINES.addLast(line);
                while (LINES.size() > MAX_LINES) {
                    LINES.removeFirst();
                }
            }
        }
    }
}
