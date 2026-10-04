package com.pmcl.core.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModLoadersTest {

    @Test
    void readsLoaderFromModernVersionIds() {
        assertEquals("forge", ModLoaders.fromVersion(
                "1.20.1-forge-47.3.0", "1.20.1", "cpw.mods.modlauncher.Launcher"));
        assertEquals("neoforge", ModLoaders.fromVersion(
                "neoforge-21.1.172", "1.21.1", "cpw.mods.modlauncher.Launcher"));
        assertEquals("fabric", ModLoaders.fromVersion(
                "fabric-loader-0.16.9-1.21.1", "1.21.1", "net.fabricmc.loader.impl.launch.knot.KnotClient"));
        assertEquals("quilt", ModLoaders.fromVersion(
                "quilt-loader-0.26.0-1.20.1", "1.20.1", "org.quiltmc.loader.impl.launch.knot.KnotClient"));
        assertEquals("", ModLoaders.fromVersion("1.21.1", null, "net.minecraft.client.main.Main"));
        assertEquals("forbric", ModLoaders.fromVersion(
                "26.2-forbric", "26.2", "net.fabricmc.loader.impl.launch.knot.KnotClient"));
    }

    @Test
    void normalizeKeepsNeoforgeDistinctFromForge() {
        assertEquals("neoforge", ModLoaders.normalize("NeoForge"));
        assertEquals("forge", ModLoaders.normalize("Forge"));
        assertEquals("", ModLoaders.normalize("vanilla"));
    }
}
