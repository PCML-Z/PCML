package com.pmcl.core.launch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 游戏日志收集器：在内存环形缓冲 + 持久化到 latest.log 文件。
 * <p>
 * 供 UI 实时读取显示，也支持独立日志窗口通过文件 tail -f。
 *
 * <p>S10: 文件 I/O 移至独立写线程，锁仅保护内存环形缓冲。
 * 原实现在 ReentrantLock 内执行 writer.write + flush，MC 日志量
 * 大时写盘阻塞，导致读取进程 stdout 的管道被填满后进程卡死。
 */
public final class GameLogger {

    private static final int BUFFER_CAPACITY = 2000;
    /** 写队列容量：超过则丢弃最旧（日志丢弃优于阻塞游戏进程） */
    private static final int WRITE_QUEUE_CAPACITY = 4000;

    private final String[] ring = new String[BUFFER_CAPACITY];
    private int head = 0;
    private int size = 0;
    private final ReentrantLock lock = new ReentrantLock();

    private final Path logFile;
    private final BufferedWriter writer;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 写线程：从队列取行写入文件，避免在 append 调用线程阻塞 */
    private final BlockingQueue<String> writeQueue = new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
    private final Thread writeThread;
    private volatile boolean closed = false;

    public GameLogger(Path logFile) throws IOException {
        this.logFile = logFile;
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        this.writer = Files.newBufferedWriter(logFile,
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        this.writeThread = new Thread(this::writeLoop, "GameLogger-Writer");
        this.writeThread.setDaemon(true);
        this.writeThread.start();
    }

    /** 写线程主循环：从队列取行写入文件，定期 flush */
    private void writeLoop() {
        int linesSinceFlush = 0;
        long lastFlushTime = System.currentTimeMillis();
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                // take() 阻塞直到有数据，被 interrupt 时抛 InterruptedException 退出
                String line = writeQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line == null) {
                    // 空闲时也定期 flush，保证日志及时落盘
                    long now = System.currentTimeMillis();
                    if (linesSinceFlush > 0 && now - lastFlushTime > 200) {
                        writer.flush();
                        linesSinceFlush = 0;
                        lastFlushTime = now;
                    }
                    continue;
                }
                writer.write(line);
                writer.write("\n");
                linesSinceFlush++;
                long now = System.currentTimeMillis();
                if (linesSinceFlush >= 50 || now - lastFlushTime > 200) {
                    writer.flush();
                    linesSinceFlush = 0;
                    lastFlushTime = now;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException ignored) {
                // 写盘失败不中断日志收集（内存缓冲仍可用）
            } catch (Throwable ignored) {
                // 兜底：任何异常都不能让写线程退出
            }
        }
        // 退出前 flush 剩余数据
        try { writer.flush(); } catch (IOException ignored) {}
    }

    /** 追加一行日志 */
    public void append(String line) {
        String stamped = "[" + LocalDateTime.now().format(TS) + "] " + line;
        lock.lock();
        try {
            ring[(head + size) % BUFFER_CAPACITY] = stamped;
            if (size < BUFFER_CAPACITY) size++;
            else head = (head + 1) % BUFFER_CAPACITY;
        } finally {
            lock.unlock();
        }
        // S10: 文件写入移出锁范围，避免 I/O 阻塞导致管道死锁
        // offer 失败（队列满）直接丢弃，优先保证游戏进程不被阻塞
        writeQueue.offer(stamped);
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
        closed = true;
        writeThread.interrupt();
        try { writeThread.join(1000); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        try { writer.close(); } catch (IOException ignored) {}
    }
}
