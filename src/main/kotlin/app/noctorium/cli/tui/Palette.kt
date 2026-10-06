package app.noctorium.cli.tui

import app.noctorium.settings.AccentPreset
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.resolvedAccent
import app.noctorium.settings.themeColours

/**
 * The theme, in terminal colours.
 *
 * The same nineteen themes the desktop and the phone have, with the same numbers -- Catppuccin is
 * Catppuccin in a terminal too, and Windows 98 is still grey -- and the same accent choice, including
 * "Match the artwork", which here follows the cover drawn in the player bar. [ownBackground] leaves the page
 * the terminal's own colour, for anybody whose terminal has a background they chose on purpose.
 */
data class Palette(
    val page: Rgb,
    val panel: Rgb,
    val card: Rgb,
    val text: Rgb,
    val subtext: Rgb,
    val accent: Rgb,
    val light: Boolean,
) {
    val faint: Rgb get() = mix(subtext, page.takeIf { it != DEFAULT } ?: if (light) 0xFFFFFF else 0, .45f)
    val line: Rgb get() = mix(subtext, panel.takeIf { it != DEFAULT } ?: if (light) 0xFFFFFF else 0, .62f)
    val selection: Rgb get() = mix(panel.takeIf { it != DEFAULT } ?: if (light) 0xFFFFFF else 0, accent, if (light) .18f else .26f)
    val onAccent: Rgb get() = if (luminance(accent) > .55f) 0x111111 else 0xFFFFFF
    val youTube: Rgb get() = 0xFF4E45
    val soundCloud: Rgb get() = 0xFF7A1A
    val spotify: Rgb get() = 0x1ED760
    val bandcamp: Rgb get() = if (light) 0x408294 else 0x629AA9
    val vk: Rgb get() = if (light) 0x0062D1 else 0x4C9BFF
    val good: Rgb get() = if (light) 0x1E8E3E else 0x5FE3B0
    val warn: Rgb get() = if (light) 0xB26A00 else 0xFFC266
    val bad: Rgb get() = if (light) 0xC62828 else 0xFF6B6B

    companion object {
        fun from(preferences: NoctoriumPreferences, artworkAccent: Rgb?, ownBackground: Boolean): Palette {
            val colours = preferences.themeColours()
            fun rgb(argb: Long) = (argb and 0xFFFFFF).toInt()
            val accent = if (preferences.accent == AccentPreset.ARTWORK && artworkAccent != null) {
                artworkAccent
            } else {
                rgb(preferences.resolvedAccent(null))
            }
            return Palette(
                page = if (ownBackground) DEFAULT else rgb(colours.background),
                panel = if (ownBackground) DEFAULT else rgb(colours.panel),
                card = rgb(colours.card),
                text = rgb(colours.text),
                subtext = rgb(colours.subtext),
                accent = accent,
                light = colours.light,
            )
        }
    }
}
