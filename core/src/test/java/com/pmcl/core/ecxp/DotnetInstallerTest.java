package com.pmcl.core.ecxp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetInstallerTest {

    private static final String HASH = "a".repeat(128);

    @Test
    void ridFollowsOsAndArch() throws Exception {
        assertEquals("osx-arm64", DotnetInstaller.rid("Mac OS X", "aarch64"));
        assertEquals("osx-x64", DotnetInstaller.rid("Mac OS X", "x86_64"));
        assertEquals("win-x64", DotnetInstaller.rid("Windows 11", "amd64"));
        assertEquals("linux-arm64", DotnetInstaller.rid("Linux", "arm64"));
        assertThrows(java.io.IOException.class, () -> DotnetInstaller.rid("FreeBSD", "amd64"));
    }

    @Test
    void selectUsesLatestRuntimeForThisMachine() throws Exception {
        String index = """
                {"releases-index":[
                  {"channel-version":"11.0","latest-runtime":"11.0.1",
                   "releases.json":"https://builds.dotnet.microsoft.com/dotnet/release-metadata/11.0/releases.json"},
                  {"channel-version":"10.0","latest-runtime":"10.0.2",
                   "releases.json":"https://builds.dotnet.microsoft.com/dotnet/release-metadata/10.0/releases.json"}
                ]}
                """;
        String releases = """
                {"releases":[
                  {"runtime":{"version":"11.0.0","files":[
                    {"name":"dotnet-runtime-osx-arm64.tar.gz","rid":"osx-arm64",
                     "url":"https://builds.dotnet.microsoft.com/dotnet/Runtime/11.0.0/dotnet-runtime-11.0.0-osx-arm64.tar.gz",
                     "hash":"%s"}]}},
                  {"runtime":{"version":"11.0.1","files":[
                    {"name":"dotnet-runtime-win-x64.exe","rid":"win-x64",
                     "url":"https://builds.dotnet.microsoft.com/dotnet/Runtime/11.0.1/dotnet-runtime-11.0.1-win-x64.exe",
                     "hash":"%s"},
                    {"name":"dotnet-runtime-osx-arm64.tar.gz","rid":"osx-arm64",
                     "url":"https://builds.dotnet.microsoft.com/dotnet/Runtime/11.0.1/dotnet-runtime-11.0.1-osx-arm64.tar.gz",
                     "hash":"%s"}]}}
                ]}
                """.formatted(HASH, HASH, HASH);
        DotnetInstaller.Package pkg = DotnetInstaller.select(index, releases, 11, "osx-arm64");
        assertEquals("11.0.1", pkg.version());
        assertEquals("dotnet-runtime-11.0.1-osx-arm64.tar.gz", pkg.fileName());
        assertTrue(pkg.url().contains("/11.0.1/"));
        assertThrows(java.io.IOException.class,
                () -> DotnetInstaller.select(index, releases, 11, "linux-x64"));
        assertThrows(java.io.IOException.class, () -> DotnetInstaller.select(
                index, releases.replace("builds.dotnet.microsoft.com", "evil.example"), 11, "osx-arm64"));
        assertThrows(java.io.IOException.class, () -> DotnetInstaller.releasesUrl(
                index.replace("builds.dotnet.microsoft.com", "evil.example"), 11));
    }

    @Test
    void preferTheNewestInstalledHost() {
        DotnetRuntime older = DotnetRuntime.fromLines(Path.of("/usr/local/share/dotnet/dotnet"),
                List.of("Microsoft.NETCore.App 8.0.11 [/usr/local/share/dotnet/shared/Microsoft.NETCore.App]"));
        DotnetRuntime newer = DotnetRuntime.fromLines(Path.of("/Users/me/.dotnet/dotnet"),
                List.of("Microsoft.NETCore.App 11.0.1 [/Users/me/.dotnet/shared/Microsoft.NETCore.App]"));
        DotnetRuntime empty = DotnetRuntime.fromLines(Path.of("/opt/dotnet"), List.of());
        assertEquals(newer.host(), DotnetRuntime.prefer(List.of(older, newer)).host());
        assertEquals(older.host(), DotnetRuntime.prefer(List.of(older, empty)).host());
        assertFalse(DotnetRuntime.prefer(List.of()).found());
        assertTrue(newer.hasMajor(11));
    }
}
