package com.pmcl.core.download;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

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
}
