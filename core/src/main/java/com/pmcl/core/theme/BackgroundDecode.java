package com.pmcl.core.theme;

/**
 * 背景图和背景视频的解码上限。超限就跳过，避免一张图或一帧把启动器撑满内存。
 */
public final class BackgroundDecode {

    public static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    public static final int MAX_SIDE = 1600;
    public static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;

    private BackgroundDecode() {}

    public static boolean imageFileAllowed(long sizeBytes) {
        return sizeBytes > 0 && sizeBytes <= MAX_IMAGE_BYTES;
    }

    /** 长边压到 {@link #MAX_SIDE}。宽或高不是正数时返回 null。 */
    public static int[] videoSize(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        int longSide = Math.max(width, height);
        if (longSide <= MAX_SIDE) return new int[] {width, height};
        double scale = (double) MAX_SIDE / longSide;
        int w = evenAtLeast2((int) (width * scale));
        int h = evenAtLeast2((int) (height * scale));
        if (w <= 0 || h <= 0) return null;
        return new int[] {w, h};
    }

    public static boolean frameBytesAllowed(int stride, int height) {
        if (stride < 4 || height <= 0) return false;
        long bytes = (long) stride * height;
        return bytes > 0 && bytes <= MAX_FRAME_BYTES;
    }

    private static int evenAtLeast2(int value) {
        int even = Math.max(2, value) & ~1;
        return even < 2 ? 2 : even;
    }
}
