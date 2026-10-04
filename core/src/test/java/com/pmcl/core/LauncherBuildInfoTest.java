package com.pmcl.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LauncherBuildInfoTest {

    @Test
    void frameworkAndRuntimeUseOsAndArch() {
        assertEquals("Java 21-osx", LauncherBuildInfo.frameworkOf("21", "Mac OS X"));
        assertEquals("osx-arm64", LauncherBuildInfo.runtimeOf("Mac OS X", "aarch64"));
        assertEquals("windows-x64", LauncherBuildInfo.runtimeOf("Windows 11", "amd64"));
        assertEquals("linux-x86", LauncherBuildInfo.runtimeOf("Linux", "i686"));
        assertEquals("Java 21", LauncherBuildInfo.frameworkOf("21", "FreeBSD"));
        assertEquals("", LauncherBuildInfo.runtimeOf("", ""));
    }
}
