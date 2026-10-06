package app.noctorium.cli.tui

import app.noctorium.cli.AutoplayState
import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.QueueState
import app.noctorium.playback.RepeatMode
import app.noctorium.settings.AutoplaySource
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Queue page with autoplay under it, the queue's own keys, the settings for both and the player bar's next,
 * drawn into a frame and read back as text.
 *
 * The queue is made by hand and autoplay's songs handed to it directly. Core asks a service for them itself once
 * the queue is nearly over, a few seconds after it gets there; so a test that takes the queue there either hands
 * the songs over first, under the key core would file them by, or switches autoplay off again well before then.
 * Once one of them has joined the queue, the rest no longer follow its last song, and core takes them away
 * whenever it next looks -- which, on a busy machine, can be in the middle of a test; so what is done to them is
 * done to a queue made afresh.
 * With `-Dnoctorium.renderTui=<folder>` each frame is also written there as text.
 */
class QueuePagesTest {
    private val parts = CliParts()
    private val state = BandcampStandIn.state(parts)
    private val tui = Tui(state, parts, web = null)
    private val folder = System.getProperty("noctorium.renderTui")?.let(::File)?.also { it.mkdirs() }

    @AfterTest
    fun close() {
        // The keys live in the test home's terminal.json, which every other test's player reads.
        tui.useKeys(KeyMap.DEFAULT)
        state.close()
    }

    private fun song(n: Int) = Track(
        provider = ProviderType.BANDCAMP,
        id = "90$n",
        title = "Song $n",
        artists = listOf(BandcampStandIn.artist),
        durationMs = 200_000,
        sourceUrl = "https://tycho.bandcamp.com/track/song-$n",
    )

    private val queued = (1..4).map(::song)
    private val offered = (11..13).map(::song)
    private val from = "More from Tycho on Bandcamp"
    private val context = PlaybackContext(ProviderType.BANDCAMP, PlaybackOrigin.SEARCH)

    /** The key core files autoplay's songs by, after the queue's last song: see AppState.observeSuggestions. */
    private val after = "${queued.last().queueKey}|${AutoplaySource.SAME_SERVICE}"
    private val queue: QueueState get() = state.queue.state.value

    /**
     * The four, the first of them current, with autoplay's three lined up after them -- then, at [at], the one
     * current instead. Handed over before the queue nears its end, so core finds them already there.
     */
    private fun withSuggestions(at: Int = 0) {
        state.queue.playQueue(queued, 0, context)
        state.queue.setSuggestions(after, offered, from)
        if (at != 0) state.queue.jumpTo(at)
        tui.page = Page.QUEUE
    }

    private fun press(vararg inputs: Input) = inputs.forEach { tui.press(it) }

    private fun text(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    private fun render(name: String, h: Int = 42): Canvas {
        tui.frame(150, h)
        val canvas = tui.frame(150, h)
        folder?.let { File(it, "queue-$name.txt").writeText(text(canvas).joinToString("\n")) }
        return canvas
    }

    /** The page's list alone, as drawn. */
    private fun drawn(canvas: Canvas): List<String> {
        val view = tui.listView
        return (view.top until view.top + view.height).map { y ->
            (view.left until view.left + view.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
        }
    }

    /** The colour [word] is drawn in, where it first appears from the top. */
    private fun colourOf(canvas: Canvas, word: String): Int {
        for (y in 0 until canvas.height) {
            val line = StringBuilder()
            val columns = ArrayList<Int>()
            for (x in 0 until canvas.width) (canvas.text[y * canvas.width + x] ?: " ").forEach { line.append(it); columns += x }
            val at = line.indexOf(word)
            if (at >= 0) return canvas.fg[y * canvas.width + columns[at]]
        }
        error("\"$word\" is not drawn:\n" + text(canvas).joinToString("\n"))
    }

    private fun select(which: (Row.Song) -> Boolean) {
        val at = Pages.rows(tui).indexOfFirst { it is Row.Song && which(it) }
        assertTrue(at >= 0, "No such row")
        tui.list(Page.QUEUE.name).selected = at
    }

    /** The line under the Autoplay header, which says what autoplay is doing when it has no songs to show. */
    private fun autoplaySays(name: String): String {
        val page = drawn(render(name))
        val header = page.indexOfFirst { it.trim().startsWith("AUTOPLAY") }
        assertTrue(header >= 0, page.joinToString("\n"))
        return page[header + 1].trim()
    }

    @Test
    fun `autoplay's songs are under the queue, dimmer, with where they come from, and the queue's actions after them`() {
        withSuggestions()
        val canvas = render("autoplay")
        val page = drawn(canvas)
        val header = page.indexOfFirst { it.trim().startsWith("AUTOPLAY · MORE FROM TYCHO ON BANDCAMP") }
        assertTrue(header > page.indexOfFirst { it.trim().startsWith("UP NEXT") }, page.joinToString("\n"))
        assertTrue(page[header].endsWith("3"), page[header])
        offered.forEachIndexed { i, track -> assertTrue(track.title in page[header + 1 + i], page[header + 1 + i]) }
        assertEquals("Enter plays one now · a keeps it · x drops it · R looks again", page[header + 4].trim())

        val actions = page.indexOfFirst { it.trim() == "THE QUEUE" }
        assertTrue(actions > header, page.joinToString("\n"))
        listOf("Shuffle what is next" to "S", "Clear what is next" to "U", "Save the queue as a playlist…" to "N", "Clear the queue" to "C")
            .forEachIndexed { i, (label, key) ->
                val line = page[actions + 1 + i]
                assertTrue(label in line && line.endsWith(" $key"), line)
            }
        val subtitle = text(canvas).joinToString("\n")
        assertTrue("4 tracks · x remove · J K move · S shuffle next · U clear next · N save · C clear" in subtitle, subtitle)

        // Offered rather than chosen: autoplay's titles in the quieter colour, the queue's own in the text colour.
        assertEquals(tui.palette.subtext, colourOf(canvas, "Song 11"))
        assertEquals(tui.palette.text, colourOf(canvas, "Song 2"))
    }

    @Test
    fun `a keeps one of autoplay's songs, x drops one, and Enter plays one with those before it`() {
        withSuggestions()
        select { it.suggestion == 0 }
        press(Input.Text("a"))
        assertEquals(queued + offered[0], queue.tracks)
        assertFalse(offered[0] in queue.suggestions)

        withSuggestions()
        select { it.suggestion == 1 }
        press(Input.Text("x"))
        assertEquals(listOf(offered[0], offered[2]), queue.suggestions)
        assertEquals(queued, queue.tracks)

        // A suggestion keeps autoplay's order: it is not moved like the queue's own.
        select { it.suggestion == 0 }
        press(Input.Text("J"))
        assertTrue("Autoplay's songs keep their order" in text(render("move")).joinToString("\n"))

        // With the one before it, as Enter on any of them does.
        select { it.suggestion == 1 }
        press(Input.Key(Keys.ENTER))
        waitFor { queue.current == offered[2] }
        // The queue now ends on a song core would look for more after; autoplay goes off before it asks.
        state.setAutoplay(false)
        assertEquals(queued + offered[0] + offered[2], queue.tracks)
        assertTrue(queue.suggestions.isEmpty())
    }

    @Test
    fun `the queue's own keys shuffle what is next and save it as a playlist, and clearing what is next asks first`() {
        withSuggestions()
        press(Input.Text("S"))
        assertEquals(queued.first(), queue.tracks.first())
        assertEquals(queued.drop(1).toSet(), queue.tracks.drop(1).toSet())
        assertTrue("Shuffled what is next" in text(render("shuffled")).joinToString("\n"))

        press(Input.Text("N"))
        assertIs<Overlay.Prompt>(tui.overlays.last())
        "Night drive".forEach { press(Input.Text("$it")) }
        press(Input.Key(Keys.ENTER))
        waitFor { state.library.value.localPlaylists.any { it.title == "Night drive" } }
        val saved = state.library.value.localPlaylists.first { it.title == "Night drive" }
        try {
            assertEquals(queue.tracks, saved.tracks)
        } finally {
            state.deletePlaylist(saved.id)
        }

        // Off first: with nothing after the first song, core would otherwise look for more.
        state.setAutoplay(false)
        press(Input.Text("U"))
        val confirm = assertIs<Overlay.Confirm>(tui.overlays.last())
        assertEquals("Clear what is next?", confirm.question)
        assertEquals("The 3 songs after this one leave the queue.", confirm.detail)
        press(Input.Text("y"))
        assertEquals(listOf(queued.first()), queue.tracks)
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun `the queue's keys are the ones Settings › Keys gives them`() {
        withSuggestions()
        tui.useKeys(assertNotNull(KeyMap.DEFAULT.with(KeyAction.SHUFFLE_UPCOMING, "W")))
        val page = text(render("rebound"))
        assertTrue(page.any { "Shuffle what is next" in it && it.endsWith(" W") }, page.joinToString("\n"))
        assertTrue(page.any { "W shuffle next" in it }, page.joinToString("\n"))

        press(Input.Text("S"))
        assertEquals(queued, queue.tracks, "S is nothing on the queue now")
        press(Input.Text("W"))
        assertEquals(queued.drop(1).toSet(), queue.tracks.drop(1).toSet())
        assertTrue("Shuffled what is next" in text(render("rebound-shuffled")).joinToString("\n"))
    }

    @Test
    fun `autoplay says what it is doing when it has no songs to show`() {
        state.queue.playQueue(queued, 0, context)
        tui.page = Page.QUEUE
        assertEquals("When the queue is nearly over, songs like its last one are lined up here.", autoplaySays("later"))

        state.queue.setSuggestions(after, emptyList(), from)
        assertEquals("Nothing lined up. R looks again.", autoplaySays("nothing"))

        state.queue.setSuggestions(after, emptyList(), "Spotify chooses what comes next", continuesElsewhere = true)
        assertEquals("Spotify chooses what comes next, in your Spotify app; next asks it to move on.", autoplaySays("spotify"))

        state.queue.setRepeat(RepeatMode.ALL)
        assertEquals("The queue repeats, so it never runs out and autoplay has nothing to add.", autoplaySays("repeating"))
        state.queue.setRepeat(RepeatMode.OFF)

        state.setAutoplay(false)
        waitFor { !state.settings.value.preferences.autoplay }
        assertEquals("Off: the queue stops when it runs out. Settings › When the queue runs out turns it on.", autoplaySays("off"))

        // The last song, with nothing lined up: core is looking. It waits a few seconds before it asks a
        // service, and autoplay is off again well inside them.
        state.queue.jumpTo(queued.lastIndex)
        state.queue.clearSuggestions()
        state.setAutoplay(true)
        try {
            waitFor { state.settings.value.preferences.autoplay }
            assertEquals("Looking for songs like the last one…", autoplaySays("waiting"))
        } finally {
            state.setAutoplay(false)
        }
    }

    @Test
    fun `what autoplay is doing is decided as the queue says it`() {
        val ready = QueueState(tracks = queued, currentIndex = 3, suggestions = offered, suggestionsSeed = after)
        assertEquals(AutoplayState.READY, AutoplayState.of(ready, autoplay = true))
        assertEquals(AutoplayState.OFF, AutoplayState.of(ready, autoplay = false))
        assertEquals(AutoplayState.REPEATING, AutoplayState.of(ready.copy(repeatMode = RepeatMode.ONE), autoplay = true))
        val none = ready.copy(suggestions = emptyList(), suggestionsSeed = null)
        assertEquals(AutoplayState.WAITING, AutoplayState.of(none, autoplay = true))
        assertEquals(AutoplayState.WAITING, AutoplayState.of(none.copy(currentIndex = 2), autoplay = true))
        assertEquals(AutoplayState.LATER, AutoplayState.of(none.copy(currentIndex = 1), autoplay = true))
        assertEquals(AutoplayState.NOTHING, AutoplayState.of(none.copy(suggestionsSeed = after), autoplay = true))
        assertEquals(AutoplayState.SPOTIFY, AutoplayState.of(none.copy(suggestionsSeed = after, continuesElsewhere = true), autoplay = true))
        assertEquals(AutoplayState.LATER, AutoplayState.of(QueueState(), autoplay = true))
    }

    @Test
    fun `next on the player bar is dim where it would go nowhere`() {
        state.setAutoplay(false)
        waitFor { !state.settings.value.preferences.autoplay }
        state.queue.playQueue(queued, queued.lastIndex, context)
        tui.page = Page.QUEUE
        val canvas = render("bar-end")
        assertFalse(queue.hasNext)
        assertEquals(tui.palette.faint, colourOf(canvas, "▶|"))
        // Nothing has played yet: the bar offers the queue's song rather than saying nothing is playing.
        val bar = text(canvas).takeLast(4).joinToString("\n")
        assertTrue("Song 4" in bar && "Ready in the queue · Space plays it" in bar, bar)
    }

    @Test
    fun `at the queue's end next goes on to autoplay's first, and the bar says so`() {
        withSuggestions(at = queued.lastIndex)
        val canvas = render("bar-auto")
        assertTrue(queue.hasNext)
        assertEquals(tui.palette.text, colourOf(canvas, "▶|"))
        val bar = text(canvas).takeLast(4)
        assertTrue(bar.any { it.contains(Regex("auto +Song 11")) }, bar.joinToString("\n"))
    }

    @Test
    fun `Settings has where autoplay draws from, whether it skips songs played lately, and keeping the queue`() {
        tui.page = Page.SETTINGS
        fun row(page: List<String>, label: String) = page.firstOrNull { it.trim().startsWith(label) || it.trim().startsWith("› $label") } ?: ""
        val page = drawn(render("settings", h = 80))
        assertTrue("The same service" in row(page, "Autoplay draws from"), page.joinToString("\n"))
        assertTrue(row(page, "Autoplay skips songs played lately").endsWith("On"), row(page, "Autoplay skips songs played lately"))
        assertTrue("On: it is back where it was left" in row(page, "Keep the queue between launches"), row(page, "Keep the queue between launches"))

        fun step(label: String) = assertNotNull(Settings.rows(tui).filterIsInstance<Row.Action>().first { it.label == label }.step).invoke(1)
        step("Autoplay draws from")
        step("Autoplay skips songs played lately")
        step("Keep the queue between launches")
        waitFor {
            val p = state.settings.value.preferences
            p.autoplayFrom == AutoplaySource.YOUTUBE_MUSIC && !p.autoplayAvoidRecent && !p.keepQueue
        }
        val changed = drawn(render("settings-changed", h = 80))
        assertTrue("YouTube Music radio" in row(changed, "Autoplay draws from"), changed.joinToString("\n"))
        assertTrue(row(changed, "Autoplay skips songs played lately").endsWith("Off"), row(changed, "Autoplay skips songs played lately"))
        assertTrue(row(changed, "Keep the queue between launches").endsWith("Off"), row(changed, "Keep the queue between launches"))
    }
}
