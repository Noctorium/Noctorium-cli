package app.noctorium.cli.tui

import app.noctorium.settings.ThemeSkin
import app.noctorium.settings.Windows98Colours
import app.noctorium.settings.WindowsXpColours

/** One of core's system colours, as a cell takes it. */
private fun cell(argb: Long): Rgb = (argb and 0xFFFFFF).toInt()

/** Windows 98's system colours ([Windows98Colours]), for cells. */
internal object W98 {
    val FACE = cell(Windows98Colours.FACE)
    val HIGHLIGHT = cell(Windows98Colours.HIGHLIGHT)
    val LIGHT = cell(Windows98Colours.LIGHT)
    val SHADOW = cell(Windows98Colours.SHADOW)
    val DARK_SHADOW = cell(Windows98Colours.DARK_SHADOW)
    val WINDOW = cell(Windows98Colours.WINDOW)
    val TEXT = cell(Windows98Colours.TEXT)
    val GREY_TEXT = cell(Windows98Colours.GREY_TEXT)
    val SELECTION = cell(Windows98Colours.SELECTION)
    val SELECTION_TEXT = cell(Windows98Colours.SELECTION_TEXT)
    val TITLE = cell(Windows98Colours.TITLE)
    val TITLE_END = cell(Windows98Colours.TITLE_END)
    val TITLE_TEXT = cell(Windows98Colours.TITLE_TEXT)
    val TOOLTIP = cell(Windows98Colours.TOOLTIP)
}

/** Luna's colours ([WindowsXpColours]), for cells. */
internal object Xp {
    val FACE = cell(WindowsXpColours.FACE)
    val WINDOW = cell(WindowsXpColours.WINDOW)
    val TEXT = cell(WindowsXpColours.TEXT)
    val GREY_TEXT = cell(WindowsXpColours.GREY_TEXT)
    val SELECTION = cell(WindowsXpColours.SELECTION)
    val SELECTION_TEXT = cell(WindowsXpColours.SELECTION_TEXT)
    val TITLE_TOP = cell(WindowsXpColours.TITLE_TOP)
    val TITLE = cell(WindowsXpColours.TITLE)
    val TITLE_LOW = cell(WindowsXpColours.TITLE_LOW)
    val TITLE_FOOT = cell(WindowsXpColours.TITLE_FOOT)
    val TITLE_TEXT = cell(WindowsXpColours.TITLE_TEXT)
    val FRAME = cell(WindowsXpColours.FRAME)
    val CLOSE = cell(WindowsXpColours.CLOSE)
    val BUTTON_EDGE = cell(WindowsXpColours.BUTTON_EDGE)
    val BUTTON_FOOT = cell(WindowsXpColours.BUTTON_FOOT)
    val HOT = cell(WindowsXpColours.HOT)
    val FOCUS = cell(WindowsXpColours.FOCUS)
    val FIELD_EDGE = cell(WindowsXpColours.FIELD_EDGE)
    val GROUP_EDGE = cell(WindowsXpColours.GROUP_EDGE)
    val GROUP_TITLE = cell(WindowsXpColours.GROUP_TITLE)
    val TAB_EDGE = cell(WindowsXpColours.TAB_EDGE)
    val TAB_CHOSEN = cell(WindowsXpColours.TAB_CHOSEN)
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
 * cell of its own for what it holds. Every colour is core's, so the terminal's 98 is the desktop's grey.
 */
object Skin {
    const val TOP = "▔"
    const val FOOT = "▁"
    const val LEFT = "▏"
    const val RIGHT = "▕"

    /** The face every slab is made of: 98's grey, or Luna's beige. */
    fun face(p: Palette): Rgb = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.FACE else W98.FACE

    /** Where a list or a field sits: white, in both. */
    fun window(p: Palette): Rgb = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.WINDOW else W98.WINDOW

    fun selection(p: Palette): Rgb = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.SELECTION else W98.SELECTION

    fun selectionText(p: Palette): Rgb = if (p.skin == ThemeSkin.WINDOWS_XP) Xp.SELECTION_TEXT else W98.SELECTION_TEXT

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
}
