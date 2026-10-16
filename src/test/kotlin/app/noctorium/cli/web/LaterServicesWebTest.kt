package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.LaterServicesStandIn
import app.noctorium.cli.RecordingBridge
import app.noctorium.core.ProviderFilter
import app.noctorium.core.SearchMode
import app.noctorium.domain.BETA_WARNING
import app.noctorium.domain.ProviderType
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.tidal.TidalAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
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
 * Apple Music, Deezer and Tidal as the web player is told of them and asks for them: their settings, the beta warning
 * for every service in beta, Hybrid search's choice of them, and every command their blocks in Settings send. The
 * services are stand-ins and every sign-in is made up and in memory; nothing sent here reaches Apple, Deezer or
 * Tidal -- the ones that would, a profile to check or a token to try, are sent only as what core turns away itself.
 */
class LaterServicesWebTest {
    private val parts = CliParts()
    private val engine = SwitchingEngine(BandcampStandIn.Silent(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val credentials = InMemorySecretStore()
    private val bridge = RecordingBridge()
    private val state = BandcampStandIn.state(parts, engine, more = LaterServicesStandIn.providers(), preferences = NoctoriumPreferences(), system = bridge, credentials = credentials)
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
            fields.forEach { (name, value) -> put(name, if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString())) }
        },
    )

    private fun settings(): JsonObject = wire.settings().jsonObject
    private fun part(name: String): JsonObject = settings()[name]!!.jsonObject
    private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.content
    private fun JsonObject.flag(name: String) = this[name]!!.jsonPrimitive.boolean

    @Test
    fun `the page is told of the three, and warned of every service in beta and of no other`() {
        val warnings = part("betaWarnings")
        assertEquals(setOf("SPOTIFY", "BANDCAMP", "VK", "APPLE_MUSIC", "DEEZER", "TIDAL"), warnings.keys)
        warnings.values.forEach { assertEquals(BETA_WARNING, it.jsonPrimitive.content) }

        val apple = part("appleMusic")
        assertFalse(apple.flag("connected"))
        assertFalse(apple.flag("ownDeveloperToken"))
        assertTrue(apple["how"]!!.jsonArray.any { "media-user-token" in it.jsonPrimitive.content })
        val deezer = part("deezer")
        assertEquals("", deezer.text("profileId"))
        assertFalse(deezer.flag("found"))
        val tidal = part("tidal")
        assertFalse(tidal.flag("configured"))
        assertEquals(TidalAuth.redirectUri(), tidal.text("redirectUri"))
        assertEquals("https://developer.tidal.com/dashboard", tidal.text("developerPortal"))

        // Every service can be chosen for Hybrid search, though a fresh install leaves the three out.
        val services = settings()["hybridServices"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(services.containsAll(listOf("APPLE_MUSIC", "DEEZER", "TIDAL")), services.toString())
        val asked = settings()["hybridSearch"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(asked.none { it in setOf("APPLE_MUSIC", "DEEZER", "TIDAL") }, asked.toString())
        assertNull(run("hybridSearch", "provider" to "DEEZER", "on" to true))
        assertTrue("DEEZER" in settings()["hybridSearch"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `their songs carry their own like keys, and Home and search reach them`() {
        assertEquals("am:1001", likeKeyOf(ProviderType.APPLE_MUSIC, "1001"))
        assertEquals("dz:1001", likeKeyOf(ProviderType.DEEZER, "1001"))
        assertEquals("td:1001", likeKeyOf(ProviderType.TIDAL, "1001"))
        for ((filter, mode) in listOf("apple_music" to "APPLE_MUSIC", "deezer" to "DEEZER", "tidal" to "TIDAL")) {
            assertNull(run("filter", "filter" to filter))
            assertEquals(ProviderFilter.valueOf(mode), state.ui.value.providerFilter)
            assertEquals(filter, wire.home().jsonObject.text("filter"))
            assertNull(run("searchMode", "mode" to mode))
            assertEquals(SearchMode.valueOf(mode), state.ui.value.searchMode)
        }
        assertNull(run("searchMode", "mode" to "HYBRID"))
        assertNull(run("filter", "filter" to "all"))
    }

    @Test
    fun `Apple Music's token and developer token are taken from the page, and let go of`() {
        assertEquals("No token", run("appleMusicSignIn", "text" to " "))
        // Core turns away what is not the token itself, before anything is asked of Apple.
        assertNull(run("appleMusicSignIn", "text" to "Cookie: itua=GB; dslang=GB-EN"))
        waitFor { part("appleMusic").text("message")?.startsWith("That is not an Apple Music sign-in") == true }
        assertFalse(part("appleMusic").flag("connected"))
        assertNull(credentials.get("applemusic.user_token"))

        assertNull(run("appleMusicDeveloperToken", "token" to "made-up-developer-token"))
        waitFor { part("appleMusic").flag("ownDeveloperToken") }
        assertEquals("made-up-developer-token", credentials.get("applemusic.developer_token"))
        assertNull(run("appleMusicDeveloperToken", "token" to ""))
        waitFor { !part("appleMusic").flag("ownDeveloperToken") }
        assertNull(credentials.get("applemusic.developer_token"))
        assertEquals("No token", run("appleMusicDeveloperToken"))

        assertNull(run("signOut", "service" to "applemusic"))
        waitFor { part("appleMusic").text("message") == "Signed out of Apple Music." }
    }

    @Test
    fun `a Tidal app is given and taken back from the page, and signing in without one says what to do`() {
        assertNull(run("tidal"))
        waitFor { part("tidal").text("message")?.contains("developer.tidal.com") == true }
        assertFalse(part("tidal").flag("connecting"))
        assertTrue(bridge.opened.isEmpty(), "something was opened: ${bridge.opened}")

        assertNull(run("tidalApp", "clientId" to "made-up-client-id", "secret" to "made-up-secret"))
        waitFor { part("tidal").flag("configured") }
        val tidal = part("tidal")
        assertTrue(tidal.flag("ownApp"))
        assertTrue(tidal.flag("readsCatalogue"), "with its secret, Tidal is searched signed out")
        assertFalse(tidal.flag("connected"))
        assertEquals("made-up-client-id", credentials.get("tidal.client_id"))
        assertEquals("made-up-secret", credentials.get("tidal.client_secret"))

        assertNull(run("tidalApp", "clientId" to "", "secret" to ""))
        waitFor { !part("tidal").flag("configured") }
        assertNull(credentials.get("tidal.client_id"))
        assertEquals("No client id", run("tidalApp"))

        assertNull(run("signOut", "service" to "tidal"))
        waitFor { part("tidal").text("message") == "Tidal disconnected." }
    }

    @Test
    fun `a Deezer profile is taken out from the page`() {
        assertEquals("No profile", run("deezerProfile"))
        assertNull(run("deezerProfile", "profile" to ""))
        assertEquals("Deezer profile removed.", part("deezer").text("message"))
        assertNull(run("signOut", "service" to "deezer"))
        assertEquals("", state.settings.value.preferences.deezerProfileId)
    }

    @Test
    fun `signed in to the three, the page is told whose they are`() {
        val signed = BandcampStandIn.state(
            parts, engine, more = LaterServicesStandIn.providers(), preferences = LaterServicesStandIn.signedInPreferences,
            system = bridge, credentials = LaterServicesStandIn.signedIn(),
        )
        try {
            val signedWire = Wire(signed, engine)
            fun part(name: String) = signedWire.settings().jsonObject[name]!!.jsonObject
            waitFor { part("tidal").flag("connected") && part("appleMusic").flag("ownDeveloperToken") }
            assertEquals(LaterServicesStandIn.LISTENER, part("tidal").text("account"))
            assertTrue(part("tidal").flag("ownApp"))
            assertTrue(part("appleMusic").flag("connected"))
            assertEquals("gb", part("appleMusic").text("storefront"))
            assertEquals(LaterServicesStandIn.DEEZER_PROFILE, part("deezer").text("profileId"))
            assertEquals(LaterServicesStandIn.LISTENER, part("deezer").text("name"))
            assertTrue(part("deezer").flag("found"))
        } finally {
            signed.close()
        }
    }
}
