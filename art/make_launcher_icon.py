"""Builds the Android adaptive launcher icon from art/logo-source.webp.

    python art/make_launcher_icon.py      (needs Pillow: pip install pillow)

The source is a 3x3 grid of extruded tiles on a navy rounded base. Android draws adaptive icons
as a foreground over a background and lets the launcher clip them to a circle, squircle or
rounded square, so the tiles are cut out onto transparency (the navy base and its shading
become see-through) and the navy goes into a flat background colour. Writes:

  app/src/main/res/mipmap-*/ic_launcher_foreground.png   108 dp foreground at each density
  app/src/main/res/values/ic_launcher_background.xml     the navy background colour
  art/preview-masks.png                                   how it looks under common masks
"""
import pathlib

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "art" / "logo-source.webp"
RES = ROOT / "app" / "src" / "main" / "res"

# The navy base in the source, used as the background layer so nothing shows a seam.
BACKGROUND = (12, 29, 62)

# Size of the tile grid as a share of the 108 dp canvas. The launcher only promises the
# central 66 dp; 52 dp keeps the grid whole under rounded-square and squircle masks and
# clips at most the tips of the outer corners under a full circle.
GRID_SHARE = 52 / 108

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def cut_out_tiles(img: Image.Image) -> Image.Image:
    """Makes the dark, blue-dominant base and its shadows transparent, keeping the tiles."""
    rgb = img.convert("RGB")
    r, g, b = rgb.split()
    brightest = ImageChops.lighter(ImageChops.lighter(r, g), b)
    warm = ImageChops.lighter(r, g)
    # Navy: dark overall and blue at least as strong as red and green.
    dark = brightest.point(lambda v: 255 if v < 95 else 0)
    blueish = ImageChops.subtract(b, warm).point(lambda v: 255 if v > 0 else 0)
    navy = ImageChops.multiply(dark, blueish)
    alpha = ImageChops.invert(navy).filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.GaussianBlur(0.8))
    out = rgb.convert("RGBA")
    out.putalpha(alpha)
    return out


def main() -> None:
    src = Image.open(SOURCE)
    tiles = cut_out_tiles(src)
    box = tiles.getchannel("A").point(lambda v: 255 if v > 40 else 0).getbbox()
    tiles = tiles.crop(box)

    master = 1080  # Work large, then scale down for each density.
    side = round(master * GRID_SHARE)
    scale = side / max(tiles.size)
    grid = tiles.resize((round(tiles.width * scale), round(tiles.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (master, master), (0, 0, 0, 0))
    canvas.alpha_composite(grid, ((master - grid.width) // 2, (master - grid.height) // 2))

    for density, px in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        canvas.resize((px, px), Image.LANCZOS).save(folder / "ic_launcher_foreground.png", optimize=True)

    (RES / "values").mkdir(exist_ok=True)
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n' % BACKGROUND,
        encoding="utf-8",
    )

    # Preview under the masks launchers use: circle, squircle, rounded square, square.
    size = 432
    flat = Image.new("RGBA", (size, size), BACKGROUND + (255,))
    flat.alpha_composite(canvas.resize((size, size), Image.LANCZOS))
    shapes = []
    for kind in ("circle", "squircle", "rounded", "square"):
        mask = Image.new("L", (size, size), 0)
        d = ImageDraw.Draw(mask)
        inset = size * (108 - 72) / 108 / 2  # launchers show the central 72 dp
        box = (inset, inset, size - inset, size - inset)
        if kind == "circle":
            d.ellipse(box, fill=255)
        elif kind == "squircle":
            d.rounded_rectangle(box, radius=(box[2] - box[0]) * 0.32, fill=255)
        elif kind == "rounded":
            d.rounded_rectangle(box, radius=(box[2] - box[0]) * 0.18, fill=255)
        else:
            d.rectangle(box, fill=255)
        tile = Image.new("RGBA", (size, size), (245, 245, 245, 255))
        tile.paste(flat, (0, 0), mask)
        shapes.append(tile.crop((int(inset) - 8, int(inset) - 8, size - int(inset) + 8, size - int(inset) + 8)))
    w, h = shapes[0].size
    sheet = Image.new("RGBA", (w * len(shapes), h), (245, 245, 245, 255))
    for i, s in enumerate(shapes):
        sheet.paste(s, (i * w, 0))
    sheet.save(ROOT / "art" / "preview-masks.png")
    print("wrote", len(DENSITIES), "foregrounds, background", "#%02X%02X%02X" % BACKGROUND)


if __name__ == "__main__":
    main()
