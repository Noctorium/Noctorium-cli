package app.noctorium.cli.tui

import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.pageUrl
import app.noctorium.lyrics.LyricsResult
import app.noctorium.playback.PlaybackStatus

/**
 * The record: the cover, the track, and the lyrics lit up line by line as they are sung, laid out as the
 * listener chose in Settings (see [TuiNowPlaying]) -- the cover with the lyrics beside it, a poster in big type,
 * the cover as large as it goes, or the lyrics large.
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
            canvas.writeCentred(x, w, y + h / 2 + 1, "Press ${tui.key(KeyAction.SEARCH)} to find something, then Enter", p.faint)
            return
        }
        if (track.queueKey != lastTrack) {
            lastTrack = track.queueKey
            manualOffset = null
            state.loadLyrics(track)
        }
        when (tui.preferences.nowPlaying) {
            TuiNowPlaying.CLASSIC -> classic(tui, canvas, x, y, w, h, track)
            TuiNowPlaying.BIG_TYPE -> poster(tui, canvas, x, y, w, h, track)
            // With covers hidden there is nothing for this one to show but the words, which the poster sets larger.
            TuiNowPlaying.COVER -> if (tui.preferences.coverArt) cover(tui, canvas, x, y, w, h, track) else poster(tui, canvas, x, y, w, h, track)
            TuiNowPlaying.LYRICS -> singAlong(tui, canvas, x, y, w, h, track)
        }
    }

    private fun classic(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, track: Track) {
        val p = tui.palette
        val state = tui.state
        val playback = state.playback.value
        val wide = w >= 90
        // The cover: as tall as the page allows on a wide screen, two cells wide for every row.
        val coverRows = if (wide) minOf(h - 8, (w * 42 / 100) / 2).coerceAtLeast(4) else minOf(h / 2 - 3, w / 4).coerceAtLeast(3)
        val coverCols = coverRows * 2
        val left = x + 3
        val top = y + 1
        if (tui.preferences.coverArt) {
            tui.art.draw(canvas, tui.art.get(track.artworkUrl), left, top, coverCols, coverRows, p.card, p.faint, round = round(tui))
            if (p.skinned) Skin.frame(canvas, p, left, top, coverCols, coverRows)
        }
        val infoY = top + (if (tui.preferences.coverArt) coverRows + 1 else 0)
        val infoW = if (wide) coverCols else w - 6
        canvas.write(left, infoY, track.title, p.text, null, BOLD, max = infoW)
        canvas.write(left, infoY + 1, track.artistLine, p.subtext, null, max = infoW)
        track.album?.title?.takeIf { it.isNotBlank() && it != track.title }?.let {
            canvas.write(left, infoY + 2, it, p.faint, null, ITALIC, max = infoW)
        }
        status(tui)?.let { (text, colour) -> canvas.write(left, infoY + 3, text, colour, null, max = infoW) }
        if (infoY + 5 < y + h) {
            val fraction = if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f
            val barW = (infoW - 12).coerceAtLeast(6)
            canvas.write(left, infoY + 5, formatTime(playback.positionMs), p.subtext)
            SeekBars.draw(
                canvas, p, state.settings.value.preferences.progressBarStyle, left + 6, infoY + 5, barW, fraction.coerceIn(0f, 1f),
                playback.status == PlaybackStatus.PLAYING, p.page, seed = track.queueKey, durationMs = playback.durationMs,
                roomAbove = true, roomBelow = true,
            )
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
            if (liked || track.provider.keepsLikes) {
                canvas.write(left, actionsY, if (liked) "♥ liked" else "♡ ${tui.key(KeyAction.LIKE)} to like", if (liked) p.accent else p.faint)
            }
            // A Spotify song the account's own Spotify app is playing says so: the sound is not coming from here.
            canvas.write(left + 14, actionsY, where(tui, track), p.badgeColour(track.provider), null, BOLD, max = infoW - 14)
        }
        val follow = state.artistFollow.value
        if (follow != null && actionsY + 1 < y + h) {
            canvas.write(
                left, actionsY + 1,
                if (follow.following == true) "✓ following ${follow.name}" else "${tui.key(KeyAction.FOLLOW_ARTIST)} to follow ${follow.name}",
                p.faint, max = infoW,
            )
        }

        // The lyrics: beside the cover on a wide screen, under everything on a narrow one.
        val lx = if (wide) left + coverCols + 4 else left
        val ly = if (wide) top else actionsY + 3
        val lw = x + w - lx - 2
        val lh = y + h - ly
        if (lw > 12 && lh > 3) lyrics(tui, canvas, lx, ly, lw, lh)
    }

    /**
     * Big type: no cover, the title across the middle in large letters and the artist spaced out under it, then
     * the seek bar and what can be done -- a poster for the song. A title the type has no letters for is
     * written in bold instead.
     */
    private fun poster(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, track: Track) {
        val p = tui.palette
        val room = w - 8
        val setting = BigType.fit(track.title, room, (h - 10).coerceAtLeast(3))
        val plain = if (setting == null) wrapWords(track.title, room).take(3) else emptyList()
        val artist = spaced(track.artistLine).takeIf { Canvas.displayWidth(it) <= room } ?: track.artistLine
        val album = track.album?.title?.takeIf { it.isNotBlank() && it != track.title }
        val follow = tui.state.artistFollow.value
        // The title, a gap, the artist and the album, a gap and a row for the seek bar's pointer, the bar, a gap,
        // what can be done, and following the artist.
        val height = (setting?.height ?: plain.size) + 1 + 1 + (if (album != null) 1 else 0) + 2 + 1 + 1 + 1 + (if (follow != null) 1 else 0)
        var row = y + ((h - height) / 2).coerceAtLeast(1)
        if (setting != null) {
            setting.lines.forEach { line ->
                BigType.draw(canvas, x + (w - BigType.width(line, setting.size)) / 2, row, line, setting.size, p.text)
                row += setting.size.rows + 1
            }
        } else {
            plain.forEach { line -> canvas.writeCentred(x, w, row, line, p.text, null, BOLD); row++ }
            row++
        }
        canvas.writeCentred(x, w, row++, artist, p.accent, null, BOLD)
        album?.let { canvas.writeCentred(x, w, row++, it, p.faint, null, ITALIC) }
        row += 2
        footer(tui, canvas, x, w, row, track, seekWidth = minOf(room, 76))
    }

    /**
     * Cover: the cover as large as the page allows, in the middle, and the track, the seek bar and what can be
     * done beneath it -- for the sleeve rather than the words.
     */
    private fun cover(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, track: Track) {
        val p = tui.palette
        // Under it: a gap, the title and the artist, a gap and a row for the seek bar's pointer, the bar, a gap
        // and what can be done.
        val under = 8
        val rows = minOf(h - under - 1, (w - 6) / 2).coerceAtLeast(3)
        val cols = rows * 2
        var row = y + ((h - rows - under) / 2).coerceAtLeast(1)
        tui.art.draw(canvas, tui.art.get(track.artworkUrl), x + (w - cols) / 2, row, cols, rows, p.card, p.faint, round = round(tui))
        if (p.skinned) Skin.frame(canvas, p, x + (w - cols) / 2, row, cols, rows)
        row += rows + 1
        canvas.writeCentred(x, w, row++, track.title, p.text, null, BOLD)
        val album = track.album?.title?.takeIf { it.isNotBlank() && it != track.title }
        canvas.writeCentred(x, w, row++, listOfNotNull(track.artistLine, album).joinToString(" · "), p.subtext)
        row += 2
        footer(tui, canvas, x, w, row, track, seekWidth = maxOf(cols, 40).coerceAtMost(w - 6))
    }

    /**
     * The seek bar centred on [row], [seekWidth] across with its times, and under it what can be done -- the
     * song's state, the like, the service -- and the artist to follow.
     */
    private fun footer(tui: Tui, canvas: Canvas, x: Int, w: Int, row: Int, track: Track, seekWidth: Int) {
        val p = tui.palette
        PlayerBar.seek(tui, canvas, x + (w - seekWidth) / 2, row, seekWidth, p.page, roomAbove = true, roomBelow = true)
        val liked = tui.state.likes.value.isLiked(track)
        val parts = buildList {
            status(tui)?.let { add(it) }
            if (liked) add("♥ liked" to p.accent) else if (track.provider.keepsLikes) add("♡ ${tui.key(KeyAction.LIKE)} to like" to p.faint)
            add(where(tui, track) to p.badgeColour(track.provider))
        }
        centredParts(canvas, x, w, row + 2, parts)
        tui.state.artistFollow.value?.let { follow ->
            val text = if (follow.following == true) "✓ following ${follow.name}" else "${tui.key(KeyAction.FOLLOW_ARTIST)} to follow ${follow.name}"
            canvas.writeCentred(x, w, row + 3, text, p.faint)
        }
    }

    /**
     * Lyrics: the words large and centred on the line being sung -- in big type where it can set them -- with the
     * lines sung dimmed above it and the ones to come below, under a small cover, the track and the controls.
     */
    private fun singAlong(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, track: Track) {
        val p = tui.palette
        val left = x + 3
        val top = y + 1
        var textX = left
        if (tui.preferences.coverArt) {
            tui.art.draw(canvas, tui.art.get(track.artworkUrl), left, top, 8, 4, p.card, p.faint, round = round(tui))
            if (p.skinned) Skin.frame(canvas, p, left, top, 8, 4)
            textX += 10
        }
        val textW = x + w - textX - 2
        canvas.write(textX, top, track.title, p.text, null, BOLD, max = textW)
        val album = track.album?.title?.takeIf { it.isNotBlank() && it != track.title }
        canvas.write(textX, top + 1, listOfNotNull(track.artistLine, album).joinToString(" · "), p.subtext, null, max = textW)
        val controlsW = PlayerBar.TRANSPORT_WIDTH
        PlayerBar.transport(tui, canvas, textX, top + 3, p.page, roomBelow = true)
        PlayerBar.seek(tui, canvas, textX + controlsW + 2, top + 3, textW - controlsW - 2, p.page, roomAbove = true)
        val liked = tui.state.likes.value.isLiked(track)
        val heart = when {
            liked -> "♥" to p.accent
            track.provider.keepsLikes -> "♡" to p.faint
            else -> null
        }
        heart?.let { (mark, colour) -> canvas.write(x + w - 4, top, mark, colour, null, BOLD) }
        status(tui)?.let { (text, colour) -> canvas.writeRight(x + w - 6, top, text, colour) }

        val bodyTop = top + 5
        val bodyH = y + h - 1 - bodyTop
        if (bodyH < 3) return
        bigLyrics(tui, canvas, x + 2, bodyTop, w - 4, bodyH)
        // The sources along the foot, the chosen one filled in, as above the lyrics in the classic layout.
        chips(tui, canvas, x + 3, y + h - 1, w - 6, centred = true)
    }

    /** The synced line being sung in the middle of [h] rows, large where it can be; plain lyrics from the top. */
    private fun bigLyrics(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        val lyrics = tui.state.lyrics.value
        val result = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }?.result
        if (result == null || result.lines.isEmpty()) {
            canvas.writeCentred(x, w, y + h / 2, noLyrics(tui), p.faint, null, ITALIC)
            return
        }
        if (!result.synced) {
            // Plain lyrics, centred, scrolled by the wheel and the arrows.
            val wrapped = result.lines.flatMap { wrap(it.text.ifBlank { "" }, w) }
            val offset = (manualOffset ?: 0).coerceIn(0, (wrapped.size - h).coerceAtLeast(0))
            for (row in 0 until h) canvas.writeCentred(x, w, y + row, wrapped.getOrNull(offset + row) ?: break, p.text)
            return
        }
        val position = tui.state.playback.value.positionMs
        val sung = result.lines.indexOfLast { (it.startTimeMs ?: Long.MAX_VALUE) <= position }
        // The wheel moves which line is in the middle, from the one being sung.
        val focus = (sung + (manualOffset ?: 0)).coerceIn(0, result.lines.lastIndex)
        val words = result.lines[focus].text.ifBlank { "♪" }
        val setting = BigType.fit(words, w - 2, minOf(9, h - 2), maxLines = 2)
        val plain = if (setting == null) wrap(words, w) else emptyList()
        val focusH = setting?.height ?: plain.size
        val focusTop = y + (h - focusH) / 2
        val colour = if (focus == sung) p.accent else p.text
        if (setting != null) {
            var row = focusTop
            setting.lines.forEach { line ->
                BigType.draw(canvas, x + (w - BigType.width(line, setting.size)) / 2, row, line, setting.size, colour)
                row += setting.size.rows + 1
            }
        } else {
            plain.forEachIndexed { i, line -> canvas.writeCentred(x, w, focusTop + i, line, colour, null, BOLD) }
        }
        // The lines before it upwards from a row above, and the ones after it downwards from a row below.
        var above = focusTop - 2
        for (index in focus - 1 downTo 0) {
            val pieces = wrap(result.lines[index].text, w)
            for (piece in pieces.reversed()) {
                if (above < y) break
                canvas.writeCentred(x, w, above--, piece, p.faint)
            }
            if (above < y) break
        }
        var below = focusTop + focusH + 1
        for (index in focus + 1 until result.lines.size) {
            for (piece in wrap(result.lines[index].text, w)) {
                if (below >= y + h) break
                canvas.writeCentred(x, w, below++, piece, p.subtext)
            }
            if (below >= y + h) break
        }
    }

    private fun lyrics(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        val state = tui.state
        val lyrics = state.lyrics.value
        val position = state.playback.value.positionMs
        chips(tui, canvas, x, y, w, centred = false)
        val body = y + 2
        val bodyH = h - 2
        val result = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }?.result
        if (result == null || result.lines.isEmpty()) {
            canvas.write(x, body + 1, noLyrics(tui), p.faint, null, ITALIC, max = w)
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

    /**
     * The lyrics sources as chips, the chosen one filled in -- or, in the Windows themes, as a row of their radio
     * buttons, which is what choosing one of several was in them. Clicking one chooses it.
     */
    private fun chips(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, centred: Boolean) {
        val p = tui.palette
        val state = tui.state
        val lyrics = state.lyrics.value
        val labels = lyrics.outcomes.map {
            if (p.skinned) "  ${it.provider.displayName} ${it.status.mark()} " else " ${it.status.mark()} ${it.provider.displayName} "
        }
        // Laid from the left in order; one too long for what is left is passed over for a shorter one after it.
        val fitting = mutableListOf<Int>()
        var used = 0
        labels.forEachIndexed { i, label ->
            if (used + Canvas.displayWidth(label) > w) return@forEachIndexed
            fitting += i
            used += Canvas.displayWidth(label) + 1
        }
        var cx = if (centred) x + (w - used + 1) / 2 else x
        fitting.forEach { i ->
            val outcome = lyrics.outcomes[i]
            val chosen = outcome.provider == lyrics.selectedProvider
            val width = Canvas.displayWidth(labels[i])
            if (p.skinned) {
                canvas.write(cx, y, labels[i], Skin.text(p), null, if (chosen) BOLD else 0)
                Skin.radio(canvas, p, cx, y, chosen)
            } else {
                canvas.write(cx, y, labels[i], if (chosen) p.onAccent else p.subtext, if (chosen) p.accent else p.card, if (chosen) BOLD else 0)
            }
            val target = outcome.provider
            tui.clickTargets += Tui.ClickTarget(cx, y, width, 1) { state.selectLyricsProvider(target) }
            cx += width + 1
        }
    }

    /** What stands in for lyrics there are none of: still looking, a link, a source's own reason, or none. */
    private fun noLyrics(tui: Tui): String {
        val lyrics = tui.state.lyrics.value
        val outcome = lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }
        val result: LyricsResult? = outcome?.result
        return when {
            lyrics.loading -> "Looking in ${lyrics.outcomes.size.takeIf { it > 0 } ?: "eight"} places…"
            result?.sourceUrl != null -> "${result.provider.displayName} only links to its lyrics: ${tui.key(KeyAction.OPEN_PAGE)} opens them."
            outcome != null -> outcome.detail ?: "${outcome.provider.displayName} has nothing for this track."
            else -> lyrics.errorMessage ?: "No lyrics for this one."
        }
    }

    /** What the song is doing, when it is not simply playing: paused, being found, or failed. */
    private fun status(tui: Tui): Pair<String, Rgb>? {
        val p = tui.palette
        val playback = tui.state.playback.value
        return when (playback.status) {
            PlaybackStatus.RESOLVING -> "Finding the audio…" to p.warn
            PlaybackStatus.ERROR -> (playback.errorMessage ?: "Could not play this") to p.bad
            PlaybackStatus.PAUSED -> "Paused" to p.warn
            else -> null
        }
    }

    /** Where the song is from -- or, for a Spotify song the account's own Spotify app is playing, that it is there. */
    private fun where(tui: Tui, track: Track): String = if (playsOnSpotify(tui, track)) "on Spotify" else track.provider.displayName

    /** Whether the desktop draws covers round, which the terminal follows. */
    private fun round(tui: Tui): Boolean = tui.state.settings.value.preferences.desktop.nowPlaying.cover.name in setOf("CIRCLE", "RECORD")

    /** [parts], each in its own colour and three cells apart, centred in the [w] cells from [x]. */
    private fun centredParts(canvas: Canvas, x: Int, w: Int, y: Int, parts: List<Pair<String, Rgb>>) {
        val total = parts.sumOf { Canvas.displayWidth(it.first) } + 3 * (parts.size - 1).coerceAtLeast(0)
        var cx = x + ((w - total) / 2).coerceAtLeast(0)
        parts.forEach { (text, colour) -> cx += canvas.write(cx, y, text, colour, null, BOLD, max = x + w - cx) + 3 }
    }

    /** An artist's name in spaced capitals, as a poster sets it, where it is in narrow letters to begin with. */
    private fun spaced(text: String): String {
        if (Canvas.displayWidth(text) != text.length) return text
        return text.uppercase().split(' ').filter(String::isNotEmpty).joinToString("   ") { word -> word.toList().joinToString(" ") }
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
        // This page's own keys first, then the chosen track's -- here, the one playing -- as on every list.
        val action = tui.keys.action(input.char, KeyScope.NOW_PLAYING) ?: tui.keys.action(input.char, KeyScope.TRACK)
        when (action) {
            KeyAction.LYRICS_PREVIOUS, KeyAction.LYRICS_NEXT -> {
                val choices = lyrics.outcomes.map { it.provider }
                if (choices.isEmpty()) return true
                val at = choices.indexOf(lyrics.selectedProvider).coerceAtLeast(0)
                val step = if (action == KeyAction.LYRICS_NEXT) 1 else choices.size - 1
                val next = choices[(at + step) % choices.size]
                state.selectLyricsProvider(next)
                manualOffset = null
            }
            KeyAction.LIKE -> tui.like(track)
            KeyAction.FOLLOW_ARTIST -> state.toggleFollowArtist()
            KeyAction.ADD_TO_QUEUE -> Unit
            KeyAction.OPEN_PAGE -> lyrics.outcomes.firstOrNull { it.provider == lyrics.selectedProvider }?.result?.sourceUrl?.let(state::openExternalUrl)
                ?: state.openExternalUrl(track.pageUrl)
            KeyAction.COPY_LINK -> state.copyTrackLink(track)
            KeyAction.DOWNLOAD -> if (!state.canKeep(track)) Tracks.notKept(tui, track) else { state.downloadTrack(track); tui.toast("Downloading ${track.title}…") }
            KeyAction.ADD_TO_PLAYLIST -> tui.overlays.addLast(Tracks.addToPlaylist(tui, track))
            KeyAction.LYRICS_AGAIN -> { state.loadLyrics(track, forceRefresh = true); tui.toast("Asking every source again…") }
            else -> return false
        }
        return true
    }

    /** Whether [track] is a Spotify song that the account's own Spotify app is playing, rather than this computer. */
    fun playsOnSpotify(tui: Tui, track: Track): Boolean =
        track.provider == ProviderType.SPOTIFY && tui.state.settings.value.spotify.playsOnSpotify
}
