package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 关闭启动器时，已经要求留下的游戏不能被随后的强制关闭杀掉。 */
class LaunchManagerShutdownTest {

    @Test
    void leaveGameSurvivesLaterKill() throws Exception {
        Path work = Files.createTempDirectory("pmcl-launch-shutdown-");
        LaunchManager manager = new LaunchManager(new LauncherConfig(work));
        Process sleep = startSleeper();
        try {
            manager.trackExternalProcess(sleep);
            manager.shutdown(false);
            manager.shutdown(true);
            assertTrue(sleep.isAlive(), "留下的进程被后一次关闭杀掉了");
        } finally {
            sleep.destroyForcibly();
            sleep.waitFor(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownKillsTrackedProcess() throws Exception {
        Path work = Files.createTempDirectory("pmcl-launch-kill-");
        LaunchManager manager = new LaunchManager(new LauncherConfig(work));
        Process sleep = startSleeper();
        manager.trackExternalProcess(sleep);
        try {
            manager.shutdown(true);
            assertTrue(sleep.waitFor(3, TimeUnit.SECONDS), "强制关闭没有结束被跟踪的进程");
            assertFalse(sleep.isAlive());
        } finally {
            if (sleep.isAlive()) sleep.destroyForcibly();
        }
    }

    private static Process startSleeper() throws Exception {
        List<String> cmd = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? List.of("ping", "-n", "30", "127.0.0.1")
                : List.of("sleep", "30");
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }
}
