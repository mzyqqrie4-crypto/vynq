"""vynq brand geometry generator.

Single source of truth for the symbol and wordmark. Emits:
  brand/svg/*.svg                         master vector assets
  android/core/designsystem/.../drawable  Compose-usable vector drawables
  android/app/.../drawable                launcher, notification, splash vectors

Run:  python brand/tools/generate.py
"""
from __future__ import annotations

import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SVG_DIR = ROOT / "brand" / "svg"
DS_DRAWABLE = ROOT / "android" / "core" / "designsystem" / "src" / "main" / "res" / "drawable"
APP_DRAWABLE = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"

# ---- Palette (mirrors brand/tokens.json) -----------------------------------
BLUE_300 = "#7CC0FF"
BLUE_500 = "#3D7BFF"
VIOLET_500 = "#6A4BFF"
VIOLET_700 = "#3B1FB8"
PURPLE_400 = "#B15CFF"
PINK_400 = "#FF6FD2"
INK_950 = "#07070C"
INK_900 = "#0E0E16"
GRAPHITE_800 = "#1A1B24"
WHITE = "#FFFFFF"
SOFT_WHITE = "#F5F5F7"

# ---- Symbol geometry --------------------------------------------------------
# Two egg-shaped lobes (wider at the crown, tapering to a tip), tilted towards
# each other so the tips cross: two people leaning in, one continuous loop.
RX, RY, TAPER, TILT_DEG, OFFSET = 100.0, 170.0, 0.30, 31.0, 74.0
SAMPLES = 40
CANVAS = 512.0  # canonical symbol space: tight bbox fitted to 512 on its long side


def _egg(cx: float, tilt: float) -> list[tuple[float, float]]:
    th = math.radians(tilt)
    pts = []
    for i in range(SAMPLES):
        t = 2 * math.pi * i / SAMPLES
        x = RX * math.sin(t) * (1 + TAPER * math.cos(t))
        y = -RY * math.cos(t)
        pts.append((cx + x * math.cos(th) - y * math.sin(th), x * math.sin(th) + y * math.cos(th)))
    return pts


def _lobes() -> tuple[list, list, float, float]:
    left = _egg(-OFFSET, -TILT_DEG)
    right = [(-x, y) for (x, y) in reversed(left)]
    xs = [p[0] for p in left + right]
    ys = [p[1] for p in left + right]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    pad = 6.0  # splines overshoot the samples slightly; keep strokes inside the viewBox
    s = (CANVAS - 2 * pad) / max(w, h)
    ox = pad - min(xs) * s
    oy = pad - min(ys) * s
    fit = lambda pts: [(x * s + ox, y * s + oy) for x, y in pts]
    return fit(left), fit(right), w * s + 2 * pad, h * s + 2 * pad


def _path(pts: list[tuple[float, float]]) -> str:
    """Closed Catmull-Rom spline through pts as cubic Béziers."""
    n = len(pts)
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    d = [f"M{f(pts[0][0])},{f(pts[0][1])}"]
    for i in range(n):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d.append(f"C{f(c1[0])},{f(c1[1])} {f(c2[0])},{f(c2[1])} {f(p2[0])},{f(p2[1])}")
    return " ".join(d) + "Z"


LEFT_PTS, RIGHT_PTS, SYM_W, SYM_H = _lobes()
LEFT, RIGHT = _path(LEFT_PTS), _path(RIGHT_PTS)


def _centroid(pts):
    return sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)


LC, RC = _centroid(LEFT_PTS), _centroid(RIGHT_PTS)


def _crown(pts):  # topmost sample: where the gloss highlight sits
    return min(pts, key=lambda p: p[1])


LT, RT = _crown(LEFT_PTS), _crown(RIGHT_PTS)

# Gradient anchor points in canonical space
LG = ((LT[0] - 30, LT[1]), (SYM_W * 0.62, SYM_H))  # crown → tip
RG = ((RT[0] + 30, RT[1]), (SYM_W * 0.38, SYM_H))
OG = ((SYM_W / 2, SYM_H * 0.45), (SYM_W / 2, SYM_H))
GLOSS_R = 150.0


# ---- Wordmark geometry ------------------------------------------------------
# Custom geometric lowercase built from a single monoline stroke so it needs no
# font licence and renders identically everywhere. x-height 200, stroke 38.
STROKE = 38.0
WM_ADV = [222, 222, 236, 0]  # advance after v, y, n, (q last)


def _wordmark_paths() -> tuple[list[str], float, float]:
    h = STROKE / 2
    glyphs = {
        "v": lambda o: f"M{o + h},{h} L{o + 100},{200 - h} L{o + 200 - h},{h}",
        "y": lambda o: f"M{o + h},{h} L{o + 104},{178 - h * 0.2} M{o + 200 - h},{h} L{o + 64},{290 - h}",
        "n": lambda o: f"M{o + h},{200 - h} L{o + h},{h} M{o + h},{100} "
                       f"A{100 - h},{100 - h} 0 0 1 {o + 200 - h},{100} L{o + 200 - h},{200 - h}",
        "q": lambda o: f"M{o + 200 - h},{100} A{100 - h},{100 - h} 0 1 0 {o + h},{100} "
                       f"A{100 - h},{100 - h} 0 1 0 {o + 200 - h},{100} M{o + 200 - h},{h} L{o + 200 - h},{290 - h}",
    }
    out, x = [], 0.0
    for ch, adv in zip("vynq", WM_ADV):
        out.append(glyphs[ch](x))
        x += adv
    return out, x + 200, 290.0


WM_PATHS, WM_W, WM_H = _wordmark_paths()

# ---- SVG emitters -----------------------------------------------------------


def svg_defs(prefix: str = "", mono: bool = False) -> str:
    if mono:
        a, b, c, d, e = "#FFFFFF", "#C9C9D2", "#8C8C99", "#FFFFFF", "#6E6E7A"
        stops_l = [(0, a), (1, c)]
        stops_r = [(0, b), (1, e)]
        stops_o = [(0, d), (1, b)]
    else:
        stops_l = [(0, BLUE_300), (0.45, BLUE_500), (1, VIOLET_700)]
        stops_r = [(0, PINK_400), (0.5, PURPLE_400), (1, VIOLET_700)]
        stops_o = [(0, "#D9CCFF"), (0.55, "#8F7BFF"), (1, VIOLET_500)]

    def lin(id_, p, stops):
        s = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
        return (f'<linearGradient id="{prefix}{id_}" gradientUnits="userSpaceOnUse" '
                f'x1="{p[0][0]:.1f}" y1="{p[0][1]:.1f}" x2="{p[1][0]:.1f}" y2="{p[1][1]:.1f}">{s}</linearGradient>')

    gloss = (f'<radialGradient id="{prefix}gloss" gradientUnits="objectBoundingBox" cx="0.5" cy="0.5" r="0.5">'
             f'<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>'
             f'<stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>')
    return ("<defs>" + lin("l", LG, stops_l) + lin("r", RG, stops_r) + lin("o", OG, stops_o) + gloss +
            f'<clipPath id="{prefix}cl"><path d="{LEFT}"/></clipPath>'
            f'<clipPath id="{prefix}cr"><path d="{RIGHT}"/></clipPath></defs>')


def svg_symbol_body(prefix: str = "") -> str:
    g = GLOSS_R
    return (
        f'<path d="{RIGHT}" fill="url(#{prefix}r)"/>'
        f'<path d="{LEFT}" fill="url(#{prefix}l)"/>'
        f'<path d="{RIGHT}" fill="url(#{prefix}o)" clip-path="url(#{prefix}cl)" opacity="0.92"/>'
        f'<g clip-path="url(#{prefix}cl)"><ellipse cx="{LT[0] + 18:.1f}" cy="{LT[1] + 70:.1f}" rx="{g * .55:.1f}" ry="{g * .42:.1f}" fill="url(#{prefix}gloss)"/></g>'
        f'<g clip-path="url(#{prefix}cr)"><ellipse cx="{RT[0] - 18:.1f}" cy="{RT[1] + 70:.1f}" rx="{g * .55:.1f}" ry="{g * .42:.1f}" fill="url(#{prefix}gloss)"/></g>'
        f'<path d="{LEFT}" fill="none" stroke="#FFFFFF" stroke-opacity="0.22" stroke-width="2.5"/>'
        f'<path d="{RIGHT}" fill="none" stroke="#FFFFFF" stroke-opacity="0.16" stroke-width="2.5"/>'
    )


def svg_solid_symbol(color: str) -> str:
    """Single-colour mark: lobes united, overlap knocked out (even-odd)."""
    return f'<path d="{LEFT} {RIGHT}" fill="{color}" fill-rule="evenodd"/>'


def svg_wordmark(color: str) -> str:
    return "".join(
        f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{STROKE}" stroke-linecap="round" stroke-linejoin="round"/>'
        for d in WM_PATHS)


def doc(w: float, h: float, body: str, bg: str | None = None) -> str:
    rect = f'<rect width="{w:.0f}" height="{h:.0f}" fill="{bg}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w:.0f} {h:.0f}" width="{w:.0f}" height="{h:.0f}">'
            f"{rect}{body}</svg>\n")


def place(body: str, x: float, y: float, s: float) -> str:
    return f'<g transform="translate({x:.2f} {y:.2f}) scale({s:.4f})">{body}</g>'


def icon_bg(kind: str, size: float = 1024) -> str:
    if kind == "light":
        return (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/>'
                f'<stop offset="1" stop-color="#ECEBF2"/></linearGradient></defs><rect width="{size}" height="{size}" fill="url(#bg)"/>')
    glow = "#3A2A9E" if kind == "dark" else "#2C2C36"
    return (f'<defs><radialGradient id="bg" cx="0.5" cy="0.46" r="0.62"><stop offset="0" stop-color="{glow}"/>'
            f'<stop offset="0.55" stop-color="{INK_900}"/><stop offset="1" stop-color="{INK_950}"/></radialGradient></defs>'
            f'<rect width="{size}" height="{size}" fill="url(#bg)"/>')


def write_svgs() -> None:
    SVG_DIR.mkdir(parents=True, exist_ok=True)
    W = lambda name, text: (SVG_DIR / name).write_text(text, encoding="utf-8")
    sym = svg_defs() + svg_symbol_body()
    W("vynq-symbol.svg", doc(SYM_W, SYM_H, sym))
    W("vynq-symbol-monochrome.svg", doc(SYM_W, SYM_H, svg_defs("m", mono=True) + svg_symbol_body("m")))
    W("vynq-symbol-single-white.svg", doc(SYM_W, SYM_H, svg_solid_symbol(WHITE)))
    W("vynq-symbol-single-ink.svg", doc(SYM_W, SYM_H, svg_solid_symbol(INK_950)))
    W("vynq-symbol-single-blue.svg", doc(SYM_W, SYM_H, svg_solid_symbol(BLUE_500)))
    W("vynq-wordmark-white.svg", doc(WM_W, WM_H, svg_wordmark(SOFT_WHITE)))
    W("vynq-wordmark-ink.svg", doc(WM_W, WM_H, svg_wordmark(INK_950)))

    # Lockups
    s = 300 / SYM_H
    stacked_w = max(SYM_W * s, WM_W * 0.55) + 160
    stacked = place(sym, (stacked_w - SYM_W * s) / 2, 80, s) + place(svg_wordmark(SOFT_WHITE), (stacked_w - WM_W * .55) / 2, 80 + 300 + 70, .55)
    W("vynq-logo-stacked-dark.svg", doc(stacked_w, 80 + 300 + 70 + WM_H * .55 + 80, stacked, INK_950))
    hs = 220 / SYM_H
    ws = 220 / 200  # wordmark x-height matches ~symbol height
    ws = 0.62
    hw = SYM_W * hs + 60 + WM_W * ws + 160
    horiz = place(sym, 80, 60, hs) + place(svg_wordmark(SOFT_WHITE), 80 + SYM_W * hs + 60, 60 + 220 / 2 - 100 * ws, ws)
    W("vynq-logo-horizontal-dark.svg", doc(hw, 340, horiz, INK_950))
    horiz_l = place(svg_defs("x") + svg_symbol_body("x"), 80, 60, hs) + place(svg_wordmark(INK_950), 80 + SYM_W * hs + 60, 60 + 220 / 2 - 100 * ws, ws)
    W("vynq-logo-horizontal-light.svg", doc(hw, 340, horiz_l, SOFT_WHITE))

    # App icons (1024, full-bleed; platforms apply their own masks)
    def icon(kind: str, body: str) -> str:
        s = 600 / SYM_W
        return doc(1024, 1024, icon_bg(kind) + place(body, (1024 - SYM_W * s) / 2, (1024 - SYM_H * s) / 2, s))

    W("app-icon-dark.svg", icon("dark", sym))
    W("app-icon-light.svg", icon("light", svg_defs("k") + svg_symbol_body("k")))
    W("app-icon-mono.svg", icon("mono", svg_defs("m", mono=True) + svg_symbol_body("m")))

    # Splash (portrait 1290x2796 reference board)
    sw, sh = 1290, 2796
    ss = 420 / SYM_W
    splash = (icon_bg("dark", 1).replace('width="1" height="1"', f'width="{sw}" height="{sh}"') +
              place(sym, (sw - SYM_W * ss) / 2, sh * .40 - SYM_H * ss / 2, ss) +
              place(svg_wordmark(SOFT_WHITE), (sw - WM_W * .5) / 2, sh * .40 + SYM_H * ss / 2 + 70, .5))
    W("splash-dark.svg", doc(sw, sh, splash))


# ---- Android VectorDrawable emitters ---------------------------------------
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def _avd_lin(p, stops) -> str:
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" '
            f'android:startX="{p[0][0]:.1f}" android:startY="{p[0][1]:.1f}" android:endX="{p[1][0]:.1f}" android:endY="{p[1][1]:.1f}">'
            f"{items}</gradient></aapt:attr>")


def _avd_gloss(cx: float, cy: float, r: float) -> str:
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="{cx:.1f}" '
            f'android:centerY="{cy:.1f}" android:gradientRadius="{r:.1f}">'
            f'<item android:offset="0" android:color="#8CFFFFFF"/><item android:offset="1" android:color="#00FFFFFF"/>'
            f"</gradient></aapt:attr>")


def _ellipse_path(cx, cy, rx, ry) -> str:
    return f"M{cx - rx:.1f},{cy:.1f} a{rx:.1f},{ry:.1f} 0 1 0 {2 * rx:.1f},0 a{rx:.1f},{ry:.1f} 0 1 0 {-2 * rx:.1f},0Z"


def avd_symbol_body() -> str:
    g = GLOSS_R
    gl = (LT[0] + 18, LT[1] + 70)
    gr = (RT[0] - 18, RT[1] + 70)
    return (
        f'<path android:pathData="{RIGHT}">{_avd_lin(RG, [(0, PINK_400), (0.5, PURPLE_400), (1, VIOLET_700)])}</path>'
        f'<path android:pathData="{LEFT}">{_avd_lin(LG, [(0, BLUE_300), (0.45, BLUE_500), (1, VIOLET_700)])}</path>'
        f'<group><clip-path android:pathData="{LEFT}"/>'
        f'<path android:pathData="{RIGHT}" android:fillAlpha="0.92">{_avd_lin(OG, [(0, "#D9CCFF"), (0.55, "#8F7BFF"), (1, VIOLET_500)])}</path>'
        f'<path android:pathData="{_ellipse_path(gl[0], gl[1], g * .55, g * .42)}">{_avd_gloss(gl[0], gl[1], g * .55)}</path></group>'
        f'<group><clip-path android:pathData="{RIGHT}"/>'
        f'<path android:pathData="{_ellipse_path(gr[0], gr[1], g * .55, g * .42)}">{_avd_gloss(gr[0], gr[1], g * .55)}</path></group>'
        f'<path android:pathData="{LEFT}" android:strokeColor="#38FFFFFF" android:strokeWidth="2.5"/>'
        f'<path android:pathData="{RIGHT}" android:strokeColor="#29FFFFFF" android:strokeWidth="2.5"/>'
    )


def avd(w_dp: float, h_dp: float, vw: float, vh: float, body: str, header: str = "") -> str:
    return (f'<?xml version="1.0" encoding="utf-8"?>\n{header}'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" {AAPT}\n'
            f'    android:width="{w_dp:.0f}dp" android:height="{h_dp:.0f}dp"\n'
            f'    android:viewportWidth="{vw:.1f}" android:viewportHeight="{vh:.1f}">\n{body}\n</vector>\n')


def avd_place(body: str, x: float, y: float, s: float) -> str:
    return (f'<group android:translateX="{x:.3f}" android:translateY="{y:.3f}" '
            f'android:scaleX="{s:.5f}" android:scaleY="{s:.5f}">{body}</group>')


GEN = "<!-- Generated by brand/tools/generate.py. Do not edit by hand. -->\n"


def write_android() -> None:
    DS_DRAWABLE.mkdir(parents=True, exist_ok=True)
    APP_DRAWABLE.mkdir(parents=True, exist_ok=True)
    (DS_DRAWABLE / "vynq_symbol.xml").write_text(
        avd(SYM_W / 4, SYM_H / 4, SYM_W, SYM_H, avd_symbol_body(), GEN), encoding="utf-8")
    wm = "".join(f'<path android:pathData="{d}" android:strokeColor="#FFFFFFFF" android:strokeWidth="{STROKE}" '
                 f'android:strokeLineCap="round" android:strokeLineJoin="round"/>' for d in WM_PATHS)
    (DS_DRAWABLE / "vynq_wordmark.xml").write_text(avd(WM_W / 8, WM_H / 8, WM_W, WM_H, wm, GEN), encoding="utf-8")
    solid = f'<path android:pathData="{LEFT} {RIGHT}" android:fillColor="#FFFFFFFF" android:fillType="evenOdd"/>'
    (DS_DRAWABLE / "vynq_symbol_solid.xml").write_text(avd(SYM_W / 4, SYM_H / 4, SYM_W, SYM_H, solid, GEN), encoding="utf-8")

    # Adaptive launcher icon: 108x108 canvas, 66dp safe zone → symbol ≈ 54 wide.
    s = 54 / SYM_W
    fg = avd_place(avd_symbol_body(), (108 - SYM_W * s) / 2, (108 - SYM_H * s) / 2, s)
    (APP_DRAWABLE / "ic_launcher_foreground.xml").write_text(avd(108, 108, 108, 108, fg, GEN), encoding="utf-8")
    mono = avd_place(solid.replace("#FFFFFFFF", "#FF000000"), (108 - SYM_W * s) / 2, (108 - SYM_H * s) / 2, s)
    (APP_DRAWABLE / "ic_launcher_monochrome.xml").write_text(avd(108, 108, 108, 108, mono, GEN), encoding="utf-8")
    bg = ('<path android:pathData="M0,0h108v108h-108z"><aapt:attr name="android:fillColor">'
          '<gradient android:type="radial" android:centerX="54" android:centerY="50" android:gradientRadius="68">'
          f'<item android:offset="0" android:color="#FF3A2A9E"/><item android:offset="0.55" android:color="#FF0E0E16"/>'
          f'<item android:offset="1" android:color="#FF07070C"/></gradient></aapt:attr></path>')
    (APP_DRAWABLE / "ic_launcher_background.xml").write_text(avd(108, 108, 108, 108, bg, GEN), encoding="utf-8")

    # Notification icon: 24dp white silhouette, 20dp live area.
    ns = 20 / SYM_W
    notif = avd_place(solid, (24 - SYM_W * ns) / 2, (24 - SYM_H * ns) / 2, ns)
    (APP_DRAWABLE / "ic_notification.xml").write_text(avd(24, 24, 24, 24, notif, GEN), encoding="utf-8")

    # Android 12+ splash icon: 288dp canvas, icon must fit a 192dp circle.
    sp = 132 / SYM_W
    splash = avd_place(avd_symbol_body(), (288 - SYM_W * sp) / 2, (288 - SYM_H * sp) / 2, sp)
    (APP_DRAWABLE / "splash_icon.xml").write_text(avd(288, 288, 288, 288, splash, GEN), encoding="utf-8")


if __name__ == "__main__":
    write_svgs()
    write_android()
    print(f"symbol {SYM_W:.0f}x{SYM_H:.0f}, wordmark {WM_W:.0f}x{WM_H:.0f}")
