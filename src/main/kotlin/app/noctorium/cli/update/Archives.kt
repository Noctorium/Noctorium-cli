package app.noctorium.cli.update

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Unpacking the terminal player's release archives: a .zip on Windows, a .tar.gz on Linux and the Mac.
 *
 * Written here rather than borrowed. The JDK reads zips and gzip but not tar, and a library for one
 * well-understood format is more weight than the format is. What has to be right is the short list of
 * things the archives hold -- folders, files with their executable bits, the long names a jlink runtime
 * runs to, and on Linux and the Mac the symbolic links jlink leaves in the runtime's legal folder -- and
 * the one thing no unpacker may ever do, whatever the archive says, which is write outside its folder.
 *
 * Links are made last, after every file and folder, and only to somewhere inside the folder. A link made
 * early could be written through by a later entry of the same name, which is how an archive that looks
 * harmless entry by entry puts a file wherever it likes.
 */
object Archives {

    /** An archive that cannot be unpacked as it stands, with a sentence that says why. */
    class Refused(message: String) : IOException(message)

    /** What the permissions of an unpacked entry are set with: Unix modes, where the file system has them. */
    fun interface Modes {
        fun apply(path: Path, mode: Int, directory: Boolean)

        companion object {
            /** The real thing, which does nothing on a file system with no Unix permissions -- Windows's. */
            val system = Modes { path, mode, directory -> setMode(path, mode, directory) }
        }
    }

    /** Unpacks [archive] into the existing, empty folder [into]. */
    fun unpack(archive: Path, into: Path, modes: Modes = Modes.system) {
        val name = archive.fileName.toString().lowercase()
        Files.createDirectories(into)
        val root = into.toAbsolutePath().normalize()
        when {
            name.endsWith(".zip") -> unzip(archive, root, modes)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                GZIPInputStream(BufferedInputStream(Files.newInputStream(archive), 1 shl 16), 1 shl 16).use { untar(it, root, modes) }
            name.endsWith(".tar") -> BufferedInputStream(Files.newInputStream(archive), 1 shl 16).use { untar(it, root, modes) }
            else -> throw Refused("${archive.fileName} is neither a .zip nor a .tar.gz, so it cannot be unpacked.")
        }
    }

    /**
     * The one folder an archive put at the top, if that is all it put there -- `noctorium-cli/`, which is how
     * the release is published. Null for an archive with several things at the top, which then all belong
     * inside the folder as they are.
     */
    fun singleFolderIn(folder: Path): Path? {
        val top = Files.list(folder).use { it.toList() }
        return top.singleOrNull()?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
    }

    // --- Where an entry may go ---

    /**
     * An entry's name as a path inside the folder, or null for the folder itself.
     *
     * Refused outright, rather than tidied up, when it is absolute or climbs out with `..`: an archive that
     * says either is broken or hostile, and neither is one to unpack the rest of.
     */
    internal fun relativeName(name: String): String? {
        val slashed = name.replace('\\', '/')
        if (slashed.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(slashed)) {
            throw Refused("The archive contains $name, an absolute path. Refusing to unpack it.")
        }
        val parts = slashed.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw Refused("The archive contains $name, which points outside its folder. Refusing to unpack it.")
        return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * The metadata a Mac's tar and Finder put beside files: `._name` for extended attributes, `__MACOSX/` in
     * a zip, and `.DS_Store`. None of it is part of the program, and unpacked it is only litter in the folder.
     */
    internal fun isMacLitter(relative: String): Boolean {
        val parts = relative.split('/')
        return parts.first() == "__MACOSX" || parts.last().startsWith("._") || parts.last() == ".DS_Store"
    }

    private fun target(root: Path, relative: String): Path {
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root)) throw Refused("The archive contains $relative, which points outside its folder.")
        return path
    }

    /** A link's target, which must stay inside the folder once it is followed from where the link is. */
    private fun checkLink(root: Path, at: Path, linkTarget: String) {
        val slashed = linkTarget.replace('\\', '/')
        if (slashed.isEmpty() || slashed.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(slashed)) {
            throw Refused("The archive links ${root.relativize(at)} to $linkTarget, outside its folder. Refusing to unpack it.")
        }
        val followed = at.parent.resolve(slashed).normalize()
        if (!followed.startsWith(root)) {
            throw Refused("The archive links ${root.relativize(at)} to $linkTarget, outside its folder. Refusing to unpack it.")
        }
    }

    // --- Writing ---

    private class PendingLink(val at: Path, val target: String)

    private fun writeFile(path: Path, data: InputStream, mode: Int?, modes: Modes) {
        Files.createDirectories(path.parent)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw Refused("The archive has both a folder and a file at ${path.fileName}.")
        }
        Files.copy(data, path, StandardCopyOption.REPLACE_EXISTING)
        if (mode != null) modes.apply(path, mode, directory = false)
    }

    private fun makeFolder(path: Path, mode: Int?, modes: Modes) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw Refused("The archive has both a file and a folder at ${path.fileName}.")
        }
        Files.createDirectories(path)
        if (mode != null) modes.apply(path, mode, directory = true)
    }

    private fun makeLinks(root: Path, links: List<PendingLink>) {
        for (link in links) {
            checkLink(root, link.at, link.target)
            Files.createDirectories(link.at.parent)
            if (Files.exists(link.at, LinkOption.NOFOLLOW_LINKS)) throw Refused("The archive has two entries at ${root.relativize(link.at)}.")
            try {
                Files.createSymbolicLink(link.at, link.at.fileSystem.getPath(link.target))
            } catch (unsupported: UnsupportedOperationException) {
                throw Refused("This file system cannot hold the links the archive contains.")
            } catch (exists: FileAlreadyExistsException) {
                throw Refused("The archive has two entries at ${root.relativize(link.at)}.")
            }
        }
    }

    // --- Zip ---

    private fun unzip(archive: Path, root: Path, modes: Modes) {
        // java.util.zip reads the names and the bytes; the Unix modes, which it does not expose, come from the
        // central directory, read separately. A zip made on Windows records none, and nothing is changed.
        val unixModes = runCatching { zipUnixModes(archive) }.getOrDefault(emptyMap())
        val links = mutableListOf<PendingLink>()
        ZipFile(archive.toFile(), StandardCharsets.UTF_8).use { zip ->
            for (entry in zip.entries()) {
                val relative = relativeName(entry.name) ?: continue
                if (isMacLitter(relative)) continue
                val path = target(root, relative)
                val mode = unixModes[entry.name]
                when {
                    entry.isDirectory -> makeFolder(path, mode?.and(0xFFF), modes)
                    mode != null && mode and S_IFMT == S_IFLNK ->
                        links += PendingLink(path, zip.getInputStream(entry).use { String(it.readBytes(), StandardCharsets.UTF_8) })
                    else -> zip.getInputStream(entry).use { writeFile(path, it, mode?.and(0xFFF), modes) }
                }
            }
        }
        makeLinks(root, links)
    }

    /**
     * Each entry's Unix mode, from the zip's central directory: the top half of the external attributes, for
     * an entry whose creator says it was made on Unix.
     */
    internal fun zipUnixModes(archive: Path): Map<String, Int> = RandomAccessFile(archive.toFile(), "r").use { file ->
        val length = file.length()
        val tailLength = minOf(length, 22L + 0xFFFF).toInt()
        val tail = ByteArray(tailLength)
        file.seek(length - tailLength)
        file.readFully(tail)
        val end = (tailLength - 22 downTo 0).firstOrNull { le32(tail, it) == 0x06054b50L } ?: return emptyMap()
        var count = le16(tail, end + 10).toLong()
        var size = le32(tail, end + 12)
        var offset = le32(tail, end + 16)
        if (offset == 0xFFFFFFFFL || size == 0xFFFFFFFFL || count == 0xFFFFL) {
            // Zip64: the real numbers are in a second record, which a locator just before this one points to.
            val locator = end - 20
            if (locator < 0 || le32(tail, locator) != 0x07064b50L) return emptyMap()
            val record = ByteArray(56)
            file.seek(le64(tail, locator + 8))
            file.readFully(record)
            if (le32(record, 0) != 0x06064b50L) return emptyMap()
            count = le64(record, 32)
            size = le64(record, 40)
            offset = le64(record, 48)
        }
        if (size > 64L * 1024 * 1024 || offset + size > length) return emptyMap()
        val directory = ByteArray(size.toInt())
        file.seek(offset)
        file.readFully(directory)
        val modes = HashMap<String, Int>()
        var at = 0
        repeat(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            if (at + 46 > directory.size || le32(directory, at) != 0x02014b50L) return modes
            val madeOn = le16(directory, at + 4) ushr 8
            val nameLength = le16(directory, at + 28)
            val extraLength = le16(directory, at + 30)
            val commentLength = le16(directory, at + 32)
            val external = le32(directory, at + 38)
            val name = String(directory, at + 46, nameLength, StandardCharsets.UTF_8)
            val mode = (external ushr 16).toInt() and 0xFFFF
            if (madeOn == UNIX_HOST && mode != 0) modes[name] = mode
            at += 46 + nameLength + extraLength + commentLength
        }
        modes
    }

    // --- Tar ---

    /**
     * Reads a tar stream: POSIX ustar, with GNU's long names and pax extended headers on top, which between
     * them are everything GNU tar on Linux and bsdtar on a Mac write.
     */
    private fun untar(stream: InputStream, root: Path, modes: Modes) {
        val links = mutableListOf<PendingLink>()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        while (true) {
            if (!readBlock(stream, header)) break
            if (header.all { it == 0.toByte() }) break
            if (!checksumFits(header)) throw Refused("The archive is damaged: an entry's header does not add up.")
            val type = header[156].toInt().toChar()
            val headerSize = number(header, 124, 12)
            when (type) {
                // GNU's long names: the next entry's name or link target, as this entry's data.
                'L' -> { longName = readString(stream, headerSize); continue }
                'K' -> { longLink = readString(stream, headerSize); continue }
                // pax: key=value records that override the next entry's header. 'g' applies to every entry
                // and says nothing this needs.
                'x' -> { pax = paxRecords(readBytes(stream, headerSize)); continue }
                'g' -> { skip(stream, headerSize + padding(headerSize)); continue }
            }
            val size = pax["size"]?.toLongOrNull() ?: headerSize
            val headerName = run {
                val name = string(header, 0, 100)
                val ustar = string(header, 257, 6) == "ustar"
                // The prefix field only exists in POSIX ustar; GNU's own format keeps times in those bytes.
                val prefix = if (ustar && header[262] == 0.toByte()) string(header, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$name" else name
            }
            val name = pax["path"] ?: longName ?: headerName
            val linkTarget = pax["linkpath"] ?: longLink ?: string(header, 157, 100)
            val mode = number(header, 100, 8).toInt() and 0xFFF
            longName = null
            longLink = null
            pax = emptyMap()

            val relative = relativeName(name)
            if (relative == null || isMacLitter(relative)) {
                if (type != '5' && type != '1' && type != '2') skip(stream, size + padding(size))
                continue
            }
            val path = target(root, relative)
            when (type) {
                '0', '\u0000', '7' -> {
                    writeFile(path, Limited(stream, size), mode, modes)
                    skip(stream, padding(size))
                }
                '5' -> makeFolder(path, mode, modes)
                '2' -> links += PendingLink(path, linkTarget)
                // A hard link names another entry of the archive, which has already been written. A copy of
                // it does the same job and cannot be pointed anywhere.
                '1' -> {
                    val source = target(root, relativeName(linkTarget) ?: throw Refused("The archive links $relative to nothing."))
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw Refused("The archive links $relative to $linkTarget, which it does not contain.")
                    Files.createDirectories(path.parent)
                    Files.copy(source, path, StandardCopyOption.REPLACE_EXISTING)
                    modes.apply(path, mode, directory = false)
                }
                // Devices and pipes have no place in a music player, and sparse or multi-volume files are
                // formats nothing here writes.
                '3', '4', '6' -> skip(stream, size + padding(size))
                'S', 'M' -> throw Refused("The archive uses a kind of entry this cannot unpack ($type).")
                else -> skip(stream, size + padding(size))
            }
        }
        makeLinks(root, links)
    }

    private fun readBlock(stream: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = stream.read(into, read, into.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw Refused("The archive ends partway through an entry.")
            }
            read += n
        }
        return true
    }

    private fun readBytes(stream: InputStream, size: Long): ByteArray {
        if (size > 1 shl 20) throw Refused("The archive has an entry header too large to be one.")
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = stream.read(bytes, read, bytes.size - read)
            if (n < 0) throw Refused("The archive ends partway through an entry.")
            read += n
        }
        skip(stream, padding(size))
        return bytes
    }

    private fun readString(stream: InputStream, size: Long): String =
        String(readBytes(stream, size), StandardCharsets.UTF_8).substringBefore('\u0000')

    private fun skip(stream: InputStream, count: Long) {
        var left = count
        val sink = ByteArray(8192)
        while (left > 0) {
            val n = stream.read(sink, 0, minOf(left, sink.size.toLong()).toInt())
            if (n < 0) throw Refused("The archive ends partway through an entry.")
            left -= n
        }
    }

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    /** "length key=value\n", repeated: the length counts the whole record, its own digits included. */
    internal fun paxRecords(bytes: ByteArray): Map<String, String> {
        val records = HashMap<String, String>()
        var at = 0
        while (at < bytes.size) {
            val space = (at until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() } ?: break
            val length = String(bytes, at, space - at, StandardCharsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || at + length > bytes.size) break
            val record = String(bytes, space + 1, at + length - space - 2, StandardCharsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals > 0) records[record.substring(0, equals)] = record.substring(equals + 1)
            at += length
        }
        return records
    }

    private fun string(header: ByteArray, from: Int, length: Int): String {
        var end = from
        while (end < from + length && header[end] != 0.toByte()) end++
        return String(header, from, end - from, StandardCharsets.UTF_8)
    }

    /** An octal field, or GNU's base-256 for a number too large for its octal digits. */
    private fun number(header: ByteArray, from: Int, length: Int): Long {
        if (header[from].toInt() and 0x80 != 0) {
            var value = (header[from].toLong() and 0x7F)
            for (i in from + 1 until from + length) value = (value shl 8) or (header[i].toLong() and 0xFF)
            return value
        }
        val text = string(header, from, length).trim().trimEnd(' ')
        return if (text.isEmpty()) 0 else text.toLongOrNull(8) ?: throw Refused("The archive is damaged: a number in it is not one.")
    }

    /** The header's own checksum: its bytes added up, with the checksum field counted as spaces. */
    private fun checksumFits(header: ByteArray): Boolean {
        val stored = string(header, 148, 8).trim().toLongOrNull(8) ?: return false
        var unsigned = 0L
        var signed = 0L
        for (i in header.indices) {
            val byte = if (i in 148 until 156) ' '.code.toByte() else header[i]
            unsigned += byte.toLong() and 0xFF
            signed += byte.toLong()
        }
        return stored == unsigned || stored == signed
    }

    /** At most [left] bytes of [stream], so a file's copy stops at the end of its entry. */
    private class Limited(private val stream: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val value = stream.read()
            if (value < 0) throw EOFException("The archive ends partway through a file.")
            left--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val n = stream.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n < 0) throw EOFException("The archive ends partway through a file.")
            left -= n
            return n
        }
    }

    // --- Little-endian numbers, for the zip directory ---

    private fun le16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(bytes: ByteArray, at: Int): Long = (le16(bytes, at).toLong()) or (le16(bytes, at + 2).toLong() shl 16)
    private fun le64(bytes: ByteArray, at: Int): Long = le32(bytes, at) or (le32(bytes, at + 4) shl 32)

    private const val BLOCK = 512
    private const val UNIX_HOST = 3
    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000

    /**
     * Sets [mode] on [path], keeping its read and execute bits and making sure its owner can still write it:
     * whatever an archive says, the next update has to be able to remove this folder again. The set-user and
     * sticky bits are dropped, as nothing a music player ships has any business carrying them.
     */
    private fun setMode(path: Path, mode: Int, directory: Boolean) {
        // Windows: there are no such bits, and nothing there needs them.
        if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
        Files.setPosixFilePermissions(path, permissionsFor(mode, directory))
    }

    /** The permissions [setMode] gives an entry with [mode]: see there. */
    internal fun permissionsFor(mode: Int, directory: Boolean): Set<PosixFilePermission> {
        val wanted = (mode and 0x1FF) or (if (directory) 0x1C0 else 0x180)
        return buildSet { PERMISSION_BITS.forEach { (bit, permission) -> if (wanted and bit != 0) add(permission) } }
    }

    private val PERMISSION_BITS = listOf(
        0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE, 0x40 to PosixFilePermission.OWNER_EXECUTE,
        0x20 to PosixFilePermission.GROUP_READ, 0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
        0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE, 0x1 to PosixFilePermission.OTHERS_EXECUTE,
    )
}
