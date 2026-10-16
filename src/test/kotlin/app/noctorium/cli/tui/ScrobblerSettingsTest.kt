package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.RecordingBridge
import app.noctorium.cli.ScrobblerStandIn
import app.noctorium.cli.ScrobblerStandIn.Sign
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.ScrobbleConnectionStatus
import app.noctorium.settings.SettingsSearch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Last.fm and Libre.fm in Settings: a sign-in waiting to be allowed shows the page to allow Noctorium on, with Copy
 * link and Open again -- the way in when the browser never opened, as on Linux, or cannot, as over ssh -- and Libre.fm
 * connects, says who and lets go as Last.fm does. The sign-ins are made up and kept in memory; nothing reaches either
 * service, and the page and the clipboard are written down rather than opened and filled.
 */
class ScrobblerSettingsTest {
    private val parts = CliParts()
    private val bridge = RecordingBridge()
    private var close: () -> Unit = {}

    @AfterTest
    fun closeState() = close()

    private fun tui(stand: ScrobblerStandIn, preferences: NoctoriumPreferences = NoctoriumPreferences(bandcampUsername = BandcampStandIn.FAN)): Tui {
        val state = BandcampStandIn.state(parts, preferences = preferences, system = bridge, scrobblers = stand.scrobblers)
        close = { state.close() }
        return Tui(state, parts, web = null).also { it.page = Page.SETTINGS }
    }

    private fun Tui.rows() = Settings.rows(this)

    private fun Tui.chosen() = rows()[list(Page.SETTINGS.name).selected] as Row.Action

    @Test
    fun `a sign-in waiting to be allowed shows its page, to copy or open again`() {
        val tui = tui(ScrobblerStandIn(lastFm = Sign.WAITING, libreFm = Sign.WAITING))
        waitFor { tui.state.settings.value.scrobbling.libreFm.approvalUrl != null && tui.state.settings.value.scrobbling.lastFm.approvalUrl != null }
        val lastFmPage = tui.state.settings.value.scrobbling.lastFm.approvalUrl!!
        val libreFmPage = tui.state.settings.value.scrobbling.libreFm.approvalUrl!!
        assertEquals("https://www.last.fm/api/auth/?api_key=${ScrobblerStandIn.APPLICATION}&token=${ScrobblerStandIn.token("lastfm")}", lastFmPage)
        assertTrue(libreFmPage.startsWith("https://libre.fm/api/auth/?api_key=") && libreFmPage.endsWith("&token=${ScrobblerStandIn.token("librefm")}"), libreFmPage)

        // Each service's row, then its page whole, then what to do with it.
        val rows = tui.rows()
        for ((id, page) in listOf("lastfm" to lastFmPage, "librefm" to libreFmPage)) {
            val at = rows.indexOfFirst { it is Row.Action && it.id == id }
            val row = rows[at] as Row.Action
            assertEquals("Allow Noctorium on its page", row.value)
            assertEquals(Row.Tone.WARN, row.tone)
            assertEquals(page, (rows[at + 1] as Row.Note).text)
            assertEquals(listOf("Copy link", "Open again"), rows.subList(at + 2, at + 4).map { (it as Row.Action).label })
            assertTrue(rows.subList(at + 2, at + 4).all { (it as Row.Action).id == "${id}Link" })
        }
        val known = SettingsIndex.ENTRIES.map { it.id }.toSet()
        rows.filterIsInstance<Row.Action>().forEach { assertTrue(it.id in known, "\"${it.label}\" (${it.id}) is not in the search's entries") }

        // Drawn, the page is there to read and select.
        val text = Frames.text(tui.frame(150, 42))
        assertTrue(lastFmPage in text, text)
        assertTrue("Copy link" in text && "Open again" in text, text)

        // Copy link puts that service's page on the clipboard, and Open again opens it.
        SettingsIndex.jump(tui, "librefmLink")
        assertEquals("Copy link", tui.chosen().label)
        tui.press(Input.Key(Keys.ENTER))
        assertEquals(listOf(libreFmPage), bridge.copied)
        tui.press(Input.Key(Keys.DOWN))
        assertEquals("Open again", tui.chosen().label)
        tui.press(Input.Key(Keys.ENTER))
        waitFor { bridge.opened.isNotEmpty() }
        assertEquals(listOf(libreFmPage), bridge.opened)

        SettingsIndex.jump(tui, "lastfmLink")
        tui.press(Input.Key(Keys.ENTER))
        assertEquals(listOf(libreFmPage, lastFmPage), bridge.copied)
    }

    @Test
    fun `with nothing waiting there is no link, and the search goes to the service instead`() {
        val tui = tui(ScrobblerStandIn())
        waitFor { tui.state.settings.value.scrobbling.lastFm.status == ScrobbleConnectionStatus.DISCONNECTED }
        val rows = tui.rows()
        assertTrue(rows.none { it is Row.Action && it.label in setOf("Copy link", "Open again") })
        assertEquals("Not connected", rows.filterIsInstance<Row.Action>().first { it.id == "librefm" }.value)
        SettingsIndex.jump(tui, "librefmLink")
        assertEquals("librefm", tui.chosen().id)
    }

    @Test
    fun `Libre_fm signed in says who, and Enter asks before it lets go`() {
        val stand = ScrobblerStandIn(libreFm = Sign.SIGNED_IN)
        val tui = tui(stand, NoctoriumPreferences(bandcampUsername = BandcampStandIn.FAN, libreFmUsername = ScrobblerStandIn.LISTENER))
        waitFor { tui.state.settings.value.scrobbling.libreFm.status == ScrobbleConnectionStatus.CONNECTED }
        SettingsIndex.jump(tui, "librefm")
        assertEquals("Libre.fm", tui.chosen().label)
        assertEquals("Scrobbling as ${ScrobblerStandIn.LISTENER}", tui.chosen().value)
        assertEquals(Row.Tone.GOOD, tui.chosen().tone)
        // Last.fm is a service of its own, untouched.
        assertEquals("Not connected", tui.rows().filterIsInstance<Row.Action>().first { it.id == "lastfm" }.value)

        tui.press(Input.Key(Keys.ENTER))
        val asked = tui.overlays.lastOrNull()
        assertTrue(asked is Overlay.Confirm, "$asked")
        tui.press(Input.Key(Keys.ENTER))
        waitFor { tui.state.settings.value.scrobbling.libreFm.status == ScrobbleConnectionStatus.DISCONNECTED }
        waitFor { tui.state.settings.value.preferences.libreFmUsername.isBlank() }
        assertNull(stand.credentials.get("librefm.session"), "The sign-in is forgotten")
        assertEquals("Not connected", tui.rows().filterIsInstance<Row.Action>().first { it.id == "librefm" }.value)
    }

    @Test
    fun `Libre_fm and the link are found by what people call them`() {
        fun found(query: String) = SettingsSearch.search(query, SettingsIndex.ENTRIES).map { it.entry.id }
        assertEquals("librefm", found("libre").first())
        assertEquals("librefm", found("libre.fm").first())
        assertEquals("librefm", found("librefm").first())
        assertEquals(listOf("lastfmLink", "librefmLink"), found("copy link").take(2))
        assertTrue("librefm" in found("scrobble"))
        assertEquals("lastfm", found("scrobble").first(), "Last.fm still comes first for scrobbling")
    }
}
