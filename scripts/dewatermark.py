"""
Final ImageGen post-processor v4 — pink BG false-positive fix.

Key insight: ImageGen's "transparent" bg is a GRAY checkerboard, not colorful.
BG palette detection must require R≈G≈B (gray-ish) to avoid matching
content-colored pixels (pink/blue/yellow that happen to be common).
"""

from PIL import Image
from collections import Counter

def quant(rgb, n=4):
    q = 256 >> n
    return (rgb[0] // q * q, rgb[1] // q * q, rgb[2] // q * q)

def color_dist(a, b):
    return abs(a[0]-b[0]) + abs(a[1]-b[1]) + abs(a[2]-b[2])

def is_gray(rgb, tol=12):
    """True for near-equal channels (grays); false for colorful pixels."""
    return max(rgb) - min(rgb) <= tol

def detect_bg_palette(img, n_top=4):
    """Sample interior, subsample 4x, keep only GRAY colors (max-min <= 12),
    then pick top-N most frequent as BG palette. Default 4 catches multi-tone checkers."""
    px = img.load()
    w, h = img.size
    pad_x, pad_y = w // 10, h // 10
    qcnt = Counter()
    for y in range(pad_y, h - pad_y, 4):
        for x in range(pad_x, w - pad_x, 4):
            c = px[x, y]
            if is_gray(c):
                qcnt[quant(c)] += 1
    if not qcnt:
        return []
    top = [list(rgb) for rgb, _ in qcnt.most_common(n_top)]
    return top

def is_bg(rgb, palette, thresh=28):
    r, g, b = rgb
    for br, bg, bb in palette:
        if (r - br) ** 2 + (g - bg) ** 2 + (b - bb) ** 2 < thresh ** 2:
            return True
    return False

def clean_image(src, dst, thresh=28):
    img = Image.open(src).convert("RGB")
    w, h = img.size
    px = img.load()
    print(f"--- {src}")
    print(f"  src: {w}x{h}")

    palette = detect_bg_palette(img)
    print(f"  BG palette (gray only): {palette}")

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out_px = out.load()
    bg_count = 0
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if is_bg((r, g, b), palette, thresh=thresh):
                bg_count += 1
            else:
                out_px[x, y] = (r, g, b, 255)
    total = w * h
    print(f"  BG cleared: {bg_count}/{total} ({bg_count * 100 // total}%)")

    WM_W, WM_H = 320, 90
    x0, y0 = w - WM_W, h - WM_H
    wm_light = wm_dark = 0
    for y in range(max(0, y0), h):
        for x in range(max(0, x0), w):
            r, g, b, a = out_px[x, y]
            if a == 0:
                continue
            if r > 160 and g > 160 and b > 160:
                out_px[x, y] = (0, 0, 0, 0)
                wm_light += 1
            elif r < 80 and g < 80 and b < 110:
                out_px[x, y] = (0, 0, 0, 0)
                wm_dark += 1
    print(f"  Watermark: light={wm_light}, dark={wm_dark}")

    border = 0
    for x in range(w):
        for y in (0, min(8, h - 1)):
            r, g, b, a = out_px[x, y]
            if a > 0 and r < 80 and g < 80 and b < 110:
                out_px[x, y] = (0, 0, 0, 0); border += 1
            y2 = h - 1 - y
            r, g, b, a = out_px[x, y2]
            if a > 0 and r < 80 and g < 80 and b < 110:
                out_px[x, y2] = (0, 0, 0, 0); border += 1
    for y in range(h):
        for x in (0, min(8, w - 1)):
            r, g, b, a = out_px[x, y]
            if a > 0 and r < 80 and g < 80 and b < 110:
                out_px[x, y] = (0, 0, 0, 0); border += 1
            x2 = w - 1 - x
            r, g, b, a = out_px[x2, y]
            if a > 0 and r < 80 and g < 80 and b < 110:
                out_px[x2, y] = (0, 0, 0, 0); border += 1
    print(f"  Border dark pixels cleared: {border}")

    out.save(dst)
    print(f"  Saved → {dst}  {out.size} {out.mode}")

if __name__ == "__main__":
    files = [
        ("/Users/peddlejumper/PMCL/generated-images/Cute_modern_pixel_art_typograp_2026-08-05T14-43-39.png",
         "/Users/peddlejumper/PMCL/generated-images/Cute_modern_pixel_art_typograp_2026-08-05T14-43-39_clean.png"),
        ("/Users/peddlejumper/PMCL/generated-images/Modern_cute_kawaii_cat_themed__2026-08-05T14-41-53.png",
         "/Users/peddlejumper/PMCL/generated-images/Modern_cute_kawaii_cat_themed__2026-08-05T14-41-53_clean.png"),
        ("/Users/peddlejumper/PMCL/generated-images/Retro_16_bit_pixel_art_banner__2026-08-05T14-37-09.png",
         "/Users/peddlejumper/PMCL/generated-images/Retro_16_bit_pixel_art_banner__2026-08-05T14-37-09_clean.png"),
    ]
    for src, dst in files:
        try:
            clean_image(src, dst)
        except FileNotFoundError as e:
            print(f"!! skip: {e}")
