package app.noctorium.cli

import app.noctorium.cli.tui.ListKind
import app.noctorium.cli.tui.Settings
import app.noctorium.cli.tui.Tui
import app.noctorium.cli.tui.formatTime
import app.noctorium.cli.tui.kind
import app.noctorium.cli.tui.wrapWords
import app.noctorium.cli.update.CliUpdates
import app.noctorium.cli.web.WebPlayer
import app.noctorium.core.AppState
import app.noctorium.core.SearchMode
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.SearchResults
import app.noctorium.domain.pageUrl
import app.noctorium.playback.MpvPlaybackEngine
import app.noctorium.playback.PlaybackTool
import app.noctorium.playback.PlaybackToolInstaller
import app.noctorium.playback.ToolOrigin
import app.noctorium.settings.AccountConnectionStatus
import app.noctorium.settings.AutoplaySource
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.ScrobbleConnectionStatus
import app.noctorium.settings.SettingsRepository
import app.noctorium.settings.SpotifyPlayback
import app.noctorium.update.Version
import app.noctorium.vk.VkCookies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * What `noctorium` does with what it was given: open the player, or do one thing and stop.
 *
 * The one-thing commands print plain text, coloured when the output is a terminal and NO_COLOR is unset, so
 * they can be read by a person and piped into something else alike.
 */
object Commands {
    private val usage = """
        |Noctorium $cliVersion — YouTube Music, SoundCloud, Bandcamp, Spotify and VK, in a terminal and a browser.
        |
        |  noctorium                     the player, in this terminal
        |  noctorium play <words|link>   the player, already playing the first match
        |  noctorium search <words>      print what the services have, and stop
        |  noctorium web                 your library and queue in any browser in the house
        |      --port <n>                  (7300)
        |      --here-only                 only from this computer, not the network
        |      --no-open                   do not open a browser
        |  noctorium login [youtube|soundcloud]
        |      --from-desktop              copy the sign-in from Noctorium on this computer
        |      --phone                     YouTube Music: scan a code with the phone app
        |      --cookies <file>            a cookies.txt from a signed-in browser
        |  noctorium login bandcamp <name>
        |                                your collection at bandcamp.com/<name>, with no password;
        |                                with no name, the one Noctorium on this computer shows
        |  noctorium login spotify       Spotify's own sign-in, in a browser on this computer
        |      --premium                   Premium: its songs play in your Spotify app
        |  noctorium login vk            VK, through a signed-in browser's session; says what that means
        |      --cookies <text|file>       "p=…; remixsid=…", or a cookies.txt holding them
        |  noctorium logout <youtube|soundcloud|bandcamp|spotify|vk|lastfm|listenbrainz>
        |  noctorium spotify devices     where your Spotify is open, to play its songs there (Premium)
        |  noctorium spotify device <name|any>
        |                                which of them plays Spotify songs
        |  noctorium spotify play-on <spotify|youtube>
        |                                Spotify songs in your Spotify app, or matched on YouTube Music
        |  noctorium settings            how it plays; and to change one:
        |      speed <0.5–2>               slower or faster, keeping the pitch
        |      autoplay <on|off>           carry on with songs like the last when the queue runs out
        |      autoplay-from <same|youtube>
        |                                  from the last song's own service, or YouTube Music's radio
        |      avoid-recent <on|off>       autoplay leaves out songs played lately
        |      keep-queue <on|off>         the queue is kept when Noctorium closes, and picked up again
        |      fade <off|seconds>          how long a sleep timer fades out for
        |      hybrid <service> <on|off>   whether a search of every service asks this one
        |  noctorium status              accounts, tools and where things are kept
        |  noctorium tools               install or update yt-dlp and mpv
        |  noctorium update              install a newer Noctorium CLI, if there is one
        |      --check                     only say whether there is
        |  noctorium version
        |
        |In the player, ? lists the keys, and Settings › Keys changes them.
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
            "spotify" -> spotify(rest)
            "settings" -> settings(rest)
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

    /** One command's AppState: the player and `noctorium web` are [interactive]; see [cliAppState]. */
    private fun state(parts: CliParts, engine: app.noctorium.playback.PlaybackEngine, interactive: Boolean = false) =
        cliAppState(parts, engine, interactive)

    private fun player(startWith: String?): Int {
        // Started from a menu or a shortcut rather than a terminal, there is nowhere to draw the player, and
        // what was wanted is the window (see WindowApp). Decided before anything is started -- the tools,
        // Connect, the engine -- so that either way it happens at once, rather than after seconds of starting
        // a player nobody can see.
        if (WindowApp.startedWithoutTerminal()) {
            if (WindowApp.open()) return 0
            System.err.println("This needs a terminal. For one thing at a time, see: noctorium help")
            return 2
        }
        val parts = parts()
        if (!ensureTools(quiet = true)) return 1
        // Whatever an interrupted update left beside the folder, cleared before anything else is started.
        runCatching { parts.updates.installer.tidy() }
        val engine = WebPlayer.engineFor(parts, preferBrowser = false)
        val state = state(parts, engine, interactive = true)
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
            fun SearchResults.found() = tracks.isNotEmpty() || playlists.isNotEmpty()
            val results = runBlocking {
                withTimeoutOrNull(30_000) {
                    while (!(state.ui.value.searchQuery == query && !state.ui.value.searchLoading && state.ui.value.searchResults.found())) delay(150)
                    state.ui.value.searchResults
                }
            } ?: state.ui.value.searchResults
            val out = Out()
            // Search opens in the mode it was last left in, which core remembers; a narrower one is said.
            val mode = state.ui.value.searchMode
            if (mode != SearchMode.HYBRID) println(out.dim("Searched ${mode.displayName} only, as Search was last left; Tab on the player's Search page changes it.\n"))
            if (!results.found()) {
                println("Nothing found for \"$query\".")
                return 1
            }
            if (results.tracks.isNotEmpty()) {
                println(out.bold("Tracks"))
                results.tracks.forEachIndexed { i, track ->
                    println(
                        "  ${out.dim((i + 1).toString().padStart(2))}  ${out.badge(track.provider)}  ${out.bold(track.title)}  ${out.dim(track.artistLine)}  ${out.dim(formatTime(track.durationMs))}",
                    )
                    println("      ${out.dim(track.pageUrl)}")
                }
            }
            // Bandcamp's and Spotify's albums and artists come as playlists, which is how they open; each is listed
            // as what it is.
            val byKind = results.playlists.groupBy { it.kind }
            val playlists = byKind[ListKind.PLAYLIST].orEmpty()
            val albums = byKind[ListKind.ALBUM].orEmpty()
            val artists = byKind[ListKind.ARTIST].orEmpty()
            var first = results.tracks.isEmpty()
            fun section(title: String, found: List<Playlist>) {
                if (found.isEmpty()) return
                if (!first) println()
                first = false
                println(out.bold(title))
                found.forEach { playlist ->
                    // Spotify gives an artist "Artist" as its owner, which the heading already says.
                    val owner = playlist.ownerName?.takeIf { it.isNotBlank() && !(playlist.kind == ListKind.ARTIST && it == "Artist") }
                        ?.let { "  " + out.dim(it) }.orEmpty()
                    println("  ${out.badge(playlist.provider)}  ${playlist.title}$owner  ${out.dim(playlist.sourceUrl.orEmpty())}")
                }
            }
            section("Playlists", playlists)
            section("Albums", albums)
            section("Artists", artists)
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
        val state = state(parts, engine, interactive = true)
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
        val words = options.filterNot { it.startsWith("--") }
        val service = words.firstOrNull()?.lowercase() ?: "youtube"
        if (service == "bandcamp" || service == "bc") return bandcamp(words.drop(1).joinToString(" "), fromDesktop = "--from-desktop" in options)
        if (service == "spotify" || service == "sp") return spotifyLogin(premium = "--premium" in options)
        if (service == "vk") return vkLogin(options.indexOf("--cookies").takeIf { it >= 0 }?.let { options.getOrNull(it + 1) })
        val youTube = when (service) {
            "youtube", "yt", "youtube-music", "ytm" -> true
            "soundcloud", "sc" -> false
            else -> {
                System.err.println(
                    "Sign in to youtube, soundcloud, spotify or vk, or name your Bandcamp collection: noctorium login bandcamp <name>. " +
                        "Last.fm and ListenBrainz are under Settings in the player.",
                )
                return 2
            }
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

    /**
     * `noctorium login bandcamp <name>`: the collection the library shows, at bandcamp.com/<name>.
     *
     * Not a sign-in. Bandcamp shows a fan's collection and wishlist to anybody, so a name is all it takes and
     * nothing secret is kept. Core checks the name with Bandcamp before keeping it -- a misspelt one would
     * otherwise be an empty library with no reason given -- and this waits for that answer. With no name, the
     * one Noctorium on this computer already shows is used.
     */
    private fun bandcamp(given: String, fromDesktop: Boolean): Int {
        val name = given.trim().ifBlank { DesktopSignIn.bandcampName().orEmpty() }
        if (name.isBlank()) {
            System.err.println(
                if (fromDesktop) "Noctorium on this computer shows no Bandcamp collection."
                else "Whose collection: noctorium login bandcamp <name>, with the name from bandcamp.com/<name>.",
            )
            return 2
        }
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        val out = Out()
        try {
            state.setBandcampUsername(name)
            // Checking is set before the call returns, and cleared with the answer.
            val answered = runBlocking { withTimeoutOrNull(30_000) { while (state.settings.value.bandcamp.checking) delay(150) } } != null
            val bandcamp = state.settings.value.bandcamp
            if (!answered) {
                println("  ${out.warn("!")} Bandcamp did not answer in time. Try again in a moment.")
                return 1
            }
            bandcamp.message?.let { problem ->
                println("  ${out.warn("!")} $problem")
                return 1
            }
            val kept = state.settings.value.preferences.bandcampUsername
            saved { it.bandcampUsername == kept }
            val who = bandcamp.fanName.takeIf { it.isNotBlank() && !it.equals(kept, ignoreCase = true) }?.let { "$it, " }.orEmpty()
            println("  ${out.good("✓")} Your Bandcamp collection is in the library: ${who}bandcamp.com/$kept.")
            return 0
        } finally {
            state.close()
        }
    }

    /**
     * `noctorium login spotify`: Spotify's own consent page, in the browser on this computer, and its answer
     * caught on this computer, at 127.0.0.1 -- so the browser has to be on this one, not on a machine this
     * terminal is reached from. Any account signs in for the library, likes, search and Home, its songs matched
     * on YouTube Music; `--premium` asks as well to play them in the account's own Spotify app.
     */
    private fun spotifyLogin(premium: Boolean): Int {
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        val out = Out()
        parts.bridge.notices = { notice ->
            if (notice is TerminalBridge.Notice.OpenLink) {
                println(if (notice.opened) "  Spotify's sign-in is open in your browser. If nothing appeared: ${notice.url}" else "  Open this in a browser on this computer: ${notice.url}")
            }
        }
        try {
            if (premium) state.connectSpotifyPremium() else state.connectSpotify()
            println(out.dim("  Waiting for Spotify, five minutes at most. The browser has to be on this computer."))
            // Spotify's answer is the first state that is no longer connecting and says something. Waiting for
            // connecting to clear is not enough: AppState publishes Spotify's state once as it opens, in the
            // background, and that can land just after this sign-in has begun.
            runBlocking { withTimeoutOrNull(6 * 60_000L) { state.settings.first { !it.spotify.connecting && it.spotify.message != null } } }
            val spotify = state.settings.value.spotify
            val done = spotify.connected && (!premium || spotify.canPlay)
            if (done) {
                val name = state.settings.value.preferences.spotifyAccountName
                saved { it.spotifyAccountName == name && it.spotifyCanPlay == spotify.canPlay }
            }
            spotify.message?.let { println("  ${if (done) out.good("✓") else out.warn("!")} $it") }
                ?: println(if (done) "  ${out.good("✓")} Spotify is connected." else "  ${out.warn("!")} Spotify did not connect.")
            return if (done) 0 else 1
        } finally {
            state.close()
        }
    }

    /**
     * `noctorium login vk`: VK, through the session of a browser signed in on vk.ru.
     *
     * VK has no sign-in for other apps, and a terminal cannot show its page, so the session's two cookies are
     * given instead -- typed in when asked, or with `--cookies`, as text or a cookies.txt holding them. What
     * that means is said first, every time, before anything is asked for; core checks the session with VK
     * before it keeps it, in the credential store and nowhere else.
     */
    private fun vkLogin(cookies: String?): Int {
        val out = Out()
        println()
        (Settings.VK_NOTICE + Settings.VK_COOKIES_HOW).forEach { paragraph ->
            wrapWords(paragraph, 96).forEach { println("  $it") }
            println()
        }
        val text = cookies?.let(::vkCookieText) ?: ask("  Paste them, as p=…; remixsid=…: ")
        if (text.isNullOrBlank()) {
            System.err.println("No cookies were given, so nothing changed.")
            return 2
        }
        // Read here first, as core reads them, so a paste that is not the two cookies is said at once.
        if (VkCookies.parse(text) == null) {
            System.err.println("Those are not VK's sign-in cookies: both p and remixsid are needed, as p=…; remixsid=…")
            return 2
        }
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        try {
            state.completeVkSignIn(text)
            // VK's answer, or the cookies' refusal, is a state no longer checking that says something -- not merely
            // one no longer checking, which AppState's own first look at VK, as it opens, can also be.
            runBlocking { withTimeoutOrNull(60_000) { state.settings.first { !it.vk.checking && it.vk.message != null } } }
            val vk = state.settings.value.vk
            if (vk.connected && !vk.checking) saved { it.vkAccountName == vk.accountName }
            println("  ${if (vk.connected) out.good("✓") else out.warn("!")} ${vk.message ?: if (vk.connected) "Signed in to VK." else "VK did not answer in time."}")
            return if (vk.connected) 0 else 1
        } finally {
            state.close()
        }
    }

    /**
     * What `--cookies` gave for VK: the cookies as text, or a file holding them -- a cookies.txt exported from a
     * browser, whose lines become `name=value` for core to read. Those marked #HttpOnly_ are cookies too, and
     * remixsid is one.
     */
    internal fun vkCookieText(given: String): String {
        val file = runCatching { Path.of(given) }.getOrNull()?.takeIf { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
        val text = file?.let { runCatching { Files.readString(it) }.getOrNull() } ?: given
        val exported = text.lineSequence()
            .filter { !it.startsWith("#") || it.startsWith("#HttpOnly_") }
            .map { it.split('\t') }
            .filter { it.size >= 7 }
            .map { "${it[5]}=${it[6].trim()}" }
            .toList()
        return if (exported.isNotEmpty()) exported.joinToString("; ") else text.trim()
    }

    /** A line typed at the terminal, not shown as it is typed where the terminal allows that. */
    private fun ask(prompt: String): String? {
        System.console()?.let { console -> return console.readPassword(prompt)?.let { String(it) } }
        print(prompt)
        return readlnOrNull()
    }

    /**
     * `noctorium spotify …`: where Spotify songs play, for an account signed in with Premium. With nothing
     * after it, how Spotify is set up now.
     */
    private fun spotify(options: List<String>): Int {
        val what = options.firstOrNull()?.lowercase()
        if (what != null && what !in setOf("devices", "device", "play-on")) {
            System.err.println("noctorium spotify devices, spotify device <name|any>, or spotify play-on <spotify|youtube>")
            return 2
        }
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        val out = Out()
        try {
            // The sign-in is read from the credential store in the background as AppState opens, and published
            // once that is done; nothing here is asked of Spotify before then.
            runBlocking { withTimeoutOrNull(4_000) { state.settings.first { it.spotify.connected } } }
            val spotify = state.settings.value.spotify
            if (!spotify.connected) {
                println("  ${out.warn("!")} Spotify is not connected: noctorium login spotify --premium")
                return 1
            }
            if (what == null) {
                println("  Spotify       connected${spotify.accountName.takeIf(String::isNotBlank)?.let { " as $it" }.orEmpty()}${if (spotify.canPlay) ", with Premium" else ""}")
                println("  Its songs     ${if (spotify.playsOnSpotify) "play in your Spotify app" else "are matched on YouTube Music"}")
                if (spotify.canPlay) println("  Played on     ${spotify.device.ifBlank { "wherever Spotify is active" }}")
                return 0
            }
            if (what == "play-on") {
                val onSpotify = when (options.getOrNull(1)?.lowercase()) {
                    "spotify" -> true
                    "youtube", "youtube-music", "ytm", "matched" -> false
                    else -> { System.err.println("Play Spotify songs on spotify or youtube?"); return 2 }
                }
                state.setSpotifyPlayback(onSpotify)
                val now = state.settings.value.spotify
                if (onSpotify && !now.canPlay) {
                    println("  ${out.warn("!")} ${now.message ?: "Playing on Spotify needs the Premium sign-in."} noctorium login spotify --premium")
                    return 1
                }
                saved { it.spotifyPlayback == if (onSpotify) SpotifyPlayback.ON_SPOTIFY else SpotifyPlayback.MATCHED }
                println("  ${out.good("✓")} ${now.message ?: "Done."}")
                return 0
            }
            if (!spotify.canPlay) {
                println("  ${out.warn("!")} Choosing where Spotify plays needs the Premium sign-in: noctorium login spotify --premium")
                return 1
            }
            val devices = spotifyDevices(state)
            if (what == "devices") {
                if (devices.isEmpty()) {
                    println("  ${out.warn("!")} ${state.settings.value.spotify.message ?: "Spotify is not open anywhere right now."}")
                    return 1
                }
                val chosen = state.settings.value.spotify.device
                devices.forEach { device ->
                    val about = listOfNotNull(
                        device.type.takeIf(String::isNotBlank),
                        "playing".takeIf { device.isActive },
                        "takes no commands".takeIf { device.isRestricted },
                        "chosen".takeIf { device.id == chosen },
                    ).joinToString(" · ")
                    println("  ${if (device.isActive) out.good("●") else out.dim("○")} ${device.name.padEnd(28)} ${out.dim(about)}")
                }
                if (chosen.isBlank()) println(out.dim("  Spotify songs play wherever Spotify is active."))
                return 0
            }
            // spotify device <name|any>
            val wanted = options.drop(1).joinToString(" ").trim()
            if (wanted.isBlank()) { System.err.println("Which device: noctorium spotify device <name|any>"); return 2 }
            val id = if (wanted.equals("any", ignoreCase = true)) "" else {
                val exact = devices.filter { it.name.equals(wanted, ignoreCase = true) }
                val near = exact.ifEmpty { devices.filter { it.name.contains(wanted, ignoreCase = true) } }
                when (near.size) {
                    1 -> near.single().id
                    0 -> { println("  ${out.warn("!")} No device called \"$wanted\". Spotify is open on: ${devices.joinToString { it.name }.ifBlank { "nothing" }}"); return 1 }
                    else -> { println("  ${out.warn("!")} \"$wanted\" could be ${near.joinToString(" or ") { it.name }}."); return 2 }
                }
            }
            state.chooseSpotifyDevice(id)
            saved { it.spotifyDevice == id }
            println("  ${out.good("✓")} ${state.settings.value.spotify.message ?: "Done."}")
            return 0
        } finally {
            state.close()
        }
    }

    /** Where the account's Spotify is open, asked of Spotify now; empty, with its reason kept, when nowhere. */
    private fun spotifyDevices(state: AppState): List<app.noctorium.spotify.SpotifyDevice> {
        val before = state.settings.value.spotify
        state.refreshSpotifyDevices()
        runBlocking { withTimeoutOrNull(10_000) { state.settings.first { it.spotify !== before } } }
        return state.settings.value.spotify.devices
    }

    /**
     * `noctorium settings …`: how Noctorium plays, the same settings the player's Settings page changes. With
     * nothing after it, what they are; with a name and a value, that one changed.
     */
    private fun settings(options: List<String>): Int {
        val what = options.firstOrNull()?.lowercase()
        val value = options.getOrNull(1)?.lowercase()
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        val out = Out()
        try {
            when (what) {
                null -> Unit
                "speed" -> {
                    val speed = value?.removeSuffix("x")?.removeSuffix("×")?.toFloatOrNull()
                        ?.takeIf { it in app.noctorium.playback.MIN_SPEED..app.noctorium.playback.MAX_SPEED }
                        ?: run { System.err.println("A speed from 0.5 to 2, such as 1.25"); return 2 }
                    state.setPlaybackSpeed(speed)
                    val kept = state.settings.value.preferences.playbackSpeed
                    saved { it.playbackSpeed == kept }
                }
                "autoplay" -> {
                    val on = onOrOff(value) ?: run { System.err.println("autoplay on, or autoplay off"); return 2 }
                    state.setAutoplay(on)
                    saved { it.autoplay == on }
                }
                "autoplay-from" -> {
                    val source = when (value) {
                        "same", "same-service" -> AutoplaySource.SAME_SERVICE
                        "youtube", "youtube-music", "ytm" -> AutoplaySource.YOUTUBE_MUSIC
                        else -> { System.err.println("autoplay-from same, or autoplay-from youtube"); return 2 }
                    }
                    state.setAutoplayFrom(source)
                    saved { it.autoplayFrom == source }
                }
                "avoid-recent" -> {
                    val on = onOrOff(value) ?: run { System.err.println("avoid-recent on, or avoid-recent off"); return 2 }
                    state.setAutoplayAvoidRecent(on)
                    saved { it.autoplayAvoidRecent == on }
                }
                "keep-queue" -> {
                    val on = onOrOff(value) ?: run { System.err.println("keep-queue on, or keep-queue off"); return 2 }
                    state.setKeepQueue(on)
                    // Off forgets the kept queue at once. This command's AppState keeps no queue of its own (see
                    // cliAppState), so the one the player keeps is forgotten here.
                    if (!on) keptQueue(interactive = true).clear()
                    saved { it.keepQueue == on }
                }
                "fade" -> {
                    val seconds = if (value == "off" || value == "no") 0 else value?.removeSuffix("s")?.toIntOrNull()
                    if (seconds == null || seconds < 0) { System.err.println("fade off, or fade with a number of seconds such as 30"); return 2 }
                    state.setSleepFade(seconds)
                    val kept = state.settings.value.preferences.sleepFadeSeconds
                    saved { it.sleepFadeSeconds == kept }
                }
                "hybrid" -> {
                    val service = value?.let(::serviceNamed)
                    val on = onOrOff(options.getOrNull(2)?.lowercase())
                    if (service == null || on == null) {
                        System.err.println("hybrid <youtube-music|youtube-videos|soundcloud|bandcamp|spotify|vk> <on|off>")
                        return 2
                    }
                    state.setHybridSearchService(service, on)
                    val kept = state.settings.value.preferences.hybridSearch
                    if ((service in kept) != on) println("  ${out.warn("!")} At least one service has to answer a search.")
                    saved { it.hybridSearch == kept }
                }
                else -> {
                    System.err.println("There is no setting called $what here: speed, autoplay, autoplay-from, avoid-recent, keep-queue, fade or hybrid.")
                    return 2
                }
            }
            val preferences = state.settings.value.preferences
            fun line(name: String, value: String) = println("  ${name.padEnd(20)} $value")
            line("Speed", Settings.speedName(preferences.playbackSpeed))
            line("Autoplay", if (preferences.autoplay) "on: the queue carries on with songs like the last" else "off")
            line(
                "Autoplay from",
                when (preferences.autoplayFrom) {
                    AutoplaySource.SAME_SERVICE -> "same: the last song's own service"
                    AutoplaySource.YOUTUBE_MUSIC -> "youtube: YouTube Music's radio, whatever the song"
                },
            )
            line("Avoid recent", if (preferences.autoplayAvoidRecent) "on: autoplay leaves out songs played lately" else "off")
            line("Keep the queue", if (preferences.keepQueue) "on: it is picked up where it was left" else "off")
            line("Sleep timer fade", if (preferences.sleepFadeSeconds <= 0) "off" else "${preferences.sleepFadeSeconds} seconds")
            line(
                "Hybrid search asks",
                app.noctorium.settings.DEFAULT_HYBRID_SEARCH.filter { it in preferences.hybridSearch }.joinToString { it.displayName },
            )
            return 0
        } finally {
            state.close()
        }
    }

    private fun onOrOff(value: String?): Boolean? = when (value) {
        "on", "yes", "true" -> true
        "off", "no", "false" -> false
        else -> null
    }

    private fun serviceNamed(name: String): ProviderType? = when (name) {
        "youtube-music", "ytm", "youtube", "yt" -> ProviderType.YOUTUBE_MUSIC
        "youtube-videos", "videos", "yv" -> ProviderType.YOUTUBE_VIDEO
        "soundcloud", "sc" -> ProviderType.SOUNDCLOUD
        "bandcamp", "bc" -> ProviderType.BANDCAMP
        "spotify", "sp" -> ProviderType.SPOTIFY
        "vk" -> ProviderType.VK
        else -> null
    }

    /**
     * Waits, five seconds at most, for what AppState saves in the background to reach the settings file. A
     * command that closes straight after changing a setting would otherwise sometimes stop the save with it.
     */
    private fun saved(done: (NoctoriumPreferences) -> Boolean) {
        val file = SettingsRepository()
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && !done(file.load())) Thread.sleep(100)
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
        val service = options.firstOrNull()?.lowercase() ?: run { System.err.println("Sign out of which: youtube, soundcloud, bandcamp, spotify, vk, lastfm or listenbrainz?"); return 2 }
        val parts = parts()
        val state = state(parts, MpvPlaybackEngine(parts.backend, downloadedFile = parts.downloads::localFile))
        try {
            when (service) {
                "youtube", "yt", "ytm" -> state.disconnectAccount(ProviderType.YOUTUBE_MUSIC)
                "soundcloud", "sc" -> state.disconnectAccount(ProviderType.SOUNDCLOUD)
                // Nothing to sign out of: the name is forgotten, and the collection leaves the library.
                "bandcamp", "bc" -> {
                    state.setBandcampUsername("")
                    saved { it.bandcampUsername.isBlank() }
                    println("Your Bandcamp collection is out of the library here. Noctorium on your other devices is unchanged.")
                    return 0
                }
                "spotify" -> state.disconnectSpotify()
                // The session's cookies are forgotten here; nothing changes at VK.
                "vk" -> {
                    state.disconnectVk()
                    saved { it.vkAccountName.isBlank() }
                    println("Signed out of VK on this computer. Nothing changed at VK itself.")
                    return 0
                }
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
            // Spotify's sign-in is read from the credential store as AppState opens, which on Windows goes
            // through PowerShell and can take longer than the moment above; waited for when one is on record.
            if (state.settings.value.preferences.spotifyAccountName.isNotBlank()) {
                runBlocking { withTimeoutOrNull(3_000) { state.settings.first { it.spotify.connected } } }
            }
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
            val spotify = settings.spotify
            line(
                "Spotify",
                spotify.connected,
                when {
                    !spotify.connected -> "not connected — noctorium login spotify"
                    else -> listOfNotNull(
                        spotify.accountName.takeIf(String::isNotBlank),
                        if (spotify.canPlay) "Premium" else null,
                        if (spotify.playsOnSpotify) "its songs play in your Spotify app" else "its songs are matched on YouTube Music",
                    ).joinToString(" · ")
                },
            )
            val bandcamp = settings.preferences.bandcampUsername
            line("Bandcamp", bandcamp.isNotBlank(), if (bandcamp.isNotBlank()) "bandcamp.com/$bandcamp" else "no collection — noctorium login bandcamp <name>")
            // The name is kept exactly as long as the session is, so it says without waiting for VK's own check.
            val vkName = settings.preferences.vkAccountName
            line("VK Music", vkName.isNotBlank(), vkName.ifBlank { "not signed in — noctorium login vk" })
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
        ProviderType.BANDCAMP -> wrap("1;38;2;98;154;169", "BC")
        ProviderType.VK -> wrap("1;38;2;0;119;255", "VK")
        ProviderType.LOCAL -> "··"
    }
}
