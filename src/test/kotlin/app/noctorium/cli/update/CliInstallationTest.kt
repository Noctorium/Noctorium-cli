package app.noctorium.cli.update

import app.noctorium.update.UpdateChannel
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which copies may replace themselves, and what the others are told instead. */
class CliInstallationTest {
    private val here = scratch("installation")

    @AfterTest
    fun clean() {
        runCatching { Files.walk(here).use { paths -> paths.forEach { it.toFile().setWritable(true) } } }
        removeTestFolder(here)
    }

    private val nothing: (String) -> String? = { null }

    /** A folder laid out like the release's, for [system]. */
    private fun installed(system: String, at: Path = here.resolve(if (system == "windows") "Noctorium CLI" else "noctorium-cli")): Path {
        when (system) {
            "windows" -> {
                Files.createDirectories(at.resolve("app"))
                at.resolve("noctorium.exe").writeText("MZ")
                at.resolve("app/noctorium-cli-0.9.1.jar").writeText("jar")
            }
            "linux" -> {
                Files.createDirectories(at.resolve("bin"))
                Files.createDirectories(at.resolve("lib/app"))
                at.resolve("bin/noctorium").writeText("ELF")
                at.resolve("lib/app/noctorium-cli-0.9.1.jar").writeText("jar")
            }
            else -> {
                Files.createDirectories(at.resolve("bin"))
                Files.createDirectories(at.resolve("lib"))
                at.resolve("bin/noctorium").writeText("#!/bin/sh")
                at.resolve("lib/noctorium-cli-0.9.1.jar").writeText("jar")
            }
        }
        return at
    }

    @Test
    fun `a run from Gradle has no folder of its own to replace`() {
        val found = CliInstallation.detect(CliInstallation.Facts("windows", launcherPath = null, folderProperty = null, environment = nothing))
        assertEquals(UpdateChannel.UNMANAGED, found.channel)
        assertNull(found.folder)
        assertTrue("build" in found.advice, found.advice)
        assertNull(found.folders)
    }

    @Test
    fun `an installed copy on each system is found from what its launcher says`() {
        val windows = installed("windows")
        val onWindows = CliInstallation.detect(CliInstallation.Facts("windows", windows.resolve("noctorium.exe").toString(), null, nothing))
        assertEquals(UpdateChannel.CLI_ARCHIVE, onWindows.channel, onWindows.advice)
        assertEquals(windows, onWindows.folder)
        assertEquals("noctorium.exe", onWindows.launcher)

        // On Linux the launcher is bin/noctorium, so the folder is the one above bin.
        val linux = installed("linux", here.resolve("share/noctorium-cli"))
        val onLinux = CliInstallation.detect(CliInstallation.Facts("linux", linux.resolve("bin/noctorium").toString(), null, nothing))
        assertEquals(UpdateChannel.CLI_ARCHIVE, onLinux.channel, onLinux.advice)
        assertEquals(linux, onLinux.folder)
        assertEquals("bin/noctorium", onLinux.launcher)

        // The Mac's launcher script says where its folder is outright.
        val mac = installed("macos", here.resolve("mac/noctorium-cli"))
        val onMac = CliInstallation.detect(CliInstallation.Facts("macos", null, mac.toString(), nothing))
        assertEquals(UpdateChannel.CLI_ARCHIVE, onMac.channel, onMac.advice)
        assertEquals(mac, onMac.folder)
        // Everything an update uses is beside the folder, so each step is a rename on one volume.
        assertEquals(mac.resolveSibling("noctorium-cli.update-next"), onMac.folders!!.next)
        assertEquals(mac.resolveSibling("noctorium-cli.old-update"), onMac.folders!!.old)
    }

    @Test
    fun `a folder that is not shaped like the release is left alone`() {
        val folder = here.resolve("something-else").also { Files.createDirectories(it) }
        val found = CliInstallation.detect(CliInstallation.Facts("windows", folder.resolve("noctorium.exe").toString(), null, nothing))
        assertEquals(UpdateChannel.UNMANAGED, found.channel)
        assertTrue("not laid out" in found.advice, found.advice)
    }

    @Test
    fun `a copy under Program Files belongs to whoever put it there`() {
        val programFiles = here.resolve("Program Files")
        val folder = installed("windows", programFiles.resolve("Noctorium CLI"))
        // As the folder will be read, through any link on the way: a Mac's temporary folder is under /var,
        // which is a link to /private/var.
        val real = programFiles.toRealPath().toString()
        val environment: (String) -> String? = { if (it == "ProgramFiles") real else null }
        val found = CliInstallation.detect(CliInstallation.Facts("windows", folder.resolve("noctorium.exe").toString(), null, environment))
        assertEquals(UpdateChannel.UNMANAGED, found.channel)
        assertTrue("belongs to the system" in found.advice, found.advice)
    }

    @Test
    fun `a build's own output is not replaced with the release`() {
        val folder = installed("windows", here.resolve("build/package/noctorium-cli"))
        val found = CliInstallation.detect(CliInstallation.Facts("windows", folder.resolve("noctorium.exe").toString(), null, nothing))
        assertEquals(UpdateChannel.UNMANAGED, found.channel)
        assertTrue("build" in found.advice, found.advice)
    }

    @Test
    fun `a folder this account cannot change is told how to update instead`() {
        assumeTrue("posix" in here.fileSystem.supportedFileAttributeViews(), "no Unix permissions to take away here")
        assumeTrue(System.getProperty("user.name") != "root", "root can write anywhere")
        val parent = here.resolve("locked").also { Files.createDirectories(it) }
        val folder = installed("linux", parent.resolve("noctorium-cli"))
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val found = CliInstallation.detect(CliInstallation.Facts("linux", folder.resolve("bin/noctorium").toString(), null, nothing))
            assertEquals(UpdateChannel.UNMANAGED, found.channel)
            assertTrue("cannot change" in found.advice, found.advice)
        } finally {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `the system's folders and the package managers' are recognised by place`() {
        val windowsEnvironment: (String) -> String? = {
            when (it) {
                "ProgramFiles" -> "C:\\Program Files"
                "ProgramFiles(x86)" -> "C:\\Program Files (x86)"
                "SystemRoot" -> "C:\\WINDOWS"
                else -> null
            }
        }
        fun windows(path: String) = CliInstallation.belongsToTheSystem(path, windows = true, environment = windowsEnvironment)
        assertTrue(windows("C:\\Program Files\\Noctorium CLI"))
        assertTrue(windows("c:\\program files (x86)\\Noctorium CLI"))
        assertTrue(windows("C:\\Windows\\Noctorium CLI"))
        // The installer's own place, and a name that only starts the same way, are a person's own.
        assertFalse(windows("C:\\Users\\Zoë\\AppData\\Local\\Programs\\Noctorium CLI"))
        assertFalse(windows("C:\\Program Filesystem\\Noctorium CLI"))

        fun unix(path: String) = CliInstallation.belongsToTheSystem(path, windows = false, environment = nothing)
        listOf("/usr/share/noctorium-cli", "/usr/local/noctorium-cli", "/opt/noctorium-cli", "/opt/homebrew/Cellar/noctorium-cli",
            "/nix/store/abc-noctorium-cli", "/snap/noctorium-cli/1", "/Applications/noctorium-cli").forEach {
            assertTrue(unix(it), "$it should belong to the system")
        }
        listOf("/home/sam/.local/share/noctorium-cli", "/Users/sam/.local/share/noctorium-cli", "/tmp/noctorium-cli",
            "/usrs/noctorium-cli", "/private/var/folders/xy/T/noctorium-cli").forEach {
            assertFalse(unix(it), "$it should be the person's own")
        }
    }
}
