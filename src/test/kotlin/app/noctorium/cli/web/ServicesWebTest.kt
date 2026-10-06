package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.SpotifyStandIn
import app.noctorium.cli.VkStandIn
import app.noctorium.core.ProviderFilter
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SpotifyPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spotify, VK and the ways of listening as the web player asks for them. The account starts signed in with
 * Spotify Premium, playing Spotify's songs on Spotify; the services are stand-ins, so nothing reaches them.
 */
class ServicesWebTest {
    private val parts = CliParts()
    private val engine = SwitchingEngine(BandcampStandIn.Silent(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val state = BandcampStandIn.state(
        parts,
        engine,
        more = listOf(SpotifyStandIn.Provider(), VkStandIn.Provider()),
        preferences = NoctoriumPreferences(spotifyCanPlay = true, spotifyPlayback = SpotifyPlayback.ON_SPOTIFY),
    )
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

    private val preferences get() = state.settings.value.preferences

    @Test
    fun `how it plays is changed from the page`() {
        assertNull(run("speed", "value" to 1.5))
        assertEquals(1.5f, preferences.playbackSpeed)
        assertNull(run("autoplay", "on" to false))
        assertFalse(preferences.autoplay)
        assertNull(run("sleepFade", "seconds" to 30))
        assertEquals(30, preferences.sleepFadeSeconds)
        assertNull(run("hybridSearch", "provider" to "VK", "on" to false))
        assertFalse(ProviderType.VK in preferences.hybridSearch)
        assertEquals("No such service", run("hybridSearch", "provider" to "NOT_A_SERVICE", "on" to true))
        assertEquals("No speed", run("speed"))
    }

    @Test
    fun `Spotify's songs are played on Spotify or matched, as the page says, and the page is told which`() {
        waitFor { state.settings.value.spotify.canPlay }
        assertNull(run("spotifyPlayback", "onSpotify" to false))
        assertEquals(SpotifyPlayback.MATCHED, preferences.spotifyPlayback)
        assertNull(run("spotifyPlayback", "onSpotify" to true))
        assertEquals(SpotifyPlayback.ON_SPOTIFY, preferences.spotifyPlayback)
        assertNull(run("spotifyDevice", "id" to ""))
        assertEquals("", preferences.spotifyDevice)
        assertEquals("On Spotify or not?", run("spotifyPlayback"))

        state.play(SpotifyStandIn.song, PlaybackOrigin.SEARCH, listOf(SpotifyStandIn.song))
        waitFor { state.playback.value.track?.queueKey == SpotifyStandIn.song.queueKey }
        assertTrue(wire.playback().jsonObject["onSpotify"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `VK's sign-in turns away what is not its two cookies, and its hearts reach the page`() {
        assertEquals("No cookies", run("vkSignIn", "text" to "  "))
        assertNull(run("vkSignIn", "text" to "remixsid=only-one"))
        assertTrue("p and remixsid" in state.settings.value.vk.message.orEmpty(), state.settings.value.vk.message)
        assertNull(run("signOut", "service" to "vk"))
        val likes = wire.likes().jsonObject
        assertFalse(likes["vkReady"]!!.jsonPrimitive.boolean)
        assertFalse(likes["spotifyReady"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `Home's chips and search reach Spotify and VK`() {
        assertNull(run("filter", "filter" to "vk"))
        assertEquals(ProviderFilter.VK, state.ui.value.providerFilter)
        assertNull(run("filter", "filter" to "spotify"))
        assertEquals(ProviderFilter.SPOTIFY, state.ui.value.providerFilter)
        assertNull(run("searchMode", "mode" to "VK"))
        assertEquals("VK", wire.search().jsonObject["mode"]!!.jsonPrimitive.content)
        assertNull(run("searchMode", "mode" to "HYBRID"))
    }
}
