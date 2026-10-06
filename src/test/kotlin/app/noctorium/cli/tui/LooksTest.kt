package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
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
        state.close()
    }

    private fun playing() {
        state.play(BandcampStandIn.song, PlaybackOrigin.SEARCH, listOf(BandcampStandIn.song))
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
    }

    private fun lines(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

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
        val read = TuiPreferences.read("""{"playerBar":"SOMETHING_LATER","coverArt":false,"keys":{"NEXT":["N"]}}""")
        assertEquals(TuiPlayerBar.FULL, read.playerBar)
        assertEquals(false, read.coverArt)
        assertEquals(mapOf("NEXT" to listOf("N")), read.keys)
    }
}
