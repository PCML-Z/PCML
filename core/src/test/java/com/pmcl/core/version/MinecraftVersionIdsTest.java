package com.pmcl.core.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftVersionIdsTest {

    @Test
    void keepsSnapshotReleaseCandidateAndPreReleaseIds() {
        assertEquals("26.4-snapshot-2", MinecraftVersionIds.gameVersion("26.4-snapshot-2"));
        assertEquals("26.3-rc-3", MinecraftVersionIds.gameVersion("26.3-rc-3"));
        assertEquals("26.3-pre-2", MinecraftVersionIds.gameVersion("26.3-pre-2"));
        assertEquals("1.16-pre1", MinecraftVersionIds.gameVersion("1.16-pre1"));
        assertEquals("1.21.8-rc1", MinecraftVersionIds.gameVersion("1.21.8-rc1"));
        assertEquals("24w14a", MinecraftVersionIds.gameVersion("24w14a"));
        assertEquals("26.3", MinecraftVersionIds.gameVersion("26.3"));
        assertEquals("b1.7.3", MinecraftVersionIds.gameVersion("b1.7.3"));
    }

    @Test
    void stripsLoaderSuffixFromModdedIds() {
        assertEquals("1.21.8", MinecraftVersionIds.gameVersion("1.21.8-fabric-0.16.14"));
        assertEquals("1.21.8", MinecraftVersionIds.gameVersion("1.21.8-NeoForge-21.8.4-beta"));
        assertEquals("1.21.1", MinecraftVersionIds.gameVersion("1.21.1-52.1.9"));
        assertEquals("1.8.9", MinecraftVersionIds.gameVersion("1.8.9-11.15.1.2318-1.8.9"));
        assertEquals("26.4-snapshot-2", MinecraftVersionIds.gameVersion(
                "fabric-loader-0.16.14-26.4-snapshot-2", "26.4-snapshot-2"));
    }
}
