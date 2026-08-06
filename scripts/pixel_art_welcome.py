"""
Pixel art + transparent background pipeline.

Source: white-ish screenshot of "欢迎使用 PMCL".
Goal : pixel-art render of the text on transparent BG.

Steps:
 1. Crop to non-bg bbox with a tiny padding.
 2. Detect BG color (sampled from corners), record for step 4.
 3. Downscale (LANCZOS) to a small target width, then upscale (NEAREST)
    so each "pixel art cell" is several original pixels wide.
 4. Make any pixel that is too close to the BG color fully transparent,
    otherwise keep it.  No smoothing / dither — hard edges for the
    pixel art look.
 5. Save as PNG (RGBA).
"""

from PIL import Image
from collections import deque

SRC = "/Users/peddlejumper/Desktop/截屏2026-08-05 22.30.10.png"
DST = "/Users/peddlejumper/PMCL/generated-images/Welcome_PMCL_pixel_art_transparent_2026-08-05.png"

# -------- Step 1: open and detect bbox of non-bg content ----------
img = Image.open(SRC).convert("RGBA")
w0, h0 = img.size

# BG color from corner pixels (smallest luminance / most "empty").
corners = [img.getpixel((0, 0)),
           img.getpixel((w0 - 1, 0)),
           img.getpixel((0, h0 - 1)),
           img.getpixel((w0 - 1, h0 - 1))]
# Average the four corners — that's our BG sample.
bg_r = sum(c[0] for c in corners) // 4
bg_g = sum(c[1] for c in corners) // 4
bg_b = sum(c[2] for c in corners) // 4
BG = (bg_r, bg_g, bg_b)
print(f"[step1] image={w0}x{h0} sampled BG={BG}")

# Bounding box of "text-ish" pixels using luminance distance to BG.
def is_bg(px):
    r, g, b = px[0], px[1], px[2]
    # Euclidean distance from BG.
    return (r - BG[0]) ** 2 + (g - BG[1]) ** 2 + (b - BG[2]) ** 2 < 8 ** 2

minx, miny, maxx, maxy = w0, h0, -1, -1
for y in range(h0):
    for x in range(w0):
        if not is_bg(img.getpixel((x, y))):
            if x < minx: minx = x
            if y < miny: miny = y
            if x > maxx: maxx = x
            if y > maxy: maxy = y

pad = 4
minx = max(0, minx - pad); miny = max(0, miny - pad)
maxx = min(w0 - 1, maxx + pad); maxy = min(h0 - 1, maxy + pad)
crop = img.crop((minx, miny, maxx + 1, maxy + 1)).convert("RGB")
cw, ch = crop.size
print(f"[step1] bbox=({minx},{miny})-({maxx},{maxy}) crop={cw}x{ch}")

# -------- Step 3: pixelation via down-then-up resize ----------
# Target small width: how chunky?  ~120 cells wide gives a clear pixel feel.
TARGET_W = 160
scale = TARGET_W / cw
target_h = max(8, round(ch * scale))
small = crop.resize((TARGET_W, target_h), Image.LANCZOS)
chunk = small.resize((cw, ch), Image.NEAREST)
print(f"[step3] pixel cells = {TARGET_W}x{target_h}, chunk back to {cw}x{ch}")

# -------- Step 4: mark BG pixels transparent ----------
# On the chunky image, do an exact color-distance threshold: every pixel
# whose distance to BG is small becomes fully transparent.  We use a
# BFS starting from all four edges so that any continuous BG region
# (even if some pixels are "different" due to AA averaging) becomes
# transparent, but isolated inside-letter specks stay.
THRESH_SQ = 16 ** 2  # Euclidean squared distance in RGB
px = chunk.load()
visited = [[False] * ch for _ in range(cw)]
queue = deque()

def is_bgish(rgb):
    r, g, b = rgb
    dr = r - BG[0]; dg = g - BG[1]; db = b - BG[2]
    return dr * dr + dg * dg + db * db <= THRESH_SQ

# Seed from every edge pixel that itself looks like background.
for x in range(cw):
    for y in (0, ch - 1):
        if is_bgish(px[x, y]) and not visited[x][y]:
            visited[x][y] = True
            queue.append((x, y))
for y in range(ch):
    for x in (0, cw - 1):
        if is_bgish(px[x, y]) and not visited[x][y]:
            visited[x][y] = True
            queue.append((x, y))

# Flood: only expand into neighbors that also look like background.
while queue:
    x, y = queue.popleft()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < cw and 0 <= ny < ch and not visited[nx][ny]:
            if is_bgish(px[nx, ny]):
                visited[nx][ny] = True
                queue.append((nx, ny))

# Build alpha mask from "inside-text" (not visited BFS region).
# Also: any pixel whose color is far from BG even if not BFS-reachable
# keeps its full alpha.  (BFS handles the outer BG region cleanly.)
out = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
out_px = out.load()
for y in range(ch):
    for x in range(cw):
        if not visited[x][y]:
            r, g, b = px[x, y]
            out_px[x, y] = (r, g, b, 255)
chunk.close()
print(f"[step4] non-bg pixels kept: "
      f"{sum(1 for y in range(ch) for x in range(cw) if not visited[x][y])}")

# -------- Step 5: save --------
out.save(DST)
print(f"[done] saved -> {DST}  size={out.size}")
