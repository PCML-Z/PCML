package com.pmcl.core.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderLoadersTest {

    @Test
    void normalizeMarketNames() {
        assertEquals("iris", ShaderLoaders.normalize("Iris"));
        assertEquals("iris", ShaderLoaders.normalize("Oculus"));
        assertEquals("optifine", ShaderLoaders.normalize("OptiFine"));
        assertEquals("canvas", ShaderLoaders.normalize("Canvas"));
        assertEquals("vanilla", ShaderLoaders.normalize("Vanilla"));
        assertEquals("", ShaderLoaders.normalize("Fabric"));
    }

    @Test
    void detectInstalledShaderLoaders() {
        assertEquals(List.of("optifine"), ShaderLoaders.detect(
                "1.20.1-OptiFine_HD_U_I6", "1.20.1", List.of(), List.of()));
        assertEquals(List.of("iris"), ShaderLoaders.detect(
                "fabric-loader-0.16.9-1.21.1", "1.21.1", List.of("iris"), List.of("iris-fabric.jar")));
        assertEquals(List.of("iris"), ShaderLoaders.detect(
                "1.20.1-forge", "1.20.1", List.of("oculus"), List.of("oculus-mc1.20.1.jar")));
        assertEquals(List.of("canvas"), ShaderLoaders.detect(
                "1.20.1", null, List.of("canvas"), List.of("canvas-mc120.jar")));
        assertEquals(List.of("vanilla"), ShaderLoaders.detect(
                "1.21.1", null, List.of("sodium"), List.of("sodium.jar")));
        assertEquals(List.of("iris", "optifine"), ShaderLoaders.detect(
                "1.21.1", null, List.of("iris", "optifine"), List.of()));
    }
}
