#!/usr/bin/env python3
"""Google Play-screenshots in Bingr-stijl (workflow playstore-screenshots.yml).

    make_play_shots.py <kale screenshots> <fonts> <iptv-ios-appletv/tools> <uitvoermap>

Leest de kale screenshots van de screenshotmodus (phone/, tablet7/, tablet10/, tv/ met <scherm>.png en _credits.json)
en maakt per set de marketingversies in dezelfde stijl als de App Store-set (zwart met rode gloed, Engelse kop en
ondertitel, de echte screenshot met afgeronde hoeken; compose() uit tools/make_appstore_shots.py van iptv-ios-appletv)
op de Play-maten. Alles RGB zonder alfakanaal. Android TV: bovenin de onbewerkte screenshots (Play-richtlijn TV-G4),
de marketingversies in marketing/. Plus CREDITS.txt en README.txt (met de Play-eisen).
"""
import json
import pathlib
import sys

from PIL import Image

SCREENS = [  # volgorde in Google Play
    ("home", "Your own TV, beautifully organised", "Movies, series and live TV from your own playlist."),
    ("detail", "Pick up right where you left off", "Seasons, episodes and your progress, on every screen."),
    ("live", "All your channels, neatly sorted", "See what's on now and next on every channel."),
    ("guide", "Live TV with a real TV guide", "Scroll through today's programmes at a glance."),
    ("movies", "Always something to watch", "Continue watching, My List, Top 10 and picks for you."),
    ("picker", "A profile for everyone", "Own lists, progress and settings for every viewer."),
    ("categories", "Browse by genre and category", "Every genre and category of your playlist in one place."),
    ("multiview", "Four streams at once", "Watch up to four live channels side by side."),
]

# kale set → (map, (breedte, hoogte), liggend?, onbewerkt bovenin?)
SETS = [
    ("phone", "Phone", (1080, 1920), False, False),
    ("tablet7", "Tablet-7in", (1920, 1080), True, False),
    ("tablet10", "Tablet-10in", (2560, 1440), True, False),
    ("tv", "AndroidTV", (1920, 1080), True, True),
]


def exact(img: Image.Image, w: int, h: int) -> Image.Image:
    """Op de exacte maat: schalen, en alleen bij een andere verhouding midden bijsnijden."""
    if img.size == (w, h):
        return img
    r = max(w / img.width, h / img.height)
    img = img.resize((max(w, round(img.width * r)), max(h, round(img.height * r))), Image.LANCZOS)
    left, top = (img.width - w) // 2, (img.height - h) // 2
    return img.crop((left, top, left + w, top + h))


def write_credits(out: pathlib.Path, credits: list, graphics_films: set) -> None:
    lines = [
        "Bingr - Google Play images: credits",
        "===================================",
        "",
        "The movie and series artwork in these screenshots, the feature graphic and the TV banner comes from films",
        "that are free to use worldwide: Blender open movies (Creative Commons Attribution, (c) Blender Foundation |",
        "blender.org) and silent films that are in the public domain in the EU and the US (published before 1931,",
        "all authors died more than 70 years ago). Channels, TV guide, channel logos and profile names are made up.",
        "No posters from TMDB, IMDb or other commercial films, and no real channels or brands.",
        "",
        "Changes: images were resized and cropped to fit the app layout; on posters made from a film frame the title",
        "was added. Frames from videos were extracted at the stated time. Feature graphic and TV banner: posters",
        "resized, darkened and placed on a tilted wall behind the Bingr logo.",
        "",
    ]
    if graphics_films:
        lines += ["Feature graphic and TV banner use posters of: " + ", ".join(sorted(graphics_films)) + ".", ""]
    for c in credits:
        artist = c.get("artist") or c.get("credit", "")
        half = len(artist) // 2
        if artist and len(artist) % 2 == 0 and artist[:half] == artist[half:]:
            artist = artist[:half]
        lines += [
            f'{c["id"]}  -  {c["film"]}' + (f' ({c["year"]})' if c.get("year") else ""),
            f'  Image:   {c["file"]}' + (f' (frame at {c["seek"]} s)' if c.get("seek") is not None else ""),
            f'  Author:  {artist}',
            f'  Licence: {c["license"]}' + (f' - {c["licenseUrl"]}' if c.get("licenseUrl") else ""),
            f'  Source:  {c["page"]}',
            "",
        ]
    (out / "CREDITS.txt").write_text("\n".join(lines))


def main() -> None:
    src, fonts, tools, out = (pathlib.Path(a) for a in sys.argv[1:5])
    sys.path.insert(0, str(tools))
    import make_appstore_shots as ap  # noqa: E402  (compose, rgb, credit_line, films_on: zelfde stijl als Apple)

    out.mkdir(parents=True, exist_ok=True)
    cpath = src / "_credits.json"
    credits = json.loads(cpath.read_text()) if cpath.exists() else []
    credit = ap.credit_line(ap.films_on("", credits))
    report = []
    for key, folder, (w, h), landscape, raw_on_top in SETS:
        sdir = src / key
        if not sdir.is_dir():
            report.append(f"{folder}: ontbreekt")
            continue
        n = 0
        for name, title, subtitle in SCREENS:
            p = sdir / f"{name}.png"
            if not p.exists() or p.stat().st_size < 1000:
                continue
            shot = exact(ap.rgb(Image.open(p)), w, h)
            n += 1
            prefix = f"{n:02d}-{name}"
            raw_dir = out / folder if raw_on_top else out / folder / "raw"
            mk_dir = out / folder / "marketing" if raw_on_top else out / folder
            raw_dir.mkdir(parents=True, exist_ok=True)
            mk_dir.mkdir(parents=True, exist_ok=True)
            shot.save(raw_dir / f"{prefix}.png", optimize=True)
            img = ap.compose(shot, w, h, landscape, title, subtitle, n == 1, fonts, credit)
            assert img.size == (w, h) and img.mode == "RGB"
            img.save(mk_dir / f"{prefix}.png", optimize=True)
        report.append(f"{folder}: {n} screenshots ({w}x{h})")
    gf = src / "_graphics-films.txt"
    write_credits(out, credits, set(gf.read_text().split("\n")) - {""} if gf.exists() else set())
    (out / "README.txt").write_text(README)
    print("\n".join(report))


README = """Bingr - Google Play store listing images (English, en-US)
==========================================================

Made with the real Bingr Android app in screenshot mode (only in a special screenshot
build; never in normal use). The demo library holds real films that are free to use
worldwide: Blender open movies (Creative Commons Attribution, (c) Blender Foundation |
blender.org) and silent films in the public domain in the EU and the US. Every image, its
author, licence and source is listed in CREDITS.txt; the marketing images carry a short
credit line. Channels, TV guide, channel logos and profile names are made up (no real
channels or brands). No personal data.

ALL OF THESE ARE REQUIRED FOR A RELEASE OF THE APP (phone, tablet and Android TV)

  File / folder                  Size          Google Play requirement
  ---------------------------------------------------------------------------------------
  icon-512.png                   512 x 512     App icon (required): 32-bit PNG with alpha,
                                               512 x 512 px, max. 1 MB; full square, Google
                                               Play rounds the corners itself
  feature-graphic-1024x500.png   1024 x 500    Feature graphic (required): JPEG or 24-bit
                                               PNG without alpha, exactly 1024 x 500 px
  tv-banner-1280x720.png         1280 x 720    Android TV banner (required for a TV app):
                                               JPEG or 24-bit PNG without alpha, 1280 x 720
  Phone/                         1080 x 1920   Phone screenshots: 2-8 per device type, JPEG
                                               or 24-bit PNG, min. 320 px, max. 3840 px, long
                                               side max. 2x the short side. For promotion
                                               ("recommended") at least 4 at min. 1080 px,
                                               9:16 portrait (1080 x 1920) or 16:9 landscape.
  Tablet-7in/                    1920 x 1080   7-inch tablet screenshots: 16:9 landscape or
                                               9:16 portrait, each side 1080-7680 px; at
                                               least 4 to show the in-app experience on
                                               large screens.
  Tablet-10in/                   2560 x 1440   10-inch tablet screenshots: same rules as 7".
  AndroidTV/                     1920 x 1080   Android TV screenshots: at least 1 is needed
                                               before a TV app can be published (16:9). TV
                                               quality guideline TV-G4: at least one
                                               unaltered, high-resolution screenshot of the
                                               current TV app -> upload the plain screenshots
                                               in AndroidTV/ (not the ones in marketing/).

  To publish at all, Google Play needs at least 2 screenshots across device types; every
  set here has 7 or 8, so each one also qualifies for promotion.

  In the app itself (not uploaded here) Android TV also needs a launcher banner in the
  manifest (android:banner, 320 x 180 px at xhdpi, with the app name): Bingr has one
  (app/src/main/res/drawable/tv_banner.png in bingr-android).

FOLDERS
  Phone/, Tablet-7in/, Tablet-10in/   numbered marketing images (01-home.png ...) to upload,
                                      plus raw/ with the plain screenshots (same size)
  AndroidTV/                          plain, unaltered TV screenshots to upload, plus
                                      marketing/ with the styled versions
  CREDITS.txt                         film images: title, author, licence and source

Order: 01 Home, 02 Detail (series), 03 Live TV, 04 TV guide, 05 Movies (Top 10), 06 Who's
watching?, 07 Categories, 08 Multiview (tablets and TV). The first marketing image of every
set carries the note: "Bring your own IPTV subscription. Bingr does not provide any
channels, movies or series."

Sources for the requirements: Play Console Help "Add preview assets to showcase your app"
(support.google.com/googleplay/android-developer/answer/9866151) and Android Developers
"TV app quality" / "TV app icon and banner guidelines" (developer.android.com).
"""

if __name__ == "__main__":
    main()
