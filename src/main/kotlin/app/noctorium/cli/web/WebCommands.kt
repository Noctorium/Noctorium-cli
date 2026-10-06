package app.noctorium.cli.web

import app.noctorium.bandcamp.BandcampGenre
import app.noctorium.cli.DesktopSignIn
import app.noctorium.core.AppState
import app.noctorium.core.LinkAction
import app.noctorium.core.ProviderFilter
import app.noctorium.core.SearchMode
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.editableOnService
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.playback.RepeatMode
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.AutoplaySource
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.TimeDisplay
import app.noctorium.social.YouTubeChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * What the page can ask for, each one the call the desktop's own button makes.
 *
 * A command is a JSON object with a `type` and whatever that type needs. Tracks come back as the page was
 * given them; playlists are named by key and looked up in the state, so a stale copy in a tab can never
 * stand in for the real thing.
 */
class WebCommands(
    private val state: AppState,
    private val engine: SwitchingEngine,
    private val scope: CoroutineScope,
    /** The settings page's "Check for updates", which is the terminal player's own updater here. */
    private val checkForUpdates: () -> Unit = { state.checkForUpdates() },
    private val notice: (String) -> Unit,
) {
    /** Does [command]. Returns null when it was understood, or why not. */
    fun run(command: JsonObject): String? {
        val type = command.string("type") ?: return "No type"
        when (type) {
            // --- Playing ---
            "toggle" -> state.togglePlayback()
            "next" -> state.next()
            "previous" -> state.previous()
            "seek" -> state.seekTo(command.long("positionMs") ?: return "No position")
            "volume" -> state.setVolume(command.float("value") ?: return "No volume")
            "mute" -> state.toggleMute()
            "boost" -> state.toggleVolumeBoost()
            "shuffle" -> state.toggleShuffle()
            "repeat" -> state.cycleRepeat()
            "setRepeat" -> state.queue.setRepeat(RepeatMode.valueOf(command.string("mode")?.uppercase() ?: return "No mode"))
            "output" -> {
                val target = if (command.string("to") == "computer") Output.COMPUTER else Output.BROWSER
                scope.launch { engine.switchTo(target) }
            }
            "play" -> {
                val track = command.track("track") ?: return "No track"
                val list = command.tracks("list").ifEmpty { listOf(track) }
                val origin = command.string("origin")?.let { runCatching { PlaybackOrigin.valueOf(it.uppercase()) }.getOrNull() } ?: PlaybackOrigin.SEARCH
                state.play(track, origin, list)
            }
            "playPlaylist" -> {
                val playlist = playlist(command.string("key")) ?: return "That playlist is not open any more"
                val start = command.string("startAt")?.let { key -> playlist.tracks.firstOrNull { it.queueKey == key } }
                if (command.bool("shuffle") == true && !state.queue.state.value.shuffleEnabled) state.toggleShuffle()
                state.playPlaylist(playlist, start)
            }
            "playLocal" -> {
                val local = state.library.value.localPlaylists.firstOrNull { it.id == command.string("id") } ?: return "No such playlist"
                val start = command.string("startAt")?.let { key -> local.tracks.firstOrNull { it.queueKey == key } }
                if (command.bool("shuffle") == true && !state.queue.state.value.shuffleEnabled) state.toggleShuffle()
                state.playLocalPlaylist(local, start)
            }
            "playDownloads" -> {
                val start = command.string("startAt")?.let { key -> state.downloadState.value.entries.map { it.toTrack() }.firstOrNull { it.queueKey == key } }
                state.playDownloads(start)
            }
            "jump" -> state.jumpToQueueItem(command.int("index") ?: return "No index")
            "moveQueue" -> state.moveQueueItem(command.int("from") ?: return "No from", command.int("to") ?: return "No to")
            "removeQueue" -> state.removeQueueItem(command.int("index") ?: return "No index")
            "addToQueue" -> command.tracks("tracks").ifEmpty { listOfNotNull(command.track("track")) }.forEach(state::addToQueue)
            "playNext" -> state.playNext(command.track("track") ?: return "No track")
            "clearQueue" -> state.clearQueue()
            "shuffleUpcoming" -> state.shuffleUpcoming()
            "clearUpcoming" -> state.clearUpcoming()
            // Core says how it went, saved or why not, as a library notice.
            "saveQueue" -> state.saveQueueAsPlaylist(command.string("title")?.takeIf(String::isNotBlank) ?: return "Give the playlist a name first")
            // Autoplay's songs, by key where the page sends one, so a list that moved on since it was drawn
            // never plays, keeps or drops the wrong one.
            "playSuggestion" -> state.playSuggestion(suggestion(command) ?: return GONE)
            "keepSuggestion" -> state.keepSuggestion(suggestion(command) ?: return GONE)
            "removeSuggestion" -> state.removeSuggestion(suggestion(command) ?: return GONE)
            "refreshSuggestions" -> state.refreshSuggestions()
            "openLink" -> state.openLink(command.string("text") ?: return "No link", command.string("action")?.let { LinkAction.valueOf(it.uppercase()) } ?: LinkAction.PLAY)

            // --- Finding ---
            "search" -> state.search(command.string("query").orEmpty())
            "searchMode" -> state.setSearchMode(SearchMode.valueOf(command.string("mode") ?: return "No mode"))
            "refreshHome" -> state.refreshHome()
            "filter" -> state.setFilter(ProviderFilter.valueOf(command.string("filter")?.uppercase() ?: "ALL"))
            "pin" -> state.togglePin(command.track("track") ?: return "No track")

            // --- The library ---
            "refreshLibrary" -> state.refreshLibrary(command.bool("force") ?: false)
            "openPlaylist" -> state.openPlaylist(playlist(command.string("key")) ?: return "No such playlist")
            "closePlaylist" -> state.closePlaylist()
            "openLocal" -> state.openLocalPlaylist(state.library.value.localPlaylists.firstOrNull { it.id == command.string("id") } ?: return "No such playlist")
            "closeLocal" -> state.closeLocalPlaylist()
            "createPlaylist" -> {
                val title = command.string("title")?.takeIf(String::isNotBlank) ?: return "No name"
                val track = command.track("track")
                when (val where = command.string("provider")) {
                    null, "LOCAL" -> state.createPlaylist(title, track)
                    else -> {
                        val provider = runCatching { ProviderType.valueOf(where) }.getOrNull() ?: return "No such service: $where"
                        // Core says itself that a playlist cannot be made on the others.
                        if (track != null && provider in WRITABLE && !fits(track, provider)) return onlyItsOwn(provider)
                        state.createPlaylist(title, provider, listOfNotNull(track), command.bool("public") ?: false)
                    }
                }
            }
            "addToPlaylist" -> {
                val track = command.track("track") ?: return "No track"
                command.string("localId")?.let { state.addTrackToPlaylist(it, track) } ?: run {
                    val playlist = writable(command.string("key")) { return it }
                    if (!fits(track, playlist.provider)) return onlyItsOwn(playlist.provider)
                    state.addTrackToPlaylist(playlist, track)
                }
            }
            "removeFromPlaylist" -> {
                val track = command.track("track") ?: return "No track"
                command.string("localId")?.let { state.removeTrackFromPlaylist(it, track.queueKey) } ?: run {
                    val playlist = writable(command.string("key")) { return it }
                    when (playlist.provider) {
                        ProviderType.SOUNDCLOUD -> state.removeTrackFromSoundCloudPlaylist(playlist.id, track.id)
                        else -> state.removeTrackFromYouTubePlaylist(playlist.id, track.id)
                    }
                }
            }
            "movePlaylistTrack" -> {
                val from = command.int("from") ?: return "No from"
                val to = command.int("to") ?: return "No to"
                command.string("localId")?.let { state.moveInLocalPlaylist(it, from, to) } ?: run {
                    // Core moves within the playlist that is open, which has to be the page's, and YouTube's own.
                    val key = command.string("key")
                    val open = state.library.value.openPlaylist?.takeIf { key == null || key == it.playlistKey }
                        ?: return "That playlist is not open any more"
                    if (!open.editableOnService() || open.provider == ProviderType.SOUNDCLOUD) return "\"${open.title}\" cannot be reordered from here"
                    state.moveInYouTubePlaylist(from, to)
                }
            }
            "renamePlaylist" -> {
                val title = command.string("title")?.takeIf(String::isNotBlank) ?: return "No name"
                command.string("localId")?.let { state.renamePlaylist(it, title) } ?: run {
                    val playlist = writable(command.string("key")) { return it }
                    when (playlist.provider) {
                        ProviderType.SOUNDCLOUD -> state.renameSoundCloudPlaylist(playlist.id, title)
                        else -> state.renameYouTubePlaylist(playlist.id, title)
                    }
                }
            }
            "deletePlaylist" -> {
                command.string("localId")?.let { state.deletePlaylist(it) } ?: run {
                    val playlist = writable(command.string("key")) { return it }
                    when (playlist.provider) {
                        ProviderType.SOUNDCLOUD -> state.deleteSoundCloudPlaylist(playlist.id)
                        else -> state.deleteYouTubePlaylist(playlist.id)
                    }
                    state.closePlaylist()
                }
            }
            "visibility" -> {
                val playlist = writable(command.string("key")) { return it }
                val public = command.bool("public") ?: return "Public or not?"
                when (playlist.provider) {
                    ProviderType.SOUNDCLOUD -> state.setSoundCloudPlaylistVisibility(playlist.id, public)
                    else -> state.setYouTubePlaylistVisibility(playlist.id, public)
                }
            }

            // --- The track ---
            "like" -> state.toggleLike(command.track("track") ?: return "No track")
            "follow" -> state.toggleFollowArtist()
            "download" -> command.tracks("tracks").ifEmpty { listOfNotNull(command.track("track")) }.let { tracks ->
                if (tracks.size == 1) state.downloadTrack(tracks.single()) else state.downloadAll(tracks)
            }
            "export" -> state.exportTrack(command.track("track") ?: return "No track")
            "deleteDownload" -> state.deleteDownload(command.string("key") ?: return "No key")
            "deleteAllDownloads" -> state.deleteAllDownloads()
            "cancelDownload" -> state.cancelDownload(command.string("key") ?: return "No key")
            "lyrics" -> state.loadLyrics(command.track("track") ?: return "No track", command.bool("force") ?: false)
            "lyricsProvider" -> command.string("provider")?.let { state.selectLyricsProvider(LyricsProviderId.valueOf(it)) } ?: state.clearPreferredLyricsProvider()
            "copyLink" -> state.copyTrackLink(command.track("track") ?: return "No track")

            // --- How it looks, and the rest of the settings ---
            "theme" -> state.setTheme(ThemePreset.valueOf(command.string("name") ?: return "No theme"))
            "accent" -> state.setAccent(AccentPreset.valueOf(command.string("name") ?: return "No accent"))
            "seekBar" -> state.setProgressBarStyle(ProgressBarStyle.valueOf(command.string("name") ?: return "No style"))
            "taskbarClock" -> state.setTaskbarClock(command.bool("on") ?: return "On or off?")
            "timeDisplay" -> state.setTimeDisplay(TimeDisplay.valueOf(command.string("name") ?: return "No choice"))
            "skipNonMusic" -> state.setSkipNonMusic(command.bool("on") ?: return "On or off?")
            "youtubeHistory" -> state.setYouTubeHistory(command.bool("on") ?: return "On or off?")
            "discord" -> state.setDiscordPresence(command.bool("on") ?: return "On or off?")
            "animations" -> state.setAnimations(command.bool("on") ?: return "On or off?")
            "exportFolder" -> state.setExportFolder(command.string("path") ?: return "No folder")
            "sleep" -> when (val how = command.string("how")) {
                "off" -> state.cancelSleepTimer()
                "endOfTrack" -> state.sleepAtEndOfTrack()
                "extend" -> state.extendSleepTimer(command.int("minutes") ?: 10)
                else -> state.startSleepTimer(command.int("minutes") ?: how?.toIntOrNull() ?: 30)
            }
            // How it plays: the same settings the terminal player's Settings page changes.
            "speed" -> state.setPlaybackSpeed(command.float("value") ?: return "No speed")
            "autoplay" -> state.setAutoplay(command.bool("on") ?: return "On or off?")
            "autoplayFrom" -> state.setAutoplayFrom(
                AutoplaySource.entries.firstOrNull { it.name == command.string("source") } ?: return "No such choice",
            )
            "avoidRecent" -> state.setAutoplayAvoidRecent(command.bool("on") ?: return "On or off?")
            "keepQueue" -> state.setKeepQueue(command.bool("on") ?: return "On or off?")
            "sleepFade" -> state.setSleepFade(command.int("seconds") ?: return "No seconds")
            "hybridSearch" -> state.setHybridSearchService(
                command.string("provider")?.let { name -> ProviderType.entries.firstOrNull { it.name == name } } ?: return "No such service",
                command.bool("on") ?: return "On or off?",
            )
            "connect" -> state.setConnectEnabled(command.bool("on") ?: return "On or off?")
            "renameDevice" -> state.renameThisDevice(command.string("name") ?: return "No name")
            "playOn" -> state.connect.value.devices.firstOrNull { it.id == command.string("id") }?.let(state::playOn) ?: return "That device is gone"
            "bringBack" -> state.bringPlaybackBack()
            "stopControlling" -> state.stopControlling()
            "checkUpdates" -> checkForUpdates()

            // --- Accounts ---
            "phoneSignIn" -> state.receiveYouTubeSignIn()
            "cancelSignIn" -> state.cancelSignInTransfer()
            "cookies" -> {
                val youTube = command.string("service") != "soundcloud"
                DesktopSignIn.fromCookies(state, youTube, null, command.string("text"))?.let { return it }
            }
            "copyDesktop" -> {
                val service = command.string("service")
                val problem = when (service) {
                    "soundcloud" -> DesktopSignIn.copySoundCloud(state)
                    "bandcamp" -> DesktopSignIn.copyBandcamp(state)
                    else -> DesktopSignIn.copyYouTube(state)
                }
                problem?.let { return it }
                // Bandcamp's name is checked with Bandcamp first, and Settings says how that went.
                if (service != "bandcamp") notice("Copied the sign-in from Noctorium on this computer")
            }
            "signOut" -> when (command.string("service")) {
                "youtube" -> state.disconnectAccount(ProviderType.YOUTUBE_MUSIC)
                "soundcloud" -> state.disconnectAccount(ProviderType.SOUNDCLOUD)
                "bandcamp" -> state.setBandcampUsername("")
                "vk" -> state.disconnectVk()
                "spotify" -> state.disconnectSpotify()
                "lastfm" -> state.disconnectLastFm()
                "listenbrainz" -> state.disconnectListenBrainz()
                "noctorium" -> state.signOutOfNoctorium()
                else -> return "Which service?"
            }
            "channels" -> state.loadYouTubeChannels()
            "channel" -> state.setYouTubeChannel(
                YouTubeChannel(
                    pageId = command.string("pageId") ?: return "No channel",
                    name = command.string("name").orEmpty(),
                    authUser = command.int("authUser") ?: 0,
                    photoUrl = command.string("photoUrl"),
                ),
            )
            "soundCloudUsername" -> state.setSoundCloudUsername(command.string("name") ?: return "No name")
            // A name, or a bandcamp.com address; a blank one takes the collection out of the library.
            "bandcampUsername" -> state.setBandcampUsername(command.string("name") ?: return "No name")
            "bandcampGenres" -> state.setBandcampGenres(
                (command.strings("genres") ?: return "No genres").mapNotNull { name -> BandcampGenre.entries.firstOrNull { it.name == name } },
            )
            // Either sign-in opens Spotify's page in a browser on the computer running Noctorium, whose answer
            // arrives there; Premium also lets the account's Spotify app play Spotify songs.
            "spotify" -> state.connectSpotify()
            "spotifyPremium" -> state.connectSpotifyPremium()
            "spotifyPlayback" -> state.setSpotifyPlayback(command.bool("onSpotify") ?: return "On Spotify or not?")
            "spotifyDevices" -> state.refreshSpotifyDevices()
            "spotifyDevice" -> state.chooseSpotifyDevice(command.string("id").orEmpty())
            // VK's session, pasted from a browser signed in on vk.ru; core checks it with VK before keeping it.
            "vkSignIn" -> state.completeVkSignIn(command.string("text")?.takeIf(String::isNotBlank) ?: return "No cookies")
            "lastfm" -> state.beginLastFmLogin()
            "lastfmFinish" -> state.finishLastFmLogin()
            "listenbrainz" -> state.connectListenBrainz(command.string("token") ?: return "No token")
            "noctoriumLogIn" -> state.logInToNoctorium(command.string("email") ?: return "No email", command.string("password") ?: return "No password")
            "noctoriumSignUp" -> state.signUpToNoctorium(command.string("email") ?: return "No email", command.string("password") ?: return "No password", command.string("name").orEmpty())
            "diagnostics" -> state.runDiagnostics()
            else -> return "Not a command: $type"
        }
        return null
    }

    /**
     * A playlist the page wants changed on its service, by [key], or [refused] with why it cannot be.
     *
     * Only the account's own YouTube and SoundCloud playlists can be written to. Everything else that opens as
     * a playlist -- a Bandcamp album, artist or wishlist, Spotify's lists, YouTube's own Liked Music -- would
     * otherwise fall through the `else` of each write above to YouTube's, as an id YouTube never gave out.
     * The page offers none of these for them; this is so that no page, old or new, can ask anyway.
     */
    private inline fun writable(key: String?, refused: (String) -> Nothing): Playlist {
        val playlist = playlist(key) ?: refused("No such playlist")
        if (!playlist.editableOnService()) refused("\"${playlist.title}\" can only be changed on ${playlist.provider.displayName} itself")
        return playlist
    }

    /**
     * Whether [track] can go into a playlist on [provider]: a service's playlists take that service's tracks.
     * Core turns away a SoundCloud track from a YouTube playlist, but would send a Bandcamp one's id to YouTube
     * as if it were a video's.
     */
    private fun fits(track: Track, provider: ProviderType): Boolean = when (provider) {
        ProviderType.SOUNDCLOUD -> track.provider == ProviderType.SOUNDCLOUD
        ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> track.provider == ProviderType.YOUTUBE_MUSIC || track.provider == ProviderType.YOUTUBE_VIDEO
        else -> false
    }

    private fun onlyItsOwn(provider: ProviderType) = "Only ${provider.displayName} tracks can go into a ${provider.displayName} playlist"

    /**
     * Where one of autoplay's songs is now: found by its key when the page sends one, or else taken at the
     * index it was given, while that is still one of them.
     */
    private fun suggestion(command: JsonObject): Int? {
        val suggestions = state.queue.state.value.suggestions
        command.string("key")?.let { key -> return suggestions.indexOfFirst { it.queueKey == key }.takeIf { it >= 0 } }
        return command.int("index")?.takeIf { it in suggestions.indices }
    }

    /** A playlist by key, from wherever the page could have seen it. */
    private fun playlist(key: String?): Playlist? {
        key ?: return null
        val library = state.library.value
        library.openPlaylist?.takeIf { it.playlistKey == key }?.let { return it }
        library.playlists.firstOrNull { it.playlistKey == key }?.let { return it }
        val ui = state.ui.value
        ui.searchResults.playlists.firstOrNull { it.playlistKey == key }?.let { return it }
        return ui.homeSections.asSequence().flatMap { it.playlists }.firstOrNull { it.playlistKey == key }
    }
}

/** The services whose playlists Noctorium makes and changes. */
private val WRITABLE = setOf(ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD)

/** What a command on one of autoplay's songs says when the song has gone since the page drew it. */
private const val GONE = "That song is no longer lined up"

private fun JsonObject.string(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
private fun JsonObject.strings(name: String) = this[name]?.let { element ->
    runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
}
private fun JsonObject.long(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
private fun JsonObject.int(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
private fun JsonObject.float(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.floatOrNull }.getOrNull() }
private fun JsonObject.bool(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
private fun JsonObject.track(name: String) = this[name]?.let { runCatching { wireJson.decodeFromJsonElement<WTrack>(it).toTrack() }.getOrNull() }
private fun JsonObject.tracks(name: String) = this[name]?.let { element ->
    runCatching { element.jsonArray.mapNotNull { runCatching { wireJson.decodeFromJsonElement<WTrack>(it).toTrack() }.getOrNull() } }.getOrNull()
}.orEmpty()
