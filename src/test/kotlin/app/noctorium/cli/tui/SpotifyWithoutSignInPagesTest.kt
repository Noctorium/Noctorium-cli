package app.noctorium.cli.tui

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
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spotify with nobody signed in, in the terminal player: the public playlists followed by their links under the
 * warning of what that can and cannot do, and Spotify's data download imported from a path typed in Settings; and
 * Keep in library over a pasted link. Nothing reaches Spotify: the followed playlists are made-up copies, and every
 * link added here is one core turns away before it reads anything (see [SpotifyPublicStandIn]). With
 * `-Dnoctorium.renderTui=<folder>` each frame is also written there as text.
 */
class SpotifyWithoutSignInPagesTest {
    private val parts = CliParts()
    private val folder = Files.createTempDirectory("noctorium-spotify-pages")
    private val state = BandcampStandIn.state(
        parts,
        preferences = NoctoriumPreferences(
            bandcampUsername = BandcampStandIn.FAN,
            spotifyPublicPlaylists = listOf(SpotifyPublicStandIn.NIGHT_BUS, SpotifyPublicStandIn.RAINY_DAYS, SpotifyPublicStandIn.NOT_READ),
        ),
        spotifyCopies = SpotifyPublicStandIn.copies(),
        playlists = LocalPlaylistRepository(folder.resolve("playlists.json")),
    )
    private val tui = Tui(state, parts, web = null)
    private val shots = System.getProperty("noctorium.renderTui")?.let(::File)?.also { it.mkdirs() }

    @AfterTest
    fun close() {
        state.close()
        folder.toFile().deleteRecursively()
    }

    private fun press(vararg inputs: Input) = inputs.forEach { tui.press(it) }
    private fun type(text: String) = text.forEach { tui.press(Input.Text(it.toString())) }

    /** The page's list alone, as drawn, all of it. */
    private fun drawn(name: String, h: Int = 200): List<String> {
        tui.frame(150, h)
        val canvas = tui.frame(150, h)
        shots?.let { File(it, "spotify-public-$name.txt").writeText(Frames.text(canvas)) }
        val view = tui.listView
        return (view.top until view.top + view.height).map { y ->
            (view.left until view.left + view.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
        }
    }

    private fun rows() = Settings.rows(tui)
    private fun actions() = rows().filterIsInstance<Row.Action>()
    private fun notes() = rows().filterIsInstance<Row.Note>()

    /** Chooses the setting [id] -- the [nth] of its rows -- on Settings, as the search would. */
    private fun choose(id: String, nth: Int = 0) {
        tui.page = Page.SETTINGS
        val all = rows()
        tui.list(Page.SETTINGS.name).selected = all.indices.filter { (all[it] as? Row.Action)?.id == id }[nth]
    }

    private fun waitForPublic() = waitFor { state.settings.value.spotify.publicPlaylists.size == 3 }

    @Test
    fun `Spotify's settings warn of what public playlists can do, above the box for one, and list the ones followed`() {
        waitForPublic()
        val all = rows()
        val warning = wrapWords(SPOTIFY_PUBLIC_PLAYLIST_LIMITS, Settings.WARNING_WIDTH)
        val first = all.indexOfFirst { it is Row.Note && it.text == warning.first() }
        assertTrue(first > 0, "the warning is not there")
        warning.forEachIndexed { i, line -> assertEquals(Row.Note(line, Row.Tone.WARN), all[first + i]) }
        // Under the sign-ins, which are as they were, and over the box that takes a link.
        val signIn = all.indexOfFirst { it is Row.Action && it.id == "spotifyPremium" }
        val add = all.indexOfFirst { it is Row.Action && it.id == "spotifyPublic" }
        assertTrue(signIn in 0 until first && add == first + warning.size, "signIn $signIn, warning $first, add $add")
        assertEquals(Row.Note("Public playlists, without signing in", Row.Tone.ACCENT), all[first - 1])
        assertEquals("Add a public playlist", (all[add] as Row.Action).label)

        tui.page = Page.SETTINGS
        val page = drawn("settings").joinToString("\n")
        fun row(label: String) = page.lines().firstOrNull { it.trim().removePrefix("› ").startsWith(label) } ?: ""
        assertTrue("Sample Listener · 12 songs" in row("Night Bus Home"), page)
        assertTrue("Paper Satellites · 100 songs · first 100 songs" in row("Rainy Days"), row("Rainy Days"))
        assertTrue("not read yet" in row(SpotifyPublicStandIn.NOT_READ), row(SpotifyPublicStandIn.NOT_READ))
        assertTrue(page.indexOf("Night Bus Home") > page.indexOf("Add a public playlist"))
        // How to get the data download, and what its import leaves out, whole.
        val said = page.replace(Regex("\\s+"), " ")
        assertTrue(SPOTIFY_DATA_EXPORT_HOW in said, said)
        assertTrue(SPOTIFY_DATA_EXPORT_LIMITS in said, said)
        assertTrue(SPOTIFY_PUBLIC_PLAYLIST_LIMITS in said, said)
        assertTrue("Enter, then the file's path" in row("Import Spotify's data download"), row("Import Spotify's data download"))
    }

    @Test
    fun `a link is asked for and handed to core, which says what came of it under the box`() {
        waitForPublic()
        choose("spotifyPublic")
        press(Input.Key(Keys.ENTER))
        assertIs<Overlay.Prompt>(tui.overlays.last())
        // An album's link, which core turns away before anything is read.
        type(SpotifyPublicStandIn.ALBUM_LINK)
        press(Input.Key(Keys.ENTER))
        waitFor { state.settings.value.spotify.publicMessage?.startsWith("That Spotify link is not a playlist") == true }
        assertTrue(notes().any { it.text.startsWith("That Spotify link is not a playlist") }, notes().toString())

        // One followed already, by the link Spotify's share button copies: said as such, by its name.
        choose("spotifyPublic")
        press(Input.Key(Keys.ENTER))
        type(SpotifyPublicStandIn.link(SpotifyPublicStandIn.NIGHT_BUS))
        press(Input.Key(Keys.ENTER))
        waitFor { state.settings.value.spotify.publicMessage == "“Night Bus Home” is already in your library." }
        assertFalse(state.settings.value.spotify.addingPublicPlaylist)
        assertEquals(3, state.settings.value.preferences.spotifyPublicPlaylists.size)
    }

    @Test
    fun `Delete on a followed playlist takes it out, once asked, and Enter offers to open it`() {
        waitForPublic()
        choose("spotifyPublic", nth = 1)
        val chosen = rows()[tui.list(Page.SETTINGS.name).selected] as Row.Action
        assertEquals("Night Bus Home", chosen.label)
        assertEquals("Enter opens it · Delete takes it out", chosen.detail)

        press(Input.Key(Keys.ENTER))
        val menu = assertIs<Overlay.Picker>(tui.overlays.last())
        assertEquals("Night Bus Home", menu.title)
        assertEquals(listOf("Open it", "Open it on Spotify", "Take it out of the library"), menu.options.map { it.first })
        tui.overlays.clear()

        press(Input.Key(Keys.DELETE))
        val confirm = assertIs<Overlay.Confirm>(tui.overlays.last())
        assertEquals("Take “Night Bus Home” out of the library?", confirm.question)
        press(Input.Text("y"))
        waitFor { state.settings.value.spotify.publicMessage == "Removed “Night Bus Home” from your library." }
        assertEquals(listOf(SpotifyPublicStandIn.RAINY_DAYS, SpotifyPublicStandIn.NOT_READ), state.settings.value.preferences.spotifyPublicPlaylists)
        assertTrue(actions().none { it.label == "Night Bus Home" })
    }

    @Test
    fun `Spotify's data download is imported from the path typed, in quotes as a dragged file comes, and says what it made`() {
        val file = folder.resolve("Spotify download").resolve("my_spotify_data.zip")
        Files.createDirectories(file.parent)
        Files.write(file, SpotifyPublicStandIn.download())
        choose("spotifyImport")
        press(Input.Key(Keys.ENTER))
        val prompt = assertIs<Overlay.Prompt>(tui.overlays.last())
        assertEquals("Spotify's data download", prompt.title)
        type("\"$file\"")
        press(Input.Key(Keys.ENTER))
        waitFor { state.settings.value.spotify.dataImport.summary != null }

        val summary = state.settings.value.spotify.dataImport.summary!!
        assertEquals(2, summary.playlists)
        assertEquals(3, summary.likedSongs)
        val titles = state.library.value.localPlaylists.filter { it.importedFrom?.source == PlaylistImport.SPOTIFY_DATA }.map { it.title }.toSet()
        assertEquals(setOf("Late Train", "Sunday Kitchen", SPOTIFY_LIKED_SONGS_TITLE), titles)
        // What it made is said under the row, in the good colour, and the row is ready for the next file.
        val said = wrapWords(summary.message, Settings.WARNING_WIDTH)
        said.forEach { line -> assertTrue(Row.Note(line, Row.Tone.GOOD) in notes(), "not said: $line") }
        assertEquals("Enter, then the file's path", actions().first { it.id == "spotifyImport" }.value)
        drawn("imported")
    }

    @Test
    fun `a file that is not the download says so in the error colour, and a path to nothing imports nothing`() {
        val file = folder.resolve("Userdata.json")
        Files.writeString(file, """{"username":"made-up-listener"}""")
        choose("spotifyImport")
        press(Input.Key(Keys.ENTER))
        type(file.toString())
        press(Input.Key(Keys.ENTER))
        waitFor { state.settings.value.spotify.dataImport.failed }
        val message = state.settings.value.spotify.dataImport.message!!
        assertTrue(notes().any { it.tone == Row.Tone.BAD && message.startsWith(it.text) }, notes().toString())

        choose("spotifyImport")
        press(Input.Key(Keys.ENTER))
        type(folder.resolve("nowhere.zip").toString())
        press(Input.Key(Keys.ENTER))
        assertEquals(message, state.settings.value.spotify.dataImport.message, "nothing more was tried")
    }

    @Test
    fun `a typed path is read however it was pasted`() {
        val home = "/home/someone"
        fun path(typed: String, unix: Boolean = true) = Settings.spotifyImportPath(typed, home, unix)?.toString()?.replace('\\', '/')
        assertEquals("/home/someone/Downloads/my_spotify_data.zip", path("~/Downloads/my_spotify_data.zip"))
        assertEquals("/home/someone/Downloads/my_spotify_data.zip", path("  '~/Downloads/my_spotify_data.zip'  "))
        assertEquals("/tmp/Spotify Account Data/Playlist1.json", path("/tmp/Spotify\\ Account\\ Data/Playlist1.json"))
        assertEquals("/tmp/Spotify Account Data", path("\"/tmp/Spotify Account Data\""))
        assertNull(Settings.spotifyImportPath("  \"\"  ", home))
        assertNull(Settings.spotifyImportPath("", home))
        // On Windows a backslash is the folder's, and stays.
        val windows = Settings.spotifyImportPath("\"C:\\Users\\Someone\\Downloads\\my_spotify_data.zip\"", home, unix = false)
        assertTrue(windows.toString().endsWith("my_spotify_data.zip"), windows.toString())
        val file = folder.resolve("my_spotify_data.zip")
        assertEquals(file, Settings.spotifyImportPath(file.toUri().toString(), home))
    }

    @Test
    fun `a public Spotify playlist opened from its link can be kept from Search, and nothing else offers to be`() {
        waitForPublic()
        val url = SpotifyPublicStandIn.link(SpotifyPublicStandIn.NIGHT_BUS).substringBefore('?')
        val opened = LinkState(
            LinkStatus.DONE,
            MusicLink(ProviderType.SPOTIFY, LinkKind.PLAYLIST, SpotifyPublicStandIn.NIGHT_BUS, url),
            tracks = SpotifyPublicStandIn.nightBus.tracks,
            message = "Playing 12 tracks from Spotify.",
            canKeepInLibrary = true,
        )
        tui.pastedLink = { opened }
        tui.page = Page.SEARCH
        tui.searchFocused = false
        val page = drawn("search-keep", h = 42)
        val header = page.indexOfFirst { it.trim().startsWith("THE LINK YOU PASTED") }
        assertTrue(header >= 0, page.joinToString("\n"))
        assertTrue("Playing 12 tracks from Spotify." in page[header + 1], page[header + 1])
        assertTrue("Keep in library" in page[header + 2] && "Follow it without signing in" in page[header + 2], page[header + 2])

        // Enter there hands core the link's own address -- one followed already here, so nothing is read.
        tui.list(Page.SEARCH.name).selected = Pages.rows(tui).indexOfFirst { it is Row.Action && it.label == "Keep in library" }
        press(Input.Key(Keys.ENTER))
        waitFor { state.settings.value.spotify.publicMessage == "“Night Bus Home” is already in your library." }

        for (other in listOf(opened.copy(canKeepInLibrary = false), opened.copy(status = LinkStatus.OPENING), LinkState())) {
            tui.pastedLink = { other }
            assertTrue(Pages.rows(tui).none { it is Row.Action && it.label == "Keep in library" }, other.toString())
        }
    }
}
