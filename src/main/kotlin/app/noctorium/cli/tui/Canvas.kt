package app.noctorium.cli.tui

import org.jline.utils.WCWidth

/** A colour as 0xRRGGBB, or [DEFAULT] for whatever the terminal's own is. */
typealias Rgb = Int

const val DEFAULT: Rgb = -1

const val BOLD = 1
const val DIM = 2
const val ITALIC = 4
const val UNDERLINE = 8

/**
 * The screen as a grid of cells, drawn into and then sent to the terminal by [Screen].
 *
 * A cell holds one character -- or, for the second half of a wide one such as 血 or ア, nothing at all,
 * because the character before it already covers it. Everything that writes text measures it the way the
 * terminal will, so a Japanese title is cut where it actually ends rather than spilling over the column
 * beside it.
 */
class Canvas(val width: Int, val height: Int) {
    val text = arrayOfNulls<String>(width * height)
    val fg = IntArray(width * height) { DEFAULT }
    val bg = IntArray(width * height) { DEFAULT }
    val style = IntArray(width * height)

    /** A region writes are confined to, so a long line in one panel never lands in the next. */
    private var clipLeft = 0
    private var clipTop = 0
    private var clipRight = width
    private var clipBottom = height

    fun clear(background: Rgb, foreground: Rgb) {
        text.fill(" ")
        fg.fill(foreground)
        bg.fill(background)
        style.fill(0)
    }

    inline fun <T> clipped(x: Int, y: Int, w: Int, h: Int, block: () -> T): T {
        val saved = saveClip()
        clip(x, y, w, h)
        try {
            return block()
        } finally {
            restoreClip(saved)
        }
    }

    fun saveClip() = intArrayOf(clipLeft, clipTop, clipRight, clipBottom)

    fun restoreClip(saved: IntArray) {
        clipLeft = saved[0]; clipTop = saved[1]; clipRight = saved[2]; clipBottom = saved[3]
    }

    fun clip(x: Int, y: Int, w: Int, h: Int) {
        clipLeft = maxOf(clipLeft, x)
        clipTop = maxOf(clipTop, y)
        clipRight = minOf(clipRight, x + w)
        clipBottom = minOf(clipBottom, y + h)
    }

    private fun visible(x: Int, y: Int) = x >= clipLeft && x < clipRight && y >= clipTop && y < clipBottom

    fun set(x: Int, y: Int, char: String, foreground: Rgb = DEFAULT, background: Rgb? = null, attributes: Int = 0) {
        if (!visible(x, y)) return
        val i = y * width + x
        text[i] = char
        if (foreground != DEFAULT) fg[i] = foreground
        if (background != null) bg[i] = background
        style[i] = attributes
    }

    fun fill(x: Int, y: Int, w: Int, h: Int, background: Rgb, char: String = " ", foreground: Rgb = DEFAULT) {
        for (row in y until y + h) for (column in x until x + w) {
            if (!visible(column, row)) continue
            val i = row * width + column
            text[i] = char
            bg[i] = background
            if (foreground != DEFAULT) fg[i] = foreground
            style[i] = 0
        }
    }

    /** Paints a background behind whatever is already written, leaving the characters alone. */
    fun tint(x: Int, y: Int, w: Int, h: Int, background: Rgb) {
        for (row in y until y + h) for (column in x until x + w) {
            if (visible(column, row)) bg[row * width + column] = background
        }
    }

    /**
     * Writes [value] from [x], at most [max] columns of it, ending in an ellipsis when it had to be cut.
     * Returns how many columns were used.
     */
    fun write(
        x: Int,
        y: Int,
        value: String,
        foreground: Rgb = DEFAULT,
        background: Rgb? = null,
        attributes: Int = 0,
        max: Int = Int.MAX_VALUE,
    ): Int {
        val limit = minOf(max, width - x).coerceAtLeast(0)
        if (limit == 0) return 0
        val total = displayWidth(value)
        val cut = total > limit
        val room = if (cut) limit - 1 else limit
        var column = x
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val chars = Character.charCount(cp)
            i += chars
            if (cp < 32) continue
            val w = charWidth(cp)
            if (w == 0) continue
            if (column - x + w > room) break
            set(column, y, String(Character.toChars(cp)), foreground, background, attributes)
            if (w == 2) set(column + 1, y, "", foreground, background, attributes)
            column += w
        }
        if (cut) {
            set(column, y, "…", foreground, background, attributes)
            column++
        }
        return column - x
    }

    /** Writes [value] right-aligned so it ends just before [right]. */
    fun writeRight(right: Int, y: Int, value: String, foreground: Rgb = DEFAULT, background: Rgb? = null, attributes: Int = 0): Int {
        val w = displayWidth(value)
        return write(right - w, y, value, foreground, background, attributes)
    }

    /** Writes [value] centred in the [w] columns from [x]. */
    fun writeCentred(x: Int, w: Int, y: Int, value: String, foreground: Rgb = DEFAULT, background: Rgb? = null, attributes: Int = 0) {
        val length = minOf(displayWidth(value), w)
        write(x + (w - length) / 2, y, value, foreground, background, attributes, max = w)
    }

    /** A rounded box drawn in [line], optionally with a title set into its top edge. */
    fun box(x: Int, y: Int, w: Int, h: Int, line: Rgb, background: Rgb? = null, title: String? = null, titleColour: Rgb = line) {
        if (w < 2 || h < 2) return
        if (background != null) fill(x, y, w, h, background)
        set(x, y, "╭", line, background); set(x + w - 1, y, "╮", line, background)
        set(x, y + h - 1, "╰", line, background); set(x + w - 1, y + h - 1, "╯", line, background)
        for (c in x + 1 until x + w - 1) { set(c, y, "─", line, background); set(c, y + h - 1, "─", line, background) }
        for (r in y + 1 until y + h - 1) { set(x, r, "│", line, background); set(x + w - 1, r, "│", line, background) }
        if (title != null && w > 6) write(x + 2, y, " $title ", titleColour, background, BOLD, max = w - 4)
    }

    companion object {
        fun charWidth(cp: Int): Int = when {
            cp < 0x1100 && cp != 0x00AD -> if (cp < 32) 0 else 1
            else -> WCWidth.wcwidth(cp).coerceIn(0, 2)
        }

        fun displayWidth(value: String): Int {
            var total = 0
            var i = 0
            while (i < value.length) {
                val cp = value.codePointAt(i)
                i += Character.charCount(cp)
                total += charWidth(cp)
            }
            return total
        }
    }
}

/** Mixes [a] toward [b] by [amount], 0 being all [a]. */
fun mix(a: Rgb, b: Rgb, amount: Float): Rgb {
    if (a == DEFAULT || b == DEFAULT) return if (amount < .5f) a else b
    fun channel(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return (x + (y - x) * amount).toInt().coerceIn(0, 255)
    }
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

fun luminance(c: Rgb): Float {
    if (c == DEFAULT) return 0f
    val r = (c shr 16 and 0xFF) / 255f
    val g = (c shr 8 and 0xFF) / 255f
    val b = (c and 0xFF) / 255f
    return .2126f * r + .7152f * g + .0722f * b
}
