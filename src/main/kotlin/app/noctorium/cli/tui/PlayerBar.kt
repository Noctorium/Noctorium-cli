package app.noctorium.cli.tui

import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.RepeatMode
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.TimeDisplay
import kotlin.math.PI
import kotlin.math.sin

/**
 * The bar along the foot of the screen: the cover, the track, the controls and the seek bar.
 *
 * The seek bar is drawn in whichever of the desktop's six styles is chosen there -- they are one setting
 * across every Noctorium -- in the nearest a terminal gets: a hairline with a dot, the wave travelling while
 * the music does, the segments, the capsule, the classic blocks. Clicking it seeks.
 */
object PlayerBar {
    fun draw(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, cover: Art.Pixels?) {
        val p = tui.palette
        val state = tui.state
        val playback = state.playback.value
        val queue = state.queue.state.value
        val preferences = state.settings.value.preferences
        val bg = p.panel.takeIf { it != DEFAULT } ?: DEFAULT
        canvas.fill(x, y, w, h, bg)
        for (c in x until x + w) canvas.set(c, y, "─", p.line, bg)
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

        val controlsWidth = 21
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
        val middleWidth = (x + w - rightWidth - 1 - middleLeft).coerceAtLeast(controlsWidth)
        val controlsX = middleLeft + (middleWidth - controlsWidth) / 2
        val play = when (playback.status) {
            PlaybackStatus.PLAYING -> "▮▮"
            PlaybackStatus.RESOLVING -> "··"
            else -> "▶ "
        }
        val shuffleColour = if (queue.shuffleEnabled) p.accent else p.faint
        val repeatColour = if (queue.repeatMode != RepeatMode.OFF) p.accent else p.faint
        canvas.write(controlsX, inner, "⇄", shuffleColour, bg, BOLD)
        // Drawn from plain shapes rather than the media symbols, which some terminals turn into wide emoji.
        canvas.write(controlsX + 4, inner, "|◀", p.text, bg)
        canvas.write(controlsX + 8, inner, " $play ", p.onAccent, p.accent, BOLD)
        // Dim where next would go nowhere: the queue's end, with nothing repeating and nothing from autoplay.
        canvas.write(controlsX + 14, inner, "▶|", if (queue.hasNext) p.text else p.faint, bg)
        canvas.write(controlsX + 19, inner, if (queue.repeatMode == RepeatMode.ONE) "↻1" else "↻", repeatColour, bg, BOLD)
        tui.clickTargets += Tui.ClickTarget(controlsX, inner, 2, 1) { state.toggleShuffle() }
        tui.clickTargets += Tui.ClickTarget(controlsX + 4, inner, 3, 1) { state.previous() }
        tui.clickTargets += Tui.ClickTarget(controlsX + 8, inner, 5, 1) { state.togglePlayback() }
        tui.clickTargets += Tui.ClickTarget(controlsX + 14, inner, 3, 1) { state.next() }
        tui.clickTargets += Tui.ClickTarget(controlsX + 18, inner, 3, 1) { state.cycleRepeat() }

        if (rows >= 2) {
            val seekY = if (rows >= 3) inner + 2 else inner + 1
            val elapsed = formatTime(playback.positionMs.takeIf { track != null })
            val total = if (preferences.timeDisplay == TimeDisplay.REMAINING && playback.durationMs > 0) {
                "-" + formatTime((playback.durationMs - playback.positionMs).coerceAtLeast(0))
            } else formatTime(playback.durationMs.takeIf { it > 0 })
            val barX = middleLeft + 6
            val barW = (middleWidth - 13).coerceAtLeast(4)
            canvas.writeRight(barX - 1, seekY, elapsed, p.subtext, bg)
            val fraction = if (playback.durationMs > 0) (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) else 0f
            seekBar(canvas, p, preferences.progressBarStyle, barX, seekY, barW, fraction, playback.status == PlaybackStatus.PLAYING, bg)
            canvas.write(barX + barW + 1, seekY, total, p.subtext, bg)
            if (playback.durationMs > 0) {
                tui.clickTargets += Tui.ClickTarget(barX, seekY, barW, 1) { column ->
                    state.seekTo((playback.durationMs * column / barW.toLong()).coerceIn(0, playback.durationMs - 500))
                }
            }
        }

        // Volume, and what else is going on, on the right.
        if (rightWidth > 0) {
            val rx = x + w - rightWidth
            val volume = if (playback.isMuted) 0f else playback.volume
            val levels = "▁▂▃▄▅▆▇█"
            val bars = 8
            val filled = (volume * bars).toInt()
            canvas.write(rx, inner, "vol", p.faint, bg)
            for (i in 0 until bars) {
                canvas.set(rx + 4 + i, inner, levels[i].toString(), if (i < filled) p.accent else p.line, bg)
            }
            canvas.write(rx + 5 + bars, inner, if (playback.isMuted) "muted" else "${(volume * 100).toInt()}%", p.subtext, bg)
            tui.clickTargets += Tui.ClickTarget(rx + 4, inner, bars, 1) { column -> state.setVolume(((column + 1) / bars.toFloat()).coerceIn(0f, 1f)) }
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

    fun seekBar(canvas: Canvas, p: Palette, style: ProgressBarStyle, x: Int, y: Int, w: Int, fraction: Float, moving: Boolean, bg: Rgb) {
        val head = (fraction * (w - 1)).toInt().coerceIn(0, w - 1)
        val unplayed = p.line
        when (style) {
            ProgressBarStyle.MINIMAL, ProgressBarStyle.MATERIAL -> {
                for (i in 0 until w) canvas.set(x + i, y, if (i < head) "━" else "─", if (i < head) p.accent else unplayed, bg)
                canvas.set(x + head, y, if (style == ProgressBarStyle.MATERIAL) "◉" else "●", p.accent, bg, BOLD)
            }
            ProgressBarStyle.WAVE -> {
                val phase = if (moving) (System.currentTimeMillis() % 1600L) / 1600.0 * 2 * PI else 0.0
                val levels = "▁▂▃▄▅▆▇"
                for (i in 0 until w) {
                    if (i < head) {
                        val v = (sin(i * .9 - phase) + 1) / 2
                        canvas.set(x + i, y, levels[(v * (levels.length - 1)).toInt()].toString(), p.accent, bg)
                    } else {
                        canvas.set(x + i, y, "─", unplayed, bg)
                    }
                }
                canvas.set(x + head, y, "●", p.accent, bg, BOLD)
            }
            ProgressBarStyle.SEGMENTS -> {
                for (i in 0 until w) {
                    val segment = i % 3 != 2
                    canvas.set(x + i, y, if (segment) "▬" else " ", if (i <= head) p.accent else unplayed, bg)
                }
            }
            ProgressBarStyle.CAPSULE -> {
                for (i in 0 until w) canvas.set(x + i, y, if (i <= head) "█" else "░", if (i <= head) p.accent else unplayed, bg)
            }
            ProgressBarStyle.CLASSIC -> {
                canvas.set(x, y, "▕", unplayed, bg)
                for (i in 1 until w - 1) canvas.set(x + i, y, if (i < head && i % 2 == 1) "█" else if (i < head) "▌" else " ", p.accent, mix(bg.takeIf { it != DEFAULT } ?: 0, 0xFFFFFF, .06f))
                canvas.set(x + w - 1, y, "▏", unplayed, bg)
                canvas.set(x + head.coerceIn(1, w - 2), y, "▐", 0xC0C0C0, bg, BOLD)
            }
        }
    }
}
