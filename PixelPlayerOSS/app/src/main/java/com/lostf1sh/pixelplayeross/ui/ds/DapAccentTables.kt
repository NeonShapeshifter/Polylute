package com.lostf1sh.pixelplayeross.ui.ds

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

    /** Black: saturation 0.70, at least 7.0:1 on page, panel and raised panel. */
    val Black: Map<DapAccent, Color> = mapOf(
        DapAccent.Sage to Color(0xFF49DF7C),
        DapAccent.Mint to Color(0xFF49DFA4),
        DapAccent.Teal to Color(0xFF49D6DF),
        DapAccent.Sky to Color(0xFF4BB0DF),
        DapAccent.Blue to Color(0xFF73A9E6),
        DapAccent.Indigo to Color(0xFF92A2EC),
        DapAccent.Purple to Color(0xFFB697ED),
        DapAccent.Lilac to Color(0xFFB995EC),
        DapAccent.Pink to Color(0xFFEA87AB),
        DapAccent.Red to Color(0xFFEA8B8B),
        DapAccent.Clay to Color(0xFFE78F78),
        DapAccent.Amber to Color(0xFFDFAE49),
        DapAccent.Sand to Color(0xFFDFA949),
        DapAccent.Lime to Color(0xFFBCDF49),
        DapAccent.Graphite to Color(0xFF9FA7B0),
    )

    /** Gray: saturation 0.46, at least 5.0:1 on page, panel and raised panel. */
    val Gray: Map<DapAccent, Color> = mapOf(
        DapAccent.Sage to Color(0xFF67C788),
        DapAccent.Mint to Color(0xFF64C69F),
        DapAccent.Teal to Color(0xFF69C2C7),
        DapAccent.Sky to Color(0xFF89BCD3),
        DapAccent.Blue to Color(0xFF98B7D9),
        DapAccent.Indigo to Color(0xFFA9B3DF),
        DapAccent.Purple to Color(0xFFBFACE0),
        DapAccent.Lilac to Color(0xFFC1ACE0),
        DapAccent.Pink to Color(0xFFDEA6BA),
        DapAccent.Red to Color(0xFFDEA6A6),
        DapAccent.Clay to Color(0xFFDBAA9D),
        DapAccent.Amber to Color(0xFFCDB177),
        DapAccent.Sand to Color(0xFFCEB07A),
        DapAccent.Lime to Color(0xFFABC25B),
        DapAccent.Graphite to Color(0xFFAFB5BA),
    )

    /** Navy: saturation 0.64, at least 6.0:1 on page, panel and raised panel. */
    val Navy: Map<DapAccent, Color> = mapOf(
        DapAccent.Sage to Color(0xFF47D778),
        DapAccent.Mint to Color(0xFF47D79E),
        DapAccent.Teal to Color(0xFF47CED7),
        DapAccent.Sky to Color(0xFF56B0DA),
        DapAccent.Blue to Color(0xFF79ABE2),
        DapAccent.Indigo to Color(0xFF94A3E7),
        DapAccent.Purple to Color(0xFFB699E9),
        DapAccent.Lilac to Color(0xFFB897E8),
        DapAccent.Pink to Color(0xFFE68CAC),
        DapAccent.Red to Color(0xFFE68F8F),
        DapAccent.Clay to Color(0xFFE2927D),
        DapAccent.Amber to Color(0xFFD7A847),
        DapAccent.Sand to Color(0xFFD7A347),
        DapAccent.Lime to Color(0xFFB5D747),
        DapAccent.Graphite to Color(0xFF95A8BB),
    )

    /** Light: saturation 0.62, at least 5.0:1 on page, panel and raised panel. */
    val Light: Map<DapAccent, Color> = mapOf(
        DapAccent.Sage to Color(0xFF196B35),
        DapAccent.Mint to Color(0xFF196B4B),
        DapAccent.Teal to Color(0xFF1A686D),
        DapAccent.Sky to Color(0xFF1F6586),
        DapAccent.Blue to Color(0xFF2660A0),
        DapAccent.Indigo to Color(0xFF304DCF),
        DapAccent.Purple to Color(0xFF6A30CF),
        DapAccent.Lilac to Color(0xFF7130CF),
        DapAccent.Pink to Color(0xFFAE2959),
        DapAccent.Red to Color(0xFFB22A2A),
        DapAccent.Clay to Color(0xFFA03F26),
        DapAccent.Amber to Color(0xFF775A1C),
        DapAccent.Sand to Color(0xFF7A581D),
        DapAccent.Lime to Color(0xFF536518),
        DapAccent.Graphite to Color(0xFF52606D),
    )
}
