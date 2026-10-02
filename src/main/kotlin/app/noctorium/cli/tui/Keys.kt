package app.noctorium.cli.tui

/** One thing the person at the keyboard did. */
sealed interface Input {
    data class Key(val key: Keys, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) : Input
    data class Text(val char: String, val alt: Boolean = false) : Input
    data class Mouse(val kind: MouseKind, val x: Int, val y: Int) : Input
    data object Resize : Input
}

enum class Keys { UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, ENTER, ESCAPE, TAB, BACK_TAB, BACKSPACE, DELETE, INSERT, F1 }

enum class MouseKind { PRESS, RELEASE, DRAG, WHEEL_UP, WHEEL_DOWN }

/**
 * Turns the bytes a terminal sends into [Input]s.
 *
 * Fed one code point at a time, because that is how they arrive: an arrow key is three of them, a mouse
 * click six or more, and a lone Escape is only told apart from the start of one by nothing following it
 * quickly -- which is the caller's to judge, through [flushEscape]. Both mouse encodings are understood:
 * SGR, which xterm and its descendants send when asked, and the older X10 form jline produces itself for a
 * Windows console.
 */
class KeyDecoder {
    private val pending = StringBuilder()

    /** True while a sequence has begun and not ended, so the caller knows to wait briefly for the rest. */
    val inSequence: Boolean get() = pending.isNotEmpty()

    fun feed(codePoint: Int): Input? {
        if (pending.isEmpty()) {
            return when (codePoint) {
                27 -> { pending.append('\u001b'); null }
                13, 10 -> Input.Key(Keys.ENTER)
                9 -> Input.Key(Keys.TAB)
                127, 8 -> Input.Key(Keys.BACKSPACE)
                0 -> null
                // The other control characters are Ctrl and a letter: Ctrl+C is 3.
                in 1..26 -> Input.Text("^" + ('a' + codePoint - 1))
                else -> Input.Text(String(Character.toChars(codePoint)))
            }
        }
        pending.appendCodePoint(codePoint)
        return decode()
    }

    /** Called when nothing followed an Escape for a moment: it was the Escape key, or Alt with nothing. */
    fun flushEscape(): Input? {
        if (pending.isEmpty()) return null
        val text = pending.toString()
        pending.clear()
        return if (text == "\u001b") Input.Key(Keys.ESCAPE) else null
    }

    private fun decode(): Input? {
        val s = pending.toString()
        // ESC followed by something that is not [ or O: Alt held with that key.
        if (s.length == 2 && s[1] != '[' && s[1] != 'O') {
            pending.clear()
            if (s[1] == '\u001b') { pending.append('\u001b'); return Input.Key(Keys.ESCAPE) }
            return Input.Text(s.substring(1), alt = true)
        }
        if (s.length < 3) return null
        if (s[1] == 'O') {
            pending.clear()
            return when (s[2]) {
                'A' -> Input.Key(Keys.UP); 'B' -> Input.Key(Keys.DOWN)
                'C' -> Input.Key(Keys.RIGHT); 'D' -> Input.Key(Keys.LEFT)
                'H' -> Input.Key(Keys.HOME); 'F' -> Input.Key(Keys.END)
                'P' -> Input.Key(Keys.F1)
                else -> null
            }
        }
        // CSI: ESC [ parameters final
        if (s[2] == 'M') {
            // X10 mouse: ESC [ M b x y, each a byte offset by 32.
            if (s.length < 6) return null
            pending.clear()
            val button = s[3].code - 32
            return mouse(button, s[4].code - 33, s[5].code - 33, release = (button and 3) == 3)
        }
        if (s[2] == '<') {
            val end = s.indexOfFirst { it == 'M' || it == 'm' }.takeIf { it > 2 } ?: return if (s.length > 32) reset() else null
            pending.clear()
            val parts = s.substring(3, end).split(';').mapNotNull(String::toIntOrNull)
            if (parts.size < 3) return null
            return mouse(parts[0], parts[1] - 1, parts[2] - 1, release = s[end] == 'm')
        }
        val last = s.last()
        if (last !in '@'..'~') return if (s.length > 16) reset() else null
        pending.clear()
        val body = s.substring(2, s.length - 1)
        val numbers = body.split(';').map { it.toIntOrNull() }
        val modifier = numbers.getOrNull(1) ?: 1
        val shift = (modifier - 1) and 1 != 0
        val alt = (modifier - 1) and 2 != 0
        val ctrl = (modifier - 1) and 4 != 0
        fun key(k: Keys) = Input.Key(k, ctrl = ctrl, alt = alt, shift = shift)
        return when (last) {
            'A' -> key(Keys.UP); 'B' -> key(Keys.DOWN); 'C' -> key(Keys.RIGHT); 'D' -> key(Keys.LEFT)
            'H' -> key(Keys.HOME); 'F' -> key(Keys.END)
            'Z' -> Input.Key(Keys.BACK_TAB, shift = true)
            '~' -> when (numbers.firstOrNull()) {
                1, 7 -> key(Keys.HOME); 4, 8 -> key(Keys.END)
                2 -> key(Keys.INSERT); 3 -> key(Keys.DELETE)
                5 -> key(Keys.PAGE_UP); 6 -> key(Keys.PAGE_DOWN)
                11 -> key(Keys.F1)
                else -> null
            }
            else -> null
        }
    }

    private fun reset(): Input? { pending.clear(); return null }

    private fun mouse(button: Int, x: Int, y: Int, release: Boolean): Input.Mouse? {
        val kind = when {
            button and 64 != 0 -> if (button and 1 == 0) MouseKind.WHEEL_UP else MouseKind.WHEEL_DOWN
            release -> MouseKind.RELEASE
            button and 32 != 0 -> MouseKind.DRAG
            (button and 3) == 0 -> MouseKind.PRESS
            else -> return null
        }
        return Input.Mouse(kind, x.coerceAtLeast(0), y.coerceAtLeast(0))
    }
}

/** A control letter: what Ctrl+C and the others arrive as, written `^c`. */
fun Input.isCtrl(letter: Char): Boolean = this is Input.Text && char == "^$letter"
