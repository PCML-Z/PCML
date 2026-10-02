package com.pmcl.core.modloader;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricMetaInstallerTest {

    @Test
    void missingGameVersionIsAnEmptyCatalog() {
        assertTrue(FabricMetaInstaller.unknownGameVersion(
                new IOException("下载失败 code=404 url=https://meta.quiltmc.org/v3/versions/loader/26.4-snapshot-2")));
        assertTrue(FabricMetaInstaller.unknownGameVersion(
                new IOException("下载失败 code=400 url=https://meta.fabricmc.net/v2/versions/loader/26.4")));
        assertFalse(FabricMetaInstaller.unknownGameVersion(new IOException("connect timed out")));
    }
}
