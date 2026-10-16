package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.LaterServicesStandIn
import app.noctorium.cli.RecordingBridge
import app.noctorium.cli.TerminalBridge
import app.noctorium.core.AppState
import app.noctorium.core.SearchMode
import app.noctorium.domain.BETA_WARNING
import app.noctorium.domain.ProviderType
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.ThemePreset
import app.noctorium.tidal.TidalAuth
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Apple Music, Deezer and Tidal in the terminal player: their rows in Settings, signed in and not, each under the beta
 * warning every service but YouTube Music, YouTube and SoundCloud has; what Enter does on each; and their badges.
 * The services are stand-ins and the sign-ins made up and in memory (see [LaterServicesStandIn]), the clipboard and
 * the browser are written down rather than used, and nothing that would open Tidal's sign-in is pressed, so nothing
 * reaches any of the three. With `-Dnoctorium.renderTui=<folder>` each frame is also written there as text.
 */
class LaterServicesPagesTest {
    private val parts = CliParts()
    private val bridge = RecordingBridge()
    private val opened = mutableListOf<Pair<AppState, Tui>>()
    private val folder = System.getProperty("noctorium.renderTui")?.let(::File)?.also { it.mkdirs() }

    private fun open(signedIn: Boolean): Pair<AppState, Tui> {
        val state = BandcampStandIn.state(
            parts,
            more = LaterServicesStandIn.providers(),
            preferences = if (signedIn) LaterServicesStandIn.signedInPreferences else NoctoriumPreferences(),
            system = bridge,
            credentials = if (signedIn) LaterServicesStandIn.signedIn() else InMemorySecretStore(),
        )
        val tui = Tui(state, parts, web = null).also { it.page = Page.SETTINGS }
        opened += state to tui
        // Tidal's app and sign-in, and Apple Music's developer token, are read from the store in the background.
        if (signedIn) waitFor { state.settings.value.tidal.connected && state.settings.value.appleMusic.ownDeveloperToken }
        return state to tui
    }

    @AfterTest
    fun close() {
        Settings.overSsh = { TerminalBridge.overSsh() }
        opened.forEach { (state, _) -> state.close() }
    }

    private fun actions(tui: Tui) = Settings.rows(tui).filterIsInstance<Row.Action>()
    private fun action(tui: Tui, id: String) = actions(tui).first { it.id == id }

    private fun text(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    private fun frame(tui: Tui, name: String, w: Int = 150, h: Int = 42): List<String> {
        tui.frame(w, h)
        val lines = text(tui.frame(w, h))
        folder?.let { File(it, "later-$name.txt").writeText(lines.joinToString("\n")) }
        return lines
    }

    /** The notes in the warning's colour straight above the row [id], as one line. */
    private fun warningAbove(tui: Tui, id: String): String {
        val rows = Settings.rows(tui)
        val at = rows.indexOfFirst { it is Row.Action && it.id == id }
        assertTrue(at >= 0, "no row $id")
        return rows.subList(0, at).takeLastWhile { it is Row.Note && it.tone == Row.Tone.WARN }
            .joinToString(" ") { (it as Row.Note).text }
    }

    @Test
    fun `every service but YouTube Music and SoundCloud has the beta warning above its sign-in, whole`() {
        val (_, tui) = open(signedIn = false)
        // Wrapped to what an 80-column terminal has room for, and the same at any width, so that resizing moves no row.
        val counts = mutableSetOf<Int>()
        for (width in listOf(150, 84)) {
            counts += Settings.rows(tui).size
            frame(tui, "warnings-$width", w = width)
            for (id in listOf("spotify", "bandcamp", "vk", "appleMusic", "deezer", "tidal")) {
                assertEquals(BETA_WARNING, warningAbove(tui, id), "$id at $width")
            }
            for (id in listOf("youtube", "soundcloud")) assertEquals("", warningAbove(tui, id), "$id at $width")
            Settings.rows(tui).filterIsInstance<Row.Note>().filter { it.tone == Row.Tone.WARN }.forEach { note ->
                assertTrue(Canvas.displayWidth(note.text) <= tui.listView.width - 3, "\"${note.text}\" is wider than the list at $width")
            }
            counts += Settings.rows(tui).size
        }
        assertEquals(1, counts.size, "Settings has a different number of rows at another width")
    }

    @Test
    fun `signed out, each says what it needs, and where Enter takes it`() {
        val (state, tui) = open(signedIn = false)
        Thread.sleep(300)
        assertEquals("Not signed in: Enter says how", action(tui, "appleMusic").value)
        assertEquals("Optional: the web player's is used", action(tui, "appleMusicToken").value)
        assertEquals("Not set", action(tui, "deezer").value)
        assertEquals("Needs a Tidal app of your own first", action(tui, "tidal").value)
        assertEquals("Not set: Enter gives its client id and secret", action(tui, "tidalApp").value)
        assertEquals(TidalAuth.redirectUri(), action(tui, "tidalRedirect").value)
        val shown = frame(tui, "signed-out", h = 120).joinToString("\n")
        listOf("Apple Music", "Deezer profile", "Use your own Tidal app", "Tidal redirect address", "http://127.0.0.1:8889/noctorium/tidal").forEach {
            assertTrue(it in shown, "Settings did not show $it\n$shown")
        }

        // Apple Music: where the cookie is, under the warning, before the token is asked for, hidden as it is typed.
        action(tui, "appleMusic").run()
        val notice = assertIs<Overlay.Notice>(tui.overlays.last())
        assertEquals(BETA_WARNING, notice.paragraphs.first())
        assertTrue(notice.paragraphs.any { "media-user-token" in it && "developer tools" in it }, notice.paragraphs.joinToString("\n"))
        frame(tui, "apple-music-notice")
        tui.press(Input.Key(Keys.ENTER))
        assertTrue(assertIs<Overlay.Prompt>(tui.overlays.last()).secret, "the token was to be hidden as it is typed")
        // A paste that is not the token is said at once, with nothing asked of Apple.
        "not a token".forEach { tui.press(Input.Text(it.toString())) }
        tui.press(Input.Key(Keys.ENTER))
        waitFor { state.settings.value.appleMusic.message?.startsWith("That is not an Apple Music sign-in") == true }
        assertFalse(state.settings.value.appleMusic.connected)

        // Deezer: a profile asked for at once; Tidal: the app to set up first, with the dashboard and the address.
        action(tui, "deezer").run()
        assertEquals("Deezer", assertIs<Overlay.Prompt>(tui.overlays.removeLast()).title)
        action(tui, "tidal").run()
        val app = assertIs<Overlay.Picker>(tui.overlays.last())
        assertEquals("Your own Tidal app", app.title)
        assertEquals(listOf("Its client id and secret…", "Copy the redirect address to register", "Open Tidal's developer dashboard"), app.options.map { it.first })
        frame(tui, "tidal-app")
        app.options[1].second()
        waitFor { TidalAuth.redirectUri() in bridge.copied }
        app.options[2].second()
        assertEquals(listOf("https://developer.tidal.com/dashboard"), bridge.opened)
    }

    @Test
    fun `a Tidal app given here is kept, and Tidal then signs in through it`() {
        val (state, tui) = open(signedIn = false)
        Thread.sleep(300)
        action(tui, "tidalApp").run()
        assertIs<Overlay.Picker>(tui.overlays.last()).options.first().second()
        "made-up-client-id".forEach { tui.press(Input.Text(it.toString())) }
        tui.press(Input.Key(Keys.ENTER))
        val secret = assertIs<Overlay.Prompt>(tui.overlays.last())
        assertTrue(secret.secret, "the secret was to be hidden as it is typed")
        tui.press(Input.Key(Keys.ENTER))
        waitFor { state.settings.value.tidal.configured }
        assertTrue(state.settings.value.tidal.ownApp)
        assertEquals("Not connected: Enter signs in", action(tui, "tidal").value)
        assertEquals("Yours", action(tui, "tidalApp").value)
        frame(tui, "tidal-own-app", h = 120)
    }

    @Test
    fun `signed in to all three, each says whose it is`() {
        val (_, tui) = open(signedIn = true)
        waitFor { action(tui, "tidal").value == "Connected as ${LaterServicesStandIn.LISTENER}" }
        assertEquals("Signed in · the GB store", action(tui, "appleMusic").value)
        assertEquals("Your own", action(tui, "appleMusicToken").value)
        assertEquals("${LaterServicesStandIn.LISTENER} · deezer.com/profile/${LaterServicesStandIn.DEEZER_PROFILE}", action(tui, "deezer").value)
        assertEquals("Yours", action(tui, "tidalApp").value)
        SettingsIndex.jump(tui, "appleMusic")
        frame(tui, "signed-in")

        // Signed in, Enter asks before letting go.
        action(tui, "tidal").run()
        assertEquals("Disconnect Tidal?", assertIs<Overlay.Confirm>(tui.overlays.removeLast()).question)
        action(tui, "appleMusic").run()
        assertEquals(listOf("Sign in again, with another token…", "Sign out"), assertIs<Overlay.Picker>(tui.overlays.removeLast()).options.map { it.first })
        action(tui, "deezer").run()
        assertEquals(listOf("Another profile…", "Take the profile out of the library"), assertIs<Overlay.Picker>(tui.overlays.removeLast()).options.map { it.first })
        action(tui, "appleMusicToken").run()
        assertEquals(listOf("Another token of your own…", "Use the web player's again"), assertIs<Overlay.Picker>(tui.overlays.removeLast()).options.map { it.first })
    }

    @Test
    fun `over ssh, Tidal says plainly that its sign-in needs a browser on this computer`() {
        Settings.overSsh = { true }
        val (state, tui) = open(signedIn = false)
        state.configureTidalApplication("made-up-client-id", "")
        waitFor { state.settings.value.tidal.configured }
        val rows = Settings.rows(tui)
        val at = rows.indexOfFirst { it is Row.Action && it.id == "tidal" }
        val note = assertIs<Row.Note>(rows[at + 1])
        assertTrue("Over ssh" in note.text && "browser on this computer" in note.text && "ssh -L 8889:127.0.0.1:8889" in note.text, note.text)
        assertEquals("with a browser here only", (rows[at] as Row.Action).detail)
        // Enter says so in full before anything is opened; Escape leaves it unopened.
        (rows[at] as Row.Action).run()
        val notice = assertIs<Overlay.Notice>(tui.overlays.last())
        assertTrue(notice.paragraphs.single().contains("needs a browser on this computer"), notice.paragraphs.single())
        frame(tui, "tidal-over-ssh")
        tui.press(Input.Key(Keys.ESCAPE))
        assertFalse(state.settings.value.tidal.connecting)
        assertTrue(bridge.opened.isEmpty(), "something was opened: ${bridge.opened}")
    }

    @Test
    fun `ssh is told by what it sets`() {
        assertTrue(TerminalBridge.overSsh { if (it == "SSH_CONNECTION") "10.0.0.2 52000 10.0.0.1 22" else null })
        assertTrue(TerminalBridge.overSsh { if (it == "SSH_TTY") "/dev/pts/3" else null })
        assertFalse(TerminalBridge.overSsh { null })
    }

    @Test
    fun `the three's songs, albums and artists are badged with their own letters, in their own colours`() {
        assertEquals(listOf("AM", "DZ", "TD"), listOf(ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL).map { it.badge() })
        val night = Palette.from(NoctoriumPreferences(theme = ThemePreset.NOCTORIUM_NIGHT), null, false)
        val latte = Palette.from(NoctoriumPreferences(theme = ThemePreset.CATPPUCCIN_LATTE), null, false)
        assertEquals(0xFA243C, night.badgeColour(ProviderType.APPLE_MUSIC))
        // Tidal's black, which a dark page would swallow, is turned to white there.
        assertTrue(luminance(night.badgeColour(ProviderType.TIDAL)) > .8f)
        assertEquals(0x000000, latte.badgeColour(ProviderType.TIDAL))
        listOf(ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL).forEach { provider ->
            assertFalse(provider.keepsLikes, "$provider offers a like that would go nowhere")
            assertEquals(ListKind.ALBUM, LaterServicesStandIn.album(provider).kind)
            assertEquals(ListKind.ARTIST, LaterServicesStandIn.artist(provider).kind)
            assertEquals(ListKind.PLAYLIST, LaterServicesStandIn.playlist(provider).kind)
        }

        val (state, tui) = open(signedIn = false)
        state.setSearchMode(SearchMode.HYBRID)
        for (provider in listOf(ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL)) {
            state.setHybridSearchService(provider, true)
        }
        state.search("tycho")
        waitFor { !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.map { it.provider }.containsAll(listOf(ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL)) }
        tui.searchText = "tycho"
        tui.page = Page.SEARCH
        val shown = frame(tui, "search", h = 60)
        for (letters in listOf("AM", "DZ", "TD")) {
            assertTrue(shown.any { it.contains("Awake") && it.contains(letters) }, "no song badged $letters\n${shown.joinToString("\n")}")
        }
        // Their albums and artists are under what they are, and not again as bare names to search for.
        val rows = Pages.rows(tui)
        val albums = rows.indexOfFirst { it is Row.Header && it.title == "Albums" }
        val artists = rows.indexOfFirst { it is Row.Header && it.title == "Artists" }
        assertTrue(albums in 0 until artists, rows.joinToString("\n"))
        assertTrue(rows.subList(artists, rows.size).none { it is Row.Action && it.label == "Tycho" }, "an artist was listed again by name")
        // Ten services' chips fit 150 columns by name; a narrower terminal names the one in force and letters the rest.
        assertTrue(shown.any { "Apple Music" in it && "Deezer" in it && "Tidal" in it && "VK Music" in it }, shown.joinToString("\n"))
        val narrow = frame(tui, "search-narrow", w = 100, h = 40)
        assertTrue(narrow.any { it.trim().split(Regex("\\s+")).containsAll(listOf("Hybrid", "AM", "DZ", "TD", "VK")) }, narrow.joinToString("\n"))
    }
}
