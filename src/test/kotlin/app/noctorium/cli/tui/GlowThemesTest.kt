package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.LyricsStandIn
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.SettingsSearch
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.contrastRatio
import app.noctorium.settings.onAccent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Glow themes in a terminal: offered with the others by their own names, drawn in their colours without their
 * light, and with a white accent -- Afterglow's, Velvet's and Smoke's -- that still leaves what is chosen plain to
 * see. Bandcamp's stand-in is the only service, and the player only pretends.
 */
class GlowThemesTest {
    private class StandIn : PlaybackEngine {
        private val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable
        override suspend fun play(track: Track) = mutable.update {
            PlaybackState(PlaybackStatus.PLAYING, track, positionMs = 94_000, durationMs = track.durationMs ?: 240_000, volume = .5f)
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

    private val parts = CliParts()
    private val state = BandcampStandIn.state(parts, StandIn())
    private val tui = Tui(state, parts, web = null)

    private val glow = listOf(ThemePreset.AFTERGLOW, ThemePreset.CINDER, ThemePreset.VELVET, ThemePreset.SMOKE)
    private val white = listOf(ThemePreset.AFTERGLOW, ThemePreset.VELVET, ThemePreset.SMOKE)

    @AfterTest
    fun close() {
        tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
        NowPlaying.unscroll()
        state.close()
    }

    private fun palette(theme: ThemePreset, accent: AccentPreset = AccentPreset.THEME) =
        Palette.from(NoctoriumPreferences(theme = theme, accent = accent), null, ownBackground = false)

    private fun argb(rgb: Rgb): Long = 0xFF000000L or rgb.toLong()

    private fun contrast(a: Rgb, b: Rgb) = contrastRatio(argb(a), argb(b))

    private fun theme(theme: ThemePreset) {
        state.setTheme(theme)
        waitFor { state.settings.value.preferences.theme == theme }
    }

    private fun render(w: Int = 150, h: Int = 42): Canvas {
        tui.frame(w, h)
        return tui.frame(w, h)
    }

    private fun lines(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    @Test
    fun `the Glow themes are offered between Solarized and Windows, by their own names, and are in force once chosen`() {
        assertEquals(listOf("Afterglow", "Glow Cinder", "Glow Velvet", "Glow Smoke"), glow.map { Settings.themeName(it) })
        // The names the rest were always offered by stay as they were.
        assertEquals("Noctorium Night", Settings.themeName(ThemePreset.NOCTORIUM_NIGHT))
        assertEquals("Crimson", Settings.themeName(ThemePreset.CRIMSON))
        assertEquals("Solarized Light", Settings.themeName(ThemePreset.SOLARIZED_LIGHT))

        tui.page = Page.SETTINGS
        Settings.rows(tui).filterIsInstance<Row.Action>().first { it.label == "Theme" }.run()
        val picker = assertIs<Overlay.Picker>(tui.overlays.last())
        val names = picker.options.map { it.first }
        val at = names.indexOf("Solarized Light")
        assertEquals(listOf("Solarized Light", "Afterglow", "Glow Cinder", "Glow Velvet", "Glow Smoke", "Windows 98"), names.subList(at, at + 6))
        picker.options.first { it.first == "Glow Velvet" }.second()
        waitFor { state.settings.value.preferences.theme == ThemePreset.VELVET }
        tui.overlays.clear()
        // Its own black page, with nothing drawn for the light.
        val canvas = render()
        assertEquals(0x0A0A0A, canvas.bg[(canvas.height / 2) * canvas.width + canvas.width / 2])
    }

    @Test
    fun `the Glow themes are found in Settings by their names, their family's and by black`() {
        fun found(query: String) = SettingsSearch.search(query, SettingsIndex.ENTRIES).map { it.entry.id }
        for (query in listOf("glow", "afterglow", "cinder", "velvet", "smoke", "dark mode")) assertEquals("theme", found(query).first(), query)
        // "black" is a letter from the start of Background's name, which a typo forgiven finds first; the theme next.
        assertEquals(listOf("background", "theme"), found("black").take(2))
    }

    @Test
    fun `a white accent is told from the writing, and no other is`() {
        ThemePreset.entries.filter { it.colours != null }.forEach { preset ->
            assertEquals(preset in white, palette(preset).accentIsWriting, preset.name)
        }
        // Whatever the theme, an accent chosen in a colour is a colour.
        white.forEach { assertEquals(false, palette(it, AccentPreset.MINT).accentIsWriting, it.name) }
    }

    @Test
    fun `a chosen row stands off the page under a white accent, and what is written on it reads`() {
        white.forEach { preset ->
            val p = palette(preset)
            assertTrue(contrast(p.selection, p.page) >= 1.3, "${preset.name}: the bar is lost in the page")
            assertTrue(contrast(p.text, p.selection) >= 7.0, "${preset.name}: the title on the bar")
            assertTrue(contrast(p.subtext, p.selection) >= 4.5, "${preset.name}: the artist on the bar is ${"%.2f".format(contrast(p.subtext, p.selection))}")
        }
        // Every other theme's bar is the one it always was.
        ThemePreset.entries.filter { it.colours != null && it !in white }.forEach { preset ->
            val p = palette(preset)
            val panel = p.panel.takeIf { it != DEFAULT } ?: if (p.light) 0xFFFFFF else 0
            assertEquals(mix(panel, p.accent, if (p.light) .18f else .26f), p.selection, preset.name)
        }
    }

    @Test
    fun `writing on the accent is core's, and no theme's or accent's reads worse than it did`() {
        // What the terminal wrote on the accent before it took core's colour.
        fun before(accent: Rgb): Rgb = if (luminance(accent) > .55f) 0x111111 else 0xFFFFFF
        val accents = ThemePreset.entries.filter { it.colours != null }.map { palette(it) } +
            AccentPreset.entries.filter { it.argb != null }.map { palette(ThemePreset.NOCTORIUM_NIGHT, it) }
        accents.forEach { p ->
            val then = contrast(before(p.accent), p.accent)
            val now = contrast(p.onAccent, p.accent)
            assertTrue(now >= minOf(then, 4.5), "#%06X: %.2f, was %.2f".format(p.accent, now, then))
            // Core's own colour, but for the one accent its white reads at under 3:1 on: Noctorium's violet.
            if (p.accent != 0xB47CFF) assertEquals((onAccent(argb(p.accent)) and 0xFFFFFF).toInt(), p.onAccent, "#%06X".format(p.accent))
        }
        assertEquals(0x292929, palette(ThemePreset.VELVET).onAccent, "dark on Velvet's white")
        assertEquals(0xFFFFFF, palette(ThemePreset.CINDER).onAccent, "white on Cinder's red")
        assertTrue(contrast(palette(ThemePreset.NOCTORIUM_NIGHT).onAccent, 0xB47CFF) >= 6.0, "Night's violet keeps a dark label")
    }

    @Test
    fun `under a white accent the chosen song is on its bar and the play button's symbol is dark on it`() {
        theme(ThemePreset.VELVET)
        state.play(BandcampStandIn.song, PlaybackOrigin.SEARCH, listOf(BandcampStandIn.song))
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
        tui.page = Page.QUEUE
        val canvas = render()
        val p = tui.palette
        val row = lines(canvas).indexOfFirst { "▶" in it && "Awake" in it }
        assertTrue(row >= 0, lines(canvas).joinToString("\n"))
        val title = lines(canvas)[row].indexOf("Awake")
        assertEquals(p.selection, canvas.bg[row * canvas.width + title], "the playing song is the chosen row, on its bar")
        assertTrue(p.selection != p.page)
        // The play button: the accent's white, its symbol in core's near-black.
        val button = (canvas.height - 4 until canvas.height).flatMap { y -> (0 until canvas.width).map { x -> y * canvas.width + x } }
            .filter { canvas.bg[it] == 0xFFFFFF && canvas.text[it]?.isNotBlank() == true }
        assertTrue(button.isNotEmpty(), lines(canvas).takeLast(4).joinToString("\n"))
        button.forEach { assertEquals(0x292929, canvas.fg[it]) }
    }

    @Test
    fun `a line of big lyrics scrolled to is not taken for the one being sung under a white accent`() {
        theme(ThemePreset.SMOKE)
        LyricsStandIn.set(state, BandcampStandIn.song)
        state.play(BandcampStandIn.song, PlaybackOrigin.SEARCH, listOf(BandcampStandIn.song))
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
        LyricsStandIn.set(state, BandcampStandIn.song)
        state.setProgressBarStyle(ProgressBarStyle.MINIMAL)
        tui.preferences.nowPlaying = TuiNowPlaying.LYRICS
        tui.page = Page.NOW_PLAYING
        render()
        val p = tui.palette
        // The big type's full blocks, between the controls at the top and the player bar, whose volume has some too.
        fun bigType(canvas: Canvas) = canvas.text.indices
            .filter { canvas.text[it] == "█" && it / canvas.width in 6 until canvas.height - 6 }
            .map { canvas.fg[it] }.toSet()
        assertEquals(setOf(p.accent), bigType(render()), "the line being sung")
        NowPlaying.scroll(-2)
        assertEquals(setOf(p.subtext), bigType(render()), "a line scrolled to")
        // Where the accent is a colour, a line scrolled to is in the writing's colour, as it always was.
        theme(ThemePreset.CINDER)
        render()
        assertEquals(setOf(tui.palette.text), bigType(render()))
    }
}
