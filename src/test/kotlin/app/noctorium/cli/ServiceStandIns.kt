package app.noctorium.cli

import app.noctorium.domain.Artist
import app.noctorium.domain.HomeSection
import app.noctorium.domain.PlaybackContext
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.Track
import app.noctorium.platform.SystemBridge
import app.noctorium.providers.MusicProvider
import app.noctorium.scrobble.ScrobbleManager
import app.noctorium.settings.InMemorySecretStore
import app.noctorium.settings.NoctoriumPreferences
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Last.fm and Libre.fm as the credential store leaves them, with nothing ever asked of either: a sign-in waiting to be
 * allowed, or one made, under made-up tokens and a made-up Last.fm application. Core reads which from the store as it
 * opens, so the pages, the wire and the commands that only let go of a sign-in can be looked at off the network.
 *
 * Nothing played beside one of these may say it is playing: a scrobbler signed in would be sent the song.
 */
class ScrobblerStandIn(lastFm: Sign = Sign.NONE, libreFm: Sign = Sign.NONE) {
    enum class Sign { NONE, WAITING, SIGNED_IN }

    val credentials = InMemorySecretStore()

    init {
        // An application of the listener's own, as Settings can be given one: so a stored Last.fm sign-in is taken
        // as made through the application this build signs in as, and the page to allow it on carries its key.
        credentials.put("lastfm.api_key", APPLICATION)
        credentials.put("lastfm.shared_secret", "fedcba9876543210fedcba9876543210")
        keep("lastfm", lastFm)
        keep("librefm", libreFm)
    }

    val scrobblers = ScrobbleManager(credentials)

    private fun keep(name: String, sign: Sign) = when (sign) {
        Sign.WAITING -> credentials.put("$name.pending", token(name))
        Sign.SIGNED_IN -> credentials.put("$name.session", "made-up-$name-session")
        Sign.NONE -> Unit
    }

    companion object {
        /** Who a made-up sign-in is, kept in the settings as core keeps it. */
        const val LISTENER = "sample-listener"
        const val APPLICATION = "0123456789abcdef0123456789abcdef"

        /** The token of a made-up sign-in waiting to be allowed on [name], "lastfm" or "librefm". */
        fun token(name: String) = "made-up-$name-token"
    }
}

/** What opening a page and the clipboard do, written down rather than done. */
class RecordingBridge : SystemBridge {
    val opened = CopyOnWriteArrayList<String>()
    val copied = CopyOnWriteArrayList<String>()
    override fun openUrl(url: String) { opened += url }
    override fun copyToClipboard(text: String) { copied += text }
    override fun defaultExportFolder(): Path? = null
}

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

/**
 * Apple Music, Deezer and Tidal as their providers answer a search -- a song each, and an album and an artist to open
 * as playlists, with the "album:" and "artist:" ids all three give them -- and a library of a playlist each. Nothing
 * reaches any of them: the songs are Tycho's, as the other stand-ins' are, and the accounts, the Tidal app and the
 * tokens are made up and kept in memory.
 */
object LaterServicesStandIn {
    const val LISTENER = "Some Listener"
    const val DEEZER_PROFILE = "1234567"

    private val pages = mapOf(
        ProviderType.APPLE_MUSIC to "https://music.apple.com/gb",
        ProviderType.DEEZER to "https://www.deezer.com",
        ProviderType.TIDAL to "https://tidal.com/browse",
    )

    fun song(provider: ProviderType) = Track(
        provider = provider,
        id = "1001",
        title = "Awake",
        artists = listOf(Artist("2002", "Tycho", provider)),
        durationMs = 283_000,
        sourceUrl = "${pages.getValue(provider)}/track/1001",
    )

    fun album(provider: ProviderType) = Playlist("album:3003", "Awake", provider, ownerName = "Tycho", trackCount = 8, sourceUrl = "${pages.getValue(provider)}/album/3003")
    fun artist(provider: ProviderType) = Playlist("artist:2002", "Tycho", provider, ownerName = "Artist", sourceUrl = "${pages.getValue(provider)}/artist/2002")
    fun playlist(provider: ProviderType) = Playlist("playlist:4004", "Evening", provider, ownerName = LISTENER, trackCount = 12, sourceUrl = "${pages.getValue(provider)}/playlist/4004")

    class Provider(override val type: ProviderType) : MusicProvider {
        override suspend fun getHome(): List<HomeSection> = emptyList()
        override suspend fun search(query: String) = SearchResults(
            tracks = listOf(song(type)),
            artists = listOf(Artist("2002", "Tycho", type)),
            playlists = listOf(album(type), artist(type)),
        )
        override suspend fun getTrack(id: String): Track? = null
        override suspend fun getRecommendations(context: PlaybackContext): List<Track> = emptyList()
        override suspend fun getLibraryPlaylists() = listOf(playlist(type))
        override suspend fun getPlaylistTracks(playlist: Playlist) = listOf(song(type))
    }

    /** A stand-in for each of the three. */
    fun providers(): List<MusicProvider> = listOf(ProviderType.APPLE_MUSIC, ProviderType.DEEZER, ProviderType.TIDAL).map(::Provider)

    /**
     * Where core keeps the three's secrets, as a listener signed in to all of them leaves it: a Tidal app of their own
     * and a sign-in through it, and an Apple Music sign-in with a developer token of their own. Under core's own keys.
     */
    fun signedIn(): InMemorySecretStore = InMemorySecretStore().apply {
        put("tidal.client_id", "made-up-tidal-client-id")
        put("tidal.client_secret", "made-up-tidal-client-secret")
        put("tidal.refresh_token", "made-up-tidal-refresh-token")
        put("applemusic.user_token", "made-up-apple-music-user-token")
        put("applemusic.developer_token", "made-up-apple-music-developer-token")
    }

    /** What the settings file says of the same listener. */
    val signedInPreferences = NoctoriumPreferences(
        deezerProfileId = DEEZER_PROFILE,
        deezerProfileName = LISTENER,
        tidalUserId = "5005",
        tidalAccountName = LISTENER,
        tidalCountry = "GB",
        appleMusicSignedIn = true,
        appleMusicStorefront = "gb",
    )
}
