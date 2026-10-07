#!/usr/bin/env python3
"""Make the Google Play listing graphics for Palaya Chess (P1).

Outputs (into dist/play-kit/ by default; `--out DIR` to change):

  play_icon_512.png        512 x 512, 32-bit PNG with alpha (RGBA), < 1 MB.
                           The launcher icon itself: the adaptive icon's layers
                           (white background, `ic_launcher_foreground.png` from
                           mipmap-xxxhdpi), cropped to the 72 dp visible area of the
                           108 dp adaptive canvas and scaled to 512. Full-bleed
                           square: Play applies its own rounded mask.
  play_feature_1024x500.png  1024 x 500, 24-bit PNG (RGB, no alpha).
                           Our words and our art only: the app's green palette
                           (ui/theme/Color.kt), the launcher mark, and the board
                           exactly as the app draws it (Cburnett pieces, CC BY-SA 3.0,
                           credited in the app), cut from our own phone screenshot
                           docs/play/screenshots/phone_02_best_line.png.

Inputs are all in the repo. Run from the repo root:

    python docs/play/make_graphics.py [--out dist/play-kit]

Needs Pillow. The text font is the first that exists of: $PALAYA_FONT_BOLD /
$PALAYA_FONT_REGULAR, Roboto, Segoe UI (Windows), DejaVu Sans. Text is rendered
into the image; no font file is shipped.
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FOREGROUND = os.path.join(ROOT, "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png")
BOARD_SHOT = os.path.join(ROOT, "docs/play/screenshots/phone_02_best_line.png")

# ui/theme/Color.kt
SURFACE_DARK = (0x26, 0x24, 0x21)
CHROME_DARK = (0x30, 0x2E, 0x2B)
ELEVATED_DARK = (0x3C, 0x3A, 0x37)
GREEN_PRIMARY = (0x81, 0xB6, 0x4C)
GREEN_HOVER = (0xA3, 0xD1, 0x60)
ON_DARK_PRIMARY = (0xFA, 0xF9, 0xF6)
ON_DARK_SECONDARY = (0xCB, 0xC8, 0xC1)
LAUNCHER_BACKGROUND = (0xFF, 0xFF, 0xFF)  # res/values/colors.xml ic_launcher_background

# Board area inside the 1080 x 1920 Board screenshot (eval bar + board), measured.
BOARD_BOX = (43, 291, 1038, 1196)

APP_NAME = "Palaya Chess"
TAGLINE = "Review your chess games on your phone"
POINTS = ["Every move graded", "Missed tactics shown", "Practise your mistakes"]


def font(bold, size):
    env = os.environ.get("PALAYA_FONT_BOLD" if bold else "PALAYA_FONT_REGULAR")
    candidates = [env] if env else []
    win = os.environ.get("WINDIR", "C:/Windows")
    if bold:
        candidates += ["Roboto-Bold.ttf", os.path.join(win, "Fonts", "segoeuib.ttf"), "DejaVuSans-Bold.ttf"]
    else:
        candidates += ["Roboto-Regular.ttf", os.path.join(win, "Fonts", "segoeui.ttf"), "DejaVuSans.ttf"]
    for c in candidates:
        try:
            return ImageFont.truetype(c, size)
        except OSError:
            continue
    sys.exit("No usable font found; set PALAYA_FONT_BOLD / PALAYA_FONT_REGULAR")


def launcher_square(size):
    """The adaptive launcher icon as the launcher shows it, before the device mask."""
    fg = Image.open(FOREGROUND).convert("RGBA")
    w, h = fg.size  # 432 = 108 dp at xxxhdpi
    canvas = Image.new("RGBA", fg.size, LAUNCHER_BACKGROUND + (255,))
    canvas.alpha_composite(fg)
    inset = (w - w * 72 // 108) // 2  # the visible 72 dp of the 108 dp canvas
    visible = canvas.crop((inset, inset, w - inset, h - inset))
    return visible.resize((size, size), Image.LANCZOS)


def make_icon(out_dir):
    icon = launcher_square(512)  # RGBA, opaque
    path = os.path.join(out_dir, "play_icon_512.png")
    icon.save(path, optimize=True)
    return path


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return m


def make_feature(out_dir):
    W, H = 1024, 500
    img = Image.new("RGB", (W, H), SURFACE_DARK)
    d = ImageDraw.Draw(img)

    # Soft vertical gradient from the chrome colour to the surface colour.
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(CHROME_DARK[i] * (1 - t) + SURFACE_DARK[i] * t) for i in range(3))
        d.line([(0, y), (W, y)], fill=c)
    # A broad green band behind the board, our accent colour.
    band = Image.new("L", (W, H), 0)
    ImageDraw.Draw(band).polygon([(600, 0), (W, 0), (W, H), (520, H)], fill=255)
    band = band.filter(ImageFilter.GaussianBlur(2))
    img.paste(Image.new("RGB", (W, H), (0x3E, 0x5C, 0x25)), (0, 0), band)

    # Board from our own screenshot, with a shadow. Kept well inside the edges.
    if not os.path.exists(BOARD_SHOT):
        sys.exit("Missing " + BOARD_SHOT + " (take the phone screenshots first)")
    board = Image.open(BOARD_SHOT).convert("RGB").crop(BOARD_BOX)
    bh = 360
    bw = round(board.width * bh / board.height)
    board = board.resize((bw, bh), Image.LANCZOS)
    bx, by = W - 64 - bw, (H - bh) // 2
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((bx + 6, by + 10, bx + bw + 6, by + bh + 10), radius=14, fill=150)
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    img.paste((0, 0, 0), (0, 0), shadow)
    img.paste(board, (bx, by), rounded_mask((bw, bh), 10))

    # Left column: mark, name, tagline, three points.
    x = 64
    mark = launcher_square(96)
    img.paste(mark.convert("RGB"), (x, 66), rounded_mask((96, 96), 22))

    f_name = font(True, 56)
    f_tag = font(False, 25)
    f_pt = font(True, 22)
    d = ImageDraw.Draw(img)
    d.text((x, 176), APP_NAME, font=f_name, fill=ON_DARK_PRIMARY)
    # Tagline, wrapped to the space left of the board.
    max_w = bx - x - 40
    words, lines, cur = TAGLINE.split(), [], ""
    for wd in words:
        trial = (cur + " " + wd).strip()
        if d.textlength(trial, font=f_tag) <= max_w:
            cur = trial
        else:
            lines.append(cur)
            cur = wd
    lines.append(cur)
    ty = 254
    for ln in lines:
        d.text((x, ty), ln, font=f_tag, fill=ON_DARK_SECONDARY)
        ty += 36
    py = ty + 22
    for p in POINTS:
        d.ellipse((x, py + 8, x + 12, py + 20), fill=GREEN_HOVER)
        d.text((x + 24, py), p, font=f_pt, fill=ON_DARK_PRIMARY)
        py += 32

    path = os.path.join(out_dir, "play_feature_1024x500.png")
    img.save(path, optimize=True)  # mode RGB: 24-bit, no alpha
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "dist", "play-kit"))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for p in (make_icon(a.out), make_feature(a.out)):
        im = Image.open(p)
        print(f"{os.path.relpath(p, ROOT)}  {im.size[0]}x{im.size[1]}  mode={im.mode}  {os.path.getsize(p):,} B")


if __name__ == "__main__":
    main()
