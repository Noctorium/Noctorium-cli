package app.noctorium.cli.tui

import app.noctorium.cli.Qr
import app.noctorium.playback.SLEEP_TIMER_PRESETS

/**
 * Something laid over the page: a picker, a question, a code to scan. The newest one gets the keys first;
 * Escape puts it away.
 */
sealed interface Overlay {
    fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int)

    /** True when the key was this overlay's to have. */
    fun handle(tui: Tui, input: Input): Boolean

    fun mouse(tui: Tui, input: Input.Mouse): Boolean = true

    /** A centred panel, its contents drawn by [body] into the area inside its border. */
    fun panel(tui: Tui, canvas: Canvas, w: Int, h: Int, width: Int, height: Int, title: String, body: (x: Int, y: Int, w: Int, h: Int) -> Unit) {
        val p = tui.palette
        val pw = minOf(width, w - 4)
        val ph = minOf(height, h - 2)
        val x = (w - pw) / 2
        val y = (h - ph) / 2
        // A shadow, a column and a row of the page darkened, so the panel sits above it.
        canvas.tint(x + 2, y + ph, pw, 1, mix(p.page.takeIf { it != DEFAULT } ?: 0, 0, .5f))
        canvas.tint(x + pw, y + 1, 2, ph, mix(p.page.takeIf { it != DEFAULT } ?: 0, 0, .5f))
        canvas.box(x, y, pw, ph, p.accent, p.card, title, p.accent)
        canvas.clipped(x + 2, y + 1, pw - 4, ph - 2) { body(x + 2, y + 1, pw - 4, ph - 2) }
    }

    data object Help : Overlay {
        private val keys = listOf(
            "Moving about" to listOf(
                "1 – 8, Tab" to "The pages, by number or in turn",
                "↑ ↓  PgUp PgDn" to "Choose; the mouse wheel and clicks work too",
                "Enter" to "Play it from here, or open it",
                "Esc  Backspace" to "Back",
                "/" to "Search every service at once, or paste a link",
            ),
            "Playing" to listOf(
                "Space" to "Play or pause",
                "n  p" to "Next, previous",
                "← →" to "Back or on five seconds; with Shift, thirty",
                "+ -  m" to "Volume, mute",
                "s  r" to "Shuffle, repeat",
                "z" to "Sleep timer",
            ),
            "The selected track" to listOf(
                "a  A" to "Add to the queue, or play it next",
                "l" to "Like or unlike, on the real account (L: the one playing)",
                "P" to "Add to a playlist",
                "d  e" to "Download to keep, or save as an MP3 in your music folder",
                "c  o" to "Copy its link, open it in the browser",
                "x  J K" to "Remove from the queue or playlist, move it down or up",
            ),
            "Noctorium" to listOf(
                "t  T" to "Next or previous theme",
                "w" to "Start the web player, for any browser in the house",
                "?" to "These keys",
                "q  Ctrl+C" to "Quit",
            ),
        )

        override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
            val p = tui.palette
            val lines = keys.sumOf { it.second.size + 2 }
            panel(tui, canvas, w, h, 92, lines + 3, "Keys") { x, y, pw, _ ->
                var row = y
                keys.forEach { (section, entries) ->
                    canvas.write(x, row, section.uppercase(), p.accent, p.card, BOLD)
                    row++
                    entries.forEach { (key, what) ->
                        canvas.write(x + 1, row, key, p.text, p.card, BOLD, max = 18)
                        canvas.write(x + 20, row, what, p.subtext, p.card, max = pw - 21)
                        row++
                    }
                    row++
                }
            }
        }

        override fun handle(tui: Tui, input: Input): Boolean {
            tui.overlays.removeLastOrNull()
            return true
        }
    }

    /** A short list to choose from: a playlist to add to, a sleep timer, a lyrics source. */
    class Picker(val title: String, val options: List<Pair<String, () -> Unit>>, val note: String? = null) : Overlay {
        private val list = ListState()
        private var top = 0
        private var left = 0

        override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
            val p = tui.palette
            val visible = minOf(options.size, h - 8).coerceAtLeast(1)
            val width = (options.maxOfOrNull { Canvas.displayWidth(it.first) } ?: 10).plus(10).coerceIn(36, 80)
            panel(tui, canvas, w, h, width, visible + 3 + (if (note != null) 2 else 0), title) { x, y, pw, _ ->
                top = y; left = x
                val rows = options.map { Row.Action(it.first) }
                list.follow(visible)
                for (i in 0 until visible) {
                    val index = list.offset + i
                    val option = options.getOrNull(index) ?: break
                    val selected = index == list.selected
                    if (selected) canvas.fill(x - 1, y + i, pw + 2, 1, p.selection)
                    canvas.write(x, y + i, if (selected) "›" else " ", p.accent, if (selected) p.selection else p.card)
                    canvas.write(x + 2, y + i, option.first, if (selected) p.text else p.subtext, if (selected) p.selection else p.card, if (selected) BOLD else 0, max = pw - 3)
                }
                rows.size
                note?.let { canvas.write(x, y + visible + 1, it, p.faint, p.card, ITALIC, max = pw) }
            }
        }

        override fun handle(tui: Tui, input: Input): Boolean {
            val rows = options.map { Row.Action(it.first) }
            when {
                input is Input.Key && input.key == Keys.UP -> list.move(rows, -1)
                input is Input.Key && input.key == Keys.DOWN -> list.move(rows, 1)
                input is Input.Key && input.key == Keys.ENTER -> {
                    tui.overlays.removeLastOrNull()
                    options.getOrNull(list.selected)?.second?.invoke()
                }
                input is Input.Key && (input.key == Keys.ESCAPE || input.key == Keys.BACKSPACE) -> tui.overlays.removeLastOrNull()
                input is Input.Text && input.char == "q" -> tui.overlays.removeLastOrNull()
                input is Input.Text && input.char.length == 1 && input.char[0].isDigit() -> {
                    val index = input.char.toInt() - 1
                    if (index in options.indices) {
                        tui.overlays.removeLastOrNull()
                        options[index].second()
                    }
                }
                else -> Unit
            }
            return true
        }

        override fun mouse(tui: Tui, input: Input.Mouse): Boolean {
            val rows = options.map { Row.Action(it.first) }
            when (input.kind) {
                MouseKind.WHEEL_UP -> list.move(rows, -1)
                MouseKind.WHEEL_DOWN -> list.move(rows, 1)
                MouseKind.PRESS -> {
                    val index = list.offset + (input.y - top)
                    if (input.y >= top && index in options.indices) {
                        tui.overlays.removeLastOrNull()
                        options[index].second()
                    } else {
                        tui.overlays.removeLastOrNull()
                    }
                }
                else -> Unit
            }
            return true
        }
    }

    /**
     * Things each on or off, ticked in place: the genres Home has a Bandcamp row for.
     *
     * What is ticked stays here until the list is put away, and is handed to [done] once, then. Every change
     * to the genres reads Home again from Bandcamp, and one reading for a handful of ticks is kinder than a
     * reading for each -- several at once could also finish out of order and leave an older Home showing.
     */
    class Checklist(
        val title: String,
        val labels: List<String>,
        /** The ones ticked to begin with, by index into [labels], in their order. */
        initial: List<Int>,
        val note: String? = null,
        /** The ticked ones when the list is put away: those it began with in their order, then the new ones. */
        val done: (List<Int>) -> Unit,
    ) : Overlay {
        private val list = ListState()
        private val ticked = initial.filter { it in labels.indices }.distinct().toMutableList()
        private val rows = labels.map { Row.Action(it) }
        private var top = 0
        private var shown = 0

        /** The ones ticked now, before [done] has them. */
        val chosen: List<Int> get() = ticked.toList()

        private fun toggle(index: Int) {
            if (index !in labels.indices) return
            if (!ticked.remove(index)) ticked += index
        }

        private fun close(tui: Tui) {
            tui.overlays.removeLastOrNull()
            done(ticked.toList())
        }

        override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
            val p = tui.palette
            val visible = minOf(labels.size, h - 8).coerceAtLeast(1)
            val width = maxOf((labels.maxOfOrNull { Canvas.displayWidth(it) } ?: 10) + 12, (note?.let(Canvas::displayWidth) ?: 0) + 6).coerceIn(44, 80)
            panel(tui, canvas, w, h, width, visible + 3 + (if (note != null) 2 else 0), title) { x, y, pw, _ ->
                top = y
                shown = visible
                list.follow(visible)
                for (i in 0 until visible) {
                    val index = list.offset + i
                    val label = labels.getOrNull(index) ?: break
                    val selected = index == list.selected
                    val on = index in ticked
                    val back = if (selected) p.selection else p.card
                    if (selected) canvas.fill(x - 1, y + i, pw + 2, 1, p.selection)
                    canvas.write(x, y + i, if (selected) "›" else " ", p.accent, back)
                    canvas.write(x + 2, y + i, if (on) "✓" else "·", if (on) p.accent else p.faint, back, BOLD)
                    canvas.write(x + 4, y + i, label, if (on || selected) p.text else p.subtext, back, if (selected) BOLD else 0, max = pw - 5)
                }
                note?.let { canvas.write(x, y + visible + 1, it, p.faint, p.card, ITALIC, max = pw) }
            }
        }

        override fun handle(tui: Tui, input: Input): Boolean {
            when {
                input is Input.Key && input.key == Keys.UP -> list.move(rows, -1)
                input is Input.Key && input.key == Keys.DOWN -> list.move(rows, 1)
                input is Input.Key && input.key == Keys.ENTER -> toggle(list.selected)
                input is Input.Text && input.char == " " -> toggle(list.selected)
                input is Input.Key && (input.key == Keys.ESCAPE || input.key == Keys.BACKSPACE) -> close(tui)
                input is Input.Text && input.char == "q" -> close(tui)
                else -> Unit
            }
            return true
        }

        override fun mouse(tui: Tui, input: Input.Mouse): Boolean {
            when (input.kind) {
                MouseKind.WHEEL_UP -> list.move(rows, -1)
                MouseKind.WHEEL_DOWN -> list.move(rows, 1)
                MouseKind.PRESS -> {
                    val index = list.offset + (input.y - top)
                    if (input.y >= top && input.y < top + shown && index in labels.indices) {
                        list.selected = index
                        toggle(index)
                    } else {
                        close(tui)
                    }
                }
                else -> Unit
            }
            return true
        }
    }

    /** A line of text to type: a playlist's new name, a token, a cookies file. */
    class Prompt(
        val title: String,
        val hint: String,
        initial: String = "",
        val secret: Boolean = false,
        val submit: (String) -> Unit,
    ) : Overlay {
        private var text = initial

        override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
            val p = tui.palette
            panel(tui, canvas, w, h, 72, 7, title) { x, y, pw, _ ->
                canvas.write(x, y, hint, p.subtext, p.card, max = pw)
                canvas.fill(x, y + 2, pw, 1, p.page.takeIf { it != DEFAULT } ?: p.panel)
                val shown = if (secret) "•".repeat(text.length) else text
                val visible = shown.takeLast(pw - 2)
                val used = canvas.write(x + 1, y + 2, visible, p.text, p.page.takeIf { it != DEFAULT } ?: p.panel)
                canvas.set(x + 1 + used, y + 2, "▏", p.accent, p.page.takeIf { it != DEFAULT } ?: p.panel, BOLD)
                canvas.write(x, y + 4, "Enter to save  ·  Esc to cancel", p.faint, p.card)
            }
        }

        override fun handle(tui: Tui, input: Input): Boolean {
            when (input) {
                is Input.Key -> when (input.key) {
                    Keys.ENTER -> { tui.overlays.removeLastOrNull(); submit(text.trim()) }
                    Keys.ESCAPE -> tui.overlays.removeLastOrNull()
                    Keys.BACKSPACE -> if (text.isNotEmpty()) text = text.dropLastCodePoint()
                    else -> Unit
                }
                is Input.Text -> when {
                    input.char == "^u" -> text = ""
                    input.char == "^w" -> text = text.trimEnd().substringBeforeLast(' ', "")
                    input.char.startsWith("^") && input.char.length == 2 -> Unit
                    else -> text += input.char
                }
                else -> Unit
            }
            return true
        }
    }

    class Confirm(val question: String, val detail: String? = null, val yes: () -> Unit) : Overlay {
        override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
            val p = tui.palette
            panel(tui, canvas, w, h, 64, if (detail != null) 7 else 6, "Are you sure?") { x, y, pw, _ ->
                canvas.write(x, y, question, p.text, p.card, BOLD, max = pw)
                detail?.let { canvas.write(x, y + 1, it, p.subtext, p.card, max = pw) }
                val row = y + if (detail != null) 3 else 2
                canvas.write(x, row, " y  Yes ", p.onAccent, p.accent, BOLD)
                canvas.write(x + 10, row, " n  No ", p.text, p.selection, BOLD)
            }
        }

        override fun handle(tui: Tui, input: Input): Boolean {
            when {
                input is Input.Text && (input.char == "y" || input.char == "Y") -> { tui.overlays.removeLastOrNull(); yes() }
                input is Input.Key && input.key == Keys.ENTER -> { tui.overlays.removeLastOrNull(); yes() }
                else -> tui.overlays.removeLastOrNull()
            }
            return true
        }
    }

    /** A QR code with what it is for: signing in from the phone, or opening the web player on one. */
    class Qr(
        val tag: String,
        val title: String,
        val code: String,
        val lines: List<String>,
        val onClose: () -> Unit = {},
    ) : Overlay {
        private val modules by lazy { app.noctorium.cli.Qr.lines(code) }

        override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
            val p = tui.palette
            val qrWidth = modules.firstOrNull()?.length ?: 0
            val fitsCode = modules.size + lines.size + 5 <= h
            val width = maxOf(qrWidth + 6, lines.maxOfOrNull { Canvas.displayWidth(it) + 6 } ?: 0, 44)
            val height = (if (fitsCode) modules.size + 1 else 0) + lines.size + 4
            panel(tui, canvas, w, h, width, height, title) { x, y, pw, _ ->
                var row = y
                if (fitsCode) {
                    val qx = x + (pw - qrWidth) / 2
                    modules.forEach { line ->
                        canvas.write(qx, row, line, 0xFFFFFF, 0x000000)
                        row++
                    }
                    row++
                } else {
                    canvas.write(x, row, "Make the window taller to see the code.", p.warn, p.card, max = pw)
                    row += 2
                }
                lines.forEach { line -> canvas.writeCentred(x, pw, row, line, p.text, p.card); row++ }
                canvas.writeCentred(x, pw, row, "Esc to close", p.faint, p.card)
            }
        }

        override fun handle(tui: Tui, input: Input): Boolean {
            if (input is Input.Key && (input.key == Keys.ESCAPE || input.key == Keys.ENTER) || input is Input.Text && input.char == "q") {
                tui.overlays.removeLastOrNull()
                onClose()
            }
            return true
        }
    }

    /** A link Noctorium wanted opened: shown whole, so it can be opened by hand where no browser could be. */
    class Link(val url: String, val opened: Boolean) : Overlay {
        override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
            val p = tui.palette
            panel(tui, canvas, w, h, minOf(maxOf(Canvas.displayWidth(url) + 8, 60), w - 4), 8, if (opened) "Opened in your browser" else "Open this link") { x, y, pw, _ ->
                canvas.write(x, y, if (opened) "If nothing appeared, open it yourself:" else "No browser could be opened here. Open this on any device:", p.subtext, p.card, max = pw)
                // OSC 8 makes it clickable in the terminals that support it; the text itself is the fallback.
                canvas.write(x, y + 2, url, p.accent, p.card, UNDERLINE, max = pw)
                canvas.write(x, y + 4, "c to copy  ·  Esc to close", p.faint, p.card)
            }
        }

        override fun handle(tui: Tui, input: Input): Boolean {
            if (input is Input.Text && input.char == "c") {
                tui.parts.bridge.copyToClipboard(url)
                return true
            }
            tui.overlays.removeLastOrNull()
            return true
        }
    }
}

object Overlays {
    fun sleepTimer(tui: Tui): Overlay {
        val state = tui.state
        val options = buildList<Pair<String, () -> Unit>> {
            if (state.sleepTimer.value != null) add("Turn the sleep timer off" to { state.cancelSleepTimer(); tui.toast("Sleep timer off") })
            SLEEP_TIMER_PRESETS.forEach { minutes ->
                add("In $minutes minutes" to { state.startSleepTimer(minutes); tui.toast("Stopping in $minutes minutes") })
            }
            add("At the end of this track" to { state.sleepAtEndOfTrack(); tui.toast("Stopping at the end of this track") })
            if (state.sleepTimer.value != null) add("Ten more minutes" to { state.extendSleepTimer(10) })
        }
        return Overlay.Picker("Sleep timer", options)
    }
}

private fun String.dropLastCodePoint(): String {
    if (isEmpty()) return this
    val end = offsetByCodePoints(length, -1)
    return substring(0, end)
}
