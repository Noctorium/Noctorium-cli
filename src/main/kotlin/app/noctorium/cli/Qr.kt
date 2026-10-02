package app.noctorium.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code as lines of text, two modules to a character cell.
 *
 * Drawn dark on light whatever the terminal's own colours are, because that is the way round a phone's camera
 * reads most reliably, with the quiet zone the standard asks for. The half blocks put two rows of modules in
 * each line, so a code for the web player's address is about twenty lines tall rather than forty.
 */
object Qr {
    fun lines(text: String): List<String> {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
        )
        val out = mutableListOf<String>()
        var y = 0
        while (y < matrix.height) {
            val line = StringBuilder()
            for (x in 0 until matrix.width) {
                val top = matrix.get(x, y)
                val bottom = y + 1 < matrix.height && matrix.get(x, y + 1)
                line.append(
                    when {
                        top && bottom -> ' '
                        top -> '▄'
                        bottom -> '▀'
                        else -> '█'
                    },
                )
            }
            out += line.toString()
            y += 2
        }
        return out
    }

    /**
     * The code ready to print. Blocks are the light modules, so it reads without colour on a dark terminal;
     * with colour it is set white on black outright, which also makes it the right way round on a light one.
     */
    fun print(text: String, indent: String = "  ", colour: Boolean = System.getenv("NO_COLOR") == null): String =
        lines(text).joinToString("\n") { line ->
            if (colour) "$indent\u001b[97;40m$line\u001b[0m" else indent + line
        }
}
