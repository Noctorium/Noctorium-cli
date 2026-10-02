package app.noctorium.cli.web

import app.noctorium.cli.DesktopSignIn
import app.noctorium.core.AppState
import app.noctorium.core.LinkAction
import app.noctorium.core.ProviderFilter
import app.noctorium.core.SearchMode
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.playback.RepeatMode
import app.noctorium.settings.AccentPreset
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
                    else -> state.createPlaylist(title, ProviderType.valueOf(where), listOfNotNull(track), command.bool("public") ?: false)
                }
            }
            "addToPlaylist" -> {
                val track = command.track("track") ?: return "No track"
                command.string("localId")?.let { state.addTrackToPlaylist(it, track) }
                    ?: state.addTrackToPlaylist(playlist(command.string("key")) ?: return "No such playlist", track)
            }
            "removeFromPlaylist" -> {
                val track = command.track("track") ?: return "No track"
                command.string("localId")?.let { state.removeTrackFromPlaylist(it, track.queueKey) } ?: run {
                    val playlist = playlist(command.string("key")) ?: return "No such playlist"
                    when (playlist.provider) {
                        ProviderType.SOUNDCLOUD -> state.removeTrackFromSoundCloudPlaylist(playlist.id, track.id)
                        else -> state.removeTrackFromYouTubePlaylist(playlist.id, track.id)
                    }
                }
            }
            "movePlaylistTrack" -> {
                val from = command.int("from") ?: return "No from"
                val to = command.int("to") ?: return "No to"
                command.string("localId")?.let { state.moveInLocalPlaylist(it, from, to) } ?: state.moveInYouTubePlaylist(from, to)
            }
            "renamePlaylist" -> {
                val title = command.string("title")?.takeIf(String::isNotBlank) ?: return "No name"
                command.string("localId")?.let { state.renamePlaylist(it, title) } ?: run {
                    val playlist = playlist(command.string("key")) ?: return "No such playlist"
                    when (playlist.provider) {
                        ProviderType.SOUNDCLOUD -> state.renameSoundCloudPlaylist(playlist.id, title)
                        else -> state.renameYouTubePlaylist(playlist.id, title)
                    }
                }
            }
            "deletePlaylist" -> {
                command.string("localId")?.let { state.deletePlaylist(it) } ?: run {
                    val playlist = playlist(command.string("key")) ?: return "No such playlist"
                    when (playlist.provider) {
                        ProviderType.SOUNDCLOUD -> state.deleteSoundCloudPlaylist(playlist.id)
                        else -> state.deleteYouTubePlaylist(playlist.id)
                    }
                    state.closePlaylist()
                }
            }
            "visibility" -> {
                val playlist = playlist(command.string("key")) ?: return "No such playlist"
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
            "connect" -> state.setConnectEnabled(command.bool("on") ?: return "On or off?")
            "renameDevice" -> state.renameThisDevice(command.string("name") ?: return "No name")
            "playOn" -> state.connect.value.devices.firstOrNull { it.id == command.string("id") }?.let(state::playOn) ?: return "That device is gone"
            "bringBack" -> state.bringPlaybackBack()
            "stopControlling" -> state.stopControlling()
            "checkUpdates" -> state.checkForUpdates()

            // --- Accounts ---
            "phoneSignIn" -> state.receiveYouTubeSignIn()
            "cancelSignIn" -> state.cancelSignInTransfer()
            "cookies" -> {
                val youTube = command.string("service") != "soundcloud"
                DesktopSignIn.fromCookies(state, youTube, null, command.string("text"))?.let { return it }
            }
            "copyDesktop" -> {
                val problem = if (command.string("service") == "soundcloud") DesktopSignIn.copySoundCloud(state) else DesktopSignIn.copyYouTube(state)
                problem?.let { return it }
                notice("Copied the sign-in from Noctorium on this computer")
            }
            "signOut" -> when (command.string("service")) {
                "youtube" -> state.disconnectAccount(ProviderType.YOUTUBE_MUSIC)
                "soundcloud" -> state.disconnectAccount(ProviderType.SOUNDCLOUD)
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
            "spotify" -> state.connectSpotify()
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

private fun JsonObject.string(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
private fun JsonObject.long(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
private fun JsonObject.int(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
private fun JsonObject.float(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.floatOrNull }.getOrNull() }
private fun JsonObject.bool(name: String) = this[name]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
private fun JsonObject.track(name: String) = this[name]?.let { runCatching { wireJson.decodeFromJsonElement<WTrack>(it).toTrack() }.getOrNull() }
private fun JsonObject.tracks(name: String) = this[name]?.let { element ->
    runCatching { element.jsonArray.mapNotNull { runCatching { wireJson.decodeFromJsonElement<WTrack>(it).toTrack() }.getOrNull() } }.getOrNull()
}.orEmpty()
