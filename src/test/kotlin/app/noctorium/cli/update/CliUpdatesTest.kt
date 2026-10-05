package app.noctorium.cli.update

import app.noctorium.update.UpdateChannel
import app.noctorium.update.UpdateChecker
import app.noctorium.update.Version
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** When the check nobody asked for happens, and what it says when it does. */
class CliUpdatesTest {
    private val here = scratch("automatic")
    private lateinit var server: HttpServer
    private val asked = CopyOnWriteArrayList<String>()

    @Volatile
    private var latest: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            asked += exchange.requestURI.path
            val body = latest?.toByteArray()
            if (body == null) exchange.sendResponseHeaders(404, -1) else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        server.stop(0)
        removeTestFolder(here)
    }

    /** An unmanaged copy -- a run from a build -- which is told about releases and installs none. */
    private fun updates(environment: Map<String, String> = emptyMap(), now: Long = 1_800_000_000_000): CliUpdates {
        val installation = CliInstallation.detect(CliInstallation.Facts(CliInstallation.systemName(), null, null) { null })
        val installer = CliUpdateInstaller(Version.parse("0.9.0"), installation, downloads = here.resolve("downloads"), log = null, startHelper = { _, _ -> null })
        return CliUpdates(
            installer = installer,
            checker = UpdateChecker(Version.parse("0.9.0"), UpdateChannel.UNMANAGED, apiBase = "http://127.0.0.1:${server.address.port}"),
            record = UpdateRecord(here.resolve("update.json")),
            clock = { now },
            environment = { environment[it] },
        )
    }

    private fun release(tag: String) = """{"tag_name": "$tag", "html_url": "https://example/$tag", "assets": []}"""

    private fun runToTheEnd(updates: CliUpdates, switchedOn: Boolean = true): List<CliUpdates.Result> {
        val heard = CopyOnWriteArrayList<CliUpdates.Result>()
        val job = updates.automatically(scope, switchedOn, after = 1.milliseconds) { heard += it }
        runBlocking { job?.join() }
        return heard
    }

    @Test
    fun `switched off, nothing is asked`() {
        latest = release("v0.9.2")
        assertNull(updates().automatically(scope, switchedOn = false) {})
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `NOCTORIUM_NO_UPDATE turns it off whatever the setting says, and 0 or false does not`() {
        listOf("1", "true", "yes", "anything").forEach { value ->
            assertNull(updates(mapOf(CliUpdates.ENVIRONMENT to value)).automatically(scope, switchedOn = true) {}, value)
            assertEquals("${CliUpdates.ENVIRONMENT} is set", updates(mapOf(CliUpdates.ENVIRONMENT to value)).automaticOffBecause(true))
        }
        listOf("", "0", "false", "no", "off").forEach { value ->
            assertFalse(CliUpdates.disabledByEnvironment { if (it == CliUpdates.ENVIRONMENT) value else null }, "\"$value\" turned it off")
        }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `once a day at most, however often it starts`() {
        latest = release("v0.9.0")
        assertTrue(runToTheEnd(updates()).isEmpty(), "being up to date is not news")
        assertEquals(1, asked.size)
        // Started again an hour later, and again: nothing more is asked of GitHub.
        assertNull(updates(now = 1_800_000_000_000 + 3_600_000).automatically(scope, switchedOn = true) {})
        assertNull(updates(now = 1_800_000_000_000 + 23 * 3_600_000).automatically(scope, switchedOn = true) {})
        assertEquals(1, asked.size)
        // A day on, it is due again.
        runToTheEnd(updates(now = 1_800_000_000_000 + 24 * 3_600_000))
        assertEquals(2, asked.size)
    }

    @Test
    fun `a clock that went backwards does not stop the checks until it catches up`() {
        val record = UpdateRecord(here.resolve("update.json"))
        record.checked(1_900_000_000_000)
        assertTrue(record.due(1_800_000_000_000))
    }

    @Test
    fun `a copy that cannot install is told what is out, and how to get it`() {
        latest = release("v0.9.2")
        val heard = runToTheEnd(updates())
        val result = assertIs<CliUpdates.Result.Available>(heard.single())
        assertEquals("0.9.2", result.update.version.toString())
        val line = updates().headline(result, asked = false)!!
        assertTrue(line.startsWith("Noctorium CLI 0.9.2 is out."), line)
    }

    @Test
    fun `a failure is said quietly when nobody asked, and plainly when somebody did`() {
        latest = "this is not JSON"
        val heard = runToTheEnd(updates())
        val failed = assertIs<CliUpdates.Result.Failed>(heard.single())
        assertTrue(updates().headline(failed, asked = false)!!.startsWith("Could not update Noctorium CLI:"))
        assertEquals(failed.message, updates().headline(failed, asked = true))
        // Nothing new is nothing to say, unless somebody asked.
        assertNull(updates().headline(CliUpdates.Result.UpToDate, asked = false))
        assertEquals("This is the newest Noctorium CLI (0.9.0).", updates().headline(CliUpdates.Result.UpToDate, asked = true))
    }

    @Test
    fun `two checks at once are one check`() = runBlocking {
        latest = release("v0.9.0")
        val updates = updates()
        val results = listOf(
            async(Dispatchers.IO) { updates.run(install = true) },
            async(Dispatchers.IO) { updates.run(install = true) },
        ).map { it.await() }
        assertTrue(results.all { it is CliUpdates.Result.UpToDate || (it is CliUpdates.Result.Failed && "Already" in it.message) }, "$results")
    }
}
