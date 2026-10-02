package app.noctorium.cli.tui

import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

/**
 * The terminal itself: taken over on [open], handed back exactly as it was found on [close].
 *
 * Drawing goes through [draw], which compares the new frame with the last one and sends only the cells that
 * changed. A terminal redrawn in full several times a second flickers, and over ssh it is a lot of bytes for
 * a seek bar that moved one cell. Colours are sent as 24-bit where the terminal says it can show them, which
 * is nearly all of them now, and folded into the 256-colour palette where it does not.
 */
class Screen : AutoCloseable {
    private val terminal: Terminal = TerminalBuilder.builder()
        .system(true)
        .encoding(StandardCharsets.UTF_8)
        .jni(true)
        .build()
    private val writer: PrintWriter = terminal.writer()
    private val reader: NonBlockingReader = terminal.reader()
    private var saved: org.jline.terminal.Attributes? = null
    private var last: Canvas? = null
    private val decoder = KeyDecoder()
    private val queued = ArrayDeque<Input>()

    @Volatile
    var resized = false
        private set

    val trueColour: Boolean = run {
        val colourTerm = System.getenv("COLORTERM").orEmpty().lowercase()
        "truecolor" in colourTerm || "24bit" in colourTerm ||
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ||
            System.getenv("TERM_PROGRAM") in setOf("iTerm.app", "WezTerm", "vscode", "ghostty") ||
            System.getenv("WT_SESSION") != null || System.getenv("KITTY_WINDOW_ID") != null
    }

    val width: Int get() = terminal.width.takeIf { it > 0 } ?: 100
    val height: Int get() = terminal.height.takeIf { it > 0 } ?: 30

    /** Whether there is a terminal to draw on at all, rather than a pipe. */
    val interactive: Boolean get() = terminal.type != Terminal.TYPE_DUMB && terminal.type != Terminal.TYPE_DUMB_COLOR

    fun open() {
        saved = terminal.enterRawMode()
        terminal.handle(Terminal.Signal.WINCH) { resized = true; last = null }
        terminal.handle(Terminal.Signal.INT) { queued.addLast(Input.Text("^c")) }
        runCatching { terminal.trackMouse(Terminal.MouseTracking.Normal) }
        // Alternate screen, hidden cursor, wheel and clicks reported, in the SGR form where it is understood.
        raw("\u001b[?1049h\u001b[?25l\u001b[?1000h\u001b[?1006h\u001b[2J")
        flush()
    }

    override fun close() {
        runCatching { terminal.trackMouse(Terminal.MouseTracking.Off) }
        raw("\u001b[?1006l\u001b[?1000l\u001b[0m\u001b[?25h\u001b[?1049l")
        flush()
        saved?.let { terminal.setAttributes(it) }
        terminal.close()
    }

    fun raw(value: String) {
        writer.write(value)
    }

    fun flush() = writer.flush()

    /**
     * The next input, waiting at most [timeoutMs]. A lone Escape is given 40 ms to turn out to be the start
     * of an arrow key before it is believed.
     */
    fun read(timeoutMs: Long): Input? {
        queued.removeFirstOrNull()?.let { return it }
        if (resized) {
            resized = false
            return Input.Resize
        }
        var wait = timeoutMs
        while (true) {
            val c = reader.read(if (decoder.inSequence) 40 else wait)
            if (c == NonBlockingReader.READ_EXPIRED) {
                if (decoder.inSequence) return decoder.flushEscape()
                return null
            }
            if (c < 0) return null
            val codePoint = if (Character.isHighSurrogate(c.toChar())) {
                val low = reader.read(10)
                if (low >= 0) Character.toCodePoint(c.toChar(), low.toChar()) else c
            } else c
            decoder.feed(codePoint)?.let { return it }
            wait = 40
        }
    }

    /** Sends [canvas] to the terminal, cell by cell where it differs from what is already there. */
    fun draw(canvas: Canvas) {
        val previous = last?.takeIf { it.width == canvas.width && it.height == canvas.height }
        val out = StringBuilder(canvas.width * canvas.height / 2)
        if (previous == null) out.append("\u001b[0m\u001b[2J")
        var lastFg = Int.MIN_VALUE
        var lastBg = Int.MIN_VALUE
        var lastStyle = -1
        var cursorX = -1
        var cursorY = -1
        for (y in 0 until canvas.height) {
            for (x in 0 until canvas.width) {
                val i = y * canvas.width + x
                val t = canvas.text[i] ?: " "
                if (t.isEmpty()) continue
                if (previous != null &&
                    previous.text[i] == canvas.text[i] && previous.fg[i] == canvas.fg[i] &&
                    previous.bg[i] == canvas.bg[i] && previous.style[i] == canvas.style[i] &&
                    // A wide character two cells on may have been replaced by narrow ones: redraw its tail.
                    !(x + 1 < canvas.width && previous.text[i + 1] != canvas.text[i + 1] && canvas.text[i + 1] == "")
                ) continue
                if (cursorX != x || cursorY != y) out.append("\u001b[").append(y + 1).append(';').append(x + 1).append('H')
                val f = canvas.fg[i]
                val b = canvas.bg[i]
                val s = canvas.style[i]
                if (f != lastFg || b != lastBg || s != lastStyle) {
                    out.append("\u001b[0")
                    if (s and BOLD != 0) out.append(";1")
                    if (s and DIM != 0) out.append(";2")
                    if (s and ITALIC != 0) out.append(";3")
                    if (s and UNDERLINE != 0) out.append(";4")
                    colour(out, f, foreground = true)
                    colour(out, b, foreground = false)
                    out.append('m')
                    lastFg = f; lastBg = b; lastStyle = s
                }
                out.append(t)
                val w = if (x + 1 < canvas.width && canvas.text[i + 1] == "") 2 else 1
                cursorX = x + w
                cursorY = y
            }
        }
        out.append("\u001b[0m")
        writer.write(out.toString())
        writer.flush()
        last = canvas
    }

    /** Forgets what is on the terminal, so the next frame is sent whole: after something else wrote to it. */
    fun invalidate() {
        last = null
    }

    private fun colour(out: StringBuilder, c: Rgb, foreground: Boolean) {
        if (c == DEFAULT) {
            out.append(if (foreground) ";39" else ";49")
            return
        }
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        if (trueColour) {
            out.append(if (foreground) ";38;2;" else ";48;2;").append(r).append(';').append(g).append(';').append(b)
        } else {
            out.append(if (foreground) ";38;5;" else ";48;5;").append(xterm256(r, g, b))
        }
    }

    private fun xterm256(r: Int, g: Int, b: Int): Int {
        // The greys, where the colour has none of its own; the 6x6x6 cube otherwise.
        if (kotlin.math.abs(r - g) < 10 && kotlin.math.abs(g - b) < 10) {
            val grey = (r + g + b) / 3
            if (grey < 8) return 16
            if (grey > 248) return 231
            return 232 + ((grey - 8) * 24 / 240).coerceIn(0, 23)
        }
        fun level(v: Int) = if (v < 48) 0 else if (v < 115) 1 else (v - 35) / 40
        return 16 + 36 * level(r) + 6 * level(g) + level(b)
    }
}
