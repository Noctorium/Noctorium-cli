package app.noctorium.cli

import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.Track
import app.noctorium.providers.MusicProvider

/**
 * Spotify as its provider answers a search: songs, and its albums and artists as playlists to open, with
 * "artist:" and "album:" ids and an artist's owner given as "Artist". Nothing reaches Spotify; the songs are
 * Tycho's, as the other stand-ins' are.
 */
object SpotifyStandIn {
    private val tycho = Artist("5oOhM2DFWab8XhSdQiITry", "Tycho", ProviderType.SPOTIFY)
    val song = Track(
        provider = ProviderType.SPOTIFY,
        id = "4uLU6hMCjMI75M1A2tKUQC",
        title = "Awake",
        artists = listOf(tycho),
        durationMs = 283_000,
        sourceUrl = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
    )
    val album = Playlist("album:2HjHO1AbMxjE6FCVyJa7HW", "Awake", ProviderType.SPOTIFY, ownerName = "Tycho", trackCount = 8, sourceUrl = "https://open.spotify.com/album/2HjHO1AbMxjE6FCVyJa7HW")
    val artist = Playlist("artist:5oOhM2DFWab8XhSdQiITry", "Tycho", ProviderType.SPOTIFY, ownerName = "Artist", sourceUrl = "https://open.spotify.com/artist/5oOhM2DFWab8XhSdQiITry")

    class Provider : MusicProvider {
        override val type = ProviderType.SPOTIFY
        override suspend fun getHome(): List<HomeSection> = emptyList()
        override suspend fun search(query: String) = SearchResults(tracks = listOf(song), artists = listOf(tycho), playlists = listOf(album, artist))
        override suspend fun getTrack(id: String): Track? = null
        override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()
        override suspend fun getPlaylistTracks(playlist: Playlist) = listOf(song)
    }
}

/**
 * VK as its provider lists a signed-in account: My music first, then its playlists, and songs whose addresses
 * carry VK's access key after a `#`. The account and its playlist are made up.
 */
object VkStandIn {
    const val ACCOUNT = "Some Listener"

    val song = Track(
        provider = ProviderType.VK,
        id = "-2001_1001",
        title = "Awake",
        artists = listOf(Artist("Tycho", "Tycho", ProviderType.VK)),
        durationMs = 283_000,
        sourceUrl = "https://vk.ru/audio-2001_1001#vk-access=k1",
    )
    val myMusic = Playlist("my-music", "My music", ProviderType.VK, ownerName = "VK", sourceUrl = "https://vk.ru/audios101")
    val evening = Playlist("playlist:101_7", "Evening", ProviderType.VK, ownerName = ACCOUNT, trackCount = 12, sourceUrl = "https://vk.ru/music/playlist/101_7")

    class Provider : MusicProvider {
        override val type = ProviderType.VK
        override suspend fun getHome(): List<HomeSection> = emptyList()
        override suspend fun search(query: String) = SearchResults(tracks = listOf(song))
        override suspend fun getTrack(id: String): Track? = null
        override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()
        override suspend fun getLibraryPlaylists() = listOf(myMusic, evening)
        override suspend fun getPlaylistTracks(playlist: Playlist) = listOf(song)
    }
}
