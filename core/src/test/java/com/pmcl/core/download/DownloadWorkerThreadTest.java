package com.pmcl.core.download;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadWorkerThreadTest {

    @Test
    void downloadWorkersDoNotKeepTheProcessAlive() throws Exception {
        ExecutorService pool = DownloadManager.newDaemonPool(1, "pmcl-download-test");
        try {
            boolean daemon = pool.submit(() -> Thread.currentThread().isDaemon())
                    .get(2, TimeUnit.SECONDS);
            assertTrue(daemon);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void previousInterruptDoesNotStickToTheNextDownload() throws Exception {
        ExecutorService pool = DownloadManager.newDaemonPool(1, "pmcl-download-test");
        try {
            pool.submit(() -> Thread.currentThread().interrupt()).get(2, TimeUnit.SECONDS);
            boolean stillInterrupted = pool.submit(() -> Thread.currentThread().isInterrupted())
                    .get(2, TimeUnit.SECONDS);
            assertFalse(stillInterrupted);
        } finally {
            pool.shutdownNow();
        }
    }
}
