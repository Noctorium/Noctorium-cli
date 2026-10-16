package app.noctorium.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which opener a link goes to: on Linux, base's [app.noctorium.platform.LinuxLinks], and whatever it answers is what
 * the terminal player and `noctorium web` are told -- the link shown whole when nothing opened. Nothing is opened
 * for real here: each opener is a stand-in that writes down what it was asked.
 */
class TerminalBridgeTest {
    private val page = "https://www.last.fm/api/auth/?api_key=made-up-key&token=made-up-token"

    private class Openers(private val linux: Boolean = false, private val launched: Boolean = true) {
        val onLinux = mutableListOf<String>()
        val launches = mutableListOf<List<String>>()
        val notices = mutableListOf<TerminalBridge.Notice>()

        fun bridge(osName: String) = TerminalBridge(
            osName = osName,
            openOnLinux = { url -> onLinux += url; linux },
            launch = { command -> launches += command; launched },
        ).also { it.notices = { notice -> notices += notice } }
    }

    @Test
    fun `on Linux a link goes to the desktop's own openers, and says whether one took it`() {
        val opened = Openers(linux = true)
        opened.bridge("Linux").openUrl(page)
        assertEquals(listOf(page), opened.onLinux)
        assertEquals(emptyList(), opened.launches, "Nothing of its own is run beside them")
        assertEquals(listOf<TerminalBridge.Notice>(TerminalBridge.Notice.OpenLink(page, opened = true)), opened.notices)

        // Nothing would open it -- no screen, as over ssh, or no browser at all: the link is shown to open by hand.
        val nothing = Openers(linux = false)
        nothing.bridge("Linux").openUrl(page)
        assertEquals(listOf(page), nothing.onLinux)
        assertEquals(listOf<TerminalBridge.Notice>(TerminalBridge.Notice.OpenLink(page, opened = false)), nothing.notices)
    }

    @Test
    fun `an opener that fails outright is a link nothing opened, not a crash`() {
        val notices = mutableListOf<TerminalBridge.Notice>()
        val bridge = TerminalBridge(osName = "Linux", openOnLinux = { error("No opener could be started") }, launch = { true })
        bridge.notices = { notices += it }
        bridge.openUrl(page)
        assertEquals(listOf<TerminalBridge.Notice>(TerminalBridge.Notice.OpenLink(page, opened = false)), notices)
    }

    @Test
    fun `the other Unixes go the way Linux does`() {
        val opened = Openers(linux = true)
        opened.bridge("FreeBSD").openUrl(page)
        assertEquals(listOf(page), opened.onLinux)
    }

    @Test
    fun `Windows keeps its own opener, and Linux's is never asked there`() {
        val windows = Openers()
        windows.bridge("Windows 11").openUrl(page)
        assertEquals(listOf(listOf("rundll32", "url.dll,FileProtocolHandler", page)), windows.launches)
        assertEquals(emptyList(), windows.onLinux)
        assertEquals(listOf<TerminalBridge.Notice>(TerminalBridge.Notice.OpenLink(page, opened = true)), windows.notices)
    }

    @Test
    fun `a Mac keeps open, unless it is reached over ssh`() {
        val mac = Openers()
        mac.bridge("Mac OS X").openUrl(page)
        if (System.getenv("SSH_CONNECTION").isNullOrBlank()) {
            assertEquals(listOf(listOf("/usr/bin/open", page)), mac.launches)
            assertEquals(emptyList(), mac.onLinux)
        } else {
            // Signed in from elsewhere, the browser is on the other machine: asked as on Linux, which needs a screen here.
            assertEquals(emptyList(), mac.launches)
            assertEquals(listOf(page), mac.onLinux)
        }
    }

    @Test
    fun `only a secure link is opened at all`() {
        val opened = Openers(linux = true)
        assertFailsWith<IllegalArgumentException> { opened.bridge("Linux").openUrl("http://example.invalid/sign-in") }
        assertFailsWith<IllegalArgumentException> { opened.bridge("Linux").openUrl("file:///etc/passwd") }
        assertTrue(opened.onLinux.isEmpty() && opened.notices.isEmpty())
        // The web player's own address, on this computer, is one.
        opened.bridge("Linux").openUrl("http://127.0.0.1:7300/#key")
        assertEquals(listOf("http://127.0.0.1:7300/#key"), opened.onLinux)
    }
}
