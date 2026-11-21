package com.pmcl.core.download;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.install.InstallInterruptedException;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.version.VersionManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadQueueResumeTest {

    @Test
    void timeoutsAreFailuresNotPauses() {
        assertFalse(InstallInterruptedException.isInterrupted(new java.net.SocketTimeoutException("Connect timed out")));
        assertFalse(InstallInterruptedException.isInterrupted(new java.io.InterruptedIOException("timeout")));
        assertTrue(InstallInterruptedException.isInterrupted(new java.io.InterruptedIOException("下载已中断")));
        assertTrue(InstallInterruptedException.isInterrupted(new InstallInterruptedException("任务已暂停")));
    }

    @Test
    void nextTaskRunsAfterAPauseInterruptedTheWorker() throws Exception {
        Path work = Files.createTempDirectory("pmcl-queue-resume-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "slow".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            byte[] body = "ok-file".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        LauncherConfig config = new LauncherConfig(work);
        Preferences preferences = new Preferences(work.resolve("preferences.json"));
        DownloadManager downloads = new DownloadManager(config, preferences);
        VersionInstaller versionInstaller = new VersionInstaller(
                config, new VersionManager(config, preferences), downloads);
        DownloadQueueManager queue = new DownloadQueueManager(
                config, downloads, versionInstaller, new ModMarketManager(config, downloads),
                new ModLoaderManager(config, downloads, versionInstaller), preferences, 1);
        try {
            int port = server.getAddress().getPort();
            String slowId = queue.submitFileDownload("slow", "http://127.0.0.1:" + port + "/slow",
                    work.resolve("slow.bin"));
            DownloadQueueManager.QueueTask slow = waitUntil(queue, slowId, task ->
                    task.getStatus() == DownloadQueueManager.TaskStatus.RUNNING, 50);
            assertEquals(DownloadQueueManager.TaskStatus.RUNNING, slow.getStatus(), slow.getMessage());
            queue.pause(slowId);
            waitUntil(queue, slowId, task -> !task.isActive(), 50);

            String okId = queue.submitFileDownload("ok", "http://127.0.0.1:" + port + "/ok",
                    work.resolve("ok.bin"));
            DownloadQueueManager.QueueTask ok = waitUntil(queue, okId, task -> !task.isActive(), 50);
            assertEquals(DownloadQueueManager.TaskStatus.DONE, ok.getStatus(), ok.getMessage());
            assertEquals("ok-file", Files.readString(work.resolve("ok.bin")));
            assertTrue(slow != null);
        } finally {
            queue.shutdown();
            downloads.shutdown();
            preferences.shutdown();
            server.stop(0);
        }
    }

    private static DownloadQueueManager.QueueTask waitUntil(DownloadQueueManager queue, String id,
                                                            java.util.function.Predicate<DownloadQueueManager.QueueTask> done,
                                                            int attempts) throws InterruptedException {
        DownloadQueueManager.QueueTask task = null;
        for (int i = 0; i < attempts; i++) {
            task = queue.getTasks().stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
            if (task != null && done.test(task)) return task;
            Thread.sleep(100);
        }
        return task;
    }
}
