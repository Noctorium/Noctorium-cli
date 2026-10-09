package app.noctorium.cli.tui

import app.noctorium.settings.ProgressBarStyle
import app.noctorium.settings.SeekBar
import app.noctorium.settings.ThemeSkin
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The seek bar, in whichever of the eleven styles is chosen -- one setting across every Noctorium -- in the
 * nearest a row of cells gets: a hairline with a dot, the wave travelling while the music does, segments, a
 * capsule, Windows' blocks, a song's own row of bars, beads, a neon line, a ruler, and XP's green blocks.
 *
 * One row each, so it fits wherever a seek bar goes. Where the row above or below is free the ruler hangs its
 * pointer there and the Windows handles stand taller than their track, as theirs did. The Windows themes draw
 * the plain slider and the classic bar as their own trackbar and progress bar; every other theme draws each
 * style the same.
 */
object SeekBars {
    /** The eight heights a cell can be filled to from its foot. */
    private const val LEVELS = "▁▂▃▄▅▆▇█"

    /**
     * Draws [style] in the [w] cells from [x] on row [y], [fraction] of the way through a song [durationMs] long
     * whose queue key is [seed]. [moving] is whether it is playing, for the wave; [roomAbove] and [roomBelow]
     * whether the rows either side are free to draw into.
     */
    fun draw(
        canvas: Canvas,
        p: Palette,
        style: ProgressBarStyle,
        x: Int,
        y: Int,
        w: Int,
        fraction: Float,
        moving: Boolean,
        bg: Rgb,
        seed: String = "",
        durationMs: Long = 0,
        roomAbove: Boolean = false,
        roomBelow: Boolean = false,
    ) {
        if (w <= 0) return
        val head = (fraction * (w - 1)).toInt().coerceIn(0, w - 1)
        val unplayed = p.line
        when {
            p.skin == ThemeSkin.WINDOWS_98 && style == ProgressBarStyle.MATERIAL -> trackbar98(canvas, p.w98, x, y, w, head, bg, roomAbove, roomBelow)
            p.skin == ThemeSkin.WINDOWS_98 && style == ProgressBarStyle.CLASSIC -> progress98(canvas, p.w98, x, y, w, head, bg, roomAbove, roomBelow)
            p.skin == ThemeSkin.WINDOWS_XP && style == ProgressBarStyle.MATERIAL -> trackbarXp(canvas, x, y, w, head, bg, roomAbove)
            else -> when (style) {
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
                ProgressBarStyle.BARS -> bars(canvas, p, x, y, w, head, bg, seed)
                ProgressBarStyle.BEADS -> beads(canvas, p, x, y, w, head, bg)
                ProgressBarStyle.NEON -> neon(canvas, p, x, y, w, head, bg)
                ProgressBarStyle.RULER -> ruler(canvas, p, x, y, w, head, bg, durationMs, roomAbove)
                ProgressBarStyle.LUNA -> luna(canvas, x, y, w, head, bg, roomAbove)
            }
        }
    }

    /** The colour the place the song has reached is picked out in: the accent, brighter. */
    private fun bright(p: Palette): Rgb = if (p.light) mix(p.accent, 0, .2f) else mix(p.accent, 0xFFFFFF, .45f)

    /**
     * The song's own row of bars, from core so it is the same row the desktop and the phone draw: a cell each,
     * standing from its foot, lit in the accent as far as the song has played.
     */
    private fun bars(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, head: Int, bg: Rgb, seed: String) {
        val heights = SeekBar.barHeights(seed, w)
        for (i in 0 until w) {
            val level = (heights[i] * LEVELS.length).roundToInt().coerceIn(1, LEVELS.length) - 1
            val colour = when {
                i == head -> bright(p)
                i < head -> p.accent
                else -> p.faint
            }
            canvas.set(x + i, y, LEVELS[level].toString(), colour, bg)
        }
    }

    /**
     * A bead in every other cell, filled in once played, and a ringed one in brighter colour where the song has
     * got to -- on a bead rather than in a gap, so it reads as one of the string.
     */
    private fun beads(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, head: Int, bg: Rgb) {
        val at = head - head % 2
        for (i in 0 until w) {
            when {
                i == at -> canvas.set(x + i, y, "◉", bright(p), bg, BOLD)
                i % 2 == 1 -> canvas.set(x + i, y, " ", p.faint, bg)
                i < at -> canvas.set(x + i, y, "●", p.accent, bg)
                else -> canvas.set(x + i, y, "·", p.faint, bg)
            }
        }
    }

    /**
     * A bright line with the glow round it as the cells' background, a spark at its head, and faint dashes for
     * what is to come. On the terminal's own background there is nothing to glow against, and the line is
     * enough.
     */
    private fun neon(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, head: Int, bg: Rgb) {
        val glow = if (bg == DEFAULT) bg else mix(bg, p.accent, if (p.light) .16f else .22f)
        val line = if (p.light) p.accent else mix(p.accent, 0xFFFFFF, .3f)
        for (i in 0 until w) {
            when {
                i < head -> canvas.set(x + i, y, "━", line, glow, BOLD)
                i == head -> canvas.set(x + i, y, "◆", bright(p), glow, BOLD)
                else -> canvas.set(x + i, y, "┄", p.faint, bg)
            }
        }
    }

    /**
     * A rule with a tick every few seconds and a longer one on each minute, from core so the spacing is the
     * desktop's. The song's place is a pointer in the row above when there is one, or else a heavier needle
     * through the rule, as an editor draws where it is playing.
     */
    private fun ruler(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, head: Int, bg: Rgb, durationMs: Long, roomAbove: Boolean) {
        val ticks = arrayOfNulls<Boolean>(w)
        if (w > 1) SeekBar.rulerTicks(durationMs, w / 2).forEach { tick ->
            val at = (tick.fraction * (w - 1)).roundToInt().coerceIn(0, w - 1)
            ticks[at] = ticks[at] == true || tick.major
        }
        for (i in 0 until w) {
            val glyph = when (ticks[i]) {
                true -> "┰"
                false -> "┬"
                null -> "─"
            }
            canvas.set(x + i, y, glyph, if (i <= head) p.accent else p.faint, bg, if (i <= head && ticks[i] == true) BOLD else 0)
        }
        if (roomAbove) canvas.set(x + head, y - 1, "▼", bright(p), null, BOLD)
        else canvas.set(x + head, y, "╂", bright(p), bg, BOLD)
    }

    /**
     * Windows XP's progress bar: green blocks with a gap after each, filling a white well, and its pointed handle
     * over them where there is room above. Its own colours in every theme, as the classic bar keeps its grey.
     */
    private fun luna(canvas: Canvas, x: Int, y: Int, w: Int, head: Int, bg: Rgb, roomAbove: Boolean) {
        if (w < 3) return
        val edge = Xp.TAB_EDGE
        canvas.set(x, y, Skin.RIGHT, edge, bg)
        canvas.set(x + w - 1, y, Skin.LEFT, edge, bg)
        for (i in 1 until w - 1) {
            canvas.set(x + i, y, if (i < head) "▊" else " ", if (i == head - 1) Xp.PROGRESS_LIGHT else Xp.PROGRESS, Xp.WINDOW)
        }
        val at = head.coerceIn(1, w - 2)
        if (roomAbove) canvas.set(x + at, y - 1, "▼", Xp.BUTTON_EDGE, null, BOLD)
        else canvas.set(x + at, y, "▮", Xp.BUTTON_EDGE, Xp.WINDOW, BOLD)
    }

    /**
     * Windows 98's trackbar: a groove, and a grey slab for a handle, lit along its left and shaded down its right,
     * standing above and below the groove where it can. The groove does not fill: the slab says where the song is.
     */
    private fun trackbar98(canvas: Canvas, c: W98, x: Int, y: Int, w: Int, head: Int, bg: Rgb, roomAbove: Boolean, roomBelow: Boolean) {
        for (i in 0 until w) canvas.set(x + i, y, "═", c.groove, bg)
        slab(canvas, c, x + head.coerceAtMost(w - 2).coerceAtLeast(0), y, minOf(2, w), roomAbove, roomBelow)
    }

    /**
     * Windows 98's progress bar: a sunken white well filling with navy blocks, a gap after each, and the
     * trackbar's slab riding on it as the handle.
     */
    private fun progress98(canvas: Canvas, c: W98, x: Int, y: Int, w: Int, head: Int, bg: Rgb, roomAbove: Boolean, roomBelow: Boolean) {
        if (w < 4) return trackbar98(canvas, c, x, y, w, head, bg, roomAbove, roomBelow)
        canvas.set(x, y, Skin.RIGHT, c.shadow, bg)
        canvas.set(x + w - 1, y, Skin.LEFT, c.highlight, bg)
        for (i in 1 until w - 1) canvas.set(x + i, y, if (i < head) "▊" else " ", c.selection, c.window)
        if (roomAbove) for (i in 1 until w - 1) canvas.set(x + i, y - 1, Skin.FOOT, c.shadow)
        if (roomBelow) for (i in 1 until w - 1) canvas.set(x + i, y + 1, Skin.TOP, c.highlight)
        slab(canvas, c, x + head.coerceIn(1, w - 3), y, 2, roomAbove = false, roomBelow = false)
    }

    /** The raised grey slab 98 made every handle of: two cells, white down its left and black down its right. */
    private fun slab(canvas: Canvas, c: W98, x: Int, y: Int, w: Int, roomAbove: Boolean, roomBelow: Boolean) {
        canvas.set(x, y, Skin.LEFT, c.highlight, c.face)
        if (w > 1) canvas.set(x + w - 1, y, Skin.RIGHT, c.darkShadow, c.face)
        if (roomAbove) for (i in 0 until w) canvas.set(x + i, y - 1, Skin.FOOT, c.highlight)
        if (roomBelow) for (i in 0 until w) canvas.set(x + i, y + 1, Skin.TOP, c.darkShadow)
    }

    /**
     * XP's trackbar: a thin sunken track that does not fill, and its handle over the song's place -- white, with
     * the green of its pointed foot, and standing taller than the track where the row above is free.
     */
    private fun trackbarXp(canvas: Canvas, x: Int, y: Int, w: Int, head: Int, bg: Rgb, roomAbove: Boolean) {
        for (i in 0 until w) canvas.set(x + i, y, "═", Xp.TAB_EDGE, bg)
        if (roomAbove) canvas.set(x + head, y - 1, "▄", Xp.WINDOW, null)
        canvas.set(x + head, y, "▼", Xp.PROGRESS, Xp.WINDOW, BOLD)
    }
}
