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
     * Every seek bar, a song of four minutes and nine a minute and a half in, under five themes: a dark one, a
     * light one and the three Windows ones -- each with the rows round it free, and without.
     */
    @Test
    fun `the seek bars draw`() {
        val folder = folder ?: return
        val themes = listOf(ThemePreset.NOCTORIUM_NIGHT, ThemePreset.CATPPUCCIN_LATTE, ThemePreset.WINDOWS_98, ThemePreset.WINDOWS_XP, ThemePreset.WINDOWS_98_NOCTORIUM)
        val styles = ProgressBarStyle.entries
        val columnW = 37
        val canvas = Canvas(maxOf(150, themes.size * columnW + 1), styles.size * 4 + 2)
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

            // The player bar's layouts, in a dark theme and a light one, wide and narrow, over the queue.
            tui.page = Page.QUEUE
            for ((theme, style) in listOf(ThemePreset.NOCTORIUM_NIGHT to ProgressBarStyle.BARS, ThemePreset.CATPPUCCIN_LATTE to ProgressBarStyle.RULER)) {
                state.setTheme(theme)
                state.setProgressBarStyle(style)
                for (bar in TuiPlayerBar.entries) {
                    tui.preferences.playerBar = bar
                    val name = "bar-${bar.name.lowercase()}-${theme.name.lowercase()}"
                    write(folder, name, tui)
                    write(folder, "$name-narrow", tui, 84, 24)
                }
            }
            tui.preferences.playerBar = TuiPlayerBar.FULL

            // Now playing's layouts, in the same two themes, wide and narrow.
            tui.page = Page.NOW_PLAYING
            for ((theme, style) in listOf(ThemePreset.NOCTORIUM_NIGHT to ProgressBarStyle.NEON, ThemePreset.CATPPUCCIN_LATTE to ProgressBarStyle.BEADS)) {
                state.setTheme(theme)
                state.setProgressBarStyle(style)
                for (layout in TuiNowPlaying.entries) {
                    tui.preferences.nowPlaying = layout
                    val name = "now-${layout.name.lowercase()}-${theme.name.lowercase()}"
                    write(folder, name, tui)
                    write(folder, "$name-narrow", tui, 84, 30)
                }
            }
            tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC

            // The three Windows themes, drawn as themselves: the pages, a list, Settings, Now playing, the dialogs,
            // a tooltip and the taskbar, wide and narrow.
            val windows = listOf(
                ThemePreset.WINDOWS_98 to ProgressBarStyle.CLASSIC,
                ThemePreset.WINDOWS_XP to ProgressBarStyle.MATERIAL,
                ThemePreset.WINDOWS_98_NOCTORIUM to ProgressBarStyle.CLASSIC,
            )
            for ((theme, style) in windows) {
                state.setTheme(theme)
                state.setProgressBarStyle(style)
                val name = if (theme == ThemePreset.WINDOWS_98_NOCTORIUM) "noctorium-98" else theme.name.lowercase().removePrefix("windows_")
                for (page in listOf(Page.HOME, Page.SEARCH, Page.QUEUE, Page.NOW_PLAYING)) {
                    tui.page = page
                    write(folder, "$name-${page.name.lowercase().replace('_', '-')}", tui)
                }
                for (layout in listOf(TuiNowPlaying.LYRICS, TuiNowPlaying.BIG_TYPE, TuiNowPlaying.COVER)) {
                    tui.preferences.nowPlaying = layout
                    write(folder, "$name-now-${layout.name.lowercase().replace('_', '-')}", tui)
                }
                tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
                tui.page = Page.SETTINGS
                tui.list(Page.SETTINGS.name).selected = Settings.rows(tui).indexOfFirst { it is Row.Action && it.label == "Seek bar" }
                write(folder, "$name-settings", tui)
                tui.page = Page.QUEUE
                tui.overlays.addLast(Overlay.Confirm("Clear the queue?", "Playback stops too.") {})
                write(folder, "$name-confirm", tui)
                tui.overlays.clear()
                tui.overlays.addLast(Overlays.sleepTimer(tui))
                write(folder, "$name-picker", tui)
                tui.overlays.clear()
                tui.overlays.addLast(Overlay.Prompt("Save the queue", "A name for the playlist. It is kept in Noctorium on this computer.", "Night drive") {})
                write(folder, "$name-prompt", tui)
                tui.overlays.clear()
                tui.overlays.addLast(Overlay.Help)
                write(folder, "$name-help", tui)
                tui.overlays.clear()
                tui.overlays.addLast(Overlay.Checklist("Hybrid search asks", listOf("YouTube Music", "SoundCloud", "Bandcamp", "Spotify", "VK Music"), listOf(0, 2), "Spotify only while its songs play on Spotify") {})
                write(folder, "$name-checklist", tui)
                tui.overlays.clear()
                tui.toast("Theme: ${Settings.themeName(theme)}")
                tui.preferences.playerBar = TuiPlayerBar.TASKBAR
                write(folder, "$name-taskbar", tui)
                write(folder, "$name-taskbar-narrow", tui, 84, 24)
                // With the clock switched off, the tray is only as wide as the volume.
                state.setTaskbarClock(false)
                write(folder, "$name-taskbar-no-clock", tui)
                write(folder, "$name-taskbar-no-clock-narrow", tui, 84, 24)
                state.setTaskbarClock(true)
                tui.preferences.playerBar = TuiPlayerBar.COMPACT
                write(folder, "$name-compact", tui)
                tui.preferences.playerBar = TuiPlayerBar.FULL
                tui.page = Page.HOME
                write(folder, "$name-home-narrow", tui, 90, 30)

                // The theme picker, open over Settings with this theme chosen in it.
                tui.page = Page.SETTINGS
                Settings.rows(tui).filterIsInstance<Row.Action>().first { it.label == "Theme" }.run()
                write(folder, "$name-themes", tui)
                tui.overlays.clear()
            }

            // Noctorium 98 with 98's trackbar for a seek bar, and as a terminal of 256 colours shows it.
            state.setTheme(ThemePreset.WINDOWS_98_NOCTORIUM)
            state.setProgressBarStyle(ProgressBarStyle.MATERIAL)
            tui.page = Page.NOW_PLAYING
            write(folder, "noctorium-98-now-playing-trackbar", tui)
            state.setProgressBarStyle(ProgressBarStyle.CLASSIC)
            for (page in listOf(Page.QUEUE, Page.NOW_PLAYING, Page.SETTINGS)) {
                tui.page = page
                write(folder, "noctorium-98-${page.name.lowercase().replace('_', '-')}-256", tui, colours256 = true)
            }
            tui.preferences.playerBar = TuiPlayerBar.TASKBAR
            tui.page = Page.QUEUE
            write(folder, "noctorium-98-taskbar-256", tui, colours256 = true)
            tui.preferences.playerBar = TuiPlayerBar.FULL
        } finally {
            state.setTaskbarClock(true)
            tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
            tui.preferences.playerBar = TuiPlayerBar.FULL
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.setProgressBarStyle(ProgressBarStyle.MINIMAL)
            state.close()
        }
    }

    private fun waitFor(ms: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(150)
    }

    private fun write(folder: File, name: String, tui: Tui, w: Int = 150, h: Int = 42, colours256: Boolean = false) {
        tui.frame(w, h)
        Thread.sleep(300)
        val frame = tui.frame(w, h)
        Frames.write(folder, name, if (colours256) folded(frame) else frame)
    }

    /** [canvas] as a terminal of only 256 colours shows it: each colour folded into the nearest, as [Screen] sends it. */
    private fun folded(canvas: Canvas): Canvas {
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        fun shown(c: Rgb): Rgb {
            if (c == DEFAULT) return c
            val index = Screen.xterm256(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
            if (index >= 232) return (8 + 10 * (index - 232)) * 0x010101
            val n = index - 16
            return (levels[n / 36] shl 16) or (levels[n / 6 % 6] shl 8) or levels[n % 6]
        }
        val copy = Canvas(canvas.width, canvas.height)
        for (i in canvas.text.indices) {
            copy.text[i] = canvas.text[i]
            copy.style[i] = canvas.style[i]
            copy.fg[i] = shown(canvas.fg[i])
            copy.bg[i] = shown(canvas.bg[i])
        }
        return copy
    }
}
