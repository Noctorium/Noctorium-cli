package app.noctorium.cli.tui

import app.noctorium.cli.CliParts
import app.noctorium.cli.cliAppState
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.test.Test

/**
 * Draws the terminal player's pages to pictures, off any terminal, for looking at. Off unless a folder is
 * named:
 *
 *     ./gradlew :test --tests "*TuiRenderCheck*" -Dnoctorium.renderTui=build/tui
 *
 * Each frame is written as a PNG, as HTML and as text (see [Frames]). The pages search for real and find real
 * lyrics, so they need the network; the player is a stand-in that only pretends to play, so nothing makes a
 * sound. The seek bars need nothing.
 */
class TuiRenderCheck {
    private val folder = System.getProperty("noctorium.renderTui")?.let(::File)

    /** A player that plays nothing and says it is a minute and a half in. */
    private class StandIn : PlaybackEngine {
        private val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable
        override suspend fun play(track: Track) = mutable.update {
            PlaybackState(PlaybackStatus.PLAYING, track, positionMs = 94_000, durationMs = track.durationMs ?: 240_000, volume = .72f)
        }
        override suspend fun pause() = mutable.update { it.copy(status = PlaybackStatus.PAUSED) }
        override suspend fun resume() = mutable.update { it.copy(status = PlaybackStatus.PLAYING) }
        override suspend fun setVolume(value: Float) = mutable.update { it.copy(volume = value) }
        override suspend fun setVolumeBoost(enabled: Boolean) = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun seekTo(positionMs: Long) = mutable.update { it.copy(positionMs = positionMs) }
        override suspend fun stop() = mutable.update { PlaybackState() }
        override fun close() = Unit
    }

    /**
     * Every seek bar, a song of four minutes and nine a minute and a half in, under four themes: a dark one, a
     * light one and the two Windows ones -- each with the rows round it free, and without.
     */
    @Test
    fun `the seek bars draw`() {
        val folder = folder ?: return
        val themes = listOf(ThemePreset.NOCTORIUM_NIGHT, ThemePreset.CATPPUCCIN_LATTE, ThemePreset.WINDOWS_98, ThemePreset.WINDOWS_XP)
        val styles = ProgressBarStyle.entries
        val canvas = Canvas(150, styles.size * 4 + 2)
        val columnW = 37
        canvas.clear(0x202020, 0xDDDDDD)
        themes.forEachIndexed { t, theme ->
            val p = Palette.from(NoctoriumPreferences(theme = theme), null, false)
            val x = t * columnW
            canvas.fill(x, 0, columnW, canvas.height, p.page)
            canvas.write(x + 1, 0, Settings.themeName(theme), p.text, p.page, BOLD)
            styles.forEachIndexed { s, style ->
                val y = 2 + s * 4
                canvas.write(x + 1, y, style.displayName, p.subtext, p.page)
                // With the rows round it free, as on Now playing, then hemmed in, as in a one-row bar.
                SeekBars.draw(canvas, p, style, x + 1, y + 2, 16, 94_000f / 249_000, true, p.page, "yt:abc", 249_000, roomAbove = true, roomBelow = true)
                SeekBars.draw(canvas, p, style, x + 19, y + 1, 16, 94_000f / 249_000, true, p.page, "yt:abc", 249_000)
            }
        }
        Frames.write(folder, "seek-bars", canvas)
    }

    @Test
    fun `the pages draw`() {
        val folder = folder ?: return
        folder.mkdirs()
        val parts = CliParts()
        val state = cliAppState(parts, StandIn())
        val tui = Tui(state, parts, web = null)
        try {
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.setProgressBarStyle(ProgressBarStyle.MINIMAL)
            state.search("Amazing Grace Judy Collins")
            waitFor(30_000) { !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty() }
            tui.searchText = "Amazing Grace Judy Collins"
            tui.page = Page.SEARCH
            write(folder, "search", tui)
            val tracks = state.ui.value.searchResults.tracks
            val chosen = tracks.firstOrNull { "judy" in it.artistLine.lowercase() } ?: tracks.first()
            state.play(chosen, PlaybackOrigin.SEARCH, tracks)
            waitFor(10_000) { state.playback.value.track != null }
            tui.page = Page.NOW_PLAYING
            tui.frame(150, 42)
            state.loadLyrics(chosen)
            waitFor(30_000) { !state.lyrics.value.loading && state.lyrics.value.selectedProvider != null }
            waitFor(10_000) { tui.art.get(chosen.artworkUrl) != null }
            write(folder, "now-playing", tui)
            tui.page = Page.QUEUE
            write(folder, "queue", tui)
            tui.page = Page.SETTINGS
            write(folder, "settings", tui)
            tui.overlays.addLast(Overlay.Help)
            tui.page = Page.HOME
            write(folder, "help", tui)
            tui.overlays.clear()
            state.setTheme(ThemePreset.CRIMSON_SCARLET)
            state.setProgressBarStyle(ProgressBarStyle.WAVE)
            tui.page = Page.NOW_PLAYING
            write(folder, "now-playing-scarlet", tui)
            state.setTheme(ThemePreset.WINDOWS_98)
            state.setProgressBarStyle(ProgressBarStyle.CLASSIC)
            tui.page = Page.SEARCH
            write(folder, "search-98", tui, 100, 32)
        } finally {
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.setProgressBarStyle(ProgressBarStyle.MINIMAL)
            state.close()
        }
    }

    private fun waitFor(ms: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(150)
    }

    private fun write(folder: File, name: String, tui: Tui, w: Int = 150, h: Int = 42) {
        tui.frame(w, h)
        Thread.sleep(300)
        Frames.write(folder, name, tui.frame(w, h))
    }
}
