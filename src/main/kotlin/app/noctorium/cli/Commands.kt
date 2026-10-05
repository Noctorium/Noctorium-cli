package app.noctorium.cli

import app.noctorium.cli.tui.Tui
import app.noctorium.cli.tui.formatTime
import app.noctorium.cli.update.CliUpdates
import app.noctorium.cli.web.WebPlayer
import app.noctorium.core.AppState
import app.noctorium.domain.ProviderType
import app.noctorium.playback.MpvPlaybackEngine
import app.noctorium.playback.PlaybackTool
import app.noctorium.playback.PlaybackToolInstaller
import app.noctorium.playback.ToolOrigin
import app.noctorium.settings.AccountConnectionStatus
import app.noctorium.settings.ScrobbleConnectionStatus
import app.noctorium.update.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * What `noctorium` does with what it was given: open the player, or do one thing and stop.
 *
 * The one-thing commands print plain text, coloured when the output is a terminal and NO_COLOR is unset, so
 * they can be read by a person and piped into something else alike.
 */
object Commands {
    private val usage = """
        |Noctorium $cliVersion — YouTube Music and SoundCloud, in a terminal and a browser.
        |
        |  noctorium                     the player, in this terminal
        |  noctorium play <words|link>   the player, already playing the first match
        |  noctorium search <words>      print what both services have, and stop
        |  noctorium web                 your library and queue in any browser in the house
        |      --port <n>                  (7300)
        |      --here-only                 only from this computer, not the network
        |      --no-open                   do not open a browser
        |  noctorium login [youtube|soundcloud]
        |      --from-desktop              copy the sign-in from Noctorium on this computer
        |      --phone                     YouTube Music: scan a code with the phone app
        |      --cookies <file>            a cookies.txt from a signed-in browser
        |  noctorium logout <youtube|soundcloud|spotify|lastfm|listenbrainz>
        |  noctorium status              accounts, tools and where things are kept
        |  noctorium tools               install or update yt-dlp and mpv
        |  noctorium update              install a newer Noctorium CLI, if there is one
        |      --check                     only say whether there is
        |  noctorium version
        |
        |In the player, ? lists the keys.
        |
        |The player and noctorium web look for a newer version once a day and install it by themselves; it
        |takes over when you quit. Settings switches that off, and so does NOCTORIUM_NO_UPDATE=1.
    """.trimMargin()

    fun run(arguments: List<String>): Int {
        val command = arguments.firstOrNull()
        val rest = arguments.drop(1)
        return when (command) {
            null -> player(null)
            "play" -> if (rest.isEmpty()) player(null) else player(rest.joinToString(" "))
            "search", "s" -> search(rest.joinToString(" "))
            "web" -> web(rest)
            "login" -> login(rest)
            "logout" -> logout(rest)
            "status" -> status()
            "tools", "doctor" -> tools()
            "update", "upgrade" -> UpdateCommand.run(rest, CliUpdates.forThisCopy(Version.parse(cliVersion)))
            "version", "--version", "-v", "-V" -> { println("noctorium $cliVersion"); 0 }
            "help", "--help", "-h" -> { println(usage); 0 }
            else -> {
                if (command.startsWith("-")) {
                    System.err.println("Unknown option $command\n\n$usage")
                    2
                } else {
                    // `noctorium daft punk` is a reasonable thing to type, and means play it.
                    player(arguments.joinToString(" "))
                }
            }
        }
    }

    private fun parts(): CliParts = CliParts()

    private fun state(parts: CliParts, engine: app.noctorium.playback.PlaybackEngine) = cliAppState(parts, engine)

    private fun player(startWith: String?): Int {
        val parts = parts()
        if (!ensureTools(quiet = true)) return 1
        // Whatever an interrupted update left beside the folder, cleared before anything else is started.
        runCatching { parts.updates.installer.tidy() }
        val engine = WebPlayer.engineFor(parts, preferBrowser = false)
        val state = state(parts, engine)
        val web = WebPlayer(state, parts, engine)
        return try {
            val tui = Tui(state, parts, web.asSwitch(), startWith)
            if (!tui.screen.interactive) {
                System.err.println("This needs a terminal. For one thing at a time, see: noctorium help")
                return 2
            }
            tui.run()
            0
        } finally {
            web.stop()
            state.close()
        }
    }

    private fun search(query: String): Int {
        if (query.isBlank()) { System.err.println("What to look for: noctorium search <words>"); return 2 }
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        return try {
            state.search(query)
            val results = runBlocking {
                withTimeoutOrNull(30_000) {
                    while (!(state.ui.value.searchQuery == query && !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty())) delay(150)
                    state.ui.value.searchResults
                }
            } ?: state.ui.value.searchResults
            val out = Out()
            if (results.tracks.isEmpty()) {
                println("Nothing found for \"$query\".")
                return 1
            }
            println(out.bold("Tracks"))
            results.tracks.forEachIndexed { i, track ->
                println(
                    "  ${out.dim((i + 1).toString().padStart(2))}  ${out.badge(track.provider)}  ${out.bold(track.title)}  ${out.dim(track.artistLine)}  ${out.dim(formatTime(track.durationMs))}",
                )
                println("      ${out.dim(track.sourceUrl)}")
            }
            if (results.playlists.isNotEmpty()) {
                println()
                println(out.bold("Playlists"))
                results.playlists.forEach { println("  ${out.badge(it.provider)}  ${it.title}  ${out.dim(it.sourceUrl.orEmpty())}") }
            }
            println()
            println(out.dim("Play one: noctorium play <link>"))
            0
        } finally {
            state.close()
        }
    }

    private fun web(options: List<String>): Int {
        var port = 7300
        var network = true
        var open = true
        var i = 0
        while (i < options.size) {
            when (options[i]) {
                "--port", "-p" -> port = options.getOrNull(++i)?.toIntOrNull() ?: run { System.err.println("--port needs a number"); return 2 }
                "--here-only", "--localhost" -> network = false
                "--no-open" -> open = false
                else -> { System.err.println("Unknown option ${options[i]}\n\n$usage"); return 2 }
            }
            i++
        }
        val parts = parts()
        if (!ensureTools(quiet = false)) return 1
        runCatching { parts.updates.installer.tidy() }
        val engine = WebPlayer.engineFor(parts, preferBrowser = true)
        val state = state(parts, engine)
        val web = WebPlayer(state, parts, engine)
        val out = Out()
        val problem = web.start(port, network)
        if (problem != null) {
            System.err.println(problem)
            state.close()
            return 1
        }
        val address = web.address!!
        println()
        println("  ${out.accent("◉")} ${out.bold("Noctorium web player")} is on.")
        println()
        println("  On this computer:  ${out.accent(web.localAddress!!)}")
        if (network && address != web.localAddress) {
            println("  On your network:   ${out.accent(address)}")
            println()
            println("  Scan this with a phone to open it there:")
            println()
            println(Qr.print(address, "    "))
        }
        println()
        println(out.dim("  The link carries a key; anyone with it can play your music, so share it like a password."))
        println(out.dim("  Ctrl+C stops it."))
        parts.bridge.notices = { notice ->
            if (notice is TerminalBridge.Notice.OpenLink && !notice.opened) println("  Open: ${notice.url}")
        }
        if (open) runCatching { parts.bridge.openUrl(web.localAddress!!) }
        // Once a day, in the background, as the player does. What it says goes to this terminal and to every
        // open page; nothing it does touches what is playing, and the new copy only takes over after Ctrl+C.
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        parts.updates.automatically(background, state.settings.value.preferences.updates.checkOnLaunch) { result ->
            parts.updates.headline(result, asked = false)?.let { line ->
                println("  ${if (result is CliUpdates.Result.Failed) out.dim(line) else out.accent(line)}")
                web.announce(line, if (result is CliUpdates.Result.Failed) "normal" else "good")
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { background.cancel(); web.stop(); state.close() })
        web.join()
        return 0
    }

    private fun login(options: List<String>): Int {
        val service = options.firstOrNull { !it.startsWith("--") }?.lowercase() ?: "youtube"
        val youTube = when (service) {
            "youtube", "yt", "youtube-music", "ytm" -> true
            "soundcloud", "sc" -> false
            else -> { System.err.println("Sign in to youtube or soundcloud. Spotify and Last.fm are under Settings in the player."); return 2 }
        }
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        val out = Out()
        parts.bridge.notices = { notice -> if (notice is TerminalBridge.Notice.OpenLink) println("  Open: ${notice.url}") }
        try {
            val cookies = options.indexOf("--cookies").takeIf { it >= 0 }?.let { options.getOrNull(it + 1) }
            val problem = when {
                cookies != null -> DesktopSignIn.fromCookies(state, youTube, Path.of(cookies), null)
                "--phone" in options -> if (youTube) phone(state, out) else "Only YouTube Music can be handed over from the phone."
                "--from-desktop" in options || (if (youTube) DesktopSignIn.youTubeAvailable() else DesktopSignIn.soundCloudAvailable()) ->
                    if (youTube) DesktopSignIn.copyYouTube(state) else DesktopSignIn.copySoundCloud(state)
                youTube -> phone(state, out)
                else -> "Give a cookies.txt with --cookies, or sign in to SoundCloud in Noctorium on this computer and use --from-desktop."
            }
            if (problem != null) {
                System.err.println(problem)
                return 1
            }
            // The check runs in the background; wait for it to say how it went.
            val provider = if (youTube) ProviderType.YOUTUBE_MUSIC else ProviderType.SOUNDCLOUD
            val connected = runBlocking {
                withTimeoutOrNull(20_000) {
                    while (true) {
                        val account = if (youTube) state.settings.value.youtubeAccount else state.settings.value.soundCloudAccount
                        val ready = if (youTube) state.likes.value.youTubeReady else state.likes.value.soundCloudReady
                        if (account.status == AccountConnectionStatus.CONNECTED && ready) return@withTimeoutOrNull true
                        if (account.status == AccountConnectionStatus.ERROR) return@withTimeoutOrNull false
                        state.likes.value.message?.let { if ("no Google session" in it || "refused" in it) return@withTimeoutOrNull false }
                        delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
            } ?: false
            state.likes.value.message?.let { println("  $it") }
            println(if (connected) "  ${out.good("✓")} Signed in to ${provider.displayName}." else "  ${out.warn("!")} ${provider.displayName} did not confirm the session yet; noctorium status says more.")
            return if (connected) 0 else 1
        } finally {
            state.close()
        }
    }

    private fun phone(state: AppState, out: Out): String? {
        state.receiveYouTubeSignIn()
        val code = runBlocking {
            withTimeoutOrNull(5_000) {
                while (state.signInTransfer.value !is AppState.SignInTransfer.Waiting) {
                    (state.signInTransfer.value as? AppState.SignInTransfer.Failed)?.let { return@withTimeoutOrNull null }
                    delay(50)
                }
                (state.signInTransfer.value as AppState.SignInTransfer.Waiting).code
            }
        } ?: return (state.signInTransfer.value as? AppState.SignInTransfer.Failed)?.message ?: "Could not wait for the phone."
        println()
        println("  On your phone: Noctorium → Settings → YouTube Music → ${out.bold("Sign in a computer")}, then scan:")
        println()
        println(Qr.print(code, "    "))
        println()
        println(out.dim("  Both have to be on the same network. Waiting for five minutes…"))
        val result = runBlocking {
            withTimeoutOrNull(5 * 60_000L) {
                while (true) {
                    when (val transfer = state.signInTransfer.value) {
                        is AppState.SignInTransfer.Done -> return@withTimeoutOrNull null
                        is AppState.SignInTransfer.Failed -> return@withTimeoutOrNull transfer.message
                        else -> delay(200)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        }
        return result
    }

    private fun logout(options: List<String>): Int {
        val service = options.firstOrNull()?.lowercase() ?: run { System.err.println("Sign out of which: youtube, soundcloud, spotify, lastfm or listenbrainz?"); return 2 }
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        try {
            when (service) {
                "youtube", "yt", "ytm" -> state.disconnectAccount(ProviderType.YOUTUBE_MUSIC)
                "soundcloud", "sc" -> state.disconnectAccount(ProviderType.SOUNDCLOUD)
                "spotify" -> state.disconnectSpotify()
                "lastfm", "last.fm" -> state.disconnectLastFm()
                "listenbrainz" -> state.disconnectListenBrainz()
                else -> { System.err.println("Not a service Noctorium signs in to: $service"); return 2 }
            }
            Thread.sleep(400)
            println("Signed out of $service here. Noctorium on your other devices is unchanged.")
            return 0
        } finally {
            state.close()
        }
    }

    private fun status(): Int {
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        val out = Out()
        try {
            Thread.sleep(600)
            val settings = state.settings.value
            println(out.bold("Noctorium CLI $cliVersion"))
            println()
            fun line(name: String, on: Boolean, detail: String) = println("  ${if (on) out.good("●") else out.dim("○")} ${name.padEnd(16)} $detail")
            fun account(state: app.noctorium.settings.AccountConnectionState) = when (state.status) {
                AccountConnectionStatus.DISCONNECTED -> "not signed in — noctorium login"
                AccountConnectionStatus.CONNECTED -> state.detail ?: "signed in"
                AccountConnectionStatus.CHECKING -> "checking…"
                else -> state.detail ?: "needs attention"
            }
            line("YouTube Music", settings.youtubeAccount.status == AccountConnectionStatus.CONNECTED, account(settings.youtubeAccount))
            line("SoundCloud", settings.soundCloudAccount.status == AccountConnectionStatus.CONNECTED, account(settings.soundCloudAccount))
            line("Spotify", settings.spotify.connected, if (settings.spotify.connected) settings.spotify.accountName else "not connected")
            line("Last.fm", settings.scrobbling.lastFm.status == ScrobbleConnectionStatus.CONNECTED, settings.scrobbling.lastFm.username ?: "not connected")
            line("ListenBrainz", settings.scrobbling.listenBrainz.status == ScrobbleConnectionStatus.CONNECTED, settings.scrobbling.listenBrainz.username ?: "not connected")
            println()
            PlaybackToolInstaller.refresh()
            PlaybackToolInstaller.state.value.tools.forEach { tool ->
                line(tool.tool.displayName, tool.origin != ToolOrigin.MISSING, tool.path ?: "missing — noctorium tools")
            }
            println()
            println("  ${out.dim("Kept in")}         ${CliHome.own}")
            println("  ${out.dim("Secrets")}         ${if (parts.credentials.persistent) "the system's keyring" else "memory only (no keyring here)"}")
            val updates = parts.updates
            val off = updates.automaticOffBecause(settings.preferences.updates.checkOnLaunch)
            println(
                "  ${out.dim("Updates")}         " + when {
                    !updates.canInstall -> updates.installer.installation.advice
                    off != null -> "installed in ${updates.installer.installation.folder}; automatic updates are off ($off)"
                    else -> "installed in ${updates.installer.installation.folder}; checked for once a day"
                },
            )
            updates.installer.waiting()?.let { println(" ".repeat(18) + "$it is installed beside it, and takes over once this copy has quit") }
            return 0
        } finally {
            state.close()
        }
    }

    private fun tools(): Int = if (ensureTools(quiet = false, force = true)) 0 else 1

    /**
     * yt-dlp and mpv, installed when they are missing -- yt-dlp everywhere, mpv on Windows and the Mac, where
     * there is a build of it to fetch. On Linux a missing mpv is the distribution's to provide, and the command
     * to get it is printed; on a Mac whose download failed, the Homebrew one.
     */
    private fun ensureTools(quiet: Boolean, force: Boolean = false): Boolean {
        PlaybackToolInstaller.refresh()
        val before = PlaybackToolInstaller.state.value
        val missing = before.missingRequired
        if (missing.isEmpty() && !force) return true
        val out = Out()
        if (missing.isNotEmpty()) println("  Noctorium needs ${missing.joinToString(" and ") { it.displayName }} to play music. Getting it…")
        val problems = runBlocking {
            PlaybackTool.entries.mapNotNull { tool ->
                val status = PlaybackToolInstaller.state.value.status(tool)
                if (status != null && status.origin != ToolOrigin.MISSING && !(force && tool == PlaybackTool.YT_DLP)) return@mapNotNull null
                PlaybackToolInstaller.install(tool)?.let { "${tool.displayName}: $it" }
            }
        }
        PlaybackToolInstaller.refresh()
        val after = PlaybackToolInstaller.state.value
        after.tools.forEach { tool ->
            if (!quiet || tool.origin == ToolOrigin.MISSING) {
                println("  ${if (tool.origin == ToolOrigin.MISSING) out.warn("!") else out.good("✓")} ${tool.tool.displayName}  ${out.dim(tool.path ?: "missing")}")
            }
        }
        problems.forEach { println("  ${out.warn("!")} $it") }
        if (after.missingRequired.isNotEmpty()) {
            if (after.missingRequired.contains(PlaybackTool.MPV)) {
                app.noctorium.playback.manualInstallHint(PlaybackTool.MPV, app.noctorium.playback.hostPlatform())?.let { println("  $it") }
            }
            return false
        }
        return true
    }
}

/** Colour for printed output, when there is a terminal to show it and nobody asked for none. */
class Out(private val colour: Boolean = System.console() != null && System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb") {
    private fun wrap(code: String, text: String) = if (colour) "\u001b[${code}m$text\u001b[0m" else text
    fun bold(text: String) = wrap("1", text)
    fun dim(text: String) = wrap("2", text)
    fun accent(text: String) = wrap("1;38;2;180;124;255", text)
    fun good(text: String) = wrap("1;32", text)
    fun warn(text: String) = wrap("1;33", text)
    fun badge(provider: ProviderType) = when (provider) {
        ProviderType.SOUNDCLOUD -> wrap("1;38;2;255;122;26", "SC")
        ProviderType.YOUTUBE_MUSIC -> wrap("1;38;2;255;78;69", "YT")
        ProviderType.YOUTUBE_VIDEO -> wrap("1;38;2;255;78;69", "YV")
        ProviderType.SPOTIFY -> wrap("1;38;2;30;215;96", "SP")
        ProviderType.LOCAL -> "··"
    }
}
