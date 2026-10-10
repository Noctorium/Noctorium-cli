package app.noctorium.cli.tui

import app.noctorium.playback.AudioAnalysis
import app.noctorium.playback.AudioAnalysisKind
import app.noctorium.settings.ThemeSkin
import app.noctorium.settings.VisualiserStyle

/**
 * How the visualiser's bars and meters stand from one drawn frame to the next.
 *
 * The player measures about thirty times a second, and mpv changes its numbers about twenty times, in bursts. Drawn
 * as they come they would flicker; so a bar goes up at once to what was measured, and falls from there a little at a
 * time, the way a meter's needle and a spectrum's bars do -- never below what the sound is doing, so it is still the
 * sound being drawn. A peak mark is held a moment at the loudest and then falls more slowly.
 */
class Meter {
    private var bands = FloatArray(0)
    private var levels = FloatArray(0)
    private var peaks = FloatArray(0)
    private var peakHeld = LongArray(0)
    private var last = 0L

    /** Whether anything is still falling, so another frame is wanted though nothing new was measured. */
    var moving = false
        private set

    fun reset() {
        bands = FloatArray(0); levels = FloatArray(0); peaks = FloatArray(0); peakHeld = LongArray(0)
        moving = false
        last = 0L
    }

    /** What to draw now, from [frame] -- or from nothing, when nothing was measured -- [now] being the time. */
    fun step(frame: AudioAnalysis?, now: Long = System.currentTimeMillis()): Shown {
        val seconds = if (last == 0L) 0f else ((now - last) / 1000f).coerceIn(0f, .25f)
        last = now
        var falling = false
        fun follow(shown: FloatArray, measured: List<Float>, rate: Float): FloatArray {
            val out = FloatArray(maxOf(shown.size, measured.size))
            for (i in out.indices) {
                val target = measured.getOrNull(i) ?: 0f
                val fallen = (shown.getOrNull(i) ?: 0f) - rate * seconds
                out[i] = maxOf(target, fallen, 0f)
                if (out[i] > target + .005f) falling = true
            }
            return out
        }
        bands = follow(bands, frame?.bands.orEmpty(), BAR_FALL)
        levels = follow(levels, frame?.rms.orEmpty(), BAR_FALL)
        val measuredPeaks = frame?.peaks.orEmpty()
        if (peakHeld.size < measuredPeaks.size) peakHeld = peakHeld.copyOf(measuredPeaks.size)
        if (peaks.size < measuredPeaks.size) peaks = peaks.copyOf(measuredPeaks.size)
        for (i in peaks.indices) {
            val measured = measuredPeaks.getOrNull(i) ?: 0f
            when {
                measured >= peaks[i] -> { peaks[i] = measured; peakHeld[i] = now }
                now - peakHeld[i] > PEAK_HOLD_MS -> peaks[i] = maxOf(measured, peaks[i] - PEAK_FALL * seconds)
            }
            if (peaks[i] > measured + .005f) falling = true
        }
        moving = falling
        return Shown(bands.toList(), levels.toList(), peaks.toList())
    }

    /** The heights to draw, each from 0 to 1. */
    data class Shown(val bands: List<Float>, val levels: List<Float>, val peaks: List<Float>)

    private companion object {
        /** A bar falls its whole height in about two thirds of a second. */
        const val BAR_FALL = 1.5f
        const val PEAK_FALL = .6f
        const val PEAK_HOLD_MS = 700L
    }
}

/**
 * The visualiser, drawn from what the player measured: a bar for each of the ten bands, or a level meter for each
 * channel -- and nothing where nothing was measured, never a pattern standing in for the sound. Bars become a meter
 * where the player can see only levels, as core's [VisualiserStyle.shownWith] says.
 */
object Visualisers {
    /** The eighths of a cell, filled from its foot and from its left. */
    private const val RISING = "▁▂▃▄▅▆▇█"
    private const val FILLING = "▏▎▍▌▋▊▉█"

    /**
     * Draws the [style] chosen into [w] by [h] cells at [x], [y], on [bg], from the [tui]'s latest frame. Returns
     * whether it drew anything; nothing is drawn where the player measures nothing, or the style is Off.
     */
    fun draw(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, bg: Rgb?, style: VisualiserStyle): Boolean {
        if (w < 6 || h < 1 || style == VisualiserStyle.OFF) return false
        tui.visualiserShown = true
        val frame = tui.analysis
        val available = frame?.kind?.takeIf { it != AudioAnalysisKind.NONE } ?: tui.state.audioAnalysisKind
        val shown = style.shownWith(available)
        val heights = tui.meter.step(frame)
        val p = tui.palette
        return when (shown) {
            VisualiserStyle.BARS -> { bars(canvas, p, x, y, w, h, bg, heights.bands); true }
            VisualiserStyle.LEVEL_METER -> { levels(canvas, p, x, y, w, h, bg, heights.levels, heights.peaks); true }
            VisualiserStyle.OFF -> false
        }
    }

    /** The colour a bar is drawn in at [fraction] of the way up: the accent, lighter towards the top. */
    private fun barColour(p: Palette, fraction: Float): Rgb = when {
        p.skin == ThemeSkin.WINDOWS_XP -> mix(Xp.PROGRESS, Xp.PROGRESS_LIGHT, fraction)
        p.skin == ThemeSkin.WINDOWS_98 -> mix(p.w98.selection, p.w98.titleEnd, fraction * .8f)
        p.light -> mix(p.accent, 0, fraction * .25f)
        else -> mix(p.accent, 0xFFFFFF, fraction * .35f)
    }

    /**
     * Ten bars from the bass up, each as wide as the room allows with a cell between, standing in eighths of a
     * cell. A faint foot under each says where the bars are while the sound is quiet.
     */
    fun bars(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, h: Int, bg: Rgb?, bands: List<Float>) {
        val count = app.noctorium.playback.Visualiser.BAND_COUNT
        // A cell between bars wherever there is room for one, even at a cell a bar: ten bars run together are a hill.
        val gap = if (w >= count * 2 - 1) 1 else 0
        val barW = ((w - gap * (count - 1)) / count).coerceAtLeast(1)
        val used = barW * count + gap * (count - 1)
        val left = x + (w - used) / 2
        val foot = if (p.skinned) mix(barColour(p, 0f), bg ?: p.page, .7f) else p.faint
        for (band in 0 until count) {
            val bx = left + band * (barW + gap)
            val eighths = ((bands.getOrNull(band) ?: 0f).coerceIn(0f, 1f) * h * 8).toInt()
            for (row in 0 until h) {
                val cy = y + h - 1 - row
                val filled = (eighths - row * 8).coerceIn(0, 8)
                val glyph = if (filled == 0) (if (row == 0) "▁" else " ") else RISING[filled - 1].toString()
                val colour = if (filled == 0) foot else barColour(p, row / (h - 1f).coerceAtLeast(1f))
                for (c in 0 until barW) canvas.set(bx + c, cy, glyph, colour, bg)
            }
        }
    }

    /**
     * A meter for each channel, left above right, filling from the left in eighths of a cell: in the accent, then
     * the warning colour past -6 dB and the bad one past -2 dB, as a mixing desk's go amber and red. The peak is a
     * mark held at the loudest a moment.
     */
    fun levels(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, h: Int, bg: Rgb?, rms: List<Float>, peaks: List<Float>) {
        val channels = maxOf(rms.size, peaks.size).coerceIn(1, 2)
        val names = if (channels == 1) listOf("M") else listOf("L", "R")
        val spacing = if (h >= channels * 2) 2 else 1
        val top = y + ((h - (channels - 1) * spacing - 1) / 2).coerceAtLeast(0)
        val label = if (p.skinned) Skin.text(p) else p.subtext
        val track = if (p.skinned) mix(barColour(p, 0f), bg ?: Skin.window(p), .8f) else p.line
        val meterW = w - 2
        for (channel in 0 until channels) {
            val cy = top + channel * spacing
            if (cy >= y + h) break
            canvas.write(x, cy, names[channel], label, bg, BOLD)
            val eighths = ((rms.getOrNull(channel) ?: 0f).coerceIn(0f, 1f) * meterW * 8).toInt()
            for (i in 0 until meterW) {
                val filled = (eighths - i * 8).coerceIn(0, 8)
                val at = (i + 1f) / meterW
                val colour = when {
                    at > LOUD -> p.bad
                    at > WARM -> p.warn
                    p.skinned -> barColour(p, .3f)
                    else -> p.accent
                }
                if (filled == 0) canvas.set(x + 2 + i, cy, "·", track, bg)
                else canvas.set(x + 2 + i, cy, FILLING[filled - 1].toString(), colour, bg)
            }
            val peak = peaks.getOrNull(channel) ?: 0f
            if (peak > 0f) {
                val at = (peak * meterW).toInt().coerceIn(0, meterW - 1)
                canvas.set(x + 2 + at, cy, "▕", if (peak > (LOUD + 1) / 2) p.bad else if (p.skinned) Skin.text(p) else p.text, bg, BOLD)
            }
        }
    }

    /** Where the meter turns amber and red: -6 and -2 dB on the visualiser's 60 dB scale. */
    private const val WARM = .9f
    private const val LOUD = 1 - 2 / 60f
}
