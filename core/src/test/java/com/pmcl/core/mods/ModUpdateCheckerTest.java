package com.pmcl.core.mods;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModUpdateCheckerTest {

    @Test
    void numericSegmentsCompareLeftToRight() {
        assertTrue(ModUpdateChecker.compareVersions("0.5.11", "0.5.8") > 0);
        assertTrue(ModUpdateChecker.compareVersions("0.5.8", "0.5.11") < 0);
        assertEquals(0, ModUpdateChecker.compareVersions("1.2.3", "1.2.3"));
    }

    @Test
    void preReleaseIsOlderThanRelease() {
        assertTrue(ModUpdateChecker.compareVersions("1.0.0", "1.0.0-beta") > 0);
        assertTrue(ModUpdateChecker.compareVersions("1.0.0-beta", "1.0.0") < 0);
    }

    @Test
    void stripsVPrefix() {
        assertEquals(0, ModUpdateChecker.compareVersions("v1.2.0", "1.2.0"));
    }

    @Test
    void forgeMcPrefixedVersionsCompareModSegment() {
        assertTrue(ModUpdateChecker.compareVersions("1.20.1-10.3.0", "1.20.1-10.2.0") > 0);
        assertTrue(ModUpdateChecker.compareVersions("1.20.1-10.2.0", "1.20.1-10.3.0") < 0);
        assertTrue(ModUpdateChecker.compareVersions("1.20.1-10.3.0", "10.2.0") > 0);
    }

    @Test
    void quiltAcceptsFabricTaggedFiles() {
        assertTrue(ModUpdateChecker.loaderCompatible(java.util.List.of("fabric"), "quilt"));
        assertTrue(ModUpdateChecker.loaderCompatible(java.util.List.of("quilt"), "quilt"));
        org.junit.jupiter.api.Assertions.assertFalse(
                ModUpdateChecker.loaderCompatible(java.util.List.of("forge"), "quilt"));
    }
}
