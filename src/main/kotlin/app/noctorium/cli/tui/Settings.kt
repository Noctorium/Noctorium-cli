package app.noctorium.cli.tui

import app.noctorium.bandcamp.BandcampGenre
import app.noctorium.cli.CliHome
import app.noctorium.cli.DesktopSignIn
import app.noctorium.cli.cliVersion
import app.noctorium.cli.update.CliUpdates
import app.noctorium.domain.ProviderType
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.playback.PlaybackTool
import app.noctorium.playback.PlaybackToolInstaller
import app.noctorium.playback.ToolOrigin
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.EqualizerPreset
import app.noctorium.settings.AccountConnectionStatus
import app.noctorium.settings.AutoplaySource
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ScrobbleConnectionStatus
import app.noctorium.settings.DEFAULT_HYBRID_SEARCH
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.ThemeSkin
import app.noctorium.settings.TimeDisplay
import app.noctorium.settings.themeSkin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * Settings and Devices, as lists of things to change.
 *
 * Accounts first, because nothing much happens until one is signed in; then how it looks; then the rest.
 * Every value here is the same setting the desktop has, kept in this program's own settings file -- except
 * the ones that only mean something in a terminal, such as the layouts, which live in [TuiPreferences].
 */
object Settings {
    private val io = CoroutineScope(Dispatchers.IO)

    fun rows(tui: Tui): List<Row> = buildList {
        val state = tui.state
        val settings = state.settings.value
        val preferences = settings.preferences
        val likes = state.likes.value

        add(Row.Header("Accounts"))
        val youTube = settings.youtubeAccount
        add(
            Row.Action(
                "YouTube Music",
                value = when (youTube.status) {
                    AccountConnectionStatus.CONNECTED -> "Signed in" + preferences.youtubeChannelName.takeIf(String::isNotBlank)?.let { " as $it" }.orEmpty()
                    AccountConnectionStatus.CHECKING -> "Checking…"
                    AccountConnectionStatus.WARNING -> youTube.detail ?: "Needs attention"
                    AccountConnectionStatus.ERROR -> youTube.detail ?: "Not working"
                    AccountConnectionStatus.DISCONNECTED -> "Not signed in"
                },
                tone = tone(youTube.status),
                run = { tui.overlays.addLast(youTubeMenu(tui)) },
            ),
        )
        val soundCloud = settings.soundCloudAccount
        add(
            Row.Action(
                "SoundCloud",
                value = when (soundCloud.status) {
                    AccountConnectionStatus.CONNECTED -> "Signed in" + preferences.soundCloudUsername.takeIf(String::isNotBlank)?.let { " as $it" }.orEmpty() +
                        if (!likes.soundCloudReady) " · liking not ready" else ""
                    AccountConnectionStatus.CHECKING -> "Checking…"
                    AccountConnectionStatus.DISCONNECTED -> "Not signed in"
                    else -> soundCloud.detail ?: "Needs attention"
                },
                tone = tone(soundCloud.status),
                run = { tui.overlays.addLast(soundCloudMenu(tui)) },
            ),
        )
        // Spotify has two sign-ins, one asking more than the other: any account, for the library, likes, search
        // and two rows of Home, its songs played matched on YouTube Music; and Premium, which also lets the
        // account's own Spotify app play them, wherever it is open. Either opens the browser on this computer.
        val spotify = settings.spotify
        add(
            Row.Action(
                "Spotify",
                value = when {
                    spotify.connecting -> "Waiting for the browser…"
                    spotify.connected -> "Connected" + (spotify.accountName.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "") +
                        if (spotify.canPlay) " · Premium" else ""
                    else -> "Not connected: Enter signs in, any account"
                },
                tone = if (spotify.connected) Row.Tone.GOOD else Row.Tone.QUIET,
                run = {
                    if (spotify.connected) {
                        tui.overlays.addLast(Overlay.Confirm("Disconnect Spotify?", "Your Spotify playlists leave the library, and its likes their hearts.") { state.disconnectSpotify() })
                    } else if (!spotify.connecting) {
                        state.connectSpotify()
                    }
                },
            ),
        )
        add(
            Row.Action(
                "Spotify Premium",
                value = when {
                    spotify.canPlay -> "Signed in: Spotify songs can play in your Spotify app"
                    spotify.connecting -> "Waiting for the browser…"
                    else -> "Enter signs in with Premium, to play in your Spotify app"
                },
                tone = if (spotify.canPlay) Row.Tone.GOOD else Row.Tone.QUIET,
                run = { if (!spotify.connecting) state.connectSpotifyPremium() },
            ),
        )
        if (spotify.canPlay) {
            add(
                Row.Action(
                    "Spotify songs play",
                    value = if (spotify.playsOnSpotify) "On Spotify" else "Matched on YouTube Music",
                    step = { state.setSpotifyPlayback(!spotify.playsOnSpotify) },
                ),
            )
            add(
                Row.Action(
                    "Spotify plays on",
                    value = spotify.devices.firstOrNull { it.id == spotify.device }?.name
                        ?: if (spotify.device.isBlank()) "Wherever Spotify is active" else "The device chosen before",
                    run = { spotifyDevices(tui) },
                ),
            )
        }
        spotify.message?.let { add(Row.Note(it)) }
        // Bandcamp is a name rather than a sign-in: it shows a fan's collection and wishlist to anybody. The name
        // is what is kept; the fan's own name for themselves is known once Bandcamp has confirmed it, here.
        val bandcamp = settings.bandcamp
        val fan = preferences.bandcampUsername
        add(
            Row.Action(
                "Bandcamp collection",
                value = when {
                    bandcamp.checking -> "Checking with Bandcamp…"
                    fan.isBlank() -> "Not set"
                    bandcamp.fanName.isNotBlank() && !bandcamp.fanName.equals(fan, ignoreCase = true) -> "${bandcamp.fanName} · bandcamp.com/$fan"
                    else -> "bandcamp.com/$fan"
                },
                tone = if (fan.isNotBlank() && !bandcamp.checking) Row.Tone.GOOD else Row.Tone.QUIET,
                run = { tui.overlays.addLast(bandcampMenu(tui)) },
            ),
        )
        bandcamp.message?.let { add(Row.Note(it, if (it == BANDCAMP_REMOVED) Row.Tone.QUIET else Row.Tone.WARN)) }
        add(
            Row.Action(
                "Bandcamp on Home",
                value = preferences.bandcampGenres.joinToString { it.displayName }.ifBlank { "Best-sellers and new releases only" },
                run = { tui.overlays.addLast(bandcampGenres(tui)) },
            ),
        )
        // VK: a browser's vk.ru session, pasted, since there is no browser here to sign in with.
        val vk = settings.vk
        add(
            Row.Action(
                "VK Music",
                value = when {
                    vk.checking -> "Checking with VK…"
                    vk.connected -> "Signed in as ${vk.accountName}"
                    else -> "Not signed in: Enter says how"
                },
                tone = when {
                    vk.checking -> Row.Tone.QUIET
                    vk.connected -> Row.Tone.GOOD
                    else -> Row.Tone.QUIET
                },
                run = { if (!vk.checking) tui.overlays.addLast(vkMenu(tui)) },
            ),
        )
        vk.message?.let { add(Row.Note(it)) }
        val scrobbling = settings.scrobbling
        add(
            Row.Action(
                "Last.fm",
                value = when (scrobbling.lastFm.status) {
                    ScrobbleConnectionStatus.CONNECTED -> "Scrobbling as ${scrobbling.lastFm.username.orEmpty()}"
                    ScrobbleConnectionStatus.AWAITING_APPROVAL -> "Approve in the browser, then Enter"
                    ScrobbleConnectionStatus.CONNECTING -> "Connecting…"
                    ScrobbleConnectionStatus.ERROR -> scrobbling.lastFm.message ?: "Not working"
                    ScrobbleConnectionStatus.DISCONNECTED -> "Not connected"
                },
                tone = scrobbleTone(scrobbling.lastFm.status),
                run = {
                    when (scrobbling.lastFm.status) {
                        ScrobbleConnectionStatus.CONNECTED -> tui.overlays.addLast(Overlay.Confirm("Stop scrobbling to Last.fm?") { state.disconnectLastFm() })
                        ScrobbleConnectionStatus.AWAITING_APPROVAL -> state.finishLastFmLogin()
                        else -> state.beginLastFmLogin()
                    }
                },
            ),
        )
        add(
            Row.Action(
                "ListenBrainz",
                value = if (scrobbling.listenBrainz.status == ScrobbleConnectionStatus.CONNECTED) "Scrobbling as ${scrobbling.listenBrainz.username.orEmpty()}" else "Not connected",
                tone = scrobbleTone(scrobbling.listenBrainz.status),
                run = {
                    if (scrobbling.listenBrainz.status == ScrobbleConnectionStatus.CONNECTED) {
                        tui.overlays.addLast(Overlay.Confirm("Stop scrobbling to ListenBrainz?") { state.disconnectListenBrainz() })
                    } else {
                        tui.overlays.addLast(Overlay.Prompt("ListenBrainz", "Your user token, from listenbrainz.org/settings.", secret = true) { if (it.isNotBlank()) state.connectListenBrainz(it) })
                    }
                },
            ),
        )
        val account = state.account.value
        add(
            Row.Action(
                "Noctorium account",
                value = account.user?.let { "Signed in as ${it.displayName.ifBlank { it.email }}" } ?: "Optional: counts your listening, and Connect",
                tone = if (account.signedIn) Row.Tone.GOOD else Row.Tone.QUIET,
                run = {
                    if (account.signedIn) tui.overlays.addLast(Overlay.Confirm("Sign out of your Noctorium account?") { state.signOutOfNoctorium() })
                    else tui.overlays.addLast(
                        Overlay.Prompt("Noctorium account", "Your email address.") { email ->
                            if (email.isBlank()) return@Prompt
                            tui.overlays.addLast(Overlay.Prompt("Noctorium account", "Your password, for $email.", secret = true) { password ->
                                if (password.isNotBlank()) state.logInToNoctorium(email, password)
                            })
                        },
                    )
                },
            ),
        )
        add(Row.Gap)

        add(Row.Header("Look"))
        val themes = ThemePreset.entries.filter { it.colours != null }
        // A Windows theme is in force: read from the settings, which the palette only catches up with at the next frame.
        val windows = preferences.themeSkin != ThemeSkin.STANDARD
        add(
            Row.Action("Theme", value = themeName(preferences.theme), step = { by -> tui.cycleTheme(by) }, run = {
                tui.overlays.addLast(Overlay.Picker("Theme", themes.map { theme -> themeName(theme) to { state.setTheme(theme) } }))
            }),
        )
        add(Row.Action("Accent", value = preferences.accent.displayName, step = { by -> state.setAccent(cycle(AccentPreset.entries, preferences.accent, by)) }))
        add(
            Row.Action("Seek bar", value = preferences.progressBarStyle.displayName, detail = preferences.progressBarStyle.description, step = { by ->
                state.setProgressBarStyle(cycle(ProgressBarStyle.entries, preferences.progressBarStyle, by))
            }),
        )
        val bar = tui.preferences.playerBar
        add(
            Row.Action("Player bar", value = bar.displayName, detail = bar.description, step = { by ->
                tui.preferences.playerBar = cycle(TuiPlayerBar.entries, bar, by)
                tui.preferences.save()
            }),
        )
        // The taskbar's clock, offered only where a taskbar may be showing it: the Taskbar layout's, and the
        // Windows themes'. The setting is core's, so the web page this player serves follows it too.
        if (bar == TuiPlayerBar.TASKBAR || windows) {
            add(
                Row.Action(
                    "Show the clock",
                    value = if (preferences.taskbarClock) "Shown" else "Hidden",
                    detail = "The time at the end of the taskbar's tray, which also sets the sleep timer",
                    step = { state.setTaskbarClock(!preferences.taskbarClock) },
                ),
            )
        }
        val nowPlaying = tui.preferences.nowPlaying
        add(
            Row.Action("Now playing", value = nowPlaying.displayName, detail = nowPlaying.description, step = { by ->
                tui.preferences.nowPlaying = cycle(TuiNowPlaying.entries, nowPlaying, by)
                tui.preferences.save()
            }),
        )
        add(Row.Action("Time on the right", value = preferences.timeDisplay.displayName, step = { by -> state.setTimeDisplay(cycle(TimeDisplay.entries, preferences.timeDisplay, by)) }))
        // The Windows themes are slabs of their own grey or beige, and keep it whichever this says: see Palette.
        val background = when {
            !tui.preferences.terminalBackground -> "The theme's"
            windows -> "The terminal's own, except in the Windows themes"
            else -> "The terminal's own"
        }
        add(
            Row.Action("Background", value = background, step = {
                tui.preferences.terminalBackground = !tui.preferences.terminalBackground
                tui.preferences.save()
                tui.screen.invalidate()
            }),
        )
        add(
            Row.Action("Covers", value = if (tui.preferences.coverArt) "Shown" else "Hidden", step = {
                tui.preferences.coverArt = !tui.preferences.coverArt
                tui.preferences.save()
            }),
        )
        val changedKeys = tui.keys.changes.size
        add(
            Row.Action(
                "Keys",
                value = if (changedKeys == 0) "As they came" else "$changedKeys changed",
                hint = "Every key the player answers to",
                run = {
                    tui.keysOpen = true
                    tui.list("SETTINGS:keys").selected = 0
                },
            ),
        )
        add(Row.Gap)

        add(Row.Header("Playing"))
        add(
            Row.Action(
                "Speed",
                value = speedName(preferences.playbackSpeed) + if (preferences.playbackSpeed == 1f) " (as recorded)" else "",
                step = { by -> tui.changeSpeed(by, say = false) },
            ),
        )
        add(
            Row.Action(
                "When the queue runs out",
                value = if (preferences.autoplay) "Carry on with songs like the last" else "Stop",
                step = { state.setAutoplay(!preferences.autoplay) },
            ),
        )
        // Where autoplay's songs come from, and which it leaves out; both shown with autoplay off too, so they
        // are set before it is switched on.
        add(
            Row.Action(
                "Autoplay draws from",
                value = preferences.autoplayFrom.displayName,
                step = { by -> state.setAutoplayFrom(cycle(AutoplaySource.entries, preferences.autoplayFrom, by)) },
            ),
        )
        add(
            Row.Action(
                "Autoplay skips songs played lately",
                value = onOff(preferences.autoplayAvoidRecent),
                step = { state.setAutoplayAvoidRecent(!preferences.autoplayAvoidRecent) },
            ),
        )
        add(
            Row.Action(
                "Keep the queue between launches",
                value = if (preferences.keepQueue) "On: it is back where it was left" else "Off",
                step = { state.setKeepQueue(!preferences.keepQueue) },
            ),
        )
        add(
            Row.Action(
                "Sleep timer fades out",
                value = if (preferences.sleepFadeSeconds <= 0) "No: it stops at once" else "Over ${preferences.sleepFadeSeconds} seconds",
                step = { by -> state.setSleepFade(cycle(FADES, preferences.sleepFadeSeconds, by)) },
            ),
        )
        val asked = HYBRID_SERVICES.filter { it in preferences.hybridSearch }
        add(
            Row.Action(
                "Hybrid search asks",
                value = if (asked.size == HYBRID_SERVICES.size) "Every service" else asked.joinToString { it.displayName },
                run = { tui.overlays.addLast(hybridSearch(tui)) },
            ),
        )
        add(Row.Action("Skip what is not the music in YouTube videos", value = onOff(preferences.skipNonMusic), step = { state.setSkipNonMusic(!preferences.skipNonMusic) }))
        add(Row.Action("Volume boost", value = onOff(state.playback.value.volumeBoostEnabled), step = { state.toggleVolumeBoost() }))
        // The equaliser's presets, with Off first; a curve of the listener's own, set on the desktop or the
        // phone, shows as itself and is one of the stops, so stepping past it does not lose it.
        val equalizer = preferences.equalizer
        val choices = listOf<EqualizerPreset?>(null) + EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM || equalizer.preset == EqualizerPreset.CUSTOM }
        add(
            Row.Action("Equaliser", value = if (equalizer.enabled) equalizer.preset.displayName else "Off", step = { by ->
                when (val next = cycle(choices, equalizer.preset.takeIf { equalizer.enabled }, by)) {
                    null -> state.updateEqualizer { copy(enabled = false) }
                    else -> state.setEqualizerPreset(next)
                }
            }),
        )
        add(Row.Action("Add what is played here to YouTube history", value = onOff(preferences.youtubeHistory), step = { state.setYouTubeHistory(!preferences.youtubeHistory) }))
        val sources = listOf<LyricsProviderId?>(null) + LyricsProviderId.entries
        add(
            Row.Action("Lyrics from", value = preferences.lyricsProvider?.displayName ?: "Whichever has them", step = { by ->
                when (val next = cycle(sources, preferences.lyricsProvider, by)) {
                    null -> state.clearPreferredLyricsProvider()
                    else -> state.selectLyricsProvider(next)
                }
            }),
        )
        add(
            Row.Action("Discord shows what is playing", value = onOff(preferences.discord.enabled), step = { state.setDiscordPresence(!preferences.discord.enabled) }, run = {
                if (preferences.discord.enabled) state.testDiscordConnection() else state.setDiscordPresence(true)
            }),
        )
        add(
            Row.Action("Downloads go to", value = state.exportFolder()?.toString() ?: "Not set", run = {
                tui.overlays.addLast(Overlay.Prompt("Downloads folder", "Where saved MP3s are written.", state.exportFolder()?.toString().orEmpty()) { if (it.isNotBlank()) state.setExportFolder(it) })
            }),
        )
        add(Row.Gap)

        add(Row.Header("The tools Noctorium plays with"))
        val tools = PlaybackToolInstaller.state.value
        PlaybackTool.entries.forEach { tool ->
            val status = tools.status(tool)
            add(
                Row.Action(
                    tool.displayName,
                    value = when {
                        tools.installing == tool -> "Installing… ${tools.progress?.let { "${(it * 100).toInt()}%" }.orEmpty()}"
                        status == null -> "Not checked yet"
                        status.origin == ToolOrigin.MISSING -> "Missing: Enter installs it"
                        else -> listOfNotNull(status.version, status.origin.name.lowercase()).joinToString(" · ")
                    },
                    tone = if (status?.origin == ToolOrigin.MISSING) Row.Tone.BAD else Row.Tone.QUIET,
                    run = {
                        io.launch {
                            val problem = PlaybackToolInstaller.install(tool)
                            tui.toast(problem ?: "${tool.displayName} is ready", if (problem == null) Row.Tone.GOOD else Row.Tone.BAD, 8)
                        }
                    },
                ),
            )
        }
        // Updating: the check by hand, and whether it happens by itself once a day. A copy that cannot replace
        // itself -- a build, or one a package manager owns -- is still told about new versions, and says how
        // it should be updated instead.
        val updates = tui.parts.updates
        val updating = updates.status.value
        add(
            Row.Action(
                "Check for updates",
                value = when {
                    updating.downloading != null -> "Downloading… ${(updating.downloading * 100).toInt()}%"
                    updating.checking -> "Checking…"
                    updating.waiting != null -> "$cliVersion · ${updating.waiting} takes over when you quit"
                    else -> cliVersion
                },
                tone = if (updating.waiting != null) Row.Tone.GOOD else Row.Tone.NORMAL,
                run = { tui.checkForUpdates() },
            ),
        )
        val automatic = preferences.updates.checkOnLaunch
        add(
            Row.Action(
                if (updates.canInstall) "Update automatically" else "Look for updates once a day",
                value = if (CliUpdates.disabledByEnvironment(System::getenv)) "Off: ${CliUpdates.ENVIRONMENT} is set" else onOff(automatic),
                step = { state.setUpdateCheckOnLaunch(!automatic) },
            ),
        )
        if (!updates.canInstall) add(Row.Note(updates.installer.installation.advice))
        add(Row.Action("Run the diagnostics", hint = "yt-dlp, mpv, the network and the folders", run = { state.runDiagnostics(); tui.toast("Checking…") }))
        settings.diagnostics.forEach { result ->
            add(Row.Note("${result.name}: ${result.detail}", when (result.level.name) { "PASS" -> Row.Tone.GOOD; "WARNING" -> Row.Tone.WARN; else -> Row.Tone.BAD }))
        }
        add(Row.Note("Noctorium CLI $cliVersion · its data: ${CliHome.own ?: "nowhere"}"))
    }

    fun deviceRows(tui: Tui): List<Row> = buildList {
        val state = tui.state
        val connect = state.connect.value
        val preferences = state.settings.value.preferences
        add(Row.Header("The web player"))
        val address = tui.web?.address
        add(
            Row.Action(
                "Web player",
                value = address ?: "Off",
                tone = if (address != null) Row.Tone.GOOD else Row.Tone.QUIET,
                hint = "Your library and queue in any browser in the house",
                run = { tui.toggleWeb() },
            ),
        )
        add(Row.Note("Starts a page this computer serves, for a phone, a tablet or another computer on your network. ${tui.key(KeyAction.WEB_PLAYER)} does the same anywhere."))
        add(Row.Gap)

        add(Row.Header("Noctorium Connect"))
        add(Row.Action("Connect", value = onOff(preferences.connect.enabled), step = { state.setConnectEnabled(!preferences.connect.enabled) }))
        add(
            Row.Action("This device is called", value = connect.thisDevice.ifBlank { preferences.connect.deviceName.ifBlank { "This computer" } }, run = {
                tui.overlays.addLast(Overlay.Prompt("Device name", "What other devices see this one as.", connect.thisDevice) { if (it.isNotBlank()) state.renameThisDevice(it) })
            }),
        )
        when {
            !preferences.connect.enabled -> add(Row.Note("Connect is off."))
            !connect.available -> add(Row.Note("Connect needs your Noctorium account: sign in under Settings, on every device.", Row.Tone.WARN))
            connect.devices.isEmpty() -> add(Row.Note("No other device found on this network yet. Open Noctorium on one."))
        }
        connect.target?.let { target ->
            add(Row.Action("Playing on ${target.name}", value = "Enter brings it back here", tone = Row.Tone.ACCENT, run = { state.bringPlaybackBack() }))
            add(Row.Action("Stop controlling ${target.name}", run = { state.stopControlling() }))
        }
        connect.controlledBy?.let { add(Row.Note("$it is playing music on this device.", Row.Tone.ACCENT)) }
        connect.devices.forEach { peer ->
            add(
                Row.Action(
                    peer.name,
                    value = if (connect.target?.id == peer.id) "playing there" else "Enter: play there",
                    hint = peer.kind.name.lowercase(),
                    tone = Row.Tone.NORMAL,
                    run = { state.playOn(peer) },
                ),
            )
        }
    }

    private fun youTubeMenu(tui: Tui): Overlay {
        val state = tui.state
        val signedIn = state.settings.value.youtubeAccount.status == AccountConnectionStatus.CONNECTED
        val options = buildList<Pair<String, () -> Unit>> {
            if (DesktopSignIn.youTubeAvailable()) add("Use the sign-in from Noctorium on this computer" to {
                DesktopSignIn.copyYouTube(state)?.let { tui.toast(it, Row.Tone.BAD, 8) } ?: tui.toast("Copied the desktop's YouTube Music session", Row.Tone.GOOD)
            })
            add("Sign in with your phone (scan a code)" to { state.receiveYouTubeSignIn() })
            add("From a cookies.txt file…" to {
                tui.overlays.addLast(Overlay.Prompt("YouTube Music cookies", "The path of a cookies.txt exported from a signed-in browser.") { path ->
                    if (path.isNotBlank()) DesktopSignIn.fromCookies(state, youTube = true, path = Path.of(path.trim('"')), text = null)?.let { tui.toast(it, Row.Tone.BAD, 8) }
                })
            })
            add("Paste cookies…" to {
                tui.overlays.addLast(Overlay.Prompt("YouTube Music cookies", "Paste a Cookie header or a cookie export, then Enter.", secret = true) { text ->
                    if (text.isNotBlank()) DesktopSignIn.fromCookies(state, youTube = true, path = null, text = text)?.let { tui.toast(it, Row.Tone.BAD, 8) }
                })
            })
            if (signedIn) {
                add("Choose which channel to act as" to {
                    state.loadYouTubeChannels()
                    io.launch {
                        kotlinx.coroutines.delay(1500)
                        val channels = state.likes.value.youTubeChannels
                        if (channels.isEmpty()) tui.toast("No other channels on this account")
                        else tui.overlays.addLast(Overlay.Picker("Act as", channels.map { channel ->
                            (channel.name + (channel.handle?.let { "  $it" } ?: "")) to { state.setYouTubeChannel(channel) }
                        }))
                        tui.invalidate()
                    }
                })
                add("Check the session" to { state.checkYouTubeSignIn() })
                add("Sign out" to { tui.overlays.addLast(Overlay.Confirm("Sign out of YouTube Music here?", "The desktop and the phone stay signed in.") { state.disconnectAccount(ProviderType.YOUTUBE_MUSIC) }) })
            }
        }
        return Overlay.Picker("YouTube Music", options, "A terminal cannot show Google's sign-in page, so the session comes from somewhere already signed in.")
    }

    private fun soundCloudMenu(tui: Tui): Overlay {
        val state = tui.state
        val signedIn = state.settings.value.soundCloudAccount.status == AccountConnectionStatus.CONNECTED
        val options = buildList<Pair<String, () -> Unit>> {
            if (DesktopSignIn.soundCloudAvailable()) add("Use the sign-in from Noctorium on this computer" to {
                DesktopSignIn.copySoundCloud(state)?.let { tui.toast(it, Row.Tone.BAD, 8) } ?: tui.toast("Copied the desktop's SoundCloud session", Row.Tone.GOOD)
            })
            add("From a cookies.txt file…" to {
                tui.overlays.addLast(Overlay.Prompt("SoundCloud cookies", "The path of a cookies.txt exported from a browser signed in to SoundCloud.") { path ->
                    if (path.isNotBlank()) DesktopSignIn.fromCookies(state, youTube = false, path = Path.of(path.trim('"')), text = null)?.let { tui.toast(it, Row.Tone.BAD, 8) }
                })
            })
            add("Paste cookies…" to {
                tui.overlays.addLast(Overlay.Prompt("SoundCloud cookies", "Paste a Cookie header or a cookie export, then Enter.", secret = true) { text ->
                    if (text.isNotBlank()) DesktopSignIn.fromCookies(state, youTube = false, path = null, text = text)?.let { tui.toast(it, Row.Tone.BAD, 8) }
                })
            })
            add("Your profile name…" to {
                tui.overlays.addLast(Overlay.Prompt("SoundCloud profile", "The name in soundcloud.com/<name>, which is how SoundCloud finds your playlists.", state.settings.value.preferences.soundCloudUsername) {
                    if (it.isNotBlank()) state.setSoundCloudUsername(it.removePrefix("https://soundcloud.com/").trim('/'))
                })
            })
            if (signedIn) add("Sign out" to { tui.overlays.addLast(Overlay.Confirm("Sign out of SoundCloud here?") { state.disconnectAccount(ProviderType.SOUNDCLOUD) }) })
        }
        return Overlay.Picker("SoundCloud", options)
    }

    private fun bandcampMenu(tui: Tui): Overlay {
        val state = tui.state
        val current = state.settings.value.preferences.bandcampUsername
        val desktop = DesktopSignIn.bandcampName()?.takeIf { !it.equals(current, ignoreCase = true) }
        val options = buildList<Pair<String, () -> Unit>> {
            desktop?.let { name -> add("Use bandcamp.com/$name, from Noctorium on this computer" to { state.setBandcampUsername(name) }) }
            add((if (current.isBlank()) "Your name, from bandcamp.com/<name>…" else "Change the name…") to {
                tui.overlays.addLast(
                    Overlay.Prompt("Bandcamp", "The name at the end of your Bandcamp address, bandcamp.com/<name>, or the address.", current) {
                        if (it.isNotBlank()) state.setBandcampUsername(it)
                    },
                )
            })
            if (current.isNotBlank()) add("Take the collection out of the library" to {
                tui.overlays.addLast(
                    Overlay.Confirm("Take your Bandcamp collection out of the library?", "Nothing changes on Bandcamp; the name is forgotten here.") {
                        state.setBandcampUsername("")
                    },
                )
            })
        }
        return Overlay.Picker("Bandcamp", options, "Not a sign-in: a collection is public.")
    }

    /** Which genres Home has a row of Bandcamp's best-sellers for, ticked in a list of all of them. */
    private fun bandcampGenres(tui: Tui): Overlay {
        val state = tui.state
        val genres = BandcampGenre.entries
        return Overlay.Checklist(
            "Bandcamp on Home",
            genres.map { it.displayName },
            state.settings.value.preferences.bandcampGenres.map(genres::indexOf),
            "A row of best-sellers for each · Space ticks · Esc when done",
        ) { chosen ->
            val picked = chosen.map { genres[it] }
            if (picked != state.settings.value.preferences.bandcampGenres) state.setBandcampGenres(picked)
        }
    }

    /**
     * Core's word for a collection taken out on purpose, the one Bandcamp message that is not a problem. Only its
     * colour depends on this.
     */
    private const val BANDCAMP_REMOVED = "Bandcamp collection removed."

    /**
     * Where the account's Spotify is open, asked of Spotify afresh, to choose which one plays Spotify songs.
     *
     * Spotify answers in a moment, and the list is shown once it has -- or after a few seconds, with whatever
     * is known, when the answer changed nothing that could be seen.
     */
    private fun spotifyDevices(tui: Tui) {
        val state = tui.state
        val before = state.settings.value.spotify
        state.refreshSpotifyDevices()
        tui.toast("Asking Spotify where it is open…")
        io.launch {
            withTimeoutOrNull(3_000) { state.settings.first { it.spotify !== before } }
            val spotify = state.settings.value.spotify
            if (spotify.devices.isEmpty()) {
                tui.toast(spotify.message ?: "Spotify is not open anywhere right now.", Row.Tone.WARN, 8)
            } else {
                val options = buildList<Pair<String, () -> Unit>> {
                    add(("Wherever Spotify is active" + if (spotify.device.isBlank()) "  ✓" else "") to { state.chooseSpotifyDevice("") })
                    spotify.devices.forEach { device ->
                        val about = listOfNotNull(
                            device.type.takeIf(String::isNotBlank),
                            "playing".takeIf { device.isActive },
                            "takes no commands".takeIf { device.isRestricted },
                        ).joinToString(" · ")
                        val label = device.name + (if (about.isNotBlank()) "  $about" else "") + if (device.id == spotify.device) "  ✓" else ""
                        add(label to { state.chooseSpotifyDevice(device.id) })
                    }
                }
                tui.overlays.addLast(Overlay.Picker("Play Spotify songs on", options, "Spotify's app has to be open there."))
            }
            tui.invalidate()
        }
    }

    /**
     * VK, signed in or not. Signing in is pasting a browser's session, after saying plainly what that means:
     * a terminal cannot show VK's sign-in page, and VK gives no other way in.
     */
    private fun vkMenu(tui: Tui): Overlay {
        val state = tui.state
        if (!state.settings.value.vk.connected) return vkNotice(tui)
        return Overlay.Picker(
            "VK Music",
            listOf(
                "Sign in again, with another browser's cookies…" to { tui.overlays.addLast(vkNotice(tui)) },
                "Sign out" to {
                    tui.overlays.addLast(Overlay.Confirm("Sign out of VK here?", "The session is forgotten on this computer; nothing changes at VK.") { state.disconnectVk() })
                },
            ),
        )
    }

    private fun vkNotice(tui: Tui): Overlay = Overlay.Notice("Signing in to VK", VK_NOTICE + VK_COOKIES_HOW) {
        tui.overlays.addLast(
            Overlay.Prompt("VK cookies", "p and remixsid, pasted as they are: p=…; remixsid=…", secret = true) { text ->
                if (text.isNotBlank()) tui.state.completeVkSignIn(text)
            },
        )
    }

    /** Which services a Hybrid search asks, ticked in a list of all of them and kept once it is put away. */
    private fun hybridSearch(tui: Tui): Overlay {
        val state = tui.state
        val asked = state.settings.value.preferences.hybridSearch
        return Overlay.Checklist(
            "Hybrid search asks",
            HYBRID_SERVICES.map { it.displayName },
            HYBRID_SERVICES.indices.filter { HYBRID_SERVICES[it] in asked },
            "Spotify only while its songs play on Spotify; VK once signed in",
        ) { chosen ->
            val wanted = chosen.map { HYBRID_SERVICES[it] }.toSet()
            // Added before any is taken out, so the list is never empty on the way: core keeps one at least.
            (wanted - asked).forEach { state.setHybridSearchService(it, true) }
            (asked - wanted).forEach { state.setHybridSearchService(it, false) }
        }
    }

    /**
     * The keys, a row for each thing a key does, grouped by where it works. Enter asks for a new key, Delete puts
     * the one chosen back as it came, and the first row puts them all back.
     */
    fun keyRows(tui: Tui): List<Row> = buildList {
        val keys = tui.keys
        add(
            Row.Action(
                "Put every key back as it came",
                value = if (keys.changes.isEmpty()) "Nothing is changed" else "${keys.changes.size} changed",
                tone = if (keys.changes.isEmpty()) Row.Tone.QUIET else Row.Tone.ACCENT,
                run = {
                    if (keys.changes.isNotEmpty()) {
                        tui.overlays.addLast(Overlay.Confirm("Put every key back as it came?", "Each changed key goes back to its default.") { tui.useKeys(KeyMap.DEFAULT) })
                    }
                },
            ),
        )
        add(Row.Note("The arrows, Enter, Escape, Tab and the keys inside a list or a question stay as they are."))
        KeyScope.entries.forEach { scope ->
            add(Row.Gap)
            add(Row.Header(scope.title))
            KeyAction.entries.filter { it.scope == scope }.forEach { action ->
                val changed = action in keys.changes
                val putBack = {
                    val back = tui.keys.reset(action)
                    if (back != null) {
                        tui.useKeys(back)
                        tui.toast("${action.label}: ${back.names(action)} again")
                    } else {
                        tui.toast("Its old key has another job now; change that one first", Row.Tone.WARN)
                    }
                }
                add(
                    Row.Action(
                        action.label,
                        value = keys.names(action) + if (changed) "   (was ${action.defaults.joinToString(" ") { KeyMap.describe(it) }})" else "",
                        tone = if (changed) Row.Tone.ACCENT else Row.Tone.NORMAL,
                        run = { tui.overlays.addLast(Overlay.KeyCapture(action)) },
                        remove = putBack.takeIf { changed },
                    ),
                )
            }
        }
    }

    /** The speeds the keys and the setting step through, from half to double. */
    val SPEEDS = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** A speed as people say it: 1×, 1.25×, 0.75×. */
    fun speedName(speed: Float): String =
        "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.') + "×"

    /** How long a sleep timer can fade out over, in seconds; none first. */
    private val FADES = listOf(0, 15, 30, 60)

    /** Every service a Hybrid search can ask, in the order Settings shows them. */
    private val HYBRID_SERVICES = DEFAULT_HYBRID_SEARCH.toList()

    /** What signing in to VK means, before anybody does it. Shown here, by `noctorium login vk`, and on the web. */
    val VK_NOTICE = listOf(
        "VK offers no music to other apps, so Noctorium uses your vk.ru session the way VK's own web player does.",
        "That is against VK's terms. VK may ask you to confirm it is you, or freeze an account it thinks is automated.",
        "Many songs do not play outside Russia, and VK's songs cannot be downloaded.",
    )

    /** Where the two cookies are. */
    val VK_COOKIES_HOW = listOf(
        "In a browser signed in to vk.ru, open the developer tools' cookies: copy p from login.vk.ru, and remixsid " +
            "from vk.ru. They stay on this computer, in its credential store.",
    )

    /**
     * "Catppuccin Mocha", "Noctorium Night", and "Nord" rather than "Nord Nord" -- or "Noctorium 98" rather than
     * "Windows Noctorium 98": a name that already starts with a family's, its own or another's, says whose it is.
     */
    fun themeName(theme: ThemePreset): String = when {
        ThemePreset.entries.any { theme.displayName.startsWith(it.family) } -> theme.displayName
        else -> "${theme.family} ${theme.displayName}"
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"

    private fun tone(status: AccountConnectionStatus) = when (status) {
        AccountConnectionStatus.CONNECTED -> Row.Tone.GOOD
        AccountConnectionStatus.CHECKING -> Row.Tone.QUIET
        AccountConnectionStatus.WARNING -> Row.Tone.WARN
        AccountConnectionStatus.ERROR -> Row.Tone.BAD
        AccountConnectionStatus.DISCONNECTED -> Row.Tone.QUIET
    }

    private fun scrobbleTone(status: ScrobbleConnectionStatus) = when (status) {
        ScrobbleConnectionStatus.CONNECTED -> Row.Tone.GOOD
        ScrobbleConnectionStatus.ERROR -> Row.Tone.BAD
        ScrobbleConnectionStatus.AWAITING_APPROVAL -> Row.Tone.WARN
        else -> Row.Tone.QUIET
    }

    fun <T> cycle(options: List<T>, current: T, by: Int): T {
        val at = options.indexOf(current).coerceAtLeast(0)
        return options[((at + by) % options.size + options.size) % options.size]
    }
}
