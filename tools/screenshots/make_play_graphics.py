#!/usr/bin/env python3
"""Google Play: feature graphic (1024x500) en TV-banner (1280x720) in de stijl van de Apple TV Top Shelf.

    make_play_graphics.py <media> <iptv-ios-appletv/tools> <uitvoermap>

<media> = uitvoer van fetch_free_media.py (vrije posters met credits.json). Beeldtaal en code komen uit
tools/make_tvos_icon.py van iptv-ios-appletv: een schuine, donkere muur van vrije filmposters (Blender open movies,
CC BY, en films die in de EU en de VS publiek domein zijn), een donker vignet met een rode gloed en het woordmerk
BINGR met de rode balk. Nooit TMDB/IMDb-posters. Uitvoer: RGB zonder alfakanaal (Play-eis voor beide).
"""
import pathlib
import sys

from PIL import Image


def main() -> None:
    media, tools, out = (pathlib.Path(a) for a in sys.argv[1:4])
    sys.path.insert(0, str(tools))
    import make_tvos_icon as tv  # noqa: E402  (tilted_wall, middle_layer, logo_layer, load_posters)

    posters, used = tv.load_posters(media)
    out.mkdir(parents=True, exist_ok=True)
    for name, W, H, wall_frac, logo_frac in (("feature-graphic-1024x500.png", 1024, 500, 0.30, 0.34),
                                              ("tv-banner-1280x720.png", 1280, 720, 0.27, 0.40)):
        img = tv.flatten(tv.tilted_wall(posters, W, H, wall_frac, seed=7),
                         tv.middle_layer(W, H, wide=True, cy=0.5),
                         tv.logo_layer(W, H, logo_frac, cy_frac=0.5))
        img = img.convert("RGB")
        assert img.size == (W, H)
        img.save(out / name, optimize=True)
        print(f"{name}: {W}x{H}")
    (out / "_graphics-films.txt").write_text("\n".join(sorted({c["film"] for c in used})) + "\n")


if __name__ == "__main__":
    main()
