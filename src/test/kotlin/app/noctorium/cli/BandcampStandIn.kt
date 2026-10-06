package app.noctorium.cli

import app.noctorium.core.AppState
import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.providers.MusicProvider
import app.noctorium.settings.AppDirectories
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files

/**
 * The terminal's AppState with a stand-in for Bandcamp as its only service, and a fan's name already in a
 * settings file of its own: what the pages make of Bandcamp can then be looked at with nothing on the network.
 *
 * The stand-in answers the way Bandcamp's provider does -- albums and artists as playlists as well as by name,
 * a fan's collection and wishlist for the library -- with Tycho's Awake, which is what the core's own tests use.
 */
object BandcampStandIn {
    const val FAN = "somefan"

    val artist = Artist("338921882", "Tycho", ProviderType.BANDCAMP)
    val song = Track(
        provider = ProviderType.BANDCAMP,
        id = "148177487",
        title = "Awake",
        artists = listOf(artist),
        durationMs = 283_000,
        sourceUrl = "https://tycho.bandcamp.com/track/awake#bandcamp-track=148177487&band=338921882",
    )
    val album = Playlist(
        "album:338921882:2414419453", "Awake", ProviderType.BANDCAMP, ownerName = "Tycho",
        sourceUrl = "https://tycho.bandcamp.com/album/awake", trackCount = 8,
    )
    val band = Playlist("band:338921882", "Tycho", ProviderType.BANDCAMP, ownerName = "San Francisco, California", sourceUrl = "https://tycho.bandcamp.com")
    val wishlist = Playlist("wishlist:42", "Wishlist", ProviderType.BANDCAMP, ownerName = "Some Fan", sourceUrl = "https://bandcamp.com/$FAN/wishlist")

    private class Provider : MusicProvider {
        override val type = ProviderType.BANDCAMP
        override suspend fun getHome() = listOf(
            HomeSection("bandcamp:top", "Best-selling on Bandcamp", ProviderType.BANDCAMP, "Bandcamp", tracks = listOf(song)),
        )
        override suspend fun search(query: String) = SearchResults(
            tracks = listOf(song),
            artists = listOf(artist),
            albums = listOf(Album("2414419453", "Awake", listOf(artist), ProviderType.BANDCAMP)),
            playlists = listOf(album, band),
        )
        override suspend fun getTrack(id: String): Track? = null
        override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()
        override suspend fun getLibraryPlaylists() = listOf(wishlist, album)
        override suspend fun getPlaylistTracks(playlist: Playlist) = listOf(song)
    }

    /** A player that plays nothing. */
    class Silent : PlaybackEngine {
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

    /**
     * Built as [cliAppState] builds the real one, but with stand-ins for its services and a settings file of its
     * own, made afresh from [preferences] for each one. Its own, not shared: AppState saves in the background,
     * and a save the last test started can still be under way when the next one begins.
     * [more] are stand-ins for other services beside Bandcamp's; see [SpotifyStandIn] and [VkStandIn].
     */
    fun state(
        parts: CliParts,
        engine: PlaybackEngine = Silent(),
        more: List<MusicProvider> = emptyList(),
        preferences: NoctoriumPreferences = NoctoriumPreferences(bandcampUsername = FAN),
    ): AppState {
        val folder = AppDirectories.resolve("stand-in-settings") ?: error("No folder for the test's settings")
        Files.createDirectories(folder)
        val settings = SettingsRepository(Files.createTempDirectory(folder, "state").resolve("settings.json")).also { it.save(preferences) }
        return AppState(
            ytDlp = parts.backend,
            credentials = parts.credentials,
            system = parts.bridge,
            downloads = parts.downloads,
            playbackEngine = engine,
            injectedProviders = listOf<MusicProvider>(Provider()) + more,
            settingsRepository = settings,
            checkForUpdatesAtLaunch = false,
        )
    }

    /** Waits, ten seconds at most, for [done]; the stand-in answers at once, but core answers in its own time. */
    fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(50)
        check(done()) { "Waited ten seconds and it did not happen" }
    }
}
