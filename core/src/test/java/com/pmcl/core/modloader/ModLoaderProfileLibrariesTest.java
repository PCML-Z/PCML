package com.pmcl.core.modloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModLoaderProfileLibrariesTest {

    @TempDir
    Path tmp;

    @Test
    void healthyRequiresMatchingSha1() throws Exception {
        Path file = tmp.resolve("lib.jar");
        byte[] data = new byte[64];
        Arrays.fill(data, (byte) 'a');
        Files.write(file, data);
        String sha1 = sha1Hex(data);
        assertTrue(ModLoaderProfileLibraries.isHealthy(file, sha1));
        assertFalse(ModLoaderProfileLibraries.isHealthy(file, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertTrue(ModLoaderProfileLibraries.isHealthy(file, null));
    }

    private static String sha1Hex(byte[] data) throws Exception {
        byte[] dig = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
