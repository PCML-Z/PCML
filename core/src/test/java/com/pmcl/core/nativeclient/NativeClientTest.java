package com.pmcl.core.nativeclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeClientTest {

    @TempDir
    Path dir;

    @Test
    void picksTheCurrentPlatformPackage() {
        String json = """
                [
                  {"tag_name":"v1.0.0","draft":false,"assets":[
                    {"name":"RustCraft-1.0.0-x86_64.AppImage","browser_download_url":"https://github.com/RustCraftMC/RustCraft-Public/releases/download/v1.0.0/RustCraft-1.0.0-x86_64.AppImage","size":20},
                    {"name":"RustCraft-Linux-x86_64.zip","browser_download_url":"https://github.com/RustCraftMC/RustCraft-Public/releases/download/v1.0.0/RustCraft-Linux-x86_64.zip","size":20},
                    {"name":"RustCraft-windows-x86_64.1.zip","browser_download_url":"https://github.com/RustCraftMC/RustCraft-Public/releases/download/v1.0.0/RustCraft-windows-x86_64.1.zip","size":20},
                    {"name":"RustCraft-windows-x86_64.zip","browser_download_url":"https://github.com/RustCraftMC/RustCraft-Public/releases/download/v1.0.0/RustCraft-windows-x86_64.zip","size":20}
                  ]}
                ]
                """;
        assertEquals("RustCraft-windows-x86_64.zip",
                NativeClientCatalog.selectAsset(json, "windows", "x64").name);
        assertEquals("RustCraft-Linux-x86_64.zip",
                NativeClientCatalog.selectAsset(json, "linux", "x64").name);
        assertNull(NativeClientCatalog.selectAsset(json, "macos", "aarch64"));
        assertNull(NativeClientCatalog.selectAsset(json, "linux", "aarch64"));
    }

    @Test
    void keepsOtherOptionsWhenForcingVulkan() {
        String updated = NativeClientCatalog.withBackend("lang:zh_cn\nrustRenderBackend:opengl\n", "rustRenderBackend", "vulkan");
        assertTrue(updated.contains("lang:zh_cn"));
        assertTrue(updated.contains("rustRenderBackend:vulkan"));
        assertFalse(updated.contains("opengl"));
    }

    @Test
    void stagesLocalAssetsAndSkipsPathEscape() throws Exception {
        String hash = "ab0123456789abcdef0123456789abcdef012345";
        Path objects = dir.resolve("assets/objects");
        Files.createDirectories(objects.resolve("ab"));
        Files.writeString(objects.resolve("ab").resolve(hash), "snd");
        Path index = dir.resolve("assets/indexes/1.12.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, """
                {"objects":{
                  "minecraft/sounds.json":{"hash":"%s","size":3},
                  "../outside.txt":{"hash":"%s","size":3}
                }}
                """.formatted(hash, hash));
        Path version = dir.resolve("versions/1.12.2");
        Files.createDirectories(version);
        Files.writeString(version.resolve("1.12.2.json"), "{\"assetIndex\":{\"id\":\"1.12\"}}");
        Path jar = version.resolve("1.12.2.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("assets/minecraft/lang/en_us.lang"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/minecraft/../../evil.txt"));
            zip.write("no".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        NativeClientAssets.Located located = NativeClientAssets.locate(dir, "1.12.2");
        assertNotNull(located);
        assertEquals("1.12", NativeClientAssets.indexId(version.resolve("1.12.2.json"), "1.12.2"));
        Path work = dir.resolve("client");
        Files.createDirectories(work);
        NativeClientAssets.stage(NativeClientCatalog.find("mc112-rust"), work, located);
        assertEquals("snd", Files.readString(work.resolve("runtime/assets/minecraft/sounds.json")));
        assertEquals("hello", Files.readString(work.resolve("runtime/assets/minecraft/lang/en_us.lang")));
        assertFalse(Files.exists(work.resolve("runtime/assets/outside.txt")));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    @Test
    void picksPommeOnMacosArm64AndKeepsRustCraftUnavailable() {
        String pomme = """
                [
                  {"tag_name":"client-v0.2.4+26.2","draft":false,"assets":[
                    {"name":"pomme-client-linux-x64-gnu.zip","browser_download_url":"https://github.com/PommeMC/Client/releases/download/client-v0.2.4+26.2/pomme-client-linux-x64-gnu.zip","size":30},
                    {"name":"pomme-client-macos-arm64.zip","browser_download_url":"https://github.com/PommeMC/Client/releases/download/client-v0.2.4+26.2/pomme-client-macos-arm64.zip","size":30},
                    {"name":"pomme-client-windows-x64.zip","browser_download_url":"https://github.com/PommeMC/Client/releases/download/client-v0.2.4+26.2/pomme-client-windows-x64.zip","size":30},
                    {"name":"sha256sums.txt","browser_download_url":"https://github.com/PommeMC/Client/releases/download/client-v0.2.4+26.2/sha256sums.txt","size":1}
                  ]}
                ]
                """;
        assertEquals("pomme-client-macos-arm64.zip",
                NativeClientCatalog.selectPublished(NativeClientCatalog.find("pomme"), pomme, "macos", "aarch64").name);
        assertEquals("pomme-client-linux-x64-gnu.zip",
                NativeClientCatalog.selectAsset(pomme, "linux", "x64").name);
        assertNull(NativeClientCatalog.selectAsset(pomme, "macos", "x64"));
        assertFalse(NativeClientCatalog.find("rustcraft").publishesHere("macos", "aarch64"));
        assertTrue(NativeClientCatalog.find("classicube").publishesHere("macos", "aarch64"));
        assertEquals(28, NativeClientCatalog.all().size());
    }

    @Test
    void picksMccAndCinnabarPackagesAndLeavesDiskImagesUnselected() {
        String mcc = """
                [
                  {"tag_name":"20260929-519","draft":false,"assets":[
                    {"name":"MinecraftClient-20260929-519-linux-arm64","browser_download_url":"https://example.invalid/linux-arm64","size":20},
                    {"name":"MinecraftClient-20260929-519-linux-x64","browser_download_url":"https://example.invalid/linux-x64","size":20},
                    {"name":"MinecraftClient-20260929-519-osx-arm64","browser_download_url":"https://example.invalid/osx-arm64","size":20},
                    {"name":"MinecraftClient-20260929-519-osx-x64","browser_download_url":"https://example.invalid/osx-x64","size":20},
                    {"name":"MinecraftClient-20260929-519-win-x64.exe","browser_download_url":"https://example.invalid/win-x64","size":20},
                    {"name":"SHA256SUMS.txt","browser_download_url":"https://example.invalid/sums","size":1}
                  ]}
                ]
                """;
        assertEquals("MinecraftClient-20260929-519-osx-arm64",
                NativeClientCatalog.selectPublished(NativeClientCatalog.find("mcc"), mcc, "macos", "aarch64").name);
        assertEquals("MinecraftClient-20260929-519-linux-x64",
                NativeClientCatalog.selectAsset(mcc, "linux", "x64").name);
        assertEquals("MinecraftClient-20260929-519-win-x64.exe",
                NativeClientCatalog.selectAsset(mcc, "windows", "x64").name);

        String cinnabar = """
                [
                  {"tag_name":"v0.1.2","draft":false,"assets":[
                    {"name":"Cinnabar-arm64.dmg","browser_download_url":"https://example.invalid/arm.dmg","size":20},
                    {"name":"Cinnabar-x64-setup.exe","browser_download_url":"https://example.invalid/setup.exe","size":20},
                    {"name":"Cinnabar-x64.msi","browser_download_url":"https://example.invalid/setup.msi","size":20},
                    {"name":"Cinnabar-x86_64.AppImage","browser_download_url":"https://example.invalid/appimage","size":20},
                    {"name":"SHA256SUMS.txt","browser_download_url":"https://example.invalid/sums","size":1}
                  ]}
                ]
                """;
        assertEquals("Cinnabar-x86_64.AppImage",
                NativeClientCatalog.selectPublished(NativeClientCatalog.find("cinnabar"), cinnabar, "linux", "x64").name);
        assertNull(NativeClientCatalog.selectPublished(NativeClientCatalog.find("cinnabar"), cinnabar, "macos", "aarch64"));
        assertNull(NativeClientCatalog.selectAsset(cinnabar, "macos", "aarch64"));

        String crosscraft = """
                [
                  {"tag_name":"v1.3","draft":false,"assets":[
                    {"name":"CrossCraft-Linux.zip","browser_download_url":"https://example.invalid/linux.zip","size":20},
                    {"name":"CrossCraft-Windows.zip","browser_download_url":"https://example.invalid/windows.zip","size":20},
                    {"name":"CrossCraft-PSP.zip","browser_download_url":"https://example.invalid/psp.zip","size":20}
                  ]}
                ]
                """;
        assertEquals("CrossCraft-Linux.zip",
                NativeClientCatalog.selectPublished(NativeClientCatalog.find("crosscraft"), crosscraft, "linux", "x64").name);
        assertNull(NativeClientCatalog.selectPublished(NativeClientCatalog.find("crosscraft"), crosscraft, "macos", "aarch64"));
        assertFalse(NativeClientCatalog.find("truecraft").publishesHere("macos", "aarch64"));
        assertTrue(NativeClientCatalog.find("bedrock").publishesHere("windows", "x64"));
        String corncraft = """
                [
                  {"tag_name":"v0.2.1","draft":false,"assets":[
                    {"name":"CornCraft.v0.2.1.zip","browser_download_url":"https://example.invalid/corn.zip","size":20}
                  ]}
                ]
                """;
        assertEquals("CornCraft.v0.2.1.zip",
                NativeClientCatalog.selectPublished(NativeClientCatalog.find("corncraft"), corncraft, "windows", "x64").name);
        assertNull(NativeClientCatalog.selectPublished(NativeClientCatalog.find("corncraft"), corncraft, "macos", "aarch64"));
    }

    @Test
    void acceptsAUniversalMacPackageAndSkipsAJarOnAnUndeclaredSystem() {
        String mac = """
                [
                  {"tag_name":"1.3.8","draft":false,"assets":[
                    {"name":"ClassiCube-windows-x64.zip","browser_download_url":"https://github.com/ClassiCube/ClassiCube/releases/download/1.3.8/ClassiCube-windows-x64.zip","size":20},
                    {"name":"ClassiCube-macOS.zip","browser_download_url":"https://github.com/ClassiCube/ClassiCube/releases/download/1.3.8/ClassiCube-macOS.zip","size":20},
                    {"name":"ClassiCube-macOS-arm64.zip","browser_download_url":"https://github.com/ClassiCube/ClassiCube/releases/download/1.3.8/ClassiCube-macOS-arm64.zip","size":20}
                  ]}
                ]
                """;
        assertEquals("ClassiCube-macOS-arm64.zip", NativeClientCatalog.selectAsset(mac, "macos", "aarch64").name);
        String universalOnly = """
                [
                  {"tag_name":"1.3.8","draft":false,"assets":[
                    {"name":"ClassiCube-windows-x64.zip","browser_download_url":"https://github.com/ClassiCube/ClassiCube/releases/download/1.3.8/ClassiCube-windows-x64.zip","size":20},
                    {"name":"ClassiCube-macOS.zip","browser_download_url":"https://github.com/ClassiCube/ClassiCube/releases/download/1.3.8/ClassiCube-macOS.zip","size":20}
                  ]}
                ]
                """;
        assertEquals("ClassiCube-macOS.zip", NativeClientCatalog.selectAsset(universalOnly, "macos", "aarch64").name);

        String jar = """
                [
                  {"tag_name":"v0.0.1-alpha","draft":false,"assets":[
                    {"name":"LeafishInstaller.jar","browser_download_url":"https://github.com/Lea-fish/Leafish/releases/download/v0.0.1-alpha/LeafishInstaller.jar","size":20}
                  ]}
                ]
                """;
        assertEquals("LeafishInstaller.jar",
                NativeClientCatalog.selectPublished(NativeClientCatalog.find("leafish"), jar, "windows", "x64").name);
        assertNull(NativeClientCatalog.selectPublished(NativeClientCatalog.find("leafish"), jar, "macos", "aarch64"));
    }

    @Test
    void compileUsesTheSelectedToolchainAndRemembersIt() throws Exception {
        Path project = dir.resolve("steven");
        List<List<String>> cargo = NativeClientCompiler.commands(
                NativeClientCatalog.BuildTool.CARGO, Path.of("/usr/local/bin/cargo"), project, "steven");
        assertEquals(List.of(List.of(
                Path.of("/usr/local/bin/cargo").toAbsolutePath().normalize().toString(),
                "build", "--release")), cargo);
        List<String> go = NativeClientCompiler.commands(
                NativeClientCatalog.BuildTool.GO, Path.of("/usr/bin/go"), project, "steven").get(0);
        List<String> zig = NativeClientCompiler.commands(
                NativeClientCatalog.BuildTool.ZIG, Path.of("/usr/bin/zig"), project, "maincraft").get(0);
        assertEquals("build", zig.get(1));
        assertEquals("-Doptimize=ReleaseFast", zig.get(2));
        assertEquals("build", go.get(1));
        assertEquals("-o", go.get(2));
        assertEquals(".", go.get(4));
        assertFalse(go.stream().anyMatch(part -> part.equals("sh") || part.contains("&&")));

        IOException missing = assertThrows(IOException.class, () -> NativeClientCompiler.requireToolchain("  ", dir));
        assertFalse(missing.getMessage().isBlank());
        Path planted = dir.resolve("cargo");
        Files.writeString(planted, "");
        assertThrows(IOException.class, () -> NativeClientCompiler.requireToolchain(planted.toString(), dir));

        Path bin = dir.resolve("bin");
        Files.createDirectories(bin);
        Path tool = bin.resolve("cargo");
        Files.writeString(tool, "");
        assertEquals(tool.toAbsolutePath().normalize().toString(),
                NativeClientCompiler.detect(NativeClientCatalog.BuildTool.CARGO, bin.toString()));
        NativeClientCompiler.save(dir, "cargo", tool.toString());
        assertEquals(tool.toAbsolutePath().normalize().toString(),
                NativeClientCompiler.saved(dir, NativeClientCatalog.BuildTool.CARGO));

        Path extracted = dir.resolve("src");
        Files.createDirectories(extracted.resolve("Repo-main/inner"));
        Files.writeString(extracted.resolve("Repo-main/Cargo.toml"), "[package]\n");
        Files.writeString(extracted.resolve("Repo-main/inner/Cargo.toml"), "[package]\n");
        assertEquals(extracted.resolve("Repo-main"),
                NativeClientCompiler.findProject(extracted, NativeClientCatalog.BuildTool.CARGO));
    }

    @Test
    void stagesPommeAssetsUnderTheVersionName() throws Exception {
        String hash = "ab0123456789abcdef0123456789abcdef012345";
        Path objects = dir.resolve("assets/objects/ab");
        Files.createDirectories(objects);
        Files.writeString(objects.resolve(hash), "snd");
        Path index = dir.resolve("assets/indexes/32.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, """
                {"objects":{"minecraft/sounds.json":{"hash":"%s","size":3}}}
                """.formatted(hash));
        Path version = dir.resolve("versions/26.2");
        Files.createDirectories(version);
        Files.writeString(version.resolve("26.2.json"), "{\"assetIndex\":{\"id\":\"32\"}}");
        Path jar = version.resolve("26.2.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("assets/minecraft/lang/en_us.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        NativeClientAssets.Located located = NativeClientAssets.locate(dir, "26.2");
        assertNotNull(located);
        Path runtime = dir.resolve("runtime");
        NativeClientAssets.stagePomme(runtime, "26.2", located);
        assertTrue(Files.isRegularFile(runtime.resolve("assets/indexes/26.2.json")));
        assertEquals("snd", Files.readString(runtime.resolve("assets/objects/ab").resolve(hash)));
        assertEquals("{}", Files.readString(runtime.resolve("versions/26.2/extracted/assets/minecraft/lang/en_us.json")));
    }
}
