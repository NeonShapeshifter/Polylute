#!/usr/bin/env python3
"""Computes the per-theme accent tables (B40-F1) and prints them as the Kotlin the app carries.

Every accent keeps its hue (taken from the colour the app has always shipped for that name); each theme sets how saturated and how bright
its accents are, and the script moves the lightness, hue and saturation held, until the accent reads at WCAG >= MIN_RATIO against all three
surfaces of the theme (page, panel, raised panel) AND the ink drawn on a filled accent control reads on the accent at >= 4.5:1.

Graphite is not computed: its accents are the shipped colours, unchanged (every install has drawn them since the picker existed).

Run: python3 tools/palette/accent_tables.py            -> prints the table (Kotlin) and the figures
     python3 tools/palette/accent_tables.py --check    -> exits 1 if the printed table differs from the one in DapAccentTables.kt
"""
import colorsys, sys, re, pathlib

# Shipped colours = the Graphite table (also the identity of each accent).
SHIPPED = {
    "Sage": 0x93B9A0, "Mint": 0x8FC2AE, "Teal": 0x8BBCBF, "Sky": 0x8FB3C4, "Blue": 0x94AECB, "Indigo": 0xA0A8CC,
    "Purple": 0xB4A8C9, "Lilac": 0xB9AEC9, "Pink": 0xC9A8B4, "Red": 0xC99A9A, "Clay": 0xC49A8F, "Amber": 0xC7B183,
    "Sand": 0xC9B48F, "Lime": 0xB4C08C, "Graphite": 0xA8B0B8,
}

# surfaces: page, panel, raised panel ; ink on a filled control
THEMES = {
    # each theme: how pure (sat) and where the search starts; the search then walks the lightness (dir) to the first value that clears min_ratio
    "Black":  dict(surfaces=(0x000000, 0x0B0C0E, 0x191B1E), ink=0x000000, sat=0.70, light=0.58, min_ratio=7.0, dir=+1, grey_sat=0.10),
    "Gray":   dict(surfaces=(0x2A2C30, 0x33363B, 0x3D4046), ink=0x16171A, sat=0.46, light=0.56, min_ratio=5.0, dir=+1, grey_sat=0.07),
    "Navy":   dict(surfaces=(0x0A1424, 0x101C33, 0x182845), ink=0x070D18, sat=0.64, light=0.56, min_ratio=6.0, dir=+1, grey_sat=0.22),
    "Light":  dict(surfaces=(0xF7F7F8, 0xEFEFF1, 0xE3E3E6), ink=0xFAFAFB, sat=0.62, light=0.50, min_ratio=5.0, dir=-1, grey_sat=0.14),
}
INK_MIN = 4.5


def lin(c):
    c /= 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hls_of(rgb):
    r, g, b = ((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255
    return colorsys.rgb_to_hls(r, g, b)


def rgb_of(h, l, s):
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return (round(r * 255) << 16) | (round(g * 255) << 8) | round(b * 255)


def fit(name, theme):
    cfg = THEMES[theme]
    hue = hls_of(SHIPPED[name])[0]
    sat = cfg["sat"]
    # the achromatic accent keeps its cool grey: low saturation, a little different on every theme
    if name == "Graphite":
        sat = cfg["grey_sat"]
    l = cfg["light"]
    step = 0.004 * cfg["dir"]
    for _ in range(400):
        c = rgb_of(hue, l, sat)
        ok = all(ratio(c, s) >= cfg["min_ratio"] for s in cfg["surfaces"]) and ratio(c, cfg["ink"]) >= INK_MIN
        if ok:
            return c
        l += step
        if not 0.02 < l < 0.98:
            break
    raise SystemExit(f"cannot fit {name} on {theme}")


def table():
    out = {}
    for theme in THEMES:
        out[theme] = {n: fit(n, theme) for n in SHIPPED}
    return out


TARGET = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/java/com/lostf1sh/pixelplayeross/ui/ds/DapAccentTables.kt"

HEADER = """package com.lostf1sh.pixelplayeross.ui.ds

import androidx.compose.ui.graphics.Color

/**
 * B40-F1: the accent set of each theme.
 *
 * Every accent keeps its name, its storage key and its hue; each theme sets how pure and how bright its colours are, so a pastel that reads on
 * graphite is not asked to read on white, and black (an OLED page) gets purer, brighter ones. **Generated** by `tools/palette/accent_tables.py`,
 * which moves each colour's lightness to the first value that clears the theme's contrast floor against all three of its surfaces (page, panel,
 * raised panel) and keeps the ink of a filled control readable on it; `python3 tools/palette/accent_tables.py --check` fails if this file is edited
 * by hand. `DapAccentTablesTest` pins WCAG AA (4.5:1) for every accent of every theme on every surface, from the palettes themselves.
 *
 * Graphite is the exception: its accents are the colours the app has always shipped ([DapAccent.color]), unchanged, since every install has
 * drawn them since the picker existed.
 */
object DapAccentTables {
    /** The shipped colours: what graphite draws, and each accent's identity. */
    val Graphite: Map<DapAccent, Color> = DapAccent.entries.associateWith { it.color }

"""


def kotlin(tab):
    lines = []
    for theme, row in tab.items():
        cfg = THEMES[theme]
        lines.append(f"    /** {theme}: saturation {cfg['sat']:.2f}, at least {cfg['min_ratio']:.1f}:1 on page, panel and raised panel. */")
        lines.append(f"    val {theme}: Map<DapAccent, Color> = mapOf(")
        for n, c in row.items():
            lines.append(f"        DapAccent.{n} to Color(0xFF{c:06X}),")
        lines.append("    )")
        lines.append("")
    return HEADER + "\n".join(lines).rstrip("\n") + "\n}\n"


def figures(tab):
    rows = []
    for theme, row in tab.items():
        cfg = THEMES[theme]
        worst = min(ratio(c, s) for c in row.values() for s in cfg["surfaces"])
        worst_ink = min(ratio(c, cfg["ink"]) for c in row.values())
        rows.append((theme, worst, worst_ink))
    return rows


if __name__ == "__main__":
    tab = table()
    if "--figures" in sys.argv:
        for theme, row in tab.items():
            cfg = THEMES[theme]
            print(theme)
            for n, c in row.items():
                print(f"  {n:9s} #{c:06X}  page {ratio(c, cfg['surfaces'][0]):5.2f}  panel {ratio(c, cfg['surfaces'][1]):5.2f}  raised {ratio(c, cfg['surfaces'][2]):5.2f}  ink {ratio(c, cfg['ink']):5.2f}")
        for theme, w, wi in figures(tab):
            print(f"worst {theme}: surfaces {w:.2f}, ink {wi:.2f}")
    elif "--check" in sys.argv:
        if TARGET.read_text(encoding="utf-8") != kotlin(tab):
            print("DapAccentTables.kt is not what accent_tables.py generates", file=sys.stderr)
            sys.exit(1)
        print("DapAccentTables.kt matches")
    else:
        TARGET.write_text(kotlin(tab), encoding="utf-8")
        print("wrote", TARGET)
