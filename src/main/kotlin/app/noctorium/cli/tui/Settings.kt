package app.noctorium.cli.tui

import app.noctorium.cli.CliHome
import app.noctorium.cli.DesktopSignIn
import app.noctorium.cli.cliVersion
import app.noctorium.domain.ProviderType
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.playback.PlaybackTool
import app.noctorium.playback.PlaybackToolInstaller
import app.noctorium.playback.ToolOrigin
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.AccountConnectionStatus
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ScrobbleConnectionStatus
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.TimeDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Settings and Devices, as lists of things to change.
 *
 * Accounts first, because nothing much happens until one is signed in; then how it looks; then the rest.
 * Every value here is the same setting the desktop has, kept in this program's own settings file -- except
 * the two that only mean something in a terminal, which live in [TuiPreferences].
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
        val spotify = settings.spotify
        add(
            Row.Action(
                "Spotify library",
                value = when {
                    spotify.connected -> "Connected" + (spotify.accountName.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "")
                    spotify.connecting -> "Waiting for the browser…"
                    else -> "Not connected"
                },
                tone = if (spotify.connected) Row.Tone.GOOD else Row.Tone.QUIET,
                run = {
                    if (spotify.connected) tui.overlays.addLast(Overlay.Confirm("Disconnect Spotify?", "Your Spotify playlists leave the library.") { state.disconnectSpotify() })
                    else state.connectSpotify()
                },
            ),
        )
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
        add(
            Row.Action("Theme", value = themeName(preferences.theme), step = { by -> tui.cycleTheme(by) }, run = {
                tui.overlays.addLast(Overlay.Picker("Theme", themes.map { theme -> themeName(theme) to { state.setTheme(theme) } }))
            }),
        )
        add(Row.Action("Accent", value = preferences.accent.displayName, step = { by -> state.setAccent(cycle(AccentPreset.entries, preferences.accent, by)) }))
        add(Row.Action("Seek bar", value = preferences.progressBarStyle.displayName, step = { by -> state.setProgressBarStyle(cycle(ProgressBarStyle.entries, preferences.progressBarStyle, by)) }))
        add(Row.Action("Time on the right", value = preferences.timeDisplay.displayName, step = { by -> state.setTimeDisplay(cycle(TimeDisplay.entries, preferences.timeDisplay, by)) }))
        add(
            Row.Action("Background", value = if (tui.preferences.terminalBackground) "The terminal's own" else "The theme's", step = {
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
        add(Row.Gap)

        add(Row.Header("Playing"))
        add(Row.Action("Skip what is not the music in YouTube videos", value = onOff(preferences.skipNonMusic), step = { state.setSkipNonMusic(!preferences.skipNonMusic) }))
        add(Row.Action("Volume boost", value = onOff(state.playback.value.volumeBoostEnabled), step = { state.toggleVolumeBoost() }))
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
        add(Row.Action("Check for updates", value = state.updates.value.currentVersion.ifBlank { cliVersion }, run = { state.checkForUpdates() }))
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
        add(Row.Note("Starts a page this computer serves, for a phone, a tablet or another computer on your network. w does the same anywhere."))
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

    /** "Catppuccin Mocha", "Noctorium Night", and "Nord" rather than "Nord Nord". */
    fun themeName(theme: ThemePreset): String = when {
        theme.displayName.startsWith(theme.family) -> theme.displayName
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
