#!/usr/bin/env python3
"""Afbeeldingen voor de screenshotmodus (Google Play), workflow playstore-screenshots.yml.

    make_demo_media.py <media> <fonts> <assets-uit>

<media> = uitvoer van tools/fetch_free_media.py uit iptv-ios-appletv (vrije film-afbeeldingen van Wikimedia Commons,
licentie per bestand gecontroleerd, met credits.json). Maakt in <assets-uit> (app/src/main/assets/screenshot-media,
staat in .gitignore en komt alleen in de screenshot-build) de bestanden die core/ScreenshotMode.kt verwacht:

  <id>-poster.jpg    600x900   echte poster, of een beeld uit de film met de titel erop
  <id>-keyart.jpg    800x1200  staand beeld zonder titel (billboard en detailpagina tekenen de titel zelf)
  <id>-backdrop.jpg  1600x900  achtergrond
  <id>-stillN.jpg    800x450   afleveringsbeelden (series)
  live-<zender>.jpg  1280x720  beeld van een (verzonnen) zender; zonder vrij beeld zelf getekend
  logo-<zender>.png  480x270   zelfgetekend logo van een verzonnen zender (geen bestaand merk)

Alleen vrije beelden (Blender open movies CC BY en publiek domein); nooit TMDB/IMDb of echte zenders.
"""
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RED = (229, 9, 20)

TITLES = {
    "m01": "Sintel", "m02": "Tears of Steel", "m03": "Big Buck Bunny", "m04": "Cosmos Laundromat", "m05": "Spring",
    "m06": "Sprite Fright", "m07": "Coffee Run", "m08": "Charge", "m09": "Elephants Dream", "m11": "Nosferatu",
    "m12": "A Trip to the Moon", "m13": "The Great Train Robbery", "s01": "Les Vampires", "s02": "Judex",
}

# verzonnen zenders (gelijk aan ScreenshotDemo.channels): id, naam, kleur
CHANNELS = [
    ("c01", "News 24", 0xFF6D00), ("c02", "Actua", 0x00A0E9), ("c03", "Prime One", 0xE3001B),
    ("c04", "Family+", 0xD50000), ("c05", "Comedy Lane", 0xF7B500), ("c06", "Cinema Nights", 0x8E44AD),
    ("c07", "Stadium 1", 0xFF7F00), ("c08", "Matchday", 0x2ECC71), ("c09", "Motor Max", 0x6A1B9A),
    ("c10", "Explore", 0x1565C0), ("c11", "Planet Wild", 0xFBC02D), ("c12", "Time Travel", 0xA0522D),
    ("c13", "Kids Club", 0x00BCD4), ("c14", "Little Stars", 0xFF4081), ("c15", "Tune Up", 0xE91E63),
    ("c16", "Weather Now", 0x26A69A), ("c17", "Info Sept", 0x3949AB), ("c18", "Sportwelt", 0x43A047),
    ("c19", "Deportes Uno", 0xF4511E), ("c20", "Cine Classique", 0x6D4C41),
]


def font(fonts: pathlib.Path, weight: str, size: int) -> ImageFont.FreeTypeFont:
    for name in (f"Inter-{weight}.ttf", f"Inter-{weight}.otf", f"Inter{weight}.ttf"):
        hits = sorted(fonts.rglob(name)) if fonts.exists() else []
        if hits:
            return ImageFont.truetype(str(hits[0]), size)
    for p in (f"/usr/share/fonts/opentype/inter/Inter-{weight}.otf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if pathlib.Path(p).exists():
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def rgb(c: int) -> tuple:
    return (c >> 16) & 255, (c >> 8) & 255, c & 255


def cover(im: Image.Image, w: int, h: int, focus_y: float = 0.5) -> Image.Image:
    r = max(w / im.width, h / im.height)
    im = im.resize((max(w, round(im.width * r)), max(h, round(im.height * r))), Image.LANCZOS)
    left = (im.width - w) // 2
    top = round((im.height - h) * focus_y)
    return im.crop((left, top, left + w, top + h))


def load(media: pathlib.Path, name: str):
    p = media / f"{name}.jpg"
    return Image.open(p).convert("RGB") if p.exists() else None


def titled(im: Image.Image, title: str, fonts: pathlib.Path) -> Image.Image:
    """Poster uit een filmbeeld: verloop naar zwart onderaan, rode streep en de titel in hoofdletters."""
    w, h = im.size
    out = im.convert("RGBA")
    grad = Image.linear_gradient("L").resize((w, h))
    grad = grad.point(lambda v: 0 if v < 110 else min(225, int((v - 110) * 225 / 145)))
    out.alpha_composite(Image.merge("RGBA", (*Image.new("RGB", (w, h)).split(), grad)))
    d = ImageDraw.Draw(out)
    size = int(w * 0.13)
    words = title.upper().split()
    while True:
        f = font(fonts, "Black", size)
        lines, cur = [], ""
        for wd in words:
            t = (cur + " " + wd).strip()
            if d.textlength(t, font=f) <= w * 0.86 or not cur:
                cur = t
            else:
                lines.append(cur)
                cur = wd
        lines.append(cur)
        if (len(lines) <= 3 and all(d.textlength(l, font=f) <= w * 0.86 for l in lines)) or size < 20:
            break
        size = int(size * 0.92)
    a, de = f.getmetrics()
    lh = int((a + de) * 0.98)
    y = h - int(h * 0.07) - lh * len(lines)
    bw, bh = int(w * 0.12), max(3, int(h * 0.006))
    d.rounded_rectangle([(w - bw) // 2, y - int(h * 0.03), (w + bw) // 2, y - int(h * 0.03) + bh], bh, fill=RED + (255,))
    for l in lines:
        tw = d.textlength(l, font=f)
        d.text(((w - tw) / 2 + 2, y + 3), l, font=f, fill=(0, 0, 0, 150))
        d.text(((w - tw) / 2, y), l, font=f, fill=(255, 255, 255, 255))
        y += lh
    return out.convert("RGB")


def channel_art(color: int, name: str, w: int, h: int, fonts: pathlib.Path) -> Image.Image:
    """Zelfgetekend zenderbeeld: kleurverloop, lichte banen en een grote, vage naam (geen bestaand merk)."""
    c = rgb(color)
    dark = tuple(int(v * 0.18) for v in c)
    base = Image.new("RGB", (w, h), dark)
    glow = Image.new("RGB", (w // 8, h // 8), dark)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([w // 8 * 0.35, -h // 8 * 0.4, w // 8 * 1.2, h // 8 * 0.9], fill=c)
    glow = glow.filter(ImageFilter.GaussianBlur(w // 8 * 0.12)).resize((w, h), Image.LANCZOS)
    base = Image.blend(base, glow, 0.75)
    over = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(over)
    for i in range(6):
        x = int(w * (0.1 + i * 0.17))
        od.polygon([(x, 0), (x + int(w * 0.05), 0), (x - int(w * 0.2), h), (x - int(w * 0.25), h)],
                   fill=(255, 255, 255, 14 + (i % 3) * 8))
    f = font(fonts, "Black", int(h * 0.2))
    tw = od.textlength(name.upper(), font=f)
    od.text(((w - tw) / 2, h * 0.58), name.upper(), font=f, fill=(255, 255, 255, 40))
    out = base.convert("RGBA")
    out.alpha_composite(over)
    return out.convert("RGB")


def logo(color: int, name: str, fonts: pathlib.Path) -> Image.Image:
    """Logo 480x270 (transparant): gekleurd blok met de beginletter en de naam."""
    W, H = 480, 270
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    s = 120
    x0, y0 = 26, (H - s) // 2
    d.rounded_rectangle([x0, y0, x0 + s, y0 + s], 28, fill=rgb(color) + (255,))
    mono = "".join(ch for ch in name if ch.isalnum())[:1].upper()
    fm = font(fonts, "Black", 78)
    l, t, r, b = d.textbbox((0, 0), mono, font=fm)
    d.text((x0 + (s - (r - l)) / 2 - l, y0 + (s - (b - t)) / 2 - t), mono, font=fm, fill=(255, 255, 255, 255))
    words = name.split()
    lines = [name] if len(words) == 1 else [" ".join(words[:-1]), words[-1]]
    size = 64
    while size > 20:
        f = font(fonts, "ExtraBold", size)
        if all(d.textlength(li, font=f) <= W - (x0 + s + 22) - 12 for li in lines):
            break
        size -= 2
    a, de = f.getmetrics()
    total = (a + de) * len(lines)
    y = (H - total) / 2
    for li in lines:
        d.text((x0 + s + 22, y), li, font=f, fill=(255, 255, 255, 255))
        y += a + de
    return im


def main() -> None:
    media, fonts, out = (pathlib.Path(a) for a in sys.argv[1:4])
    out.mkdir(parents=True, exist_ok=True)
    n = 0
    for tid, title in TITLES.items():
        poster, key, back = load(media, f"{tid}-poster"), load(media, f"{tid}-keyart"), load(media, f"{tid}-backdrop")
        if back is not None:
            cover(back, 1600, 900).save(out / f"{tid}-backdrop.jpg", quality=86)
            n += 1
        # staand beeld zonder titel: keyart, anders een uitsnede van de achtergrond, anders de poster
        k = key or back or poster
        if k is not None:
            cover(k, 800, 1200, 0.3).save(out / f"{tid}-keyart.jpg", quality=86)
            n += 1
        if poster is not None:
            cover(poster, 600, 900, 0.0).save(out / f"{tid}-poster.jpg", quality=88)
        elif k is not None:
            titled(cover(k, 600, 900, 0.3), title, fonts).save(out / f"{tid}-poster.jpg", quality=88)
        else:
            continue
        n += 1
        for p in sorted(media.glob(f"{tid}-still*.jpg")):
            cover(Image.open(p).convert("RGB"), 800, 450).save(out / p.name, quality=85)
            n += 1
    for cid, name, color in CHANNELS:
        live = load(media, f"live-{cid}")
        (cover(live, 1280, 720) if live is not None else channel_art(color, name, 1280, 720, fonts)) \
            .save(out / f"live-{cid}.jpg", quality=85)
        logo(color, name, fonts).save(out / f"logo-{cid}.png", optimize=True)
        n += 2
    print(f"{n} demo-afbeeldingen in {out}")


if __name__ == "__main__":
    main()
