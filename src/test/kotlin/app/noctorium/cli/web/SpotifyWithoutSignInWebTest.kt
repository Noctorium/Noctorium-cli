package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.SpotifyPublicStandIn
import app.noctorium.core.LinkState
import app.noctorium.core.LinkStatus
import app.noctorium.domain.LinkKind
import app.noctorium.domain.MusicLink
import app.noctorium.domain.ProviderType
import app.noctorium.playlists.LocalPlaylistRepository
import app.noctorium.playlists.PlaylistImport
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.spotify.SPOTIFY_DATA_EXPORT_HOW
import app.noctorium.spotify.SPOTIFY_DATA_EXPORT_LIMITS
import app.noctorium.spotify.SPOTIFY_LIKED_SONGS_TITLE
import app.noctorium.spotify.SPOTIFY_PUBLIC_PLAYLIST_LIMITS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spotify with nobody signed in, as the web player is told of it and asks for it: the followed public playlists and
 * core's words for what they can do, the commands that add and take out one, Spotify's data download sent as an
 * upload and turned away when it is far too large to be that, and the pasted link that can be kept. Nothing reaches
 * Spotify: the followed playlists are made-up copies, and every link added is one core turns away unread.
 */
class SpotifyWithoutSignInWebTest {
    private val parts = CliParts()
    private val engine = SwitchingEngine(BandcampStandIn.Silent(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val folder = Files.createTempDirectory("noctorium-spotify-web")
    private val state = BandcampStandIn.state(
        parts, engine,
        preferences = NoctoriumPreferences(spotifyPublicPlaylists = listOf(SpotifyPublicStandIn.NIGHT_BUS, SpotifyPublicStandIn.RAINY_DAYS, SpotifyPublicStandIn.NOT_READ)),
        spotifyCopies = SpotifyPublicStandIn.copies(),
        playlists = LocalPlaylistRepository(folder.resolve("playlists.json")),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = WebCommands(state, engine, scope) {}
    private val wire = Wire(state, engine)

    @AfterTest
    fun close() {
        scope.cancel()
        state.close()
        folder.toFile().deleteRecursively()
    }

    private fun run(type: String, vararg fields: Pair<String, String>): String? = commands.run(
        buildJsonObject {
            put("type", JsonPrimitive(type))
            fields.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        },
    )

    private fun spotify(): JsonObject = wire.settings().jsonObject["spotify"]!!.jsonObject
    private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.content
    private fun JsonObject.flag(name: String) = this[name]!!.jsonPrimitive.boolean

    private fun waitForPublic() = waitFor { spotify()["publicPlaylists"]!!.jsonArray.size == 3 }

    @Test
    fun `the page is told of the playlists followed, each as last read, and given core's words to show as they are`() {
        waitForPublic()
        val spotify = spotify()
        val listed = spotify["publicPlaylists"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(SpotifyPublicStandIn.NIGHT_BUS, SpotifyPublicStandIn.RAINY_DAYS, SpotifyPublicStandIn.NOT_READ), listed.map { it.text("id") })
        val (night, rainy, unread) = listed
        assertEquals("Night Bus Home", night.text("title"))
        assertEquals(SpotifyPublicStandIn.OWNER, night.text("owner"))
        assertEquals(12, night["songCount"]!!.jsonPrimitive.int)
        assertFalse(night.flag("mayBeLonger"))
        assertEquals("https://open.spotify.com/playlist/${SpotifyPublicStandIn.NIGHT_BUS}", night.text("url"))
        assertTrue(rainy.flag("mayBeLonger"))
        assertEquals(100, rainy["songCount"]!!.jsonPrimitive.int)
        // Not read yet: no name, no count, nothing gone wrong.
        assertEquals("", unread.text("title"))
        assertNull(unread["songCount"])
        assertNull(unread["lastError"])

        assertFalse(spotify.flag("addingPublicPlaylist"))
        assertNull(spotify["publicMessage"])
        assertEquals(SPOTIFY_PUBLIC_PLAYLIST_LIMITS, spotify.text("publicLimits"))
        assertEquals(SPOTIFY_DATA_EXPORT_HOW, spotify.text("dataExportHow"))
        assertEquals(SPOTIFY_DATA_EXPORT_LIMITS, spotify.text("dataExportLimits"))
        assertEquals(WebCommands.SPOTIFY_IMPORT_MOST_BYTES, spotify["importLimitBytes"]!!.jsonPrimitive.long)
        val import = spotify["dataImport"]!!.jsonObject
        assertFalse(import.flag("importing"))
        assertFalse(import.flag("failed"))
        assertNull(import["summary"])
        // The sign-in is told of as it always was, beside them.
        assertFalse(spotify.flag("connected"))
        assertFalse(spotify.flag("canPlay"))
    }

    @Test
    fun `a public playlist is added by its link and taken out by its id, and core says how each went`() {
        waitForPublic()
        assertEquals("No link", run("spotifyPublicAdd"))
        assertEquals("No link", run("spotifyPublicAdd", "link" to "  "))
        // An album's link, which core turns away before anything is read.
        assertNull(run("spotifyPublicAdd", "link" to SpotifyPublicStandIn.ALBUM_LINK))
        waitFor { spotify().text("publicMessage")?.startsWith("That Spotify link is not a playlist") == true }
        assertFalse(spotify().flag("addingPublicPlaylist"))
        // One followed already, by the link the share button copies.
        assertNull(run("spotifyPublicAdd", "link" to SpotifyPublicStandIn.link(SpotifyPublicStandIn.RAINY_DAYS)))
        waitFor { spotify().text("publicMessage") == "“Rainy Days” is already in your library." }

        assertEquals("Which playlist?", run("spotifyPublicRemove"))
        assertNull(run("spotifyPublicRemove", "id" to SpotifyPublicStandIn.RAINY_DAYS))
        waitFor { spotify().text("publicMessage") == "Removed “Rainy Days” from your library." }
        assertEquals(listOf(SpotifyPublicStandIn.NIGHT_BUS, SpotifyPublicStandIn.NOT_READ), spotify()["publicPlaylists"]!!.jsonArray.map { it.jsonObject.text("id") })
        assertEquals(listOf(SpotifyPublicStandIn.NIGHT_BUS, SpotifyPublicStandIn.NOT_READ), state.settings.value.preferences.spotifyPublicPlaylists)
        // By its link as well.
        assertNull(run("spotifyPublicRemove", "id" to SpotifyPublicStandIn.link(SpotifyPublicStandIn.NIGHT_BUS)))
        waitFor { state.settings.value.preferences.spotifyPublicPlaylists == listOf(SpotifyPublicStandIn.NOT_READ) }
    }

    @Test
    fun `Spotify's data download, uploaded, is imported, and the page is told how far it got and what it made`() {
        val bytes = SpotifyPublicStandIn.download()
        // As a browser names it, with whatever it puts in front.
        assertNull(commands.importSpotifyData("C:\\fakepath\\my_spotify_data.zip", bytes.size.toLong(), ByteArrayInputStream(bytes)))
        waitFor { !spotify()["dataImport"]!!.jsonObject.flag("importing") && spotify()["dataImport"]!!.jsonObject["summary"] != null }

        val import = spotify()["dataImport"]!!.jsonObject
        assertFalse(import.flag("failed"))
        val summary = import["summary"]!!.jsonObject
        assertEquals(2, summary["playlists"]!!.jsonPrimitive.int)
        assertEquals(3, summary["songs"]!!.jsonPrimitive.int)
        assertEquals(3, summary["likedSongs"]!!.jsonPrimitive.int)
        assertEquals(0, summary["replaced"]!!.jsonPrimitive.int)
        val message = summary.text("message")!!
        assertTrue(message.startsWith("Imported 2 playlists and 3 liked songs from Spotify."), message)
        assertEquals(message, import.text("message"))
        val imported = state.library.value.localPlaylists.filter { it.importedFrom?.source == PlaylistImport.SPOTIFY_DATA }
        assertEquals(setOf("Late Train", "Sunday Kitchen", SPOTIFY_LIKED_SONGS_TITLE), imported.map { it.title }.toSet())
        // And the library part has them, as every other local playlist.
        val local = wire.library().jsonObject["local"]!!.jsonArray.map { it.jsonObject.text("title") }
        assertTrue(local.containsAll(listOf("Late Train", "Sunday Kitchen", SPOTIFY_LIKED_SONGS_TITLE)), local.toString())

        // Again, the same file: what the first made is brought up to date rather than made twice.
        assertNull(commands.importSpotifyData("my_spotify_data.zip", null, ByteArrayInputStream(bytes)))
        waitFor { spotify()["dataImport"]!!.jsonObject["summary"]?.jsonObject?.get("replaced")?.jsonPrimitive?.int == 3 }
        assertEquals(3, state.library.value.localPlaylists.count { it.importedFrom?.source == PlaylistImport.SPOTIFY_DATA })
    }

    @Test
    fun `a file that is not the download is imported as nothing, and says why in the error colour`() {
        assertNull(commands.importSpotifyData("Userdata.json", null, ByteArrayInputStream("""{"username":"made-up-listener"}""".toByteArray())))
        waitFor { spotify()["dataImport"]!!.jsonObject.flag("failed") }
        val import = spotify()["dataImport"]!!.jsonObject
        assertFalse(import.flag("importing"))
        assertNotNull(import.text("message"))
        assertTrue(state.library.value.localPlaylists.none { it.importedFrom != null })
        // Nothing at all is turned away before core is asked.
        assertEquals("my_spotify_data.zip is empty. Choose my_spotify_data.zip, the file Spotify emailed you a link to.", commands.importSpotifyData(null, 0, ByteArrayInputStream(ByteArray(0))))
    }

    /** A stream that counts what was read of it. */
    private class Counted(stream: InputStream) : FilterInputStream(stream) {
        var read = 0L
        override fun read(): Int = super.read().also { if (it >= 0) read++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
    }

    @Test
    fun `an upload far larger than any download is turned away, said plainly, and not read past the limit`() {
        // Spotify's download is a few megabytes; fifty is the ceiling, and that is what the page is told.
        assertEquals(50L * 1024 * 1024, WebCommands.SPOTIFY_IMPORT_MOST_BYTES)
        val small = WebCommands(state, engine, scope, importLimit = 1024) {}

        // Said to be larger: turned away before a byte of it is read.
        val declared = Counted(ByteArrayInputStream(ByteArray(4096)))
        val said = small.importSpotifyData("everything.zip", 4096, declared)
        assertEquals(
            "everything.zip is 4 KB, and the import takes 1 KB at most. Spotify's data download is a few megabytes: " +
                "choose my_spotify_data.zip, the file Spotify emailed you a link to.",
            said,
        )
        assertEquals(0, declared.read)

        // Not said, and larger all the same: turned away once it has gone past the limit, the rest unread.
        val undeclared = Counted(ByteArrayInputStream(ByteArray(1_000_000)))
        val found = small.importSpotifyData("everything.zip", null, undeclared)
        assertTrue(found!!.startsWith("everything.zip is more than 1 KB, and the import takes 1 KB at most."), found)
        assertTrue(undeclared.read <= 1025, "read ${undeclared.read} bytes")
        // Nothing was imported, nor begun.
        Thread.sleep(200)
        val import = spotify()["dataImport"]!!.jsonObject
        assertFalse(import.flag("importing"))
        assertNull(import["message"])

        // At the limit exactly, it is the import's to read -- and this one is not Spotify's.
        assertNull(small.importSpotifyData("Playlist1.json", 1024, ByteArrayInputStream(ByteArray(1024) { ' '.code.toByte() })))
        waitFor { spotify()["dataImport"]!!.jsonObject.flag("failed") }
    }

    @Test
    fun `an upload while an import is under way is turned away in words`() {
        // An import from a stream that is held until the test lets it go, as a slow file would be.
        val release = CountDownLatch(1)
        state.importSpotifyData("my_spotify_data.zip") {
            object : InputStream() {
                override fun read(): Int {
                    release.await()
                    return -1
                }
            }
        }
        assertTrue(state.settings.value.spotify.dataImport.importing)
        val second = Counted(ByteArrayInputStream(SpotifyPublicStandIn.download()))
        assertEquals("An import of Spotify's data is under way already. Wait for it to finish first.", commands.importSpotifyData("my_spotify_data.zip", null, second))
        assertEquals(0, second.read)
        release.countDown()
        waitFor { !state.settings.value.spotify.dataImport.importing }
    }

    @Test
    fun `a pasted link is told of, and while it is a public playlist not followed yet, that it can be kept`() {
        val all = wire.all()
        assertTrue("link" in all.keys)
        val idle = wire.link().jsonObject
        assertEquals("idle", idle.text("status"))
        assertFalse(idle.flag("canKeepInLibrary"))

        val url = "https://open.spotify.com/playlist/${SpotifyPublicStandIn.NOT_READ}"
        val opened = LinkState(
            LinkStatus.DONE,
            MusicLink(ProviderType.SPOTIFY, LinkKind.PLAYLIST, SpotifyPublicStandIn.NOT_READ, url),
            tracks = SpotifyPublicStandIn.nightBus.tracks,
            message = "Playing 12 tracks from Spotify. Spotify's public page shows only a playlist's first 100 songs.",
            canKeepInLibrary = true,
        ).wire()
        assertEquals(WLink("done", url, ProviderType.SPOTIFY, "playlist", opened.message, 12, canKeepInLibrary = true), opened)
        val json = wireJson.encodeToJsonElement(WLink.serializer(), opened).jsonObject
        assertEquals("SPOTIFY", json.text("provider"))
        assertTrue(json.flag("canKeepInLibrary"))

        // A link that is not a music link at all fails, and says so, with nothing to keep.
        state.openLink("https://example.invalid/not-music")
        waitFor { wire.link().jsonObject.text("status") == "failed" }
        assertFalse(wire.link().jsonObject.flag("canKeepInLibrary"))
        assertNotNull(wire.link().jsonObject.text("message"))
    }
}
