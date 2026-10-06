package app.noctorium.cli.tui

import app.noctorium.domain.PlaybackOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** `noctorium play <something>`: the terminal opens with it already playing. */
object Startup {
    suspend fun playFirst(tui: Tui, query: String) {
        val state = tui.state
        val text = query.trim()
        if (Pages.looksLikeLink(text)) {
            state.openLink(text)
            tui.toast("Opening the link…")
            return
        }
        tui.page = Page.SEARCH
        tui.searchText = text
        tui.toast("Looking for \"$text\"…")
        state.search(text)
        val found = withTimeoutOrNull(25_000) {
            // Wait for this search to have begun and then finished, not for the empty state before it.
            while (!(state.ui.value.searchQuery == text && !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty())) {
                delay(150)
            }
            state.ui.value.searchResults.tracks
        }
        if (found.isNullOrEmpty()) {
            tui.toast("Nothing found for \"$text\"", Row.Tone.WARN)
            return
        }
        state.play(found.first(), PlaybackOrigin.SEARCH, found)
        tui.page = Page.NOW_PLAYING
        tui.invalidate()
    }
}
