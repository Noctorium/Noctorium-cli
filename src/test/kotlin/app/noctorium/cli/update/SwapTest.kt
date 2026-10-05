package app.noctorium.cli.update

import app.noctorium.update.Version
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Putting the new copy in place: staging it beside the folder, tidying what an interrupted update left, and
 * the helpers that do the swap, run for real against folders in the temporary directory.
 *
 * The helpers are given a process id that has already ended to wait for, which is the moment they would
 * normally reach once the player had quit.
 */
class SwapTest {
    private val here = scratch("swap")

    @AfterTest
    fun clean() = removeTestFolder(here)

    /** A copy of the player, of [version], laid out the way this system's release is. */
    private fun copyAt(folder: Path, version: String, extra: String? = null): Path {
        Files.createDirectories(folder)
        if (isWindows) {
            Files.createDirectories(folder.resolve("app"))
            folder.resolve("noctorium.exe").writeText("launcher $version")
            folder.resolve("app/noctorium-cli-$version.jar").writeText("jar $version")
        } else {
            Files.createDirectories(folder.resolve("bin"))
            Files.createDirectories(folder.resolve("lib/app"))
            folder.resolve("bin/noctorium").writeText("launcher $version")
            folder.resolve("bin/noctorium").toFile().setExecutable(true)
            folder.resolve("lib/app/noctorium-cli-$version.jar").writeText("jar $version")
        }
        extra?.let { folder.resolve(it).writeText("only in $version") }
        return folder
    }

    private val launcher = if (isWindows) "noctorium.exe" else "bin/noctorium"

    private fun launcherText(folder: Path) = folder.resolve(launcher).readText()

    /** The release's archive for [version], shaped as this system's is. */
    private fun archive(version: String, launcherAt: String = launcher, jarVersion: String = version): Path {
        val system = if (isWindows) "windows" else "linux"
        return TarWriter.tarGz(here.resolve("noctorium-cli-$version-$system-x64.tar.gz")) {
            folder("noctorium-cli/")
            file("noctorium-cli/$launcherAt", "launcher $version", octal("755"))
            val app = if (isWindows) "app" else "lib/app"
            file("noctorium-cli/$app/noctorium-cli-$jarVersion.jar", "jar $version")
            file("noctorium-cli/$app/noctorium.cfg", "[Application]")
        }
    }

    private fun deadPid(): Long {
        val process = (if (isWindows) ProcessBuilder("cmd", "/c", "exit", "0") else ProcessBuilder("true")).start()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.pid()
    }

    private fun waitUntil(seconds: Long, what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("Waited $seconds s for $what")
            Thread.sleep(100)
        }
    }

    // --- Staging ---

    @Test
    fun `an archive is unpacked beside the folder and left waiting, with the old copy untouched`() {
        val folder = copyAt(here.resolve("Noctorium CLI"), "0.9.0")
        val folders = UpdateFolders(folder)

        assertNull(folders.stage(archive("0.9.2"), launcher, isWindows))

        assertEquals("launcher 0.9.2", launcherText(folders.next))
        assertEquals(Version.parse("0.9.2"), folders.waiting())
        assertEquals("launcher 0.9.0", launcherText(folder), "the running copy was changed")
        // Nothing left of the unpacking itself.
        val siblings = Files.list(here).use { it.map { p -> p.fileName.toString() }.toList() }
        assertTrue(siblings.none { Regex("""\.update-\d+$""").containsMatchIn(it) }, "left behind: $siblings")
    }

    /**
     * noctorium-installer-cli swaps the same folder when it installs, through `<name>.partial` and
     * `<name>.old`, and leaves anything named `<name>.update-*` or `<name>.old-*` alone. The updater keeps to
     * its side of that, both in what it names and in what it tidies away.
     */
    @Test
    fun `the updater's names and the installer's never meet`() {
        val folder = copyAt(here.resolve("Noctorium CLI"), "0.9.0")
        val folders = UpdateFolders(folder)
        val ours = listOf(folders.next, folders.old, folders.lock, folders.unpacking(1234)).map { it.fileName.toString() }
        assertTrue(ours.all { it.startsWith("Noctorium CLI.update-") || it.startsWith("Noctorium CLI.old-") }, "$ours")
        assertTrue(ours.none { it == "Noctorium CLI.partial" || it == "Noctorium CLI.old" }, "$ours")

        val installers = listOf(here.resolve("Noctorium CLI.partial"), here.resolve("Noctorium CLI.old"))
        installers.forEach { copyAt(it, "0.8.0") }
        folders.tidy(Version.parse("0.9.0"), busy = { false })
        installers.forEach { assertTrue(it.exists(), "tidying took the installer's $it") }
    }

    @Test
    fun `an archive whose launcher is not where the old copy has it is refused, and leaves nothing`() {
        val folder = copyAt(here.resolve("Noctorium CLI"), "0.9.0")
        val folders = UpdateFolders(folder)
        val moved = archive("0.9.2", launcherAt = if (isWindows) "bin/noctorium.exe" else "noctorium")

        val problem = folders.stage(moved, launcher, isWindows)

        assertNotNull(problem)
        assertTrue(launcher in problem, problem)
        assertFalse(folders.next.exists())
        assertEquals("launcher 0.9.0", launcherText(folder))
    }

    @Test
    fun `an archive that holds a different version from the one its name promises is refused`() {
        val folders = UpdateFolders(copyAt(here.resolve("Noctorium CLI"), "0.9.0"))
        val problem = folders.stage(archive("0.9.2", jarVersion = "0.9.1"), launcher, isWindows)
        assertNotNull(problem)
        assertTrue("0.9.1" in problem && "0.9.2" in problem, problem)
        assertFalse(folders.next.exists())
    }

    @Test
    fun `a damaged archive is refused with a sentence, and nothing is left waiting`() {
        val folders = UpdateFolders(copyAt(here.resolve("Noctorium CLI"), "0.9.0"))
        val broken = here.resolve("noctorium-cli-0.9.2-linux-x64.tar.gz").also { it.writeText("not gzip at all") }
        val problem = folders.stage(broken, launcher, isWindows)
        assertNotNull(problem)
        assertFalse(folders.next.exists())
    }

    @Test
    fun `tidying clears what an interrupted update left, and keeps a newer copy waiting`() {
        val folder = copyAt(here.resolve("Noctorium CLI"), "0.9.0")
        val folders = UpdateFolders(folder)
        val dead = deadPid()
        copyAt(folders.unpacking(dead), "0.9.2")
        copyAt(folders.unpacking(ProcessHandle.current().pid()), "0.9.2")
        copyAt(folders.old, "0.8.0")
        copyAt(folders.next, "0.9.2")
        Files.writeString(folders.lock, "")
        Files.setLastModifiedTime(folders.lock, FileTime.from(Instant.now().minusSeconds(3600)))

        val waiting = folders.tidy(Version.parse("0.9.0"), busy = { false })

        assertEquals(Version.parse("0.9.2"), waiting)
        assertFalse(folders.unpacking(dead).exists(), "an unpacking whose process is gone was kept")
        assertTrue(folders.unpacking(ProcessHandle.current().pid()).exists(), "a live process's unpacking was taken from it")
        assertFalse(folders.old.exists())
        assertFalse(folders.lock.exists(), "a lock nobody has held for an hour was kept")
        assertTrue(folders.next.exists())
        assertEquals("launcher 0.9.0", launcherText(folder))
    }

    @Test
    fun `a copy waiting that is not newer than the running one is stale, and goes`() {
        val folders = UpdateFolders(copyAt(here.resolve("Noctorium CLI"), "0.9.2"))
        copyAt(folders.next, "0.9.2")
        assertNull(folders.tidy(Version.parse("0.9.2"), busy = { false }))
        assertFalse(folders.next.exists(), "swapping this in would replace a version with itself, or with an older one")
    }

    @Test
    fun `a replaced copy something still runs from is left until it is not`() {
        val folders = UpdateFolders(copyAt(here.resolve("Noctorium CLI"), "0.9.2"))
        copyAt(folders.old, "0.9.0")
        folders.tidy(Version.parse("0.9.2"), busy = { it == folders.old })
        assertTrue(folders.old.exists())
        folders.tidy(Version.parse("0.9.2"), busy = { false })
        assertFalse(folders.old.exists())
    }

    @Test
    fun `the version in an archive's name is read as the release names it`() {
        assertEquals(Version.parse("0.9.2"), UpdateFolders.versionInArchiveName("noctorium-cli-0.9.2-linux-x64.tar.gz"))
        assertEquals(Version.parse("1.0.0-beta.1"), UpdateFolders.versionInArchiveName("noctorium-cli-1.0.0-beta.1-windows-x64.zip"))
        assertEquals(Version.parse("0.9.2"), UpdateFolders.versionInArchiveName("noctorium-cli-0.9.2-macos-arm64.tar.gz"))
        assertNull(UpdateFolders.versionInArchiveName("noctorium-installer-cli-linux-x64"))
    }

    // --- The Windows helper, for real ---

    @Test
    fun `on Windows the helper swaps the folders, whatever the folder is called`() {
        assumeTrue(isWindows, "PowerShell is Windows's")
        // A space, accents, an apostrophe and typographic quotes, in the folder above and in the folder itself.
        listOf(
            here.resolve("Zoë's Müsic ‘quoted’").resolve("Noctorium CLI"),
            here.resolve("plain").resolve("O'Brien’s Noctorium CLI"),
        ).forEach { folder ->
            copyAt(folder, "0.9.0", extra = "old-only.txt")
            val folders = UpdateFolders(folder)
            assertNull(folders.stage(archive("0.9.2"), launcher, windows = true))

            val helper = SwapHelper.launch(folders, listOf(deadPid()), windows = true, log = here.resolve("helper.log"))
            assertTrue(helper.waitFor(120, TimeUnit.SECONDS), "the helper never finished")

            assertEquals("launcher 0.9.2", launcherText(folder), "not swapped in $folder; the helper said: ${log()}")
            assertFalse(folder.resolve("old-only.txt").exists(), "a file only the old copy had survived")
            assertFalse(folders.next.exists())
            assertFalse(folders.old.exists())
            assertFalse(folders.lock.exists())
        }
    }

    @Test
    fun `on Windows the helper waits while anything runs out of the folder`() {
        assumeTrue(isWindows, "PowerShell is Windows's")
        val folder = copyAt(here.resolve("Noctorium CLI"), "0.9.0")
        val folders = UpdateFolders(folder)
        assertNull(folders.stage(archive("0.9.2"), launcher, windows = true))
        // A second player in another terminal, as far as the helper can tell: a program running from the folder.
        val ping = folder.resolve("PING.EXE")
        Files.copy(Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "PING.EXE"), ping)
        val busy = ProcessBuilder(ping.toString(), "-n", "7", "127.0.0.1").redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val helper = SwapHelper.launch(folders, listOf(deadPid()), windows = true, log = here.resolve("helper.log"))
            Thread.sleep(3000)
            assertTrue(busy.isAlive, "the stand-in player finished too soon to prove anything")
            assertEquals("launcher 0.9.0", launcherText(folder), "swapped under a running program")
            assertTrue(folders.next.exists())
            assertTrue(busy.waitFor(30, TimeUnit.SECONDS))
            assertTrue(helper.waitFor(120, TimeUnit.SECONDS), "the helper never finished")
            assertEquals("launcher 0.9.2", launcherText(folder), "not swapped once it had gone; the helper said: ${log()}")
        } finally {
            busy.destroyForcibly()
        }
    }

    @Test
    fun `on Windows two helpers waiting for the same folder swap it once`() {
        assumeTrue(isWindows, "PowerShell is Windows's")
        val folder = copyAt(here.resolve("Noctorium CLI"), "0.9.0")
        val folders = UpdateFolders(folder)
        assertNull(folders.stage(archive("0.9.2"), launcher, windows = true))
        val first = SwapHelper.launch(folders, listOf(deadPid()), windows = true, log = here.resolve("helper.log"))
        val second = SwapHelper.launch(folders, listOf(deadPid()), windows = true, log = here.resolve("helper.log"))
        assertTrue(first.waitFor(120, TimeUnit.SECONDS) && second.waitFor(120, TimeUnit.SECONDS))
        assertEquals("launcher 0.9.2", launcherText(folder), log())
        assertFalse(folders.next.exists())
        assertFalse(folders.old.exists())
        assertFalse(folders.lock.exists())
    }

    @Test
    fun `on Windows a copy started while a helper waits does not start another`() {
        assumeTrue(isWindows, "PowerShell is Windows's")
        val folder = copyAt(here.resolve("Noctorium CLI"), "0.9.0")
        val folders = UpdateFolders(folder)
        assertNull(folders.stage(archive("0.9.2"), launcher, windows = true))
        val ping = folder.resolve("PING.EXE")
        Files.copy(Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "PING.EXE"), ping)
        val busy = ProcessBuilder(ping.toString(), "-n", "9", "127.0.0.1").redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val helper = SwapHelper.launch(folders, listOf(deadPid()), windows = true, log = here.resolve("helper.log"))
            // Long enough for PowerShell to have started and written its id.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!SwapHelper.helperWaiting(folders) && System.nanoTime() < deadline) Thread.sleep(200)
            assertTrue(SwapHelper.helperWaiting(folders), "the waiting helper is not seen; the helper said: ${log()}")
            assertEquals(helper.pid().toString(), folders.helper.readText().trim())

            // What every later start of the old copy does: it finds the one waiting and leaves it to it.
            val before = ProcessHandle.allProcesses().use { all -> all.filter { it.info().command().orElse("").endsWith("powershell.exe", ignoreCase = true) }.count() }
            assertNull(SwapHelper.start(folders, listOf(deadPid()), windows = true, log = here.resolve("helper.log")))
            Thread.sleep(1500)
            val after = ProcessHandle.allProcesses().use { all -> all.filter { it.info().command().orElse("").endsWith("powershell.exe", ignoreCase = true) }.count() }
            assertEquals(before, after, "a second helper was started")

            assertTrue(busy.waitFor(30, TimeUnit.SECONDS))
            assertTrue(helper.waitFor(120, TimeUnit.SECONDS), "the helper never finished")
            assertEquals("launcher 0.9.2", launcherText(folder), "not swapped; the helper said: ${log()}")
            assertFalse(folders.helper.exists(), "a finished helper left its id behind, and would look as though it still waited")
            assertFalse(SwapHelper.helperWaiting(folders))
        } finally {
            busy.destroyForcibly()
        }
    }

    @Test
    fun `an id left behind by a helper that has gone is not mistaken for one waiting`() {
        val folders = UpdateFolders(copyAt(here.resolve("Noctorium CLI"), "0.9.0"))
        // A process that ended, and this process: alive, but started long before the file was written.
        folders.helper.writeText(deadPid().toString())
        assertFalse(SwapHelper.helperWaiting(folders))
        folders.helper.writeText(ProcessHandle.current().pid().toString())
        Files.setLastModifiedTime(folders.helper, FileTime.from(Instant.now().plusSeconds(3600)))
        assertFalse(SwapHelper.helperWaiting(folders), "a process id used again by something else counted as a helper")
    }

    @Test
    fun `the PowerShell names every folder in a string that means exactly it`() {
        val folders = UpdateFolders(Path.of(if (isWindows) "C:\\Users\\Zoë\\O'Brien’s Music\\Noctorium CLI" else "/home/zoë/O'Brien’s Music/noctorium-cli"))
        val script = SwapHelper.windowsScript(folders, listOf(12, 34))
        assertTrue("O''Brien’’s Music" in script, script)
        assertTrue("@(12, 34)" in script)
        assertEquals(SwapHelper.quoted("it's"), "'it''s'")
        // And it reaches PowerShell as UTF-16, which is what -EncodedCommand reads.
        val decoded = String(java.util.Base64.getDecoder().decode(SwapHelper.encoded(script)), Charsets.UTF_16LE)
        assertEquals(script, decoded)
    }

    // --- The sh helper, for real ---

    /** sh: the system's own, or Git's on Windows, which is enough to run the script and prove the swap. */
    private fun sh(): Path? = listOfNotNull(
        Path.of("/bin/sh").takeIf { !isWindows && Files.isExecutable(it) },
        System.getenv("ProgramFiles")?.let { Path.of(it, "Git", "usr", "bin", "sh.exe") }?.takeIf { isWindows && Files.isRegularFile(it) },
    ).firstOrNull()

    /** A path as sh sees it: /c/Users/... under Git's sh on Windows. */
    private fun shPath(path: Path): String = if (!isWindows) path.toString() else
        "/" + path.root.toString().first().lowercaseChar() + "/" + (0 until path.nameCount).joinToString("/") { path.getName(it).toString() }

    private fun runSh(folders: UpdateFolders, waitFor: List<Long>, extraEnvironment: Map<String, String> = emptyMap(), path: String? = null): Process {
        val sh = sh()!!
        val builder = if (isWindows) {
            // Git's sh is given the script as a file: Windows command lines have no reliable way to carry the
            // quotes inside it, which a real Linux or Mac exec passes untouched.
            val file = here.resolve("helper.sh").also { it.writeText(SwapHelper.POSIX_SCRIPT) }
            ProcessBuilder(sh.toString(), shPath(file))
        } else {
            ProcessBuilder(sh.toString(), "-c", SwapHelper.POSIX_SCRIPT, "noctorium-update")
        }
        builder.environment().putAll(
            mapOf(
                "NOCTORIUM_UPDATE_FOLDER" to shPath(folders.folder),
                "NOCTORIUM_UPDATE_NEXT" to shPath(folders.next),
                "NOCTORIUM_UPDATE_OLD" to shPath(folders.old),
                "NOCTORIUM_UPDATE_LOCK" to shPath(folders.lock),
                "NOCTORIUM_UPDATE_HELPER" to shPath(folders.helper),
                "NOCTORIUM_UPDATE_WAIT_FOR" to waitFor.joinToString(" "),
            ) + extraEnvironment,
        )
        val gitBin = sh.parent.toString()
        builder.environment()["PATH"] = listOfNotNull(path, if (isWindows) gitBin else null, System.getenv("PATH")).joinToString(java.io.File.pathSeparator)
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(here.resolve("helper.log").toFile())).start()
            .also { it.outputStream.close() }
    }

    @Test
    fun `the sh helper swaps the folders, whatever the folder is called`() {
        assumeTrue(sh() != null, "no sh here")
        val folder = copyAt(here.resolve("Zoë's Müsic ‘quoted’").resolve("noctorium cli"), "0.9.0", extra = "old-only.txt")
        val folders = UpdateFolders(folder)
        assertNull(folders.stage(archive("0.9.2"), launcher, isWindows))

        val helper = runSh(folders, listOf(deadPid()))
        assertTrue(helper.waitFor(120, TimeUnit.SECONDS), "the helper never finished")

        assertEquals("launcher 0.9.2", launcherText(folder), "not swapped; the helper said: ${log()}")
        assertFalse(folder.resolve("old-only.txt").exists())
        assertFalse(folders.next.exists())
        assertFalse(folders.old.exists())
        assertFalse(folders.lock.exists())
        assertFalse(folders.helper.exists(), "the sh helper left its id behind")
    }

    @Test
    fun `the sh helper waits while a program runs out of the folder`() {
        assumeTrue(sh() != null, "no sh here")
        val sleep = listOfNotNull(
            Path.of("/bin/sleep").takeIf { !isWindows && Files.isExecutable(it) },
            sh()?.resolveSibling("sleep.exe")?.takeIf { isWindows && Files.isRegularFile(it) },
        ).firstOrNull()
        assumeTrue(sleep != null, "no sleep to copy")
        val folder = copyAt(here.resolve("noctorium-cli"), "0.9.0")
        val folders = UpdateFolders(folder)
        assertNull(folders.stage(archive("0.9.2"), launcher, isWindows))
        val copied = folder.resolve(sleep!!.fileName.toString())
        Files.copy(sleep, copied)
        copied.toFile().setExecutable(true)
        val running = ProcessBuilder(copied.toString(), "6").also { builder ->
            if (isWindows) builder.environment()["PATH"] = sleep.parent.toString() + java.io.File.pathSeparator + System.getenv("PATH")
        }.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val helper = runSh(folders, listOf(deadPid()))
            Thread.sleep(2500)
            assertTrue(running.isAlive, "the stand-in player finished too soon to prove anything")
            assertEquals("launcher 0.9.0", launcherText(folder), "swapped under a running program; the helper said: ${log()}")
            assertTrue(running.waitFor(30, TimeUnit.SECONDS))
            assertTrue(helper.waitFor(120, TimeUnit.SECONDS), "the helper never finished")
            assertEquals("launcher 0.9.2", launcherText(folder), "not swapped once it had gone; the helper said: ${log()}")
        } finally {
            running.destroyForcibly()
        }
    }

    /**
     * A Mac has no /proc, and the helper reads `ps` there instead. Tried here with a `ps` of the test's own,
     * which reports the player's java running out of the folder for as long as a file exists.
     */
    @Test
    fun `where there is no proc, the sh helper reads ps, and waits while the folder is named in it`() {
        assumeTrue(sh() != null, "no sh here")
        val folder = copyAt(here.resolve("noctorium-cli"), "0.9.0")
        val folders = UpdateFolders(folder)
        assertNull(folders.stage(archive("0.9.2"), launcher, isWindows))
        val marker = here.resolve("player-is-running").also { it.writeText("") }
        val bin = Files.createDirectories(here.resolve("fake-bin"))
        bin.resolve("ps").writeText(
            """
            |#!/bin/sh
            |echo "/bin/zsh -l"
            |if [ -e '${shPath(marker)}' ]; then echo "${shPath(folder)}/runtime/bin/java -cp ${shPath(folder)}/lib/* app.noctorium.cli.MainKt"; fi
            |""".trimMargin(),
        )
        bin.resolve("ps").toFile().setExecutable(true)

        val helper = runSh(folders, listOf(deadPid()), mapOf("NOCTORIUM_UPDATE_PROC" to shPath(here.resolve("no-proc-here"))), path = bin.toString())
        Thread.sleep(3500)
        assertEquals("launcher 0.9.0", launcherText(folder), "swapped while ps said the player was running; the helper said: ${log()}")
        Files.delete(marker)
        assertTrue(helper.waitFor(120, TimeUnit.SECONDS), "the helper never finished")
        assertEquals("launcher 0.9.2", launcherText(folder), "not swapped once ps stopped naming the folder; the helper said: ${log()}")
    }

    private fun log(): String = here.resolve("helper.log").takeIf { it.exists() }?.readText().orEmpty()
}
