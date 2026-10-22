"""Put client screenshots of the panel on one canvas size for the README.

    python3 tools/frame_shot.py docs/img/*.png
"""
import sys
from PIL import Image

BG = (40, 40, 40)
PANEL_W = 452
MARGIN = 14
W, H = PANEL_W + 2 * MARGIN, 1130
FADE = 120


def differs(p):
    return p[3] and max(abs(c - b) for c, b in zip(p[:3], BG)) > 6


def frame(path):
    shot = Image.open(path).convert('RGBA')
    flat = Image.new('RGBA', shot.size, BG + (255,))
    flat.alpha_composite(shot)
    px = flat.load()
    w, h = flat.size
    top = next(y for y in range(h) if sum(differs(px[x, y]) for x in range(w)) > 300)
    left = next(x for x in range(w) if differs(px[x, top]))
    bottom = max(y for y in range(h) for x in range(left, min(w, left + PANEL_W)) if differs(px[x, y]))
    cut = bottom >= h - 3
    keep = min(bottom + 1 - top, H - 2 * MARGIN)
    out = Image.new('RGB', (W, H), BG)
    out.paste(flat.crop((left, top, left + PANEL_W, top + keep)).convert('RGB'), (MARGIN, MARGIN))
    if cut or bottom + 1 - top > keep:
        o = out.load()
        for i in range(FADE):
            y = MARGIN + keep - FADE + i
            a = (i + 1) / FADE
            for x in range(MARGIN, MARGIN + PANEL_W):
                o[x, y] = tuple(round(c * (1 - a) + b * a) for c, b in zip(o[x, y], BG))
    out.save(path, optimize=True)


for p in sys.argv[1:]:
    frame(p)
