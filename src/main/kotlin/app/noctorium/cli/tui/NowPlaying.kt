package app.noctorium.cli.tui

import app.noctorium.domain.pageUrl
import app.noctorium.playback.PlaybackStatus

/**
 * The record: a large cover, the track, and the lyrics beside it lit up line by line as they are sung.
 *
 * The lyrics are the ones every Noctorium finds -- eight sources asked at once -- with the source chosen in
 * the chips above them, and [ / ] to change it, which is remembered for the songs after, as it is on the
 * desktop. Synced lyrics keep the line being sung a little above the middle; plain ones scroll with the wheel.
 */
object NowPlaying {
    private var manualOffset: Int? = null
    private var lastTrack: String? = null

    fun scroll(by: Int) {
        manualOffset = (manualOffset ?: 0) + by
    }

    fun draw(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        val state = tui.state
        val playback = state.playback.value
        val track = playback.track
        if (track == null) {
            canvas.writeCentred(x, w, y + h / 2 - 1, "Nothing is playing", p.subtext, null, BOLD)
            canvas.writeCentred(x, w, y + h / 2 + 1, "Press / to find something, then Enter", p.faint)
            return
        }
        if (track.queueKey != lastTrack) {
            lastTrack = track.queueKey
            manualOffset = null
            state.loadLyrics(track)
        }
        val wide = w >= 90
        // The cover: as tall as the page allows on a wide screen, two cells wide for every row.
        val coverRows = if (wide) minOf(h - 8, (w * 42 / 100) / 2).coerceAtLeast(4) else minOf(h / 2 - 3, w / 4).coerceAtLeast(3)
        val coverCols = coverRows * 2
        val left = x + 3
        val top = y + 1
        if (tui.preferences.coverArt) {
            val round = state.settings.value.preferences.desktop.nowPlaying.cover.name in setOf("CIRCLE", "RECORD")
            tui.art.draw(canvas, tui.art.get(track.artworkUrl), left, top, coverCols, coverRows, p.card, p.faint, round = round)
        }
        val infoY = top + (if (tui.preferences.coverArt) coverRows + 1 else 0)
        val infoW = if (wide) coverCols else w - 6
        canvas.write(left, infoY, track.title, p.text, null, BOLD, max = infoW)
        canvas.write(left, infoY + 1, track.artistLine, p.subtext, null, max = infoW)
        track.album?.title?.takeIf { it.isNotBlank() && it != track.title }?.let {
            canvas.write(left, infoY + 2, it, p.faint, null, ITALIC, max = infoW)
        }
        val status = when (playback.status) {
            PlaybackStatus.RESOLVING -> "Finding the audio…"
            PlaybackStatus.ERROR -> playback.errorMessage ?: "Could not play this"
            PlaybackStatus.PAUSED -> "Paused"
            else -> null
        }
        status?.let { canvas.write(left, infoY + 3, it, if (playback.status == PlaybackStatus.ERROR) p.bad else p.warn, null, max = infoW) }
        if (infoY + 5 < y + h) {
            val fraction = if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f
            val barW = (infoW - 12).coerceAtLeast(6)
            canvas.write(left, infoY + 5, formatTime(playback.positionMs), p.subtext)
            PlayerBar.seekBar(canvas, p, state.settings.value.preferences.progressBarStyle, left + 6, infoY + 5, barW, fraction.coerceIn(0f, 1f), playback.status == PlaybackStatus.PLAYING, p.page)
            canvas.write(left + 7 + barW, infoY + 5, formatTime(playback.durationMs), p.subtext)
            if (playback.durationMs > 0) {
                tui.clickTargets += Tui.ClickTarget(left + 6, infoY + 5, barW, 1) { column ->
                    state.seekTo((playback.durationMs * column / barW.toLong()).coerceIn(0, playback.durationMs - 500))
                }
            }
        }
        val actionsY = infoY + 7
        if (actionsY < y + h) {
            val liked = state.likes.value.isLiked(track)
            // Only where a like can go to the account: not under a Bandcamp song, say.
            if (liked || track.provider.keepsLikes) canvas.write(left, actionsY, if (liked) "♥ liked" else "♡ l to like", if (liked) p.accent else p.faint)
            canvas.write(left + 14, actionsY, track.provider.displayName, p.badgeColour(track.provider), null, BOLD, max = infoW - 14)
        }
        val follow = state.artistFollow.value
        if (follow != null && actionsY + 1 < y + h) {
            canvas.write(left, actionsY + 1, if (follow.following == true) "✓ following ${follow.name}" else "f to follow ${follow.name}", p.faint, max = infoW)
        }

        // The lyrics: beside the cover on a wide screen, under everything on a narrow one.
        val lx = if (wide) left + coverCols + 4 else left
        val ly = if (wide) top else actionsY + 3
        val lw = x + w - lx - 2
        val lh = y + h - ly
        if (lw > 12 && lh > 3) lyrics(tui, canvas, lx, ly, lw, lh)
    }

    private fun lyrics(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        val state = tui.state
        val lyrics = state.lyrics.value
        val position = state.playback.value.positionMs
        // The sources, as chips: the chosen one filled in.
        var cx = x
        lyrics.outcomes.forEach { outcome ->
            val chosen = outcome.provider == lyrics.selectedProvider
            val label = " ${outcome.status.mark()} ${outcome.provider.displayName} "
            val width = Canvas.displayWidth(label)
            if (cx + width > x + w) return@forEach
            canvas.write(cx, y, label, if (chosen) p.onAccent else p.subtext, if (chosen) p.accent else p.card, if (chosen) BOLD else 0)
            val target = outcome.provider
            tui.clickTargets += Tui.ClickTarget(cx, y, width, 1) { state.selectLyricsProvider(target) }
            cx += width + 1
        }
        val body = y + 2
        val bodyH = h - 2
        val outcome = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }
        val result = outcome?.result
        if (result == null || result.lines.isEmpty()) {
            val message = when {
                lyrics.loading -> "Looking in ${lyrics.outcomes.size.takeIf { it > 0 } ?: "eight"} places…"
                result?.sourceUrl != null -> "${result.provider.displayName} only links to its lyrics: o opens them."
                outcome != null -> outcome.detail ?: "${outcome.provider.displayName} has nothing for this track."
                else -> lyrics.errorMessage ?: "No lyrics for this one."
            }
            canvas.write(x, body + 1, message, p.faint, null, ITALIC, max = w)
            return
        }
        // Wrapped to the width, keeping which original line each piece came from.
        val wrapped = mutableListOf<Pair<Int, String>>()
        result.lines.forEachIndexed { index, line ->
            wrap(line.text.ifBlank { "" }, w - 1).forEach { wrapped += index to it }
        }
        val active = if (result.synced) result.lines.indexOfLast { (it.startTimeMs ?: Long.MAX_VALUE) <= position } else -1
        val activeRow = wrapped.indexOfFirst { it.first == active }
        val automatic = if (activeRow >= 0) (activeRow - bodyH * 2 / 5).coerceAtLeast(0) else 0
        val offset = ((manualOffset?.let { automatic + it }) ?: automatic).coerceIn(0, (wrapped.size - bodyH).coerceAtLeast(0))
        for (row in 0 until bodyH) {
            val (index, text) = wrapped.getOrNull(offset + row) ?: break
            val colour = when {
                index == active -> p.accent
                result.synced && index < active -> p.faint
                result.synced -> p.subtext
                else -> p.text
            }
            canvas.write(x, body + row, text, colour, null, if (index == active) BOLD else 0, max = w)
        }
        result.attribution?.let { if (offset + bodyH >= wrapped.size) canvas.write(x, y + h - 1, it, p.faint, null, ITALIC, max = w) }
    }

    private fun wrap(text: String, width: Int): List<String> {
        if (width <= 4 || Canvas.displayWidth(text) <= width) return listOf(text)
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (Canvas.displayWidth(candidate) > width && line.isNotEmpty()) {
                out += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    fun handle(tui: Tui, input: Input): Boolean {
        val state = tui.state
        val track = state.playback.value.track ?: return false
        if (input is Input.Key) when (input.key) {
            Keys.UP -> { scroll(-1); return true }
            Keys.DOWN -> { scroll(1); return true }
            Keys.HOME -> { manualOffset = null; return true }
            else -> Unit
        }
        if (input !is Input.Text) return false
        val lyrics = state.lyrics.value
        when (input.char) {
            "[", "]" -> {
                val choices = lyrics.outcomes.map { it.provider }
                if (choices.isEmpty()) return true
                val at = choices.indexOf(lyrics.selectedProvider).coerceAtLeast(0)
                val step = if (input.char == "]") 1 else choices.size - 1
                val next = choices[(at + step) % choices.size]
                state.selectLyricsProvider(next)
                manualOffset = null
            }
            "l" -> tui.like(track)
            "f" -> state.toggleFollowArtist()
            "a" -> Unit
            "o" -> lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }?.result?.sourceUrl?.let(state::openExternalUrl)
                ?: state.openExternalUrl(track.pageUrl)
            "c" -> state.copyTrackLink(track)
            "d" -> if (!state.canKeep(track)) Tracks.notKept(tui) else { state.downloadTrack(track); tui.toast("Downloading ${track.title}…") }
            "P" -> tui.overlays.addLast(Tracks.addToPlaylist(tui, track))
            "R" -> { state.loadLyrics(track, forceRefresh = true); tui.toast("Asking every source again…") }
            else -> return false
        }
        return true
    }
}
