package com.pmcl.core.modpack;

import com.pmcl.core.instance.InstanceManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModpackForbricTest {

    @Test
    void minecraft262FabricForgeAndNeoforgePacksUseForbric() {
        assertTrue(ModpackManager.modpackUsesForbric("26.2", "fabric"));
        assertTrue(ModpackManager.modpackUsesForbric("26.2", "forge"));
        assertTrue(ModpackManager.modpackUsesForbric("26.2", "neoforge"));
        assertTrue(ModpackManager.modpackUsesForbric("26.2", "forbric"));
        assertFalse(ModpackManager.modpackUsesForbric("26.2", "quilt"));
        assertFalse(ModpackManager.modpackUsesForbric("1.20.1", "fabric"));
        assertFalse(ModpackManager.modpackUsesForbric("26.2", null));
    }

    @Test
    void inProgressModpackDirectoryIsNotAVersionId() {
        assertTrue(InstanceManager.isGeneratedInstanceId("7d3b9d32-8560-4454-9c54-33daef365efd"));
        assertFalse(InstanceManager.isGeneratedInstanceId("26.2-forbric"));
        assertFalse(InstanceManager.isGeneratedInstanceId("1.20.4"));
    }
}
