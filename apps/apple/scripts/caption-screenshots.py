#!/usr/bin/env python3
"""Compose App Store caption plates over the raw in-app captures.

Usage: caption-screenshots.py [6.9|13]   (default 6.9)

Reads `docs/apple-port/screenshots/<set>/<n>-<screen>-<lang>.png` and writes
`.../plated/` at the same size — the plate is drawn INTO the frame rather than
added above it, because a letterboxed composite is a different pixel size and
gets rejected at upload. `6.9` is 1320x2868 (iPhone 17 Pro Max), `13` is
2064x2752 (iPad Pro 13-inch); those are the two slots App Store Connect
requires for this app.

Arabic is laid out right-to-left and shaped: PIL renders Unicode Arabic
unshaped and in logical order, so the glyphs come out disconnected and
backwards without the two passes below. If the shaping libraries are missing
the script says so and skips AR rather than emitting broken text into a store
listing.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
# Which device set to plate: `6.9` (iPhone, 1320x2868) or `13` (iPad,
# 2064x2752). Plate geometry is derived from the frame in `plate()`, so a new
# accepted size needs a directory of captures and nothing here.
SRC = ROOT / "docs/apple-port/screenshots" / (sys.argv[1] if len(sys.argv) > 1 else "6.9")
DST = SRC / "plated"

# From docs/apple-port/store-listing-apple.md — kept in one place there, copied
# here rather than parsed, because a listing edit should be a deliberate
# re-render and not a silent change to shipped assets.
CAPTIONS = {
    "1-pick":       ("Nothing leaves your device",          "لا شيء يغادر جهازك"),
    "2-options":    ("Choose exactly what to filter",       "اختر بالضبط ما تريد فلترته"),
    "3-strictness": ("You set how cautious it is",          "أنت تحدد مدى التحفّظ"),
    "4-progress":   ("Leave and come back — it resumes",    "اخرج وعُد — يكمل من حيث توقف"),
    "5-done":       ("Your original is untouched",          "ملفك الأصلي كما هو"),
    "6-about":      ("No account. No network. No analytics.", "بلا حساب. بلا شبكة. بلا تتبّع."),
}

# Brand colours, matching Theme.swift.
INK = (16, 24, 22)
PAPER = (247, 249, 248)

# Proportions, not pixels — measured on the 6.9" frame (1320x2868) and applied
# to whatever size the capture is, so the iPad set composes from the same
# numbers. Plate height and type scale with height, the margin with width.
PLATE_FRAC = 520 / 2868   # leaves the device art dominant
MARGIN_FRAC = 88 / 1320
TYPE_FRAC = 92 / 2868
TYPE_MIN_FRAC = 56 / 2868


def shape_arabic(text):
    """Logical-order Unicode -> shaped, right-to-left visual order."""
    try:
        import arabic_reshaper
        from bidi.algorithm import get_display
    except ImportError:
        return None
    return get_display(arabic_reshaper.reshape(text))


def _is_tofu(f, ch):
    """True when the face draws `ch` as .notdef.

    Rendered and compared against U+FFFF, which no font has. The obvious probes
    both lie: `getmask(ch).size[0] > 0` is true for the tofu box itself, since
    the box has a width, and a cmap lookup would not catch a face that maps the
    codepoint to an empty glyph.
    """
    def bitmap(c):
        im = Image.new("L", (100, 100), 0)
        ImageDraw.Draw(im).text((10, 10), c, font=f, fill=255)
        return im.tobytes()
    return bitmap(ch) == bitmap("￿")


def font(size, arabic, text):
    """Resolve a face that can draw **this exact string**, or raise.

    Two bugs live here, both of which shipped tofu into a store asset before
    being caught by looking at the PNG:

    1. The first version fell back to `ImageFont.load_default()` when the
       Arabic face would not load — and it would not, because GeezaPro is not
       at the path it assumed. The default is a tiny bitmap font with no Arabic
       coverage, so every AR plate was a row of boxes and the script still
       exited 0 saying "12 plated".
    2. The second version probed with a fixed sample word. Al Nile draws
       "مرحبا" perfectly and still has no **U+FE8D** — the isolated
       presentation form of alef — so "الأصلي" came out with one box in it.
       Many Arabic faces shape from base codepoints and omit Presentation
       Forms-B entirely, which is exactly the block `arabic_reshaper` emits.

    So the probe is the real caption, and a face that cannot draw all of it is
    skipped rather than used.
    """
    candidates = (["/System/Library/Fonts/Supplemental/Damascus.ttc",
                   "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"] if arabic else
                  ["/System/Library/Fonts/SFNS.ttf",
                   "/System/Library/Fonts/Helvetica.ttc"])
    tried = []
    for p in candidates:
        if not Path(p).exists():
            continue
        try:
            f = ImageFont.truetype(p, size, index=0)
        except OSError:
            continue
        missing = [c for c in dict.fromkeys(text) if c.strip() and _is_tofu(f, c)]
        if not missing:
            return f
        tried.append(f"{Path(p).name} (no {' '.join(f'U+{ord(c):04X}' for c in missing)})")
    raise SystemExit(f"no font can draw {text!r}; tried: {tried or candidates}")


def wrap(draw, text, f, max_w):
    words, lines, cur = text.split(), [], ""
    for w in words:
        trial = f"{cur} {w}".strip()
        if draw.textlength(trial, font=f) <= max_w or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def plate(src_path, caption, arabic, out_path):
    img = Image.open(src_path).convert("RGB")
    W, H = img.size
    plate_h = round(H * PLATE_FRAC)
    margin = round(W * MARGIN_FRAC)
    canvas = Image.new("RGB", (W, H), PAPER)
    # Device art keeps its aspect ratio and sits below the plate.
    art_h = H - plate_h
    scale = min(W / img.width, art_h / img.height)
    art = img.resize((int(img.width * scale), int(img.height * scale)), Image.LANCZOS)
    canvas.paste(art, ((W - art.width) // 2, plate_h))

    d = ImageDraw.Draw(canvas)
    size = round(H * TYPE_FRAC)
    # Wrap in LOGICAL order, then shape+bidi each line separately.
    #
    # Doing it the other way round — bidi the whole caption, then word-wrap the
    # visual string — is wrong for RTL and looks almost right, which is worse:
    # `get_display` moves the sentence-final "." to the visual left, so
    # splitting on spaces stranded it alone on a second line.
    probe = shape_arabic(caption) if arabic else caption
    f = font(size, arabic, probe)
    lines = wrap(d, caption, f, W - 2 * margin)
    while len(lines) > 2 and size > round(H * TYPE_MIN_FRAC):
        size -= 8
        f = font(size, arabic, probe)
        lines = wrap(d, caption, f, W - 2 * margin)

    total = sum(int(size * 1.22) for _ in lines)
    y = (plate_h - total) // 2
    for line in lines:
        visual = shape_arabic(line) if arabic else line
        w = d.textlength(visual, font=f)
        d.text(((W - w) / 2, y), visual, font=f, fill=INK)
        y += int(size * 1.22)

    canvas.save(out_path)
    return canvas.size


def main():
    DST.mkdir(parents=True, exist_ok=True)
    missing_shaper = False
    made = 0
    for stem, (en, ar) in CAPTIONS.items():
        for lang, text in (("en", en), ("ar", ar)):
            src = SRC / f"{stem}-{lang}.png"
            if not src.exists():
                print(f"  skip {src.name}: not captured")
                continue
            if lang == "ar" and shape_arabic(text) is None:
                missing_shaper = True
                continue
            size = plate(src, text, lang == "ar", DST / f"{stem}-{lang}.png")
            print(f"  {stem}-{lang}.png  {size[0]}x{size[1]}")
            made += 1

    if missing_shaper:
        print("\n  AR SKIPPED — needs: pip3 install arabic-reshaper python-bidi", file=sys.stderr)
        print("  (unshaped Arabic renders disconnected and left-to-right; "
              "shipping that is worse than shipping nothing)", file=sys.stderr)
    print(f"\n{made} plated -> {DST}")
    return 1 if missing_shaper else 0


if __name__ == "__main__":
    sys.exit(main())
