package app.noctorium.cli.tui

import app.noctorium.bandcamp.BandcampMusicProvider
import app.noctorium.core.AppState
import app.noctorium.domain.Playlist
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playlists.LocalPlaylist

/** One line of a list page. Headers and notes are drawn but skipped over by the selection. */
sealed interface Row {
    val selectable: Boolean get() = true

    data class Header(val title: String, val note: String? = null) : Row {
        override val selectable get() = false
    }

    data class Note(val text: String, val tone: Tone = Tone.QUIET) : Row {
        override val selectable get() = false
    }

    data object Gap : Row {
        override val selectable get() = false
    }

    /** A track, and the list it sits in, which becomes the queue when it is played from here. */
    data class Song(
        val track: Track,
        val list: List<Track>,
        val origin: PlaybackOrigin,
        val number: Int? = null,
        /** Where it sits in the queue, on the queue page. */
        val queueIndex: Int? = null,
        /** The service playlist it belongs to, so it can be taken out of it. */
        val inPlaylist: Playlist? = null,
        val inLocalPlaylist: LocalPlaylist? = null,
        val playlistIndex: Int? = null,
    ) : Row

    data class PlaylistItem(val playlist: Playlist) : Row
    data class LocalItem(val playlist: LocalPlaylist) : Row

    /** Something to do: sign in, change a setting, start the web player. */
    data class Action(
        val label: String,
        val value: String? = null,
        val hint: String? = null,
        val tone: Tone = Tone.NORMAL,
        /** Left and right change a setting's value; Enter does [run]. */
        val step: ((Int) -> Unit)? = null,
        val run: () -> Unit = { step?.invoke(1) },
        /** What Delete does on this row, where there is something to put back: a key changed, say. */
        val remove: (() -> Unit)? = null,
    ) : Row

    enum class Tone { NORMAL, QUIET, GOOD, WARN, BAD, ACCENT }
}

/** Where a list is scrolled to and which line is chosen, kept per page so going back finds it unchanged. */
class ListState {
    var selected = 0
    var offset = 0

    fun clamp(rows: List<Row>) {
        if (rows.isEmpty()) { selected = 0; offset = 0; return }
        selected = selected.coerceIn(0, rows.lastIndex)
        if (!rows[selected].selectable) {
            val next = (selected until rows.size).firstOrNull { rows[it].selectable }
                ?: (selected downTo 0).firstOrNull { rows[it].selectable }
            selected = next ?: 0
        }
    }

    fun move(rows: List<Row>, by: Int) {
        if (rows.isEmpty()) return
        var i = selected
        var left = kotlin.math.abs(by)
        val step = if (by > 0) 1 else -1
        while (left > 0) {
            var j = i + step
            while (j in rows.indices && !rows[j].selectable) j += step
            if (j !in rows.indices) break
            i = j
            left--
        }
        selected = i
    }

    fun toEnd(rows: List<Row>, end: Boolean) {
        selected = if (end) rows.indexOfLast { it.selectable }.coerceAtLeast(0) else rows.indexOfFirst { it.selectable }.coerceAtLeast(0)
    }

    /** Keeps the chosen line on screen, with a line of context above and below where there is room. */
    fun follow(visible: Int) {
        if (visible <= 0) return
        if (selected < offset + 1) offset = (selected - 1).coerceAtLeast(0)
        if (selected > offset + visible - 2) offset = (selected - visible + 2).coerceAtLeast(0)
    }
}

/** The two letters a row shows for where a track came from. */
fun ProviderType.badge(): String = when (this) {
    ProviderType.YOUTUBE_MUSIC -> "YT"
    ProviderType.YOUTUBE_VIDEO -> "YV"
    ProviderType.SOUNDCLOUD -> "SC"
    ProviderType.SPOTIFY -> "SP"
    ProviderType.BANDCAMP -> "BC"
    ProviderType.VK -> "VK"
    ProviderType.LOCAL -> "··"
}

/**
 * Whether a like given to a track from here goes to the listener's account: YouTube's, SoundCloud's,
 * Spotify's Liked Songs and VK's My music do. Bandcamp has none, and a file on this computer is nobody's.
 */
val ProviderType.keepsLikes: Boolean
    get() = this != ProviderType.BANDCAMP && this != ProviderType.LOCAL

/**
 * What a playlist a service listed really is: a whole artist, an album, or a list.
 *
 * Bandcamp and Spotify answer a search with their albums and artists as playlists -- which is what lets one
 * open like any other list -- and say which by the start of the id.
 */
enum class ListKind { ARTIST, ALBUM, PLAYLIST }

val Playlist.kind: ListKind
    get() = when {
        BandcampMusicProvider.isArtist(this) -> ListKind.ARTIST
        // Core's own names for these, SpotifyClient.ARTIST_PREFIX and ALBUM_PREFIX, are not visible from here.
        provider == ProviderType.SPOTIFY && id.startsWith("artist:") -> ListKind.ARTIST
        provider == ProviderType.BANDCAMP && (id.startsWith("${BandcampMusicProvider.ALBUM}:") || id.startsWith("${BandcampMusicProvider.TRACK}:")) -> ListKind.ALBUM
        provider == ProviderType.SPOTIFY && id.startsWith("album:") -> ListKind.ALBUM
        else -> ListKind.PLAYLIST
    }

fun Palette.badgeColour(provider: ProviderType): Rgb = when (provider) {
    ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> youTube
    ProviderType.SOUNDCLOUD -> soundCloud
    ProviderType.SPOTIFY -> spotify
    ProviderType.BANDCAMP -> bandcamp
    ProviderType.VK -> vk
    ProviderType.LOCAL -> subtext
}

fun formatTime(ms: Long?): String {
    if (ms == null || ms <= 0) return "–:––"
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Draws a list of rows into a box and keeps hit-testing information for the mouse. */
class ListView(private val palette: () -> Palette, private val state: AppState) {
    /** Which row each screen line showed last frame, for clicks. */
    val lines = mutableMapOf<Int, Int>()
    var top = 0
    var left = 0
    var width = 0
    var height = 0

    fun draw(canvas: Canvas, rows: List<Row>, list: ListState, x: Int, y: Int, w: Int, h: Int, focused: Boolean = true) {
        val p = palette()
        top = y; left = x; width = w; height = h
        lines.clear()
        list.clamp(rows)
        list.follow(h)
        list.offset = list.offset.coerceIn(0, (rows.size - h).coerceAtLeast(0))
        val playingKey = state.playback.value.track?.queueKey
        val queueIndex = state.queue.state.value.currentIndex
        val likes = state.likes.value
        for (line in 0 until h) {
            val index = list.offset + line
            val row = rows.getOrNull(index) ?: break
            lines[y + line] = index
            val selected = index == list.selected && row.selectable
            val background = if (selected) (if (focused) p.selection else mix(p.panel.takeIf { it != DEFAULT } ?: 0, p.selection, .5f)) else null
            if (background != null) canvas.fill(x, y + line, w, 1, background)
            val ry = y + line
            when (row) {
                is Row.Header -> {
                    canvas.write(x + 1, ry, row.title.uppercase(), p.accent, background, BOLD, max = w - 2)
                    row.note?.let { canvas.writeRight(x + w - 1, ry, it, p.faint, background) }
                }
                is Row.Note -> canvas.write(x + 2, ry, row.text, toneColour(p, row.tone), background, if (row.tone == Row.Tone.QUIET) ITALIC else 0, max = w - 3)
                Row.Gap -> Unit
                is Row.Song -> {
                    val playing = row.track.queueKey == playingKey &&
                        (row.queueIndex == null || row.queueIndex == queueIndex)
                    song(canvas, p, row, playing, likes.isLiked(row.track), background, x, ry, w)
                }
                is Row.PlaylistItem -> {
                    val pl = row.playlist
                    canvas.write(x + 1, ry, if (selected) "›" else " ", p.accent, background)
                    canvas.write(x + 3, ry, pl.provider.badge(), p.badgeColour(pl.provider), background, BOLD)
                    val name = canvas.write(x + 6, ry, pl.title, p.text, background, BOLD, max = (w * 6 / 10).coerceAtLeast(10))
                    val artist = pl.kind == ListKind.ARTIST
                    val detail = listOfNotNull(
                        // An artist opens as a playlist of what they put out, and says what it is. What Bandcamp
                        // gives as its owner is then where they are; Spotify's says "Artist" itself.
                        "Artist".takeIf { artist },
                        pl.ownerName?.takeIf { it.isNotBlank() && !(artist && it == "Artist") },
                        (pl.trackCount ?: pl.tracks.size.takeIf { it > 0 })?.let { "$it tracks" },
                        when (pl.isPublic) { true -> "public"; false -> "private"; null -> null },
                    ).joinToString(" · ")
                    canvas.write(x + 7 + name, ry, detail, p.subtext, background, max = (w - 8 - name).coerceAtLeast(0))
                }
                is Row.LocalItem -> {
                    val pl = row.playlist
                    canvas.write(x + 1, ry, if (selected) "›" else " ", p.accent, background)
                    canvas.write(x + 3, ry, "♪ ", p.accent, background)
                    val name = canvas.write(x + 6, ry, pl.title, p.text, background, BOLD, max = (w * 6 / 10).coerceAtLeast(10))
                    canvas.write(x + 7 + name, ry, "On this computer · ${pl.tracks.size} tracks", p.subtext, background, max = (w - 8 - name).coerceAtLeast(0))
                }
                is Row.Action -> {
                    canvas.write(x + 1, ry, if (selected) "›" else " ", p.accent, background)
                    val labelWidth = canvas.write(x + 3, ry, row.label, toneColour(p, row.tone), background, if (row.tone == Row.Tone.ACCENT) BOLD else 0, max = w / 2)
                    val valueX = maxOf(x + 3 + labelWidth + 2, x + w / 2)
                    row.value?.let { value ->
                        val shown = if (row.step != null && selected) "‹ $value ›" else value
                        canvas.write(valueX, ry, shown, if (selected) p.accent else p.subtext, background, BOLD, max = x + w - valueX - 1)
                    }
                    if (row.value == null && row.hint != null) {
                        canvas.write(valueX, ry, row.hint, p.faint, background, max = x + w - valueX - 1)
                    }
                }
            }
        }
        // A scroll mark, so a long list says it goes on.
        if (rows.size > h && h > 2) {
            val thumb = (h * h / rows.size).coerceIn(1, h)
            val at = ((h - thumb) * list.offset / (rows.size - h).coerceAtLeast(1)).coerceIn(0, h - thumb)
            for (i in 0 until h) canvas.set(x + w - 1, y + i, if (i in at until at + thumb) "┃" else "│", if (i in at until at + thumb) p.accent else p.line)
        }
    }

    private fun song(canvas: Canvas, p: Palette, row: Row.Song, playing: Boolean, liked: Boolean, background: Rgb?, x: Int, y: Int, w: Int) {
        val track = row.track
        val titleColour = if (playing) p.accent else p.text
        canvas.write(x + 1, y, if (playing) "▶" else " ", p.accent, background, BOLD)
        val numberWidth = if (row.number != null) 4 else 0
        row.number?.let { canvas.write(x + 3, y, it.toString().padStart(3), p.faint, background) }
        val right = 16
        val textX = x + 3 + numberWidth + (if (numberWidth > 0) 1 else 0)
        val room = (w - (textX - x) - right).coerceAtLeast(8)
        val titleRoom = if (room > 40) room * 58 / 100 else room
        val written = canvas.write(textX, y, track.title, titleColour, background, if (playing) BOLD else 0, max = titleRoom)
        if (room > 40) {
            canvas.write(textX + titleRoom + 2, y, track.artistLine, p.subtext, background, max = room - titleRoom - 2)
        } else if (written < room - 4) {
            canvas.write(textX + written + 1, y, "· " + track.artistLine, p.subtext, background, max = room - written - 1)
        }
        val rx = x + w - right
        canvas.write(rx, y, track.provider.badge(), p.badgeColour(track.provider), background, BOLD)
        canvas.writeRight(rx + 12, y, formatTime(track.durationMs), p.faint, background)
        if (liked) canvas.write(rx + 13, y, "♥", p.accent, background)
    }

    /** The row index at a screen line, for a click. */
    fun rowAt(y: Int): Int? = lines[y]
}

fun toneColour(p: Palette, tone: Row.Tone): Rgb = when (tone) {
    Row.Tone.NORMAL -> p.text
    Row.Tone.QUIET -> p.subtext
    Row.Tone.GOOD -> p.good
    Row.Tone.WARN -> p.warn
    Row.Tone.BAD -> p.bad
    Row.Tone.ACCENT -> p.accent
}
