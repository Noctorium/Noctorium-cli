package app.noctorium.cli.web

import app.noctorium.bandcamp.BandcampGenre
import app.noctorium.cli.AutoplayState
import app.noctorium.cli.DesktopSignIn
import app.noctorium.cli.cliVersion
import app.noctorium.cli.tui.Settings
import app.noctorium.core.AppState
import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.editableOnService
import app.noctorium.playback.SleepTimerState
import app.noctorium.playlists.LocalPlaylist
import app.noctorium.settings.AutoplaySource
import app.noctorium.settings.DEFAULT_HYBRID_SEARCH
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.ThemeSkin
import app.noctorium.settings.resolvedAccent
import app.noctorium.settings.themeColours
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/*
 * What the browser is told, shaped for it.
 *
 * Core's own state classes are mostly not serialisable, and would not be the right shape if they were: a
 * browser wants a track's key and its artist line worked out, a playlist's editability decided, a colour as
 * a CSS string. So each part of the state has a small wire type here, and the page receives them as separate
 * parts -- playback alone changes several times a second, and resending the whole library with it would be
 * a lot of bytes for a seek bar.
 */

internal val wireJson = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

@Serializable
data class WArtist(val id: String, val name: String, val provider: ProviderType)

@Serializable
data class WAlbum(val id: String, val title: String, val artists: List<WArtist> = emptyList(), val provider: ProviderType, val artworkUrl: String? = null)

@Serializable
data class WTrack(
    val key: String,
    val provider: ProviderType,
    val id: String,
    val title: String,
    val artists: List<WArtist>,
    val artistLine: String,
    val album: WAlbum? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val sourceUrl: String,
) {
    fun toTrack(): Track = Track(
        provider = provider,
        id = id,
        title = title,
        artists = artists.map { Artist(it.id, it.name, it.provider) },
        album = album?.let { Album(it.id, it.title, it.artists.map { a -> Artist(a.id, a.name, a.provider) }, it.provider, it.artworkUrl) },
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        sourceUrl = sourceUrl,
    )
}

fun Artist.wire() = WArtist(id, name, provider)
fun Album.wire() = WAlbum(id, title, artists.map { it.wire() }, provider, artworkUrl)
fun Track.wire() = WTrack(queueKey, provider, id, title, artists.map { it.wire() }, artistLine, album?.wire(), durationMs, artworkUrl, sourceUrl)

@Serializable
data class WPlaylist(
    val key: String,
    val id: String,
    val title: String,
    val provider: ProviderType,
    val ownerName: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
    val isPublic: Boolean? = null,
    val editable: Boolean = false,
    val sourceUrl: String? = null,
    val tracks: List<WTrack>? = null,
)

fun Playlist.wire(withTracks: Boolean = false) = WPlaylist(
    playlistKey, id, title, provider, ownerName, artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
    trackCount ?: tracks.size.takeIf { it > 0 }, isPublic, editableOnService(), sourceUrl,
    if (withTracks) tracks.map { it.wire() } else null,
)

@Serializable
data class WLocalPlaylist(val id: String, val title: String, val tracks: List<WTrack>, val artworkUrl: String? = null)

fun LocalPlaylist.wire() = WLocalPlaylist(id, title, tracks.map { it.wire() }, tracks.firstOrNull { it.artworkUrl != null }?.artworkUrl)

@Serializable
data class WPlayback(
    val status: String,
    val track: WTrack? = null,
    val error: String? = null,
    val volume: Float,
    val positionMs: Long,
    val durationMs: Long,
    val muted: Boolean,
    val boost: Boolean,
    val output: String,
    val sleep: WSleep,
    /** A Spotify song the account's own Spotify app is playing: the sound is there, not here or in a tab. */
    val onSpotify: Boolean = false,
    val at: Long = System.currentTimeMillis(),
)

@Serializable
data class WSleep(val kind: String = "off", val remainingMs: Long? = null)

/**
 * The queue, and autoplay after it: the songs autoplay has lined up, kept apart from the queue until they play
 * or are kept, where they come from, and what autoplay is doing when there are none -- one of AutoplayState's
 * names, in lower case, so the page says the same as the terminal's Queue page.
 */
@Serializable
data class WQueue(
    val tracks: List<WTrack>,
    val currentIndex: Int,
    val shuffle: Boolean,
    val repeat: String,
    val origin: String? = null,
    val suggestions: List<WTrack> = emptyList(),
    val suggestionsFrom: String? = null,
    /** Spotify, playing on Spotify itself, chooses what comes next; next asks it to move on. */
    val continuesElsewhere: Boolean = false,
    /** Whether next would go anywhere: on in the queue, round again, autoplay's first, or Spotify's choice. */
    val hasNext: Boolean = false,
    val autoplay: String = "off",
)

/** One of a setting's choices, with what it means. */
@Serializable
data class WChoice(val name: String, val title: String, val description: String? = null)

@Serializable
data class WSection(val id: String, val title: String, val subtitle: String? = null, val provider: ProviderType, val tracks: List<WTrack>, val playlists: List<WPlaylist>)

@Serializable
data class WHome(val sections: List<WSection>, val loading: Boolean, val recent: List<WTrack>, val pinned: List<WTrack>, val error: String? = null, val filter: String)

@Serializable
data class WSearch(
    val query: String,
    val mode: String,
    val loading: Boolean,
    val tracks: List<WTrack>,
    val playlists: List<WPlaylist>,
    val albums: List<WAlbum>,
    val artists: List<WArtist>,
)

@Serializable
data class WLibrary(
    val playlists: List<WPlaylist>,
    val local: List<WLocalPlaylist>,
    val loading: Boolean,
    val loaded: Boolean,
    val error: String? = null,
    val needsSoundCloudUsername: Boolean,
    val open: WPlaylist? = null,
    val openLocal: WLocalPlaylist? = null,
    val openLoading: Boolean,
    val openError: String? = null,
    val enriching: Boolean,
)

@Serializable
data class WChannel(val pageId: String, val name: String, val authUser: Int, val photoUrl: String? = null, val handle: String? = null, val selected: Boolean)

@Serializable
data class WLikes(
    val keys: List<String>,
    val busy: List<String>,
    val soundCloudReady: Boolean,
    val youTubeReady: Boolean,
    val channels: List<WChannel>,
    val spotifyReady: Boolean = false,
    val vkReady: Boolean = false,
)

@Serializable
data class WLine(val text: String, val startMs: Long? = null)

@Serializable
data class WLyricsSource(
    val provider: String,
    val name: String,
    val status: String,
    val synced: Boolean = false,
    val lines: List<WLine> = emptyList(),
    val sourceUrl: String? = null,
    val attribution: String? = null,
    val message: String? = null,
    val detail: String? = null,
)

@Serializable
data class WLyrics(val trackKey: String? = null, val loading: Boolean, val sources: List<WLyricsSource>, val selected: String? = null, val error: String? = null)

@Serializable
data class WAccount(val status: String, val detail: String? = null, val hint: String? = null)

@Serializable
data class WService(val status: String, val username: String? = null, val message: String? = null)

@Serializable
data class WGenre(val name: String, val title: String)

/**
 * Bandcamp, which is a name rather than a sign-in: the fan whose collection the library shows, how the last
 * look for them went, and the genres Home has a row for.
 */
@Serializable
data class WBandcamp(
    /** The name in bandcamp.com/<name>; blank for none. */
    val username: String,
    /** What the fan calls themselves, once Bandcamp has confirmed the name since Noctorium started. */
    val fanName: String,
    val checking: Boolean,
    val message: String? = null,
    /** The genres Home has a row for, by name, in their order. */
    val genres: List<String>,
    /** Every genre there could be a row for. */
    val allGenres: List<WGenre>,
    /** The name Noctorium on this computer shows, to be offered here too; null when it shows none. */
    val desktop: String? = null,
)

@Serializable
data class WSpotifyDevice(val id: String, val name: String, val type: String, val active: Boolean, val restricted: Boolean, val volume: Int? = null)

/**
 * Spotify's two sign-ins and what the Premium one allows: whether its songs play in the account's own Spotify
 * app, and on which of the places that app is open.
 */
@Serializable
data class WSpotify(
    val connected: Boolean,
    val connecting: Boolean,
    val account: String,
    val message: String? = null,
    /** The Premium sign-in: Spotify songs can be played on Spotify itself. */
    val canPlay: Boolean,
    val playsOnSpotify: Boolean,
    /** Where the account's Spotify is open, as last asked; empty until asked. */
    val devices: List<WSpotifyDevice>,
    /** The device chosen, by Spotify's id; blank for whichever Spotify has active. */
    val device: String,
)

/** VK, signed in with a browser's session, and what to read before signing in. */
@Serializable
data class WVk(
    val connected: Boolean,
    val account: String,
    val checking: Boolean,
    val message: String? = null,
    /** What signing in to VK means, and where the two cookies are: the terminal player says the same. */
    val notice: List<String>,
)

@Serializable
data class WTheme(
    val name: String,
    val title: String,
    val family: String,
    val background: String,
    val panel: String,
    val card: String,
    val text: String,
    val subtext: String,
    val accent: String,
    val light: Boolean,
    /**
     * How the theme is drawn beyond its colours, by ThemeSkin's name: STANDARD for nearly all, WINDOWS_98 and
     * WINDOWS_XP for the two that draw their bevels, title bars and taskbars, which the page draws from the
     * same system colours as every other Noctorium.
     */
    val skin: String = ThemeSkin.STANDARD.name,
)

@Serializable
data class WSettings(
    val theme: String,
    val themes: List<WTheme>,
    val colours: WTheme,
    val accent: String,
    val accents: List<String>,
    val progressBarStyle: String,
    /** Every seek bar there is, by name, with what each is called and looks like, so the page lists them all. */
    val progressBarStyles: List<WChoice> = emptyList(),
    val timeDisplay: String,
    val skipNonMusic: Boolean,
    val youtubeHistory: Boolean,
    val lyricsProvider: String? = null,
    val discord: Boolean,
    val animations: Boolean,
    val exportFolder: String? = null,
    val youtube: WAccount,
    val soundcloud: WAccount,
    val youtubeChannel: String,
    val soundCloudUsername: String,
    val spotifyConnected: Boolean,
    val spotifyConnecting: Boolean,
    val spotifyAccount: String,
    val spotify: WSpotify,
    val bandcamp: WBandcamp,
    val vk: WVk,
    /** How fast it plays, 1 being as recorded, from 0.5 to 2. */
    val playbackSpeed: Float,
    /** The queue running out carries on with songs like the last. */
    val autoplay: Boolean,
    /** Where autoplay's songs come from, by name, and the choices; and whether it leaves out ones played lately. */
    val autoplayFrom: String,
    val autoplaySources: List<WChoice>,
    val avoidRecent: Boolean,
    /** The queue is kept when the player closes and put back when it starts. */
    val keepQueue: Boolean,
    /** The services a Hybrid search asks, by name, and every one it could. */
    val hybridSearch: List<String>,
    val hybridServices: List<String>,
    /** Seconds a sleep timer fades out over; zero for none. */
    val sleepFade: Int,
    val lastfm: WService,
    val listenbrainz: WService,
    val scrobbles: Int,
    val connectEnabled: Boolean,
    val desktopYouTube: Boolean,
    val desktopSoundCloud: Boolean,
    val message: String? = null,
    val version: String = cliVersion,
)

@Serializable
data class WDownload(val track: WTrack, val bytes: Long, val at: Long)

@Serializable
data class WJob(val track: WTrack, val stage: String, val progress: Float, val detail: String? = null)

@Serializable
data class WDownloads(val entries: List<WDownload>, val active: List<WJob>, val message: String? = null, val canSaveAsMp3: Boolean)

@Serializable
data class WPeer(val id: String, val name: String, val kind: String)

@Serializable
data class WConnect(
    val available: Boolean,
    val enabled: Boolean,
    val thisDevice: String,
    val devices: List<WPeer>,
    val target: WPeer? = null,
    val controlledBy: String? = null,
    val busy: Boolean,
)

@Serializable
data class WSignIn(val state: String, val code: String? = null, val message: String? = null)

@Serializable
data class WNoctorium(val signedIn: Boolean, val name: String? = null, val email: String? = null, val streams: Long = 0, val hours: Double = 0.0, val message: String? = null)

@Serializable
data class WNotice(val text: String, val tone: String = "normal", val url: String? = null)

private fun argb(value: Long) = "#%06x".format(value and 0xFFFFFF)

private fun ThemePreset.wire(custom: app.noctorium.settings.ThemeColours): WTheme {
    val c = colours ?: custom
    return WTheme(name, displayName, family, argb(c.background), argb(c.panel), argb(c.card), argb(c.text), argb(c.subtext), argb(c.accent), c.light, skin.name)
}

/** The parts of the state, each as JSON, by the name the page knows them by. */
class Wire(private val state: AppState, private val engine: SwitchingEngine) {
    fun playback(): JsonElement {
        val p = state.playback.value
        val sleep = when (val timer = state.sleepTimer.value) {
            is SleepTimerState.Countdown -> WSleep("countdown", state.sleepTimerRemainingMs.value)
            SleepTimerState.EndOfTrack -> WSleep("endOfTrack")
            null -> WSleep()
            else -> WSleep()
        }
        val onSpotify = p.track?.provider == ProviderType.SPOTIFY && state.settings.value.spotify.playsOnSpotify
        return wireJson.encodeToJsonElement(
            WPlayback(
                p.status.name.lowercase(), p.track?.wire(), p.errorMessage, p.volume, p.positionMs, p.durationMs,
                p.isMuted, p.volumeBoostEnabled, engine.output.value.name.lowercase(), sleep, onSpotify,
            ),
        )
    }

    fun queue(): JsonElement {
        val q = state.queue.state.value
        return wireJson.encodeToJsonElement(
            WQueue(
                q.tracks.map { it.wire() }, q.currentIndex, q.shuffleEnabled, q.repeatMode.name.lowercase(), q.context?.originType?.name?.lowercase(),
                suggestions = q.suggestions.map { it.wire() },
                suggestionsFrom = q.suggestionsFrom,
                continuesElsewhere = q.continuesElsewhere,
                hasNext = q.hasNext,
                autoplay = AutoplayState.of(q, state.settings.value.preferences.autoplay).name.lowercase(),
            ),
        )
    }

    fun home(): JsonElement {
        val ui = state.ui.value
        return wireJson.encodeToJsonElement(
            WHome(
                // Only the rows of the service chosen with the page's chips, as the hosted player sends them too:
                // core keeps the choice, and each Noctorium leaves the other rows out as it shows Home.
                ui.homeSections.filter { ui.providerFilter.matches(it.provider) }
                    .map { s -> WSection(s.id, s.title, s.subtitle, s.provider, s.tracks.map { it.wire() }, s.playlists.map { it.wire() }) },
                ui.homeLoading,
                ui.recentTracks.take(12).map { it.wire() },
                ui.pinnedTracks.map { it.wire() },
                ui.errorMessage,
                ui.providerFilter.name.lowercase(),
            ),
        )
    }

    fun search(): JsonElement {
        val ui = state.ui.value
        val r = ui.searchResults
        return wireJson.encodeToJsonElement(
            WSearch(ui.searchQuery, ui.searchMode.name, ui.searchLoading, r.tracks.map { it.wire() }, r.playlists.map { it.wire() }, r.albums.map { it.wire() }, r.artists.map { it.wire() }),
        )
    }

    fun library(): JsonElement {
        val l = state.library.value
        return wireJson.encodeToJsonElement(
            WLibrary(
                l.playlists.map { it.wire() }, l.localPlaylists.map { it.wire() }, l.loading, l.loaded, l.errorMessage,
                l.needsSoundCloudUsername, l.openPlaylist?.wire(withTracks = true), l.openLocalPlaylist?.wire(),
                l.openPlaylistLoading, l.openPlaylistError, l.openPlaylistEnriching,
            ),
        )
    }

    fun likes(): JsonElement {
        val l = state.likes.value
        return wireJson.encodeToJsonElement(
            WLikes(
                l.likedKeys.toList(), l.busyKeys.toList(), l.soundCloudReady, l.youTubeReady,
                l.youTubeChannels.map { WChannel(it.pageId, it.name, it.authUser, it.photoUrl, it.handle, it.selected) },
                l.spotifyReady, l.vkReady,
            ),
        )
    }

    fun lyrics(): JsonElement {
        val l = state.lyrics.value
        return wireJson.encodeToJsonElement(
            WLyrics(
                l.trackKey,
                l.loading,
                l.outcomes.map { o ->
                    WLyricsSource(
                        o.provider.name, o.provider.displayName, o.status.name.lowercase(), o.result?.synced == true,
                        o.result?.lines.orEmpty().map { WLine(it.text, it.startTimeMs) }, o.result?.sourceUrl,
                        o.result?.attribution, o.result?.message, o.detail,
                    )
                },
                l.selectedProvider?.name,
                l.errorMessage,
            ),
        )
    }

    fun settings(): JsonElement {
        val s = state.settings.value
        val p = s.preferences
        val colours = p.themeColours()
        val current = ThemePreset.entries.first { it == p.theme }.wire(p.customTheme).copy(accent = argb(p.resolvedAccent(null)))
        return wireJson.encodeToJsonElement(
            WSettings(
                theme = p.theme.name,
                themes = ThemePreset.entries.filter { it.colours != null }.map { it.wire(p.customTheme) },
                colours = current.copy(light = colours.light),
                accent = p.accent.name,
                accents = app.noctorium.settings.AccentPreset.entries.map { it.name },
                progressBarStyle = p.progressBarStyle.name,
                progressBarStyles = ProgressBarStyle.entries.map { WChoice(it.name, it.displayName, it.description) },
                timeDisplay = p.timeDisplay.name,
                skipNonMusic = p.skipNonMusic,
                youtubeHistory = p.youtubeHistory,
                lyricsProvider = p.lyricsProvider?.name,
                discord = p.discord.enabled,
                animations = p.animations,
                exportFolder = state.exportFolder()?.toString(),
                youtube = WAccount(s.youtubeAccount.status.name.lowercase(), s.youtubeAccount.detail, s.youtubeAccount.hint),
                soundcloud = WAccount(s.soundCloudAccount.status.name.lowercase(), s.soundCloudAccount.detail, s.soundCloudAccount.hint),
                youtubeChannel = p.youtubeChannelName,
                soundCloudUsername = p.soundCloudUsername,
                spotifyConnected = s.spotify.connected,
                spotifyConnecting = s.spotify.connecting,
                spotifyAccount = s.spotify.accountName,
                spotify = WSpotify(
                    connected = s.spotify.connected,
                    connecting = s.spotify.connecting,
                    account = s.spotify.accountName,
                    message = s.spotify.message,
                    canPlay = s.spotify.canPlay,
                    playsOnSpotify = s.spotify.playsOnSpotify,
                    devices = s.spotify.devices.map { WSpotifyDevice(it.id, it.name, it.type, it.isActive, it.isRestricted, it.volumePercent) },
                    device = s.spotify.device,
                ),
                vk = WVk(
                    connected = s.vk.connected,
                    account = s.vk.accountName,
                    checking = s.vk.checking,
                    message = s.vk.message,
                    notice = Settings.VK_NOTICE + Settings.VK_COOKIES_HOW,
                ),
                playbackSpeed = p.playbackSpeed,
                autoplay = p.autoplay,
                autoplayFrom = p.autoplayFrom.name,
                autoplaySources = AutoplaySource.entries.map { WChoice(it.name, it.displayName, it.description) },
                avoidRecent = p.autoplayAvoidRecent,
                keepQueue = p.keepQueue,
                hybridSearch = DEFAULT_HYBRID_SEARCH.filter { it in p.hybridSearch }.map { it.name },
                hybridServices = DEFAULT_HYBRID_SEARCH.map { it.name },
                sleepFade = p.sleepFadeSeconds,
                bandcamp = WBandcamp(
                    username = p.bandcampUsername,
                    fanName = s.bandcamp.fanName,
                    checking = s.bandcamp.checking,
                    message = s.bandcamp.message,
                    genres = p.bandcampGenres.map { it.name },
                    allGenres = BandcampGenre.entries.map { WGenre(it.name, it.displayName) },
                    desktop = desktopBandcamp,
                ),
                lastfm = WService(s.scrobbling.lastFm.status.name.lowercase(), s.scrobbling.lastFm.username, s.scrobbling.lastFm.message),
                listenbrainz = WService(s.scrobbling.listenBrainz.status.name.lowercase(), s.scrobbling.listenBrainz.username, s.scrobbling.listenBrainz.message),
                scrobbles = s.scrobbling.scrobblesThisSession,
                connectEnabled = p.connect.enabled,
                desktopYouTube = desktopYouTube,
                desktopSoundCloud = desktopSoundCloud,
                message = s.message,
            ),
        )
    }

    /** Asked once a minute at most: it reads the desktop's files. */
    private val desktopYouTube: Boolean get() = cached("yt", false) { DesktopSignIn.youTubeAvailable() }
    private val desktopSoundCloud: Boolean get() = cached("sc", false) { DesktopSignIn.soundCloudAvailable() }
    private val desktopBandcamp: String? get() = cached("bc", null) { DesktopSignIn.bandcampName() }
    private val cache = mutableMapOf<String, Pair<Long, Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, otherwise: T, read: () -> T): T {
        synchronized(cache) {
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { now - it.first < 60_000 }?.let { return it.second as T }
            return runCatching(read).getOrDefault(otherwise).also { cache[key] = now to it }
        }
    }

    fun downloads(): JsonElement {
        val d = state.downloadState.value
        return wireJson.encodeToJsonElement(
            WDownloads(
                d.entries.map { WDownload(it.toTrack().wire(), it.bytes, it.downloadedAtEpochSeconds) },
                d.active.map { WJob(it.track.wire(), it.stage.name.lowercase(), it.progress, it.detail) },
                d.message,
                state.canSaveAsMp3(),
            ),
        )
    }

    fun connect(): JsonElement {
        val c = state.connect.value
        return wireJson.encodeToJsonElement(
            WConnect(
                c.available, state.settings.value.preferences.connect.enabled, c.thisDevice,
                c.devices.map { WPeer(it.id, it.name, it.kind.name.lowercase()) },
                c.target?.let { WPeer(it.id, it.name, it.kind.name.lowercase()) }, c.controlledBy, c.busy,
            ),
        )
    }

    fun signIn(): JsonElement = wireJson.encodeToJsonElement(
        when (val t = state.signInTransfer.value) {
            is AppState.SignInTransfer.Waiting -> WSignIn("waiting", code = t.code)
            AppState.SignInTransfer.Checking -> WSignIn("checking")
            is AppState.SignInTransfer.Done -> WSignIn("done", message = t.channel)
            is AppState.SignInTransfer.Failed -> WSignIn("failed", message = t.message)
            AppState.SignInTransfer.Idle -> WSignIn("idle")
        },
    )

    fun account(): JsonElement {
        val a = state.account.value
        return wireJson.encodeToJsonElement(
            WNoctorium(a.signedIn, a.user?.displayName, a.user?.email, a.stats.streams.toLong(), a.stats.hours.toDouble(), a.message),
        )
    }

    /** Every part, for a page that has just connected. */
    fun all(): Map<String, () -> JsonElement> = linkedMapOf(
        "settings" to ::settings,
        "playback" to ::playback,
        "queue" to ::queue,
        "home" to ::home,
        "search" to ::search,
        "library" to ::library,
        "likes" to ::likes,
        "lyrics" to ::lyrics,
        "downloads" to ::downloads,
        "connect" to ::connect,
        "signIn" to ::signIn,
        "account" to ::account,
    )
}

/** The like key core keeps for a track, computed the same way on the page from a track's id. */
fun likeKeyOf(provider: ProviderType, id: String) = when (provider) {
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> "yt:$id"
    ProviderType.SOUNDCLOUD -> "sc:$id"
    ProviderType.SPOTIFY -> "spotify:$id"
    ProviderType.BANDCAMP -> "bc:$id"
    ProviderType.VK -> "vk:$id"
    ProviderType.LOCAL -> "local:$id"
}
