package app.noctorium.cli.tui

import app.noctorium.cli.CliParts
import app.noctorium.cli.cliAppState
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.ThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.test.Test

/**
 * Draws the terminal player's pages to HTML, off any terminal, for looking at in a browser. Off unless a
 * folder is named:
 *
 *     ./gradlew test --tests "*TuiRenderCheck*" -Dnoctorium.renderTui=build/tui
 *
 * It searches for real and finds real lyrics, so it needs the network; the player is a stand-in that only
 * pretends to play, so nothing makes a sound.
 */
class TuiRenderCheck {

    /** A player that plays nothing and says it is a minute and a half in. */
    private class StandIn : PlaybackEngine {
        private val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable
        override suspend fun play(track: Track) = mutable.update {
            PlaybackState(PlaybackStatus.PLAYING, track, positionMs = 94_000, durationMs = track.durationMs ?: 240_000, volume = .72f)
        }
        override suspend fun pause() = mutable.update { it.copy(status = PlaybackStatus.PAUSED) }
        override suspend fun resume() = mutable.update { it.copy(status = PlaybackStatus.PLAYING) }
        override suspend fun setVolume(value: Float) = mutable.update { it.copy(volume = value) }
        override suspend fun setVolumeBoost(enabled: Boolean) = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun seekTo(positionMs: Long) = mutable.update { it.copy(positionMs = positionMs) }
        override suspend fun stop() = mutable.update { PlaybackState() }
        override fun close() = Unit
    }

    @Test
    fun `the pages draw`() {
        val folder = System.getProperty("noctorium.renderTui")?.let(::File) ?: return
        folder.mkdirs()
        val parts = CliParts()
        val state = cliAppState(parts, StandIn())
        val tui = Tui(state, parts, web = null)
        try {
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.setProgressBarStyle(ProgressBarStyle.MINIMAL)
            state.search("Amazing Grace Judy Collins")
            waitFor(30_000) { !state.ui.value.searchLoading && state.ui.value.searchResults.tracks.isNotEmpty() }
            tui.searchText = "Amazing Grace Judy Collins"
            tui.page = Page.SEARCH
            write(folder, "search", tui)
            val tracks = state.ui.value.searchResults.tracks
            val chosen = tracks.firstOrNull { "judy" in it.artistLine.lowercase() } ?: tracks.first()
            state.play(chosen, PlaybackOrigin.SEARCH, tracks)
            waitFor(10_000) { state.playback.value.track != null }
            tui.page = Page.NOW_PLAYING
            tui.frame(150, 42)
            state.loadLyrics(chosen)
            waitFor(30_000) { !state.lyrics.value.loading && state.lyrics.value.selectedProvider != null }
            waitFor(10_000) { tui.art.get(chosen.artworkUrl) != null }
            write(folder, "now-playing", tui)
            tui.page = Page.QUEUE
            write(folder, "queue", tui)
            tui.page = Page.SETTINGS
            write(folder, "settings", tui)
            tui.overlays.addLast(Overlay.Help)
            tui.page = Page.HOME
            write(folder, "help", tui)
            tui.overlays.clear()
            state.setTheme(ThemePreset.CRIMSON_SCARLET)
            state.setProgressBarStyle(ProgressBarStyle.WAVE)
            tui.page = Page.NOW_PLAYING
            write(folder, "now-playing-scarlet", tui)
            state.setTheme(ThemePreset.WINDOWS_98)
            state.setProgressBarStyle(ProgressBarStyle.CLASSIC)
            tui.page = Page.SEARCH
            write(folder, "search-98", tui, 100, 32)
        } finally {
            state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
            state.setProgressBarStyle(ProgressBarStyle.MINIMAL)
            state.close()
        }
    }

    private fun waitFor(ms: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(150)
    }

    private fun write(folder: File, name: String, tui: Tui, w: Int = 150, h: Int = 42) {
        tui.frame(w, h)
        Thread.sleep(300)
        val canvas = tui.frame(w, h)
        File(folder, "$name.html").writeText(html(canvas))
        File(folder, "$name.txt").writeText(text(canvas))
        javax.imageio.ImageIO.write(png(canvas), "png", File(folder, "$name.png"))
    }

    /**
     * The frame as a picture, the way a terminal draws it: a cell is a box of the background with the
     * character over it, and the half blocks are drawn as the two halves they are rather than left to a font.
     */
    private fun png(canvas: Canvas): java.awt.image.BufferedImage {
        val cw = 9
        val ch = 18
        val image = java.awt.image.BufferedImage(canvas.width * cw, canvas.height * ch, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val plain = java.awt.Font("Cascadia Mono", java.awt.Font.PLAIN, 14).takeIf { it.family != java.awt.Font.DIALOG } ?: java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 14)
        val bold = plain.deriveFont(java.awt.Font.BOLD)
        val fallback = java.awt.Font("Segoe UI Symbol", java.awt.Font.PLAIN, 14)
        val cjk = java.awt.Font("MS Gothic", java.awt.Font.PLAIN, 14)
        fun colour(c: Rgb, d: Int) = java.awt.Color(if (c == DEFAULT) d else c)
        for (y in 0 until canvas.height) for (x in 0 until canvas.width) {
            val i = y * canvas.width + x
            val t = canvas.text[i] ?: " "
            if (t.isEmpty()) continue
            val wide = x + 1 < canvas.width && canvas.text[i + 1] == ""
            val w = if (wide) cw * 2 else cw
            val fg = colour(canvas.fg[i], 0xDDDDDD)
            val bg = colour(canvas.bg[i], 0x111111)
            g.color = bg
            g.fillRect(x * cw, y * ch, w, ch)
            when (t) {
                "▀" -> { g.color = fg; g.fillRect(x * cw, y * ch, w, ch / 2) }
                "▄" -> { g.color = fg; g.fillRect(x * cw, y * ch + ch / 2, w, ch - ch / 2) }
                "█" -> { g.color = fg; g.fillRect(x * cw, y * ch, w, ch) }
                " " -> Unit
                else -> {
                    var font = if (canvas.style[i] and BOLD != 0) bold else plain
                    if (font.canDisplayUpTo(t) != -1) font = if (cjk.canDisplayUpTo(t) == -1) cjk else fallback
                    g.font = font
                    g.color = fg
                    g.drawString(t, x * cw, y * ch + 14)
                }
            }
        }
        g.dispose()
        return image
    }

    private fun text(canvas: Canvas): String = buildString {
        for (y in 0 until canvas.height) {
            for (x in 0 until canvas.width) append(canvas.text[y * canvas.width + x] ?: " ")
            append('\n')
        }
    }

    private fun css(c: Rgb, fallback: String) = if (c == DEFAULT) fallback else "#%06x".format(c)

    private fun html(canvas: Canvas): String = buildString {
        append("<!doctype html><meta charset=utf-8><style>body{margin:0;background:#111}pre{margin:0;font:15px/1 'Cascadia Mono',Consolas,monospace;letter-spacing:0}span{display:inline-block;height:1em;vertical-align:top}</style><pre>")
        for (y in 0 until canvas.height) {
            for (x in 0 until canvas.width) {
                val i = y * canvas.width + x
                val t = canvas.text[i] ?: " "
                if (t.isEmpty()) continue
                val wide = x + 1 < canvas.width && canvas.text[i + 1] == ""
                val style = canvas.style[i]
                append("<span style=\"color:${css(canvas.fg[i], "#ddd")};background:${css(canvas.bg[i], "#111")};width:${if (wide) 2 else 1}ch")
                if (style and BOLD != 0) append(";font-weight:700")
                if (style and ITALIC != 0) append(";font-style:italic")
                if (style and UNDERLINE != 0) append(";text-decoration:underline")
                append("\">")
                append(t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                append("</span>")
            }
            append('\n')
        }
        append("</pre>")
    }
}
