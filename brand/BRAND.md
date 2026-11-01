# vynq brand guidelines (v1)

**Name:** vynq, always lowercase, including at the start of a sentence. **Tagline:** REAL CONNECTIONS (tracked caps, overline style).

## Symbol
Two egg-shaped lobes lean into each other and cross at their tips: two people coming together, one continuous loop. The glass lens where they overlap is the "connection".

- Geometry lives in `tools/generate.py` (lobe radii, taper, tilt, offset). **Never edit exported SVGs or Android XML by hand.** Change the generator and re-run it.
- Full colour: left lobe electric blue → violet, right lobe pink → purple → violet, overlap lens a lighter glassy violet, soft top gloss, fine white rim.
- Single colour: lobes united, lens knocked out (even-odd). Use it for notification icons, the themed launcher icon, embossing and tiny sizes. Recognisable down to 16 px.
- Clear space: at least 25 % of symbol width on every side. Minimum size: 20 px (full colour), 12 px (single colour).
- Don't: rotate, recolour lobes outside the palette, add outlines or shadows, or put the full-colour symbol on busy photos (use single-white there).

## Wordmark
Custom monoline geometric lowercase (x-height 200, stroke 38, round terminals). It is built from strokes, so it needs no font licence and renders identically on every platform. White on dark, ink `#07070C` on light. Never typeset "vynq" in a substitute font for logo use.

## Colour
See `tokens.json`. Iridescent colours are reserved for the symbol, the single primary action per screen (brand sweep), and brand moments (splash, welcome). Interfaces are otherwise neutral: near-black / graphite in dark mode, warm off-white in light mode.

## Files

| Asset | File |
|---|---|
| Primary symbol | `svg/vynq-symbol.svg` |
| Monochrome symbol | `svg/vynq-symbol-monochrome.svg` |
| Single-colour | `svg/vynq-symbol-single-{white,ink,blue}.svg` |
| Wordmark | `svg/vynq-wordmark-{white,ink}.svg` |
| Lockups | `svg/vynq-logo-horizontal-{dark,light}.svg`, `svg/vynq-logo-stacked-dark.svg` |
| App icons (1024 master) | `svg/app-icon-{dark,light,mono}.svg` → `exports/ios/*.png` |
| Splash | `svg/splash-dark.svg`. Android: `app/src/main/res/drawable/splash_icon.xml` |
| Android launcher | adaptive `ic_launcher_{background,foreground,monochrome}.xml` (vector, all densities) |
| Notification icon | `android/app/src/main/res/drawable/ic_notification.xml` |
| Play Store icon | `exports/android/play-store-icon-512.png` |

## Regenerate
```powershell
python brand/tools/generate.py
cd brand/tools; npm install; node render.mjs
```

Trademark, domain and handle availability for "vynq" is **not verified**. See `docs/05-accounts-and-infrastructure.md`.
