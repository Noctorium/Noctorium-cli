package app.noctorium.cli

import app.noctorium.core.AppState
import app.noctorium.domain.Track
import app.noctorium.lyrics.LyricLine
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.lyrics.LyricsProviderOutcome
import app.noctorium.lyrics.LyricsProviderStatus
import app.noctorium.lyrics.LyricsResult
import app.noctorium.lyrics.LyricsUiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Lyrics for the tests and the pictures, made up line by line, and a way to put them in front of AppState as if the
 * sources had found them. Core asks the real sources, which know nothing of the stand-ins' made-up songs -- and a
 * real song's words have no place in a test -- so its state is set as their answer would leave it, under the key core
 * looks a song's lyrics up by, so that showing them does not ask again.
 */
object LyricsStandIn {
    val words = listOf(
        "Lamps along the water", "counting boats that never land", "the radio hums low", "and the tide keeps every promise",
        "salt upon the window", "static in the night", "we drift until the morning", "and the harbour lights come on",
        "paper in the rigging", "a lantern for the gulls", "the ferry sounds its leaving", "and the night lets go of us",
        "morning on the jetty", "nothing left to say",
    )

    /** When line [index] is sung: six and a half seconds apart from 0:20, so that the twelfth is at 1:34. */
    fun startMs(index: Int): Long = 20_000L + index * 6_500L

    /** [track]'s lyrics in [state], as LRCLIB's synced answer, with Genius wanting a key beside it. */
    fun set(state: AppState, track: Track) {
        @Suppress("UNCHECKED_CAST")
        val lyrics = AppState::class.java.getDeclaredField("mutableLyrics").apply { isAccessible = true }.get(state) as MutableStateFlow<LyricsUiState>
        val lines = words.mapIndexed { i, text -> LyricLine(text, startMs(i)) }
        lyrics.value = LyricsUiState(
            trackKey = "${track.queueKey}|${track.title}|${track.artistLine}|${track.durationMs ?: 0}",
            outcomes = listOf(
                LyricsProviderOutcome(LyricsProviderId.LRCLIB, LyricsProviderStatus.FOUND, LyricsResult(LyricsProviderId.LRCLIB, lines, synced = true)),
                LyricsProviderOutcome(LyricsProviderId.GENIUS, LyricsProviderStatus.NEEDS_KEY, detail = "Needs a key"),
            ),
            selectedProvider = LyricsProviderId.LRCLIB,
        )
    }
}
