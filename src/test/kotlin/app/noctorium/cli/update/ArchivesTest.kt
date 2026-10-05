package app.noctorium.cli.update

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unpacker, against archives shaped like the release's and against the ways an archive can go wrong.
 *
 * Modes are recorded as well as applied, because Windows has none to apply: what matters on Linux and a Mac
 * is that the launcher and the runtime's programs come out executable, and the recording shows that the
 * mode reached the file system call whichever system the test runs on.
 */
class ArchivesTest {
    private val here = scratch("archives")

    @AfterTest
    fun clean() = removeTestFolder(here)

    private class Recorded : Archives.Modes {
        val modes = mutableMapOf<String, Int>()
        lateinit var root: Path
        override fun apply(path: Path, mode: Int, directory: Boolean) {
            modes[root.relativize(path).toString().replace('\\', '/')] = mode
            Archives.Modes.system.apply(path, mode, directory)
        }
    }

    private fun unpack(archive: Path, into: Path = here.resolve("out")): Pair<Path, Recorded> {
        val recorded = Recorded().also { it.root = into.toAbsolutePath().normalize() }
        Files.createDirectories(into)
        Archives.unpack(archive, into, recorded)
        return into to recorded
    }

    private val posix = "posix" in here.fileSystem.supportedFileAttributeViews()

    private fun symlinksWork(): Boolean = runCatching {
        val probe = here.resolve("probe-link")
        Files.createSymbolicLink(probe, Path.of("target"))
        Files.delete(probe)
        true
    }.getOrDefault(false)

    // --- Shaped like the release ---

    @Test
    fun `a tar_gz becomes its folder, nested folders and all, with the launcher still executable`() {
        val archive = TarWriter.tarGz(here.resolve("noctorium-cli-1.0.0-linux-x64.tar.gz")) {
            folder("noctorium-cli/")
            folder("noctorium-cli/bin/")
            file("noctorium-cli/bin/noctorium", "#!/bin/sh\necho one\n", octal("755"))
            folder("noctorium-cli/lib/app/")
            file("noctorium-cli/lib/app/noctorium.cfg", "[Application]\n", octal("644"))
            file("noctorium-cli/lib/runtime/bin/java", "ELF", octal("755"))
            file("noctorium-cli/lib/runtime/lib/jspawnhelper", "ELF", octal("755"))
        }
        val (out, recorded) = unpack(archive)

        val root = Archives.singleFolderIn(out)!!
        assertEquals("noctorium-cli", root.fileName.toString())
        assertEquals("#!/bin/sh\necho one\n", root.resolve("bin/noctorium").readText())
        assertEquals("[Application]\n", root.resolve("lib/app/noctorium.cfg").readText())
        assertTrue(Files.isRegularFile(root.resolve("lib/runtime/lib/jspawnhelper")))
        assertEquals(octal("755"), recorded.modes["noctorium-cli/bin/noctorium"])
        assertEquals(octal("755"), recorded.modes["noctorium-cli/lib/runtime/bin/java"])
        assertEquals(octal("755"), recorded.modes["noctorium-cli/lib/runtime/lib/jspawnhelper"])
        assertEquals(octal("644"), recorded.modes["noctorium-cli/lib/app/noctorium.cfg"])
        if (posix) {
            assertTrue(Files.isExecutable(root.resolve("bin/noctorium")), "the launcher lost its executable bit")
            assertFalse(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(root.resolve("lib/app/noctorium.cfg")))
        }
    }

    @Test
    fun `a zip becomes its folder, and keeps the modes it recorded`() {
        val archive = TestZip.write(
            here.resolve("noctorium-cli-1.0.0-windows-x64.zip"),
            listOf(
                Triple("noctorium-cli/", null, octal("40755")),
                Triple("noctorium-cli/noctorium.exe", "MZ", octal("100755")),
                Triple("noctorium-cli/app/noctorium.cfg", "[Application]", octal("100644")),
                Triple("noctorium-cli/runtime/bin/server/jvm.dll", "MZ", null),
                Triple("noctorium-cli/runtime/release", "JAVA_VERSION=\"21\"", null),
            ),
        )
        assertEquals(octal("100755"), Archives.zipUnixModes(archive)["noctorium-cli/noctorium.exe"])
        val (out, recorded) = unpack(archive)

        val root = Archives.singleFolderIn(out)!!
        assertEquals("MZ", root.resolve("noctorium.exe").readText())
        assertEquals("[Application]", root.resolve("app/noctorium.cfg").readText())
        assertTrue(Files.isRegularFile(root.resolve("runtime/bin/server/jvm.dll")), "a file three folders down went missing")
        assertEquals(octal("755"), recorded.modes["noctorium-cli/noctorium.exe"])
        assertEquals(octal("644"), recorded.modes["noctorium-cli/app/noctorium.cfg"])
        // Nothing recorded, nothing changed: a zip made on Windows says nothing about modes.
        assertNull(recorded.modes["noctorium-cli/runtime/release"])
    }

    @Test
    fun `an archive with several things at the top is unpacked as it is`() {
        val archive = TarWriter.tarGz(here.resolve("flat.tar.gz")) {
            file("bin/noctorium", "x", octal("755"))
            file("lib/a.jar", "jar")
        }
        val (out, _) = unpack(archive)
        assertNull(Archives.singleFolderIn(out))
        assertTrue(Files.isRegularFile(out.resolve("bin/noctorium")))
    }

    // --- Long names, three ways ---

    @Test
    fun `long names are read however the archive wrote them`() {
        // Long, but with a folder boundary where ustar can split it into its 155-byte prefix and 100-byte name.
        val deep = "noctorium-cli/lib/runtime/legal/" + "a-module-with-a-long-name.".repeat(4) + "desktop/ASSEMBLY_EXCEPTION_and_more.txt"
        assertTrue(deep.length in 101..255, "the test's own name is ${deep.length} long")
        // Too long for ustar at all: only GNU's and pax's ways can name it.
        val deeper = "noctorium-cli/" + (1..20).joinToString("/") { "folder-number-$it" } + "/end.txt"
        assertTrue(deeper.length > 255)
        TarWriter.Style.entries.forEach { style ->
            val archive = TarWriter.tarGz(here.resolve("long-$style.tar.gz"), style) {
                file(deep, "deep $style")
                if (style != TarWriter.Style.USTAR) file(deeper, "deeper $style")
            }
            val (out, _) = unpack(archive, here.resolve("out-$style"))
            assertEquals("deep $style", out.resolve(deep).readText(), "$style lost the long name")
            if (style != TarWriter.Style.USTAR) assertEquals("deeper $style", out.resolve(deeper).readText(), "$style lost the very long name")
        }
    }

    @Test
    fun `a pax record is read whole, whatever its length and its letters`() {
        // Each length counts the record's bytes, its own digits included -- and ü is two bytes.
        val records = "33 path=noctorium-cli/Müsic.txt\n21 linkpath=../a/b/c\n".toByteArray()
        val parsed = Archives.paxRecords(records)
        assertEquals("noctorium-cli/Müsic.txt", parsed["path"])
        assertEquals("../a/b/c", parsed["linkpath"])
    }

    /**
     * The same, made by the real thing: bsdtar, which is what a Mac's tar is and what Windows ships, or GNU
     * tar, which is Linux's. Skipped where neither is to be found.
     */
    @Test
    fun `a tarball made by the system's own tar unpacks the same`() {
        val tar = listOfNotNull(
            Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "tar.exe").takeIf { isWindows && Files.isRegularFile(it) }?.toString(),
            "/usr/bin/tar".takeIf { Files.isExecutable(Path.of(it)) },
            "/bin/tar".takeIf { Files.isExecutable(Path.of(it)) },
        ).firstOrNull()
        assumeTrue(tar != null, "no tar here")
        val source = here.resolve("source")
        val deep = "noctorium-cli/lib/runtime/legal/" + "long-folder-name-".repeat(6) + "/LICENSE"
        Files.createDirectories(source.resolve(deep).parent)
        source.resolve(deep).writeText("licence")
        Files.createDirectories(source.resolve("noctorium-cli/bin"))
        source.resolve("noctorium-cli/bin/noctorium").writeText("#!/bin/sh\n")
        if (posix) Files.setPosixFilePermissions(source.resolve("noctorium-cli/bin/noctorium"), PosixFilePermission.values().toSet())
        // bsdtar calls GNU's format gnutar, and GNU tar calls it gnu.
        val bsd = runCatching {
            ProcessBuilder(tar, "--version").redirectErrorStream(true).start().inputStream.readAllBytes().decodeToString()
        }.getOrDefault("").contains("bsdtar")
        listOf("pax", "ustar", "gnutar").forEach { format ->
            val archive = here.resolve("system-$format.tar.gz")
            val gnuName = if (format == "gnutar" && !bsd) "gnu" else format
            val made = ProcessBuilder(tar, "--format=$gnuName", "-czf", archive.toString(), "-C", source.toString(), "noctorium-cli")
                .redirectErrorStream(true).start()
            val said = made.inputStream.readAllBytes().decodeToString()
            assertTrue(made.waitFor(60, TimeUnit.SECONDS))
            if (made.exitValue() != 0) {
                // ustar cannot hold a name this long, and says so; the other two must.
                assertEquals("ustar", format, "tar --format=$gnuName failed: $said")
                return@forEach
            }
            val (out, recorded) = unpack(archive, here.resolve("system-out-$format"))
            assertEquals("licence", out.resolve(deep).readText(), "$format from $tar")
            assertEquals("#!/bin/sh\n", out.resolve("noctorium-cli/bin/noctorium").readText())
            if (posix) assertEquals(octal("755"), recorded.modes["noctorium-cli/bin/noctorium"]!! and octal("755"))
        }
    }

    // --- Links ---

    @Test
    fun `links inside the folder are made last, and hard links become copies`() {
        val archive = TarWriter.tarGz(here.resolve("links.tar.gz")) {
            // jlink's runtime: every module's licence is a link to java.base's copy. The link comes first
            // here, before what it points at exists, which a real archive is free to do.
            symlink("noctorium-cli/lib/runtime/legal/java.desktop/LICENSE", "../java.base/LICENSE")
            file("noctorium-cli/lib/runtime/legal/java.base/LICENSE", "GPLv2 with the classpath exception")
            hardlink("noctorium-cli/lib/runtime/legal/java.sql/LICENSE", "noctorium-cli/lib/runtime/legal/java.base/LICENSE")
        }
        if (!symlinksWork()) {
            // Windows without the right to make links: the archive is refused cleanly rather than half made.
            assertFailsWith<Exception> { unpack(archive) }
            return
        }
        val (out, _) = unpack(archive)
        val link = out.resolve("noctorium-cli/lib/runtime/legal/java.desktop/LICENSE")
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("GPLv2 with the classpath exception", link.readText())
        val copy = out.resolve("noctorium-cli/lib/runtime/legal/java.sql/LICENSE")
        assertFalse(Files.isSymbolicLink(copy))
        assertEquals("GPLv2 with the classpath exception", copy.readText())
    }

    @Test
    fun `a link out of the folder is refused, and nothing is written through it`() {
        val outside = here.resolve("outside")
        Files.createDirectories(outside)
        val cases = listOf(
            "climbing" to "../../outside",
            "absolute" to outside.toString(),
            "rooted" to "/etc",
        )
        cases.forEach { (label, target) ->
            val archive = TarWriter.tarGz(here.resolve("link-$label.tar.gz")) {
                symlink("noctorium-cli/escape", target)
                file("noctorium-cli/escape/planted.txt", "should never land outside")
            }
            assertFailsWith<Archives.Refused>(label) { unpack(archive, here.resolve("out-$label")) }
            assertFalse(Files.exists(outside.resolve("planted.txt")), "$label wrote through the link")
        }
    }

    // --- Refusals ---

    @Test
    fun `an entry that climbs out of the folder is refused, in either kind of archive`() {
        val names = listOf("../escaped.txt", "noctorium-cli/../../escaped.txt", "/etc/escaped.txt", "C:\\escaped.txt", "C:/escaped.txt")
        names.forEachIndexed { index, name ->
            val tar = TarWriter.tarGz(here.resolve("climb-$index.tar.gz")) { file(name, "nope") }
            assertFailsWith<Archives.Refused>(name) { unpack(tar, here.resolve("tar-out-$index")) }
            val zip = TestZip.write(here.resolve("climb-$index.zip"), listOf(Triple(name, "nope", null)))
            assertFailsWith<Archives.Refused>(name) { unpack(zip, here.resolve("zip-out-$index")) }
        }
        assertFalse(Files.exists(here.resolve("escaped.txt")))
        assertFalse(Files.exists(here.parent.resolve("escaped.txt")))
    }

    @Test
    fun `a damaged archive is refused rather than half unpacked into something that runs`() {
        val good = TarWriter.tarGz(here.resolve("good.tar.gz")) { file("noctorium-cli/bin/noctorium", "x".repeat(2000), octal("755")) }
        // The same archive, cut off in the middle of the file.
        val bytes = java.util.zip.GZIPInputStream(Files.newInputStream(good)).readAllBytes()
        val cut = here.resolve("cut.tar.gz")
        java.util.zip.GZIPOutputStream(Files.newOutputStream(cut)).use { it.write(bytes, 0, 1200) }
        assertFailsWith<Exception> { unpack(cut, here.resolve("cut-out")) }
        // And one whose header does not add up.
        bytes[10] = (bytes[10] + 1).toByte()
        val damaged = here.resolve("damaged.tar.gz")
        java.util.zip.GZIPOutputStream(Files.newOutputStream(damaged)).use { it.write(bytes) }
        assertFailsWith<Archives.Refused> { unpack(damaged, here.resolve("damaged-out")) }
        // And something that is not an archive at all.
        val text = here.resolve("notes.rar").also { it.writeText("x") }
        assertFailsWith<Archives.Refused> { unpack(text, here.resolve("rar-out")) }
    }

    @Test
    fun `devices and pipes are skipped, and what a Mac adds is left out`() {
        val archive = TarWriter.tarGz(here.resolve("odd.tar.gz")) {
            folder("noctorium-cli/")
            file("noctorium-cli/bin/noctorium", "x", octal("755"))
            raw("noctorium-cli/fifo", '6')
            raw("noctorium-cli/null", '3')
            file("noctorium-cli/bin/._noctorium", "apple double")
            file("noctorium-cli/.DS_Store", "finder")
            file("._noctorium-cli", "apple double for the folder itself")
        }
        val (out, _) = unpack(archive)
        val root = Archives.singleFolderIn(out)
        assertEquals("noctorium-cli", root?.fileName?.toString(), "the Mac's ._ file hid the single folder")
        assertTrue(Files.isRegularFile(root!!.resolve("bin/noctorium")))
        listOf("fifo", "null", "bin/._noctorium", ".DS_Store").forEach {
            assertFalse(Files.exists(root.resolve(it), LinkOption.NOFOLLOW_LINKS), "$it was unpacked")
        }
    }

    @Test
    fun `whatever an archive says, the owner can still write what it unpacked`() {
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE),
            Archives.permissionsFor(octal("755"), directory = false),
        )
        // Read-only in the archive is still removable by the next update.
        assertTrue(PosixFilePermission.OWNER_WRITE in Archives.permissionsFor(octal("444"), directory = false))
        // A folder its owner cannot enter or write to could never be emptied again.
        assertTrue(Archives.permissionsFor(octal("555"), directory = true).containsAll(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        ))
        // And set-user-id and the sticky bit are nothing a set of permissions can carry anyway.
        assertEquals(Archives.permissionsFor(octal("755"), false), Archives.permissionsFor(octal("4755"), false))
    }
}
