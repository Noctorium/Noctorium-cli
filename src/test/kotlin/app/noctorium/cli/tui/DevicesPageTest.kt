package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.settings.ThemePreset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Devices, drawn into frames and read back: Noctorium Connect's switch first, with what on and off mean in the
 * desktop's words; while it is on, this computer's name and the other devices, or what keeps them away; while it is
 * off, a calm word in their place and nothing to put right. Enter, a click or the arrows switch it, and Settings has
 * the same switch by the account. Bandcamp's stand-in is the only service, and no Noctorium account is signed in.
 */
class DevicesPageTest {
    private val parts = CliParts()
    private val state = BandcampStandIn.state(parts)
    private val tui = Tui(state, parts, web = null)

    @AfterTest
    fun close() = state.close()

    private val on get() = state.settings.value.preferences.connect.enabled

    private fun lines(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    private fun render(w: Int = 150, h: Int = 42): List<String> {
        tui.frame(w, h)
        return lines(tui.frame(w, h))
    }

    private fun switch(): Row.Action = assertIs<Row.Action>(Settings.deviceRows(tui).first())

    @Test
    fun `Noctorium Connect's switch is the first thing on Devices, saying what on means`() {
        assertTrue(on, "Connect is on unless it is switched off")
        val rows = Settings.deviceRows(tui)
        val switch = switch()
        assertEquals("Noctorium Connect", switch.label)
        assertEquals("On", switch.value)
        assertEquals("connect", switch.id)
        assertEquals(Row.Note("This computer finds your other devices, and they can find it."), rows[1])
        // Then this computer's name, the other devices -- here, what keeps them away -- and the web player after.
        val name = rows.indexOfFirst { it is Row.Action && it.label == "This device is called" }
        val devices = rows.indexOfFirst { it is Row.Header && it.title == "Your other devices" }
        val web = rows.indexOfFirst { it is Row.Header && it.title == "The web player" }
        assertTrue(name in 2 until devices && devices < web, rows.joinToString("\n"))
        assertTrue(rows.any { it is Row.Note && "needs your Noctorium account" in it.text }, rows.joinToString("\n"))

        tui.page = Page.DEVICES
        val page = render()
        val at = page.indexOfFirst { "Noctorium Connect" in it && "On" in it }
        assertTrue(at in 1..4, page.joinToString("\n"))
        assertTrue("This computer finds your other devices, and they can find it." in page[at + 1], page.joinToString("\n"))
        assertEquals(0, tui.list(Page.DEVICES.name).selected, "The switch is chosen when the page opens")
    }

    @Test
    fun `Enter switches Connect off, and the page then says only that it is off`() {
        tui.page = Page.DEVICES
        render()
        tui.press(Input.Key(Keys.ENTER))
        waitFor { !on }
        val rows = Settings.deviceRows(tui)
        assertEquals("Off", switch().value)
        assertEquals(Row.Note("Off: this computer is not looking for your other devices, and they cannot see it."), rows[1])
        assertTrue(Row.Note("Noctorium Connect is off", Row.Tone.NORMAL) in rows, rows.joinToString("\n"))
        // No name, no list, and nothing to put right: off is a choice, not a fault.
        assertFalse(rows.any { it is Row.Action && it.label == "This device is called" }, rows.joinToString("\n"))
        assertFalse(rows.any { it is Row.Header && it.title == "Your other devices" }, rows.joinToString("\n"))
        assertFalse(rows.any { it is Row.Note && ("account" in it.text || "No other device" in it.text) }, rows.joinToString("\n"))
        // The web player is still there.
        assertTrue(rows.any { it is Row.Action && it.label == "Web player" })

        val page = render()
        assertTrue(page.any { "Noctorium Connect" in it && "Off" in it }, page.joinToString("\n"))
        assertTrue(page.any { "Off: this computer is not looking for your other devices, and they cannot see it." in it }, page.joinToString("\n"))
        assertTrue(page.any { "Noctorium Connect is off" in it }, page.joinToString("\n"))

        // The arrows switch it too, as any setting's.
        tui.press(Input.Key(Keys.RIGHT))
        waitFor { on }
        assertEquals("On", switch().value)
    }

    @Test
    fun `a click on the switch turns Connect off and on`() {
        tui.page = Page.DEVICES
        val page = render()
        val row = page.indexOfFirst { "Noctorium Connect" in it && "On" in it }
        tui.press(Input.Mouse(MouseKind.PRESS, page[row].indexOf("Noctorium Connect"), row))
        waitFor { !on }
        assertTrue(render()[row].contains("Off"))
        tui.press(Input.Mouse(MouseKind.PRESS, page[row].indexOf("Noctorium Connect"), row))
        waitFor { on }
    }

    @Test
    fun `Settings has the same switch by the account, saying what it means while it is chosen`() {
        val rows = Settings.rows(tui)
        val account = rows.indexOfFirst { it is Row.Action && it.id == "account" }
        val connect = assertIs<Row.Action>(rows[account + 1])
        assertEquals("connect", connect.id)
        assertEquals("Noctorium Connect", connect.label)
        assertEquals("On", connect.value)
        assertEquals(Settings.connectSays(true), connect.detail)
        connect.run()
        waitFor { !on }
        val off = Settings.rows(tui).filterIsInstance<Row.Action>().first { it.id == "connect" }
        assertEquals("Off", off.value)
        assertEquals(Settings.connectSays(false), off.detail)
        // One setting, wherever it is switched.
        assertEquals("Off", switch().value)
    }

    @Test
    fun `Devices draws on and off in every kind of theme, from the smallest screen the player allows to a large one`() {
        tui.page = Page.DEVICES
        for (enabled in listOf(true, false)) {
            state.setConnectEnabled(enabled)
            waitFor { on == enabled }
            for (theme in listOf(ThemePreset.NOCTORIUM_NIGHT, ThemePreset.CATPPUCCIN_LATTE, ThemePreset.WINDOWS_98, ThemePreset.WINDOWS_98_NOCTORIUM, ThemePreset.WINDOWS_XP)) {
                state.setTheme(theme)
                waitFor { state.settings.value.preferences.theme == theme }
                for ((w, h) in listOf(40 to 12, 61 to 19, 97 to 33, 220 to 70)) render(w, h)
            }
        }
    }
}
