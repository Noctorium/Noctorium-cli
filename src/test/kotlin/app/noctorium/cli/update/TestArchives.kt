package app.noctorium.cli.update

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Archives for the tests, written byte by byte, in each of the ways a real tar can name a long path: POSIX
 * ustar's prefix field, GNU's `././@LongLink` entries, and pax's extended headers. GNU tar on Linux writes
 * the second; bsdtar on a Mac writes the third.
 */
internal class TarWriter(private val out: OutputStream, private val style: Style = Style.GNU) {
    enum class Style { USTAR, GNU, PAX }

    fun folder(name: String, mode: Int = "755".toInt(8)) = entry(name.trimEnd('/') + "/", '5', ByteArray(0), mode)
    fun file(name: String, text: String, mode: Int = "644".toInt(8)) = entry(name, '0', text.toByteArray(), mode)
    fun file(name: String, bytes: ByteArray, mode: Int = "644".toInt(8)) = entry(name, '0', bytes, mode)
    fun symlink(name: String, target: String) = entry(name, '2', ByteArray(0), "777".toInt(8), target)
    fun hardlink(name: String, target: String) = entry(name, '1', ByteArray(0), "644".toInt(8), target)
    fun raw(name: String, type: Char, data: ByteArray = ByteArray(0)) = entry(name, type, data, "644".toInt(8))

    fun finish() {
        out.write(ByteArray(1024))
        out.flush()
    }

    private fun entry(name: String, type: Char, data: ByteArray, mode: Int, link: String = "") {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        var headerName = name
        var prefix = ""
        if (nameBytes.size > 100) {
            when (style) {
                Style.GNU -> block("././@LongLink", 'L', (name + "\u0000").toByteArray(StandardCharsets.UTF_8), "644".toInt(8), "", "", gnu = true)
                Style.PAX -> block("PaxHeaders/x", 'x', pax("path", name), "644".toInt(8), "", "", gnu = false)
                Style.USTAR -> {
                    val split = name.lastIndexOf('/', 155)
                    require(split > 0 && name.length - split - 1 <= 100) { "$name is too long for ustar" }
                    prefix = name.substring(0, split)
                    headerName = name.substring(split + 1)
                }
            }
            if (style != Style.USTAR) headerName = name.takeLast(99)
        }
        block(headerName, type, data, mode, link, prefix, gnu = style == Style.GNU)
    }

    /** One pax record, whose length counts its own digits: found by trying until it stops changing. */
    private fun pax(key: String, value: String): ByteArray {
        val body = " $key=$value\n".toByteArray(StandardCharsets.UTF_8).size
        var length = body
        while (length.toString().length + body != length) length = length.toString().length + body
        return "$length $key=$value\n".toByteArray(StandardCharsets.UTF_8)
    }

    private fun block(name: String, type: Char, data: ByteArray, mode: Int, link: String, prefix: String, gnu: Boolean) {
        val header = ByteArray(512)
        fun put(at: Int, length: Int, text: String) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(bytes, 0, header, at, minOf(bytes.size, length))
        }
        fun octal(at: Int, length: Int, value: Long) = put(at, length, value.toString(8).padStart(length - 1, '0') + "\u0000")
        put(0, 100, name)
        octal(100, 8, mode.toLong())
        octal(108, 8, 1000)
        octal(116, 8, 1000)
        octal(124, 12, data.size.toLong())
        octal(136, 12, 1_700_000_000)
        put(148, 8, "        ")
        header[156] = type.code.toByte()
        put(157, 100, link)
        if (gnu) put(257, 8, "ustar  \u0000") else { put(257, 6, "ustar\u0000"); put(263, 2, "00") }
        put(265, 32, "listener")
        put(297, 32, "listener")
        if (!gnu) put(345, 155, prefix)
        val sum = header.sumOf { it.toInt() and 0xFF }
        put(148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")
        out.write(header)
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        out.write(ByteArray(pad))
    }

    companion object {
        fun tarGz(at: Path, style: Style = Style.GNU, build: TarWriter.() -> Unit): Path {
            GZIPOutputStream(Files.newOutputStream(at)).use { gzip ->
                TarWriter(gzip, style).apply(build).finish()
            }
            return at
        }
    }
}

/** A zip, with the Unix modes it records patched into its central directory afterwards, as Info-ZIP writes them. */
internal object TestZip {
    fun write(at: Path, entries: List<Triple<String, String?, Int?>>): Path {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text, _) ->
                zip.putNextEntry(ZipEntry(name))
                if (text != null) zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        val data = bytes.toByteArray()
        val modes = entries.associate { (name, _, mode) -> name to mode }
        var at2 = 0
        while (at2 + 46 <= data.size) {
            if (le32(data, at2) == 0x02014b50L) {
                val nameLength = le16(data, at2 + 28)
                val name = String(data, at2 + 46, nameLength, StandardCharsets.UTF_8)
                modes[name]?.let { mode ->
                    // Made on Unix (3), by zip version 3.0 (30).
                    data[at2 + 4] = 30
                    data[at2 + 5] = 3
                    val external = (mode.toLong() shl 16) or (if (name.endsWith("/")) 0x10L else 0L)
                    for (i in 0 until 4) data[at2 + 38 + i] = ((external ushr (8 * i)) and 0xFF).toByte()
                }
                at2 += 46 + nameLength + le16(data, at2 + 30) + le16(data, at2 + 32)
            } else {
                at2++
            }
        }
        Files.write(at, data)
        return at
    }

    private fun le16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(bytes: ByteArray, at: Int) = le16(bytes, at).toLong() or (le16(bytes, at + 2).toLong() shl 16)
}

/** A folder for one test, under the build's own temporary folder, with a name that tests whatever it is given. */
internal fun scratch(label: String): Path {
    val base = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
    return Files.createTempDirectory(base, "noctorium-update-$label-").toRealPath()
}

/**
 * Removes a test's folder, trying again for a few seconds: on Windows a virus scanner reads a file the moment
 * a helper finishes writing it, and holds it open just long enough to make one attempt fail. What is left
 * after that is in the temporary folder, which is no failure of the test's.
 */
internal fun removeTestFolder(folder: Path) {
    repeat(40) {
        if (runCatching { UpdateFolders.deleteTree(folder) }.isSuccess) return
        Thread.sleep(100)
    }
}

internal fun octal(text: String) = text.toInt(8)

internal val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
