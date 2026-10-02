package app.noctorium.cli.tui

import app.noctorium.settings.AppDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * The two choices that only mean something in a terminal, kept beside the program's settings file rather
 * than in it: that file is the same shape every Noctorium reads, and these have no meaning anywhere else.
 */
@Serializable
class TuiPreferences(
    /** Leave the page the terminal's own colour, for a terminal with a background chosen on purpose. */
    var terminalBackground: Boolean = false,
    /** Draw covers in half blocks. Off for a terminal whose font makes them look like noise. */
    var coverArt: Boolean = true,
) {
    fun save() {
        val path = AppDirectories.resolve("terminal.json") ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(serializer(), this))
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun load(): TuiPreferences {
            val path = AppDirectories.resolve("terminal.json")?.takeIf(Files::isRegularFile) ?: return TuiPreferences()
            return runCatching { json.decodeFromString(serializer(), Files.readString(path)) }.getOrDefault(TuiPreferences())
        }
    }
}
