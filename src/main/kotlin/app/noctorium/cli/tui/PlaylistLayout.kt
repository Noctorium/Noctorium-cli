package app.noctorium.cli.tui

import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.QueueColumn
import app.noctorium.settings.ThemeSkin

/**
 * Now playing's Playlist layout: a desktop music player's window. The cover in a panel of its own on the left; the
 * queue as a table with the columns chosen in Settings, under tabs -- the queue, and autoplay's songs when there are
 * some; an info bar under it with a small cover, the title set large, the artist and the album, and the visualiser;
 * and along the foot a status bar with what is playing -- "Opus, stereo, 48 kHz, 128 kbit/s" -- and how far into the
 * whole queue it is.
 *
 * The table is a list like any other: the arrows choose a song, Enter plays it, and a track's keys work on it; [ and ]
 * change the tab. The 98 and XP themes draw it as theirs: the panels and the table sunk into the face, the table's
 * headings as raised header buttons, the tabs as tabs, and the status bar in sunken panes.
 */
object PlaylistLayout {
    /** The tabs over the table. */
    enum class Tab { QUEUE, AUTOPLAY }

    private var tab = Tab.QUEUE

    /** The chosen row, or null to follow the song playing; and how far the table is scrolled. */
    private var cursor: Int? = null
    private var offset = 0
    private var lastCurrent: String? = null

    /** Rows of the table on screen last frame, by screen row, for clicks; and how many fitted. */
    private var tableTop = 0
    private var tableRows = 0

    fun draw(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        val state = tui.state
        val preferences = state.settings.value.preferences
        val queue = state.queue.state.value
        // A new song playing brings the table back to it.
        if (queue.current?.queueKey != lastCurrent) {
            lastCurrent = queue.current?.queueKey
            cursor = null
        }
        if (tab == Tab.AUTOPLAY && queue.suggestions.isEmpty()) tab = Tab.QUEUE

        val statusH = if (preferences.statusBar) 1 else 0
        val bodyH = h - statusH
        val infoH = when {
            bodyH >= 22 -> 6
            bodyH >= 14 -> 4
            else -> 0
        }
        val panel = w >= 110 && tui.preferences.coverArt && bodyH >= 14
        val panelW = if (panel) ((w * 28 / 100) / 2 * 2).coerceIn(30, 48) else 0
        if (panel) coverPanel(tui, canvas, x + 1, y + 1, panelW - 1, bodyH - 1)

        val tx = x + panelW + 1
        val tw = w - panelW - 2
        val tableH = bodyH - infoH - 1 - (if (infoH > 0) 1 else 0)
        tabs(tui, canvas, tx, y, tw)
        table(tui, canvas, tx, y + 1, tw, tableH)
        if (infoH > 0) infoBar(tui, canvas, tx, y + bodyH - infoH - 1, tw, infoH, smallCover = tui.preferences.coverArt)
        if (statusH > 0) statusBar(tui, canvas, x, y + h - 1, w)
    }

    /** The cover, as large as the panel is wide, under its heading, in a box -- or sunk into a skin's face. */
    private fun coverPanel(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        val track = PlayerBar.shownTrack(tui) ?: tui.state.queue.state.value.current
        val cols = ((w - 4) / 2 * 2).coerceAtLeast(4)
        val rows = minOf(cols / 2, h - 3).coerceAtLeast(2)
        val coverCols = rows * 2
        val left = x + (w - coverCols) / 2
        if (p.skinned) {
            canvas.write(x, y, "Cover", Skin.text(p), null, BOLD)
            tui.art.draw(canvas, tui.art.get(track?.artworkUrl), left, y + 2, coverCols, rows, Skin.window(p), Skin.greyText(p))
            Skin.frame(canvas, p, left, y + 2, coverCols, rows)
        } else {
            canvas.box(x, y - 1, w, rows + 4, p.line, null, "Cover", p.subtext)
            tui.art.draw(canvas, tui.art.get(track?.artworkUrl), left, y + 1, coverCols, rows, p.card, p.faint)
        }
    }

    /** The tabs: the queue, and autoplay's songs while there are some. Clicking one shows it; [ and ] go between them. */
    private fun tabs(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int) {
        val p = tui.palette
        val queue = tui.state.queue.state.value
        val tabs = buildList {
            add(Tab.QUEUE to "Queue (${queue.tracks.size})")
            if (queue.suggestions.isNotEmpty()) add(Tab.AUTOPLAY to "Autoplay (${queue.suggestions.size})")
        }
        var cx = x
        when (p.skin) {
            ThemeSkin.WINDOWS_98, ThemeSkin.WINDOWS_XP -> {
                // A row of tabs standing on the table's edge, the one showing lifted and joined to it.
                tabs.forEach { (which, label) ->
                    val chosen = which == tab
                    val face = if (chosen) Skin.window(p) else Skin.face(p)
                    val edge = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.TAB_EDGE else p.w98.shadow
                    val lit = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.TAB_EDGE else p.w98.highlight
                    val used = Canvas.displayWidth(label) + 4
                    canvas.fill(cx, y, used, 1, face)
                    canvas.set(cx, y, Skin.LEFT, lit, face)
                    canvas.set(cx + used - 1, y, Skin.RIGHT, edge, face)
                    canvas.write(cx + 2, y, label, Skin.text(p), face, if (chosen) BOLD else 0)
                    tui.clickTargets += Tui.ClickTarget(cx, y, used, 1) { tab = which; cursor = null }
                    cx += used
                }
            }
            else -> {
                tabs.forEach { (which, label) ->
                    val chosen = which == tab
                    val used = canvas.write(cx, y, " $label ", if (chosen) p.accent else p.subtext, if (chosen) p.selection else null, if (chosen) BOLD else 0)
                    tui.clickTargets += Tui.ClickTarget(cx, y, used, 1) { tab = which; cursor = null }
                    cx += used + 1
                }
            }
        }
    }

    /** The songs of the tab showing, with the index each has in its own list. */
    private fun songs(tui: Tui): List<Track> {
        val queue = tui.state.queue.state.value
        return if (tab == Tab.AUTOPLAY) queue.suggestions else queue.tracks
    }

    /** Where the song playing is in the tab showing, or -1. */
    private fun playingIndex(tui: Tui): Int = if (tab == Tab.QUEUE) tui.state.queue.state.value.currentIndex else -1

    private fun width(column: QueueColumn): Int? = when (column) {
        QueueColumn.NUMBER -> 4
        QueueColumn.DURATION -> 8
        QueueColumn.SERVICE -> 12
        else -> null
    }

    private fun weight(column: QueueColumn): Int = when (column) {
        QueueColumn.TITLE -> 5
        else -> 3
    }

    /** The table: its headings, then a row for each song, the one playing marked and the one chosen picked out. */
    private fun table(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        val skinned = p.skinned
        val columns = QueueColumn.normalised(tui.state.settings.value.preferences.queueColumns)
        val songs = songs(tui)
        val playing = playingIndex(tui)
        // A gutter for the playing mark, then the columns with a cell between each.
        val gutter = 3
        val fixed = columns.sumOf { width(it) ?: 0 } + (columns.size - 1)
        val flexible = columns.filter { width(it) == null }
        val room = (w - gutter - fixed - 1).coerceAtLeast(flexible.size * 4)
        val weights = flexible.sumOf(::weight).coerceAtLeast(1)
        var given = 0
        val widths = columns.associateWith { column ->
            width(column) ?: run {
                val last = column == flexible.last()
                val share = if (last) room - given else room * weight(column) / weights
                given += share
                share
            }
        }

        // The headings: a strip of the panel's colour, or 98's and XP's raised header buttons.
        val headerBg = if (skinned) Skin.face(p) else p.panel.takeIf { it != DEFAULT } ?: p.card
        val bodyBg = if (skinned) Skin.window(p) else null
        if (skinned) Skin.well(canvas, p, x + 1, y + 1, w - 2, h - 1, top = false, foot = false)
        canvas.fill(x, y, w, 1, headerBg)
        var cx = x + gutter
        columns.forEach { column ->
            val cw = widths.getValue(column)
            val heading = column.heading
            if (skinned) {
                val lit = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.WINDOW else p.w98.highlight
                val shade = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.TAB_EDGE else p.w98.darkShadow
                canvas.set(cx - 1, y, Skin.LEFT, lit, headerBg)
                canvas.set(cx + cw, y, Skin.LEFT, shade, headerBg)
            }
            val ink = if (skinned) Skin.text(p) else p.subtext
            if (column == QueueColumn.NUMBER || column == QueueColumn.DURATION) canvas.writeRight(cx + cw, y, heading, ink, headerBg, BOLD)
            else canvas.write(cx, y, heading, ink, headerBg, BOLD, max = cw)
            cx += cw + 1
        }

        // The rows.
        val visible = (h - 1).coerceAtLeast(0)
        tableTop = y + 1
        tableRows = visible
        val chosen = (cursor ?: playing.coerceAtLeast(0)).coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        if (cursor == null) {
            // Following the song playing: a couple of rows of what came before it stay in view.
            offset = (playing - 2).coerceAtLeast(0)
        }
        if (chosen < offset) offset = chosen
        if (chosen >= offset + visible) offset = chosen - visible + 1
        offset = offset.coerceIn(0, (songs.size - visible).coerceAtLeast(0))
        if (songs.isEmpty()) {
            val note = if (tab == Tab.QUEUE) "The queue is empty. Play something, and it is listed here." else "Autoplay has nothing lined up."
            canvas.write(x + gutter, y + 2, note, if (skinned) Skin.greyText(p) else p.faint, bodyBg, ITALIC, max = w - gutter - 1)
        }
        val likes = tui.state.likes.value
        for (line in 0 until visible) {
            val index = offset + line
            val track = songs.getOrNull(index) ?: break
            val ry = y + 1 + line
            val isPlaying = index == playing
            val isChosen = index == chosen && (cursor != null || isPlaying)
            val back = when {
                isChosen && skinned -> Skin.selection(p)
                isChosen -> p.selection
                else -> bodyBg
            }
            if (back != null) canvas.fill(x + (if (skinned) 1 else 0), ry, w - (if (skinned) 2 else 0), 1, back)
            val ink = when {
                isChosen && skinned -> Skin.selectionText(p)
                skinned -> Skin.text(p)
                isPlaying -> p.accent
                tab == Tab.AUTOPLAY -> p.subtext
                else -> p.text
            }
            val quiet = when {
                isChosen && skinned -> Skin.selectionText(p)
                skinned -> Skin.greyText(p)
                else -> p.subtext
            }
            if (isPlaying) canvas.write(x + 1, ry, tui.glyphs.playing, if (skinned) ink else p.accent, back, BOLD, max = 1)
            cx = x + gutter
            columns.forEach { column ->
                val cw = widths.getValue(column)
                val style = if (isPlaying) BOLD else 0
                when (column) {
                    QueueColumn.NUMBER -> canvas.writeRight(cx + cw, ry, "${index + 1}", quiet, back)
                    QueueColumn.TITLE -> {
                        val used = canvas.write(cx, ry, track.title, ink, back, style, max = cw - 2)
                        if (likes.isLiked(track) && used + 2 <= cw) canvas.write(cx + used + 1, ry, tui.glyphs.liked, if (skinned) ink else p.accent, back, max = 1)
                    }
                    QueueColumn.ARTIST -> canvas.write(cx, ry, track.artistLine, quiet, back, max = cw)
                    QueueColumn.ALBUM -> canvas.write(cx, ry, track.album?.title.orEmpty(), quiet, back, max = cw)
                    QueueColumn.DURATION -> canvas.writeRight(cx + cw, ry, formatTime(track.durationMs), quiet, back)
                    QueueColumn.SERVICE -> canvas.write(cx, ry, track.provider.displayName, if (skinned || isChosen) quiet else p.badgeColour(track.provider), back, max = cw)
                }
                cx += cw + 1
            }
            val target = index
            tui.clickTargets += Tui.ClickTarget(x, ry, w, 1) {
                if (cursor == target || (cursor == null && target == playing)) play(tui, target) else cursor = target
            }
        }
        // A scroll mark, so a long queue says it goes on.
        if (skinned) Skin.scrollBar(canvas, p, x + w - 2, y + 1, visible, offset, visible, songs.size)
        else if (songs.size > visible && visible > 2) {
            val thumb = (visible * visible / songs.size).coerceIn(1, visible)
            val at = ((visible - thumb) * offset / (songs.size - visible).coerceAtLeast(1)).coerceIn(0, visible - thumb)
            for (i in 0 until visible) canvas.set(x + w - 1, y + 1 + i, if (i in at until at + thumb) "┃" else "│", if (i in at until at + thumb) p.accent else p.line)
        }
    }

    /**
     * The info bar: a small cover, the title set large -- in big type where it fits, in bold where it does not -- the
     * artist and the album under it, and the visualiser at the right, in its own sunken well in the skins.
     */
    private fun infoBar(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, smallCover: Boolean) {
        val p = tui.palette
        val state = tui.state
        val skinned = p.skinned
        val bg = if (skinned) Skin.face(p) else null
        if (skinned) canvas.fill(x, y, w, h + 1, Skin.face(p))
        else for (c in x until x + w) canvas.set(c, y, "─", p.line)
        val top = y + 1
        val rows = h
        val track = PlayerBar.shownTrack(tui) ?: state.queue.state.value.current
        var left = x + 1
        if (smallCover) {
            val coverRows = rows.coerceAtMost(4)
            tui.art.draw(canvas, tui.art.get(track?.artworkUrl), left, top + (rows - coverRows) / 2, coverRows * 2, coverRows, if (skinned) Skin.window(p) else p.card, p.faint)
            left += coverRows * 2 + 2
        }
        val style = state.settings.value.preferences.visualiser
        val visualiserW = if (w >= 90) minOf(30, w / 3) else if (w >= 64) 22 else 0
        val textW = (x + w - left - visualiserW - 2).coerceAtLeast(8)
        val ink = if (skinned) Skin.text(p) else p.text
        val quiet = if (skinned) Skin.greyText(p) else p.subtext
        if (track == null) {
            canvas.write(left, top + rows / 2 - 1, "Nothing playing", quiet, bg, BOLD, max = textW)
        } else {
            val setting = if (rows >= 5) BigType.fit(track.title, textW, 3, maxLines = 1) else null
            var row = top
            if (setting != null) {
                BigType.draw(canvas, left, row, setting.lines.first(), setting.size, ink)
                row += setting.size.rows
            } else {
                canvas.write(left, row, track.title, ink, bg, BOLD, max = textW)
                row++
            }
            canvas.write(left, row, track.artistLine, if (skinned) ink else p.accent, bg, BOLD, max = textW)
            track.album?.title?.takeIf { it.isNotBlank() && it != track.title }?.let {
                if (row + 1 < top + rows) canvas.write(left, row + 1, it, quiet, bg, ITALIC, max = textW)
            }
        }
        if (visualiserW > 0) {
            val vx = x + w - visualiserW - 1
            val vh = rows - 1
            if (skinned && style != app.noctorium.settings.VisualiserStyle.OFF) {
                // A sunken well of its own, as a desktop player's visualiser sits in.
                Skin.well(canvas, p, vx, top, visualiserW, vh)
                Visualisers.draw(tui, canvas, vx, top, visualiserW, vh, Skin.window(p), style)
            } else {
                Visualisers.draw(tui, canvas, vx, top, visualiserW, vh, bg, style)
            }
        }
    }

    /**
     * The status bar: what is playing, as a status bar writes it, the booster and stop after this song while they are
     * on, and at the right how far into the whole queue it is, "2:31 / 1:03:41". In the skins, sunken panes.
     */
    private fun statusBar(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int) {
        val p = tui.palette
        val state = tui.state
        val playback = state.playback.value
        val queue = state.queue.state.value
        val skinned = p.skinned
        val bg = if (skinned) Skin.face(p) else p.panel.takeIf { it != DEFAULT } ?: p.card
        canvas.fill(x, y, w, 1, bg)
        val track = PlayerBar.shownTrack(tui)
        val format = when {
            track != null && NowPlaying.playsOnSpotify(tui, track) -> "Playing in Spotify's app"
            playback.status == PlaybackStatus.RESOLVING -> "Finding the audio…"
            track == null -> if (queue.current != null) "Stopped" else "Nothing playing"
            else -> playback.format?.describe() ?: "–"
        }
        val position = if (track != null) playback.positionMs else 0L
        val elapsed = queue.elapsedMs(position)
        val right = if (queue.durationMs > 0) "${if (elapsed > 0) formatTime(elapsed) else "0:00"} / ${formatTime(queue.durationMs)}" else ""
        val marks = buildList {
            PlayerBar.boostMark(tui)?.let { add("Boost $it") }
            if (state.stopAfterCurrent.value) add("${tui.glyphs.stop} Stops after this song")
            state.sleepTimerRemainingMs.value?.let { add("${tui.glyphs.sleep} ${formatTime(it)}") }
        }.joinToString("   ")
        val ink = if (skinned) Skin.text(p) else p.subtext
        val rightW = Canvas.displayWidth(right)
        if (skinned) {
            // Sunken panes, as a window's status bar had them: what is playing, then the rest at the right.
            val c = p.w98
            val shade = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.TAB_EDGE else c.shadow
            val lit = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.WINDOW else c.highlight
            val rightX = x + w - rightW - 3
            canvas.set(x, y, Skin.LEFT, shade, bg)
            canvas.set(rightX - 2, y, Skin.RIGHT, lit, bg)
            if (right.isNotEmpty()) {
                canvas.set(rightX - 1, y, Skin.LEFT, shade, bg)
                canvas.set(x + w - 1, y, Skin.RIGHT, lit, bg)
            }
            val used = canvas.write(x + 1, y, format, ink, bg, max = rightX - x - 4)
            if (marks.isNotEmpty() && x + 1 + used + 3 + Canvas.displayWidth(marks) < rightX - 3) canvas.write(x + used + 4, y, marks, ink, bg)
            canvas.write(rightX, y, right, ink, bg)
        } else {
            val used = canvas.write(x + 2, y, format, ink, bg, max = w - rightW - 6)
            if (marks.isNotEmpty() && x + 2 + used + 3 + Canvas.displayWidth(marks) < x + w - rightW - 4) canvas.write(x + used + 5, y, marks, p.accent, bg, BOLD)
            canvas.writeRight(x + w - 2, y, right, ink, bg)
        }
    }

    /** Plays the song at [index] of the tab showing: one of the queue's, or one of autoplay's. */
    private fun play(tui: Tui, index: Int) {
        if (tab == Tab.AUTOPLAY) tui.state.playSuggestion(index) else tui.state.jumpToQueueItem(index)
        cursor = null
    }

    /** The song chosen, as a list's row, for a track's keys to work on. */
    private fun chosenRow(tui: Tui): Row.Song? {
        val queue = tui.state.queue.state.value
        val songs = songs(tui)
        val index = (cursor ?: playingIndex(tui)).takeIf { it in songs.indices } ?: return null
        return if (tab == Tab.AUTOPLAY) Row.Song(songs[index], songs, PlaybackOrigin.QUEUE, suggestion = index)
        else Row.Song(songs[index], queue.tracks, PlaybackOrigin.QUEUE, queueIndex = index)
    }

    /** The keys of this layout: the arrows choose, Enter plays, [ and ] change the tab, and a track's keys work on the one chosen. */
    fun handle(tui: Tui, input: Input): Boolean {
        val songs = songs(tui)
        val at = cursor ?: playingIndex(tui).coerceAtLeast(0)
        val page = (tableRows - 1).coerceAtLeast(1)
        fun moveTo(index: Int) { if (songs.isNotEmpty()) cursor = index.coerceIn(0, songs.lastIndex) }
        if (input is Input.Key) when (input.key) {
            Keys.UP -> { moveTo(at - 1); return true }
            Keys.DOWN -> { moveTo(at + 1); return true }
            Keys.PAGE_UP -> { moveTo(at - page); return true }
            Keys.PAGE_DOWN -> { moveTo(at + page); return true }
            Keys.HOME -> { moveTo(0); return true }
            Keys.END -> { moveTo(songs.lastIndex); return true }
            Keys.ENTER -> { if (at in songs.indices) play(tui, at); return true }
            Keys.ESCAPE -> if (cursor != null) { cursor = null; return true }
            else -> Unit
        }
        if (input !is Input.Text) return false
        val keys = tui.keys
        if (keys.matches(input.char, KeyAction.LYRICS_PREVIOUS) || keys.matches(input.char, KeyAction.LYRICS_NEXT)) {
            val tabs = if (tui.state.queue.state.value.suggestions.isEmpty()) listOf(Tab.QUEUE) else Tab.entries
            tab = tabs[(tabs.indexOf(tab) + (if (keys.matches(input.char, KeyAction.LYRICS_NEXT)) 1 else tabs.size - 1)) % tabs.size]
            cursor = null
            return true
        }
        val row = chosenRow(tui) ?: return false
        return Tracks.handle(tui, row, input.char)
    }

    /** Wheel: the table scrolls by moving the choice. */
    fun scroll(tui: Tui, by: Int) {
        val songs = songs(tui)
        if (songs.isEmpty()) return
        cursor = ((cursor ?: playingIndex(tui).coerceAtLeast(0)) + by).coerceIn(0, songs.lastIndex)
    }
}
