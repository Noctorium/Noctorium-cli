package app.noctorium.cli.tui

import app.noctorium.playback.PlaybackTool
import app.noctorium.settings.SettingEntry
import app.noctorium.settings.SettingMatchKind
import app.noctorium.settings.SettingsSearch

/**
 * The settings search: every setting the terminal player has, as the search every Noctorium shares knows it --
 * its name, the section it lives in, what it does, and the other words people use for it -- and the box above
 * Settings that finds one and jumps to it.
 *
 * The search key opens the box on Settings; what is typed is looked for as it is typed, best first (core's
 * [SettingsSearch]: a name beginning with it, then a word of a name, another word for it, what it says it does,
 * its section; one typo forgiven in a long word). Enter, or a click, goes to the setting in its section, chosen.
 */
object SettingsIndex {
    private fun entry(id: String, title: String, section: String, description: String = "", vararg keywords: String) =
        SettingEntry(id, title, section, description, keywords.toList())

    /** Every setting, in the order Settings lists them. Each [Row.Action] in [Settings.rows] has one of these ids. */
    val ENTRIES: List<SettingEntry> = listOf(
        entry("account", "Noctorium account", Settings.YOU, "Counts your listening, and lets Connect find your other devices.", "sign in", "log in", "login", "profile", "stats"),
        entry(
            "connect", "Noctorium Connect", Settings.YOU, "Whether this computer and your other devices find each other, to move the music between them.",
            "turn off connect", "turn on connect", "disable connect", "enable connect", "devices", "other devices", "play on another device", "cast", "remote", "multiroom",
        ),
        entry("lastfm", "Last.fm", Settings.YOU, "Scrobbles what you play to Last.fm.", "scrobble", "scrobbling", "lastfm"),
        entry("listenbrainz", "ListenBrainz", Settings.YOU, "Scrobbles what you play to ListenBrainz.", "scrobble", "scrobbling", "token"),
        entry("discord", "Discord shows what is playing", Settings.YOU, "Your Discord status shows the song.", "rich presence", "status", "activity"),

        entry("theme", "Theme", Settings.LOOK, "The colours, and the Windows themes drawn as themselves.", "dark mode", "light mode", "colours", "colors", "skin", "windows 98", "windows xp"),
        entry("accent", "Accent", Settings.LOOK, "The colour that picks things out, or one from the cover.", "colour", "color", "highlight", "tint"),
        entry("glyphs", "Symbols", Settings.LOOK, "The buttons' and pages' symbols: the standard ones, plain ASCII, or a Nerd Font's icons.", "icons", "icon set", "glyphs", "nerd font", "ascii", "emoji"),
        entry("playerBar", "Player bar", Settings.LOOK, "How the bar along the foot is laid out: full, compact, a taskbar, or a toolbar as it was or Toolbar 2.", "controls", "toolbar", "toolbar 2", "taskbar", "compact", "footer"),
        entry("taskbarClock", "Show the clock", Settings.LOOK, "The time in the taskbar's tray.", "time", "tray", "clock"),
        entry("playerButtons", "Player bar buttons", Settings.LOOK, "Which buttons Toolbar 2 has: Stop, like, lyrics, the queue, the sleep timer, the speed and the rest.", "player buttons", "buttons", "stop button", "hide buttons", "controls"),
        entry("seekBar", "Seek bar", Settings.LOOK, "The bar that shows how far into the song it is.", "progress bar", "scrubber", "slider", "timeline", "position"),
        entry("nowPlaying", "Now playing", Settings.LOOK, "How Now playing is laid out: classic, big type, cover, lyrics or playlist.", "layout", "full screen", "playlist"),
        entry("visualiser", "Visualiser", Settings.LOOK, "Bars or a level meter from the sound itself, in the Playlist layout.", "visualizer", "spectrum", "analyser", "analyzer", "level meter", "vu meter", "bars"),
        entry("queueColumns", "Queue table columns", Settings.LOOK, "Which columns the queue's table shows, in the Playlist layout.", "columns", "table", "number", "album", "duration"),
        entry("statusBar", "Status bar", Settings.LOOK, "The codec, the bitrate and the queue's length along the foot of the Playlist layout.", "format", "bitrate", "codec", "sample rate"),
        entry("timeDisplay", "Time on the right", Settings.LOOK, "The song's length, or the time left.", "remaining", "time left", "countdown", "duration"),
        entry("covers", "Covers", Settings.LOOK, "Album covers drawn in half blocks.", "artwork", "album art", "pictures", "images"),
        entry("background", "Background", Settings.LOOK, "The theme's background, or the terminal's own.", "transparent", "terminal colour"),

        entry("booster", "Volume booster", Settings.LISTENING, "Louder than full, up to 1000%, behind a limiter.", "louder", "boost", "amplify", "gain", "loudness", "volume"),
        entry("equaliser", "Equaliser", Settings.LISTENING, "The sound shaped by a preset: more bass, clearer voices.", "equalizer", "eq", "bass", "treble"),
        entry("speed", "Speed", Settings.LISTENING, "Plays faster or slower, keeping the pitch.", "tempo", "playback rate", "faster", "slower"),
        entry("autoplay", "When the queue runs out", Settings.LISTENING, "Carry on with songs like the last, or stop.", "autoplay", "radio", "continue", "endless"),
        entry("autoplayFrom", "Autoplay draws from", Settings.LISTENING, "The service of the last song, or YouTube Music's radio.", "autoplay", "radio", "recommendations"),
        entry("avoidRecent", "Autoplay skips songs played lately", Settings.LISTENING, "Autoplay leaves out what you heard recently.", "autoplay", "repeats", "recently played"),
        entry("homePlay", "Playing a song from Home or a search", Settings.LISTENING, "Autoplay after it, or the rest of its shelf or results.", "home", "search", "search results", "shelf", "radio", "autoplay"),
        entry("keepQueue", "Keep the queue between launches", Settings.LISTENING, "The queue is back where it was left.", "resume", "restore", "remember", "session"),
        entry("sleepFade", "Sleep timer fades out", Settings.LISTENING, "The sleep timer lowers the volume before it stops.", "sleep timer", "fade", "bedtime"),
        entry("hybridSearch", "Hybrid search asks", Settings.LISTENING, "Which services a search of all of them asks.", "search", "services", "all"),
        entry("skipNonMusic", "Skip what is not the music in YouTube videos", Settings.LISTENING, "Skips intros and talking in music videos.", "sponsorblock", "intro", "skip"),
        entry("youtubeHistory", "Add what is played here to YouTube history", Settings.LISTENING, "Songs played here show in YouTube's history.", "watch history", "history"),
        entry("lyrics", "Lyrics from", Settings.LISTENING, "Which source's lyrics are shown first.", "lyrics", "words", "karaoke", "synced"),

        entry("youtube", "YouTube Music", Settings.SERVICES, "Sign in, from the desktop, your phone or cookies.", "youtube", "google", "sign in", "account", "ytm"),
        entry("soundcloud", "SoundCloud", Settings.SERVICES, "Sign in, from the desktop or cookies.", "sign in", "account", "sc"),
        entry("spotify", "Spotify", Settings.SERVICES, "Your library, likes and search, with any account.", "sign in", "account", "sp"),
        entry("spotifyPremium", "Spotify Premium", Settings.SERVICES, "Spotify songs played in your own Spotify app.", "premium", "connect", "spotify app"),
        entry("spotifyPlayback", "Spotify songs play", Settings.SERVICES, "On Spotify, or matched on YouTube Music.", "spotify connect", "matched"),
        entry("spotifyDevice", "Spotify plays on", Settings.SERVICES, "Which of the places Spotify is open plays its songs.", "spotify device", "speaker"),
        entry("bandcamp", "Bandcamp collection", Settings.SERVICES, "A fan's collection and wishlist in the library.", "bandcamp", "fan", "collection", "bc"),
        entry("bandcampGenres", "Bandcamp on Home", Settings.SERVICES, "Which genres Home has a row of Bandcamp's best-sellers for.", "genres", "home"),
        entry("vk", "VK Music", Settings.SERVICES, "Sign in with a browser's vk.ru session.", "vk", "vkontakte", "sign in", "cookies"),

        entry("keys", "Keys", Settings.DEVICE, "Every key the player answers to, and changing them.", "hotkeys", "shortcuts", "keyboard", "bindings", "keybindings"),
        entry("downloads", "Downloads go to", Settings.DEVICE, "The folder saved MP3s are written to.", "folder", "export", "mp3", "save"),
    ) + PlaybackTool.entries.map { tool ->
        entry("tool:${tool.name}", tool.displayName, Settings.DEVICE, "The tool Noctorium plays with, and installing it.", "install", "tools", "player", "missing")
    } + listOf(
        entry("updates", "Check for updates", Settings.ABOUT, "Looks for a newer Noctorium CLI and installs it.", "update", "upgrade", "version", "new version"),
        entry("autoUpdate", "Update automatically", Settings.ABOUT, "Looks for updates once a day, by itself.", "updates", "automatic", "daily", "check"),
        entry("diagnostics", "Run the diagnostics", Settings.ABOUT, "Checks yt-dlp, mpv, the network and the folders.", "troubleshoot", "debug", "problems", "not working", "test"),
    )

    /**
     * Settings shown only some of the time, and the setting to go to instead while they are not, with why: the
     * clock only with a taskbar, Spotify's playing only once signed in with Premium.
     */
    val SHOWN_WITH: Map<String, Pair<String, String>> = mapOf(
        "taskbarClock" to ("playerBar" to "The clock is there with the Taskbar, or a Windows theme"),
        "playerButtons" to ("playerBar" to "The buttons are there to choose with Toolbar 2"),
        "spotifyPlayback" to ("spotifyPremium" to "There once Spotify Premium is signed in"),
        "spotifyDevice" to ("spotifyPremium" to "There once Spotify Premium is signed in"),
    )

    /** What [query] finds, as rows to choose from: each with its section, and the word it was found by. */
    fun rows(tui: Tui, query: String): List<Row> = buildList {
        val matches = SettingsSearch.search(query, ENTRIES)
        if (matches.isEmpty()) {
            add(Row.Note(if (query.isBlank()) "Type to find a setting: a name, or what it does — dark mode, louder, hotkeys…" else "No setting matches \"${query.trim()}\"."))
            return@buildList
        }
        add(Row.Header(if (matches.size == 1) "1 setting" else "${matches.size} settings", "Enter goes to it"))
        matches.forEach { match ->
            val how = when {
                match.kind == SettingMatchKind.KEYWORD && match.keyword != null -> "for \"${match.keyword}\""
                match.kind == SettingMatchKind.DESCRIPTION -> match.entry.description
                else -> null
            }
            add(
                Row.Action(
                    match.entry.title,
                    value = match.entry.section,
                    detail = how,
                    run = { jump(tui, match.entry.id) },
                    id = match.entry.id,
                ),
            )
        }
    }

    /** Closes the search and goes to the setting [id] in its section, chosen -- or to the one it is shown with. */
    fun jump(tui: Tui, id: String) {
        tui.settingsQuery = null
        tui.settingsSearchFocused = false
        tui.keysOpen = false
        val rows = Settings.rows(tui)
        var index = rows.indexOfFirst { it is Row.Action && it.id == id }
        if (index < 0) {
            val (instead, why) = SHOWN_WITH[id] ?: return
            index = rows.indexOfFirst { it is Row.Action && it.id == instead }
            tui.toast(why, Row.Tone.QUIET, 6)
        }
        if (index < 0) return
        val list = tui.list(Page.SETTINGS.name)
        list.selected = index
        // A row of the section above it in view too, so where it lives is plain.
        list.offset = (index - 3).coerceAtLeast(0)
    }

    /** The box, one row across [w] cells from [x]: what is typed, or what it is for. Clicking it starts typing. */
    fun field(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int) {
        val p = tui.palette
        val query = tui.settingsQuery
        val focused = tui.settingsSearchFocused
        val icon = tui.glyphs.search
        val hint = "Find a setting — dark mode, louder, hotkeys…   ${tui.key(KeyAction.SEARCH)}"
        if (p.skinned) {
            Skin.well(canvas, p, x + 1, y, w - 2, 1, top = false, foot = false)
            canvas.write(x + 2, y, icon, if (focused) p.accent else Skin.greyText(p), null, BOLD, max = 1)
            if (query.isNullOrEmpty()) canvas.write(x + 4, y, hint, Skin.greyText(p), null, ITALIC, max = w - 6)
            else canvas.write(x + 4, y, query.takeLast(w - 8), Skin.text(p), null, max = w - 7).also { used ->
                if (focused) canvas.set(x + 4 + used, y, "▏", Skin.text(p), null, BOLD)
            }
        } else {
            val field = p.card
            canvas.fill(x, y, w, 1, field)
            canvas.set(x, y, "▌", if (focused) p.accent else p.line, field)
            canvas.write(x + 2, y, icon, if (focused) p.accent else p.subtext, field, BOLD, max = 1)
            if (query.isNullOrEmpty()) canvas.write(x + 4, y, hint, p.faint, field, ITALIC, max = w - 6)
            else canvas.write(x + 4, y, query.takeLast(w - 8), p.text, field, max = w - 7).also { used ->
                if (focused) canvas.set(x + 4 + used, y, "▏", p.accent, field, BOLD)
            }
        }
        tui.clickTargets += Tui.ClickTarget(x, y, w, 1) { open(tui) }
    }

    /** Opens the box, keeping what was typed before. */
    fun open(tui: Tui) {
        tui.keysOpen = false
        tui.settingsQuery = tui.settingsQuery ?: ""
        tui.settingsSearchFocused = true
        tui.list("SETTINGS:search").selected = 0
    }

    fun close(tui: Tui) {
        tui.settingsQuery = null
        tui.settingsSearchFocused = false
    }

    /** Keys while typing in the box. True when the key was the box's. */
    fun input(tui: Tui, input: Input): Boolean {
        val query = tui.settingsQuery ?: return false
        when (input) {
            is Input.Key -> when (input.key) {
                Keys.ENTER -> {
                    // Straight to the best match, as a search box of settings is expected to do.
                    val best = SettingsSearch.search(query, ENTRIES).firstOrNull()
                    if (best != null) jump(tui, best.entry.id) else tui.settingsSearchFocused = false
                }
                Keys.DOWN -> tui.settingsSearchFocused = false
                Keys.ESCAPE -> close(tui)
                Keys.BACKSPACE -> if (query.isNotEmpty()) tui.settingsQuery = query.substring(0, query.offsetByCodePoints(query.length, -1)) else close(tui)
                else -> return false
            }
            is Input.Text -> {
                if (input.char == "^u") { tui.settingsQuery = ""; return true }
                if (input.char.startsWith("^") && input.char.length == 2) return false
                if (input.alt) return false
                tui.settingsQuery = query + input.char
                tui.list("SETTINGS:search").selected = 0
            }
            else -> return false
        }
        return true
    }
}
