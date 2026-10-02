package com.pmcl.core.preferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import com.pmcl.core.launch.ImeFixSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentSettingsTest {

    @TempDir
    Path tmp;

    @Test
    void keepsAbsoluteJarsAndDropsTheRest() {
        Preferences pref = new Preferences(tmp.resolve("preferences.json"));
        Preferences reloaded = null;
        try {
            pref.setJavaAgents(List.of(
                    new Preferences.JavaAgentSetting("/opt/agents/ok.jar", "server=https://lash.org.cn"),
                    new Preferences.JavaAgentSetting("relative.jar", ""),
                    new Preferences.JavaAgentSetting("/opt/agents/notes.txt", ""),
                    new Preferences.JavaAgentSetting("/opt/agents/bad=name.jar", ""),
                    new Preferences.JavaAgentSetting("/opt/agents/break.jar\n-javaagent:evil.jar", ""),
                    new Preferences.JavaAgentSetting("/opt/agents/ok.jar", "duplicate")
            ));
            pref.save();

            reloaded = new Preferences(tmp.resolve("preferences.json"));
            List<Preferences.JavaAgentSetting> stored = reloaded.getJavaAgents();
            assertEquals(1, stored.size());
            assertEquals("/opt/agents/ok.jar", stored.get(0).getPath());
            assertEquals("server=https://lash.org.cn", stored.get(0).getOptions());
        } finally {
            pref.shutdown();
            if (reloaded != null) reloaded.shutdown();
        }
    }

    @Test
    void imeFixAgentDefaultsOffAndRoundTrips() throws Exception {
        Preferences pref = new Preferences(tmp.resolve("ime.json"));
        Preferences reloaded = null;
        try {
            assertFalse(pref.isImeFixAgent());
            pref.setImeFixAgent(true);
            pref.save();
            reloaded = new Preferences(tmp.resolve("ime.json"));
            assertTrue(reloaded.isImeFixAgent());
        } finally {
            pref.shutdown();
            if (reloaded != null) reloaded.shutdown();
        }
        try (java.io.InputStream in = ImeFixSupport.class.getResourceAsStream(
                "/com/pmcl/core/ime/pmcl-ime-agent.jar")) {
            assertNotNull(in);
            assertTrue(in.readAllBytes().length > 100);
        }
    }
}
