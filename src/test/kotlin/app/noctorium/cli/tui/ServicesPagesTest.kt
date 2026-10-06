package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.SpotifyStandIn
import app.noctorium.cli.VkStandIn
import app.noctorium.core.SearchMode
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SpotifyPlayback
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spotify, VK, the ways of listening and the keys, in the terminal player, drawn into a frame and read back as
 * text. The services are stand-ins (see [SpotifyStandIn] and [VkStandIn]), so nothing reaches them, and the
 * account starts signed in with Premium, playing Spotify's songs on Spotify, and signed in to VK as a made-up
 * listener. With `-Dnoctorium.renderTui=<folder>` each frame is also written there as text.
 */
class ServicesPagesTest {
    private val parts = CliParts()
    private val state = BandcampStandIn.state(
        parts,
        more = listOf(SpotifyStandIn.Provider(), VkStandIn.Provider()),
        preferences = NoctoriumPreferences(
            bandcampUsername = BandcampStandIn.FAN,
            spotifyCanPlay = true,
            spotifyPlayback = SpotifyPlayback.ON_SPOTIFY,
            vkAccountName = VkStandIn.ACCOUNT,
        ),
    )
    private val tui = Tui(state, parts, web = null)
    private val folder = System.getProperty("noctorium.renderTui")?.let(::File)?.also { it.mkdirs() }

    @AfterTest
    fun close() {
        // The keys live in the test home's terminal.json, which every other test's player reads.
        tui.useKeys(KeyMap.DEFAULT)
        state.close()
    }

    private fun press(vararg inputs: Input) = inputs.forEach { tui.press(it) }

    private fun text(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    private fun frame(name: String, h: Int = 42): List<String> {
        tui.frame(150, h)
        val lines = text(tui.frame(150, h))
        folder?.let { File(it, "services-$name.txt").writeText(lines.joinToString("\n")) }
        return lines
    }

    /** The page's list alone, as drawn. */
    private fun drawn(name: String, h: Int = 42): List<String> {
        frame(name, h)
        val canvas = tui.frame(150, h)
        val view = tui.listView
        return (view.top until view.top + view.height).map { y ->
            (view.left until view.left + view.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
        }
    }

    private fun searchFor(query: String, mode: SearchMode) {
        state.setSearchMode(mode)
        state.search(query)
        waitFor { state.ui.value.searchQuery == query && state.ui.value.searchMode == mode && !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty() }
        tui.searchText = query
        tui.searchFocused = false
        tui.page = Page.SEARCH
    }

    @Test
    fun `Spotify's albums and artists are listed as what they are`() {
        searchFor("tycho", SearchMode.SPOTIFY)
        val page = drawn("spotify-search")
        val albums = page.indexOfFirst { it.trim() == "ALBUMS" }
        val artists = page.indexOfFirst { it.trim() == "ARTISTS" }
        assertTrue(albums >= 0 && artists > albums, page.joinToString("\n"))
        assertTrue("SP" in page[albums + 1] && "Awake" in page[albums + 1] && "Tycho · 8 tracks" in page[albums + 1], page[albums + 1])
        assertTrue("Tycho" in page[artists + 1] && "Artist" in page[artists + 1] && "Artist · Artist" !in page[artists + 1], page[artists + 1])
        // Spotify's artists come again as bare names, which are not listed a second time.
        assertEquals("", page.getOrElse(artists + 2) { "" }.trim())
        assertFalse(page.any { it.trim() == "PLAYLISTS" })
    }

    @Test
    fun `the library has VK's My music and its playlists`() {
        state.refreshLibrary(force = true)
        waitFor { state.library.value.playlists.any { it.provider == ProviderType.VK } && !state.library.value.loading }
        tui.page = Page.LIBRARY
        val page = drawn("vk-library")
        val group = page.indexOfFirst { it.trim().startsWith("VK MUSIC") }
        assertTrue(group >= 0, page.joinToString("\n"))
        assertTrue("My music" in page[group + 1], page[group + 1])
        assertTrue("Evening" in page[group + 2] && "${VkStandIn.ACCOUNT} · 12 tracks" in page[group + 2], page[group + 2])
    }

    @Test
    fun `a VK song is not downloaded, and says so`() {
        searchFor("tycho", SearchMode.VK)
        tui.list("SEARCH").selected = Pages.rows(tui).indexOfFirst { it is Row.Song && it.track.provider == ProviderType.VK }
        press(Input.Text("d"))
        val shown = frame("vk-keep").joinToString("\n")
        assertTrue("VK's songs play here but cannot be downloaded" in shown, shown)
        assertTrue(state.downloadState.value.active.isEmpty())
    }

    @Test
    fun `Settings has both Spotify sign-ins, where its songs play, VK, and the ways of listening`() {
        waitFor { state.settings.value.spotify.canPlay && state.settings.value.vk.connected }
        tui.page = Page.SETTINGS
        val page = drawn("settings", h = 64)
        fun row(label: String) = page.firstOrNull { it.trim().startsWith(label) || it.trim().startsWith("› $label") } ?: ""
        assertTrue("Not connected" in row("Spotify "), page.joinToString("\n"))
        assertTrue("Signed in: Spotify songs can play in your Spotify app" in row("Spotify Premium"), row("Spotify Premium"))
        assertTrue("On Spotify" in row("Spotify songs play"), row("Spotify songs play"))
        assertTrue("Wherever Spotify is active" in row("Spotify plays on"), row("Spotify plays on"))
        assertTrue("Signed in as ${VkStandIn.ACCOUNT}" in row("VK Music"), row("VK Music"))
        assertTrue("1× (as recorded)" in row("Speed"), row("Speed"))
        assertTrue("Carry on with songs like the last" in row("When the queue runs out"), row("When the queue runs out"))
        assertTrue("No: it stops at once" in row("Sleep timer fades out"), row("Sleep timer fades out"))
        assertTrue("Every service" in row("Hybrid search asks"), row("Hybrid search asks"))
        assertTrue("As they came" in row("Keys"), row("Keys"))
    }

    @Test
    fun `VK's sign-in says what it means, whole, before it asks for the cookies`() {
        waitFor { state.settings.value.vk.connected }
        Settings.rows(tui).filterIsInstance<Row.Action>().first { it.label == "VK Music" }.run()
        val menu = assertIs<Overlay.Picker>(tui.overlays.last())
        menu.options.first { it.first.startsWith("Sign in again") }.second()
        assertIs<Overlay.Notice>(tui.overlays.last())
        // The panel is 84 wide, in the middle of 150: what is inside its borders, line by line, is the notice.
        val shown = frame("vk-notice").joinToString(" ") { it.padEnd(150).substring(34, 116) }.replace(Regex("\\s+"), " ")
        listOf(
            "VK offers no music to other apps",
            "That is against VK's terms",
            "freeze an account it thinks is automated",
            "Many songs do not play outside Russia",
            "VK's songs cannot be downloaded",
            "copy p from login.vk.ru, and remixsid from vk.ru",
        ).forEach { assertTrue(it in shown, "the notice did not say: $it\n$shown") }
        press(Input.Key(Keys.ENTER))
        val prompt = assertIs<Overlay.Prompt>(tui.overlays.last())
        assertTrue(prompt.secret, "the cookies were to be shown as they are typed")
    }

    @Test
    fun `a Spotify song played on Spotify says so in the player bar`() {
        waitFor { state.settings.value.spotify.playsOnSpotify }
        state.play(SpotifyStandIn.song, PlaybackOrigin.SEARCH, listOf(SpotifyStandIn.song))
        waitFor { state.playback.value.track?.queueKey == SpotifyStandIn.song.queueKey }
        tui.page = Page.HOME
        val bar = frame("on-spotify-bar").takeLast(4).joinToString("\n")
        assertTrue("SP  on Spotify" in bar, bar)
        // Now playing says it from the same answer; drawing that page here would ask the lyrics sources online.
        assertTrue(NowPlaying.playsOnSpotify(tui, SpotifyStandIn.song))
        assertFalse(NowPlaying.playsOnSpotify(tui, VkStandIn.song))
    }

    @Test
    fun `the speed keys, and the ways of listening changed from Settings`() {
        press(Input.Text(">"))
        assertEquals(1.25f, state.settings.value.preferences.playbackSpeed)
        press(Input.Text("<"), Input.Text("<"))
        assertEquals(.75f, state.settings.value.preferences.playbackSpeed)
        // Beyond the ends it stays at the end.
        repeat(4) { press(Input.Text("<")) }
        assertEquals(.5f, state.settings.value.preferences.playbackSpeed)

        fun action(label: String) = Settings.rows(tui).filterIsInstance<Row.Action>().first { it.label == label }
        assertEquals("0.5×", action("Speed").value)
        action("When the queue runs out").step!!.invoke(1)
        assertEquals(false, state.settings.value.preferences.autoplay)
        action("Sleep timer fades out").step!!.invoke(1)
        assertEquals(15, state.settings.value.preferences.sleepFadeSeconds)

        action("Hybrid search asks").run()
        assertIs<Overlay.Checklist>(tui.overlays.last())
        // VK is the last of the six: down to it, untick it, and put the list away.
        press(*Array(5) { Input.Key(Keys.DOWN) }, Input.Text(" "), Input.Key(Keys.ESCAPE))
        assertFalse(ProviderType.VK in state.settings.value.preferences.hybridSearch)
        assertTrue(ProviderType.SPOTIFY in state.settings.value.preferences.hybridSearch)
        state.setPlaybackSpeed(1f)
    }

    @Test
    fun `a key is changed on the keys page, refused where it would clash, and the player answers to it`() {
        tui.page = Page.SETTINGS
        Settings.rows(tui).filterIsInstance<Row.Action>().first { it.label == "Keys" }.run()
        assertTrue(tui.keysOpen)
        val page = drawn("keys", h = 60)
        // A header's line ends in the scroll mark here: the list is longer than the page.
        assertTrue(page.any { it.trim().startsWith("EVERYWHERE") }, page.joinToString("\n"))
        assertTrue(page.any { "Play or pause" in it && "Space" in it }, page.joinToString("\n"))

        val rows = Settings.keyRows(tui)
        tui.list("SETTINGS:keys").selected = rows.indexOfFirst { it is Row.Action && it.label == KeyAction.HELP.label }
        press(Input.Key(Keys.ENTER))
        assertIs<Overlay.KeyCapture>(tui.overlays.last())
        press(Input.Text("q"))
        val refused = frame("keys-refused").joinToString("\n")
        assertTrue("q is already “Quit”" in refused, refused)
        assertTrue(tui.running, "q was taken as Quit while a key was being chosen")
        press(Input.Text("h"))
        assertTrue(tui.overlays.isEmpty())
        assertEquals(listOf("h"), tui.keys.keys(KeyAction.HELP))
        assertEquals(listOf("h"), TuiPreferences.load().keys["HELP"])

        press(Input.Key(Keys.ESCAPE))
        assertFalse(tui.keysOpen)
        press(Input.Text("?"))
        assertTrue(tui.overlays.isEmpty(), "the old key still opened the help")
        press(Input.Text("h"))
        assertIs<Overlay.Help>(tui.overlays.last())
        val help = frame("help-rebound").joinToString("\n")
        assertTrue(Regex("""\bh\s+These keys""").containsMatchIn(help), help)
        press(Input.Text("x"))

        // Delete on its row puts it back.
        tui.keysOpen = true
        tui.list("SETTINGS:keys").selected = Settings.keyRows(tui).indexOfFirst { it is Row.Action && it.label == KeyAction.HELP.label }
        press(Input.Key(Keys.DELETE))
        assertEquals(listOf("?"), tui.keys.keys(KeyAction.HELP))
        assertTrue(TuiPreferences.load().keys.isEmpty())
    }
}
