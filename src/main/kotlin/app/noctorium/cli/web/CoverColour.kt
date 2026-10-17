package app.noctorium.cli.web

import app.noctorium.cli.tui.Art
import app.noctorium.core.AppState
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.ArtworkColour
import app.noctorium.settings.NoctoriumPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The playing cover's colour, for the page: what "Match the artwork" takes for the accent, and Afterglow its light
 * from.
 *
 * Found here rather than by the page, with core's [ArtworkColour] over a small copy of the cover, so that it is the
 * very colour the desktop finds from the same cover; and because a cover is another site's picture, whose pixels a
 * page may only read where that site says it may, which not every service does. Looked for only while something
 * wants it -- the artwork's accent, or a light that follows the artwork -- and kept from one song until the next
 * one's cover has arrived, so the page eases from one colour to the next rather than through the theme's.
 */
class CoverColour(private val state: AppState, scope: CoroutineScope) {
    private val art = Art { refresh() }
    private val mutable = MutableStateFlow<Long?>(null)

    /** The colour, as ARGB; null with nothing wanting it, nothing playing, no cover, or a cover with no colour in it. */
    val colour: StateFlow<Long?> = mutable

    init {
        scope.launch {
            combine(
                state.playback.map { it.track?.artworkUrl }.distinctUntilChanged(),
                state.settings.map { wanted(it.preferences) }.distinctUntilChanged(),
            ) { _, _ -> }.collect { refresh() }
        }
    }

    private fun refresh() {
        val url = state.playback.value.track?.artworkUrl
        if (url.isNullOrBlank() || !wanted(state.settings.value.preferences)) {
            mutable.value = null
            return
        }
        // Still on its way: the last song's colour stands until Art calls back with this one's.
        val pixels = art.get(url) ?: return
        mutable.value = ArtworkColour.of(pixels.rgb, pixels.side, pixels.side)
    }

    companion object {
        /** Whether anything on the page is drawn in the cover's colour. */
        fun wanted(preferences: NoctoriumPreferences): Boolean =
            preferences.accent == AccentPreset.ARTWORK || preferences.theme.glow?.followsArtwork == true
    }
}
