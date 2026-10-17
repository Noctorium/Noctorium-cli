package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.domain.Artist
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.onAccent
import app.noctorium.settings.toHexColour
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Glow themes as the page is told them: each with its light in the shapes core draws it in, the colour of
 * writing on every accent as core has it, and Afterglow's light -- and the artwork's accent -- in the colour core
 * finds in the playing cover. The covers are made here and served from this computer; nothing else is fetched.
 */
class GlowThemesWebTest {
    /** A player that keeps what it was asked to play, so the song and its cover are the state's. */
    private class StandIn : PlaybackEngine {
        private val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable
        override suspend fun play(track: Track) = mutable.update { PlaybackState(PlaybackStatus.PLAYING, track, durationMs = track.durationMs ?: 240_000) }
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun setVolume(value: Float) = Unit
        override suspend fun setVolumeBoost(enabled: Boolean) = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun stop() = mutable.update { PlaybackState() }
        override fun close() = Unit
    }

    private val parts = CliParts()
    private val engine = SwitchingEngine(StandIn(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val state = BandcampStandIn.state(parts, engine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The cover's colour the wire is handed, as a [CoverColour] would hand it. */
    private var artwork: Long? = null
    private val wire = Wire(state, engine) { artwork }

    @AfterTest
    fun close() {
        scope.cancel()
        state.close()
    }

    private fun settings(): JsonObject = wire.settings().jsonObject

    private fun themes(): Map<String, JsonObject> =
        settings().getValue("themes").jsonArray.map { it.jsonObject }.associateBy { it.string("name") }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun hex(argb: Long) = argb.toHexColour().lowercase()

    private fun theme(theme: ThemePreset) {
        state.setTheme(theme)
        waitFor { state.settings.value.preferences.theme == theme }
    }

    @Test
    fun `the Glow themes reach the page with their light in core's shapes, and no other theme has one`() {
        val themes = themes()
        assertEquals(ThemePreset.entries.filter { it.colours != null }.map { it.name }, themes.keys.toList())
        ThemePreset.entries.filter { it.colours != null }.forEach { preset ->
            val wired = themes.getValue(preset.name)
            val glow = preset.glow
            if (glow == null) {
                assertFalse("glow" in wired, "${preset.name} has no light")
                return@forEach
            }
            val light = wired.getValue("glow").jsonObject
            assertEquals(hex(glow.colour), light.string("colour"), preset.name)
            assertEquals(glow.followsArtwork, light.getValue("followsArtwork").jsonPrimitive.boolean)
            assertEquals(glow.placement.name, light.string("placement"))
            assertEquals(glow.strength, light.getValue("strength").jsonPrimitive.float)
            assertEquals(glow.placement.shapes.size, light.getValue("shapes").jsonArray.size)
        }
        // Afterglow's two pools, low behind the controls and broad up beside the cover, and Velvet's band.
        val pools = themes.getValue("AFTERGLOW").getValue("glow").jsonObject.getValue("shapes").jsonArray.map { it.jsonObject }
        assertEquals(listOf("pool", "pool"), pools.map { it.string("kind") })
        assertEquals(listOf(.325f, .72f, .21f, .82f), listOf("x", "y", "radius", "strength").map { pools[0].getValue(it).jsonPrimitive.float })
        assertEquals(listOf(.56f, .36f, .34f, 1f), listOf("x", "y", "radius", "strength").map { pools[1].getValue(it).jsonPrimitive.float })
        val band = themes.getValue("VELVET").getValue("glow").jsonObject.getValue("shapes").jsonArray.single().jsonObject
        assertEquals("band", band.string("kind"))
        assertEquals(.62f, band.getValue("depth").jsonPrimitive.float)
        assertFalse("x" in band, "a band is not anywhere across")
    }

    @Test
    fun `writing on the accent is core's, for every theme and for the accent in force`() {
        ThemePreset.entries.filter { it.colours != null }.forEach { preset ->
            assertEquals(hex(onAccent(preset.colours!!.accent)), themes().getValue(preset.name).string("onAccent"), preset.name)
        }
        assertEquals("#292929", themes().getValue("VELVET").string("onAccent"), "near-black on Velvet's white")
        theme(ThemePreset.VELVET)
        state.setAccent(AccentPreset.MINT)
        waitFor { state.settings.value.preferences.accent == AccentPreset.MINT }
        val colours = settings().getValue("colours").jsonObject
        assertEquals(hex(AccentPreset.MINT.argb!!), colours.string("accent"))
        assertEquals(hex(onAccent(AccentPreset.MINT.argb!!)), colours.string("onAccent"), "the accent chosen, not the theme's")
    }

    @Test
    fun `Afterglow's light is in the cover's colour, and the artwork's accent is the cover's colour`() {
        fun colours() = settings().getValue("colours").jsonObject
        fun light() = colours().getValue("glow").jsonObject.string("colour")
        val afterglow = ThemePreset.AFTERGLOW.glow!!
        theme(ThemePreset.AFTERGLOW)
        assertEquals("#c41e0c", light(), "red with nothing to follow")
        artwork = 0xFF2A7AC8
        assertEquals(hex(afterglow.resolvedColour(0xFF2A7AC8)), light())
        assertNotEquals("#c41e0c", light(), "a blue cover's light")
        assertEquals("#fff7f4", colours().string("accent"), "the theme's own white while the accent is the theme's")
        assertEquals("#c41e0c", themes().getValue("AFTERGLOW").getValue("glow").jsonObject.string("colour"), "the list keeps each one's own")

        state.setAccent(AccentPreset.ARTWORK)
        waitFor { state.settings.value.preferences.accent == AccentPreset.ARTWORK }
        assertEquals("#2a7ac8", colours().string("accent"))
        assertEquals(hex(onAccent(0xFF2A7AC8)), colours().string("onAccent"))
        artwork = null
        assertEquals("#fff7f4", colours().string("accent"), "a cover with no colour leaves the theme's")

        // Cinder holds its red whatever is playing, and an older theme has no light to colour.
        artwork = 0xFF2A7AC8
        theme(ThemePreset.CINDER)
        assertEquals("#c41e0c", light())
        theme(ThemePreset.NOCTORIUM_NIGHT)
        assertFalse("glow" in colours())
        assertEquals("#2a7ac8", colours().string("accent"), "Night with the artwork's accent follows the cover too")
    }

    @Test
    fun `the cover's colour is core's, looked for only while something is drawn in it, and follows the song`() {
        val asked = AtomicInteger()
        // Two covers, a red bar down a dark one and a blue one, each 96 pixels across, so nothing is averaged away.
        fun cover(colour: Int): ByteArray {
            val image = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 96) for (x in 0 until 96) image.setRGB(x, y, if (x in 40..56) colour else 0x141414)
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
        val covers = mapOf("/red.png" to cover(0xC0261A), "/blue.png" to cover(0x2A7AC8))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            asked.incrementAndGet()
            val body = covers[exchange.requestURI.path]
            if (body == null) exchange.sendResponseHeaders(404, -1)
            else {
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val artist = Artist("lanterns", "The Paper Lanterns", ProviderType.BANDCAMP)
            fun song(id: String, cover: String) = Track(
                provider = ProviderType.BANDCAMP, id = id, title = "Harbour Lights", artists = listOf(artist), durationMs = 249_000,
                artworkUrl = "$base$cover", sourceUrl = "https://example.invalid/track/$id",
            )
            val red = song("901", "/red.png")
            val blue = song("902", "/blue.png")
            val found = CoverColour(state, scope)
            state.play(red, PlaybackOrigin.SEARCH, listOf(red, blue))
            waitFor { state.playback.value.track?.id == "901" }
            Thread.sleep(400)
            assertNull(found.colour.value, "Night with its own accent draws nothing in the cover's colour")
            assertEquals(0, asked.get(), "and the cover is not even asked for")

            theme(ThemePreset.AFTERGLOW)
            waitFor { found.colour.value != null }
            assertEquals(0xFFC0261A, found.colour.value, "the red bar, as core's ArtworkColour finds it")
            state.play(blue, PlaybackOrigin.SEARCH, listOf(red, blue))
            waitFor { found.colour.value == 0xFF2A7AC8 }

            theme(ThemePreset.VELVET)
            waitFor { found.colour.value == null }
            state.setAccent(AccentPreset.ARTWORK)
            waitFor { found.colour.value == 0xFF2A7AC8 }
            assertTrue(asked.get() <= 2, "each cover fetched once: ${asked.get()}")
        } finally {
            server.stop(0)
        }
    }
}
