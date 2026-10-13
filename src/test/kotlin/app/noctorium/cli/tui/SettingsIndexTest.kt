package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.CliParts
import app.noctorium.settings.SettingsSearch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings in its sections, and the search over them: every setting the page shows is one the search knows, the
 * other words people use find the right one, and choosing one goes to it.
 */
class SettingsIndexTest {
    private val parts = CliParts()
    private val state = BandcampStandIn.state(parts)
    private val tui = Tui(state, parts, web = null)

    @AfterTest
    fun close() {
        tui.preferences.playerBar = TuiPlayerBar.FULL
        state.close()
    }

    private fun actions(): List<Row.Action> = Settings.rows(tui).filterIsInstance<Row.Action>()

    @Test
    fun `every setting has an entry, and every entry is a setting`() {
        val known = SettingsIndex.ENTRIES.map { it.id }
        assertEquals(known.distinct(), known, "Each entry once")
        // The clock's row is there with the Taskbar, and the buttons' with Toolbar 2; the rest are there always, or
        // shown with another.
        val seen = (
            actions() +
                run { tui.preferences.playerBar = TuiPlayerBar.TASKBAR; actions() } +
                run { tui.preferences.playerBar = TuiPlayerBar.TOOLBAR_2; actions() }
            ).toMutableList()
        assertTrue(seen.any { it.id == "playerButtons" }, "Toolbar 2's buttons are never offered")
        seen.forEach { row ->
            val id = row.id
            assertTrue(id != null, "\"${row.label}\" has no id for the search")
            assertTrue(id in known, "\"${row.label}\" ($id) is not in the search's entries")
        }
        val ids = seen.mapNotNull { it.id }.toSet()
        SettingsIndex.ENTRIES.forEach { entry ->
            val shownWith = SettingsIndex.SHOWN_WITH[entry.id]?.first
            assertTrue(entry.id in ids || shownWith in ids, "\"${entry.title}\" (${entry.id}) is never on the page, nor shown with another that is")
            assertTrue(entry.section in Settings.SECTIONS, "\"${entry.title}\" is in no section: ${entry.section}")
        }
    }

    @Test
    fun `the sections are in their order`() {
        val headers = Settings.rows(tui).filterIsInstance<Row.Header>().map { it.title }
        assertEquals(Settings.SECTIONS, headers)
        // Each setting is listed under the section its entry names.
        var section = ""
        Settings.rows(tui).forEach { row ->
            if (row is Row.Header) section = row.title
            if (row is Row.Action) assertEquals(SettingsIndex.ENTRIES.first { it.id == row.id }.section, section, row.label)
        }
    }

    @Test
    fun `the other words for a setting find it first`() {
        fun first(query: String) = SettingsSearch.search(query, SettingsIndex.ENTRIES).firstOrNull()?.entry?.id
        assertEquals("theme", first("dark mode"))
        assertEquals("booster", first("louder"))
        assertEquals("booster", first("boost"))
        assertEquals("keys", first("hotkeys"))
        assertEquals("equaliser", first("equalizer"))
        assertEquals("visualiser", first("visualizer"))
        assertEquals("seekBar", first("progress bar"))
        assertEquals("glyphs", first("nerd font"))
        assertEquals("homePlay", first("playing a song"))
        assertEquals("playerBar", first("toolbar"))
        assertEquals("playerBar", first("toolbar 2"))
        assertEquals("playerButtons", first("stop button"))
        assertEquals("playerButtons", first("hide buttons"))
        // A typo forgiven in a long word.
        assertEquals("equaliser", first("equaliserr"))
        assertEquals(null, first("nothing like any of them xyzzy"))
    }

    @Test
    fun `the search key opens the box, what is typed is found, and Enter goes to it`() {
        tui.page = Page.SETTINGS
        tui.press(Input.Text("/"))
        assertEquals(Page.SETTINGS, tui.page, "The search key finds a setting here, rather than going to Search")
        assertTrue(tui.settingsSearchFocused)
        "louder".forEach { tui.press(Input.Text(it.toString())) }
        val found = Pages.rows(tui).filterIsInstance<Row.Action>()
        assertEquals("Volume booster", found.first().label)
        assertEquals(Settings.LISTENING, found.first().value)

        tui.frame(150, 42)
        val canvas = tui.frame(150, 42)
        val text = (0 until canvas.height).joinToString("\n") { y -> (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " } }
        assertTrue("louder" in text && "Volume booster" in text, text)

        tui.press(Input.Key(Keys.ENTER))
        assertNull(tui.settingsQuery)
        val rows = Settings.rows(tui)
        val chosen = rows[tui.list(Page.SETTINGS.name).selected]
        assertEquals("booster", (chosen as Row.Action).id)

        // Escape puts the box away and Settings is as it was.
        tui.press(Input.Text("/"))
        tui.press(Input.Text("x"))
        tui.press(Input.Key(Keys.ESCAPE))
        assertNull(tui.settingsQuery)
        assertTrue(Pages.rows(tui).filterIsInstance<Row.Header>().map { it.title } == Settings.SECTIONS)
    }

    @Test
    fun `a setting shown only some of the time goes to the one it is shown with`() {
        tui.page = Page.SETTINGS
        tui.preferences.playerBar = TuiPlayerBar.FULL
        SettingsIndex.jump(tui, "taskbarClock")
        val chosen = Settings.rows(tui)[tui.list(Page.SETTINGS.name).selected] as Row.Action
        assertEquals("playerBar", chosen.id)
    }
}
