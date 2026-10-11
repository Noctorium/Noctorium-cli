package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.AudioAnalysis
import app.noctorium.playback.AudioAnalysisKind
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.StreamFormat
import app.noctorium.settings.HomePlay
import app.noctorium.settings.IconSet
import app.noctorium.settings.QueueColumn
import app.noctorium.settings.VisualiserStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What came with the desktop-music-player look, as `noctorium web` tells the page and takes from it: the
 * booster's level and where there is one, stop and stop after this song, the stream's format, the queue's length
 * for a status bar, the bang in a search, the new choices, Clear all but this song, a song from Home, and the
 * visualiser's frames from the computer's player. The services are stand-ins and the player plays nothing.
 */
class PlayerChoicesWebTest {
    private val parts = CliParts()
    private val computer = Recording()
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private val browser = BrowserEngine(parts.backend) { null }.also { it.sink = { message -> sent += message; true } }
    private val engine = SwitchingEngine(computer, browser, Output.COMPUTER)
    private val state = BandcampStandIn.state(parts, engine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = WebCommands(state, engine, scope) {}
    private val wire = Wire(state, engine)
    private var web: WebPlayer? = null

    @AfterTest
    fun close() {
        web?.stop()
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

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private val preferences get() = state.settings.value.preferences

    private fun song(n: Int) = Track(
        provider = ProviderType.BANDCAMP,
        id = "90$n",
        title = "Song $n",
        artists = listOf(BandcampStandIn.artist),
        durationMs = 100_000L * n,
        sourceUrl = "https://tycho.bandcamp.com/track/song-$n",
    )

    @Test
    fun `the booster's level and where there is one are in the playback part, and set from the page`() {
        val before = wire.playback().jsonObject
        assertEquals(100, before.getValue("volumeBoostPercent").jsonPrimitive.int)
        assertTrue(before.getValue("volumeBoostAvailable").jsonPrimitive.boolean)
        assertFalse(before.getValue("boost").jsonPrimitive.boolean)

        // The slider lands on tens, and never past the ends.
        assertNull(run("boostPercent", "percent" to 437))
        waitFor { preferences.volumeBoostPercent == 440 }
        waitFor { computer.state.value.volumeBoostPercent == 440 }
        val after = wire.playback().jsonObject
        assertEquals(440, after.getValue("volumeBoostPercent").jsonPrimitive.int)
        assertTrue(after.getValue("boost").jsonPrimitive.boolean)
        assertNull(run("boostPercent", "percent" to 5000))
        waitFor { preferences.volumeBoostPercent == 1000 }
        assertEquals("No level", run("boostPercent"))

        // The button: off, then back to the last level.
        assertNull(run("boost"))
        waitFor { preferences.volumeBoostPercent == 100 }
        assertNull(run("boost"))
        waitFor { preferences.volumeBoostPercent == 1000 }

        // Both players are told, so the level is in force wherever the music goes; the browser tells its page.
        waitFor { browser.state.value.volumeBoostPercent == 1000 }
        waitFor { sent.any { it.string("command") == "boost" && it.getValue("percent").jsonPrimitive.int == 1000 } }
    }

    @Test
    fun `stop goes back to the start, and stop after this song is set, toggled and told`() {
        state.queue.playQueue(listOf(song(1), song(2)), 0, PlaybackContext(ProviderType.BANDCAMP, PlaybackOrigin.SEARCH))
        state.play(song(1), PlaybackOrigin.SEARCH, listOf(song(1), song(2)))
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
        computer.calls.clear()
        assertNull(run("stop"))
        waitFor { "seek 0" in computer.calls }
        assertEquals(listOf("pause", "seek 0"), computer.calls.toList())

        assertFalse(wire.playback().jsonObject.getValue("stopAfterCurrent").jsonPrimitive.boolean)
        assertNull(run("stopAfterCurrent", "on" to true))
        assertTrue(state.stopAfterCurrent.value)
        assertTrue(wire.playback().jsonObject.getValue("stopAfterCurrent").jsonPrimitive.boolean)
        assertNull(run("stopAfterCurrent"))
        assertFalse(state.stopAfterCurrent.value)
    }

    @Test
    fun `the stream's format, the queue's length and what the computer can measure are told`() {
        state.setAutoplay(false)
        waitFor { !preferences.autoplay }
        val songs = listOf(song(1), song(2), song(3))
        state.play(songs[1], PlaybackOrigin.SEARCH, songs)
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
        computer.mutable.update { it.copy(format = StreamFormat("opus", 48_000, 2, 128_000)) }
        waitFor { state.playback.value.format != null }
        val playback = wire.playback().jsonObject
        assertEquals("Opus, stereo, 48 kHz, 128 kbit/s", playback.string("format"))
        assertEquals("SPECTRUM", playback.string("analysisKind"))

        val queue = wire.queue().jsonObject
        assertEquals(600_000, queue.getValue("durationMs").jsonPrimitive.long)
        assertEquals(100_000, queue.getValue("elapsedBeforeMs").jsonPrimitive.long)
    }

    @Test
    fun `a bang in the search is told beside the page's own choice`() {
        assertNull(run("search", "query" to "!bc awake"))
        waitFor { state.ui.value.searchBang != null }
        val search = wire.search().jsonObject
        assertEquals("!bc awake", search.string("query"))
        assertEquals("!bc", search.string("searchBang"))
        assertEquals("BANDCAMP", search.string("activeSearchMode"))
        assertEquals(state.ui.value.searchMode.name, search.string("mode"))

        assertNull(run("search", "query" to "awake"))
        waitFor { state.ui.value.searchBang == null }
        assertNull(wire.search().jsonObject["searchBang"])
        assertEquals(state.ui.value.searchMode.name, wire.search().jsonObject.string("activeSearchMode"))
    }

    @Test
    fun `the new choices are told with every option, and set by name`() {
        val before = wire.settings().jsonObject
        assertEquals("BARS", before.string("visualiser"))
        assertEquals(VisualiserStyle.entries.map { it.name }, before.getValue("visualiserStyles").jsonArray.map { it.jsonObject.string("name") })
        assertEquals(QueueColumn.DEFAULT.map { it.name }, before.getValue("queueColumns").jsonArray.map { it.jsonPrimitive.content })
        val number = before.getValue("allQueueColumns").jsonArray.map { it.jsonObject }.first { it.string("name") == "NUMBER" }
        assertEquals("#", number.string("description"))
        assertTrue(before.getValue("statusBar").jsonPrimitive.boolean)
        assertEquals("STANDARD", before.string("iconSet"))
        assertEquals(IconSet.entries.map { it.name }, before.getValue("iconSets").jsonArray.map { it.jsonObject.string("name") })
        assertEquals("AUTOPLAY", before.string("homePlay"))
        assertEquals(listOf("Autoplay after it", "The songs around it"), before.getValue("homePlays").jsonArray.map { it.jsonObject.string("title") })
        assertTrue("SLIDER" in before.getValue("progressBarStyles").jsonArray.map { it.jsonObject.string("name") })

        assertNull(run("visualiser", "name" to "LEVEL_METER"))
        // An unknown column is dropped, and the title is kept whatever is asked.
        assertNull(run("queueColumns", "columns" to JsonArray(listOf("SERVICE", "ARTIST", "WAVEFORM").map(::JsonPrimitive))))
        assertNull(run("statusBar", "on" to false))
        assertNull(run("iconSet", "name" to "LINE"))
        assertNull(run("homePlay", "name" to "SHELF"))
        assertEquals("No such visualiser", run("visualiser", "name" to "SPARKLES"))
        assertEquals("No such icons", run("iconSet", "name" to "EMOJI"))
        assertEquals("No such choice", run("homePlay", "name" to "SOMEWHERE"))
        assertEquals("On or off?", run("statusBar"))
        assertEquals("No columns", run("queueColumns"))
        waitFor {
            preferences.visualiser == VisualiserStyle.LEVEL_METER && !preferences.statusBar && preferences.iconSet == IconSet.LINE &&
                preferences.homePlay == HomePlay.SHELF && preferences.queueColumns.size == 3
        }

        val after = wire.settings().jsonObject
        assertEquals("LEVEL_METER", after.string("visualiser"))
        assertEquals(listOf("TITLE", "SERVICE", "ARTIST"), after.getValue("queueColumns").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(after.getValue("statusBar").jsonPrimitive.boolean)
        assertEquals("LINE", after.string("iconSet"))
        assertEquals("SHELF", after.string("homePlay"))
    }

    @Test
    fun `clear all but this song leaves the song playing alone`() {
        state.setAutoplay(false)
        waitFor { !preferences.autoplay }
        val songs = (1..4).map(::song)
        state.play(songs[2], PlaybackOrigin.SEARCH, songs)
        waitFor { state.queue.state.value.current == songs[2] }
        assertNull(run("clearAllButCurrent"))
        assertEquals(listOf(songs[2]), state.queue.state.value.tracks)
        assertEquals(songs[2], state.queue.state.value.current)
    }

    @Test
    fun `a song from Home or a search plays the way the listener chose, and from anywhere else as it always did`() {
        val shelf = (1..3).map(::song)
        fun wired(tracks: List<Track>) = JsonArray(tracks.map { wireJson.encodeToJsonElement(it.wire()) })

        // Autoplay after it: the song alone, for autoplay to follow.
        assertNull(run("play", "track" to wireJson.encodeToJsonElement(shelf[0].wire()), "list" to wired(shelf), "origin" to "HOME"))
        waitFor { state.queue.state.value.current?.queueKey == shelf[0].queueKey }
        assertEquals(listOf(shelf[0].queueKey), state.queue.state.value.tracks.map { it.queueKey })

        // Searched for, the same: the song alone.
        assertNull(run("play", "track" to wireJson.encodeToJsonElement(shelf[1].wire()), "list" to wired(shelf), "origin" to "SEARCH"))
        waitFor { state.queue.state.value.current?.queueKey == shelf[1].queueKey }
        assertEquals(listOf(shelf[1].queueKey), state.queue.state.value.tracks.map { it.queueKey })

        // From a playlist, the list comes along as it always did.
        assertNull(run("play", "track" to wireJson.encodeToJsonElement(shelf[0].wire()), "list" to wired(shelf), "origin" to "PLAYLIST"))
        waitFor { state.queue.state.value.current?.queueKey == shelf[0].queueKey && state.queue.state.value.tracks.size == shelf.size }
        assertEquals(shelf.map { it.queueKey }, state.queue.state.value.tracks.map { it.queueKey })

        // The rest of its shelf, the old way.
        assertNull(run("homePlay", "name" to "SHELF"))
        waitFor { preferences.homePlay == HomePlay.SHELF }
        assertNull(run("play", "track" to wireJson.encodeToJsonElement(shelf[2].wire()), "list" to wired(shelf), "origin" to "HOME"))
        waitFor { state.queue.state.value.current?.queueKey == shelf[2].queueKey }
        assertEquals(shelf.map { it.queueKey }, state.queue.state.value.tracks.map { it.queueKey })
        state.setAutoplay(false)
    }

    @Test
    fun `a browser as the speaker is told the level with each song and when it takes over, and says what it hears`() {
        val file = Files.createTempFile("noctorium-song", ".mp3")
        val tab = BrowserEngine(parts.backend) { file }
        val messages = CopyOnWriteArrayList<JsonObject>()
        try {
            runBlocking { tab.setVolumeBoostPercent(300) }
            // No page yet; one that becomes the speaker is told at once.
            tab.sink = { messages += it; true }
            assertEquals(300, messages.single { it.string("command") == "boost" }.getValue("percent").jsonPrimitive.int)

            runBlocking { tab.play(song(1)) }
            val load = messages.last { it.string("command") == "load" }
            assertEquals(300, load.getValue("boostPercent").jsonPrimitive.int)
            assertEquals("MP3", tab.state.value.format?.describe())

            // What the page can tell is put over what was known, for this song only.
            val generation = load.getValue("generation").jsonPrimitive.int
            tab.reportFormat(generation, null, null, 44_100, 2)
            assertEquals("MP3, stereo, 44.1 kHz", tab.state.value.format?.describe())
            tab.reportFormat(generation - 1, "opus", null, null, 1)
            assertEquals("MP3, stereo, 44.1 kHz", tab.state.value.format?.describe())
            assertTrue(tab.state.value.volumeBoostAvailable)

            runBlocking { tab.setVolumeBoost(false) }
            assertEquals(100, tab.state.value.volumeBoostPercent)
            runBlocking { tab.setVolumeBoost(true) }
            assertEquals(300, tab.state.value.volumeBoostPercent)
        } finally {
            tab.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `the speed reaches the computer's player through the switch`() {
        state.setPlaybackSpeed(1.25f)
        waitFor { "speed 1.25" in computer.calls }
    }

    @Test
    fun `a page that shows a visualiser is sent the computer's frames, and the player measures only while one does`() {
        val player = WebPlayer(state, parts, engine).also { web = it }
        assertNull(player.start(preferredPort = 20_000 + (System.nanoTime() % 20_000).toInt(), network = false))
        val port = URI(player.localAddress!!).port
        val received = LinkedBlockingQueue<JsonObject>()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("X-Noctorium-Key", player.key)
            .buildAsync(
                URI("ws://127.0.0.1:$port/api/live"),
                object : WebSocket.Listener {
                    private val buffer = StringBuilder()
                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        buffer.append(data)
                        if (last) {
                            runCatching { Json.parseToJsonElement(buffer.toString()).jsonObject }.getOrNull()?.let(received::add)
                            buffer.setLength(0)
                        }
                        webSocket.request(1)
                        return null
                    }
                },
            ).get(10, TimeUnit.SECONDS)
        fun next(what: (JsonObject) -> Boolean): JsonObject {
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                val message = received.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (what(message)) return message
            }
            error("Nothing like that came in ten seconds")
        }
        fun ask(kind: String) = socket.sendText("""{"kind":"command","id":7,"command":{"type":"analysis","kind":"$kind"}}""", true).get(5, TimeUnit.SECONDS)

        val playback = next { it.string("kind") == "part" && it.string("name") == "playback" }.getValue("data").jsonObject
        assertEquals(100, playback.getValue("volumeBoostPercent").jsonPrimitive.int)
        // Nobody has asked: mpv is not watched, so it measures nothing.
        Thread.sleep(300)
        assertEquals(0, computer.frames.subscriptionCount.value)

        ask("SPECTRUM")
        assertNull(next { it.string("kind") == "reply" && it.getValue("id").jsonPrimitive.int == 7 }["error"])
        waitFor { computer.frames.subscriptionCount.value > 0 }
        computer.frames.value = AudioAnalysis(bands = List(10) { it / 10f }, peaks = listOf(.9f, .8f), rms = listOf(.123456f, .5f))
        val frame = next { it.string("kind") == "analysis" && it.getValue("bands").jsonArray.isNotEmpty() }
        assertEquals(10, frame.getValue("bands").jsonArray.size)
        assertEquals(.123f, frame.getValue("rms").jsonArray[0].jsonPrimitive.float)
        assertEquals(listOf(.9f, .8f), frame.getValue("peaks").jsonArray.map { it.jsonPrimitive.float })

        // Nothing to show is empty lists, not silence on the wire.
        computer.frames.value = null
        val empty = next { it.string("kind") == "analysis" }
        assertTrue(empty.getValue("bands").jsonArray.isEmpty() && empty.getValue("peaks").jsonArray.isEmpty())

        ask("NONE")
        waitFor { computer.frames.subscriptionCount.value == 0 }
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `the switch follows the output for the visualiser, and only while watched`() = runBlocking {
        assertEquals(0, computer.frames.subscriptionCount.value)
        computer.frames.value = AudioAnalysis(peaks = listOf(.5f), rms = listOf(.25f))
        val seen = CopyOnWriteArrayList<AudioAnalysis?>()
        val watching = scope.launch { engine.analysis.collect { seen += it } }
        withTimeout(5_000) { computer.frames.subscriptionCount.first { it > 0 } }
        waitFor { seen.lastOrNull()?.kind == AudioAnalysisKind.LEVELS }
        engine.switchTo(Output.BROWSER)
        waitFor { seen.lastOrNull() == null }
        assertEquals(AudioAnalysisKind.NONE, engine.analysisKind)
        waitFor { computer.frames.subscriptionCount.value == 0 }
        watching.cancel()
        // Back on the computer with nobody watching, it is not watched either.
        engine.switchTo(Output.COMPUTER)
        assertNotNull(computer.frames.value)
        Thread.sleep(200)
        assertEquals(0, computer.frames.subscriptionCount.value)
    }

    /** A player that plays nothing, says what it was asked, and has a visualiser feed to hand out. */
    private class Recording : PlaybackEngine {
        val calls = CopyOnWriteArrayList<String>()
        val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable.asStateFlow()
        val frames = MutableStateFlow<AudioAnalysis?>(null)
        override val analysis: StateFlow<AudioAnalysis?> get() = frames
        override val analysisKind: AudioAnalysisKind get() = AudioAnalysisKind.SPECTRUM

        override suspend fun play(track: Track) {
            calls += "play ${track.title}"
            mutable.update { it.copy(status = PlaybackStatus.PLAYING, track = track, positionMs = 0, durationMs = track.durationMs ?: 0, format = null) }
        }
        override suspend fun pause() {
            calls += "pause"
            mutable.update { if (it.status == PlaybackStatus.PLAYING) it.copy(status = PlaybackStatus.PAUSED) else it }
        }
        override suspend fun resume() { calls += "resume" }
        override suspend fun setVolume(value: Float) { mutable.update { it.copy(volume = value) } }
        override suspend fun setVolumeBoost(enabled: Boolean) { calls += "boost $enabled" }
        override suspend fun setVolumeBoostPercent(percent: Int) {
            calls += "boost $percent"
            mutable.update { it.copy(volumeBoostPercent = percent, volumeBoostEnabled = percent > 100) }
        }
        override suspend fun setMuted(muted: Boolean) { mutable.update { it.copy(isMuted = muted) } }
        override suspend fun seekTo(positionMs: Long) {
            calls += "seek $positionMs"
            mutable.update { it.copy(positionMs = positionMs) }
        }
        override suspend fun stop() {
            calls += "stop"
            mutable.update { it.copy(status = PlaybackStatus.IDLE, track = null) }
        }
        override suspend fun setSpeed(speed: Float) { calls += "speed $speed" }
        override suspend fun setAnalysis(wanted: AudioAnalysisKind) { calls += "analysis $wanted" }
        override fun close() = Unit
    }
}
