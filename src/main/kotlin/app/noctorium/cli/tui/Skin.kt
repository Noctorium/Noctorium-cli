package app.noctorium.cli.tui

import app.noctorium.settings.ThemeSkin
import app.noctorium.settings.Windows98Palette
import app.noctorium.settings.WindowsXpColours
import app.noctorium.settings.contrastRatio

/** One of core's system colours, as a cell takes it. */
private fun cell(argb: Long): Rgb = (argb and 0xFFFFFF).toInt()

/**
 * A scheme of Windows 98's system colours ([Windows98Palette]), for cells: 98's own grey, or Noctorium 98's night.
 * The skin draws every bevel, title bar and list from the one in force, [Palette.w98], as 98 drew them from the
 * scheme chosen in its Display Properties.
 */
internal class W98 private constructor(palette: Windows98Palette) {
    val face = cell(palette.face)
    val highlight = cell(palette.highlight)
    val light = cell(palette.light)
    val shadow = cell(palette.shadow)
    val darkShadow = cell(palette.darkShadow)
    val window = cell(palette.window)
    val text = cell(palette.text)
    val greyText = cell(palette.greyText)
    val selection = cell(palette.selection)
    val selectionText = cell(palette.selectionText)
    val title = cell(palette.title)
    val titleEnd = cell(palette.titleEnd)
    val titleText = cell(palette.titleText)
    val tooltip = cell(palette.tooltip)

    /** A dark face, whose light edges are darker than any writing: what has to be turned round on it. */
    val dark = palette.dark

    /**
     * What a groove cut into the face is drawn in, a trackbar's: the edge of it that shows. A sunken groove is shaded
     * along its top and lit along its foot, and a cell has room for one colour of the two -- the shadow on 98's
     * grey, where it stands out the more, and on a dark face, where the shadow all but vanishes, the light.
     */
    val groove = if (contrastRatio(palette.shadow, palette.face) >= contrastRatio(palette.highlight, palette.face)) shadow else highlight

    companion object {
        /** 98 as it shipped. */
        val STANDARD = W98(Windows98Palette.STANDARD)

        /** Noctorium 98. */
        val NOCTORIUM = W98(Windows98Palette.NOCTORIUM)

        /** The cells for [palette]: the two shipped ones are made once, as every frame asks for one. */
        fun of(palette: Windows98Palette): W98 = when (palette) {
            Windows98Palette.STANDARD -> STANDARD
            Windows98Palette.NOCTORIUM -> NOCTORIUM
            else -> W98(palette)
        }
    }
}

/** Luna's colours ([WindowsXpColours]), for cells. */
internal object Xp {
    val FACE = cell(WindowsXpColours.FACE)
    val WINDOW = cell(WindowsXpColours.WINDOW)
    val TEXT = cell(WindowsXpColours.TEXT)
    val GREY_TEXT = cell(WindowsXpColours.GREY_TEXT)
    val SELECTION = cell(WindowsXpColours.SELECTION)
    val SELECTION_TEXT = cell(WindowsXpColours.SELECTION_TEXT)
    val TITLE = cell(WindowsXpColours.TITLE)
    val TITLE_LOW = cell(WindowsXpColours.TITLE_LOW)
    val TITLE_TEXT = cell(WindowsXpColours.TITLE_TEXT)
    val FRAME = cell(WindowsXpColours.FRAME)
    val CLOSE = cell(WindowsXpColours.CLOSE)
    val BUTTON_EDGE = cell(WindowsXpColours.BUTTON_EDGE)
    val BUTTON_FOOT = cell(WindowsXpColours.BUTTON_FOOT)
    val FOCUS = cell(WindowsXpColours.FOCUS)
    val FIELD_EDGE = cell(WindowsXpColours.FIELD_EDGE)
    val TAB_EDGE = cell(WindowsXpColours.TAB_EDGE)
    val PROGRESS_LIGHT = cell(WindowsXpColours.PROGRESS_LIGHT)
    val PROGRESS = cell(WindowsXpColours.PROGRESS)
    val SCROLL_THUMB = cell(WindowsXpColours.SCROLL_THUMB)
    val SCROLL_EDGE = cell(WindowsXpColours.SCROLL_EDGE)
    val SCROLL_ARROW = cell(WindowsXpColours.SCROLL_ARROW)
    val TASKBAR_TOP = cell(WindowsXpColours.TASKBAR_TOP)
    val TASKBAR = cell(WindowsXpColours.TASKBAR)
    val TASKBAR_FOOT = cell(WindowsXpColours.TASKBAR_FOOT)
    val START = cell(WindowsXpColours.START)
    val START_LIGHT = cell(WindowsXpColours.START_LIGHT)
    val TRAY = cell(WindowsXpColours.TRAY)
    val TRAY_EDGE = cell(WindowsXpColours.TRAY_EDGE)
    val TASK_PANE_TOP = cell(WindowsXpColours.TASK_PANE_TOP)
    val TASK_PANE_FOOT = cell(WindowsXpColours.TASK_PANE_FOOT)
    val TASK_PANEL = cell(WindowsXpColours.TASK_PANEL)
    val TASK_PANEL_TITLE = cell(WindowsXpColours.TASK_PANEL_TITLE)
    val TOOLTIP = cell(WindowsXpColours.TOOLTIP)
}

/**
 * Windows 98 and XP, as a terminal can draw them: the rest of the two Windows themes, beyond their colours.
 *
 * Bevels are the whole of 98 and a good part of XP, and a cell is far too coarse for a one-pixel edge. The
 * eighth blocks are not: ▔ is a line along the top of a cell, ▁ along its foot, ▏ down its left side and ▕
 * down its right, each an eighth of the cell thick, and most terminals draw them themselves rather than from
 * a font, so they meet. A slab is lit from the top left -- white along those edges, black along the others --
 * and a well the other way round; a slab's edges are drawn in the cells round it, so the slab keeps every
 * cell of its own for what it holds. Every colour is core's, so the terminal's 98 is the desktop's grey and its
 * Noctorium 98 the desktop's night: 98's colours are its scheme's, [Palette.w98], never a grey of their own.
 */
object Skin {
    const val TOP = "▔"
    const val FOOT = "▁"
    const val LEFT = "▏"
    const val RIGHT = "▕"

    private fun xp(p: Palette) = p.skin == ThemeSkin.WINDOWS_XP

    /** The face every slab is made of: 98's grey or its night's violet, or Luna's beige. */
    fun face(p: Palette): Rgb = if (xp(p)) Xp.FACE else p.w98.face

    /** Where a list or a field sits: white in both, or black in Noctorium 98. */
    fun window(p: Palette): Rgb = if (xp(p)) Xp.WINDOW else p.w98.window

    fun text(p: Palette): Rgb = if (xp(p)) Xp.TEXT else p.w98.text

    /** Writing on something that cannot be used, and the quieter writing beside the rest. */
    fun greyText(p: Palette): Rgb = if (xp(p)) mix(Xp.GREY_TEXT, Xp.TEXT, .3f) else p.w98.greyText

    fun selection(p: Palette): Rgb = if (xp(p)) Xp.SELECTION else p.w98.selection

    fun selectionText(p: Palette): Rgb = if (xp(p)) Xp.SELECTION_TEXT else p.w98.selectionText

    /** The writing on a title bar, and the quieter writing after its title. */
    fun titleText(p: Palette): Rgb = if (xp(p)) Xp.TITLE_TEXT else p.w98.titleText

    /**
     * 98 wrote it in the light grey of its bevels, which in a dark scheme is a dark colour and would vanish into
     * the bar; there it is the title's own writing, dimmed towards the bar's lighter end as far as 98's grey was.
     */
    fun titleQuiet(p: Palette): Rgb = when {
        xp(p) -> mix(Xp.TITLE_TEXT, Xp.TITLE, .25f)
        p.w98.dark -> mix(p.w98.titleText, p.w98.titleEnd, .25f)
        else -> p.w98.light
    }

    /** The colours a title bar runs between, from its left end to its right. */
    private fun titleColours(p: Palette): Pair<Rgb, Rgb> = if (xp(p)) Xp.TITLE to Xp.TITLE_LOW else p.w98.title to p.w98.titleEnd

    /** A tooltip's face, which both drew with a thin edge in the writing's colour: pale yellow, or 98's night. */
    fun tooltip(p: Palette): Rgb = if (xp(p)) Xp.TOOLTIP else p.w98.tooltip

    /**
     * A line of [w] cells from [x], each a step of the way from [from] to [to]: the title bars of both, which
     * run from one colour to another across their length.
     */
    fun gradient(canvas: Canvas, x: Int, y: Int, w: Int, from: Rgb, to: Rgb) {
        for (i in 0 until w) canvas.fill(x + i, y, 1, 1, mix(from, to, if (w > 1) i / (w - 1f) else 0f))
    }

    /**
     * The edges of a slab [w] by [h] at [x], [y], drawn in the cells round it: [lit] along the top and down the
     * left, [shaded] along the foot and down the right. A raised slab is lit white and shaded black; a well is
     * shaded where a slab is lit. Each edge keeps the background of the cell it is drawn in.
     */
    fun edges(canvas: Canvas, x: Int, y: Int, w: Int, h: Int, lit: Rgb?, shaded: Rgb?, top: Boolean = true, foot: Boolean = true) {
        if (lit != null) {
            if (top) for (c in x until x + w) canvas.set(c, y - 1, FOOT, lit)
            for (r in y until y + h) canvas.set(x - 1, r, RIGHT, lit)
        }
        if (shaded != null) {
            if (foot) for (c in x until x + w) canvas.set(c, y + h, TOP, shaded)
            for (r in y until y + h) canvas.set(x + w, r, LEFT, shaded)
        }
    }

    /**
     * A title bar [w] cells across from [x] on row [y]: the theme's blue running along it, [title] in bold white,
     * and [subtitle] after it more quietly. With [close], a close button at its right end that runs it when
     * clicked -- there only, because only closing does something in a terminal; a window's other buttons would
     * be buttons that did nothing. Returns where the writing has to stop.
     */
    fun titleBar(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, title: String, subtitle: String? = null, close: (() -> Unit)? = null): Int {
        val p = tui.palette
        val (from, to) = titleColours(p)
        gradient(canvas, x, y, w, from, to)
        val end = if (close != null) x + w - 5 else x + w - 1
        val used = canvas.write(x + 1, y, title, titleText(p), null, BOLD, max = end - x - 1)
        if (subtitle != null && x + 1 + used + 4 < end) canvas.write(x + 3 + used, y, subtitle, titleQuiet(p), null, max = end - x - 3 - used)
        if (close != null) closeButton(tui, canvas, x + w - 4, y, close)
        return end
    }

    /**
     * The close button, three cells from [x]: a little grey slab with a black cross on 98's title bars, Luna's red
     * one with a white cross on XP's. Clicking it runs [close].
     */
    fun closeButton(tui: Tui, canvas: Canvas, x: Int, y: Int, close: () -> Unit) {
        val p = tui.palette
        if (xp(p)) {
            val rim = mix(Xp.CLOSE, 0xFFFFFF, .55f)
            canvas.set(x, y, LEFT, rim, Xp.CLOSE)
            canvas.set(x + 1, y, "×", Xp.TITLE_TEXT, Xp.CLOSE, BOLD)
            canvas.set(x + 2, y, RIGHT, rim, Xp.CLOSE)
        } else {
            canvas.set(x, y, LEFT, p.w98.highlight, p.w98.face)
            canvas.set(x + 1, y, "×", p.w98.text, p.w98.face, BOLD)
            canvas.set(x + 2, y, RIGHT, p.w98.darkShadow, p.w98.face)
        }
        tui.clickTargets += Tui.ClickTarget(x, y, 3, 1) { close() }
    }

    /**
     * A window [w] by [h] at [x], [y], its title bar along its first row and its face below. 98's is a raised
     * grey slab, its edges in the cells round it -- lit in the light grey 98 put outside a window's white, so the
     * edge still shows against a white list behind it; XP's is beige in a frame of Luna's blue half a cell thick,
     * its top corners rounded, as XP's were.
     */
    fun window(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int, title: String, close: (() -> Unit)?) {
        val p = tui.palette
        canvas.fill(x, y, w, h, face(p))
        if (xp(p)) {
            for (r in y + 1 until y + h) {
                canvas.set(x - 1, r, "▐", Xp.FRAME)
                canvas.set(x + w, r, "▌", Xp.FRAME)
            }
            for (c in x until x + w) canvas.set(c, y + h, "▀", Xp.FRAME)
            canvas.set(x - 1, y + h, "▝", Xp.FRAME)
            canvas.set(x + w, y + h, "▘", Xp.FRAME)
            canvas.set(x - 1, y, "▗", Xp.TITLE)
            canvas.set(x + w, y, "▖", Xp.TITLE_LOW)
        } else {
            edges(canvas, x, y, w, h, p.w98.light, p.w98.darkShadow)
        }
        titleBar(tui, canvas, x, y, w, title, close = close)
    }

    /**
     * A picture's frame round [w] by [h] at [x], [y]: sunk into the face as 98 set pictures in, or Luna's pale
     * blue edge. Drawn in the cells round it, which have to be free.
     */
    fun frame(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, h: Int) {
        if (xp(p)) edges(canvas, x, y, w, h, Xp.FIELD_EDGE, Xp.FIELD_EDGE)
        else edges(canvas, x, y, w, h, p.w98.shadow, p.w98.highlight)
    }

    /**
     * A white well [w] by [h] at [x], [y]: sunk into 98's face, dark along its top and left and lit along its foot
     * and right, or edged all round in Luna's pale blue. Its edges are in the cells round it; the top one and the
     * foot only where [top] and [foot] say those rows are free.
     */
    fun well(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, h: Int, top: Boolean = true, foot: Boolean = false) {
        canvas.fill(x, y, w, h, window(p))
        if (xp(p)) edges(canvas, x, y, w, h, Xp.FIELD_EDGE, Xp.FIELD_EDGE, top, foot)
        else edges(canvas, x, y, w, h, p.w98.shadow, p.w98.highlight, top, foot)
    }

    /**
     * A push button reading [label] at [x], [y], with the letter at [mnemonic] underlined as a button's key was:
     * a raised grey slab in 98, Luna's white one edged in dark blue in XP. Its edges are its own end cells and,
     * where [roomAbove] and [roomBelow] say so, the rows over and under it. [default] is the one Enter presses.
     * Returns how wide it is.
     */
    fun button(
        canvas: Canvas,
        p: Palette,
        x: Int,
        y: Int,
        label: String,
        mnemonic: Int? = null,
        default: Boolean = false,
        roomAbove: Boolean = true,
        roomBelow: Boolean = true,
    ): Int {
        val w = Canvas.displayWidth(label) + 4
        val xp = xp(p)
        val face = if (xp) mix(Xp.WINDOW, Xp.BUTTON_FOOT, .3f) else p.w98.face
        // 98 lit a slab along its top and left and shaded it along its foot and right, the default button more
        // deeply; XP edged its button all round in dark blue, and the default one's foot in the blue of focus.
        val lit = if (xp) Xp.BUTTON_EDGE else p.w98.highlight
        val shaded = when {
            xp -> Xp.BUTTON_EDGE
            default -> p.w98.darkShadow
            else -> p.w98.shadow
        }
        canvas.fill(x, y, w, 1, face)
        canvas.set(x, y, LEFT, lit, face)
        canvas.set(x + w - 1, y, RIGHT, shaded, face)
        if (roomAbove) for (c in x until x + w) canvas.set(c, y - 1, FOOT, lit)
        if (roomBelow) for (c in x until x + w) canvas.set(c, y + 1, TOP, if (xp && default) Xp.FOCUS else shaded)
        val style = if (default) BOLD else 0
        canvas.write(x + 2, y, label, text(p), face, style)
        if (mnemonic != null && mnemonic in label.indices) canvas.set(x + 2 + mnemonic, y, label[mnemonic].toString(), text(p), face, style or UNDERLINE)
        return w
    }

    /**
     * A scroll bar down the [h] cells of column [x] from [y], for [visible] rows of [total] scrolled [offset] down:
     * an arrow button at each end and the thumb between them. 98's track is the dither of white and grey it was
     * drawn with and its thumb a grey slab; XP's are pale, the thumb Luna's light blue with its grip.
     */
    fun scrollBar(canvas: Canvas, p: Palette, x: Int, y: Int, h: Int, offset: Int, visible: Int, total: Int) {
        if (h < 4 || total <= visible) return
        val track = h - 2
        val thumb = (track * visible / total).coerceIn(1, track)
        val at = ((track - thumb) * offset / (total - visible).coerceAtLeast(1)).coerceIn(0, track - thumb)
        if (xp(p)) {
            val pale = mix(Xp.WINDOW, Xp.FACE, .45f)
            for (r in 0 until track) canvas.set(x, y + 1 + r, " ", Xp.SCROLL_EDGE, pale)
            canvas.set(x, y, "▲", Xp.SCROLL_ARROW, Xp.SCROLL_THUMB)
            canvas.set(x, y + h - 1, "▼", Xp.SCROLL_ARROW, Xp.SCROLL_THUMB)
            for (r in at until at + thumb) canvas.set(x, y + 1 + r, RIGHT, Xp.SCROLL_EDGE, Xp.SCROLL_THUMB)
            if (thumb >= 3) canvas.set(x, y + 1 + at + thumb / 2, "≡", Xp.SCROLL_EDGE, Xp.SCROLL_THUMB, BOLD)
        } else {
            for (r in 0 until track) canvas.set(x, y + 1 + r, "▒", p.w98.highlight, p.w98.face)
            canvas.set(x, y, "▲", p.w98.text, p.w98.face)
            canvas.set(x, y + h - 1, "▼", p.w98.text, p.w98.face)
            for (r in at until at + thumb) canvas.set(x, y + 1 + r, RIGHT, p.w98.darkShadow, p.w98.face)
            canvas.set(x, y + 1 + at, if (thumb > 1) TOP else RIGHT, if (thumb > 1) p.w98.highlight else p.w98.darkShadow, p.w98.face)
        }
    }

    /** A radio button's mark, chosen or not, in the colours its theme drew it in. */
    fun radio(canvas: Canvas, p: Palette, x: Int, y: Int, chosen: Boolean) {
        val colour = when {
            xp(p) && chosen -> Xp.PROGRESS
            xp(p) -> Xp.FIELD_EDGE
            else -> p.w98.text
        }
        canvas.set(x, y, if (chosen) "◉" else "○", colour, null, BOLD)
    }
}
