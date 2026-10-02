package app.noctorium.cli.tui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Covers, drawn in the terminal with half blocks.
 *
 * Each cell is two pixels stacked: the upper half block ▀ in the colour of the top one, on a background of
 * the bottom one. That is the finest a terminal can draw everywhere, without any of the image protocols only
 * some of them speak, and at the size a cover gets on a screen it is clearly the cover.
 *
 * A cover is fetched once, decoded with ImageIO and shrunk to a small square straight away: what is kept is
 * at most 96 by 96 pixels, which is more than any terminal will show of it. Asking for a JPEG matters,
 * because YouTube Music's image server answers in WebP otherwise and ImageIO cannot read that.
 */
class Art(private val changed: () -> Unit) {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val images = ConcurrentHashMap<String, Pixels>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** A small square picture, row by row. */
    class Pixels(val side: Int, val rgb: IntArray) {
        /** The colour that carries the cover: the average of its more vivid pixels. */
        val accent: Rgb by lazy {
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (c in rgb) {
                val cr = c shr 16 and 0xFF; val cg = c shr 8 and 0xFF; val cb = c and 0xFF
                val max = maxOf(cr, cg, cb); val min = minOf(cr, cg, cb)
                if (max < 50 || max - min < 40) continue
                r += cr; g += cg; b += cb; n++
            }
            if (n == 0L) 0xB47CFF else ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
    }

    /** The cover at [url], if it has arrived; asks for it if not, and calls back when it does. */
    fun get(url: String?): Pixels? {
        val address = url?.takeIf(String::isNotBlank) ?: return null
        images[address]?.let { return it }
        if (address in failed || !loading.add(address)) return null
        scope.launch {
            val pixels = runCatching { fetch(address) }.getOrNull()
            if (pixels != null) {
                if (images.size > 48) images.clear()
                images[address] = pixels
                changed()
            } else {
                failed += address
            }
            loading -= address
        }
        return null
    }

    private fun fetch(url: String): Pixels? {
        val request = Request.Builder().url(asJpeg(url)).header("Accept", "image/jpeg,image/png;q=0.9,*/*;q=0.1").build()
        val bytes = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes()
        } ?: return null
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return shrink(image, 96)
    }

    /** Covers are square and thumbnails often are not: the middle square, averaged down to [side]. */
    private fun shrink(image: BufferedImage, side: Int): Pixels {
        val crop = minOf(image.width, image.height)
        val left = (image.width - crop) / 2
        val top = (image.height - crop) / 2
        val out = IntArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            val x0 = left + x * crop / side
            val x1 = maxOf(x0 + 1, left + (x + 1) * crop / side)
            val y0 = top + y * crop / side
            val y1 = maxOf(y0 + 1, top + (y + 1) * crop / side)
            var r = 0; var g = 0; var b = 0; var n = 0
            var yy = y0
            while (yy < y1) {
                var xx = x0
                while (xx < x1) {
                    val c = image.getRGB(xx, yy)
                    r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
                    xx += maxOf(1, (x1 - x0) / 3)
                }
                yy += maxOf(1, (y1 - y0) / 3)
            }
            out[y * side + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Pixels(side, out)
    }

    /**
     * Draws [pixels] as a square [w] cells wide at [x], [y]; [h] is that many rows, each two pixels tall.
     * With nothing to draw yet, a soft square in [placeholder] with a note in the middle stands in.
     */
    fun draw(canvas: Canvas, pixels: Pixels?, x: Int, y: Int, w: Int, h: Int, placeholder: Rgb, mark: Rgb, round: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val rows = h * 2
        for (row in 0 until h) for (column in 0 until w) {
            val top = sample(pixels, column, row * 2, w, rows, placeholder, round)
            val bottom = sample(pixels, column, row * 2 + 1, w, rows, placeholder, round)
            if (top == null && bottom == null) continue
            val background = bottom ?: canvas.bg[(y + row) * canvas.width + (x + column).coerceAtMost(canvas.width - 1)]
            if (top == null) canvas.set(x + column, y + row, "▄", bottom!!, canvas.bg[(y + row) * canvas.width + (x + column).coerceAtMost(canvas.width - 1)])
            else canvas.set(x + column, y + row, "▀", top, background)
        }
        if (pixels == null && w >= 3 && h >= 1) canvas.write(x + (w - 1) / 2, y + (h - 1) / 2, "♪", mark, placeholder)
    }

    private fun sample(pixels: Pixels?, column: Int, row: Int, w: Int, rows: Int, placeholder: Rgb, round: Boolean): Rgb? {
        if (round) {
            val dx = (column + .5f) / w - .5f
            val dy = (row + .5f) / rows - .5f
            if (dx * dx + dy * dy > .25f) return null
        }
        if (pixels == null) return placeholder
        val px = (column * pixels.side / w).coerceIn(0, pixels.side - 1)
        val py = (row * pixels.side / rows).coerceIn(0, pixels.side - 1)
        return pixels.rgb[py * pixels.side + px]
    }

    companion object {
        /** YouTube Music's image server picks the format from the end of the address: -rj means JPEG. */
        fun asJpeg(url: String): String {
            if ("googleusercontent.com" !in url && "ggpht.com" !in url) return url
            val sizeAt = url.lastIndexOf('=')
            if (sizeAt < 0) return "$url=w226-h226-l90-rj"
            val options = url.substring(sizeAt + 1).split('-').filterNot { it == "rw" || it == "rj" || it == "rp" }
            return url.substring(0, sizeAt + 1) + (options + "rj").joinToString("-")
        }
    }
}
