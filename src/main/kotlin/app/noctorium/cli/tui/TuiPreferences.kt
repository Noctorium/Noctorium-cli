package app.noctorium.cli.tui

import app.noctorium.settings.AppDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * The choices that only mean something in a terminal, kept beside the program's settings file rather than
 * in it: that file is the same shape every Noctorium reads, and these have no meaning anywhere else.
 */
@Serializable
class TuiPreferences(
    /** Leave the page the terminal's own colour, for a terminal with a background chosen on purpose. */
    var terminalBackground: Boolean = false,
    /** Draw covers in half blocks. Off for a terminal whose font makes them look like noise. */
    var coverArt: Boolean = true,
    /**
     * The keys the listener changed, by action name: only the changes, so the rest keep their defaults and a
     * key added in a later version arrives with its own. See [KeyMap].
     */
    var keys: Map<String, List<String>> = emptyMap(),
    /** How the player bar along the foot of the screen is laid out. */
    var playerBar: TuiPlayerBar = TuiPlayerBar.FULL,
    /** How Now playing is laid out. */
    var nowPlaying: TuiNowPlaying = TuiNowPlaying.CLASSIC,
    /**
     * The symbols the buttons and the pages are drawn with: the terminal's own, as they have always been, plain
     * ASCII, or a Nerd Font's icons. The terminal's answer to core's icon sets, which are drawn shapes a cell
     * cannot hold; see [Glyphs].
     */
    var glyphs: TuiGlyphs = TuiGlyphs.STANDARD,
) {
    fun save() {
        val path = AppDirectories.resolve("terminal.json") ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(serializer(), this))
        }
    }

    companion object {
        /**
         * Forgiving about what it does not know, as core's settings file is: a layout added in a later version
         * and read by an earlier one falls back to that one choice's default, rather than failing the whole file
         * and taking the keys with it.
         */
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true; encodeDefaults = true }

        fun load(): TuiPreferences {
            val path = AppDirectories.resolve("terminal.json")?.takeIf(Files::isRegularFile) ?: return TuiPreferences()
            return runCatching { read(Files.readString(path)) }.getOrDefault(TuiPreferences())
        }

        /** The preferences [text] describes. */
        internal fun read(text: String): TuiPreferences = json.decodeFromString(serializer(), text)
    }
}

/**
 * How the player bar is laid out in a terminal. The desktop's and the phone's bars are chosen apart, in their
 * own settings; a bar made of a few rows of cells wants choices of its own.
 */
@Serializable
enum class TuiPlayerBar(val displayName: String, val description: String) {
    FULL(
        "Full",
        "The cover, the track, the controls over the seek bar, and the volume and what is next.",
    ),
    COMPACT(
        "Compact",
        "One row: play, the track, the seek bar and the times, so the page has the rest of the screen.",
    ),
    TASKBAR(
        "Taskbar",
        "A desktop's taskbar: a start button that opens Now playing, the song as a pressed button, and a tray with the volume and the clock.",
    ),
    TOOLBAR(
        "Toolbar",
        "One row of flat buttons, as a desktop music player has them: add to a playlist, previous, play, pause, stop and next, a slider and the time, then repeat, shuffle and the volume.",
    ),
    /**
     * The Toolbar asked for again with play and pause one button and no add button, and the bar's other buttons
     * after the slider. Which of them it carries is core's player-bar buttons setting, kept as the desktop's bar
     * keeps it: Stop once asked for, and any of the rest put away. See [PlayerBar].
     */
    TOOLBAR_2(
        "Toolbar 2",
        "The Toolbar's flat buttons with play and pause as one, and more of them: like, lyrics and the queue, repeat and shuffle, the sleep timer, Connect, the speed and the volume.",
    ),
}

/** How Now playing is laid out in a terminal; chosen apart from the desktop's and the phone's, as the bar is. */
@Serializable
enum class TuiNowPlaying(val displayName: String, val description: String) {
    CLASSIC(
        "Classic",
        "The cover and the track on the left, and the lyrics beside them, lit line by line.",
    ),
    BIG_TYPE(
        "Big type",
        "No cover: the title in large letters and the artist under it, like a poster.",
    ),
    COVER(
        "Cover",
        "The cover as large as the terminal allows, with the track and the seek bar beneath it.",
    ),
    LYRICS(
        "Lyrics",
        "The lyrics large and centred on the line being sung, under a small cover and the controls.",
    ),
    PLAYLIST(
        "Playlist",
        "The cover in a panel on the left, the queue as a table with columns and the lyrics in a tab beside it, an info bar with the title large and a visualiser, and a status bar.",
    ),
}

/**
 * The symbols the terminal draws its buttons and pages with, chosen in Settings. Core's icon sets are drawn
 * shapes -- rounded, outlined, a thin line -- and a cell holds a character, so the terminal has these instead.
 */
@Serializable
enum class TuiGlyphs(val displayName: String, val description: String) {
    STANDARD(
        "Standard",
        "The symbols the terminal player has always drawn, which every terminal's font has.",
    ),
    ASCII(
        "Plain ASCII",
        "Letters and signs only, for a terminal or a font that shows the symbols as boxes.",
    ),
    NERD_FONT(
        "Nerd Font",
        "A Nerd Font's icons, for a terminal set in one: crisp media buttons and page icons.",
    ),
}
