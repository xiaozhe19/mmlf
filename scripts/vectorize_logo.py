"""Convert the supplied pixel-art logo into palette-coloured SVG paths."""
from collections import defaultdict
from pathlib import Path
import sys

from PIL import Image

source, destination = map(Path, sys.argv[1:])
image = Image.open(source).convert("RGBA").resize((512, 512), Image.Resampling.NEAREST)
alpha = image.getchannel("A")
palette = image.convert("RGB").quantize(colors=96, method=Image.Quantize.MEDIANCUT)
colors = palette.getpalette()
pixels = palette.load()
mask = alpha.load()
paths = defaultdict(list)
for y in range(image.height):
    x = 0
    while x < image.width:
        if mask[x, y] < 128:
            x += 1
            continue
        color = pixels[x, y]
        start = x
        x += 1
        while x < image.width and mask[x, y] >= 128 and pixels[x, y] == color:
            x += 1
        paths[color].append(f"M{start} {y}h{x-start}v1H{start}z")
destination.parent.mkdir(parents=True, exist_ok=True)
with destination.open("w", encoding="utf-8", newline="\n") as out:
    out.write('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" shape-rendering="crispEdges">\n')
    out.write('<title>MMLF logo</title>\n')
    for index, segments in sorted(paths.items()):
        rgb = colors[index*3:index*3+3]
        out.write(f'<path fill="#{rgb[0]:02x}{rgb[1]:02x}{rgb[2]:02x}" d="{"".join(segments)}"/>\n')
    out.write('</svg>\n')
