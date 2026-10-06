package app.noctorium.cli.tui

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A frame written out to be looked at: as text, as HTML, and as a picture drawn the way a terminal draws it.
 *
 * In the picture a cell is a box of its background with its character over it. The block elements -- the half
 * and eighth blocks covers and bevels are made of, the quadrants and the shades -- are drawn as the shapes they
 * are rather than left to a font, which is what most terminals do too, so edges meet as they will on screen.
 */
object Frames {
    private const val CELL_W = 9
    private const val CELL_H = 18

    fun write(folder: File, name: String, canvas: Canvas) {
        folder.mkdirs()
        File(folder, "$name.html").writeText(html(canvas))
        File(folder, "$name.txt").writeText(text(canvas))
        ImageIO.write(png(canvas), "png", File(folder, "$name.png"))
    }

    fun text(canvas: Canvas): String = buildString {
        for (y in 0 until canvas.height) {
            for (x in 0 until canvas.width) append(canvas.text[y * canvas.width + x] ?: " ")
            append('\n')
        }
    }

    fun png(canvas: Canvas): BufferedImage {
        val image = BufferedImage(canvas.width * CELL_W, canvas.height * CELL_H, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val plain = Font("Cascadia Mono", Font.PLAIN, 14).takeIf { it.family != Font.DIALOG } ?: Font(Font.MONOSPACED, Font.PLAIN, 14)
        val bold = plain.deriveFont(Font.BOLD)
        val fallback = Font("Segoe UI Symbol", Font.PLAIN, 14)
        val cjk = Font("MS Gothic", Font.PLAIN, 14)
        fun colour(c: Rgb, d: Int) = Color(if (c == DEFAULT) d else c)
        for (y in 0 until canvas.height) for (x in 0 until canvas.width) {
            val i = y * canvas.width + x
            val t = canvas.text[i] ?: " "
            if (t.isEmpty()) continue
            val wide = x + 1 < canvas.width && canvas.text[i + 1] == ""
            val w = if (wide) CELL_W * 2 else CELL_W
            val fg = colour(canvas.fg[i], 0xDDDDDD)
            val bg = colour(canvas.bg[i], 0x111111)
            g.color = bg
            g.fillRect(x * CELL_W, y * CELL_H, w, CELL_H)
            if (t == " ") continue
            g.color = fg
            if (!block(g, t, x * CELL_W, y * CELL_H, w)) {
                var font = if (canvas.style[i] and BOLD != 0) bold else plain
                if (font.canDisplayUpTo(t) != -1) font = if (cjk.canDisplayUpTo(t) == -1) cjk else fallback
                g.font = font
                g.drawString(t, x * CELL_W, y * CELL_H + 14)
            }
            if (canvas.style[i] and UNDERLINE != 0) g.fillRect(x * CELL_W, y * CELL_H + 15, w, 1)
        }
        g.dispose()
        return image
    }

    /** Draws [t] as the block it is, if it is one. */
    private fun block(g: Graphics2D, t: String, x: Int, y: Int, w: Int): Boolean {
        val h = CELL_H
        fun rect(left: Double, top: Double, right: Double, bottom: Double) {
            val x0 = x + (left * w).toInt()
            val y0 = y + (top * h).toInt()
            g.fillRect(x0, y0, maxOf(1, x + (right * w).toInt() - x0), maxOf(1, y + (bottom * h).toInt() - y0))
        }
        val lower = "▁▂▃▄▅▆▇█".indexOf(t)
        if (lower >= 0) { rect(0.0, 1 - (lower + 1) / 8.0, 1.0, 1.0); return true }
        val left = "▏▎▍▌▋▊▉".indexOf(t)
        if (left >= 0) { rect(0.0, 0.0, (left + 1) / 8.0, 1.0); return true }
        when (t) {
            "▔" -> rect(0.0, 0.0, 1.0, 1 / 8.0)
            "▕" -> rect(7 / 8.0, 0.0, 1.0, 1.0)
            "▀" -> rect(0.0, 0.0, 1.0, .5)
            "▐" -> rect(.5, 0.0, 1.0, 1.0)
            "▖" -> rect(0.0, .5, .5, 1.0)
            "▗" -> rect(.5, .5, 1.0, 1.0)
            "▘" -> rect(0.0, 0.0, .5, .5)
            "▝" -> rect(.5, 0.0, 1.0, .5)
            "▙" -> { rect(0.0, 0.0, .5, 1.0); rect(.5, .5, 1.0, 1.0) }
            "▛" -> { rect(0.0, 0.0, 1.0, .5); rect(0.0, .5, .5, 1.0) }
            "▜" -> { rect(0.0, 0.0, 1.0, .5); rect(.5, .5, 1.0, 1.0) }
            "▟" -> { rect(.5, 0.0, 1.0, .5); rect(0.0, .5, 1.0, 1.0) }
            "▚" -> { rect(0.0, 0.0, .5, .5); rect(.5, .5, 1.0, 1.0) }
            "▞" -> { rect(.5, 0.0, 1.0, .5); rect(0.0, .5, .5, 1.0) }
            "░", "▒", "▓" -> {
                // A dither, as the shades are drawn: one dot in four, every other one, or all but one in four.
                for (py in 0 until h) for (px in 0 until w) {
                    val on = when (t) {
                        "░" -> px % 2 == 0 && py % 2 == 0
                        "▒" -> (px + py) % 2 == 0
                        else -> !(px % 2 == 0 && py % 2 == 0)
                    }
                    if (on) g.fillRect(x + px, y + py, 1, 1)
                }
            }
            else -> return false
        }
        return true
    }

    private fun css(c: Rgb, fallback: String) = if (c == DEFAULT) fallback else "#%06x".format(c)

    fun html(canvas: Canvas): String = buildString {
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
