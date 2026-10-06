package app.noctorium.cli.web

import app.noctorium.bandcamp.BandcampGenre
import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.core.ProviderFilter
import app.noctorium.domain.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bandcamp as the web player asks for it: its name and genres set from the page, Home's rows chosen by the
 * page's chips -- and none of what opens as a Bandcamp playlist ever sent to YouTube or SoundCloud to be
 * written, whatever a page asks. Bandcamp is a stand-in, so nothing here reaches the network for it.
 */
class BandcampWebTest {
    private val parts = CliParts()
    private val engine = SwitchingEngine(BandcampStandIn.Silent(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val state = BandcampStandIn.state(parts, engine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = WebCommands(state, engine, scope) {}
    private val wire = Wire(state, engine)
    private val song: JsonElement = wireJson.encodeToJsonElement(BandcampStandIn.song.wire())

    @AfterTest
    fun close() {
        scope.cancel()
        state.close()
    }

    private fun run(type: String, vararg fields: Pair<String, JsonElement>): String? =
        commands.run(buildJsonObject { put("type", JsonPrimitive(type)); fields.forEach { (name, value) -> put(name, value) } })

    private fun text(value: String) = JsonPrimitive(value)

    @Test
    fun `nothing that opens as a Bandcamp playlist is written to a service`() {
        state.refreshLibrary(force = true)
        waitFor { state.library.value.playlists.isNotEmpty() }
        val album = text(BandcampStandIn.album.playlistKey)
        assertNotNull(run("renamePlaylist", "key" to album, "title" to text("Mine now")))
        assertNotNull(run("deletePlaylist", "key" to album))
        assertNotNull(run("visibility", "key" to album, "public" to JsonPrimitive(true)))
        assertNotNull(run("removeFromPlaylist", "key" to album, "track" to song))
        assertNotNull(run("addToPlaylist", "key" to text(BandcampStandIn.wishlist.playlistKey), "track" to song))
        // Nor a Bandcamp song into a playlist made on YouTube Music, as if its id were a video's.
        assertEquals(
            "Only YouTube Music tracks can go into a YouTube Music playlist",
            run("createPlaylist", "title" to text("New"), "provider" to text("YOUTUBE_MUSIC"), "track" to song),
        )
        state.openPlaylist(BandcampStandIn.album)
        assertNotNull(run("movePlaylistTrack", "key" to album, "from" to JsonPrimitive(0), "to" to JsonPrimitive(1)))
        assertEquals(listOf(BandcampStandIn.wishlist, BandcampStandIn.album).map { it.title }, state.library.value.playlists.map { it.title })
    }

    @Test
    fun `the genres and the name are set from the page, and a blank name takes the collection out`() {
        assertNull(run("bandcampGenres", "genres" to JsonArray(listOf("ROCK", "JAZZ", "NOT_A_GENRE").map(::JsonPrimitive))))
        assertEquals(listOf(BandcampGenre.ROCK, BandcampGenre.JAZZ), state.settings.value.preferences.bandcampGenres)
        assertEquals("No genres", run("bandcampGenres"))

        state.refreshLibrary(force = true)
        waitFor { state.library.value.playlists.isNotEmpty() }
        assertNull(run("bandcampUsername", "name" to text("")))
        assertEquals("", state.settings.value.preferences.bandcampUsername)
        assertTrue(state.library.value.playlists.none { it.provider == ProviderType.BANDCAMP })
    }

    @Test
    fun `Home sends the rows of the service the chips chose`() {
        waitFor { state.ui.value.homeSections.isNotEmpty() }
        fun sections() = wire.home().jsonObject["sections"]!!.jsonArray
        assertEquals(1, sections().size)
        state.setFilter(ProviderFilter.SOUNDCLOUD)
        assertTrue(sections().isEmpty())
        assertNull(run("filter", "filter" to text("bandcamp")))
        assertEquals(ProviderFilter.BANDCAMP, state.ui.value.providerFilter)
        assertEquals("BANDCAMP", sections().single().jsonObject["provider"].toString().trim('"'))
    }
}
