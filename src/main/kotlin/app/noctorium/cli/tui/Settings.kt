package app.noctorium.cli.tui

import app.noctorium.bandcamp.BandcampGenre
import app.noctorium.cli.CliHome
import app.noctorium.cli.DesktopSignIn
import app.noctorium.cli.TerminalBridge
import app.noctorium.cli.cliVersion
import app.noctorium.cli.update.CliUpdates
import app.noctorium.domain.ProviderType
import app.noctorium.domain.betaWarning
import app.noctorium.lyrics.LyricsProviderId
import app.noctorium.playback.PlaybackTool
import app.noctorium.playback.PlaybackToolInstaller
import app.noctorium.playback.ToolOrigin
import app.noctorium.playback.VolumeBoost
import app.noctorium.scrobble.AudioscrobblerSite
import app.noctorium.settings.AccentPreset
import app.noctorium.settings.EqualizerPreset
import app.noctorium.settings.AccountConnectionStatus
import app.noctorium.settings.AutoplaySource
import app.noctorium.settings.HomePlay
import app.noctorium.settings.PlayerButton
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.QueueColumn
import app.noctorium.settings.ScrobbleConnectionStatus
import app.noctorium.settings.ScrobbleServiceState
import app.noctorium.settings.HYBRID_SEARCH_SERVICES
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.ThemeSkin
import app.noctorium.settings.TimeDisplay
import app.noctorium.settings.VisualiserStyle
import app.noctorium.settings.showsPlayerButton
import app.noctorium.settings.themeSkin
import app.noctorium.settings.withPlayerButton
import app.noctorium.tidal.TidalAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * Settings and Devices, as lists of things to change.
 *
 * Settings is in the sections every Noctorium has -- You, Look, Listening, Services, This device, About -- and the
 * search above it finds any of them by name, by what it says it does, or by another word for it ([SettingsIndex]).
 * Every value here is the same setting the desktop has, kept in this program's own settings file -- except the
 * ones that only mean something in a terminal, such as the layouts and the symbols, which live in [TuiPreferences].
 * Each setting's row carries the id the search knows it by.
 */
object Settings {
    private val io = CoroutineScope(Dispatchers.IO)

    /** The sections, in their order, as the search names them too. */
    const val YOU = "You"
    const val LOOK = "Look"
    const val LISTENING = "Listening"
    const val SERVICES = "Services"
    const val DEVICE = "This device"
    const val ABOUT = "About"
    val SECTIONS = listOf(YOU, LOOK, LISTENING, SERVICES, DEVICE, ABOUT)

    /** The booster's stops for the arrows in Settings: finer where it is quieter, a hundred at a time above 300%. */
    val BOOST_STEPS = listOf(100, 125, 150, 175, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000)

    fun rows(tui: Tui): List<Row> = buildList {
        val state = tui.state
        val settings = state.settings.value
        val preferences = settings.preferences
        val likes = state.likes.value

        // --- You: the account that counts the listening, and where else the listening goes. ---
        add(Row.Header(YOU))
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
                id = "account",
            ),
        )
        // The switch at the top of Devices, here too, where Settings is looked in for it: the desktop has it by the account.
        add(connectSwitch(tui, detail = true))
        val scrobbling = settings.scrobbling
        addAll(
            audioscrobbler(tui, AudioscrobblerSite.LAST_FM, scrobbling.lastFm, "lastfm", state::beginLastFmLogin, state::finishLastFmLogin, state::disconnectLastFm),
        )
        addAll(
            audioscrobbler(tui, AudioscrobblerSite.LIBRE_FM, scrobbling.libreFm, "librefm", state::beginLibreFmLogin, state::finishLibreFmLogin, state::disconnectLibreFm),
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
                id = "listenbrainz",
            ),
        )
        add(
            Row.Action("Discord shows what is playing", value = onOff(preferences.discord.enabled), step = { state.setDiscordPresence(!preferences.discord.enabled) }, run = {
                if (preferences.discord.enabled) state.testDiscordConnection() else state.setDiscordPresence(true)
            }, id = "discord"),
        )
        add(Row.Gap)

        // --- Look ---
        add(Row.Header(LOOK))
        val themes = ThemePreset.entries.filter { it.colours != null }
        // A Windows theme is in force: read from the settings, which the palette only catches up with at the next frame.
        val windows = preferences.themeSkin != ThemeSkin.STANDARD
        add(
            Row.Action("Theme", value = themeName(preferences.theme), step = { by -> tui.cycleTheme(by) }, run = {
                tui.overlays.addLast(Overlay.Picker("Theme", themes.map { theme -> themeName(theme) to { state.setTheme(theme) } }))
            }, id = "theme"),
        )
        add(Row.Action("Accent", value = preferences.accent.displayName, step = { by -> state.setAccent(cycle(AccentPreset.entries, preferences.accent, by)) }, id = "accent"))
        val glyphs = tui.preferences.glyphs
        add(
            Row.Action("Symbols", value = glyphs.displayName, detail = glyphs.description, step = { by ->
                tui.preferences.glyphs = cycle(TuiGlyphs.entries, glyphs, by)
                tui.preferences.save()
            }, id = "glyphs"),
        )
        val bar = tui.preferences.playerBar
        add(
            Row.Action("Player bar", value = bar.displayName, detail = bar.description, step = { by ->
                tui.preferences.playerBar = cycle(TuiPlayerBar.entries, bar, by)
                tui.preferences.save()
            }, id = "playerBar"),
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
                    id = "taskbarClock",
                ),
            )
        }
        // Toolbar 2's buttons, offered with it: core's player-bar buttons, kept as the desktop's bar keeps them --
        // Stop there once asked for, any of the rest put away.
        if (bar == TuiPlayerBar.TOOLBAR_2) {
            val shown = PlayerBar.TOOLBAR_2_BUTTONS.filter { preferences.desktop.showsPlayerButton(it) }
            add(
                Row.Action(
                    "Player bar buttons",
                    value = if (shown.isEmpty()) "None" else shown.joinToString(", ") { it.displayName },
                    detail = "Which Toolbar 2 has after the slider, and Stop after next",
                    run = { tui.overlays.addLast(playerButtons(tui)) },
                    id = "playerButtons",
                ),
            )
        }
        add(
            Row.Action("Seek bar", value = preferences.progressBarStyle.displayName, detail = preferences.progressBarStyle.description, step = { by ->
                state.setProgressBarStyle(cycle(ProgressBarStyle.entries, preferences.progressBarStyle, by))
            }, id = "seekBar"),
        )
        val nowPlaying = tui.preferences.nowPlaying
        add(
            Row.Action("Now playing", value = nowPlaying.displayName, detail = nowPlaying.description, step = { by ->
                tui.preferences.nowPlaying = cycle(TuiNowPlaying.entries, nowPlaying, by)
                tui.preferences.save()
            }, id = "nowPlaying"),
        )
        // The Playlist layout's own: shared with every Noctorium, so the desktop's and the web page's follow them.
        add(
            Row.Action("Visualiser", value = preferences.visualiser.displayName, detail = preferences.visualiser.description, step = { by ->
                state.setVisualiser(cycle(VisualiserStyle.entries, preferences.visualiser, by))
            }, id = "visualiser"),
        )
        val columns = QueueColumn.normalised(preferences.queueColumns)
        add(
            Row.Action(
                "Queue table columns",
                value = columns.joinToString(", ") { it.heading },
                detail = "In the Playlist layout of Now playing",
                run = { tui.overlays.addLast(queueColumns(tui)) },
                id = "queueColumns",
            ),
        )
        add(
            Row.Action(
                "Status bar",
                value = if (preferences.statusBar) "Shown" else "Hidden",
                detail = "What is playing and how far into the queue, under the Playlist layout",
                step = { state.setStatusBar(!preferences.statusBar) },
                id = "statusBar",
            ),
        )
        add(Row.Action("Time on the right", value = preferences.timeDisplay.displayName, step = { by -> state.setTimeDisplay(cycle(TimeDisplay.entries, preferences.timeDisplay, by)) }, id = "timeDisplay"))
        add(
            Row.Action("Covers", value = if (tui.preferences.coverArt) "Shown" else "Hidden", step = {
                tui.preferences.coverArt = !tui.preferences.coverArt
                tui.preferences.save()
            }, id = "covers"),
        )
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
            }, id = "background"),
        )
        add(Row.Gap)

        // --- Listening ---
        add(Row.Header(LISTENING))
        add(boosterRow(tui))
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
            }, id = "equaliser"),
        )
        add(
            Row.Action(
                "Speed",
                value = speedName(preferences.playbackSpeed) + if (preferences.playbackSpeed == 1f) " (as recorded)" else "",
                step = { by -> tui.changeSpeed(by, say = false) },
                id = "speed",
            ),
        )
        add(
            Row.Action(
                "When the queue runs out",
                value = if (preferences.autoplay) "Carry on with songs like the last" else "Stop",
                step = { state.setAutoplay(!preferences.autoplay) },
                id = "autoplay",
            ),
        )
        // Where autoplay's songs come from, and which it leaves out; both shown with autoplay off too, so they
        // are set before it is switched on.
        add(
            Row.Action(
                "Autoplay draws from",
                value = preferences.autoplayFrom.displayName,
                step = { by -> state.setAutoplayFrom(cycle(AutoplaySource.entries, preferences.autoplayFrom, by)) },
                id = "autoplayFrom",
            ),
        )
        add(
            Row.Action(
                "Autoplay skips songs played lately",
                value = onOff(preferences.autoplayAvoidRecent),
                step = { state.setAutoplayAvoidRecent(!preferences.autoplayAvoidRecent) },
                id = "avoidRecent",
            ),
        )
        // With autoplay off there is no radio to start, and core plays the rest of the shelf whatever this says.
        add(
            Row.Action(
                "Playing a song from Home or a search",
                value = preferences.homePlay.displayName,
                detail = if (preferences.autoplay) preferences.homePlay.description else "Autoplay is off, so the songs around it play",
                step = { by -> state.setHomePlay(cycle(HomePlay.entries, preferences.homePlay, by)) },
                id = "homePlay",
            ),
        )
        add(
            Row.Action(
                "Keep the queue between launches",
                value = if (preferences.keepQueue) "On: it is back where it was left" else "Off",
                step = { state.setKeepQueue(!preferences.keepQueue) },
                id = "keepQueue",
            ),
        )
        add(
            Row.Action(
                "Sleep timer fades out",
                value = if (preferences.sleepFadeSeconds <= 0) "No: it stops at once" else "Over ${preferences.sleepFadeSeconds} seconds",
                step = { by -> state.setSleepFade(cycle(FADES, preferences.sleepFadeSeconds, by)) },
                id = "sleepFade",
            ),
        )
        val asked = HYBRID_SERVICES.filter { it in preferences.hybridSearch }
        // The ones left out, where they are fewer: a fresh install asks every service but Apple Music, Deezer and Tidal.
        val left = HYBRID_SERVICES - asked.toSet()
        add(
            Row.Action(
                "Hybrid search asks",
                value = when {
                    left.isEmpty() -> "Every service"
                    left.size < asked.size -> "Every service but " + names(left)
                    else -> asked.joinToString { it.displayName }
                },
                run = { tui.overlays.addLast(hybridSearch(tui)) },
                id = "hybridSearch",
            ),
        )
        add(Row.Action("Skip what is not the music in YouTube videos", value = onOff(preferences.skipNonMusic), step = { state.setSkipNonMusic(!preferences.skipNonMusic) }, id = "skipNonMusic"))
        add(Row.Action("Add what is played here to YouTube history", value = onOff(preferences.youtubeHistory), step = { state.setYouTubeHistory(!preferences.youtubeHistory) }, id = "youtubeHistory"))
        val sources = listOf<LyricsProviderId?>(null) + LyricsProviderId.entries
        add(
            Row.Action("Lyrics from", value = preferences.lyricsProvider?.displayName ?: "Whichever has them", step = { by ->
                when (val next = cycle(sources, preferences.lyricsProvider, by)) {
                    null -> state.clearPreferredLyricsProvider()
                    else -> state.selectLyricsProvider(next)
                }
            }, id = "lyrics"),
        )
        add(Row.Gap)

        // --- Services: nothing much happens until one is signed in. ---
        add(Row.Header(SERVICES))
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
                id = "youtube",
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
                id = "soundcloud",
            ),
        )
        // Spotify has two sign-ins, one asking more than the other: any account, for the library, likes, search
        // and two rows of Home, its songs played matched on YouTube Music; and Premium, which also lets the
        // account's own Spotify app play them, wherever it is open. Either opens the browser on this computer.
        val spotify = settings.spotify
        betaWarning(ProviderType.SPOTIFY)
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
                id = "spotify",
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
                id = "spotifyPremium",
            ),
        )
        if (spotify.canPlay) {
            add(
                Row.Action(
                    "Spotify songs play",
                    value = if (spotify.playsOnSpotify) "On Spotify" else "Matched on YouTube Music",
                    step = { state.setSpotifyPlayback(!spotify.playsOnSpotify) },
                    id = "spotifyPlayback",
                ),
            )
            add(
                Row.Action(
                    "Spotify plays on",
                    value = spotify.devices.firstOrNull { it.id == spotify.device }?.name
                        ?: if (spotify.device.isBlank()) "Wherever Spotify is active" else "The device chosen before",
                    run = { spotifyDevices(tui) },
                    id = "spotifyDevice",
                ),
            )
        }
        spotify.message?.let { add(Row.Note(it)) }
        // Bandcamp is a name rather than a sign-in: it shows a fan's collection and wishlist to anybody. The name
        // is what is kept; the fan's own name for themselves is known once Bandcamp has confirmed it, here.
        val bandcamp = settings.bandcamp
        val fan = preferences.bandcampUsername
        betaWarning(ProviderType.BANDCAMP)
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
                id = "bandcamp",
            ),
        )
        bandcamp.message?.let { add(Row.Note(it, if (it == BANDCAMP_REMOVED) Row.Tone.QUIET else Row.Tone.WARN)) }
        add(
            Row.Action(
                "Bandcamp on Home",
                value = preferences.bandcampGenres.joinToString { it.displayName }.ifBlank { "Best-sellers and new releases only" },
                run = { tui.overlays.addLast(bandcampGenres(tui)) },
                id = "bandcampGenres",
            ),
        )
        // VK: a browser's vk.ru session, pasted, since there is no browser here to sign in with.
        val vk = settings.vk
        betaWarning(ProviderType.VK)
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
                id = "vk",
            ),
        )
        vk.message?.let { add(Row.Note(it)) }
        // Apple Music, Deezer and Tidal are searched, and their links opened, with nothing set up here; what is set up
        // adds the library and Home's rows. None of them hands out its audio, so every song is matched on YouTube Music.
        addAll(appleMusic(tui))
        addAll(deezer(tui))
        addAll(tidal(tui))
        add(Row.Gap)

        // --- This device: the keys, where things are kept, and the tools that play. ---
        add(Row.Header(DEVICE))
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
                id = "keys",
            ),
        )
        add(
            Row.Action("Downloads go to", value = state.exportFolder()?.toString() ?: "Not set", run = {
                tui.overlays.addLast(Overlay.Prompt("Downloads folder", "Where saved MP3s are written.", state.exportFolder()?.toString().orEmpty()) { if (it.isNotBlank()) state.setExportFolder(it) })
            }, id = "downloads"),
        )
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
                    id = "tool:${tool.name}",
                ),
            )
        }
        add(Row.Gap)

        // --- About: this copy, keeping it up to date, and what it finds when it looks at itself. ---
        add(Row.Header(ABOUT))
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
                id = "updates",
            ),
        )
        val automatic = preferences.updates.checkOnLaunch
        add(
            Row.Action(
                if (updates.canInstall) "Update automatically" else "Look for updates once a day",
                value = if (CliUpdates.disabledByEnvironment(System::getenv)) "Off: ${CliUpdates.ENVIRONMENT} is set" else onOff(automatic),
                step = { state.setUpdateCheckOnLaunch(!automatic) },
                id = "autoUpdate",
            ),
        )
        if (!updates.canInstall) add(Row.Note(updates.installer.installation.advice))
        add(Row.Action("Run the diagnostics", hint = "yt-dlp, mpv, the network and the folders", run = { state.runDiagnostics(); tui.toast("Checking…") }, id = "diagnostics"))
        settings.diagnostics.forEach { result ->
            add(Row.Note("${result.name}: ${result.detail}", when (result.level.name) { "PASS" -> Row.Tone.GOOD; "WARNING" -> Row.Tone.WARN; else -> Row.Tone.BAD }))
        }
        add(Row.Note("Noctorium CLI $cliVersion · its data: ${CliHome.own ?: "nowhere"}"))
    }

    /**
     * The volume booster: its level, from 100% (off) to 1000%, stepped through [BOOST_STEPS] by the arrows, and the
     * volume panel on Enter. Loud past 200%, and not there at all where Spotify's own app is playing.
     */
    private fun boosterRow(tui: Tui): Row.Action {
        val state = tui.state
        val level = VolumeBoost.clamp(state.settings.value.preferences.volumeBoostPercent)
        val available = state.playback.value.volumeBoostAvailable
        val on = VolumeBoost.isOn(level)
        return Row.Action(
            "Volume booster",
            value = when {
                !available -> "Not available here" + if (on) " (${VolumeBoost.percentLabel(level)} kept)" else ""
                on -> "${VolumeBoost.percentLabel(level)} · ${VolumeBoost.timesLabel(level)}"
                else -> "Off"
            },
            tone = if (available && VolumeBoost.isLoud(level)) Row.Tone.WARN else Row.Tone.NORMAL,
            detail = when {
                !available -> VolumeBoost.UNAVAILABLE
                VolumeBoost.isLoud(level) -> VolumeBoost.LOUD_WARNING
                else -> "Up to 1000%, always behind a limiter · Enter for the slider"
            },
            step = { by ->
                if (!available) {
                    tui.toast(VolumeBoost.UNAVAILABLE, Row.Tone.WARN)
                } else {
                    val next = if (by > 0) BOOST_STEPS.firstOrNull { it > level } ?: BOOST_STEPS.last() else BOOST_STEPS.lastOrNull { it < level } ?: BOOST_STEPS.first()
                    state.setVolumeBoostPercent(next)
                }
            },
            run = { tui.overlays.addLast(VolumePanel(booster = true)) },
            id = "booster",
        )
    }

    /** The queue table's columns, ticked in a list of all of them, in the order they were ticked; the title is always one. */
    private fun queueColumns(tui: Tui): Overlay {
        val state = tui.state
        val all = QueueColumn.entries
        return Overlay.Checklist(
            "Queue table columns",
            all.map { if (it == QueueColumn.TITLE) "${it.displayName} (always)" else it.displayName },
            QueueColumn.normalised(state.settings.value.preferences.queueColumns).map(all::indexOf),
            "In the order ticked · Space ticks · Esc when done",
        ) { chosen ->
            val picked = QueueColumn.normalised(chosen.map { all[it] })
            if (picked != state.settings.value.preferences.queueColumns) state.setQueueColumns(picked)
        }
    }

    /**
     * Toolbar 2's buttons, each ticked or not: Stop is one the listener asks for, the rest are there until put
     * away. Kept where core keeps the desktop bar's, in this program's own settings file, so the choice is the
     * terminal's alone.
     */
    private fun playerButtons(tui: Tui): Overlay {
        val state = tui.state
        val all = PlayerBar.TOOLBAR_2_BUTTONS
        return Overlay.Checklist(
            "Player bar buttons",
            all.map { if (it == PlayerButton.STOP) "${it.displayName}, after next" else it.displayName },
            all.indices.filter { state.settings.value.preferences.desktop.showsPlayerButton(all[it]) },
            "Space ticks · Esc when done · a button showing something under way stays",
        ) { chosen ->
            val ticked = chosen.map { all[it] }.toSet()
            val changed = all.filter { (it in ticked) != state.settings.value.preferences.desktop.showsPlayerButton(it) }
            if (changed.isNotEmpty()) state.updateDesktop { changed.fold(this) { desktop, button -> desktop.withPlayerButton(button, button in ticked) } }
        }
    }

    /**
     * Last.fm or Libre.fm, which sign in the same way: Enter connects, finishes a sign-in waiting to be allowed, or asks
     * before it stops scrobbling.
     *
     * While a sign-in waits, the page to allow Noctorium on is under it, whole, with Copy link -- for a browser of the
     * listener's own, on this computer or another device -- and Open again. On Linux the browser often never opened,
     * and over ssh none can; allowing Noctorium on the page from any browser connects it here by itself.
     */
    private fun audioscrobbler(
        tui: Tui,
        site: AudioscrobblerSite,
        service: ScrobbleServiceState,
        id: String,
        connect: () -> Unit,
        finish: () -> Unit,
        disconnect: () -> Unit,
    ): List<Row> = buildList {
        val state = tui.state
        val name = site.displayName
        // Waiting on a page there is to allow it on. Core also says it waits after a Finish that found no sign-in
        // begun at all, with nothing to allow: Enter then begins one.
        val link = service.approvalUrl?.takeIf { service.status == ScrobbleConnectionStatus.AWAITING_APPROVAL }
        add(
            Row.Action(
                name,
                value = when {
                    service.status == ScrobbleConnectionStatus.CONNECTED -> "Scrobbling as ${service.username.orEmpty()}"
                    link != null -> "Allow Noctorium on its page"
                    service.status == ScrobbleConnectionStatus.CONNECTING -> "Connecting…"
                    service.status == ScrobbleConnectionStatus.DISCONNECTED -> "Not connected"
                    else -> service.message ?: "Not working"
                },
                detail = "it connects by itself, or Enter".takeIf { link != null },
                tone = scrobbleTone(service.status),
                run = {
                    when {
                        service.status == ScrobbleConnectionStatus.CONNECTED -> tui.overlays.addLast(Overlay.Confirm("Stop scrobbling to $name?") { disconnect() })
                        link != null -> finish()
                        else -> connect()
                    }
                },
                id = id,
            ),
        )
        if (link == null) return@buildList
        add(Row.Note(link, Row.Tone.ACCENT))
        add(Row.Action("Copy link", hint = "For a browser here or on another device", run = { state.copyScrobblerSignInLink(site) }, id = "${id}Link"))
        add(Row.Action("Open again", hint = "In this computer's browser", run = { state.openExternalUrl(link) }, id = "${id}Link"))
    }

    /**
     * What Noctorium Connect on and off mean, in the desktop's words: said under its switch on Devices, and after it
     * in Settings while it is chosen.
     */
    fun connectSays(on: Boolean): String =
        if (on) "This computer finds your other devices, and they can find it."
        else "Off: this computer is not looking for your other devices, and they cannot see it."

    /** Noctorium Connect's switch, the same row on Devices and in Settings: Enter, a click or the arrows turn it on or off. */
    private fun connectSwitch(tui: Tui, detail: Boolean): Row.Action {
        val state = tui.state
        val on = state.settings.value.preferences.connect.enabled
        return Row.Action(
            "Noctorium Connect",
            value = onOff(on),
            step = { state.setConnectEnabled(!on) },
            detail = if (detail) connectSays(on) else null,
            id = "connect",
        )
    }

    /**
     * Devices: Noctorium Connect's switch first, so whether this computer can be found at all is the first thing the
     * page says; then, while it is on, what this computer is called and the other devices, and while it is off only
     * that it is; then the web player.
     */
    fun deviceRows(tui: Tui): List<Row> = buildList {
        val state = tui.state
        val connect = state.connect.value
        val preferences = state.settings.value.preferences
        val on = preferences.connect.enabled
        add(connectSwitch(tui, detail = false))
        add(Row.Note(connectSays(on)))
        if (on) {
            add(
                Row.Action("This device is called", value = connect.thisDevice.ifBlank { preferences.connect.deviceName.ifBlank { "This computer" } }, run = {
                    tui.overlays.addLast(Overlay.Prompt("Device name", "What other devices see this one as.", connect.thisDevice) { if (it.isNotBlank()) state.renameThisDevice(it) })
                }),
            )
            add(Row.Gap)
            add(Row.Header("Your other devices"))
            when {
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
        } else {
            // Off is a choice, not a fault: no list and nothing to put right, only what is so and how to undo it.
            add(Row.Gap)
            add(Row.Note("Noctorium Connect is off", Row.Tone.NORMAL))
            add(Row.Note("Turn it on above to see your other devices here and play on them."))
        }
        add(Row.Gap)

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

    /**
     * [provider]'s beta warning above its sign-in, where it has one: core decides which services are in beta and
     * says the same of them in every player. Apart from the service before it, so it is plain whose it is, and in the
     * warning's colour, wrapped to [WARNING_WIDTH] since a note is a line.
     */
    private fun MutableList<Row>.betaWarning(provider: ProviderType) {
        val warning = provider.betaWarning ?: return
        if (lastOrNull() !is Row.Header) add(Row.Gap)
        wrapWords(warning, WARNING_WIDTH).forEach { add(Row.Note(it, Row.Tone.WARN)) }
    }

    /**
     * How wide the beta warning's lines are: what an 80-column terminal's list has room for, whatever this one has. Not
     * the list's own width, which would move every row under the warnings each time the terminal is resized, and a
     * setting jumped to by its row with them.
     */
    const val WARNING_WIDTH = 78

    /**
     * Apple Music: signed in with the token Apple's own web player keeps once somebody has signed in on Apple's page.
     * A terminal cannot show that page, so the token is pasted from a browser that is signed in, after saying where
     * it is (see [APPLE_MUSIC_HOW]); core checks it with Apple before keeping it. Its developer token is the web
     * player's unless the listener has one of their own, made with a MusicKit key of theirs.
     */
    private fun appleMusic(tui: Tui): List<Row> = buildList {
        val state = tui.state
        val apple = state.settings.value.appleMusic
        betaWarning(ProviderType.APPLE_MUSIC)
        add(
            Row.Action(
                "Apple Music",
                value = when {
                    apple.checking -> "Checking with Apple…"
                    apple.connected -> "Signed in" + apple.storefront.takeIf(String::isNotBlank)?.let { " · the ${it.uppercase()} store" }.orEmpty()
                    else -> "Not signed in: Enter says how"
                },
                detail = "search and links work without".takeIf { !apple.connected && !apple.checking },
                tone = if (apple.connected && !apple.checking) Row.Tone.GOOD else Row.Tone.QUIET,
                run = { if (!apple.checking) tui.overlays.addLast(appleMusicMenu(tui)) },
                id = "appleMusic",
            ),
        )
        add(
            Row.Action(
                "Apple Music developer token",
                value = if (apple.ownDeveloperToken) "Your own" else "Optional: the web player's is used",
                run = { tui.overlays.addLast(appleMusicDeveloperToken(tui)) },
                id = "appleMusicToken",
            ),
        )
        apple.message?.let { add(Row.Note(it)) }
    }

    private fun appleMusicMenu(tui: Tui): Overlay {
        val state = tui.state
        if (!state.settings.value.appleMusic.connected) return appleMusicNotice(tui)
        return Overlay.Picker(
            "Apple Music",
            listOf(
                "Sign in again, with another token…" to { tui.overlays.addLast(appleMusicNotice(tui)) },
                "Sign out" to {
                    tui.overlays.addLast(Overlay.Confirm("Sign out of Apple Music here?", "The token is forgotten on this computer; nothing changes at Apple.") { state.disconnectAppleMusic() })
                },
            ),
        )
    }

    private fun appleMusicNotice(tui: Tui): Overlay =
        Overlay.Notice("Signing in to Apple Music", listOfNotNull(ProviderType.APPLE_MUSIC.betaWarning) + APPLE_MUSIC_HOW) {
            tui.overlays.addLast(
                Overlay.Prompt("Apple Music sign-in", "The media-user-token cookie, or a Cookie: line with it in.", secret = true) { text ->
                    if (text.isNotBlank()) tui.state.completeAppleMusicSignIn(text)
                },
            )
        }

    /** A developer token of the listener's own, or the web player's again: once one is kept, Enter offers either. */
    private fun appleMusicDeveloperToken(tui: Tui): Overlay {
        val state = tui.state
        val ask = Overlay.Prompt("Apple Music developer token", "A MusicKit developer token of your own, made with your key.", secret = true) { token ->
            if (token.isNotBlank()) state.setAppleMusicDeveloperToken(token)
        }
        if (!state.settings.value.appleMusic.ownDeveloperToken) return ask
        return Overlay.Picker(
            "Apple Music developer token",
            listOf(
                "Another token of your own…" to { tui.overlays.addLast(ask) },
                "Use the web player's again" to { state.setAppleMusicDeveloperToken("") },
            ),
        )
    }

    /**
     * Deezer: a public profile rather than a sign-in, as Bandcamp's collection is -- Deezer shows one to anybody, and
     * lets no new application sign anybody in. Its link, a share link or its number is all it takes; core checks it
     * with Deezer before keeping it, and the profile's own name is known once it has.
     */
    private fun deezer(tui: Tui): List<Row> = buildList {
        val deezer = tui.state.settings.value.deezer
        betaWarning(ProviderType.DEEZER)
        add(
            Row.Action(
                "Deezer profile",
                value = when {
                    deezer.checking -> "Checking with Deezer…"
                    deezer.found -> listOfNotNull(deezer.name.takeIf(String::isNotBlank), "deezer.com/profile/${deezer.profileId}").joinToString(" · ")
                    else -> "Not set"
                },
                detail = "search and links work without one".takeIf { !deezer.found && !deezer.checking },
                tone = if (deezer.found && !deezer.checking) Row.Tone.GOOD else Row.Tone.QUIET,
                run = { if (!deezer.checking) tui.overlays.addLast(deezerMenu(tui)) },
                id = "deezer",
            ),
        )
        deezer.message?.let { add(Row.Note(it, if (it == DEEZER_REMOVED || it.startsWith(DEEZER_SHOWN)) Row.Tone.QUIET else Row.Tone.WARN)) }
    }

    private fun deezerMenu(tui: Tui): Overlay {
        val state = tui.state
        val current = state.settings.value.deezer.profileId
        val ask = Overlay.Prompt("Deezer", "Your profile's link, deezer.com/profile/<number>, a share link, or the number.", current) {
            if (it.isNotBlank()) state.setDeezerProfile(it)
        }
        if (current.isBlank()) return ask
        return Overlay.Picker(
            "Deezer",
            listOf(
                "Another profile…" to { tui.overlays.addLast(ask) },
                "Take the profile out of the library" to {
                    tui.overlays.addLast(Overlay.Confirm("Take your Deezer profile out of the library?", "Nothing changes on Deezer; the profile is forgotten here.") { state.setDeezerProfile("") })
                },
            ),
            "Not a sign-in: a profile is public.",
        )
    }

    /**
     * Tidal: its own sign-in page, through a Tidal app of the listener's own until Noctorium has one -- registered,
     * free, at developer.tidal.com, with the redirect address here among its redirect URIs. Tidal sends its answer to
     * that address, 127.0.0.1 on this computer, so the browser has to be on this one; over ssh that is said first.
     */
    private fun tidal(tui: Tui): List<Row> = buildList {
        val state = tui.state
        val tidal = state.settings.value.tidal
        val remote = overSsh()
        betaWarning(ProviderType.TIDAL)
        add(
            Row.Action(
                "Tidal",
                value = when {
                    tidal.connecting -> "Waiting for the browser…"
                    tidal.connected -> "Connected" + tidal.accountName.takeIf(String::isNotBlank)?.let { " as $it" }.orEmpty()
                    !tidal.configured -> "Needs a Tidal app of your own first"
                    else -> "Not connected: Enter signs in"
                },
                detail = when {
                    tidal.connected || tidal.connecting -> null
                    !tidal.configured -> "Enter sets one up"
                    remote -> "with a browser here only"
                    tidal.readsCatalogue -> "search works without"
                    else -> "with this computer's browser"
                },
                tone = if (tidal.connected) Row.Tone.GOOD else Row.Tone.QUIET,
                run = {
                    when {
                        tidal.connected -> tui.overlays.addLast(
                            Overlay.Confirm("Disconnect Tidal?", "Your Tidal collection leaves the library; nothing changes at Tidal.") { state.disconnectTidal() },
                        )
                        tidal.connecting -> Unit
                        !tidal.configured -> tui.overlays.addLast(tidalAppMenu(tui))
                        remote -> tui.overlays.addLast(Overlay.Notice("Signing in to Tidal", TIDAL_OVER_SSH, "Enter opens it on this computer anyway") { state.connectTidal() })
                        else -> state.connectTidal()
                    }
                },
                id = "tidal",
            ),
        )
        if (remote && !tidal.connected) add(Row.Note(TIDAL_OVER_SSH_SHORT, Row.Tone.WARN))
        add(
            Row.Action(
                "Use your own Tidal app",
                value = when {
                    !tidal.ownApp -> "Not set: Enter gives its client id and secret"
                    tidal.readsCatalogue && !tidal.connected -> "Yours, with its secret: Tidal is searched signed out"
                    else -> "Yours"
                },
                tone = if (tidal.ownApp) Row.Tone.NORMAL else Row.Tone.QUIET,
                run = { tui.overlays.addLast(tidalAppMenu(tui)) },
                id = "tidalApp",
            ),
        )
        add(
            Row.Action(
                "Tidal redirect address",
                value = tidal.redirectUri,
                detail = "your app's redirect URIs need it exactly · Enter copies it",
                run = { state.copyTidalRedirectUri() },
                id = "tidalRedirect",
            ),
        )
        tidal.message?.let { add(Row.Note(it)) }
    }

    private fun tidalAppMenu(tui: Tui): Overlay {
        val state = tui.state
        val own = state.settings.value.tidal.ownApp
        val options = buildList<Pair<String, () -> Unit>> {
            add((if (own) "Another client id and secret…" else "Its client id and secret…") to { tui.overlays.addLast(tidalClientId(tui)) })
            add("Copy the redirect address to register" to { state.copyTidalRedirectUri() })
            add("Open Tidal's developer dashboard" to { state.openTidalDeveloperPortal() })
            if (own) add("Stop using it" to {
                tui.overlays.addLast(
                    Overlay.Confirm("Stop using your own Tidal app?", "Its client id and secret are forgotten here, and the sign-in through it.") {
                        state.configureTidalApplication("", "")
                    },
                )
            })
        }
        return Overlay.Picker("Your own Tidal app", options, "Free to register, at developer.tidal.com.")
    }

    /** A Tidal app's client id, and then its secret, which may be left blank to sign in only. */
    private fun tidalClientId(tui: Tui): Overlay = Overlay.Prompt("Your own Tidal app", "Its client id, from developer.tidal.com.") { id ->
        if (id.isBlank()) return@Prompt
        tui.overlays.addLast(
            Overlay.Prompt("Your own Tidal app", "Its client secret, for Tidal to be searched signed out. Blank to sign in only.", secret = true) { secret ->
                tui.state.configureTidalApplication(id, secret)
            },
        )
    }

    private fun vkNotice(tui: Tui): Overlay = Overlay.Notice("Signing in to VK", listOfNotNull(ProviderType.VK.betaWarning) + VK_NOTICE + VK_COOKIES_HOW) {
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
            "Spotify only while its songs play on Spotify; VK once signed in; Tidal with an app",
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

    /**
     * Every service a Hybrid search can ask, in the order Settings shows them: core's whole list, Apple Music, Deezer
     * and Tidal among them, which a fresh install leaves out of its search but anybody can tick.
     */
    private val HYBRID_SERVICES = HYBRID_SEARCH_SERVICES

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
     * How Apple Music is signed in to from a terminal, which has no browser to show Apple's page in: shown here, by
     * `noctorium login applemusic`, and on the web.
     */
    val APPLE_MUSIC_HOW = listOf(
        "A terminal cannot show Apple's sign-in page, so the sign-in comes from a browser that can: sign in at " +
            "music.apple.com there, and Apple's web player keeps your Apple Music token in its media-user-token cookie.",
        "Open the browser's developer tools on music.apple.com and find its cookies (under Application in Chrome and " +
            "Edge, Storage in Firefox and Safari), then copy the value of media-user-token. A request's whole Cookie: " +
            "line, from the Network tab, does as well. It stays on this computer, in its credential store.",
        "Search, albums and links work without signing in; signing in adds your playlists and songs to the library, " +
            "and Home's rows from Apple Music.",
    )

    /** What Tidal's sign-in needs over ssh, said before it is begun: shown here, and by `noctorium login tidal`. */
    val TIDAL_OVER_SSH = listOf(
        "This terminal is reached over ssh, and Tidal's sign-in needs a browser on this computer: Tidal sends its " +
            "answer to ${TidalAuth.redirectUri()}, which only a browser here can reach. Sign in at this computer, or " +
            "forward the port from the one you are at first: ssh -L ${TidalAuth.REDIRECT_PORT}:127.0.0.1:${TidalAuth.REDIRECT_PORT}.",
    )

    /** The same, under Tidal's row in Settings. */
    private val TIDAL_OVER_SSH_SHORT =
        "Over ssh: Tidal's sign-in needs a browser on this computer, or its port forwarded " +
            "(ssh -L ${TidalAuth.REDIRECT_PORT}:127.0.0.1:${TidalAuth.REDIRECT_PORT})."

    /** Whether this terminal is reached over ssh; a test says so of its own. */
    internal var overSsh: () -> Boolean = { TerminalBridge.overSsh() }

    /**
     * Core's words for a Deezer profile taken out on purpose, and the start of its words for one found: the Deezer
     * messages that are not a problem. Only their colour, and whether `noctorium login deezer` succeeded, depend on these.
     */
    private const val DEEZER_REMOVED = "Deezer profile removed."
    const val DEEZER_SHOWN = "Showing "

    /**
     * "Catppuccin Mocha", "Noctorium Night", and "Nord" rather than "Nord Nord" -- or "Noctorium 98" rather than
     * "Windows Noctorium 98": a name that already starts with a family's, its own or another's, says whose it is.
     */
    fun themeName(theme: ThemePreset): String = when {
        ThemePreset.entries.any { theme.displayName.startsWith(it.family) } -> theme.displayName
        else -> "${theme.family} ${theme.displayName}"
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"

    /** "Apple Music, Deezer and Tidal". */
    private fun names(services: List<ProviderType>): String =
        if (services.size < 2) services.joinToString { it.displayName }
        else services.dropLast(1).joinToString { it.displayName } + " and " + services.last().displayName

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
