package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.QueueState
import app.noctorium.settings.AutoplaySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue and autoplay as the web player sees them and asks for them: autoplay's songs in the queue's part,
 * what can be done to them and to the queue as a whole, and the settings for both.
 *
 * As in the terminal's own test (QueuePagesTest), autoplay's songs are handed to the queue directly, under the
 * key core files them by, so core never asks a service for any; and whatever takes the queue to its end turns
 * autoplay off again before core would. Once one of them has joined the queue, the rest no longer follow its last
 * song, and core takes them away whenever it next looks -- which, on a busy machine, can be in the middle of a
 * test; so what is done to them is done to a queue made afresh.
 */
class QueueWebTest {
    private val parts = CliParts()
    private val engine = SwitchingEngine(BandcampStandIn.Silent(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val state = BandcampStandIn.state(parts, engine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = WebCommands(state, engine, scope) {}
    private val wire = Wire(state, engine)

    @AfterTest
    fun close() {
        scope.cancel()
        state.close()
    }

    private fun run(type: String, vararg fields: Pair<String, Any>): String? = commands.run(
        buildJsonObject {
            put("type", JsonPrimitive(type))
            fields.forEach { (name, value) ->
                put(
                    name,
                    when (value) {
                        is JsonElement -> value
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    },
                )
            }
        },
    )

    private fun song(n: Int) = Track(
        provider = ProviderType.BANDCAMP,
        id = "80$n",
        title = "Song $n",
        artists = listOf(BandcampStandIn.artist),
        durationMs = 200_000,
        sourceUrl = "https://tycho.bandcamp.com/track/song-$n",
    )

    private val queued = (1..4).map(::song)
    private val offered = (11..13).map(::song)
    private val queue: QueueState get() = state.queue.state.value
    private val preferences get() = state.settings.value.preferences

    private fun withSuggestions() {
        state.queue.playQueue(queued, 0, PlaybackContext(ProviderType.BANDCAMP, PlaybackOrigin.SEARCH))
        state.queue.setSuggestions("${queued.last().queueKey}|${AutoplaySource.SAME_SERVICE}", offered, "More from Tycho on Bandcamp")
    }

    @Test
    fun `the queue's part carries autoplay's songs, where they come from, and whether next goes anywhere`() {
        withSuggestions()
        val part = wire.queue().jsonObject
        assertEquals(offered.map { it.queueKey }, part["suggestions"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content })
        assertEquals("More from Tycho on Bandcamp", part["suggestionsFrom"]!!.jsonPrimitive.content)
        assertEquals("ready", part["autoplay"]!!.jsonPrimitive.content)
        assertTrue(part["hasNext"]!!.jsonPrimitive.boolean)
        assertFalse(part["continuesElsewhere"]!!.jsonPrimitive.boolean)

        state.setAutoplay(false)
        waitFor { !preferences.autoplay }
        assertEquals("off", wire.queue().jsonObject["autoplay"]!!.jsonPrimitive.content)
    }

    @Test
    fun `autoplay's songs are kept, dropped and played by key, and one that has gone is said to have`() {
        withSuggestions()
        assertNull(run("keepSuggestion", "key" to offered[0].queueKey, "index" to 0))
        assertEquals(queued + offered[0], queue.tracks)
        assertFalse(offered[0] in queue.suggestions)

        withSuggestions()
        // Named by key, the index the page drew it at no longer matters.
        assertNull(run("removeSuggestion", "key" to offered[2].queueKey, "index" to 0))
        assertEquals(offered.take(2), queue.suggestions)
        assertEquals("That song is no longer lined up", run("removeSuggestion", "key" to offered[2].queueKey, "index" to 0))
        assertEquals("That song is no longer lined up", run("keepSuggestion", "index" to 5))
        assertEquals(offered.take(2), queue.suggestions)

        assertNull(run("playSuggestion", "key" to offered[1].queueKey, "index" to 0))
        waitFor { queue.current == offered[1] }
        // The queue now ends on a song core would look for more after; autoplay goes off before it asks.
        state.setAutoplay(false)
        assertEquals(queued + offered.take(2), queue.tracks)
        assertTrue(queue.suggestions.isEmpty())
    }

    @Test
    fun `looking again forgets autoplay's songs, to be found afresh when the queue is nearly over`() {
        withSuggestions()
        assertNull(run("refreshSuggestions"))
        waitFor { queue.suggestions.isEmpty() }
        assertEquals("later", wire.queue().jsonObject["autoplay"]!!.jsonPrimitive.content)
    }

    @Test
    fun `what is next is shuffled and cleared, and the queue saved as a playlist with a name`() {
        withSuggestions()
        assertNull(run("shuffleUpcoming"))
        assertEquals(queued.first(), queue.tracks.first())
        assertEquals(queued.drop(1).toSet(), queue.tracks.drop(1).toSet())

        assertEquals("Give the playlist a name first", run("saveQueue", "title" to "   "))
        assertNull(run("saveQueue", "title" to "Night drive"))
        waitFor { state.library.value.localPlaylists.any { it.title == "Night drive" } }
        val saved = state.library.value.localPlaylists.first { it.title == "Night drive" }
        try {
            assertEquals(queue.tracks, saved.tracks)
        } finally {
            state.deletePlaylist(saved.id)
        }

        // Off first, and a queue of its own with nothing from autoplay: with nothing after the first song, core
        // would otherwise look for more.
        state.setAutoplay(false)
        waitFor { !preferences.autoplay }
        state.queue.playQueue(queued, 0, PlaybackContext(ProviderType.BANDCAMP, PlaybackOrigin.SEARCH))
        assertNull(run("clearUpcoming"))
        assertEquals(listOf(queued.first()), queue.tracks)
        assertFalse(wire.queue().jsonObject["hasNext"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `where autoplay draws from, songs played lately and keeping the queue are set from the page`() {
        val before = wire.settings().jsonObject
        assertEquals("SAME_SERVICE", before["autoplayFrom"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("SAME_SERVICE" to "The same service", "YOUTUBE_MUSIC" to "YouTube Music radio"),
            before["autoplaySources"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["title"]!!.jsonPrimitive.content },
        )
        assertTrue(before["avoidRecent"]!!.jsonPrimitive.boolean)
        assertTrue(before["keepQueue"]!!.jsonPrimitive.boolean)

        assertNull(run("autoplayFrom", "source" to "YOUTUBE_MUSIC"))
        assertEquals("No such choice", run("autoplayFrom", "source" to "SOMEWHERE_ELSE"))
        assertNull(run("avoidRecent", "on" to false))
        assertNull(run("keepQueue", "on" to false))
        assertEquals("On or off?", run("keepQueue"))
        waitFor { preferences.autoplayFrom == AutoplaySource.YOUTUBE_MUSIC && !preferences.autoplayAvoidRecent && !preferences.keepQueue }

        val after = wire.settings().jsonObject
        assertEquals("YOUTUBE_MUSIC", after["autoplayFrom"]!!.jsonPrimitive.content)
        assertFalse(after["avoidRecent"]!!.jsonPrimitive.boolean)
        assertFalse(after["keepQueue"]!!.jsonPrimitive.boolean)
    }
}
