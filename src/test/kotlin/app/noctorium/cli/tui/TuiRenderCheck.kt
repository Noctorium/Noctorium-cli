package app.noctorium.cli.tui

import app.noctorium.cli.CliParts
import app.noctorium.cli.cliAppState
import app.noctorium.domain.Artist
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.AudioAnalysis
import app.noctorium.playback.AudioAnalysisKind
import app.noctorium.playback.MpvPlaybackEngine
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.StreamFormat
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.PlayerButton
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.VisualiserStyle
import app.noctorium.settings.showsPlayerButton
import app.noctorium.settings.withPlayerButton
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin
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

    /**
     * A player that plays nothing and says it is a minute and a half in. For the pictures of the new looks it also
     * says what it plays as, and hands the visualiser a frame set by the test: a stand-in for the pictures only --
     * the live check below draws the visualiser from a real mpv measuring real sound.
     */
    private class StandIn : PlaybackEngine {
        val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable
        val frames = MutableStateFlow<AudioAnalysis?>(null)
        override val analysis: StateFlow<AudioAnalysis?> get() = frames
        override val analysisKind: AudioAnalysisKind get() = AudioAnalysisKind.SPECTRUM
        override suspend fun setVolumeBoostPercent(percent: Int) = mutable.update { it.copy(volumeBoostPercent = percent, volumeBoostEnabled = percent > 100) }
        override suspend fun play(track: Track) = mutable.update {
            PlaybackState(
                PlaybackStatus.PLAYING, track, positionMs = 94_000, durationMs = track.durationMs ?: 240_000, volume = .72f,
                volumeBoostPercent = it.volumeBoostPercent, volumeBoostEnabled = it.volumeBoostEnabled,
                format = StreamFormat("opus", 48_000, 2, 133_000),
            )
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

    /**
     * The new looks -- the Toolbar, the Playlist layout with its visualiser and status bar, the volume panel, the
     * settings search and the symbols -- in a dark theme, a light one, the three Windows ones, and 256 colours.
     */
    @Test
    fun `the new looks draw`() {
        val folder = folder ?: return
        folder.mkdirs()
        val parts = CliParts()
        val player = StandIn()
        val state = cliAppState(parts, player)
        val tui = Tui(state, parts, web = null)
        // A spectrum's shape for the pictures: more in the bass, a little less each octave up.
        fun spectrum(t: Double) = AudioAnalysis(
            bands = List(10) { band -> (.86 - band * .045 + .1 * sin(t + band * 1.3)).toFloat().coerceIn(0f, 1f) },
            peaks = listOf(.93f, .88f),
            rms = listOf(.71f, .66f),
        )
        try {
            state.setAutoplay(false)
            state.search("Amazing Grace Judy Collins")
            waitFor(30_000) { !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty() }
            val tracks = state.ui.value.searchResults.tracks
            val chosen = tracks.firstOrNull { "judy" in it.artistLine.lowercase() } ?: tracks.first()
            state.play(chosen, PlaybackOrigin.SEARCH, tracks)
            waitFor(10_000) { state.playback.value.track != null }
            waitFor(10_000) { tui.art.get(chosen.artworkUrl) != null }
            player.frames.value = spectrum(1.0)
            state.setVolumeBoostPercent(400)
            val themes = listOf(
                ThemePreset.NOCTORIUM_NIGHT to "night",
                ThemePreset.CATPPUCCIN_LATTE to "latte",
                ThemePreset.WINDOWS_98 to "98",
                ThemePreset.WINDOWS_98_NOCTORIUM to "noctorium-98",
                ThemePreset.WINDOWS_XP to "xp",
            )
            for ((theme, name) in themes) {
                state.setTheme(theme)
                state.setProgressBarStyle(ProgressBarStyle.SLIDER)
                state.setVisualiser(VisualiserStyle.BARS)
                tui.preferences.playerBar = TuiPlayerBar.TOOLBAR
                tui.page = Page.QUEUE
                write(folder, "toolbar-$name", tui)
                write(folder, "toolbar-$name-narrow", tui, 84, 24)
                tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
                tui.page = Page.NOW_PLAYING
                write(folder, "playlist-$name", tui)
                write(folder, "playlist-$name-narrow", tui, 96, 30)
                state.setVisualiser(VisualiserStyle.LEVEL_METER)
                write(folder, "playlist-$name-meter", tui)
                state.setVisualiser(VisualiserStyle.BARS)
                tui.preferences.playerBar = TuiPlayerBar.FULL
                write(folder, "playlist-$name-full-bar", tui)
                tui.page = Page.QUEUE
                write(folder, "bar-full-boost-$name", tui)
                tui.preferences.playerBar = TuiPlayerBar.TASKBAR
                write(folder, "bar-taskbar-boost-$name", tui)
                tui.preferences.playerBar = TuiPlayerBar.FULL
                tui.overlays.addLast(VolumePanel(booster = true))
                write(folder, "volume-$name", tui)
                tui.overlays.clear()
                tui.page = Page.SETTINGS
                tui.settingsQuery = null
                write(folder, "settings-$name", tui)
                tui.settingsQuery = "dark mode"
                tui.settingsSearchFocused = true
                write(folder, "settings-search-$name", tui)
                SettingsIndex.close(tui)
                tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
            }

            // 256 colours: the Toolbar and the Playlist layout, dark and in Noctorium 98.
            for ((theme, name) in listOf(ThemePreset.NOCTORIUM_NIGHT to "night", ThemePreset.WINDOWS_98_NOCTORIUM to "noctorium-98")) {
                state.setTheme(theme)
                tui.preferences.playerBar = TuiPlayerBar.TOOLBAR
                tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
                tui.page = Page.NOW_PLAYING
                write(folder, "playlist-$name-256", tui, colours256 = true)
                tui.page = Page.QUEUE
                write(folder, "toolbar-$name-256", tui, colours256 = true)
            }
            tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC

            // The symbols, on the toolbar, the full bar and the sidebar.
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            for (glyphs in listOf(TuiGlyphs.ASCII, TuiGlyphs.NERD_FONT)) {
                tui.preferences.glyphs = glyphs
                tui.page = Page.QUEUE
                tui.preferences.playerBar = TuiPlayerBar.TOOLBAR
                write(folder, "glyphs-${glyphs.name.lowercase()}-toolbar", tui)
                tui.preferences.playerBar = TuiPlayerBar.FULL
                write(folder, "glyphs-${glyphs.name.lowercase()}-full", tui)
                tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
                tui.page = Page.NOW_PLAYING
                write(folder, "glyphs-${glyphs.name.lowercase()}-playlist", tui)
                tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
            }
            tui.preferences.glyphs = TuiGlyphs.STANDARD

            // Where the music is out of the booster's reach, and stop after this song on the bars.
            player.mutable.update { it.copy(volumeBoostAvailable = false) }
            waitFor(5_000) { !state.playback.value.volumeBoostAvailable }
            tui.overlays.addLast(VolumePanel(booster = true))
            write(folder, "volume-unavailable", tui)
            tui.overlays.clear()
            player.mutable.update { it.copy(volumeBoostAvailable = true) }
            state.setStopAfterCurrent(true)
            tui.page = Page.QUEUE
            write(folder, "queue-stop-after", tui)
            tui.preferences.playerBar = TuiPlayerBar.TOOLBAR
            write(folder, "toolbar-stop-after", tui)
            state.setStopAfterCurrent(false)
            // The search's bang.
            tui.page = Page.SEARCH
            tui.searchText = "!sc amazing grace"
            state.search(tui.searchText)
            waitFor(20_000) { !state.ui.value.searchLoading }
            write(folder, "search-bang", tui)
        } finally {
            tui.preferences.glyphs = TuiGlyphs.STANDARD
            tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
            tui.preferences.playerBar = TuiPlayerBar.FULL
            state.setVolumeBoostPercent(100)
            state.setAutoplay(true)
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.setProgressBarStyle(ProgressBarStyle.MINIMAL)
            state.setVisualiser(VisualiserStyle.BARS)
            state.close()
        }
    }

    /**
     * Toolbar 2 in a dark theme, a light one and the three Windows ones, wide, a little narrower and narrow; with a
     * sleep timer, a faster speed, the booster and Stop; and in the other symbol sets. Over a queue of made-up songs
     * with Bandcamp's stand-in for the only service, so it needs no network and no account.
     */
    @Test
    fun `Toolbar 2 draws`() {
        val folder = folder ?: return
        folder.mkdirs()
        val parts = CliParts()
        val player = StandIn()
        val state = app.noctorium.cli.BandcampStandIn.state(parts, player)
        val tui = Tui(state, parts, web = null)
        val artist = Artist("lanterns", "The Paper Lanterns", ProviderType.BANDCAMP)
        val songs = listOf("Harbour Lights" to 249_000L, "Salt and Static" to 203_000L, "Night Ferry" to 187_000L).mapIndexed { i, (title, ms) ->
            Track(provider = ProviderType.BANDCAMP, id = "90$i", title = title, artists = listOf(artist), durationMs = ms, sourceUrl = "https://example.invalid/track/$i")
        }
        try {
            state.setAutoplay(false)
            state.play(songs.first(), PlaybackOrigin.SEARCH, songs)
            waitFor(10_000) { state.playback.value.track != null }
            tui.preferences.playerBar = TuiPlayerBar.TOOLBAR_2
            tui.page = Page.QUEUE
            val themes = listOf(
                ThemePreset.NOCTORIUM_NIGHT to "night",
                ThemePreset.CATPPUCCIN_LATTE to "latte",
                ThemePreset.WINDOWS_98 to "98",
                ThemePreset.WINDOWS_98_NOCTORIUM to "noctorium-98",
                ThemePreset.WINDOWS_XP to "xp",
            )
            for ((theme, name) in themes) {
                state.setTheme(theme)
                state.setProgressBarStyle(if (theme == ThemePreset.WINDOWS_98) ProgressBarStyle.CLASSIC else ProgressBarStyle.SLIDER)
                write(folder, "toolbar2-$name", tui)
                write(folder, "toolbar2-$name-92", tui, 92, 24)
                write(folder, "toolbar2-$name-narrow", tui, 84, 24)
            }

            // Under way: a sleep timer, a faster speed and the booster, with Stop asked for -- wide and narrow.
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.startSleepTimer(30)
            state.setPlaybackSpeed(1.25f)
            state.setVolumeBoostPercent(400)
            state.updateDesktop { withPlayerButton(PlayerButton.STOP, true) }
            waitFor(5_000) { state.settings.value.preferences.desktop.showsPlayerButton(PlayerButton.STOP) }
            write(folder, "toolbar2-under-way", tui)
            write(folder, "toolbar2-under-way-narrow", tui, 84, 24)
            state.setTheme(ThemePreset.WINDOWS_XP)
            write(folder, "toolbar2-xp-under-way", tui)
            write(folder, "toolbar2-xp-under-way-narrow", tui, 84, 24)

            // The other symbols.
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            for (glyphs in listOf(TuiGlyphs.ASCII, TuiGlyphs.NERD_FONT)) {
                tui.preferences.glyphs = glyphs
                write(folder, "toolbar2-glyphs-${glyphs.name.lowercase()}", tui)
            }
        } finally {
            tui.preferences.glyphs = TuiGlyphs.STANDARD
            tui.preferences.playerBar = TuiPlayerBar.FULL
            state.close()
        }
    }

    /**
     * Devices with Noctorium Connect on and off, the same switch in Settings and found by its search, and the Playlist
     * layout's Lyrics tab -- playing, scrolled, and with nothing playing -- in a dark theme, a light one and the three
     * Windows ones, wide and narrow. Over made-up songs with made-up lyrics and Bandcamp's stand-in for the only
     * service, so it needs no network and no account.
     */
    @Test
    fun `Connect's switch and the Playlist layout's lyrics draw`() {
        val folder = folder ?: return
        folder.mkdirs()
        val parts = CliParts()
        val player = StandIn()
        val state = app.noctorium.cli.BandcampStandIn.state(parts, player)
        val tui = Tui(state, parts, web = null)
        val artist = Artist("lanterns", "The Paper Lanterns", ProviderType.BANDCAMP)
        val songs = listOf("Harbour Lights" to 249_000L, "Salt and Static" to 203_000L, "Night Ferry" to 187_000L).mapIndexed { i, (title, ms) ->
            Track(provider = ProviderType.BANDCAMP, id = "90$i", title = title, artists = listOf(artist), durationMs = ms, sourceUrl = "https://example.invalid/track/$i")
        }
        val themes = listOf(
            ThemePreset.NOCTORIUM_NIGHT to "night",
            ThemePreset.CATPPUCCIN_LATTE to "latte",
            ThemePreset.WINDOWS_98 to "98",
            ThemePreset.WINDOWS_98_NOCTORIUM to "noctorium-98",
            ThemePreset.WINDOWS_XP to "xp",
        )
        try {
            state.setAutoplay(false)
            app.noctorium.cli.LyricsStandIn.set(state, songs.first())
            state.play(songs.first(), PlaybackOrigin.SEARCH, songs)
            waitFor(10_000) { state.playback.value.track != null }
            player.frames.value = AudioAnalysis(bands = List(10) { band -> (.8f - band * .05f) }, peaks = listOf(.9f, .85f), rms = listOf(.6f, .55f))
            for ((theme, name) in themes) {
                state.setTheme(theme)
                state.setProgressBarStyle(if (theme == ThemePreset.WINDOWS_98) ProgressBarStyle.CLASSIC else ProgressBarStyle.SLIDER)
                tui.page = Page.DEVICES
                state.setConnectEnabled(true)
                waitFor(5_000) { state.settings.value.preferences.connect.enabled }
                write(folder, "devices-on-$name", tui)
                state.setConnectEnabled(false)
                waitFor(5_000) { !state.settings.value.preferences.connect.enabled }
                write(folder, "devices-off-$name", tui)
                write(folder, "devices-off-$name-narrow", tui, 84, 24)

                tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
                tui.page = Page.NOW_PLAYING
                PlaylistLayout.shown = PlaylistLayout.Tab.LYRICS
                write(folder, "playlist-lyrics-$name", tui)
                write(folder, "playlist-lyrics-$name-narrow", tui, 96, 30)
                PlaylistLayout.shown = PlaylistLayout.Tab.QUEUE
                write(folder, "playlist-queue-$name", tui)
                tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
            }

            // Settings: the switch by the account, chosen, and the search finding it.
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            tui.page = Page.SETTINGS
            SettingsIndex.jump(tui, "connect")
            write(folder, "settings-connect-off", tui)
            state.setConnectEnabled(true)
            waitFor(5_000) { state.settings.value.preferences.connect.enabled }
            write(folder, "settings-connect-on", tui)
            state.setTheme(ThemePreset.WINDOWS_XP)
            write(folder, "settings-connect-on-xp", tui)
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            tui.settingsQuery = "turn off connect"
            tui.settingsSearchFocused = true
            write(folder, "settings-search-connect", tui)
            SettingsIndex.close(tui)

            // Under Toolbar 2, whose lyrics button opens the layout on its lyrics; scrolled back to the top in a short
            // terminal, and back to the line being sung.
            tui.preferences.playerBar = TuiPlayerBar.TOOLBAR_2
            tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
            tui.page = Page.NOW_PLAYING
            PlaylistLayout.showLyrics()
            write(folder, "playlist-lyrics-toolbar2", tui)
            repeat(12) { tui.press(Input.Key(Keys.UP)) }
            write(folder, "playlist-lyrics-scrolled-short", tui, 150, 24)
            tui.press(Input.Key(Keys.HOME))
            write(folder, "playlist-lyrics-short", tui, 150, 24)

            // With nothing playing, the tab says what it is for.
            state.clearQueue()
            waitFor(5_000) { state.playback.value.track == null }
            write(folder, "playlist-lyrics-nothing", tui)
            state.setTheme(ThemePreset.WINDOWS_98)
            write(folder, "playlist-lyrics-nothing-98", tui)
        } finally {
            PlaylistLayout.shown = PlaylistLayout.Tab.QUEUE
            tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
            tui.preferences.playerBar = TuiPlayerBar.FULL
            state.close()
        }
    }

    /**
     * Last.fm and Libre.fm in Settings: both waiting to be allowed, with their pages to copy or open again, under a dark
     * theme, a light one and the Windows ones, wide and narrow; Libre.fm signed in beside Last.fm waiting; neither; the
     * link Open again shows where no browser opened; and the search finding them. The sign-ins are made up and in
     * memory, nothing plays, and the page and the clipboard are written down rather than opened and filled, so nothing
     * reaches either service.
     */
    @Test
    fun `the scrobblers' settings draw`() {
        val folder = folder ?: return
        folder.mkdirs()
        val parts = CliParts()
        val bridge = app.noctorium.cli.RecordingBridge()
        fun open(stand: app.noctorium.cli.ScrobblerStandIn, preferences: NoctoriumPreferences = NoctoriumPreferences()): Pair<app.noctorium.core.AppState, Tui> {
            val state = app.noctorium.cli.BandcampStandIn.state(parts, preferences = preferences, system = bridge, scrobblers = stand.scrobblers)
            return state to Tui(state, parts, web = null).also { it.page = Page.SETTINGS }
        }
        val waiting = app.noctorium.cli.ScrobblerStandIn.Sign.WAITING
        val signedIn = app.noctorium.cli.ScrobblerStandIn.Sign.SIGNED_IN

        val (state, tui) = open(app.noctorium.cli.ScrobblerStandIn(lastFm = waiting, libreFm = waiting))
        try {
            waitFor(10_000) { state.settings.value.scrobbling.libreFm.approvalUrl != null && state.settings.value.scrobbling.lastFm.approvalUrl != null }
            val themes = listOf(
                ThemePreset.NOCTORIUM_NIGHT to "night",
                ThemePreset.CATPPUCCIN_LATTE to "latte",
                ThemePreset.WINDOWS_98 to "98",
                ThemePreset.WINDOWS_XP to "xp",
            )
            for ((theme, name) in themes) {
                state.setTheme(theme)
                waitFor(5_000) { state.settings.value.preferences.theme == theme }
                SettingsIndex.jump(tui, "lastfm")
                write(folder, "scrobblers-waiting-$name", tui)
                SettingsIndex.jump(tui, "librefmLink")
                write(folder, "scrobblers-waiting-$name-copy", tui)
                write(folder, "scrobblers-waiting-$name-narrow", tui, 84, 24)
            }
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            // Where no browser opened: the link whole, to open on any device, as Open again shows it then.
            tui.overlays.addLast(Overlay.Link(state.settings.value.scrobbling.libreFm.approvalUrl!!, opened = false))
            write(folder, "scrobblers-link-overlay", tui)
            tui.overlays.clear()
            tui.settingsQuery = "libre"
            tui.settingsSearchFocused = true
            write(folder, "scrobblers-search-libre", tui)
            tui.settingsQuery = "copy link"
            write(folder, "scrobblers-search-copy-link", tui)
            SettingsIndex.close(tui)
        } finally {
            state.close()
        }

        val stand = app.noctorium.cli.ScrobblerStandIn(lastFm = waiting, libreFm = signedIn)
        val (signed, signedTui) = open(stand, NoctoriumPreferences(libreFmUsername = app.noctorium.cli.ScrobblerStandIn.LISTENER))
        try {
            waitFor(10_000) { signed.settings.value.scrobbling.libreFm.status == app.noctorium.settings.ScrobbleConnectionStatus.CONNECTED }
            SettingsIndex.jump(signedTui, "librefm")
            write(folder, "scrobblers-libre-signed-in", signedTui)
            signedTui.press(Input.Key(Keys.ENTER))
            write(folder, "scrobblers-libre-stop", signedTui)
        } finally {
            signed.close()
        }

        val (none, noneTui) = open(app.noctorium.cli.ScrobblerStandIn())
        try {
            Thread.sleep(500)
            SettingsIndex.jump(noneTui, "librefm")
            write(folder, "scrobblers-none", noneTui)
        } finally {
            none.close()
        }
    }

    /**
     * Apple Music, Deezer and Tidal in Settings, each under the beta warning, as Spotify, Bandcamp and VK now are:
     * signed out and signed in, under a dark theme, a light one and the Windows ones, wide and narrow; what Enter opens
     * on each; Tidal over ssh; the search finding them; and their songs, albums and artists badged in a search of them.
     * The services are stand-ins, every sign-in is made up and in memory, and the clipboard and the browser are written
     * down rather than used, so nothing reaches any of the three.
     */
    @Test
    fun `the later services draw`() {
        val folder = folder ?: return
        folder.mkdirs()
        val parts = CliParts()
        val bridge = app.noctorium.cli.RecordingBridge()
        val stand = app.noctorium.cli.LaterServicesStandIn
        fun open(signedIn: Boolean): Pair<app.noctorium.core.AppState, Tui> {
            val state = app.noctorium.cli.BandcampStandIn.state(
                parts,
                more = stand.providers(),
                preferences = if (signedIn) stand.signedInPreferences else NoctoriumPreferences(),
                system = bridge,
                credentials = if (signedIn) stand.signedIn() else app.noctorium.settings.InMemorySecretStore(),
            )
            return state to Tui(state, parts, web = null).also { it.page = Page.SETTINGS }
        }
        val themes = listOf(
            ThemePreset.NOCTORIUM_NIGHT to "night",
            ThemePreset.CATPPUCCIN_LATTE to "latte",
            ThemePreset.WINDOWS_98 to "98",
            ThemePreset.WINDOWS_XP to "xp",
        )

        val (state, tui) = open(signedIn = false)
        try {
            Thread.sleep(500)
            for ((theme, name) in themes) {
                state.setTheme(theme)
                waitFor(5_000) { state.settings.value.preferences.theme == theme }
                SettingsIndex.jump(tui, "spotify")
                write(folder, "later-spotify-bandcamp-$name", tui)
                SettingsIndex.jump(tui, "tidalRedirect")
                write(folder, "later-signed-out-$name", tui)
                write(folder, "later-signed-out-$name-narrow", tui, 84, 30)
            }
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            waitFor(5_000) { state.settings.value.preferences.theme == ThemePreset.NOCTORIUM_NIGHT }
            SettingsIndex.jump(tui, "appleMusic")
            tui.press(Input.Key(Keys.ENTER))
            write(folder, "later-apple-music-notice", tui)
            tui.overlays.clear()
            SettingsIndex.jump(tui, "tidal")
            tui.press(Input.Key(Keys.ENTER))
            write(folder, "later-tidal-app", tui)
            tui.overlays.clear()
            state.configureTidalApplication("made-up-client-id", "")
            waitFor(5_000) { state.settings.value.tidal.configured }
            Settings.overSsh = { true }
            SettingsIndex.jump(tui, "tidal")
            write(folder, "later-tidal-over-ssh", tui)
            tui.press(Input.Key(Keys.ENTER))
            write(folder, "later-tidal-over-ssh-notice", tui)
            tui.overlays.clear()
            Settings.overSsh = { app.noctorium.cli.TerminalBridge.overSsh() }
            tui.settingsQuery = "client id"
            tui.settingsSearchFocused = true
            write(folder, "later-search-client-id", tui)
            tui.settingsQuery = "apple music"
            write(folder, "later-search-apple-music", tui)
            SettingsIndex.close(tui)

            // A search of every service, the three ticked for it, and their songs, albums and artists badged.
            for (provider in listOf(app.noctorium.domain.ProviderType.APPLE_MUSIC, app.noctorium.domain.ProviderType.DEEZER, app.noctorium.domain.ProviderType.TIDAL)) {
                state.setHybridSearchService(provider, true)
            }
            state.search("tycho")
            waitFor(10_000) { !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.size >= 4 }
            tui.searchText = "tycho"
            tui.searchFocused = false
            tui.page = Page.SEARCH
            for ((theme, name) in themes) {
                state.setTheme(theme)
                waitFor(5_000) { state.settings.value.preferences.theme == theme }
                write(folder, "later-search-$name", tui)
            }
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            waitFor(5_000) { state.settings.value.preferences.theme == ThemePreset.NOCTORIUM_NIGHT }
            write(folder, "later-search-night-narrow", tui, 100, 30)
            state.setSearchMode(app.noctorium.core.SearchMode.TIDAL)
            write(folder, "later-search-tidal-chip", tui, 100, 30)
            state.setSearchMode(app.noctorium.core.SearchMode.HYBRID)
        } finally {
            Settings.overSsh = { app.noctorium.cli.TerminalBridge.overSsh() }
            state.close()
        }

        val (signed, signedTui) = open(signedIn = true)
        try {
            waitFor(10_000) { signed.settings.value.tidal.connected && signed.settings.value.appleMusic.ownDeveloperToken }
            for ((theme, name) in themes) {
                signed.setTheme(theme)
                waitFor(5_000) { signed.settings.value.preferences.theme == theme }
                SettingsIndex.jump(signedTui, "tidalRedirect")
                write(folder, "later-signed-in-$name", signedTui)
            }
            signed.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            signed.refreshLibrary(force = true)
            waitFor(10_000) { signed.library.value.playlists.count { it.title == "Evening" } >= 3 }
            signedTui.page = Page.LIBRARY
            write(folder, "later-library", signedTui)
        } finally {
            signed.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            signed.close()
        }
    }

    /**
     * Spotify with nobody signed in, in Settings under the sign-ins: the warning over the box for a public playlist's
     * link, the playlists followed -- one read, one cut at its hundred songs, one not read yet, and one gone private
     * since -- and the import of Spotify's data download, done and failed; the prompts, the menu and the question
     * they open; Keep in library over a pasted link on Search; and the search finding both. Core's own services, with
     * nothing read at launch and no sign-in anywhere: the private one is found missing by core's own Spotify without
     * Spotify being asked, its id being none Spotify could have, and every other playlist is a made-up copy.
     */
    @Test
    fun `Spotify without a sign-in draws`() {
        val folder = folder ?: return
        folder.mkdirs()
        val parts = CliParts()
        val stand = app.noctorium.cli.SpotifyPublicStandIn
        val home = Files.createTempDirectory("noctorium-spotify-render")
        val state = app.noctorium.cli.BandcampStandIn.state(
            parts,
            preferences = NoctoriumPreferences(spotifyPublicPlaylists = listOf(stand.NIGHT_BUS, stand.RAINY_DAYS, stand.NOT_READ, stand.GONE)),
            system = app.noctorium.cli.RecordingBridge(),
            credentials = app.noctorium.settings.InMemorySecretStore(),
            spotifyCopies = stand.copies(),
            playlists = app.noctorium.playlists.LocalPlaylistRepository(home.resolve("playlists.json")),
            ownServices = true,
        )
        val tui = Tui(state, parts, web = null).also { it.page = Page.SETTINGS }
        val themes = listOf(
            ThemePreset.NOCTORIUM_NIGHT to "night",
            ThemePreset.CATPPUCCIN_LATTE to "latte",
            ThemePreset.WINDOWS_98 to "98",
            ThemePreset.WINDOWS_XP to "xp",
        )
        fun theme(theme: ThemePreset) {
            state.setTheme(theme)
            waitFor(5_000) { state.settings.value.preferences.theme == theme }
        }
        try {
            waitFor(5_000) { state.settings.value.spotify.publicPlaylists.size == 4 }
            // Opened, the private one is found missing, and shown as it was last read.
            state.openPlaylist(app.noctorium.domain.Playlist(stand.GONE, "Saturday Morning", app.noctorium.domain.ProviderType.SPOTIFY))
            waitFor(5_000) { state.settings.value.spotify.publicPlaylists.any { it.lastError != null } }
            state.closePlaylist()
            // What adding one already followed says, under the box.
            state.addSpotifyPublicPlaylist(stand.link(stand.NIGHT_BUS))
            waitFor(5_000) { state.settings.value.spotify.publicMessage != null }
            for ((preset, name) in themes) {
                theme(preset)
                SettingsIndex.jump(tui, "spotifyPremium")
                write(folder, "spotify-public-$name", tui)
            }
            theme(ThemePreset.NOCTORIUM_NIGHT)
            write(folder, "spotify-public-night-narrow", tui, 84, 30)
            SettingsIndex.jump(tui, "spotifyImport")
            write(folder, "spotify-import-night", tui)

            // The prompts, the menu and the question.
            SettingsIndex.jump(tui, "spotifyPublic")
            tui.press(Input.Key(Keys.ENTER))
            write(folder, "spotify-public-prompt", tui)
            tui.overlays.clear()
            val all = Settings.rows(tui)
            tui.list(Page.SETTINGS.name).selected = all.indexOfFirst { it is Row.Action && it.label == "Saturday Morning" }
            write(folder, "spotify-public-gone-chosen", tui)
            tui.press(Input.Key(Keys.ENTER))
            write(folder, "spotify-public-menu", tui)
            tui.overlays.clear()
            tui.press(Input.Key(Keys.DELETE))
            write(folder, "spotify-public-remove", tui)
            tui.overlays.clear()
            SettingsIndex.jump(tui, "spotifyImport")
            tui.press(Input.Key(Keys.ENTER))
            write(folder, "spotify-import-prompt", tui)
            tui.overlays.clear()

            // An import done, and one of a file that is not the download.
            val file = home.resolve("my_spotify_data.zip")
            Files.write(file, stand.download())
            state.importSpotifyData(file)
            waitFor(10_000) { state.settings.value.spotify.dataImport.summary != null }
            for ((preset, name) in themes) {
                theme(preset)
                SettingsIndex.jump(tui, "spotifyImport")
                write(folder, "spotify-imported-$name", tui)
            }
            theme(ThemePreset.NOCTORIUM_NIGHT)
            val wrong = home.resolve("Userdata.json")
            Files.writeString(wrong, """{"username":"made-up-listener"}""")
            state.importSpotifyData(wrong)
            waitFor(10_000) { state.settings.value.spotify.dataImport.failed }
            SettingsIndex.jump(tui, "spotifyImport")
            write(folder, "spotify-import-failed-night", tui)
            tui.page = Page.LIBRARY
            write(folder, "spotify-imported-library", tui)

            // The search finding them.
            tui.page = Page.SETTINGS
            tui.settingsQuery = "without premium"
            tui.settingsSearchFocused = true
            write(folder, "spotify-search-without-premium", tui)
            tui.settingsQuery = "my_spotify_data"
            write(folder, "spotify-search-my-spotify-data", tui)
            SettingsIndex.close(tui)

            // Keep in library over a pasted link, which only Spotify's own page could make, so made up here.
            val url = "https://open.spotify.com/playlist/0MadeUpSundayDrive0004"
            tui.pastedLink = {
                app.noctorium.core.LinkState(
                    app.noctorium.core.LinkStatus.DONE,
                    app.noctorium.domain.MusicLink(app.noctorium.domain.ProviderType.SPOTIFY, app.noctorium.domain.LinkKind.PLAYLIST, "0MadeUpSundayDrive0004", url),
                    tracks = stand.rainyDays.tracks,
                    message = "Playing 100 tracks from Spotify. Spotify's public page shows only a playlist's first 100 songs.",
                    canKeepInLibrary = true,
                )
            }
            tui.searchText = url
            tui.searchFocused = false
            tui.page = Page.SEARCH
            for ((preset, name) in themes) {
                theme(preset)
                write(folder, "spotify-keep-$name", tui)
            }
            theme(ThemePreset.NOCTORIUM_NIGHT)
            write(folder, "spotify-keep-night-narrow", tui, 84, 30)
        } finally {
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.close()
            home.toFile().deleteRecursively()
        }
    }

    /**
     * The visualiser drawn from a real mpv measuring real sound, with nothing to hear: mpv plays to its null output
     * a sweep from the bass up over pink noise, made by ffmpeg, and the Playlist layout is drawn as it plays. Needs
     * `-Dnoctorium.live=true` as well, ffmpeg on the path, and mpv where `-Dnoctorium.mpv` says or on the path.
     */
    @Test
    fun `the visualiser draws what mpv measures`() {
        val folder = folder ?: return
        if (System.getProperty("noctorium.live") != "true") return
        val mpv = System.getProperty("noctorium.mpv")?.let(Path::of) ?: app.noctorium.playback.BackendLocator.mpv() ?: return
        val sound = Files.createTempFile("noctorium-sweep", ".ogg")
        val made = ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.08:duration=40",
            "-f", "lavfi", "-i", "aevalsrc=0.5*sin(2*PI*40*pow(2\\,t/4)*t):s=48000:d=40",
            "-filter_complex", "amix=inputs=2:normalize=0,aformat=channel_layouts=stereo", "-c:a", "libvorbis", sound.toString(),
        ).redirectErrorStream(true).start().waitFor()
        check(made == 0) { "ffmpeg could not make the sweep" }
        val parts = CliParts()
        val engine = MpvPlaybackEngine(parts.backend, executable = { mpv }, downloadedFile = { sound }, audioOutput = "null")
        val state = cliAppState(parts, engine)
        val tui = Tui(state, parts, web = null)
        val track = Track(
            provider = app.noctorium.domain.ProviderType.YOUTUBE_MUSIC, id = "sweep", title = "A sweep over pink noise",
            artists = listOf(app.noctorium.domain.Artist("ffmpeg", "ffmpeg", app.noctorium.domain.ProviderType.YOUTUBE_MUSIC)),
            durationMs = 40_000, sourceUrl = "https://music.youtube.com/watch?v=sweep",
        )
        try {
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.setVisualiser(VisualiserStyle.BARS)
            tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
            tui.preferences.playerBar = TuiPlayerBar.TOOLBAR
            tui.page = Page.NOW_PLAYING
            state.play(track, PlaybackOrigin.SEARCH, listOf(track))
            waitFor(15_000) { state.playback.value.status == PlaybackStatus.PLAYING }
            tui.frame(150, 42)
            waitFor(10_000) { tui.analysis?.bands?.isNotEmpty() == true }
            for (second in listOf(3, 9, 15)) {
                waitFor(20_000) { state.playback.value.positionMs >= second * 1_000L }
                write(folder, "live-bars-${second}s", tui)
            }
            state.setVisualiser(VisualiserStyle.LEVEL_METER)
            Thread.sleep(1_500)
            write(folder, "live-meter", tui)
            state.setTheme(ThemePreset.WINDOWS_98)
            state.setVisualiser(VisualiserStyle.BARS)
            Thread.sleep(1_500)
            write(folder, "live-bars-98", tui)
        } finally {
            tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
            tui.preferences.playerBar = TuiPlayerBar.FULL
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.close()
            Files.deleteIfExists(sound)
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
