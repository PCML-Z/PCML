package com.pmcl.core.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CurseForgeClientTest {

    @Test
    void versionHintUsesLastNumericSegment() {
        assertEquals("0.5.11+mc1.20.1",
                CurseForgeClient.versionHintFromFileName("sodium-fabric-0.5.11+mc1.20.1.jar"));
        assertEquals("0.5.8",
                CurseForgeClient.versionHintFromFileName("jei-1.20.1-forge-0.5.8.jar"));
        assertEquals("", CurseForgeClient.versionHintFromFileName("readme.txt"));
    }

    @Test
    void filesEndpointLoaderTypeIsIntegerEnum() {
        assertEquals(1, CurseForgeClient.modLoaderTypeId("forge"));
        assertEquals(4, CurseForgeClient.modLoaderTypeId("Fabric"));
        assertEquals(5, CurseForgeClient.modLoaderTypeId("quilt"));
        assertEquals(6, CurseForgeClient.modLoaderTypeId("NeoForge"));
        assertEquals(null, CurseForgeClient.modLoaderTypeId("unknown"));
        assertEquals(null, CurseForgeClient.modLoaderTypeId(null));
    }

    @Test
    void searchClassIdAndSortFieldMatchApiEnums() {
        assertEquals(null, CurseForgeClient.classIdForType(null));
        assertEquals(null, CurseForgeClient.classIdForType(""));
        assertEquals(6, CurseForgeClient.classIdForType("mod"));
        assertEquals(12, CurseForgeClient.classIdForType("resourcepack"));
        assertEquals(6552, CurseForgeClient.classIdForType("shader"));
        assertEquals(2, CurseForgeClient.sortFieldId("default"));
        assertEquals(6, CurseForgeClient.sortFieldId("downloads"));
        assertEquals(3, CurseForgeClient.sortFieldId("updated"));
        assertEquals(3, CurseForgeClient.sortFieldId("newest"));
        assertEquals(true, CurseForgeClient.shouldSendModLoaderType("1.20.1", "fabric"));
        assertEquals(false, CurseForgeClient.shouldSendModLoaderType("", "fabric"));
        assertEquals(false, CurseForgeClient.shouldSendModLoaderType("1.20.1", "unknown"));
        assertEquals(false, CurseForgeClient.shouldSendModLoaderType(null, "forge"));
        assertEquals("fabric", CurseForgeClient.loaderName(4));
        assertEquals("resourcepack", CurseForgeClient.projectTypeFromClassId(12));
        assertEquals(true, CurseForgeClient.isContentClassId(6));
        assertEquals(false, CurseForgeClient.isContentClassId(4471));
    }
}
