package app.noctorium.cli.tui

import app.noctorium.cli.CliParts
import app.noctorium.cli.TerminalBridge
import app.noctorium.cli.cliVersion
import app.noctorium.cli.update.CliUpdates
import app.noctorium.core.AppState
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** The pages, in the order the number keys reach them. */
enum class Page(val title: String, val icon: String) {
    HOME("Home", "⌂"),
    SEARCH("Search", "⌕"),
    LIBRARY("Library", "♪"),
    QUEUE("Queue", "≡"),
    NOW_PLAYING("Now playing", "▶"),
    DOWNLOADS("Downloads", "↓"),
    DEVICES("Devices", "⇄"),
    SETTINGS("Settings", "✦"),
}

/** The browser player, as the terminal sees it: started and stopped from Devices, or with `w`. */
interface WebSwitch {
    /** Where a browser on this network can open it, with its key, or null while it is off. */
    val address: String?
    fun start(): String?
    fun stop()
}

/**
 * Noctorium in a terminal.
 *
 * One loop on the main thread reads a key, does what it asks, and draws the frame when something changed --
 * a key, a new position from mpv, a cover arriving, lyrics found. The rest of the program is the same
 * AppState the desktop runs, so everything this does is a call the desktop's buttons also make, and what
 * it shows is read straight from the same state.
 */
class Tui(
    internal val state: AppState,
    internal val parts: CliParts,
    internal val web: WebSwitch?,
    private val startWith: String? = null,
) {
    internal val screen = Screen()
    private val dirty = AtomicBoolean(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val art = Art { dirty.set(true) }
    internal var page = Page.HOME
    internal val lists = mutableMapOf<String, ListState>()
    internal val listView = ListView({ palette }, state)
    internal var overlays = ArrayDeque<Overlay>()
    private val toasts = ArrayDeque<Toast>()
    internal var running = true
    internal var palette = Palette.from(state.settings.value.preferences, null, false)
    internal val preferences = TuiPreferences.load()

    /** The search box: what is typed, and whether the keys go to it. */
    internal var searchText = ""
    internal var searchFocused = false

    /** Whether the playlist list or an open playlist is showing on the library page. */
    internal var libraryOpen = false

    /** What a mouse press on the screen means, filled in as the frame is drawn. */
    internal val clickTargets = mutableListOf<ClickTarget>()

    data class ClickTarget(val x: Int, val y: Int, val w: Int, val h: Int, val action: (Int) -> Unit)

    private data class Toast(val text: String, val tone: Row.Tone, val until: Long)

    fun list(key: String) = lists.getOrPut(key) { ListState() }

    fun invalidate() = dirty.set(true)

    fun toast(text: String, tone: Row.Tone = Row.Tone.NORMAL, seconds: Int = 4) {
        synchronized(toasts) {
            toasts.removeIf { it.text == text }
            toasts.addLast(Toast(text, tone, System.currentTimeMillis() + seconds * 1000))
            while (toasts.size > 3) toasts.removeFirst()
        }
        invalidate()
    }

    fun run() {
        screen.open()
        parts.bridge.terminalOutput = { screen.raw(it); screen.flush() }
        parts.bridge.notices = { notice ->
            when (notice) {
                is TerminalBridge.Notice.OpenLink -> overlays.addLast(Overlay.Link(notice.url, notice.opened))
                is TerminalBridge.Notice.Copied -> toast("Copied to the clipboard", Row.Tone.GOOD)
            }
            invalidate()
        }
        watch()
        // Whether yt-dlp and mpv are there, for Settings to say; it reads the disk, so not on this thread.
        scope.launch { app.noctorium.playback.PlaybackToolInstaller.refresh() }
        startWith?.let { query -> scope.launch { Startup.playFirst(this@Tui, query) } }
        // At most once a day, a little after opening, on a thread of its own. An update it finds is
        // installed beside this copy without asking and takes over at the next start; nothing about what is
        // playing changes, and a failure is one quiet line.
        parts.updates.automatically(scope, state.settings.value.preferences.updates.checkOnLaunch) { result ->
            announceUpdate(result, asked = false)
        }
        try {
            var lastFrame = 0L
            while (running) {
                // One millisecond, not zero, when a frame is waiting: to jline a timeout of zero means forever.
                val input = screen.read(if (dirty.get()) 1 else 120)
                if (input != null) {
                    runCatching { handle(input) }.onFailure { toast(it.message ?: "Something went wrong", Row.Tone.BAD) }
                    dirty.set(true)
                }
                val now = System.currentTimeMillis()
                // The seek bar and the lyrics move on their own while playing; a few frames a second is plenty.
                if (state.playback.value.status == PlaybackStatus.PLAYING && now - lastFrame > 250) dirty.set(true)
                if (dirty.getAndSet(false)) {
                    render()
                    lastFrame = now
                }
            }
        } finally {
            scope.cancel()
            parts.bridge.terminalOutput = null
            screen.close()
        }
    }

    /** Anything that changes what is on screen sets the frame dirty, and messages become toasts. */
    private fun watch() {
        fun <T> Flow<T>.redraw() = scope.launch { collect { dirty.set(true) } }
        state.ui.redraw()
        state.queue.state.redraw()
        state.library.redraw()
        state.likes.redraw()
        state.lyrics.redraw()
        state.downloadState.redraw()
        state.connect.redraw()
        state.sleepTimer.redraw()
        state.account.redraw()
        state.artistFollow.redraw()
        app.noctorium.playback.PlaybackToolInstaller.state.redraw()
        parts.updates.status.redraw()
        state.playback.map { it.status to it.track?.queueKey }.distinctUntilChanged().redraw()
        scope.launch {
            state.settings.collect { settings ->
                settings.message?.let { toast(it); state.clearSettingsMessage() }
                dirty.set(true)
            }
        }
        scope.launch { state.likes.collect { l -> l.message?.let { toast(it); state.clearLikeMessage() } } }
        scope.launch { state.library.collect { l -> l.notice?.let { toast(it); state.clearLibraryNotice() } } }
        scope.launch { state.downloadState.collect { d -> d.message?.let { toast(it); state.clearDownloadMessage() } } }
        scope.launch { state.connect.collect { c -> c.message?.let { toast(it); state.dismissConnectMessage() } } }
        scope.launch { state.account.collect { a -> a.message?.let { toast(it); state.clearNoctoriumMessage() } } }
        // Updates are [CliUpdates]'s here, not AppState's, which does not check at launch in a terminal.
        scope.launch { state.updates.collect { u -> u.message?.let { toast(it); state.clearUpdateMessage() } } }
        scope.launch {
            state.signInTransfer.collect { transfer ->
                when (transfer) {
                    is AppState.SignInTransfer.Waiting -> {
                        overlays.removeIf { it is Overlay.Qr && it.tag == "signin" }
                        overlays.addLast(
                            Overlay.Qr(
                                tag = "signin",
                                title = "Sign in with your phone",
                                code = transfer.code,
                                lines = listOf(
                                    "On your phone: Noctorium → Settings → YouTube Music →",
                                    "\"Sign in a computer\", then point it at this code.",
                                    "Both have to be on the same network.",
                                ),
                                onClose = { state.cancelSignInTransfer() },
                            ),
                        )
                    }
                    AppState.SignInTransfer.Checking -> toast("Checking the session with YouTube…")
                    is AppState.SignInTransfer.Done -> {
                        overlays.removeIf { it is Overlay.Qr && it.tag == "signin" }
                        toast("Signed in to YouTube Music" + (transfer.channel?.let { " as $it" } ?: ""), Row.Tone.GOOD, 6)
                    }
                    is AppState.SignInTransfer.Failed -> {
                        overlays.removeIf { it is Overlay.Qr && it.tag == "signin" }
                        toast(transfer.message, Row.Tone.BAD, 8)
                    }
                    AppState.SignInTransfer.Idle -> overlays.removeIf { it is Overlay.Qr && it.tag == "signin" }
                }
                dirty.set(true)
            }
        }
    }

    // --- Drawing ---

    private fun render() {
        screen.draw(frame(screen.width.coerceAtLeast(40), screen.height.coerceAtLeast(12)))
    }

    /** One frame, [w] by [h], drawn into a canvas and not yet sent anywhere: the tests look at these. */
    internal fun frame(w: Int, h: Int): Canvas {
        val playing = state.playback.value.track
        val cover = art.get(playing?.artworkUrl)
        palette = Palette.from(state.settings.value.preferences, cover?.accent, preferences.terminalBackground)
        val p = palette
        val canvas = Canvas(w, h)
        canvas.clear(p.page, p.text)
        clickTargets.clear()

        val barHeight = if (h >= 20) 4 else 2
        val sidebar = if (w >= 96) 22 else 0
        header(canvas, w)
        if (sidebar > 0) sidebar(canvas, 0, 1, sidebar, h - 1 - barHeight)
        val bodyX = sidebar
        val bodyW = w - sidebar
        val bodyY = 1
        val bodyH = h - 1 - barHeight
        canvas.clipped(bodyX, bodyY, bodyW, bodyH) {
            Pages.draw(this, canvas, bodyX, bodyY, bodyW, bodyH)
        }
        PlayerBar.draw(this, canvas, 0, h - barHeight, w, barHeight, cover)
        overlays.lastOrNull()?.let { overlay -> canvas.clipped(0, 0, w, h) { overlay.draw(this, canvas, w, h) } }
        drawToasts(canvas, w, h - barHeight)
        return canvas
    }

    private fun header(canvas: Canvas, w: Int) {
        val p = palette
        val bar = p.panel.takeIf { it != DEFAULT } ?: DEFAULT
        canvas.fill(0, 0, w, 1, bar)
        canvas.write(1, 0, "◉", p.accent, bar, BOLD)
        canvas.write(3, 0, "NOCTORIUM", p.text, bar, BOLD)
        var x = 14
        if (w < 96) {
            // No sidebar: the pages along the top instead, as numbers and names while they fit.
            Page.entries.forEachIndexed { i, entry ->
                val label = if (w >= 120) " ${i + 1} ${entry.title} " else " ${i + 1}${entry.icon} "
                val active = entry == page
                val used = Canvas.displayWidth(label)
                if (x + used >= w - 20) return@forEachIndexed
                canvas.write(x, 0, label, if (active) p.onAccent else p.subtext, if (active) p.accent else bar, if (active) BOLD else 0)
                val target = entry
                clickTargets += ClickTarget(x, 0, used, 1) { go(target) }
                x += used
            }
        }
        val settings = state.settings.value
        val services = buildList {
            add("YT" to (settings.youtubeAccount.status == app.noctorium.settings.AccountConnectionStatus.CONNECTED))
            add("SC" to (settings.soundCloudAccount.status == app.noctorium.settings.AccountConnectionStatus.CONNECTED))
            if (settings.spotify.connected) add("SP" to true)
            if (settings.preferences.bandcampUsername.isNotBlank()) add("BC" to true)
        }
        var right = w - 1
        web?.address?.let {
            right -= canvas.writeRight(right, 0, " web ● ", p.good, bar) + 1
        }
        services.reversed().forEach { (name, on) ->
            val text = "$name ${if (on) "●" else "○"}"
            right -= canvas.writeRight(right, 0, text, if (on) p.good else p.faint, bar) + 2
        }
    }

    private fun sidebar(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = palette
        val bg = p.panel
        canvas.fill(x, y, w, h, bg)
        for (r in y until y + h) canvas.set(x + w - 1, r, "│", p.line, bg)
        var row = y + 1
        Page.entries.forEachIndexed { i, entry ->
            if (row >= y + h) return@forEachIndexed
            val active = entry == page
            if (active) canvas.fill(x + 1, row, w - 3, 1, p.selection)
            val back = if (active) p.selection else bg
            canvas.write(x + 2, row, entry.icon, if (active) p.accent else p.subtext, back, BOLD)
            canvas.write(x + 4, row, entry.title, if (active) p.text else p.subtext, back, if (active) BOLD else 0)
            canvas.writeRight(x + w - 3, row, "${i + 1}", p.faint, back)
            val target = entry
            clickTargets += ClickTarget(x, row, w - 1, 1) { go(target) }
            row++
        }
        row++
        val library = state.library.value
        val playlists = library.playlists.take((y + h - row - 3).coerceAtLeast(0))
        if (playlists.isNotEmpty() && row < y + h - 2) {
            canvas.write(x + 2, row, "PLAYLISTS", p.faint, bg, BOLD)
            row++
            playlists.forEach { pl ->
                if (row >= y + h - 1) return@forEach
                canvas.write(x + 2, row, "·", p.badgeColour(pl.provider), bg)
                canvas.write(x + 4, row, pl.title, p.subtext, bg, max = w - 6)
                val target = pl
                clickTargets += ClickTarget(x, row, w - 1, 1) {
                    page = Page.LIBRARY
                    libraryOpen = true
                    state.openPlaylist(target)
                }
                row++
            }
        }
        canvas.write(x + 2, y + h - 1, "? keys   v$cliVersion", p.faint, bg, max = w - 3)
    }

    private fun drawToasts(canvas: Canvas, w: Int, bottom: Int) {
        val now = System.currentTimeMillis()
        val showing = synchronized(toasts) {
            toasts.removeIf { it.until < now }
            toasts.toList()
        }
        if (showing.isNotEmpty()) dirty.set(true)
        var y = bottom - 1
        for (toast in showing.reversed()) {
            val p = palette
            val text = " ${toast.text} "
            val tw = minOf(Canvas.displayWidth(text) + 2, w - 4)
            val x = w - tw - 2
            val colour = toneColour(p, toast.tone).takeIf { toast.tone != Row.Tone.NORMAL } ?: p.text
            canvas.fill(x, y, tw, 1, p.card)
            canvas.set(x, y, "▌", if (toast.tone == Row.Tone.NORMAL) p.accent else colour, p.card)
            canvas.write(x + 1, y, text, colour, p.card, max = tw - 1)
            y--
            if (y < 2) break
        }
    }

    // --- Input ---

    internal fun go(target: Page) {
        page = target
        searchFocused = target == Page.SEARCH && searchText.isBlank()
        when (target) {
            Page.LIBRARY -> state.refreshLibrary()
            Page.HOME -> if (state.ui.value.homeSections.isEmpty() && !state.ui.value.homeLoading) state.refreshHome()
            Page.NOW_PLAYING -> state.playback.value.track?.let { state.loadLyrics(it) }
            else -> Unit
        }
    }

    /** A key, as if it had been pressed: the tests drive the player through this, with no terminal. */
    internal fun press(input: Input) = handle(input)

    private fun handle(input: Input) {
        if (input is Input.Resize) { screen.invalidate(); return }
        if (input.isCtrl('c')) { running = false; return }
        if (input is Input.Mouse) { mouse(input); return }
        overlays.lastOrNull()?.let { overlay ->
            if (overlay.handle(this, input)) return
        }
        if (page == Page.SEARCH && searchFocused && Pages.searchInput(this, input)) return
        if (Pages.handle(this, input)) return
        global(input)
    }

    private fun global(input: Input) {
        val playback = state.playback.value
        when (input) {
            is Input.Key -> when (input.key) {
                Keys.TAB -> go(Page.entries[(page.ordinal + 1) % Page.entries.size])
                Keys.BACK_TAB -> go(Page.entries[(page.ordinal + Page.entries.size - 1) % Page.entries.size])
                Keys.LEFT -> seekBy(if (input.shift || input.ctrl) -30_000 else -5_000)
                Keys.RIGHT -> seekBy(if (input.shift || input.ctrl) 30_000 else 5_000)
                Keys.ESCAPE -> if (page != Page.HOME) go(Page.HOME)
                Keys.F1 -> overlays.addLast(Overlay.Help)
                else -> Unit
            }
            is Input.Text -> when (input.char) {
                " " -> state.togglePlayback()
                "n" -> state.next()
                "p" -> state.previous()
                "+", "=" -> state.setVolume((playback.volume + .05f).coerceAtMost(1f))
                "-", "_" -> state.setVolume((playback.volume - .05f).coerceAtLeast(0f))
                "m" -> state.toggleMute()
                "s" -> { state.toggleShuffle(); toast(if (!state.queue.state.value.shuffleEnabled) "Shuffle off" else "Shuffle on") }
                "r" -> {
                    state.cycleRepeat()
                    toast(
                        when (state.queue.state.value.repeatMode) {
                            RepeatMode.OFF -> "Repeat off"
                            RepeatMode.ALL -> "Repeating the queue"
                            RepeatMode.ONE -> "Repeating this track"
                        },
                    )
                }
                "L" -> playback.track?.let(::like)
                "/" -> { go(Page.SEARCH); searchFocused = true }
                "?" -> overlays.addLast(Overlay.Help)
                "q" -> running = false
                "z" -> overlays.addLast(Overlays.sleepTimer(this))
                "t" -> cycleTheme(1)
                "T" -> cycleTheme(-1)
                "w" -> toggleWeb()
                "1", "2", "3", "4", "5", "6", "7", "8" -> go(Page.entries[input.char.toInt() - 1])
                "^l" -> screen.invalidate()
                else -> Unit
            }
            else -> Unit
        }
    }

    /** Settings' "Check for updates": the same as the daily check, asked for, so it always answers. */
    internal fun checkForUpdates() {
        if (parts.updates.status.value.checking) { toast("Already looking for an update…"); return }
        toast("Looking for a newer Noctorium CLI…")
        scope.launch(Dispatchers.IO) { announceUpdate(parts.updates.run(install = true), asked = true) }
    }

    /**
     * What came of a check, as a toast. The daily one says nothing when nothing is new, and a failure of it
     * is a quiet line rather than an alarm: it changes nothing, and tomorrow's check will try again.
     */
    private fun announceUpdate(result: CliUpdates.Result, asked: Boolean) {
        when (result) {
            is CliUpdates.Result.Installed -> toast(result.message, Row.Tone.GOOD, 8)
            is CliUpdates.Result.Available -> toast(
                if (parts.updates.canInstall) "Noctorium CLI ${result.update.version} is out: Settings installs it"
                else "Noctorium CLI ${result.update.version} is out: Settings says how to update this copy",
                Row.Tone.ACCENT,
                10,
            )
            is CliUpdates.Result.Failed -> parts.updates.headline(result, asked)?.let { toast(it, if (asked) Row.Tone.BAD else Row.Tone.QUIET, 6) }
            CliUpdates.Result.UpToDate -> parts.updates.headline(result, asked)?.let { toast(it, Row.Tone.GOOD) }
        }
        invalidate()
    }

    internal fun like(track: Track) {
        val likes = state.likes.value
        if (!likes.supports(track)) {
            toast(
                if (track.provider.keepsLikes) "Sign in to ${track.provider.displayName} in Settings to like its tracks"
                else "${track.provider.displayName} tracks cannot be liked from Noctorium",
                Row.Tone.WARN,
            )
            return
        }
        val was = likes.isLiked(track)
        state.toggleLike(track)
        toast(if (was) "Removed from your likes" else "Liked on ${track.provider.displayName}", if (was) Row.Tone.NORMAL else Row.Tone.GOOD)
    }

    internal fun seekBy(deltaMs: Long) {
        val playback = state.playback.value
        if (playback.track == null || playback.durationMs <= 0) return
        state.seekTo((playback.positionMs + deltaMs).coerceIn(0, playback.durationMs - 500))
    }

    internal fun cycleTheme(direction: Int) {
        val themes = app.noctorium.settings.ThemePreset.entries.filter { it.colours != null }
        val current = themes.indexOf(state.settings.value.preferences.theme).coerceAtLeast(0)
        val next = themes[(current + direction + themes.size) % themes.size]
        state.setTheme(next)
        toast("Theme: ${next.family} ${next.displayName}".replace("Noctorium Noctorium", "Noctorium"))
    }

    internal fun toggleWeb() {
        val switch = web ?: run { toast("The web player is not part of this build", Row.Tone.WARN); return }
        if (switch.address != null) {
            switch.stop()
            toast("Web player stopped")
            return
        }
        val problem = switch.start()
        if (problem != null) { toast(problem, Row.Tone.BAD, 8); return }
        switch.address?.let { address ->
            overlays.addLast(
                Overlay.Qr(
                    tag = "web",
                    title = "The web player is on",
                    code = address,
                    lines = listOf(
                        "Open it in any browser on this network:",
                        address,
                        "Scan this with a phone to open it there.",
                    ),
                ),
            )
        }
    }

    private fun mouse(input: Input.Mouse) {
        overlays.lastOrNull()?.let { if (it.mouse(this, input)) return }
        when (input.kind) {
            MouseKind.WHEEL_UP -> Pages.scroll(this, -3)
            MouseKind.WHEEL_DOWN -> Pages.scroll(this, 3)
            MouseKind.PRESS -> {
                clickTargets.lastOrNull { input.x >= it.x && input.x < it.x + it.w && input.y >= it.y && input.y < it.y + it.h }
                    ?.let { target -> target.action(input.x - target.x); return }
                Pages.click(this, input.x, input.y)
            }
            else -> Unit
        }
    }
}
