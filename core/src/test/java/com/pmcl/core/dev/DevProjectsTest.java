package com.pmcl.core.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevProjectsTest {

    @TempDir
    Path temp;

    @Test
    void slugKeepsPluginAndEcxpRulesApart() {
        assertEquals("pmcl.hello-app", DevProjects.slug("Hello App", false));
        assertEquals("hello-app", DevProjects.slug("Hello App", true));
        assertTrue(DevProjects.validId("pmcl.hello-app", false));
        assertTrue(DevProjects.validId("hello-app", true));
        assertFalse(DevProjects.validId("hi", true));
        assertFalse(DevProjects.validId("打开设置", false));
    }

    @Test
    void ecxpProjectCanOpenInStudioOrVsCode() throws Exception {
        Path dir = DevProjects.create(temp, DevProjects.Kind.ECXP_VSCODE, "Hello App", "pmcl.hello-app");
        String project = Files.readString(dir.resolve("HelloApp.csproj"));
        assertTrue(project.contains("<PmclAppId>pmcl.hello-app</PmclAppId>"));
        assertTrue(project.contains("sdk/Pmcl.Ecxp/Pmcl.Ecxp.csproj"));
        assertTrue(Files.isRegularFile(dir.resolve("sdk/Pmcl.Ecxp/PmclApp.cs")));
        assertTrue(Files.isRegularFile(dir.resolve("sdk/Pmcl.Ecxp/build/Pmcl.Ecxp.targets")));
        assertTrue(Files.readString(dir.resolve("Program.cs")).contains("namespace Pmcl.HelloApp"));
        assertTrue(Files.readString(dir.resolve(".vscode/tasks.json")).contains("dotnet"));
        assertTrue(DevProjects.commands(DevProjects.Kind.ECXP_STUDIO, dir.toString()).isEmpty());
    }

    @Test
    void ideaPluginProjectCarriesTheApiJar() throws Exception {
        Path dir = DevProjects.create(temp, DevProjects.Kind.PLUGIN_IDEA, "Hello App", "hello-app");
        String props = Files.readString(dir.resolve("src/main/resources/META-INF/pmcl-plugin.properties"));
        assertTrue(props.contains("plugin.id=hello-app"));
        assertTrue(props.contains("plugin.main-class=pmcl.plugins.app.HelloAppPlugin"));
        assertTrue(props.contains("plugin.api-version=1.8"));
        String source = Files.readString(dir.resolve("src/main/kotlin/pmcl/plugins/app/HelloAppPlugin.kt"));
        assertTrue(source.contains("class HelloAppPlugin"));
        assertTrue(source.contains("pluginId = \"hello-app\""));
        assertFalse(Files.readString(dir.resolve("build.gradle.kts")).contains("project(\":plugin-api\")"));
        try (JarFile jar = new JarFile(dir.resolve("libs/plugin-api.jar").toFile())) {
            assertTrue(jar.getJarEntry("com/pmcl/plugin/PmclPlugin.class") != null);
        }
    }

    @Test
    void occupiedFolderIsLeftAlone() throws Exception {
        Path occupied = temp.resolve("pmcl.hello-app");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("keep.txt"), "stay");
        IOException error = assertThrows(IOException.class, () ->
                DevProjects.create(temp, DevProjects.Kind.ECXP_STUDIO, "Hello App", "pmcl.hello-app"));
        assertEquals("exists", error.getMessage());
        assertEquals("stay", Files.readString(occupied.resolve("keep.txt")));
    }
}
