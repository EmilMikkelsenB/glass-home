"""Collect real app logos into ./logos as transparent PNGs (personal use on your own TV only).

Run:  python fetch_logos.py <dir with app .apk files pulled from the TV>
Sources: Simple Icons (SVG, rendered white) and the apps' own banner/icon images inside their APKs.
"""
import io
import sys
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from svgelements import Path as SvgPath

LOGOS = Path(__file__).parent / "logos"
SI = "https://cdn.jsdelivr.net/npm/simple-icons@16.36.0/icons/{}.svg"

# key -> simple-icons slug
SIMPLE = {
    "jellyfin": "jellyfin", "youtube": "youtube", "netflix": "netflix", "appletv": "appletv",
    "max": "hbomax", "crunchyroll": "crunchyroll", "twitch": "twitch", "viaplay": "viaplay",
    "spotify": "spotify", "steamlink": "steam", "homeassistant": "homeassistant",
}
# key -> (apk file, image inside apk, mode). mode: key = remove solid background, white = keep white parts, asis
FROM_APK = {
    "tv4play":    ("tv4.apk", "res/mipmap-xhdpi-v4/ic_hero_banner.png", "white"),
    "svtplay":    ("svt.apk", "res/drawable/logo_banner.png", "key"),
    "moonlight":  ("moonlight.apk", "res/mipmap-xxxhdpi-v4/ic_launcher_foreground.png", "asis"),
    "discovery":  ("discovery.apk", "assets/assets/drawable/default/logo.png", "asis"),
    "allente":    ("allente.apk", "res/drawable-xhdpi-v4/allente_banner.png", "key"),
    "disneyplus": ("disney.apk", "res/drawable-xhdpi-v4/disney_banner.png", "white"),
    "primevideo": ("prime.apk", "res/drawable-xhdpi-v4/amazon_video_tv_icon.png", "key"),
}


def render_svg(svg_text, size=1024, ss=2):
    """Rasterise a single-path Simple Icons SVG (24x24 viewBox) to white-on-transparent, even-odd fill."""
    d = svg_text.split(' d="', 1)[1].split('"', 1)[0]
    S = size * ss
    path = SvgPath(d)
    mask = np.zeros((S, S), bool)
    for sub in path.as_subpaths():
        sp = SvgPath(sub)
        n = max(64, int(sp.length(error=1e-3) * S / 48))
        pts = [(x * S / 24, y * S / 24) for x, y in sp.npoint(np.linspace(0, 1, n))]
        if len(pts) < 3:
            continue
        layer = Image.new("1", (S, S), 0)
        ImageDraw.Draw(layer).polygon(pts, fill=1)
        mask ^= np.asarray(layer, bool)
    alpha = Image.fromarray((mask * 255).astype(np.uint8)).resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def remove_background(im, mode):
    a = np.asarray(im.convert("RGBA"), np.float32)
    rgb = a[..., :3]
    if mode == "asis":
        return im.convert("RGBA")
    if mode == "white":
        alpha = np.clip((rgb.min(axis=2) - 150) / 90, 0, 1)
        out = np.dstack([np.full_like(alpha, 255)] * 3 + [alpha * 255])
    else:  # key out the solid background colour (median of the border) and un-mix anti-aliased edges
        border = np.concatenate([rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]])
        bg = np.median(border, axis=0)
        far = np.linalg.norm(255 - bg) * 0.85
        alpha = np.clip(np.linalg.norm(rgb - bg, axis=2) / far, 0, 1)
        safe = np.maximum(alpha, 1e-3)[..., None]
        col = np.clip(bg + (rgb - bg) / safe, 0, 255)
        out = np.dstack([col, alpha * 255])
    return Image.fromarray(out.astype(np.uint8), "RGBA")


def trim(im):
    box = im.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    return im.crop(box) if box else im


WALLPAPERS = {   # Apple wallpapers (not in git: large + Apple's). make_assets.py picks up anything in ./src
    "sierra.jpg": "https://media.512pixels.net/downloads/macos-wallpapers-6k/10-12-6k.jpg",
    "tahoe_dark.png": "https://media.512pixels.net/downloads/macos-wallpapers-6k/26-Tahoe-Dark-6K.png",
}


def main():
    src = Path(__file__).parent / "src"
    src.mkdir(exist_ok=True)
    for name, url in WALLPAPERS.items():
        if not (src / name).exists():
            try:  # optional: the APK ships the gradient; 512pixels answers 403 to CI runners
                urllib.request.urlretrieve(url, src / name)
                print("wallpaper", name)
            except OSError as e:
                print("skip wallpaper", name, e)
    LOGOS.mkdir(exist_ok=True)
    for key, slug in SIMPLE.items():
        svg = urllib.request.urlopen(SI.format(slug)).read().decode()
        trim(render_svg(svg)).save(LOGOS / f"{key}.png")
        print("simple-icons", key)
    apk_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    for key, (apk, member, mode) in FROM_APK.items():
        if not apk_dir or not (apk_dir / apk).exists():
            print("skip (no apk)", key)
            continue
        im = Image.open(io.BytesIO(zipfile.ZipFile(apk_dir / apk).read(member)))
        trim(remove_background(im, mode)).save(LOGOS / f"{key}.png")
        print("apk", key)


if __name__ == "__main__":
    main()


