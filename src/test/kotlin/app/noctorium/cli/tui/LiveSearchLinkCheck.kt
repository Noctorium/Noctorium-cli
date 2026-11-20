package app.noctorium.cli.tui

import app.noctorium.cli.CliParts
import app.noctorium.cli.cliAppState
import app.noctorium.cli.web.WebPlayer
import app.noctorium.playback.PlaybackStatus
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A music link typed into the terminal's search box a key at a time, then Enter: the song plays, through the CLI's
 * whole AppState, yt-dlp and mpv. Off unless asked for:
 *
 *     ./gradlew :test --tests "*LiveSearchLinkCheck*" -Dnoctorium.live=true
 */
class LiveSearchLinkCheck {

    @Test
    fun `a link typed into search and Enter plays`() {
        assumeTrue(System.getProperty("noctorium.live") == "true", "live test: -Dnoctorium.live=true")
        val parts = CliParts()
        val engine = WebPlayer.engineFor(parts, preferBrowser = false)
        val state = cliAppState(parts, engine)
        val tui = Tui(state, parts, web = null)
        try {
            tui.page = Page.SEARCH
            listOf(
                "https://soundcloud.com/flume/never-be-like-you-feat-kai",
                "https://music.youtube.com/watch?v=PzYrr7K1dvU",
            ).forEach { link ->
                val before = state.playback.value.track?.queueKey
                tui.searchText = ""
                tui.searchFocused = true
                link.forEach { Pages.searchInput(tui, Input.Text(it.toString())) }
                Pages.searchInput(tui, Input.Key(Keys.ENTER))
                val until = System.currentTimeMillis() + 60_000
                while (System.currentTimeMillis() < until &&
                    !(state.playback.value.track?.queueKey != before && state.playback.value.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.ERROR))
                ) Thread.sleep(250)
                val now = state.playback.value
                println("LINKCHECK $link: ${now.status} ${now.track?.provider} ${now.track?.title} — ${now.track?.artistLine} ${now.errorMessage ?: ""}")
                assertEquals(PlaybackStatus.PLAYING, now.status, "$link: ${now.errorMessage} / ${state.linkState.value.message}")
                kotlin.test.assertNotEquals(before, now.track?.queueKey, "$link: its song never started")
            }
        } finally {
            state.close()
        }
    }
}
