package app.noctorium.cli.web

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ThemePreset
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
import kotlin.test.assertNull

/**
 * How the player looks, as the web page is told it: every seek bar by name with what it is called, each
 * theme's skin, so the page can draw the Windows themes as themselves, and whether a taskbar shows its clock.
 * The services are stand-ins.
 */
class LooksWebTest {
    private val parts = CliParts()
    private val engine = SwitchingEngine(BandcampStandIn.Silent(), BrowserEngine(parts.backend) { null }, Output.COMPUTER)
    private val state = BandcampStandIn.state(parts, engine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = WebCommands(state, engine, scope) {}
    private val wire = Wire(state, engine)

    @AfterTest
    fun close() {
        scope.cancel()
        state.close()
    }

    private fun run(type: String, name: String): String? =
        commands.run(buildJsonObject { put("type", JsonPrimitive(type)); put("name", JsonPrimitive(name)) })

    private fun settings(): JsonObject = wire.settings().jsonObject

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `the page is told every seek bar, and takes the new ones by name`() {
        val styles = settings().getValue("progressBarStyles").jsonArray.map { it.jsonObject }
        assertEquals(ProgressBarStyle.entries.map { it.name }, styles.map { it.string("name") })
        val ruler = styles.first { it.string("name") == "RULER" }
        assertEquals("Ruler", ruler.string("title"))
        assertEquals(ProgressBarStyle.RULER.description, ruler.string("description"))

        assertNull(run("seekBar", "LUNA"))
        waitFor { state.settings.value.preferences.progressBarStyle == ProgressBarStyle.LUNA }
        assertEquals("LUNA", settings().string("progressBarStyle"))
    }

    @Test
    fun `the page is told whether a taskbar shows its clock, and switches it`() {
        fun clock() = settings().getValue("taskbarClock").jsonPrimitive.boolean
        assertEquals(true, clock())
        assertNull(commands.run(buildJsonObject { put("type", JsonPrimitive("taskbarClock")); put("on", JsonPrimitive(false)) }))
        waitFor { !state.settings.value.preferences.taskbarClock }
        assertEquals(false, clock())
        assertNull(commands.run(buildJsonObject { put("type", JsonPrimitive("taskbarClock")); put("on", JsonPrimitive(true)) }))
        waitFor { state.settings.value.preferences.taskbarClock }
        assertEquals(true, clock())
        assertEquals("On or off?", commands.run(buildJsonObject { put("type", JsonPrimitive("taskbarClock")) }))
    }

    @Test
    fun `each theme says how it is drawn, and the one in force says it too`() {
        val themes = settings().getValue("themes").jsonArray.map { it.jsonObject }.associate { it.string("name") to it.string("skin") }
        assertEquals("WINDOWS_98", themes["WINDOWS_98"])
        assertEquals("WINDOWS_XP", themes["WINDOWS_XP"])
        assertEquals("STANDARD", themes["NOCTORIUM_NIGHT"])
        assertEquals("STANDARD", settings().getValue("colours").jsonObject.string("skin"))

        assertNull(run("theme", "WINDOWS_XP"))
        waitFor { state.settings.value.preferences.theme == ThemePreset.WINDOWS_XP }
        val colours = settings().getValue("colours").jsonObject
        assertEquals("WINDOWS_XP", colours.string("skin"))
        assertEquals("#ece9d8", colours.string("background"))
    }

    @Test
    fun `the 98 themes say which scheme their skin is drawn in, and the one in force says it too`() {
        val themes = settings().getValue("themes").jsonArray.map { it.jsonObject }.associateBy { it.string("name") }
        assertEquals("WINDOWS_98", themes.getValue("WINDOWS_98_NOCTORIUM").string("skin"))
        assertEquals("Noctorium 98", themes.getValue("WINDOWS_98_NOCTORIUM").string("title"))
        val grey = themes.getValue("WINDOWS_98").getValue("windows98").jsonObject
        assertEquals("#c0c0c0", grey.string("face"))
        assertEquals("#000080", grey.string("title"))
        assertEquals("#008080", grey.string("desktop"))
        assertEquals(false, grey.getValue("dark").jsonPrimitive.boolean)
        val night = themes.getValue("WINDOWS_98_NOCTORIUM").getValue("windows98").jsonObject
        assertEquals("#231b2e", night.string("face"))
        assertEquals("#0b0810", night.string("window"))
        assertEquals("#2b0e5c", night.string("title"))
        assertEquals("#8b5cf6", night.string("titleEnd"))
        assertEquals("#140a26", night.string("desktop"))
        assertEquals(true, night.getValue("dark").jsonPrimitive.boolean)
        // Only the 98 skin is drawn from a scheme.
        assertNull(themes.getValue("WINDOWS_XP")["windows98"])
        assertNull(themes.getValue("NOCTORIUM_NIGHT")["windows98"])

        assertNull(run("theme", "WINDOWS_98_NOCTORIUM"))
        waitFor { state.settings.value.preferences.theme == ThemePreset.WINDOWS_98_NOCTORIUM }
        val colours = settings().getValue("colours").jsonObject
        assertEquals("WINDOWS_98", colours.string("skin"))
        assertEquals("#231b2e", colours.getValue("windows98").jsonObject.string("face"))
    }
}
