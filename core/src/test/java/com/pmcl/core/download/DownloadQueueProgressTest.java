package com.pmcl.core.download;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadQueueProgressTest {

    @Test
    void assetIndexPlaceholderDoesNotClearZipBytes() {
        assertNull(DownloadQueueManager.mergeInstallProgress(167_784, 167_784, 0, 1));
    }

    @Test
    void realByteTotalReplacesThePreviousPhase() {
        assertArrayEquals(
                new long[]{12, 579_000_000},
                DownloadQueueManager.mergeInstallProgress(167_784, 167_784, 12, 579_000_000));
    }

    @Test
    void fileCountReplacesByteTotal() {
        assertArrayEquals(
                new long[]{3, 51},
                DownloadQueueManager.mergeInstallProgress(12, 579_000_000, 3, 51));
    }

    @Test
    void byteMessagesStayInOnePhase() {
        assertEquals(
                DownloadQueueManager.phaseKey("下载中 12 / 579000000 bytes"),
                DownloadQueueManager.phaseKey("下载中 400 / 579000000 bytes"));
        assertEquals("下载资产索引", DownloadQueueManager.phaseKey("下载资产索引"));
    }

    @Test
    void summaryUsesTheGivenSnapshot() {
        DownloadQueueManager.QueueTask task = new DownloadQueueManager.QueueTask(
                "1", "pack", DownloadQueueManager.TaskType.MARKET_CONTENT);
        DownloadQueueManager.QueueSummary summary = DownloadQueueManager.summarize(List.of(task));
        assertEquals(1, summary.queued);
        assertEquals(0, summary.completedBytes);
        assertEquals(0, summary.totalBytes);
    }
}
