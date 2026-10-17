package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.auth.Account;
import com.pmcl.core.preferences.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G1ForceTest {

    @TempDir
    Path tmp;

    @Test
    void dropsOtherCollectorsAndKeepsG1Tuning() {
        List<String> args = new ArrayList<>(List.of(
                "-Xmx4G",
                "-XX:+UseZGC",
                "-XX:+ZGenerational",
                "-XX:SoftMaxHeapSize=4096m",
                "-XX:ZAllocationSpikeTolerance=2.0",
                "-XX:+UseShenandoahGC",
                "-XX:ShenandoahGCHeuristics=adaptive",
                "-XX:+UseParallelGC",
                "-XX:-UseG1GC",
                "-XX:G1NewSizePercent=30",
                "-XX:MaxGCPauseMillis=50",
                "-XX:+UseStringDeduplication",
                "-XX:+UseAES"
        ));
        G1Force.apply(args);
        assertFalse(args.stream().anyMatch(a -> a.contains("UseZGC") || a.contains("ZGenerational")
                || a.contains("SoftMaxHeapSize") || a.contains("Shenandoah") || a.contains("UseParallelGC")
                || a.contains("-UseG1GC")));
        assertTrue(args.contains("-XX:G1NewSizePercent=30"));
        assertTrue(args.contains("-XX:MaxGCPauseMillis=50"));
        assertTrue(args.contains("-XX:+UseStringDeduplication"));
        assertTrue(args.contains("-XX:+UseAES"));
        assertTrue(args.contains("-Xmx4G"));
        assertEquals("-XX:+UseG1GC", args.get(args.size() - 1));
        assertEquals(1, args.stream().filter("-XX:+UseG1GC"::equals).count());
    }

    @Test
    void commandUsesG1EvenWhenAnotherCollectorWasAdded() {
        LaunchProfile profile = new LaunchProfile(new LauncherConfig(tmp),
                new Account("Steve", "abc", "", Account.AccountType.OFFLINE), "1.21");
        profile.addJvmArg("-XX:+UseZGC");
        profile.addJvmArg("-XX:+ZGenerational");
        profile.addJvmArg("-XX:G1HeapRegionSize=8M");
        profile.setForceG1(true);
        List<String> cmd = profile.buildCommand("java");
        assertTrue(cmd.contains("-XX:+UseG1GC"));
        assertFalse(cmd.stream().anyMatch(a -> a.contains("UseZGC") || a.contains("ZGenerational")));
        assertTrue(cmd.contains("-XX:G1HeapRegionSize=8M"));
    }

    @Test
    void preferenceDefaultsOffAndRoundTrips() {
        Preferences pref = new Preferences(tmp.resolve("preferences.json"));
        Preferences reloaded = null;
        try {
            assertFalse(pref.isForceG1Gc());
            pref.setForceG1Gc(true);
            pref.save();
            reloaded = new Preferences(tmp.resolve("preferences.json"));
            assertTrue(reloaded.isForceG1Gc());
        } finally {
            pref.shutdown();
            if (reloaded != null) reloaded.shutdown();
        }
    }
}
