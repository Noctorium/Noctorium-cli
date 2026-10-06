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
}
