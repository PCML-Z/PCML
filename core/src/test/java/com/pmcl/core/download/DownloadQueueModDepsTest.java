package com.pmcl.core.download;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.install.VersionInstaller;
import com.pmcl.core.market.ModFile;
import com.pmcl.core.market.ModMarketManager;
import com.pmcl.core.modloader.ModLoaderManager;
import com.pmcl.core.mods.ModDependencyResolver;
import com.pmcl.core.preferences.Preferences;
import com.pmcl.core.version.VersionManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：市场「模组 + 依赖」必须走下载队列（此前依赖链绕开队列直接下载，
 * 下载中心看不到任务，也无法暂停/取消/限流）。
 */
class DownloadQueueModDepsTest {

    @Test
    void modWithDependenciesRunsInsideQueue() throws Exception {
        Path work = Files.createTempDirectory("pmcl-queue-deps-");
        LauncherConfig config = new LauncherConfig(work);
        Preferences preferences = new Preferences(work.resolve("preferences.json"));
        DownloadManager downloads = new DownloadManager(config, preferences);
        VersionInstaller versionInstaller = new VersionInstaller(
                config, new VersionManager(config, preferences), downloads);
        ModLoaderManager loaders = new ModLoaderManager(config, downloads, versionInstaller);
        ModMarketManager market = new ModMarketManager(config, downloads);
        DownloadQueueManager queue = new DownloadQueueManager(
                config, downloads, versionInstaller, market, loaders, preferences);
        queue.setModDependencyResolver(new ModDependencyResolver(config, market, preferences));
        try {
            // 缺少校验和：installMod 会立即失败，全程不碰网络，依赖解析器把失败记进结果
            ModFile file = new ModFile("modrinth", "project", "file", "broken-mod.jar", 10,
                    "https://example.invalid/broken-mod.jar",
                    List.of("1.20.1"), List.of("fabric"), "release");
            String id = queue.submitModDownloadWithDeps(file, "1.20.1", null, null);

            DownloadQueueManager.QueueTask task = null;
            for (int i = 0; i < 100; i++) {
                task = queue.getTasks().stream()
                        .filter(t -> t.getId().equals(id)).findFirst().orElse(null);
                if (task != null && !task.isActive()) break;
                Thread.sleep(100);
            }
            assertNotNull(task, "队列里应存在含依赖任务");
            assertEquals(DownloadQueueManager.TaskType.MOD_DOWNLOAD, task.getType());
            assertFalse(task.isActive(), "任务应已结束: " + task.getMessage());
            assertEquals(DownloadQueueManager.TaskStatus.DONE, task.getStatus());
            assertNotNull(task.getDependencyResult(), "含依赖任务应回填依赖解析结果");
            assertTrue(task.getDependencyResult().getFailed().stream()
                            .anyMatch(s -> s.contains("broken-mod.jar")),
                    "主模组失败应记入 failed: " + task.getDependencyResult().summary());
        } finally {
            queue.shutdown();
        }
    }
}
