package com.pmcl.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 安全的 ZIP 解压工具，统一防护 ZipSlip（路径穿越）与 ZipBomb（解压炸弹）。
 * <p>
 * <b>ZipSlip 防护：</b>所有解压目标路径必须位于指定的基目录内，
 * 拒绝包含 {@code ..} 或绝对路径的 entry。
 * <p>
 * <b>ZipBomb 防护：</b>
 * <ul>
 *   <li>单文件解压大小上限：{@link #DEFAULT_MAX_ENTRY_SIZE}（默认 256 MB）</li>
 *   <li>解压后总大小上限：{@link #DEFAULT_MAX_TOTAL_SIZE}（默认 2 GB）</li>
 *   <li>entry 数量上限：{@link #DEFAULT_MAX_ENTRIES}（默认 100,000）</li>
 *   <li>压缩比上限：{@link #DEFAULT_MAX_RATIO}（默认 100:1，超出视为炸弹）</li>
 *   <li>单个 entry 读取时按块复制并累计字节数，超限立即抛出 IOException</li>
 * </ul>
 * <p>
 * 推荐使用 {@link #extractSafely(Path, Path)} 替代手写解压循环。
 * 对于需要过滤 entry 的场景（如只解压 {@code overrides/} 前缀），使用
 * {@link #extractSafely(Path, Path, Predicate)}。
 */
public final class SafeZipExtractor {

    /** 单个 entry 解压后最大字节数（默认 256 MB）。 */
    public static final long DEFAULT_MAX_ENTRY_SIZE = 256L * 1024 * 1024;

    /** 所有 entry 解压后总最大字节数（默认 2 GB）。 */
    public static final long DEFAULT_MAX_TOTAL_SIZE = 2L * 1024 * 1024 * 1024;

    /** 最多 entry 数量（默认 100,000）。 */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    /** 最大压缩比（解压后/压缩前，默认 100:1）。 */
    public static final int DEFAULT_MAX_RATIO = 100;

    /** 复制缓冲区大小。 */
    private static final int BUFFER_SIZE = 8192;

    private SafeZipExtractor() {}

    /**
     * 安全解压 ZIP 文件到目标目录（无 entry 过滤）。
     *
     * @param zipFile   ZIP 文件路径
     * @param targetDir 解压目标目录（将自动创建）
     * @throws IOException 如果发生 ZipSlip、ZipBomb 或其他 IO 错误
     */
    public static void extractSafely(Path zipFile, Path targetDir) throws IOException {
        extractSafely(zipFile, targetDir, null);
    }

    /**
     * 安全解压 ZIP 文件到目标目录，可选 entry 过滤。
     *
     * @param zipFile     ZIP 文件路径
     * @param targetDir   解压目标目录（将自动创建）
     * @param entryFilter 仅解压 filter 返回 true 的 entry；null 表示不过滤
     * @throws IOException 如果发生 ZipSlip、ZipBomb 或其他 IO 错误
     */
    public static void extractSafely(Path zipFile, Path targetDir, Predicate<ZipEntry> entryFilter)
            throws IOException {
        extractSafely(zipFile, targetDir, entryFilter,
                DEFAULT_MAX_ENTRY_SIZE, DEFAULT_MAX_TOTAL_SIZE, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RATIO);
    }

    /**
     * 安全解压 ZIP 文件到目标目录（可自定义 ZipBomb 阈值）。
     *
     * @param zipFile       ZIP 文件路径
     * @param targetDir     解压目标目录（将自动创建）
     * @param entryFilter   仅解压 filter 返回 true 的 entry；null 表示不过滤
     * @param maxEntrySize  单个 entry 最大解压字节数
     * @param maxTotalSize  总解压字节数上限
     * @param maxEntries    entry 数量上限
     * @param maxRatio      最大压缩比
     * @throws IOException 如果发生 ZipSlip、ZipBomb 或其他 IO 错误
     */
    public static void extractSafely(Path zipFile, Path targetDir, Predicate<ZipEntry> entryFilter,
                                     long maxEntrySize, long maxTotalSize, int maxEntries, int maxRatio)
            throws IOException {
        Files.createDirectories(targetDir);
        Path normalizedBase = targetDir.normalize();

        long totalSize = 0;
        int entryCount = 0;
        long compressedSize = 0;
        long zipFileSize = Files.exists(zipFile) ? Files.size(zipFile) : 0;

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                // entry 数量限制
                if (++entryCount > maxEntries) {
                    throw new IOException("ZipBomb detected: entry count exceeds limit (" + maxEntries
                            + ") in " + zipFile);
                }

                if (entry.isDirectory()) continue;
                if (entryFilter != null && !entryFilter.test(entry)) continue;

                String entryName = entry.getName();
                long entryCompressed = entry.getCompressedSize();
                if (entryCompressed > 0) compressedSize += entryCompressed;

                // ZipSlip 防护
                Path dest = targetDir.resolve(entryName).normalize();
                if (!dest.startsWith(normalizedBase)) {
                    throw new IOException("ZipSlip detected: entry '" + entryName
                            + "' resolves outside target dir " + targetDir);
                }

                Files.createDirectories(dest.getParent());

                // 流式复制并累计字节数，防止超大 entry 导致 OOM
                long entrySize = 0;
                try (InputStream in = zip.getInputStream(entry);
                     java.io.OutputStream out = Files.newOutputStream(dest,
                             java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                             java.nio.file.StandardOpenOption.WRITE)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        entrySize += n;
                        totalSize += n;
                        if (entrySize > maxEntrySize) {
                            throw new IOException("ZipBomb detected: entry '" + entryName
                                    + "' exceeds max entry size " + maxEntrySize
                                    + " (extracted " + entrySize + " bytes)");
                        }
                        if (totalSize > maxTotalSize) {
                            throw new IOException("ZipBomb detected: total extracted size exceeds limit "
                                    + maxTotalSize + " bytes in " + zipFile);
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
        }

        // 压缩比检查（zip 文件总大小 > 0 时）
        if (zipFileSize > 0 && compressedSize > 0) {
            long ratio = totalSize / Math.max(1, compressedSize);
            if (ratio > maxRatio) {
                throw new IOException("ZipBomb detected: compression ratio " + ratio + ":1 exceeds limit "
                        + maxRatio + ":1 in " + zipFile
                        + " (extracted=" + totalSize + ", compressed=" + compressedSize + ")");
            }
        }
    }

    /**
     * 安全流式解压（适用于 ZipInputStream，无预先 entry 索引的场景）。
     * 用于 {@link java.util.zip.ZipInputStream} 直接读取流的场景。
     *
     * @param zis         已打开的 ZipInputStream（调用方负责关闭）
     * @param targetDir   解压目标目录（将自动创建）
     * @param entryFilter 仅解压 filter 返回 true 的 entry；null 表示不过滤
     * @throws IOException 如果发生 ZipSlip、ZipBomb 或其他 IO 错误
     */
    public static void extractStreamSafely(ZipInputStream zis, Path targetDir,
                                           Predicate<ZipEntry> entryFilter) throws IOException {
        extractStreamSafely(zis, targetDir, entryFilter,
                DEFAULT_MAX_ENTRY_SIZE, DEFAULT_MAX_TOTAL_SIZE, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RATIO);
    }

    /**
     * 安全流式解压（可自定义 ZipBomb 阈值）。
     */
    public static void extractStreamSafely(ZipInputStream zis, Path targetDir,
                                           Predicate<ZipEntry> entryFilter,
                                           long maxEntrySize, long maxTotalSize,
                                           int maxEntries, int maxRatio) throws IOException {
        Files.createDirectories(targetDir);
        Path normalizedBase = targetDir.normalize();

        long totalSize = 0;
        int entryCount = 0;

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (++entryCount > maxEntries) {
                throw new IOException("ZipBomb detected: entry count exceeds limit (" + maxEntries + ")");
            }
            if (entry.isDirectory()) continue;
            if (entryFilter != null && !entryFilter.test(entry)) continue;

            String entryName = entry.getName();
            Path dest = targetDir.resolve(entryName).normalize();
            if (!dest.startsWith(normalizedBase)) {
                throw new IOException("ZipSlip detected: entry '" + entryName
                        + "' resolves outside target dir " + targetDir);
            }

            Files.createDirectories(dest.getParent());

            long entrySize = 0;
            try (java.io.OutputStream out = Files.newOutputStream(dest,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    entrySize += n;
                    totalSize += n;
                    if (entrySize > maxEntrySize) {
                        throw new IOException("ZipBomb detected: entry '" + entryName
                                + "' exceeds max entry size " + maxEntrySize
                                + " (extracted " + entrySize + " bytes)");
                    }
                    if (totalSize > maxTotalSize) {
                        throw new IOException("ZipBomb detected: total extracted size exceeds limit "
                                + maxTotalSize + " bytes");
                    }
                    out.write(buf, 0, n);
                }
            }
        }
    }
}
