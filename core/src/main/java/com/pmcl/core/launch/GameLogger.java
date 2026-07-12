package com.pmcl.core.launch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 游戏日志收集器：在内存环形缓冲 + 持久化到 latest.log 文件。
 * <p>
 * 供 UI 实时读取显示，也支持独立日志窗口通过文件 tail -f。
 */
public final class GameLogger {

    private static final int BUFFER_CAPACITY = 2000;

    private final String[] ring = new String[BUFFER_CAPACITY];
    private int head = 0;
    private int size = 0;
    private final ReentrantLock lock = new ReentrantLock();

    private final Path logFile;
    private final BufferedWriter writer;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private int linesSinceFlush = 0;
    private long lastFlushTime = 0;

    public GameLogger(Path logFile) throws IOException {
        this.logFile = logFile;
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        this.writer = Files.newBufferedWriter(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 追加一行日志 */
    public void append(String line) {
        String stamped = "[" + LocalDateTime.now().format(TS) + "] " + line;
        lock.lock();
        try {
            ring[(head + size) % BUFFER_CAPACITY] = stamped;
            if (size < BUFFER_CAPACITY) size++;
            else head = (head + 1) % BUFFER_CAPACITY;
            // BufferedWriter 非线程安全，写入操作也需在锁内
            try {
                writer.write(stamped);
                writer.newLine();
                linesSinceFlush++;
                long now = System.currentTimeMillis();
                if (linesSinceFlush >= 50 || now - lastFlushTime > 200) {
                    writer.flush();
                    linesSinceFlush = 0;
                    lastFlushTime = now;
                }
            } catch (IOException ignored) {
            }
        } finally {
            lock.unlock();
        }
    }

    /** 获取最近 N 行 */
    public String[] recentLines(int n) {
        lock.lock();
        try {
            int take = Math.min(n, size);
            String[] out = new String[take];
            for (int i = 0; i < take; i++) {
                out[i] = ring[(head + (size - take) + i) % BUFFER_CAPACITY];
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 获取全部缓冲 */
    public String[] allLines() {
        return recentLines(size);
    }

    public Path getLogFile() { return logFile; }

    public void close() {
        try { writer.close(); } catch (IOException ignored) {}
    }
}
