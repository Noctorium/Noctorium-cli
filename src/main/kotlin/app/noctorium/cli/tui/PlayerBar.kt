package app.noctorium.cli.tui

import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RepeatMode
import app.noctorium.settings.ThemeSkin
import app.noctorium.settings.TimeDisplay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The bar along the foot of the screen, laid out as the listener chose in Settings (see [TuiPlayerBar]): the
 * full bar with the cover, the track, the controls over the seek bar and the volume; one compact row; or a
 * desktop's taskbar.
 *
 * The seek bar is drawn by [SeekBars], in whichever style is chosen -- one setting across every Noctorium.
 * Clicking it seeks, and every control does the same wherever a layout puts it.
 */
object PlayerBar {
    /** How many rows the bar takes from a screen [screenHeight] tall: its top edge, then what it holds. */
    fun height(tui: Tui, screenHeight: Int): Int = when (tui.preferences.playerBar) {
        TuiPlayerBar.FULL -> if (screenHeight >= 20) 4 else 2
        TuiPlayerBar.COMPACT, TuiPlayerBar.TASKBAR -> 2
    }

    fun draw(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, cover: Art.Pixels?) {
        when (tui.preferences.playerBar) {
            TuiPlayerBar.FULL -> full(tui, canvas, x, y, w, h, cover)
            TuiPlayerBar.COMPACT -> compact(tui, canvas, x, y, w)
            TuiPlayerBar.TASKBAR -> taskbar(tui, canvas, x, y, w)
        }
    }

    private fun full(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, cover: Art.Pixels?) {
        val p = tui.palette
        val state = tui.state
        val playback = state.playback.value
        val queue = state.queue.state.value
        val preferences = state.settings.value.preferences
        val bg = barBackground(p)
        canvas.fill(x, y, w, h, bg)
        edge(canvas, p, x, y, w, bg)
        val track = playback.track
        val inner = y + 1
        val rows = h - 1
        var left = x + 1

        // The cover, two rows of cells is four rows of pixels; square on screen means twice as wide.
        if (rows >= 3 && tui.preferences.coverArt) {
            val coverRows = rows
            val coverCols = coverRows * 2
            tui.art.draw(canvas, cover, left, inner, coverCols, coverRows, p.card, p.faint)
            left += coverCols + 2
        } else {
            left += 1
        }

        val rightWidth = if (w >= 110) 30 else if (w >= 80) 18 else 0
        val infoWidth = ((w - left) * 34 / 100).coerceIn(14, 48)

        // The track, or what is happening instead of one: a queue waiting to be played -- the one kept from
        // last time, put back at launch -- or nothing at all.
        val waiting = queue.current
        if (track == null && waiting != null) {
            canvas.write(left, inner, waiting.title, p.subtext, bg, BOLD, max = infoWidth)
            if (rows >= 2) canvas.write(left, inner + 1, "Ready in the queue · ${tui.key(KeyAction.PLAY_PAUSE)} plays it", p.faint, bg, max = infoWidth)
        } else if (track == null) {
            canvas.write(left, inner, "Nothing playing", p.subtext, bg, BOLD, max = infoWidth)
            if (rows >= 2) canvas.write(left, inner + 1, "Find something with ${tui.key(KeyAction.SEARCH)} and press Enter", p.faint, bg, max = infoWidth)
        } else {
            canvas.write(left, inner, track.title, p.text, bg, BOLD, max = infoWidth)
            val second = when (playback.status) {
                PlaybackStatus.RESOLVING -> "Finding the audio…"
                PlaybackStatus.ERROR -> playback.errorMessage ?: "Could not play this"
                else -> listOfNotNull(track.artistLine.takeIf(String::isNotBlank), track.album?.title?.takeIf { it.isNotBlank() && it != track.title }).joinToString(" · ")
            }
            if (rows >= 2) {
                canvas.write(left, inner + 1, second, if (playback.status == PlaybackStatus.ERROR) p.bad else p.subtext, bg, max = infoWidth)
            }
            if (rows >= 3) {
                val liked = state.likes.value.isLiked(track)
                val note = buildString {
                    append(track.provider.badge())
                    // Spotify's own app is playing it, wherever that is: the sound is not coming from here.
                    if (NowPlaying.playsOnSpotify(tui, track)) append("  on Spotify")
                    if (liked) append("  ♥")
                    preferences.playbackSpeed.takeIf { it != 1f }?.let { append("  ${Settings.speedName(it)}") }
                    tui.state.sleepTimerRemainingMs.value?.let { append("  ☾ ${formatTime(it)}") }
                    if (state.sleepTimer.value is app.noctorium.playback.SleepTimerState.EndOfTrack) append("  ☾ end of track")
                }
                canvas.write(left, inner + 2, note, p.badgeColour(track.provider), bg, BOLD, max = infoWidth)
                if (liked) {
                    val heart = note.indexOf('♥')
                    if (heart >= 0) canvas.set(left + heart, inner + 2, "♥", p.accent, bg, BOLD)
                }
            }
        }

        // The controls, centred over the seek bar.
        val middleLeft = left + infoWidth + 2
        val middleWidth = (x + w - rightWidth - 1 - middleLeft).coerceAtLeast(TRANSPORT_WIDTH)
        transport(tui, canvas, middleLeft + (middleWidth - TRANSPORT_WIDTH) / 2, inner, bg, roomBelow = rows >= 3)

        if (rows >= 2) {
            seek(tui, canvas, middleLeft, if (rows >= 3) inner + 2 else inner + 1, middleWidth, bg, roomAbove = rows >= 3)
        }

        // Volume, and what else is going on, on the right.
        if (rightWidth > 0) {
            val rx = x + w - rightWidth
            val volume = if (playback.isMuted) 0f else playback.volume
            canvas.write(rx, inner, "vol", p.faint, bg)
            volume(tui, canvas, rx + 4, inner, bg, p.accent, p.line)
            canvas.write(rx + 5 + VOLUME_BARS, inner, if (playback.isMuted) "muted" else "${(volume * 100).toInt()}%", p.subtext, bg)
            if (rows >= 2 && rightWidth >= 26) {
                // What next leads to: the queue's next song, its first again when it repeats, autoplay's first
                // after the end, or Spotify's choice when Spotify carries on by itself.
                val next = when {
                    queue.currentIndex + 1 < queue.tracks.size -> "next" to queue.tracks[queue.currentIndex + 1].title
                    queue.repeatMode == RepeatMode.ALL && queue.tracks.isNotEmpty() -> "next" to queue.tracks.first().title
                    queue.suggestions.isNotEmpty() -> "auto" to queue.suggestions.first().title
                    queue.continuesElsewhere -> "next" to "Spotify chooses"
                    else -> null
                }
                if (next != null) {
                    canvas.write(rx, inner + 1, next.first, p.faint, bg)
                    canvas.write(rx + 5, inner + 1, next.second, p.subtext, bg, max = rightWidth - 6)
                }
                val connect = state.connect.value
                val remote = connect.target
                if (rows >= 3) {
                    val line = when {
                        remote != null -> "⇄ playing on ${remote.name}"
                        connect.controlledBy != null -> "⇄ controlled from ${connect.controlledBy}"
                        tui.web?.address != null -> "◎ web player on"
                        else -> "${queue.currentIndex + 1} of ${queue.tracks.size} in the queue".takeIf { queue.tracks.isNotEmpty() }
                    }
                    line?.let { canvas.write(rx, inner + 2, it, p.faint, bg, max = rightWidth - 1) }
                }
            }
        }
    }

    /**
     * One row under the top edge: play, the track, and the seek bar with its times across the rest -- for
     * anybody who would rather the page had the screen.
     */
    private fun compact(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int) {
        val p = tui.palette
        val bg = barBackground(p)
        canvas.fill(x, y, w, 2, bg)
        edge(canvas, p, x, y, w, bg)
        val row = y + 1
        playButton(tui, canvas, x + 1, row, p)
        val trackX = x + 8
        val trackW = (w * 34 / 100).coerceIn(12, 56)
        trackLine(tui, canvas, trackX, row, trackW, bg)
        seek(tui, canvas, trackX + trackW + 2, row, x + w - 1 - (trackX + trackW + 2), bg, roomAbove = false)
    }

    /**
     * A desktop's taskbar, as the ones of the late nineties had it: a start button, which opens Now playing; the
     * controls where the small launch buttons sat; the song as the button of the window in use -- pressed in
     * while it plays, and pressed again to pause it, as a window's button put it away; the seek bar; and the
     * tray at the far end, with the volume and the clock, which opens the sleep timer and can be switched off
     * in Settings, as Windows let it be. Plain in most themes;
     * 98 draws it as its grey bar of raised buttons and XP as Luna's blue one with its green start button.
     */
    private fun taskbar(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int) {
        val theme = tui.palette
        val state = tui.state
        val playback = state.playback.value
        val xp = theme.skin == ThemeSkin.WINDOWS_XP
        // What is written on the bar: on XP's blue, white; elsewhere, the theme's own colours.
        val p = if (xp) onLuna(theme) else theme
        val bg = if (xp) Xp.TASKBAR else barBackground(theme)
        val row = y + 1
        canvas.fill(x, y, w, 2, bg)
        if (xp) {
            // Luna's taskbar is lighter at its top, under a bright rim.
            canvas.fill(x, y, w, 1, Xp.TASKBAR_TOP)
            for (c in x until x + w) canvas.set(c, y, Skin.TOP, mix(Xp.TASKBAR_TOP, 0xFFFFFF, .45f), Xp.TASKBAR_TOP)
        } else {
            edge(canvas, theme, x, y, w, bg)
        }

        // The start button: the mark and the name, or the mark alone when the bar is short of room.
        val startW = startButton(tui, canvas, x, y, if (w >= 90) "◉ Noctorium" else "◉")
        tui.clickTargets += Tui.ClickTarget(x, row, startW, 1) { tui.go(Page.NOW_PLAYING) }
        var left = x + startW + 2

        // The controls: all five where there is room, then previous, play and next, then play alone.
        left += when {
            w >= 120 -> { transport(tui, canvas, left, row, bg, ink = p, onLuna = xp); TRANSPORT_WIDTH }
            w >= 90 -> { transport(tui, canvas, left - 4, row, bg, ends = false, ink = p, onLuna = xp); TRANSPORT_WIDTH - 8 }
            else -> playButton(tui, canvas, left, row, p, onLuna = xp)
        } + 2

        // The tray, from the right: the volume with a cell either side, then the sleep timer's moon and the clock
        // after it -- the clock unless it is switched off, when the tray is only as wide as what is left in it.
        val clock = clock().takeIf { state.settings.value.preferences.taskbarClock }
        val sleeping = state.sleepTimer.value != null
        val trayW = VOLUME_BARS + 2 + when {
            clock != null -> Canvas.displayWidth(clock) + 3
            sleeping -> 2
            else -> 0
        }
        val trayX = x + w - trayW
        val trayBg = when {
            xp -> Xp.TRAY
            theme.skinned -> theme.w98.face
            else -> theme.card
        }
        canvas.fill(trayX, row, trayW, 1, trayBg)
        when {
            xp -> {
                // XP's tray is a lighter blue the height of the bar, edged in a darker one on its left.
                canvas.fill(trayX, y, trayW, 1, Xp.TRAY)
                for (c in trayX until trayX + trayW) canvas.set(c, y, Skin.TOP, mix(Xp.TRAY, 0xFFFFFF, .45f), Xp.TRAY)
                canvas.set(trayX, y, Skin.LEFT, Xp.TRAY_EDGE, Xp.TRAY)
                canvas.set(trayX, row, Skin.LEFT, Xp.TRAY_EDGE, Xp.TRAY)
            }
            theme.skinned -> {
                // 98's tray is sunk into the bar.
                canvas.set(trayX, row, Skin.LEFT, theme.w98.shadow, trayBg)
                canvas.set(trayX + trayW - 1, row, Skin.RIGHT, theme.w98.highlight, trayBg)
            }
        }
        volume(tui, canvas, trayX + 1, row, trayBg, p.accent, if (theme.skinned) p.line else p.faint)
        tray(tui, canvas, trayX + VOLUME_BARS + 2, row, clock, p, trayBg)

        // The song's button, then the seek bar across what is left between it and the tray -- each left out
        // rather than drawn over the tray when a narrow terminal has no room for it.
        val buttonW = (w * 24 / 100).coerceIn(10, 42).coerceAtMost(trayX - 1 - left)
        if (buttonW < 8) return
        val pressed = playback.status == PlaybackStatus.PLAYING
        val face = taskButton(canvas, theme, left, row, buttonW, pressed, bg)
        trackLine(tui, canvas, left + 1, row, buttonW - 2, face, ink = p)
        tui.clickTargets += Tui.ClickTarget(left, row, buttonW, 1) { state.togglePlayback() }
        val seekX = left + buttonW + 1
        if (trayX - 1 - seekX >= 18) seek(tui, canvas, seekX, row, trayX - 1 - seekX, bg, roomAbove = false, ink = p)
    }

    /**
     * The start button, its [label] from [x] on the taskbar's row under [y]: the theme's accent; 98's raised slab;
     * or XP's green, the bar's height, lighter at its top and rounded at its end. Returns how wide it is.
     */
    private fun startButton(tui: Tui, canvas: Canvas, x: Int, y: Int, label: String): Int {
        val p = tui.palette
        val row = y + 1
        return when (p.skin) {
            ThemeSkin.WINDOWS_98 -> Skin.button(canvas, p, x, row, label, default = true, roomAbove = false, roomBelow = false)
            ThemeSkin.WINDOWS_XP -> {
                val w = Canvas.displayWidth(label) + 3
                canvas.fill(x, y, w, 1, Xp.START_LIGHT)
                canvas.fill(x, row, w, 1, Xp.START)
                canvas.write(x + 1, row, label, Xp.TITLE_TEXT, Xp.START, BOLD or ITALIC)
                canvas.set(x + w, y, "▖", Xp.START_LIGHT)
                canvas.set(x + w, row, "▘", Xp.START)
                w + 1
            }
            else -> canvas.write(x, row, " $label ", p.onAccent, p.accent, BOLD)
        }
    }

    /**
     * The song's button on the taskbar, [w] across from [x]: pressed in while it plays and standing out while it
     * does not, in the theme's own way. Returns the colour its face is, for the writing on it.
     */
    private fun taskButton(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, pressed: Boolean, bg: Rgb): Rgb = when (p.skin) {
        ThemeSkin.WINDOWS_98 -> {
            // Pressed, 98 filled it with a dither of white and grey, and lit it from the other side.
            val face = if (pressed) p.w98.light else p.w98.face
            canvas.fill(x, y, w, 1, face)
            canvas.set(x, y, Skin.LEFT, if (pressed) p.w98.darkShadow else p.w98.highlight, face)
            canvas.set(x + w - 1, y, Skin.RIGHT, if (pressed) p.w98.highlight else p.w98.darkShadow, face)
            face
        }
        ThemeSkin.WINDOWS_XP -> {
            val face = if (pressed) mix(Xp.TASKBAR, Xp.TASKBAR_FOOT, .75f) else mix(Xp.TASKBAR, Xp.WINDOW, .2f)
            canvas.fill(x, y, w, 1, face)
            face
        }
        else -> (if (pressed) p.selection else bg).also { canvas.fill(x, y, w, 1, it) }
    }

    /** The colours of what is written on XP's blue taskbar: white, with the theme's own skin. */
    private fun onLuna(p: Palette): Palette = p.copy(
        page = Xp.TASKBAR,
        panel = Xp.TASKBAR,
        text = Xp.TITLE_TEXT,
        subtext = mix(Xp.TITLE_TEXT, Xp.TASKBAR, .15f),
        accent = Xp.TITLE_TEXT,
    )

    /** What the bar is filled with: the panel's colour, or a Windows theme's face. */
    private fun barBackground(p: Palette): Rgb = if (p.skinned) Skin.face(p) else p.panel.takeIf { it != DEFAULT } ?: DEFAULT

    /** The bar's top edge: a line, or a Windows theme's lit edge along the top of a raised bar. */
    private fun edge(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, bg: Rgb) {
        if (p.skinned) for (c in x until x + w) canvas.set(c, y, Skin.TOP, if (p.skin == ThemeSkin.WINDOWS_XP) Xp.WINDOW else p.w98.highlight, bg)
        else for (c in x until x + w) canvas.set(c, y, "─", p.line, bg)
    }

    /**
     * The play button alone, at [x]: the accent's chip, a Windows theme's push button, or on XP's blue taskbar a
     * chip in its green. Returns how wide it is.
     */
    private fun playButton(tui: Tui, canvas: Canvas, x: Int, y: Int, p: Palette, onLuna: Boolean = false): Int {
        val w = when {
            onLuna -> canvas.write(x, y, " ${playSymbol(tui)} ", Xp.TITLE_TEXT, Xp.START, BOLD)
            p.skinned -> Skin.button(canvas, p, x, y, playSymbol(tui), default = true, roomAbove = false, roomBelow = false)
            else -> canvas.write(x, y, " ${playSymbol(tui)} ", p.onAccent, p.accent, BOLD)
        }
        tui.clickTargets += Tui.ClickTarget(x, y, w, 1) { tui.state.togglePlayback() }
        return w
    }

    /**
     * The end of the tray from [x]: the sleep timer's moon while one is set, and two cells on the [clock], unless
     * it is switched off. Clicking either sets a sleep timer, or changes the one set.
     */
    private fun tray(tui: Tui, canvas: Canvas, x: Int, y: Int, clock: String?, p: Palette, bg: Rgb) {
        val sleeping = tui.state.sleepTimer.value != null
        if (sleeping) canvas.set(x, y, "☾", p.accent, bg, BOLD)
        clock?.let { canvas.write(x + 2, y, it, if (p.skinned) p.text else p.subtext, bg) }
        val w = when {
            clock != null -> Canvas.displayWidth(clock) + 2
            sleeping -> 1
            else -> return
        }
        tui.clickTargets += Tui.ClickTarget(x, y, w, 1) { tui.overlays.addLast(Overlays.sleepTimer(tui)) }
    }

    /** The time of day, as this computer's own clock writes it. */
    fun clock(): String = LocalTime.now().format(CLOCK)

    private val CLOCK = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /** The title in bold and the artist after it, or what is going on instead, in [w] cells from [x]. */
    private fun trackLine(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, bg: Rgb, ink: Palette = tui.palette) {
        val p = ink
        val playback = tui.state.playback.value
        val track = playback.track
        val waiting = tui.state.queue.state.value.current
        when {
            track != null -> {
                val used = canvas.write(x, y, track.title, p.text, bg, BOLD, max = w)
                val second = when (playback.status) {
                    PlaybackStatus.RESOLVING -> "finding the audio…"
                    PlaybackStatus.ERROR -> playback.errorMessage ?: "could not play this"
                    else -> track.artistLine
                }
                if (second.isNotBlank() && used + 4 < w) {
                    canvas.write(x + used, y, " – $second", if (playback.status == PlaybackStatus.ERROR) p.bad else p.subtext, bg, max = w - used)
                }
            }
            waiting != null -> canvas.write(x, y, "${waiting.title} · ${tui.key(KeyAction.PLAY_PAUSE)} plays it", p.subtext, bg, max = w)
            else -> canvas.write(x, y, "Nothing playing · ${tui.key(KeyAction.SEARCH)} finds something", p.subtext, bg, max = w)
        }
    }

    /** What the play button shows: pause while playing, a wait while the audio is found, play otherwise. */
    private fun playSymbol(tui: Tui): String = when (tui.state.playback.value.status) {
        PlaybackStatus.PLAYING -> "▮▮"
        PlaybackStatus.RESOLVING -> "··"
        else -> "▶ "
    }

    /** How wide [transport] is with its two ends. */
    internal const val TRANSPORT_WIDTH = 21

    /**
     * Shuffle, previous, play, next and repeat, from [x]; without shuffle and repeat at the [ends] when there
     * is not the room, in which case [x] is still where shuffle would have been. In the Windows themes play is
     * one of their push buttons, its foot drawn in the row under it where [roomBelow] says it is free -- or, on
     * XP's taskbar, a chip in the start button's green, as that bar had no grey buttons.
     */
    internal fun transport(
        tui: Tui,
        canvas: Canvas,
        x: Int,
        y: Int,
        bg: Rgb,
        ends: Boolean = true,
        roomBelow: Boolean = false,
        ink: Palette = tui.palette,
        onLuna: Boolean = false,
    ) {
        val p = ink
        val state = tui.state
        val queue = state.queue.state.value
        val play = playSymbol(tui)
        if (ends) {
            canvas.write(x, y, "⇄", if (queue.shuffleEnabled) p.accent else p.faint, bg, BOLD)
            tui.clickTargets += Tui.ClickTarget(x, y, 2, 1) { state.toggleShuffle() }
        }
        // Drawn from plain shapes rather than the media symbols, which some terminals turn into wide emoji.
        canvas.write(x + 4, y, "|◀", p.text, bg)
        when {
            onLuna -> canvas.write(x + 8, y, " $play ", Xp.TITLE_TEXT, Xp.START, BOLD)
            // A push button is two cells wider than the chip: one taken from each side of it.
            p.skinned -> Skin.button(canvas, p, x + 7, y, play, default = true, roomAbove = false, roomBelow = roomBelow)
            else -> canvas.write(x + 8, y, " $play ", p.onAccent, p.accent, BOLD)
        }
        // Dim where next would go nowhere: the queue's end, with nothing repeating and nothing from autoplay.
        canvas.write(x + 14, y, "▶|", if (queue.hasNext) p.text else p.faint, bg)
        tui.clickTargets += Tui.ClickTarget(x + 4, y, 3, 1) { state.previous() }
        val button = p.skinned && !onLuna
        tui.clickTargets += Tui.ClickTarget(if (button) x + 7 else x + 8, y, if (button) 6 else 5, 1) { state.togglePlayback() }
        tui.clickTargets += Tui.ClickTarget(x + 14, y, 3, 1) { state.next() }
        if (ends) {
            canvas.write(x + 19, y, if (queue.repeatMode == RepeatMode.ONE) "↻1" else "↻", if (queue.repeatMode != RepeatMode.OFF) p.accent else p.faint, bg, BOLD)
            tui.clickTargets += Tui.ClickTarget(x + 18, y, 3, 1) { state.cycleRepeat() }
        }
    }

    /**
     * The seek bar with the time played before it and the length, or the time left, after it, in [w] cells from
     * [x]; clicking the bar seeks there.
     */
    internal fun seek(
        tui: Tui,
        canvas: Canvas,
        x: Int,
        y: Int,
        w: Int,
        bg: Rgb,
        roomAbove: Boolean,
        roomBelow: Boolean = false,
        ink: Palette = tui.palette,
    ) {
        val p = ink
        val state = tui.state
        val playback = state.playback.value
        val track = playback.track
        val preferences = state.settings.value.preferences
        val elapsed = formatTime(playback.positionMs.takeIf { track != null })
        val total = if (preferences.timeDisplay == TimeDisplay.REMAINING && playback.durationMs > 0) {
            "-" + formatTime((playback.durationMs - playback.positionMs).coerceAtLeast(0))
        } else formatTime(playback.durationMs.takeIf { it > 0 })
        val barX = x + 6
        val barW = (w - 13).coerceAtLeast(4)
        canvas.writeRight(barX - 1, y, elapsed, p.subtext, bg)
        val fraction = if (playback.durationMs > 0) (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) else 0f
        SeekBars.draw(
            canvas, p, preferences.progressBarStyle, barX, y, barW, fraction, playback.status == PlaybackStatus.PLAYING, bg,
            seed = track?.queueKey.orEmpty(), durationMs = playback.durationMs, roomAbove = roomAbove, roomBelow = roomBelow,
        )
        canvas.write(barX + barW + 1, y, total, p.subtext, bg)
        if (playback.durationMs > 0) {
            tui.clickTargets += Tui.ClickTarget(barX, y, barW, 1) { column ->
                state.seekTo((playback.durationMs * column / barW.toLong()).coerceIn(0, playback.durationMs - 500))
            }
        }
    }

    private const val VOLUME_BARS = 8

    /** The volume as eight bars rising left to right, [lit] as far as it goes; clicking one sets it there. */
    private fun volume(tui: Tui, canvas: Canvas, x: Int, y: Int, bg: Rgb, lit: Rgb, unlit: Rgb) {
        val playback = tui.state.playback.value
        val filled = ((if (playback.isMuted) 0f else playback.volume) * VOLUME_BARS).toInt()
        val levels = "▁▂▃▄▅▆▇█"
        for (i in 0 until VOLUME_BARS) canvas.set(x + i, y, levels[i].toString(), if (i < filled) lit else unlit, bg)
        tui.clickTargets += Tui.ClickTarget(x, y, VOLUME_BARS, 1) { column ->
            tui.state.setVolume(((column + 1) / VOLUME_BARS.toFloat()).coerceIn(0f, 1f))
        }
    }
}
