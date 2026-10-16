package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.RecordingBridge
import app.noctorium.cli.ScrobblerStandIn
import app.noctorium.cli.ScrobblerStandIn.Sign
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.ScrobbleConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Last.fm and Libre.fm as `noctorium web` tells the page of them and takes from it: each one's state, with the page
 * to allow Noctorium on while a sign-in waits, for the listener's own browser to open or copy; and Libre.fm finished
 * and let go of as Last.fm is. The sign-ins are made up and in memory, so nothing reaches either service -- which is
 * why starting one is not asked for here: that asks the service for a token.
 */
class ScrobblersWebTest {
    private val parts = CliParts()
    private val engine = SwitchingEngine(BandcampStandIn.Silent(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var close: () -> Unit = {}

    @AfterTest
    fun closeState() {
        scope.cancel()
        close()
    }

    private class Page(val state: app.noctorium.core.AppState, val commands: WebCommands, val wire: Wire) {
        fun run(type: String, vararg fields: Pair<String, String>): String? = commands.run(
            buildJsonObject {
                put("type", JsonPrimitive(type))
                fields.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
            },
        )

        fun service(name: String): JsonObject = wire.settings().jsonObject.getValue(name).jsonObject
    }

    private fun page(stand: ScrobblerStandIn, preferences: NoctoriumPreferences = NoctoriumPreferences(bandcampUsername = BandcampStandIn.FAN)): Page {
        val state = BandcampStandIn.state(parts, engine, preferences = preferences, system = RecordingBridge(), scrobblers = stand.scrobblers)
        close = { state.close() }
        return Page(state, WebCommands(state, engine, scope) {}, Wire(state, engine))
    }

    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content

    @Test
    fun `a sign-in waiting to be allowed carries its page, each service its own`() {
        val page = page(ScrobblerStandIn(lastFm = Sign.WAITING, libreFm = Sign.WAITING))
        waitFor { page.state.settings.value.scrobbling.libreFm.approvalUrl != null && page.state.settings.value.scrobbling.lastFm.approvalUrl != null }
        val lastFm = page.service("lastfm")
        assertEquals("awaiting_approval", lastFm.string("status"))
        assertEquals("https://www.last.fm/api/auth/?api_key=${ScrobblerStandIn.APPLICATION}&token=${ScrobblerStandIn.token("lastfm")}", lastFm.string("approvalUrl"))
        val libreFm = page.service("librefm")
        assertEquals("awaiting_approval", libreFm.string("status"))
        val libreFmPage = assertNotNull(libreFm.string("approvalUrl"))
        assertTrue(libreFmPage.startsWith("https://libre.fm/api/auth/?api_key=") && libreFmPage.endsWith("&token=${ScrobblerStandIn.token("librefm")}"), libreFmPage)
        assertNotNull(libreFm.string("message"), "What core says of the wait comes along")
        // ListenBrainz signs in with a token, and has no page.
        assertFalse("approvalUrl" in page.service("listenbrainz"))
    }

    @Test
    fun `Libre_fm is let go of from the page, and Last_fm left as it was`() {
        val stand = ScrobblerStandIn(lastFm = Sign.SIGNED_IN, libreFm = Sign.SIGNED_IN)
        val page = page(stand, NoctoriumPreferences(bandcampUsername = BandcampStandIn.FAN, lastFmUsername = "another-listener", libreFmUsername = ScrobblerStandIn.LISTENER))
        waitFor { page.state.settings.value.scrobbling.libreFm.status == ScrobbleConnectionStatus.CONNECTED }
        assertEquals("connected", page.service("librefm").string("status"))
        assertEquals(ScrobblerStandIn.LISTENER, page.service("librefm").string("username"))
        assertNull(page.service("librefm").string("approvalUrl"), "No page once signed in")

        assertNull(page.run("signOut", "service" to "librefm"))
        waitFor { page.state.settings.value.scrobbling.libreFm.status == ScrobbleConnectionStatus.DISCONNECTED }
        waitFor { page.state.settings.value.preferences.libreFmUsername.isBlank() }
        assertEquals("disconnected", page.service("librefm").string("status"))
        assertNull(page.service("librefm").string("username"))
        assertNull(stand.credentials.get("librefm.session"), "Libre.fm's sign-in is forgotten")

        assertEquals("connected", page.service("lastfm").string("status"))
        assertEquals("another-listener", page.service("lastfm").string("username"))
        assertEquals("made-up-lastfm-session", stand.credentials.get("lastfm.session"), "Last.fm's is kept")
    }

    @Test
    fun `Finish asks after Libre_fm's own sign-in, not Last_fm's`() {
        // Last.fm waits to be allowed; Libre.fm was never begun, so finishing it has nothing to ask Libre.fm about.
        val page = page(ScrobblerStandIn(lastFm = Sign.WAITING))
        waitFor { page.state.settings.value.scrobbling.lastFm.approvalUrl != null }
        assertNull(page.run("librefmFinish"))
        waitFor { "Start Libre.fm sign-in first" in page.state.settings.value.message.orEmpty() }
        assertEquals("awaiting_approval", page.service("lastfm").string("status"))
        assertNotNull(page.service("lastfm").string("approvalUrl"), "Last.fm's sign-in still waits, its page kept")
    }
}
