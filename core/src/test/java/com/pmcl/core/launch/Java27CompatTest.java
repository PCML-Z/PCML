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

class Java27CompatTest {

    @TempDir
    Path tmp;

    @Test
    void restoresOldLayoutAndDropsRemovedOptions() {
        List<String> args = new ArrayList<>(List.of(
                "-Xmx4G",
                "-Xverify:none",
                "-noverify",
                "-noclassgc",
                "-XX:+UseCompactObjectHeaders",
                "-XX:+UseG1GC"
        ));
        Java27Compat.apply(args);
        Java27Compat.apply(args);
        assertFalse(args.contains("-Xverify:none"));
        assertFalse(args.contains("-noverify"));
        assertFalse(args.contains("-noclassgc"));
        assertFalse(args.contains("-XX:+UseCompactObjectHeaders"));
        assertTrue(args.contains("-Xmx4G"));
        assertTrue(args.contains("-XX:+UseG1GC"));
        assertEquals(1, args.stream().filter(Java27Compat.FINAL_FIELD::equals).count());
        assertEquals(1, args.stream().filter(Java27Compat.OLD_HEADERS::equals).count());
    }

    @Test
    void commandKeepsCompatFlags() {
        LaunchProfile profile = new LaunchProfile(new LauncherConfig(tmp),
                new Account("Steve", "abc", "", Account.AccountType.OFFLINE), "26.2");
        profile.addJvmArg("-Xverify:none");
        profile.addJvmArg("-Dfile.encoding=UTF-8");
        profile.setJava27Compat(true);
        List<String> cmd = profile.buildCommand("java");
        assertTrue(cmd.contains(Java27Compat.FINAL_FIELD));
        assertTrue(cmd.contains(Java27Compat.OLD_HEADERS));
        assertTrue(cmd.contains("-Dfile.encoding=UTF-8"));
        assertFalse(cmd.contains("-Xverify:none"));
    }

    @Test
    void preferenceDefaultsOffAndRoundTrips() {
        Preferences pref = new Preferences(tmp.resolve("preferences.json"));
        Preferences reloaded = null;
        try {
            assertFalse(pref.isJava27Compat());
            pref.setJava27Compat(true);
            pref.save();
            reloaded = new Preferences(tmp.resolve("preferences.json"));
            assertTrue(reloaded.isJava27Compat());
        } finally {
            pref.shutdown();
            if (reloaded != null) reloaded.shutdown();
        }
    }
}
