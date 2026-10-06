package app.noctorium.cli.tui

import java.text.Normalizer
import java.util.Locale

/**
 * Letters set large in cells, for a title on a poster and the line being sung.
 *
 * A small type of its own: capitals, figures and the common marks, in two sizes -- five dots by seven, the
 * shape of a character on a hi-fi's display, and three by five for where that is too wide. A cell holds two
 * dots, one above the other, drawn with the half blocks ▀ and ▄ and the full block █; a cell is twice as tall
 * as it is wide, so the dots come out square. Accented letters are set as the letters under their accents and
 * the typographer's quotes and dashes as plain ones. Anything the type has no shape for -- another script, an
 * emoji -- is not set at all: the caller writes the words as they are instead, in bold.
 */
object BigType {
    enum class Size(val dots: Int, val space: Int) {
        LARGE(7, 3),
        SMALL(5, 2);

        /** Rows of cells a line takes: two dots to a row, and a row of space under the last when the dots are odd. */
        val rows: Int get() = (dots + 1) / 2
    }

    /** [lines] of text, already folded to what the type can set, in [size]. */
    class Setting(val size: Size, val lines: List<String>) {
        /** Rows of cells the whole of it takes, with a blank row between lines. */
        val height: Int get() = lines.size * size.rows + (lines.size - 1).coerceAtLeast(0)
    }

    /**
     * [text] set in the largest size whose lines fit [w] cells across, and at most [maxLines] of them [h] rows
     * down, broken between words. Null when no size fits, or the text has a character the type cannot set.
     */
    fun fit(text: String, w: Int, h: Int, maxLines: Int = 3): Setting? {
        val folded = fold(text) ?: return null
        for (size in Size.entries) {
            val lines = wrap(folded, size, w) ?: continue
            val setting = Setting(size, lines)
            if (lines.size <= maxLines && setting.height <= h) return setting
        }
        return null
    }

    /** How many cells [line] takes across in [size]. */
    fun width(line: String, size: Size): Int =
        line.sumOf { (glyph(it, size)?.width ?: 0) + 1 }.minus(1).coerceAtLeast(0)

    /** Draws [line], already folded, from [x], [y] in [colour]; the cells between the dots keep their background. */
    fun draw(canvas: Canvas, x: Int, y: Int, line: String, size: Size, colour: Rgb) {
        var left = x
        for (char in line) {
            val glyph = glyph(char, size) ?: continue
            for (row in 0 until size.rows) for (column in 0 until glyph.width) {
                val top = glyph.dot(column, row * 2)
                val bottom = glyph.dot(column, row * 2 + 1)
                val block = when {
                    top && bottom -> "█"
                    top -> "▀"
                    bottom -> "▄"
                    else -> continue
                }
                canvas.set(left + column, y + row, block, colour)
            }
            left += glyph.width + 1
        }
    }

    /**
     * [text] in the type's own characters: capitals, accents taken off, the typographer's marks made plain, or
     * null when something in it has no shape here.
     */
    fun fold(text: String): String? {
        val plain = buildString {
            for (char in text.trim().uppercase(Locale.ROOT)) append(PLAIN[char] ?: char.toString())
        }
        val bare = Normalizer.normalize(plain, Normalizer.Form.NFD).replace(MARKS, "")
            .replace(Regex("\\s+"), " ")
        return bare.takeIf { it.isNotEmpty() && it.all { char -> char == ' ' || char in LARGE } }
    }

    /** [text] broken between words into lines no wider than [w] in [size], or null when a word is wider alone. */
    private fun wrap(text: String, size: Size, w: Int): List<String>? {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            if (width(word, size) > w) return null
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (width(candidate, size) <= w) {
                line = candidate
            } else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    internal class Glyph(val width: Int, val rows: List<String>) {
        fun dot(column: Int, row: Int): Boolean = rows.getOrNull(row)?.getOrNull(column) == '#'
    }

    private fun glyph(char: Char, size: Size): Glyph? = when {
        char == ' ' -> Glyph(size.space, emptyList())
        size == Size.LARGE -> LARGE[char]
        else -> SMALL[char]
    }

    private val MARKS = Regex("\\p{Mn}+")

    /** The marks and letters that are set as others: quotes and dashes as the plain ones, ligatures spelt out. */
    private val PLAIN = mapOf(
        '‘' to "'", '’' to "'", '‚' to "'", '‛' to "'", '′' to "'", '`' to "'", '´' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '‟' to "\"", '″' to "\"", '«' to "\"", '»' to "\"",
        '–' to "-", '—' to "-", '―' to "-", '‐' to "-", '‑' to "-", '−' to "-",
        '…' to "...", '·' to "-", '•' to "-", '×' to "X", ' ' to " ",
        'Æ' to "AE", 'Œ' to "OE", 'Ø' to "O", 'Ł' to "L", 'Đ' to "D", 'Þ' to "TH", 'ẞ' to "SS",
    )

    /** Rows of dots, top first, '#' for a dot; split on spaces, so each glyph is one line of the table. */
    private fun glyphs(vararg pairs: Pair<Char, String>): Map<Char, Glyph> = pairs.associate { (char, rows) ->
        val split = rows.split(' ')
        char to Glyph(split.first().length, split)
    }

    internal val LARGE = glyphs(
        'A' to ".###. #...# #...# #...# ##### #...# #...#",
        'B' to "####. #...# #...# ####. #...# #...# ####.",
        'C' to ".###. #...# #.... #.... #.... #...# .###.",
        'D' to "###.. #..#. #...# #...# #...# #..#. ###..",
        'E' to "##### #.... #.... ####. #.... #.... #####",
        'F' to "##### #.... #.... ####. #.... #.... #....",
        'G' to ".###. #...# #.... #.### #...# #...# .####",
        'H' to "#...# #...# #...# ##### #...# #...# #...#",
        'I' to "### .#. .#. .#. .#. .#. ###",
        'J' to "..### ...#. ...#. ...#. ...#. #..#. .##..",
        'K' to "#...# #..#. #.#.. ##... #.#.. #..#. #...#",
        'L' to "#.... #.... #.... #.... #.... #.... #####",
        'M' to "#...# ##.## #.#.# #.#.# #...# #...# #...#",
        'N' to "#...# #...# ##..# #.#.# #..## #...# #...#",
        'O' to ".###. #...# #...# #...# #...# #...# .###.",
        'P' to "####. #...# #...# ####. #.... #.... #....",
        'Q' to ".###. #...# #...# #...# #.#.# #..#. .##.#",
        'R' to "####. #...# #...# ####. #.#.. #..#. #...#",
        'S' to ".#### #.... #.... .###. ....# ....# ####.",
        'T' to "##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..",
        'U' to "#...# #...# #...# #...# #...# #...# .###.",
        'V' to "#...# #...# #...# #...# #...# .#.#. ..#..",
        'W' to "#...# #...# #...# #.#.# #.#.# #.#.# .#.#.",
        'X' to "#...# #...# .#.#. ..#.. .#.#. #...# #...#",
        'Y' to "#...# #...# #...# .#.#. ..#.. ..#.. ..#..",
        'Z' to "##### ....# ...#. ..#.. .#... #.... #####",
        '0' to ".###. #...# #..## #.#.# ##..# #...# .###.",
        '1' to "..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###.",
        '2' to ".###. #...# ....# ...#. ..#.. .#... #####",
        '3' to "##### ...#. ..#.. ...#. ....# #...# .###.",
        '4' to "...#. ..##. .#.#. #..#. ##### ...#. ...#.",
        '5' to "##### #.... ####. ....# ....# #...# .###.",
        '6' to "..##. .#... #.... ####. #...# #...# .###.",
        '7' to "##### ....# ...#. ..#.. .#... .#... .#...",
        '8' to ".###. #...# #...# .###. #...# #...# .###.",
        '9' to ".###. #...# #...# .#### ....# ...#. .##..",
        '.' to ". . . . . . #",
        ',' to ".. .. .. .. .. .# #.",
        '!' to "# # # # # . #",
        '?' to ".###. #...# ....# ...#. ..#.. ..... ..#..",
        '\'' to "# # . . . . .",
        '"' to "#.# #.# ... ... ... ... ...",
        '-' to ".... .... .... #### .... .... ....",
        ':' to ". . # . . # .",
        ';' to ".. .. .# .. .. .# #.",
        '&' to ".##.. #..#. #.#.. .#... #.#.# #..#. .##.#",
        '(' to "..# .#. #.. #.. #.. .#. ..#",
        ')' to "#.. .#. ..# ..# ..# .#. #..",
        '[' to "### #.. #.. #.. #.. #.. ###",
        ']' to "### ..# ..# ..# ..# ..# ###",
        '/' to "....# ....# ...#. ..#.. .#... #.... #....",
        '+' to "..... ..#.. ..#.. ##### ..#.. ..#.. .....",
        '#' to ".#.#. .#.#. ##### .#.#. ##### .#.#. .#.#.",
        '%' to "##... ##..# ...#. ..#.. .#... #..## ...##",
        '*' to "..... ..#.. #.#.# .###. #.#.# ..#.. .....",
        '@' to ".###. #...# ....# .##.# #.#.# #.#.# .###.",
        '=' to ".... .... #### .... #### .... ....",
        '<' to "...# ..#. .#.. #... .#.. ..#. ...#",
        '>' to "#... .#.. ..#. ...# ..#. .#.. #...",
        '_' to "..... ..... ..... ..... ..... ..... #####",
        '$' to "..#.. .#### #.#.. .###. ..#.# ####. ..#..",
        '~' to "..... ..... .#... #.#.# ...#. ..... .....",
    )

    internal val SMALL = glyphs(
        'A' to ".#. #.# ### #.# #.#",
        'B' to "##. #.# ##. #.# ##.",
        'C' to ".## #.. #.. #.. .##",
        'D' to "##. #.# #.# #.# ##.",
        'E' to "### #.. ##. #.. ###",
        'F' to "### #.. ##. #.. #..",
        'G' to ".## #.. #.# #.# .##",
        'H' to "#.# #.# ### #.# #.#",
        'I' to "### .#. .#. .#. ###",
        'J' to "..# ..# ..# #.# .#.",
        'K' to "#.# #.# ##. #.# #.#",
        'L' to "#.. #.. #.. #.. ###",
        'M' to "#...# ##.## #.#.# #...# #...#",
        'N' to "#..# ##.# #.## #..# #..#",
        'O' to ".#. #.# #.# #.# .#.",
        'P' to "##. #.# ##. #.. #..",
        'Q' to ".#. #.# #.# ##. .##",
        'R' to "##. #.# ##. #.# #.#",
        'S' to ".## #.. .#. ..# ##.",
        'T' to "### .#. .#. .#. .#.",
        'U' to "#.# #.# #.# #.# ###",
        'V' to "#.# #.# #.# #.# .#.",
        'W' to "#...# #...# #.#.# ##.## #...#",
        'X' to "#.# #.# .#. #.# #.#",
        'Y' to "#.# #.# .#. .#. .#.",
        'Z' to "### ..# .#. #.. ###",
        '0' to "### #.# #.# #.# ###",
        '1' to ".#. ##. .#. .#. ###",
        '2' to "##. ..# .#. #.. ###",
        '3' to "##. ..# .#. ..# ##.",
        '4' to "#.# #.# ### ..# ..#",
        '5' to "### #.. ##. ..# ##.",
        '6' to ".## #.. ### #.# ###",
        '7' to "### ..# .#. .#. .#.",
        '8' to "### #.# ### #.# ###",
        '9' to "### #.# ### ..# ##.",
        '.' to ". . . . #",
        ',' to ".. .. .. .# #.",
        '!' to "# # # . #",
        '?' to "##. ..# .#. ... .#.",
        '\'' to "# # . . .",
        '"' to "#.# #.# ... ... ...",
        '-' to "... ... ### ... ...",
        ':' to ". # . # .",
        ';' to ".. .# .. .# #.",
        '&' to ".#. #.# .#. #.# .##",
        '(' to ".# #. #. #. .#",
        ')' to "#. .# .# .# #.",
        '[' to "## #. #. #. ##",
        ']' to "## .# .# .# ##",
        '/' to "..# ..# .#. #.. #..",
        '+' to "... .#. ### .#. ...",
        '#' to "#.# ### #.# ### #.#",
        '%' to "#.# ..# .#. #.. #.#",
        '*' to "... #.# .#. #.# ...",
        '@' to ".#. #.# ### #.. .##",
        '=' to "... ### ... ### ...",
        '<' to "..# .#. #.. .#. ..#",
        '>' to "#.. .#. ..# .#. #..",
        '_' to "... ... ... ... ###",
        '$' to ".## ##. .#. .## ##.",
        '~' to "... .## ##. ... ...",
    )
}
