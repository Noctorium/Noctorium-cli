package app.noctorium.cli.tui

import app.noctorium.cli.CliParts
import app.noctorium.cli.cliAppState
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The player's keys, fed in without a terminal and checked against what they should have changed. */
class TuiKeysTest {
    private class Silent : PlaybackEngine {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override suspend fun play(track: Track) = Unit
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun setVolume(value: Float) = Unit
        override suspend fun setVolumeBoost(enabled: Boolean) = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun stop() = Unit
        override fun close() = Unit
    }

    private val parts = CliParts()
    private val state = cliAppState(parts, Silent())
    private val tui = Tui(state, parts, web = null)

    @AfterTest
    fun close() = state.close()

    private fun press(vararg inputs: Input) = inputs.forEach { tui.press(it) }
    private fun type(text: String) = text.forEach { press(Input.Text(it.toString())) }

    @Test
    fun `numbers go to the pages, and tab goes round them`() {
        type("3")
        assertEquals(Page.LIBRARY, tui.page)
        type("8")
        assertEquals(Page.SETTINGS, tui.page)
        press(Input.Key(Keys.TAB))
        assertEquals(Page.HOME, tui.page)
    }

    @Test
    fun `slash opens the search box and what is typed goes into it, not to the keys`() {
        type("/")
        assertEquals(Page.SEARCH, tui.page)
        assertTrue(tui.searchFocused)
        type("q3s")
        assertEquals("q3s", tui.searchText)
        assertTrue(tui.running, "q typed into the search box must not quit")
        press(Input.Key(Keys.BACKSPACE))
        assertEquals("q3", tui.searchText)
        press(Input.Key(Keys.ESCAPE))
        assertFalse(tui.searchFocused)
    }

    @Test
    fun `the help opens on a question mark and any key puts it away`() {
        type("?")
        assertTrue(tui.overlays.lastOrNull() is Overlay.Help)
        type("x")
        assertTrue(tui.overlays.isEmpty())
    }

    @Test
    fun `q quits, and so does control c`() {
        type("q")
        assertFalse(tui.running)
    }

    @Test
    fun `a frame draws at any size without falling over`() {
        for ((w, h) in listOf(40 to 12, 80 to 24, 100 to 30, 200 to 60)) {
            Page.entries.forEach { page ->
                tui.page = page
                val canvas = tui.frame(w, h)
                assertEquals(w * h, canvas.text.size)
            }
        }
    }
}
