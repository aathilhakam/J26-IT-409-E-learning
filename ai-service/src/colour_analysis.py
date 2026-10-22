"""Descriptive HSV colour usage, independent of creativity models and scores."""
from collections import deque

import numpy as np
from PIL import Image


def analyse_colours(image: Image.Image) -> dict:
    # Bound work for camera photos. Retain alpha; transparent RGB is not artwork.
    image = image.convert("RGBA")
    image.thumbnail((768, 768), Image.Resampling.LANCZOS)
    rgba = np.asarray(image)
    rgb = rgba[:, :, :3]
    hsv = np.asarray(image.convert("RGB").convert("HSV"), dtype=float) / 255
    h, s, v = hsv[:, :, 0] * 360, hsv[:, :, 1], hsv[:, :, 2]
    opaque = rgba[:, :, 3] >= 128
    white = (s < 0.12) & (v > 0.90)

    # Remove light neutral paper/shadow connected to the photo edges. Interior
    # gray marks and dark outlines remain. This cannot segment every photograph.
    edge_values = np.concatenate((v[0, :], v[-1, :], v[:, 0], v[:, -1]))
    edge_saturation = np.concatenate((s[0, :], s[-1, :], s[:, 0], s[:, -1]))
    neutral_edges = edge_values[edge_saturation < 0.16]
    paper_floor = max(0.48, float(np.median(neutral_edges)) - 0.12) if neutral_edges.size else 0.90
    paper = opaque & (s < 0.16) & (v > paper_floor)
    background = np.zeros(paper.shape, dtype=bool)
    height, width = paper.shape
    queue = deque()
    for y, x in ([(0, x) for x in range(width)]
                 + [(height - 1, x) for x in range(width)]
                 + [(y, 0) for y in range(height)]
                 + [(y, width - 1) for y in range(height)]):
        if paper[y, x] and not background[y, x]:
            background[y, x] = True
            queue.append((y, x))
    while queue:
        y, x = queue.popleft()
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < height and 0 <= nx < width and paper[ny, nx] and not background[ny, nx]:
                background[ny, nx] = True
                queue.append((ny, nx))
    foreground = opaque & ~white & ~background
    names = np.full(h.shape, "Gray", dtype=object)
    chromatic = s >= 0.16
    names[chromatic & ((h < 15) | (h >= 345))] = "Red"
    names[chromatic & (h >= 15) & (h < 45)] = "Orange"
    names[chromatic & (h >= 45) & (h < 70)] = "Yellow"
    names[chromatic & (h >= 70) & (h < 170)] = "Green"
    names[chromatic & (h >= 170) & (h < 260)] = "Blue"
    names[chromatic & (h >= 260) & (h < 345)] = "Purple"
    names[chromatic & ((h >= 315) | (h < 15)) & (s < 0.65) & (v > 0.7)] = "Pink"
    names[chromatic & (h >= 10) & (h < 55) & (v < 0.68)] = "Brown"
    names[v < 0.20] = "Black"
    total = int(foreground.sum())
    colours = []
    for name in ("Red", "Blue", "Green", "Yellow", "Orange", "Purple", "Pink", "Brown", "Black", "Gray"):
        selected = foreground & (names == name)
        count = int(selected.sum())
        if not count:
            continue
        mean = np.rint(rgb[selected].mean(axis=0)).astype(int)
        colours.append({"name": name, "hex": "#" + "".join(f"{c:02x}" for c in mean),
                        "percentage": round(count * 100 / total, 2), "pixel_count": count})
    colours.sort(key=lambda colour: colour["pixel_count"], reverse=True)
    return {"colours": colours, "dominant_colour": colours[0]["name"] if colours else None,
            "category_count": len(colours), "drawing_pixels": total,
            "analysed_pixels": width * height, "approximate": True,
            "method": "HSV colour categories with white, transparent and edge-connected neutral paper exclusion",
            "message": "Approximate colour usage among drawing pixels, sampled up to 768 px per side. Lighting, shadows and paper can affect photos. Colour count is not a creativity score."}
