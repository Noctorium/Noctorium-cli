package app.noctorium.cli.tui

import app.noctorium.bandcamp.BandcampGenre
import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bandcamp in the terminal player, drawn into a frame and read back as text, the way it would be seen: the
 * collection on the library page, its artists and albums in search, its rows in Settings.
 *
 * Bandcamp itself is a stand-in (see [BandcampStandIn]), so this needs no network. Run with
 * `-Dnoctorium.renderTui=<folder>` and each frame is also written there as text, to be looked at whole.
 */
class BandcampPagesTest {
    private val parts = CliParts()
    private val state = BandcampStandIn.state(parts)
    private val tui = Tui(state, parts, web = null)
    private val folder = System.getProperty("noctorium.renderTui")?.let(::File)?.also { it.mkdirs() }

    @AfterTest
    fun close() = state.close()

    private fun press(vararg inputs: Input) = inputs.forEach { tui.press(it) }

    /** The whole frame as text, a line per row of the terminal. */
    private fun text(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    /** The page's list as drawn, without the sidebar, the header or the player bar around it. */
    private fun drawn(name: String): List<String> {
        tui.frame(150, 42)
        val canvas = tui.frame(150, 42)
        folder?.let { File(it, "bandcamp-$name.txt").writeText(text(canvas).joinToString("\n")) }
        val view = tui.listView
        return (view.top until view.top + view.height).map { y ->
            (view.left until view.left + view.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
        }
    }

    private fun searchFor(query: String) {
        state.search(query)
        waitFor { state.ui.value.searchQuery == query && !state.ui.value.searchLoading && state.ui.value.searchResults.playlists.isNotEmpty() }
        tui.searchText = query
        tui.searchFocused = false
        tui.page = Page.SEARCH
    }

    @Test
    fun `the library has a group for the Bandcamp collection`() {
        state.refreshLibrary(force = true)
        waitFor { state.library.value.loaded && !state.library.value.loading && state.library.value.playlists.isNotEmpty() }
        tui.page = Page.LIBRARY
        val page = drawn("library")
        val group = page.indexOfFirst { it.trim().startsWith("BANDCAMP") }
        assertTrue(group >= 0, "no Bandcamp group:\n" + page.joinToString("\n"))
        assertTrue(page[group].trimEnd().endsWith("2"), page[group])
        assertTrue("Wishlist" in page[group + 1] && "Some Fan" in page[group + 1], page[group + 1])
        assertTrue("Awake" in page[group + 2] && "Tycho · 8 tracks" in page[group + 2], page[group + 2])
    }

    @Test
    fun `search lists Bandcamp's artists as artists and its releases as albums, once each`() {
        searchFor("tycho")
        val page = drawn("search")
        val albums = page.indexOfFirst { it.trim() == "ALBUMS" }
        val artists = page.indexOfFirst { it.trim() == "ARTISTS" }
        assertTrue(albums >= 0 && artists > albums, page.joinToString("\n"))
        assertTrue("BC" in page[albums + 1] && "Awake" in page[albums + 1] && "Tycho" in page[albums + 1], page[albums + 1])
        assertTrue("Tycho" in page[artists + 1] && "Artist · San Francisco, California" in page[artists + 1], page[artists + 1])
        // The bare names Bandcamp also sends are not listed under them a second time, and none is a playlist.
        assertEquals("", page[albums + 2].trim())
        assertEquals("", page.getOrElse(artists + 2) { "" }.trim())
        assertFalse(page.any { it.trim() == "PLAYLISTS" }, page.joinToString("\n"))

        // An artist opens as everything they put out, and says it is an artist.
        val rows = Pages.rows(tui)
        tui.list("SEARCH").selected = rows.indexOfFirst { it is Row.PlaylistItem && it.playlist == BandcampStandIn.band }
        press(Input.Key(Keys.ENTER))
        assertEquals(Page.LIBRARY, tui.page)
        waitFor { state.library.value.openPlaylist?.tracks?.isNotEmpty() == true }
        assertEquals(BandcampStandIn.band.playlistKey, state.library.value.openPlaylist?.playlistKey)
        val title = text(tui.frame(150, 42)).first { "Tycho" in it && "Artist on Bandcamp" in it }
        folder?.let { File(it, "bandcamp-artist.txt").writeText(text(tui.frame(150, 42)).joinToString("\n")) }
        assertFalse("rename" in title, "a Bandcamp artist offered to be renamed: $title")
    }

    @Test
    fun `a Bandcamp song is not downloaded or saved, and the page says where it can be bought`() {
        searchFor("tycho")
        tui.list("SEARCH").selected = Pages.rows(tui).indexOfFirst { it is Row.Song }
        press(Input.Text("d"))
        press(Input.Text("e"))
        assertTrue(state.downloadState.value.active.isEmpty())
        val frame = text(tui.frame(150, 42)).joinToString("\n")
        assertTrue("to keep one, buy it on its page" in frame, frame)
        assertFalse("Downloading" in frame || "Saving" in frame, frame)
    }

    @Test
    fun `Settings has the Bandcamp collection and the genres Home has rows for`() {
        tui.page = Page.SETTINGS
        val page = drawn("settings")
        assertTrue(page.any { "Bandcamp collection" in it && "bandcamp.com/${BandcampStandIn.FAN}" in it }, page.joinToString("\n"))
        assertTrue(page.any { "Bandcamp on Home" in it && "Electronic, Hip-hop and rap, Ambient" in it }, page.joinToString("\n"))
        // Its menu is not opened here: it looks for the desktop app's own settings, which are the listener's.
    }

    @Test
    fun `the genres are ticked in a list, and kept once it is put away`() {
        Settings.rows(tui).filterIsInstance<Row.Action>().first { it.label == "Bandcamp on Home" }.run()
        val list = assertIs<Overlay.Checklist>(tui.overlays.last())
        assertEquals(BandcampGenre.DEFAULT_HOME, list.chosen.map { BandcampGenre.entries[it] })
        tui.page = Page.SETTINGS
        drawn("genres")
        // Electronic, the first, off; down to Rock, the third, and on.
        press(Input.Text(" "), Input.Key(Keys.DOWN), Input.Key(Keys.DOWN), Input.Text(" "))
        assertEquals(BandcampGenre.DEFAULT_HOME, state.settings.value.preferences.bandcampGenres, "kept before the list was put away")
        press(Input.Key(Keys.ESCAPE))
        assertTrue(tui.overlays.isEmpty())
        assertEquals(listOf(BandcampGenre.HIP_HOP, BandcampGenre.AMBIENT, BandcampGenre.ROCK), state.settings.value.preferences.bandcampGenres)
    }
}
