package app.noctorium.cli.update

import app.noctorium.cli.Out
import app.noctorium.cli.UpdateCommand
import app.noctorium.update.UpdateChannel
import app.noctorium.update.UpdateChecker
import app.noctorium.update.UpdateDownloader
import app.noctorium.update.Version
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An update from start to finish, against a release served from this machine.
 *
 * A local HTTP server stands in for GitHub: the release JSON at the address the checker asks, SHA256SUMS.txt,
 * and an archive shaped like the real one for this system. The copy being updated is a folder in the
 * temporary directory laid out like an installed one, found the way the real program finds its own; and the
 * swap is done by the real helper, PowerShell on Windows and sh elsewhere, waiting for a process that has
 * already ended -- the moment it would reach once the player had quit.
 */
class UpdateEndToEndTest {
    private val here = scratch("end-to-end")
    private lateinit var server: HttpServer
    private val served = ConcurrentHashMap<String, ByteArray>()
    private val asked = CopyOnWriteArrayList<String>()
    private val helpers = CopyOnWriteArrayList<Process>()

    private val system = CliInstallation.systemName()
    private val architecture = if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
    private val windows = system == "windows"
    private val archiveName = "noctorium-cli-0.9.2-$system-$architecture" + if (windows) ".zip" else ".tar.gz"

    private val base get() = "http://127.0.0.1:${server.address.port}"

    /** Noctorium 0.9.1's files as published, but for the checksums, which [publish] adds when asked to. */
    private val RELEASE_0_9_1 = listOf(
        "noctorium-0.9.1-1-x86_64.pkg.tar.zst", "Noctorium-0.9.1-macos-arm64.dmg", "Noctorium-0.9.1-macos-x64.dmg",
        "Noctorium-0.9.1-windows-x64-setup.exe", "Noctorium-0.9.1-windows-x64.msi", "Noctorium-0.9.1-x86_64.AppImage",
        "Noctorium-0.9.1-x86_64.flatpak", "Noctorium-0.9.1.apk", "noctorium-0.9.1.x86_64.rpm",
        "noctorium-cli-0.9.1-linux-x64.tar.gz", "noctorium-cli-0.9.1-macos-arm64.tar.gz", "noctorium-cli-0.9.1-macos-x64.tar.gz",
        "noctorium-cli-0.9.1-windows-x64.zip", "Noctorium-Installer-android.apk", "noctorium-installer-cli-linux-x64",
        "noctorium-installer-cli-macos", "noctorium-installer-cli-windows-x64.exe", "noctorium-installer-linux-x64",
        "Noctorium-Installer-windows-x64.exe", "Noctorium-Installer-x86_64.AppImage", "noctorium_0.9.1_amd64.deb",
    )

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            asked += exchange.requestURI.path
            val body = served[exchange.requestURI.path]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        helpers.forEach { it.waitFor(60, TimeUnit.SECONDS) }
        removeTestFolder(here)
    }

    // --- The release ---

    /** The new version's archive, laid out exactly as this system's release archive is. */
    private fun archiveBytes(): ByteArray {
        val file = here.resolve(archiveName)
        when (system) {
            "windows" -> ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                fun put(name: String, text: String?) {
                    zip.putNextEntry(ZipEntry(name))
                    text?.let { zip.write(it.toByteArray()) }
                    zip.closeEntry()
                }
                put("noctorium-cli/", null)
                put("noctorium-cli/app/", null)
                put("noctorium-cli/noctorium.exe", "launcher 0.9.2")
                put("noctorium-cli/app/noctorium-cli-0.9.2.jar", "jar 0.9.2")
                put("noctorium-cli/app/noctorium.cfg", "[Application]\napp.classpath=\$APPDIR\\noctorium-cli-0.9.2.jar\n")
                put("noctorium-cli/runtime/release", "JAVA_VERSION=\"21.0.9\"")
                put("noctorium-cli/runtime/bin/server/jvm.dll", "MZ")
                put("noctorium-cli/runtime/legal/java.base/" + "LICENSE-with-a-long-name-".repeat(5) + ".txt", "licence")
            }
            "linux" -> TarWriter.tarGz(file, TarWriter.Style.GNU) {
                folder("noctorium-cli/")
                file("noctorium-cli/bin/noctorium", "launcher 0.9.2", octal("755"))
                file("noctorium-cli/lib/app/noctorium-cli-0.9.2.jar", "jar 0.9.2")
                file("noctorium-cli/lib/app/noctorium.cfg", "[Application]")
                file("noctorium-cli/lib/runtime/bin/java", "ELF", octal("755"))
                file("noctorium-cli/lib/runtime/lib/jspawnhelper", "ELF", octal("755"))
                file("noctorium-cli/lib/runtime/legal/java.base/LICENSE", "licence")
                symlink("noctorium-cli/lib/runtime/legal/java.desktop/LICENSE", "../java.base/LICENSE")
                file("noctorium-cli/lib/runtime/legal/java.base/" + "a-long-name-".repeat(12) + ".txt", "long")
            }
            else -> TarWriter.tarGz(file, TarWriter.Style.PAX) {
                folder("noctorium-cli/")
                file("noctorium-cli/bin/noctorium", "launcher 0.9.2", octal("755"))
                file("noctorium-cli/lib/noctorium-cli-0.9.2.jar", "jar 0.9.2")
                file("noctorium-cli/runtime/bin/java", "Mach-O", octal("755"))
                file("noctorium-cli/runtime/legal/java.base/LICENSE", "licence")
                symlink("noctorium-cli/runtime/legal/java.desktop/LICENSE", "../java.base/LICENSE")
                file("._noctorium-cli", "what a Mac's tar adds")
            }
        }
        return Files.readAllBytes(file).also { Files.delete(file) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Publishes 0.9.2 on the stand-in: every file the real 0.9.1 release had, renamed, with this system's
     * archive real and the rest absent, as a release with only some of its files downloadable would be.
     */
    private fun publish(checksum: (ByteArray) -> String? = ::sha256) {
        val archive = archiveBytes()
        served["/download/$archiveName"] = archive
        val names = (RELEASE_0_9_1.map { it.replace("0.9.1", "0.9.2") } + archiveName).distinct()
        val sum = checksum(archive)
        val assets = names.map { name ->
            """{"name": "$name", "browser_download_url": "$base/download/$name", "size": ${if (name == archiveName) archive.size else 1000}}"""
        } + listOfNotNull(sum?.let { """{"name": "SHA256SUMS.txt", "browser_download_url": "$base/download/SHA256SUMS.txt", "size": 2078}""" })
        if (sum != null) {
            served["/download/SHA256SUMS.txt"] = names.joinToString("\n") { name ->
                (if (name == archiveName) sum else "0".repeat(64)) + "  " + name
            }.toByteArray()
        }
        served["/repos/Noctorium/Noctorium-Installer/releases/latest"] = """
            {"tag_name": "v0.9.2", "name": "Noctorium 0.9.2",
             "html_url": "https://github.com/Noctorium/Noctorium-Installer/releases/tag/v0.9.2",
             "body": "Notes.", "assets": [${assets.joinToString(",\n")}]}
        """.trimIndent().toByteArray()
    }

    // --- The copy being updated ---

    private fun installedCopy(): Path {
        val folder = if (windows) here.resolve("Programs").resolve("Noctorium CLI") else here.resolve("share").resolve("noctorium-cli")
        if (windows) {
            Files.createDirectories(folder.resolve("app"))
            folder.resolve("noctorium.exe").writeText("launcher 0.9.0")
            folder.resolve("app/noctorium-cli-0.9.0.jar").writeText("jar 0.9.0")
            folder.resolve("app/only-in-0.9.0.jar").writeText("stale")
        } else {
            val app = if (system == "linux") "lib/app" else "lib"
            Files.createDirectories(folder.resolve("bin"))
            Files.createDirectories(folder.resolve(app))
            folder.resolve("bin/noctorium").writeText("launcher 0.9.0")
            folder.resolve("bin/noctorium").toFile().setExecutable(true)
            folder.resolve("$app/noctorium-cli-0.9.0.jar").writeText("jar 0.9.0")
            folder.resolve("$app/only-in-0.9.0.jar").writeText("stale")
        }
        return folder
    }

    private fun deadPid(): Long {
        val process = (if (windows) ProcessBuilder("cmd", "/c", "exit", "0") else ProcessBuilder("true")).start()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.pid()
    }

    private fun updates(
        folder: Path,
        current: String = "0.9.0",
        swap: Boolean = true,
        started: MutableList<UpdateFolders> = mutableListOf(),
    ): CliUpdates {
        val launcher = folder.resolve(if (windows) "noctorium.exe" else "bin/noctorium")
        val installation = CliInstallation.detect(
            CliInstallation.Facts(system, launcher.toString().takeIf { system != "macos" }, folder.toString().takeIf { system == "macos" }) { null },
        )
        val installer = CliUpdateInstaller(
            currentVersion = Version.parse(current),
            installation = installation,
            downloads = here.resolve("downloads"),
            log = here.resolve("update.log"),
            waitFor = { listOf(deadPid()) },
            startHelper = { folders, pids ->
                started += folders
                if (swap) helpers += SwapHelper.launch(folders, pids, windows, here.resolve("update.log"))
                null
            },
        )
        return CliUpdates(
            installer = installer,
            checker = UpdateChecker(Version.parse(current), installer.channel, apiBase = base),
            record = UpdateRecord(here.resolve("update.json")),
            downloader = UpdateDownloader(),
            clock = { 1_800_000_000_000 },
        )
    }

    private fun launcherText(folder: Path) = folder.resolve(if (windows) "noctorium.exe" else "bin/noctorium").readText()

    private fun helperLog() = here.resolve("update.log").takeIf { it.exists() }?.readText().orEmpty()

    /** Anything left where archives are downloaded, folders included: nothing, once an update is done with. */
    private fun downloadsLeft(): List<Path> = here.resolve("downloads").takeIf { it.exists() }
        ?.let { folder -> Files.walk(folder).use { paths -> paths.filter { it != folder }.toList() } }
        .orEmpty()

    // --- From start to finish ---

    @Test
    fun `an update is found, downloaded, verified, installed, and swapped in once the old copy has gone`() {
        publish()
        val folder = installedCopy()
        val updates = updates(folder)
        assertEquals(UpdateChannel.CLI_ARCHIVE, updates.installer.channel, updates.installer.installation.advice)

        val result = runBlocking { updates.run(install = true) }

        assertIs<CliUpdates.Result.Installed>(result, "$result")
        assertEquals(Version.parse("0.9.2"), result.version)
        assertTrue("takes over when you quit" in result.message, result.message)
        assertEquals(
            listOf("/repos/Noctorium/Noctorium-Installer/releases/latest", "/download/SHA256SUMS.txt", "/download/$archiveName"),
            asked.toList(),
        )
        assertEquals(1_800_000_000_000, UpdateRecord(here.resolve("update.json")).lastChecked())

        val helper = helpers.single()
        assertTrue(helper.waitFor(120, TimeUnit.SECONDS), "the helper never finished")
        assertEquals("launcher 0.9.2", launcherText(folder), "not swapped in; the helper said: ${helperLog()}")
        val app = when (system) { "windows" -> "app"; "linux" -> "lib/app"; else -> "lib" }
        assertTrue(folder.resolve("$app/noctorium-cli-0.9.2.jar").exists())
        assertFalse(folder.resolve("$app/only-in-0.9.0.jar").exists(), "a jar only the old copy had survived the update")
        val folders = UpdateFolders(folder)
        assertFalse(folders.next.exists())
        assertFalse(folders.old.exists())
        assertFalse(folders.lock.exists())
        // The archive is not kept once it is unpacked.
        assertTrue(downloadsLeft().isEmpty(), "the archive was kept: ${downloadsLeft()}")
        if (!windows) {
            assertTrue(Files.isExecutable(folder.resolve("bin/noctorium")), "the launcher lost its executable bit")
            val legal = if (system == "linux") "lib/runtime/legal" else "runtime/legal"
            assertTrue(Files.isSymbolicLink(folder.resolve("$legal/java.desktop/LICENSE")))
            assertEquals("licence", folder.resolve("$legal/java.desktop/LICENSE").readText())
        }
        // And the copy now reports itself as the new version, which is what the next check will compare.
        assertEquals(Version.parse("0.9.2"), UpdateFolders.versionIn(folder))
    }

    @Test
    fun `a download that does not match its checksum is refused, and nothing changes`() {
        publish(checksum = { "f".repeat(64) })
        val folder = installedCopy()
        val started = mutableListOf<UpdateFolders>()
        val updates = updates(folder, started = started)

        val result = runBlocking { updates.run(install = true) }

        assertIs<CliUpdates.Result.Failed>(result)
        assertTrue("checksum" in result.message, result.message)
        assertTrue("/download/$archiveName" in asked, "it was never downloaded, so the refusal proves nothing")
        assertTrue(started.isEmpty(), "a helper was started for an archive that failed its checksum")
        assertFalse(UpdateFolders(folder).next.exists())
        assertEquals("launcher 0.9.0", launcherText(folder))
        assertTrue(downloadsLeft().isEmpty(), "the mismatched download was kept: ${downloadsLeft()}")
    }

    @Test
    fun `a release that published no checksum is not downloaded at all`() {
        publish(checksum = { null })
        val folder = installedCopy()
        val result = runBlocking { updates(folder).run(install = true) }
        assertIs<CliUpdates.Result.Failed>(result)
        assertFalse("/download/$archiveName" in asked)
        assertEquals("launcher 0.9.0", launcherText(folder))
    }

    @Test
    fun `asked only to look, it says what there is and fetches nothing`() {
        publish()
        val folder = installedCopy()
        val result = runBlocking { updates(folder).run(install = false) }
        assertIs<CliUpdates.Result.Available>(result)
        assertEquals("0.9.2", result.update.version.toString())
        assertEquals(archiveName, result.update.file?.name)
        assertFalse("/download/$archiveName" in asked)
    }

    @Test
    fun `once an update is waiting, the next check says so instead of downloading it again`() {
        publish()
        val folder = installedCopy()
        val updates = updates(folder, swap = false)
        assertIs<CliUpdates.Result.Installed>(runBlocking { updates.run(install = true) })
        asked.clear()

        val again = runBlocking { updates.run(install = true) }

        assertIs<CliUpdates.Result.Installed>(again)
        assertTrue(again.earlier)
        assertFalse("/download/$archiveName" in asked, "downloaded twice")
        assertEquals(Version.parse("0.9.2"), updates.status.value.waiting)
    }

    @Test
    fun `the version that is out already is up to date`() {
        publish()
        val result = runBlocking { updates(installedCopy(), current = "0.9.2").run(install = true) }
        assertEquals(CliUpdates.Result.UpToDate, result)
    }

    @Test
    fun `a copy that cannot replace itself is told about the release, and how to update instead`() {
        publish()
        val folder = here.resolve("not-a-cli").also { Files.createDirectories(it) }
        val result = runBlocking { updates(folder).run(install = true) }
        assertIs<CliUpdates.Result.Available>(result)
        assertTrue(result.advice.isNotBlank())
        assertFalse("/download/$archiveName" in asked)
    }

    // --- noctorium update ---

    private fun command(options: List<String>, updates: CliUpdates): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { UpdateCommand.run(options, updates, Out(colour = false), it) }
        return code to buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `noctorium update --check reports, and noctorium update installs, saying each step`() {
        publish()
        val folder = installedCopy()

        val (checked, report) = command(listOf("--check"), updates(folder, swap = false))
        assertEquals(0, checked, report)
        assertTrue("Noctorium CLI 0.9.2 is out; this is 0.9.0." in report, report)
        assertFalse("/download/$archiveName" in asked)

        val (installed, said) = command(emptyList(), updates(folder))
        assertEquals(0, installed, said)
        assertTrue("Downloading $archiveName" in said, said)
        assertTrue("checksum" in said, said)
        assertTrue("Noctorium CLI 0.9.2 is installed, and takes over when you quit." in said, said)
        assertTrue(helpers.single().waitFor(120, TimeUnit.SECONDS))
        assertEquals("launcher 0.9.2", launcherText(folder), helperLog())
    }

    @Test
    fun `noctorium update refuses an option it does not know`() {
        val (code, _) = command(listOf("--frobnicate"), updates(installedCopy()))
        assertEquals(2, code)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the check is recorded, and the next is not due for a day`() {
        publish()
        val record = UpdateRecord(here.resolve("update.json"))
        assertTrue(record.due(1_800_000_000_000))
        runBlocking { updates(installedCopy(), current = "0.9.2").run(install = true) }
        assertNotNull(record.lastChecked())
        assertFalse(record.due(1_800_000_000_000 + 23 * 3_600_000L))
        assertTrue(record.due(1_800_000_000_000 + 24 * 3_600_000L))
    }
}
