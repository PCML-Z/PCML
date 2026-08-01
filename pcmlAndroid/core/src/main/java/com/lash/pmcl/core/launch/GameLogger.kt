package com.lash.pmcl.core.launch

import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 游戏日志收集器：在内存环形缓冲 + 持久化到 latest.log 文件。
 *
 * 供 UI 实时读取显示，也支持独立日志窗口通过文件 tail -f。
 *
 * 文件 I/O 移至独立写线程，锁仅保护内存环形缓冲。
 * 原实现在 ReentrantLock 内执行 writer.write + flush，MC 日志量
 * 大时写盘阻塞，导致读取进程 stdout 的管道被填满后进程卡死。
 *
 * @param logFile 日志文件路径
 */
class GameLogger(logFile: Path) {

    private val ring: Array<String?> = arrayOfNulls(BUFFER_CAPACITY)
    private var head = 0
    private var size = 0
    private val lock = ReentrantLock()

    private val logFile: Path = logFile
    private val writer: BufferedWriter
    private val ts: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** 写线程：从队列取行写入文件，避免在 append 调用线程阻塞 */
    private val writeQueue: BlockingQueue<String> = ArrayBlockingQueue(WRITE_QUEUE_CAPACITY)
    private val writeThread: Thread
    @Volatile private var closed = false
    @Volatile private var bytesWritten = 0L
    @Volatile private var diskWriteDisabled = false
    @Volatile private var diskErrorLogged = false

    init {
        logFile.parent?.let { Files.createDirectories(it) }
        this.writer = Files.newBufferedWriter(
            logFile, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
        this.writeThread = Thread(::writeLoop, "GameLogger-Writer").apply {
            isDaemon = true
            start()
        }
    }

    /** 写线程主循环：从队列取行写入文件，定期 flush */
    private fun writeLoop() {
        var linesSinceFlush = 0
        var lastFlushTime = System.currentTimeMillis()
        while (!closed && !Thread.currentThread().isInterrupted) {
            try {
                // take() 阻塞直到有数据，被 interrupt 时抛 InterruptedException 退出
                val line = writeQueue.poll(200, TimeUnit.MILLISECONDS)
                if (line == null) {
                    // 空闲时也定期 flush，保证日志及时落盘
                    val now = System.currentTimeMillis()
                    if (linesSinceFlush > 0 && now - lastFlushTime > 200 && !diskWriteDisabled) {
                        writer.flush()
                        linesSinceFlush = 0
                        lastFlushTime = now
                    }
                    continue
                }
                if (diskWriteDisabled) continue
                val lineBytes = line.length + 1L
                if (bytesWritten + lineBytes > MAX_LOG_FILE_BYTES) {
                    diskWriteDisabled = true
                    try {
                        writer.write("[PMCL] 日志文件已达上限 ($MAX_LOG_FILE_BYTES bytes)，停止继续写入磁盘\n")
                        writer.flush()
                    } catch (_: IOException) {
                    }
                    System.err.println("[GameLogger] 日志文件达上限，停止写盘: $logFile")
                    continue
                }
                writer.write(line)
                writer.write("\n")
                bytesWritten += lineBytes
                linesSinceFlush++
                val now = System.currentTimeMillis()
                if (linesSinceFlush >= 50 || now - lastFlushTime > 200) {
                    writer.flush()
                    linesSinceFlush = 0
                    lastFlushTime = now
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: IOException) {
                diskWriteDisabled = true
                if (!diskErrorLogged) {
                    diskErrorLogged = true
                    System.err.println("[GameLogger] 写盘失败，停止文件日志: ${e.message}")
                }
            } catch (t: Throwable) {
                if (!diskErrorLogged) {
                    diskErrorLogged = true
                    System.err.println("[GameLogger] 写线程异常: ${t.message}")
                }
            }
        }
        // 退出前 flush 剩余数据
        try {
            if (!diskWriteDisabled) writer.flush()
        } catch (_: IOException) {
        }
    }

    /** 追加一行日志 */
    fun append(line: String) {
        val stamped = "[${LocalDateTime.now().format(ts)}] $line"
        lock.lock()
        try {
            ring[(head + size) % BUFFER_CAPACITY] = stamped
            if (size < BUFFER_CAPACITY) size++
            else head = (head + 1) % BUFFER_CAPACITY
        } finally {
            lock.unlock()
        }
        // 文件写入移出锁范围，避免 I/O 阻塞导致管道死锁
        // offer 失败（队列满）直接丢弃，优先保证游戏进程不被阻塞
        writeQueue.offer(stamped)
    }

    /** 获取最近 N 行 */
    fun recentLines(n: Int): Array<String> {
        lock.lock()
        return try {
            val take = minOf(n, size)
            val out = Array(take) { "" }
            for (i in 0 until take) {
                out[i] = ring[(head + (size - take) + i) % BUFFER_CAPACITY]!!
            }
            out
        } finally {
            lock.unlock()
        }
    }

    /** 获取全部缓冲 */
    fun allLines(): Array<String> = recentLines(size)

    fun getLogFile(): Path = logFile

    fun close() {
        closed = true
        // 排空队列后再关流，避免尾部日志丢失
        val deadline = System.currentTimeMillis() + 2000
        while (writeQueue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        writeThread.interrupt()
        try {
            writeThread.join(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 同步排空剩余行（写线程已退出）；已达磁盘上限则丢弃
        var leftover = writeQueue.poll()
        while (leftover != null) {
            if (diskWriteDisabled) break
            try {
                val lineBytes = leftover.length + 1L
                if (bytesWritten + lineBytes > MAX_LOG_FILE_BYTES) {
                    diskWriteDisabled = true
                    break
                }
                writer.write(leftover)
                writer.write("\n")
                bytesWritten += lineBytes
            } catch (_: IOException) {
                break
            }
            leftover = writeQueue.poll()
        }
        try {
            if (!diskWriteDisabled) writer.flush()
        } catch (_: IOException) {
        }
        try {
            writer.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val BUFFER_CAPACITY = 2000
        /** 写队列容量：超过则丢弃最旧（日志丢弃优于阻塞游戏进程） */
        private const val WRITE_QUEUE_CAPACITY = 4000
        /** 单文件最大约 64MB，超出后停止写盘（内存环形缓冲仍可用） */
        private const val MAX_LOG_FILE_BYTES = 64L * 1024 * 1024
    }
}
