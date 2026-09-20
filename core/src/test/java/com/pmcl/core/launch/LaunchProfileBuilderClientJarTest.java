package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.preferences.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchProfileBuilderClientJarTest {

    @TempDir
    Path tmp;

    @Test
    void inheritedLoaderUsesParentClientJarOwner() throws Exception {
        Path work = tmp.resolve("pmcl");
        Path vanilla = work.resolve("versions").resolve("1.20.1");
        Path fabric = work.resolve("versions").resolve("1.20.1-fabric-0.16.0");
        Files.createDirectories(vanilla);
        Files.createDirectories(fabric);
        Files.writeString(vanilla.resolve("1.20.1.json"),
                "{\"id\":\"1.20.1\",\"downloads\":{\"client\":{"
                        + "\"sha1\":\"0123456789abcdef0123456789abcdef01234567\","
                        + "\"url\":\"https://example.invalid/client.jar\"}}}",
                StandardCharsets.UTF_8);
        Files.writeString(fabric.resolve("1.20.1-fabric-0.16.0.json"),
                "{\"id\":\"1.20.1-fabric-0.16.0\",\"inheritsFrom\":\"1.20.1\","
                        + "\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\"}",
                StandardCharsets.UTF_8);

        LaunchProfileBuilder b = new LaunchProfileBuilder(
                new LauncherConfig(work), new Preferences(work.resolve("prefs.json")));
        assertEquals("1.20.1", b.resolveClientJarOwnerId("1.20.1-fabric-0.16.0"));
        assertEquals("1.20.1", b.resolveClientJarOwnerId("1.20.1"));
    }
}
