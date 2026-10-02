package app.noctorium.cli.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyDecoderTest {
    private fun decode(text: String): List<Input> {
        val decoder = KeyDecoder()
        val out = mutableListOf<Input>()
        text.codePoints().forEach { cp -> decoder.feed(cp)?.let(out::add) }
        decoder.flushEscape()?.let(out::add)
        return out
    }

    @Test
    fun `arrows, home and paging arrive as keys`() {
        assertEquals(listOf<Input>(Input.Key(Keys.UP), Input.Key(Keys.DOWN), Input.Key(Keys.RIGHT), Input.Key(Keys.LEFT)), decode("\u001b[A\u001b[B\u001b[C\u001b[D"))
        assertEquals(listOf<Input>(Input.Key(Keys.PAGE_UP), Input.Key(Keys.PAGE_DOWN), Input.Key(Keys.HOME)), decode("\u001b[5~\u001b[6~\u001bOH"))
    }

    @Test
    fun `shift and control ride along on an arrow`() {
        assertEquals(listOf<Input>(Input.Key(Keys.RIGHT, shift = true)), decode("\u001b[1;2C"))
        assertEquals(listOf<Input>(Input.Key(Keys.LEFT, ctrl = true)), decode("\u001b[1;5D"))
    }

    @Test
    fun `a lone escape is the escape key, and alt with a letter is the letter`() {
        assertEquals(listOf<Input>(Input.Key(Keys.ESCAPE)), decode("\u001b"))
        assertEquals(listOf<Input>(Input.Text("x", alt = true)), decode("\u001bx"))
    }

    @Test
    fun `control letters, enter, tab and backspace`() {
        assertEquals(listOf<Input>(Input.Text("^c"), Input.Key(Keys.ENTER), Input.Key(Keys.TAB), Input.Key(Keys.BACKSPACE)), decode("\u0003\r\t\u007f"))
    }

    @Test
    fun `text outside the basic plane comes through whole`() {
        assertEquals(listOf<Input>(Input.Text("血"), Input.Text("🎵")), decode("血🎵"))
    }

    @Test
    fun `mouse in both encodings`() {
        // SGR: press at column 11, row 5 (one-based on the wire).
        assertEquals(listOf<Input>(Input.Mouse(MouseKind.PRESS, 10, 4)), decode("\u001b[<0;11;5M"))
        assertEquals(listOf<Input>(Input.Mouse(MouseKind.WHEEL_DOWN, 0, 0)), decode("\u001b[<65;1;1M"))
        // X10: button, column and row each offset by 32, one-based.
        assertEquals(listOf<Input>(Input.Mouse(MouseKind.WHEEL_UP, 2, 3)), decode("\u001b[M" + (64 + 32).toChar() + (3 + 32).toChar() + (4 + 32).toChar()))
    }
}

class CanvasTest {
    @Test
    fun `a wide character takes two cells and is never cut in half`() {
        val canvas = Canvas(10, 1)
        canvas.clear(DEFAULT, DEFAULT)
        val used = canvas.write(0, 0, "血腥暴力", max = 5)
        // Two of them fit in four cells; the fifth is the ellipsis.
        assertEquals(5, used)
        assertEquals("血", canvas.text[0])
        assertEquals("", canvas.text[1])
        assertEquals("腥", canvas.text[2])
        assertEquals("…", canvas.text[4])
    }

    @Test
    fun `writes stay inside a clip`() {
        val canvas = Canvas(10, 2)
        canvas.clear(DEFAULT, DEFAULT)
        canvas.clipped(2, 0, 3, 1) { canvas.write(0, 0, "abcdefgh"); canvas.write(0, 1, "zzz") }
        assertEquals(" ", canvas.text[0])
        assertEquals("c", canvas.text[2])
        assertEquals("e", canvas.text[4])
        assertEquals(" ", canvas.text[5])
        assertEquals(" ", canvas.text[10])
    }

    @Test
    fun `display width counts what the terminal will show`() {
        assertEquals(5, Canvas.displayWidth("hello"))
        assertEquals(4, Canvas.displayWidth("ア血"))
        assertTrue(Canvas.displayWidth("Ｐ") == 2)
    }
}

class ArtTest {
    @Test
    fun `YouTube Music covers are asked for as JPEG`() {
        assertEquals("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", Art.asJpeg("https://lh3.googleusercontent.com/abc=w120-h120-l90-rw"))
        assertEquals("https://lh3.googleusercontent.com/abc=w226-h226-l90-rj", Art.asJpeg("https://lh3.googleusercontent.com/abc"))
        assertEquals("https://i1.sndcdn.com/a-t500x500.jpg", Art.asJpeg("https://i1.sndcdn.com/a-t500x500.jpg"))
    }

    @Test
    fun `nothing to draw draws a placeholder`() {
        val art = Art {}
        assertNull(art.get(null))
    }
}
