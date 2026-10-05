package app.noctorium.cli.tui

import app.noctorium.cli.CliParts
import app.noctorium.cli.cliAppState
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Updating, as Settings shows it: the check by hand, the daily switch, and what a build is told. */
class SettingsUpdatesTest {
    private class Silent : PlaybackEngine {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override suspend fun play(track: Track) = Unit
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun setVolume(value: Float) = Unit
        override suspend fun setVolumeBoost(enabled: Boolean) = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun stop() = Unit
        override fun close() = Unit
    }

    private val parts = CliParts()
    private val state = cliAppState(parts, Silent())
    private val tui = Tui(state, parts, web = null)

    @AfterTest
    fun close() {
        // Put back as the next test expects, and wait for it to reach the file: AppState saves in the
        // background, and closing straight away would leave the last test's choice behind for the next run.
        state.setUpdateCheckOnLaunch(true)
        val file = app.noctorium.settings.AppDirectories.resolve("settings.json")
        val until = System.currentTimeMillis() + 5_000
        while (file != null && System.currentTimeMillis() < until &&
            runCatching { java.nio.file.Files.readString(file) }.getOrDefault("").replace(" ", "").contains("\"checkOnLaunch\":false")
        ) Thread.sleep(50)
        state.close()
    }

    private fun action(label: String) = Settings.rows(tui).filterIsInstance<Row.Action>().firstOrNull { it.label == label }

    @Test
    fun `a build is told about releases, says how it should be updated, and the daily look can be switched off`() {
        // The tests run from Gradle, which is exactly the copy that must not replace itself.
        state.setUpdateCheckOnLaunch(true)
        val check = assertNotNull(action("Check for updates"))
        assertEquals(app.noctorium.cli.cliVersion, check.value)
        val daily = assertNotNull(action("Look for updates once a day"), "an unmanaged copy offered to update itself")
        assertTrue(daily.value == "On" || daily.value!!.startsWith("Off: "), daily.value)
        assertTrue(Settings.rows(tui).filterIsInstance<Row.Note>().any { "build" in it.text }, "nothing said how to update a build")

        if (daily.value == "On") {
            daily.step!!.invoke(1)
            assertEquals(false, state.settings.value.preferences.updates.checkOnLaunch)
            assertEquals("Off", action("Look for updates once a day")!!.value)
        }
    }

    @Test
    fun `the terminal's AppState does not ask GitHub at launch`() {
        // CliUpdates does that, once a day, for the commands that stay open; AppState would ask every start.
        assertEquals(false, state.updates.value.checking)
        Thread.sleep(300)
        assertEquals(null, state.updates.value.available)
        assertEquals(false, state.updates.value.checking)
    }
}
