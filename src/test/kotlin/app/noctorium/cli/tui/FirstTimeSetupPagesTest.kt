package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.CliParts
import app.noctorium.settings.SetupStep
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-time setup in the terminal: Home shows it, a step at a time, until it is finished or skipped, and then
 * Home is Home. With `-Dnoctorium.renderTui=<folder>` each frame is also written there.
 */
class FirstTimeSetupPagesTest {
    private val parts = CliParts()
    private val state = BandcampStandIn.state(parts)
    private val tui = Tui(state, parts, web = null)
    private val folder = System.getProperty("noctorium.renderTui")?.let(::File)?.also { it.mkdirs() }

    private fun screen(name: String): String {
        val canvas = tui.frame(120, 34)
        folder?.let { Frames.write(it, "setup-$name", canvas) }
        return Frames.text(canvas)
    }

    @Test
    fun `Home shows the setup a step at a time until it is finished or skipped`() {
        // As a first start leaves it: not done yet.
        state.settings.value.preferences.let { if (it.setupDone) state.finishSetup() }
        val undone = state.settings.value.copy(preferences = state.settings.value.preferences.copy(setupDone = false))
        val field = state.javaClass.getDeclaredField("mutableSettings").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(state) as kotlinx.coroutines.flow.MutableStateFlow<app.noctorium.settings.SettingsState>).value = undone
        tui.page = Page.HOME

        SetupStep.entries.forEach { step ->
            tui.setupStep = step
            val shown = screen(step.name.lowercase())
            assertTrue(shown.contains(step.title, ignoreCase = true), "${step.name} shows its title")
            assertTrue(SetupStep.progress(step) in shown)
            if (step != SetupStep.DONE) assertTrue("Skip setup" in shown)
        }
        state.finishSetup()
        val home = screen("after")
        assertFalse(home.contains(SetupStep.WELCOME.title, ignoreCase = true), "finished, Home is Home")
        assertTrue(state.settings.value.preferences.setupDone)
    }
}
