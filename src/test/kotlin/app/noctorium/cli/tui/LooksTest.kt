package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.ThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The terminal's own layouts, drawn into a frame and read back: the player bar's and Now playing's, chosen in
 * Settings and kept in terminal.json. Bandcamp's stand-in is the only service, and the player only pretends.
 */
class LooksTest {
    /** A player that keeps what it was asked to do, a minute and a half into whatever it plays. */
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

    @AfterTest
    fun close() {
        // Never saved here, but put back all the same: the choices live in the test home's terminal.json.
        tui.preferences.playerBar = TuiPlayerBar.FULL
        tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
        state.close()
    }

    private fun playing() {
        state.play(BandcampStandIn.song, PlaybackOrigin.SEARCH, listOf(BandcampStandIn.song))
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
    }

    private fun lines(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    /** What a line of the frame holds right of the sidebar. */
    private fun body(line: String): String = line.substringAfter('│').trim()

    private fun render(w: Int = 150, h: Int = 42): Canvas {
        tui.frame(w, h)
        return tui.frame(w, h)
    }

    private fun click(canvas: Canvas, text: String, row: Int = canvas.height - 1) {
        val at = lines(canvas)[row].indexOf(text)
        assertTrue(at >= 0, "\"$text\" is not on row $row:\n" + lines(canvas).joinToString("\n"))
        tui.press(Input.Mouse(MouseKind.PRESS, at, row))
    }

    @Test
    fun `the compact bar and the taskbar take one row under their edge, and the page has the rest`() {
        playing()
        tui.page = Page.QUEUE
        render()
        assertEquals(42 - 1 - 4 - 3, tui.listView.height, "The full bar is four rows")
        for (bar in listOf(TuiPlayerBar.COMPACT, TuiPlayerBar.TASKBAR)) {
            tui.preferences.playerBar = bar
            val canvas = render()
            assertEquals(42 - 1 - 2 - 3, tui.listView.height, "$bar is two rows")
            val last = lines(canvas).last()
            assertTrue("Awake – Tycho" in last && "1:34" in last && "4:43" in last, last)
        }
    }

    @Test
    fun `the taskbar's start button opens Now playing, and the song's button pauses and plays`() {
        playing()
        tui.preferences.playerBar = TuiPlayerBar.TASKBAR
        tui.page = Page.HOME
        click(render(), "◉ Noctorium")
        assertEquals(Page.NOW_PLAYING, tui.page)

        click(render(), "Awake")
        waitFor { state.playback.value.status == PlaybackStatus.PAUSED }
        click(render(), "Awake")
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
    }

    @Test
    fun `a layout this version does not know is the default, and the rest of the file is kept`() {
        val read = TuiPreferences.read("""{"playerBar":"SOMETHING_LATER","nowPlaying":"ANOTHER","coverArt":false,"keys":{"NEXT":["N"]}}""")
        assertEquals(TuiPlayerBar.FULL, read.playerBar)
        assertEquals(TuiNowPlaying.CLASSIC, read.nowPlaying)
        assertEquals(false, read.coverArt)
        assertEquals(mapOf("NEXT" to listOf("N")), read.keys)
    }

    /** The rows [text] makes in big type of [size], as they would be drawn from the left edge. */
    private fun set(text: String, size: BigType.Size): List<String> {
        val canvas = Canvas(BigType.width(text, size), size.rows)
        canvas.clear(DEFAULT, DEFAULT)
        BigType.draw(canvas, 0, 0, text, size, 0)
        return lines(canvas)
    }

    @Test
    fun `big type sets the title large across the middle, spaces out the artist, and its seek bar seeks`() {
        playing()
        tui.preferences.nowPlaying = TuiNowPlaying.BIG_TYPE
        tui.page = Page.NOW_PLAYING
        val canvas = render()
        val page = lines(canvas)
        val title = set("AWAKE", BigType.Size.LARGE)
        val top = page.indexOfFirst { title[0] in it }
        assertTrue(top >= 0, page.joinToString("\n"))
        title.forEachIndexed { i, row -> assertTrue(row in page[top + i], page[top + i]) }
        assertTrue(page.any { body(it) == "T Y C H O" }, page.joinToString("\n"))

        val bar = page.indexOfFirst { "1:34" in it && "4:43" in it && it.indexOf("1:34") > 22 }
        assertTrue(bar > top, page.joinToString("\n"))
        val from = page[bar].indexOf("1:34") + 6
        val to = page[bar].indexOf("4:43") - 2
        tui.press(Input.Mouse(MouseKind.PRESS, (from + to) / 2, bar))
        waitFor { state.playback.value.positionMs in 120_000L..165_000L }
    }

    @Test
    fun `the cover layout puts the track under the cover, and the lyrics layout the controls over the words`() {
        playing()
        tui.page = Page.NOW_PLAYING
        tui.preferences.nowPlaying = TuiNowPlaying.COVER
        var page = lines(render())
        val title = page.indexOfFirst { body(it) == "Awake" }
        assertTrue(title > 10, page.joinToString("\n"))
        assertEquals("Tycho", body(page[title + 1]))

        tui.preferences.nowPlaying = TuiNowPlaying.LYRICS
        page = lines(render())
        val head = page.indexOfFirst { "|◀" in it && "1:34" in it }
        assertTrue(head in 1..6, page.joinToString("\n"))
        assertTrue(page.subList(1, head).any { "Awake" in it }, page.joinToString("\n"))
    }

    private fun theme(theme: ThemePreset) {
        state.setTheme(theme)
        waitFor { state.settings.value.preferences.theme == theme }
    }

    @Test
    fun `the Windows themes put the player in a title bar whose close button asks before it closes`() {
        theme(ThemePreset.WINDOWS_98)
        tui.page = Page.QUEUE
        val canvas = render()
        // Navy at its left end, running towards 98's lighter blue along it, with the close button at its right.
        assertEquals(W98.TITLE, canvas.bg[0])
        assertEquals(mix(W98.TITLE, W98.TITLE_END, 100 / 149f), canvas.bg[100])
        assertEquals("×", canvas.text[147])
        click(canvas, "×", row = 0)
        val confirm = tui.overlays.last()
        assertTrue(confirm is Overlay.Confirm && confirm.question == "Close Noctorium?")
        assertTrue(tui.running)
        tui.press(Input.Text("y"))
        assertEquals(false, tui.running)
    }

    @Test
    fun `a Windows theme's dialog closes from its close button, and its buttons are pressed with the mouse`() {
        theme(ThemePreset.WINDOWS_XP)
        tui.page = Page.QUEUE
        var answered = 0
        tui.overlays.addLast(Overlay.Confirm("Clear the queue?", "Playback stops too.") { answered++ })
        var canvas = render()
        val title = lines(canvas).indexOfFirst { "Are you sure?" in it }
        click(canvas, "×", row = title)
        assertTrue(tui.overlays.isEmpty())
        assertEquals(0, answered)

        tui.overlays.addLast(Overlay.Confirm("Clear the queue?") { answered++ })
        canvas = render()
        click(canvas, " Yes ", row = lines(canvas).indexOfFirst { " Yes " in it })
        assertTrue(tui.overlays.isEmpty())
        assertEquals(1, answered)

        var saved: String? = null
        tui.overlays.addLast(Overlay.Prompt("Rename", "A new name.", "Night drive") { saved = it })
        canvas = render()
        click(canvas, " OK ", row = lines(canvas).indexOfFirst { " OK " in it })
        assertEquals("Night drive", saved)
    }

    @Test
    fun `a Windows theme's list picks out its row in its own selection, written in white`() {
        playing()
        theme(ThemePreset.WINDOWS_98)
        tui.page = Page.QUEUE
        val canvas = render()
        val row = lines(canvas).indexOfFirst { "Awake" in it && "Tycho" in it && it.indexOf("Awake") > 22 }
        val at = row * canvas.width + lines(canvas)[row].indexOf("Awake")
        assertEquals(W98.SELECTION, canvas.bg[at])
        assertEquals(W98.SELECTION_TEXT, canvas.fg[at])
    }

    @Test
    fun `the Windows themes keep their own page when the terminal's background is asked for, and the others give it up`() {
        fun page(theme: ThemePreset) = Palette.from(NoctoriumPreferences(theme = theme), null, ownBackground = true).page
        assertEquals(0xC0C0C0, page(ThemePreset.WINDOWS_98))
        assertEquals(0xECE9D8, page(ThemePreset.WINDOWS_XP))
        assertEquals(DEFAULT, page(ThemePreset.NOCTORIUM_NIGHT))
        assertEquals(DEFAULT, page(ThemePreset.CATPPUCCIN_LATTE))
    }

    @Test
    fun `big type has every glyph whole in both sizes, and sets what it can`() {
        assertEquals(BigType.LARGE.keys, BigType.SMALL.keys)
        for ((size, glyphs) in listOf(BigType.Size.LARGE to BigType.LARGE, BigType.Size.SMALL to BigType.SMALL)) {
            glyphs.forEach { (char, glyph) ->
                assertEquals(size.dots, glyph.rows.size, "$size '$char' rows")
                assertTrue(glyph.rows.all { it.length == glyph.width }, "$size '$char' widths")
            }
        }
        assertEquals("SENOR CAFE", BigType.fold("Señor Café"))
        assertEquals("ISIK STRASSE", BigType.fold("ışık straße"))
        assertEquals("GLORIA'S - THEME...", BigType.fold("Gloria’s — Theme…"))
        assertEquals(null, BigType.fold("血と骨"))
        assertEquals(null, BigType.fold("Звезда"))

        assertEquals(BigType.Size.LARGE, BigType.fit("Amazing Grace", 100, 10)?.size)
        assertEquals(BigType.Size.SMALL, BigType.fit("Amazing Grace", 60, 3)?.size)
        assertEquals(listOf("BRIDGE OVER", "TROUBLED WATER"), BigType.fit("Bridge over troubled water", 90, 12)?.lines)
        assertEquals(null, BigType.fit("Supercalifragilistic", 30, 10))
    }
}
