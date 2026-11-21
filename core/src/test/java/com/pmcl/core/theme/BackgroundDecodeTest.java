package com.pmcl.core.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundDecodeTest {

    @Test
    void hugeImageFileIsSkipped() {
        assertFalse(BackgroundDecode.imageFileAllowed(0));
        assertTrue(BackgroundDecode.imageFileAllowed(2L * 1024 * 1024));
        assertFalse(BackgroundDecode.imageFileAllowed(BackgroundDecode.MAX_IMAGE_BYTES + 1));
    }

    @Test
    void videoLongSideIsCapped() {
        assertNull(BackgroundDecode.videoSize(0, 1080));
        assertNull(BackgroundDecode.videoSize(1920, -1));
        assertArrayEquals(new int[] {1280, 720}, BackgroundDecode.videoSize(1280, 720));
        int[] tall = BackgroundDecode.videoSize(2, 100_000);
        assertTrue(tall[0] <= BackgroundDecode.MAX_SIDE);
        assertTrue(tall[1] <= BackgroundDecode.MAX_SIDE);
        assertTrue(tall[0] >= 2 && tall[1] >= 2);
    }

    @Test
    void oversizedFrameIsNotAllocated() {
        assertFalse(BackgroundDecode.frameBytesAllowed(0, 100));
        assertFalse(BackgroundDecode.frameBytesAllowed(8000, 100_000));
        assertTrue(BackgroundDecode.frameBytesAllowed(1600 * 4, 900));
    }
}
