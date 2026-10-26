"""Generate iOS-style frosted-glass icons, banners and wallpapers for Projectivy Launcher.

Run:  python make_assets.py            -> full set + zip in ./out
      python make_assets.py --preview  -> only ./out/preview.png
Add an app: append a line to APPS below and re-run.
"""
import json
import math
import random
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageColor, ImageDraw, ImageFilter, ImageFont

OUT = Path(__file__).parent / "out"
LOGOS = Path(__file__).parent / "logos"    # real logos from fetch_logos.py (optional)
SRC = Path(__file__).parent / "src"       # optional wallpaper photos (e.g. Apple's), any size
FONT_BOLD = "C:/Windows/Fonts/seguisb.ttf"
FONT_TEXT = "C:/Windows/Fonts/segoeui.ttf"

# ---- shared style (identical on every icon) --------------------------------
SS = 4                    # supersampling factor
RADIUS_RATIO = 0.28       # corner radius as a fraction of the shape's short side (iOS 26 is rounder)
SQUIRCLE_N = 4.0          # corner curvature: 2 = circle arc, higher = smoother "continuous" corner
FILL_TOP, FILL_BOTTOM = 0.20, 0.07   # white glass opacity, top -> bottom (Liquid Glass is clearer)
TINT_TOP, TINT_BOTTOM = 0.62, 0.42   # brand-tinted glass opacity, top -> bottom
BORDER_ALPHA = 0.22       # base rim opacity all round
SPECULAR_ALPHA = 0.85     # extra rim light at top-left (and 60% of it at bottom-right)
INNER_GLOW_ALPHA = 0.18   # soft light just inside the rim
HIGHLIGHT_ALPHA = 0.45    # inner top-edge highlight
SHADOW_ALPHA, SHADOW_BLUR, SHADOW_DY = 0.28, 16, 8
GLYPH_ALPHA = 0.96
WIDE_ASPECT = 1.5         # shape width / height for the "wide" button set

# (file key, display name, fallback glyph, glass tint) - real logo comes from ./logos/<key>.png when present
# glyph is a drawer name or "text:XYZ"; tint None = clear white glass
APPS = [
    ("jellyfin",   "Jellyfin",    "jelly",      "#00A4DC"),
    ("tv4play",    "TV4 Play",    "text:TV4",   "#E4002B"),
    ("svtplay",    "SVT Play",    "text:SVT",   "#1C1C1E"),
    ("youtube",    "YouTube",     "play",       "#FF0000"),
    ("netflix",    "Netflix",     "text:N",     "#E50914"),
    ("moonlight",  "Moonlight",   "moon",       "#5B6B7F"),
    ("homeassistant", "Home",     "text:HA",    "#18BCF2"),
    ("more",       "More",        "grid",       None),
    ("settings",   "Settings",    "gear",       None),
    ("appletv",    "Apple TV",    "text:tv",    "#1C1C1E"),
    ("max",        "Max",         "text:max",   "#002BE7"),
    ("discovery",  "discovery+",  "text:d+",    "#1E50E6"),
    ("crunchyroll","Crunchyroll", "text:CR",    "#FF5E00"),
    ("twitch",     "Twitch",      "text:tw",    "#9146FF"),
    ("viaplay",    "Viaplay",     "text:vp",    "#FE365F"),
    ("allente",    "Allente",     "text:A",     "#FF6600"),
    ("spotify",    "Spotify",     "waves",      "#1DB954"),
    ("steamlink",  "Steam Link",  "text:SL",    "#1B2838"),
    ("disneyplus", "Disney+",     "text:D+",    "#0E7C8C"),
    ("primevideo", "Prime Video", "text:prime", "#1A98FF"),
]
PREVIEW_KEYS = ["jellyfin", "tv4play", "svtplay", "youtube", "netflix", "moonlight", "more"]


# ---- shape helpers ---------------------------------------------------------
def squircle_points(x0, y0, x1, y1, r, n=SQUIRCLE_N, steps=48):
    """Rounded rect whose corners are superellipse quarters (iOS-like continuous corners)."""
    pts = []
    corners = [(x1 - r, y0 + r, 0), (x0 + r, y0 + r, 1), (x0 + r, y1 - r, 2), (x1 - r, y1 - r, 3)]
    for cx, cy, q in corners:
        for i in range(steps + 1):
            t = (q + i / steps) * math.pi / 2
            c, s = math.cos(t), math.sin(t)
            pts.append((cx + r * math.copysign(abs(c) ** (2 / n), c),
                        cy - r * math.copysign(abs(s) ** (2 / n), s)))
    return pts


def shape_mask(size, box):
    """Supersampled 'L' mask of the glass shape inside `box` on a canvas of `size` (both at SS scale)."""
    m = Image.new("L", size, 0)
    x0, y0, x1, y1 = box
    r = RADIUS_RATIO * min(x1 - x0, y1 - y0)
    ImageDraw.Draw(m).polygon(squircle_points(x0, y0, x1, y1, r), fill=255)
    return m


def scale_alpha(img, a):
    return img.point(lambda v: int(v * a))


def glass_card(size, box, tint=None):
    """Return an RGBA glass shape (shadow, fill, highlight, border) at SS scale; `tint` = brand hex or None."""
    W, H = size
    mask = shape_mask(size, box)
    out = Image.new("RGBA", size, (0, 0, 0, 0))

    shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    shadow.putalpha(scale_alpha(mask, SHADOW_ALPHA))
    shadow = shadow.transform(size, Image.AFFINE, (1, 0, 0, 0, 1, -SHADOW_DY * SS))
    shadow = shadow.filter(ImageFilter.GaussianBlur(SHADOW_BLUR * SS))
    outside = Image.fromarray(255 - np.asarray(mask))           # keep shadow from tinting the glass
    shadow.putalpha(Image.fromarray((np.asarray(shadow.getchannel("A"), np.uint16) * np.asarray(outside) // 255).astype(np.uint8)))
    out.alpha_composite(shadow)

    y0, y1 = box[1], box[3]
    grad = np.zeros((H, W), np.float32)
    t = np.clip((np.arange(H) - y0) / max(1, y1 - y0), 0, 1)
    top, bottom = (TINT_TOP, TINT_BOTTOM) if tint else (FILL_TOP, FILL_BOTTOM)
    grad[:] = (top + (bottom - top) * t)[:, None]
    fill_a = (np.asarray(mask, np.float32) * grad).astype(np.uint8)
    fill = Image.new("RGBA", size, ImageColor.getrgb(tint) + (0,) if tint else (255, 255, 255, 0))
    fill.putalpha(Image.fromarray(fill_a))
    out.alpha_composite(fill)

    # inner top highlight: the shape minus itself shifted down, faded towards the bottom
    shifted = mask.transform(size, Image.AFFINE, (1, 0, 0, 0, 1, -3 * SS))
    edge = np.clip(np.asarray(mask, np.int16) - np.asarray(shifted, np.int16), 0, 255).astype(np.float32)
    fade = np.clip(1 - (np.arange(H) - y0) / (0.5 * (y1 - y0)), 0, 1)[:, None]
    hl = Image.new("RGBA", size, (255, 255, 255, 0))
    hl.putalpha(Image.fromarray((edge * fade * HIGHLIGHT_ALPHA).astype(np.uint8)))
    out.alpha_composite(hl)

    # Liquid Glass rim: thin border everywhere, bright specular light at top-left and bottom-right
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    ang = np.arctan2((yy - cy) / (box[3] - box[1]), (xx - cx) / (box[2] - box[0]))
    spec = np.maximum(0, np.cos(ang + 3 * np.pi / 4)) ** 3 + 0.6 * np.maximum(0, np.cos(ang - np.pi / 4)) ** 3
    m = np.asarray(mask, np.float32)
    inner = np.asarray(mask.filter(ImageFilter.MinFilter(int(1.5 * SS) | 1)), np.float32)
    ring = np.clip(m - inner, 0, 255)
    glow = np.clip(m - np.asarray(mask.filter(ImageFilter.GaussianBlur(6 * SS)), np.float32), 0, 255) * 2
    a = ring * (BORDER_ALPHA + SPECULAR_ALPHA * spec) + np.clip(glow, 0, 255) * INNER_GLOW_ALPHA * spec
    rim = Image.new("RGBA", size, (255, 255, 255, 0))
    rim.putalpha(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)))
    out.alpha_composite(rim)
    return out


# ---- glyphs (original, simple shapes - no official logos) -------------------
def fit_font(path, text, max_w, max_h):
    size = int(max_h)
    while size > 8:
        f = ImageFont.truetype(path, size)
        l, t, r, b = f.getbbox(text)
        if r - l <= max_w and b - t <= max_h:
            return f
        size -= 2
    return ImageFont.truetype(path, 8)


def draw_glyph(kind, cx, cy, s):
    """Draw glyph `kind` centred at (cx, cy) with nominal size s onto a white 'L' layer; returns (layer, offset)."""
    pad = int(s)
    layer = Image.new("L", (2 * pad, 2 * pad), 0)
    d = ImageDraw.Draw(layer)
    c = pad
    if kind.startswith("text:"):
        text = kind[5:]
        f = fit_font(FONT_BOLD, text, s * 0.95, s * (0.62 if len(text) <= 2 else 0.5))
        d.text((c, c), text, font=f, fill=255, anchor="mm")
    elif kind == "play":
        w = s * 0.5
        d.rounded_rectangle((c - w * 0.85, c - w * 0.6, c + w * 0.85, c + w * 0.6), radius=w * 0.28, outline=255, width=int(s * 0.07))
        t = w * 0.32
        d.polygon([(c - t * 0.7, c - t), (c - t * 0.7, c + t), (c + t, c)], fill=255)
    elif kind == "moon":
        r = s * 0.36
        d.ellipse((c - r, c - r, c + r, c + r), fill=255)
        o = r * 0.55
        d.ellipse((c - r + o, c - r - o * 0.35, c + r + o, c + r - o * 0.35), fill=0)
    elif kind == "jelly":  # nested rounded triangles
        for k, rr in ((1.0, 255), (0.55, 0)):
            h = s * 0.42 * k
            pts = [(c, c - h), (c + h * 1.05, c + h * 0.75), (c - h * 1.05, c + h * 0.75)]
            d.polygon(pts, fill=rr)
        h = s * 0.12
        d.polygon([(c, c - h * 0.6), (c + h, c + h * 0.9), (c - h, c + h * 0.9)], fill=255)
    elif kind == "grid":
        g, gap = s * 0.2, s * 0.08
        for i in (-1, 1):
            for j in (-1, 1):
                x, y = c + i * (g / 2 + gap / 2), c + j * (g / 2 + gap / 2)
                d.rounded_rectangle((x - g / 2, y - g / 2, x + g / 2, y + g / 2), radius=g * 0.3, fill=255)
    elif kind == "gear":
        r = s * 0.3
        for i in range(8):
            a = i * math.pi / 4
            pts = []
            for dx, dy in ((-0.11, 0), (0.11, 0), (0.11, 1.32), (-0.11, 1.32)):
                x, y = dx * r * 1.6, dy * r
                pts.append((c + x * math.cos(a) - y * math.sin(a), c + x * math.sin(a) + y * math.cos(a)))
            d.polygon(pts, fill=255)
        d.ellipse((c - r, c - r, c + r, c + r), fill=255)
        d.ellipse((c - r * 0.45, c - r * 0.45, c + r * 0.45, c + r * 0.45), fill=0)
    elif kind == "waves":
        d.ellipse((c - s * 0.08 - s * 0.22, c - s * 0.08, c + s * 0.08 - s * 0.22, c + s * 0.08), fill=255)
        for k in (1, 2, 3):
            r = s * 0.13 * k
            d.arc((c - s * 0.22 - r, c - r, c - s * 0.22 + r, c + r), -50, 50, fill=255, width=int(s * 0.07))
    else:
        raise ValueError(kind)
    return layer, (int(cx - pad), int(cy - pad))


def paste_glyph(img, kind, cx, cy, s):
    layer, off = draw_glyph(kind, cx, cy, s)
    sh = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    sh.putalpha(scale_alpha(layer.filter(ImageFilter.GaussianBlur(3 * SS)), 0.25))
    img.alpha_composite(sh, (off[0], off[1] + 2 * SS))
    g = Image.new("RGBA", layer.size, (255, 255, 255, 0))
    g.putalpha(scale_alpha(layer, GLYPH_ALPHA))
    img.alpha_composite(g, off)


def paste_logo(img, logo, cx, cy, max_w, max_h):
    """Fit a real logo (RGBA, from ./logos) into max_w x max_h centred at (cx, cy), with a soft shadow."""
    s = min(max_w / logo.width, max_h / logo.height)
    lg = logo.resize((max(1, round(logo.width * s)), max(1, round(logo.height * s))), Image.LANCZOS)
    off = (int(cx - lg.width / 2), int(cy - lg.height / 2))
    sh = Image.new("RGBA", lg.size, (0, 0, 0, 0))
    sh.putalpha(scale_alpha(lg.getchannel("A").filter(ImageFilter.GaussianBlur(3 * SS)), 0.3))
    img.alpha_composite(sh, (off[0], off[1] + 2 * SS))
    img.alpha_composite(lg, off)


def paste_mark(img, app, cx, cy, max_w, max_h):
    """Real logo if ./logos/<key>.png exists, otherwise the drawn fallback glyph."""
    key, _, glyph, _ = app
    p = LOGOS / f"{key}.png"
    if p.exists():
        paste_logo(img, Image.open(p).convert("RGBA"), cx, cy, max_w, max_h)
    else:
        paste_glyph(img, glyph, cx, cy, max_h * 1.25)


# ---- tvOS-style layered icons: logo layer only (the app draws plate + glass at runtime) --------------
LOGO_W, LOGO_H = 640, 400          # 16:10, same as the tile
INK_TARGET = 0.085                 # share of the tile covered by logo ink (alpha) — equal optical weight for every logo
MAX_W, MAX_H = 0.60, 0.44          # never wider/taller than this share of the tile
KEEP_COLOUR = {"discovery", "svtplay"}   # multi-colour logos that lose their identity in plain white


def logo_layer(app):
    """Trimmed RGBA logo for `app`: the real logo (white, unless KEEP_COLOUR) or its drawn glyph."""
    key, _, glyph, _ = app
    p = LOGOS / f"{key}.png"
    if p.exists():
        lg = Image.open(p).convert("RGBA")
        if key not in KEEP_COLOUR:
            white = Image.new("RGBA", lg.size, (255, 255, 255, 0))
            white.putalpha(lg.getchannel("A"))
            lg = white
    else:
        layer, _ = draw_glyph(glyph, 0, 0, 800)
        lg = Image.new("RGBA", layer.size, (255, 255, 255, 0))
        lg.putalpha(layer)
    box = lg.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    return lg.crop(box) if box else lg


def make_logo(app):
    """Logo centred on a transparent 16:10 canvas, scaled by ink area so wide and square logos look equally big."""
    lg = logo_layer(app)
    ink = np.asarray(lg.getchannel("A"), np.float32).sum() / 255.0
    target = INK_TARGET * LOGO_W * LOGO_H
    s = math.sqrt(target / max(ink, 1.0))
    if lg.width / lg.height > 2.2:   # wordmarks read bigger than marks of the same ink area
        s *= 0.86
    s = min(s, MAX_W * LOGO_W / lg.width, MAX_H * LOGO_H / lg.height)
    lg = lg.resize((max(1, round(lg.width * s)), max(1, round(lg.height * s))), Image.LANCZOS)
    out = Image.new("RGBA", (LOGO_W, LOGO_H), (0, 0, 0, 0))
    out.alpha_composite(lg, ((LOGO_W - lg.width) // 2, (LOGO_H - lg.height) // 2))
    return out


def logo_sheet(logos):
    """All logos on their brand plates in one image, to eyeball optical size consistency."""
    cols, tw, th = 5, 320, 200
    sheet = Image.new("RGB", (cols * (tw + 20) + 20, ((len(logos) + cols - 1) // cols) * (th + 20) + 20), (12, 14, 30))
    for i, (app, lg) in enumerate(logos):
        tint = app[3] or "#3A3F5C"
        plate = Image.new("RGBA", (tw, th), tint)
        plate.alpha_composite(lg.resize((tw, th), Image.LANCZOS))
        sheet.paste(plate.convert("RGB"), (20 + (i % cols) * (tw + 20), 20 + (i // cols) * (th + 20)))
    return sheet


# ---- icons -----------------------------------------------------------------
def make_icon(app, size=512):
    S = size * SS
    m = int(S * 0.0625)                        # margin for the drop shadow
    box = (m, m - SHADOW_DY * SS // 2, S - m, S - m - SHADOW_DY * SS // 2)
    img = glass_card((S, S), box, app[3])
    s = box[2] - box[0]
    paste_mark(img, app, (box[0] + box[2]) / 2, (box[1] + box[3]) / 2, s * 0.62, s * 0.5)
    return img.resize((size, size), Image.LANCZOS)


def make_banner(app, w=640, h=360):
    """Full-width 16:9 glass card; logo-only when a real logo exists, else glyph + app name."""
    W, H = w * SS, h * SS
    m = int(H * 0.0625)
    box = (m, m - SHADOW_DY * SS // 2, W - m, H - m - SHADOW_DY * SS // 2)
    img = glass_card((W, H), box, app[3])
    bw, bh = box[2] - box[0], box[3] - box[1]
    if (LOGOS / f"{app[0]}.png").exists():
        paste_mark(img, app, (box[0] + box[2]) / 2, (box[1] + box[3]) / 2, bw * 0.6, bh * 0.5)
        return img.resize((w, h), Image.LANCZOS)
    gx = box[0] + bh * 0.55
    paste_glyph(img, app[2], gx, (box[1] + box[3]) / 2, bh * 0.5)
    f = fit_font(FONT_BOLD, app[1], (box[2] - gx - bh * 0.45) - bh * 0.15, bh * 0.2)
    d = ImageDraw.Draw(img)
    d.text((gx + bh * 0.36, (box[1] + box[3]) / 2), app[1], font=f, fill=(255, 255, 255, 245), anchor="lm")
    return img.resize((w, h), Image.LANCZOS)


def make_wide(app, w=640, h=360):
    """Wider-than-square button (WIDE_ASPECT) centred on a transparent 16:9 banner canvas."""
    W, H = w * SS, h * SS
    bh = int(H * 0.78)
    bw = int(bh * WIDE_ASPECT)
    x0, y0 = (W - bw) // 2, (H - bh) // 2 - SHADOW_DY * SS // 2
    box = (x0, y0, x0 + bw, y0 + bh)
    img = glass_card((W, H), box, app[3])
    paste_mark(img, app, W / 2, y0 + bh / 2, bw * 0.62, bh * 0.5)
    return img.resize((w, h), Image.LANCZOS)


# ---- wallpapers ------------------------------------------------------------
def cover(img, w, h):
    """Scale and centre-crop to exactly w x h."""
    s = max(w / img.width, h / img.height)
    img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
    x, y = (img.width - w) // 2, (img.height - h) // 2
    return img.crop((x, y, x + w, y + h))


def make_wallpaper(dark=True, w=3840, h=2160, seed=7):
    rnd = random.Random(seed)
    lw, lh = 640, 360
    if dark:
        base, blobs = (8, 10, 28), [(40, 70, 220), (120, 50, 200), (20, 160, 170), (70, 40, 160), (30, 100, 210)]
    else:
        base, blobs = (226, 232, 248), [(140, 170, 255), (200, 160, 250), (130, 220, 215), (170, 190, 255), (230, 190, 240)]
    img = Image.new("RGB", (lw, lh), base)
    spots = [(0.18, 0.25), (0.78, 0.2), (0.6, 0.82), (0.15, 0.85), (0.45, 0.45)]
    for (fx, fy), col in zip(spots, blobs):
        layer = Image.new("RGB", (lw, lh), base)
        msk = Image.new("L", (lw, lh), 0)
        r = rnd.uniform(0.22, 0.34) * lw
        x, y = fx * lw + rnd.uniform(-20, 20), fy * lh + rnd.uniform(-20, 20)
        ImageDraw.Draw(msk).ellipse((x - r, y - r * 0.8, x + r, y + r * 0.8), fill=230 if dark else 210)
        layer.paste(col, mask=msk)
        img = Image.blend(img, Image.composite(layer, img, msk.filter(ImageFilter.GaussianBlur(70))), 1)
    img = img.filter(ImageFilter.GaussianBlur(40)).resize((w, h), Image.BICUBIC)
    a = np.asarray(img, np.float32) + np.random.default_rng(seed).normal(0, 1.6, (h, w, 1))  # dither: no banding
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


# ---- preview ---------------------------------------------------------------
def make_preview(wall):
    """Mock home screen: iOS 26 lock-screen clock in the middle, wide glass buttons below."""
    bg = cover(wall, 1920, 1080).convert("RGBA")
    txt = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(txt)
    d.text((960, 150), "Monday 26 October", font=ImageFont.truetype(FONT_BOLD, 40), fill=(255, 255, 255, 215), anchor="mm")
    d.text((960, 300), "21:37", font=ImageFont.truetype(FONT_BOLD, 250), fill=(255, 255, 255, 225), anchor="mm")
    sh = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    sh.putalpha(scale_alpha(txt.getchannel("A").filter(ImageFilter.GaussianBlur(10)), 0.35))
    bg.alpha_composite(sh, (0, 4))
    bg.alpha_composite(txt)

    apps = {a[0]: a for a in APPS}
    cw, ch, step = 320, 180, 252                       # card canvas is 16:9, glass shape is narrower
    x = (1920 - step * (len(PREVIEW_KEYS) - 1) - cw) // 2
    label = ImageFont.truetype(FONT_TEXT, 26)
    for i, k in enumerate(PREVIEW_KEYS):
        f = 1.12 if i == 0 else 1.0                    # first one "focused"
        ic = make_wide(apps[k], int(cw * f), int(ch * f))
        bg.alpha_composite(ic, (x - (ic.width - cw) // 2, 640 - (ic.height - ch) // 2))
        ImageDraw.Draw(bg).text((x + cw // 2, 640 + ch + 4), apps[k][1], font=label, fill=(255, 255, 255, 230), anchor="mt")
        x += step
    return bg.convert("RGB")


def apple_wallpapers():
    """Apple wallpapers dropped in ./src (personal use only - not for redistribution)."""
    return {p.stem: Image.open(p).convert("RGB") for p in sorted(SRC.glob("*.*"))}


def main():
    OUT.mkdir(exist_ok=True)
    apple = apple_wallpapers()
    if "--preview" in sys.argv:
        make_preview(apple["sierra"] if "sierra" in apple else make_wallpaper(True)).save(OUT / "preview.png")
        print(OUT / "preview.png")
        return
    walls = {"dark": make_wallpaper(True), "light": make_wallpaper(False), **apple}
    for name, img in walls.items():
        cover(img, 3840, 2160).save(OUT / f"wallpaper_{name}_3840x2160.jpg", quality=95)
        cover(img, 1920, 1080).save(OUT / f"wallpaper_{name}_1920x1080.jpg", quality=95)
    for d in ("icons", "banners", "wide", "logos"):
        (OUT / d).mkdir(exist_ok=True)
    logos = []
    for app in APPS:
        lg = make_logo(app)
        lg.save(OUT / "logos" / f"{app[0]}.png")
        logos.append((app, lg))
    (OUT / "brands.json").write_text(json.dumps({a[0]: a[3] for a in APPS}, indent=1))
    logo_sheet(logos).save(OUT / "logo-sheet.png")
    if "--sheet" in sys.argv:
        print(OUT / "logo-sheet.png")
        return
    for app in APPS:
        make_icon(app).save(OUT / "icons" / f"{app[0]}.png")
        make_banner(app).save(OUT / "banners" / f"{app[0]}.png")
        make_wide(app).save(OUT / "wide" / f"{app[0]}.png")
    make_preview(walls.get("sierra", walls["dark"])).save(OUT / "preview.png")
    with zipfile.ZipFile(OUT / "projectivy-glass.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for p in OUT.rglob("*.png"):
            z.write(p, p.relative_to(OUT))
    print(f"done: {len(APPS)} apps -> {OUT}")


if __name__ == "__main__":
    main()




