package app.noctorium.cli

import app.noctorium.platform.LinuxLinks
import app.noctorium.platform.SystemBridge
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * What a terminal can do for core, which assumes a desktop.
 *
 * There is no window to raise a browser from and no clipboard API, so each of these goes to the operating
 * system's own command instead, and says what it did through [notices] -- the terminal player shows them,
 * and `noctorium web` passes them to the browser. Where none of those commands exists, as on a server over
 * ssh, a link is still printed for somebody to open by hand, and copying falls back to OSC 52: the escape
 * sequence a terminal answers by putting text on the clipboard of the machine the person is sitting at,
 * which is the one they wanted it on.
 *
 * A link on Linux goes to [LinuxLinks]: the desktop's own openers, xdg-open first, with an AppImage's libraries kept
 * out of the browser's way. A bare xdg-open was all that was asked before, and where it was missing, or had no
 * browser to hand the page to, nothing opened -- which is how signing in to Last.fm from Linux came to open nothing.
 */
class TerminalBridge(
    /** The operating system by the name Java gives it. A test passes "Linux" to be on Linux anywhere. */
    osName: String = System.getProperty("os.name").orEmpty(),
    /** How a link is opened on Linux, and on any other Unix but a Mac's desktop: whether a browser took it. */
    private val openOnLinux: (String) -> Boolean = { url -> LinuxLinks.open(url) },
    /** How Windows' and a Mac's own opener is run, which a test replaces so that nothing opens. */
    private val launch: (List<String>) -> Boolean = ::launchAndSettle,
) : SystemBridge {
    private val isWindows = osName.startsWith("Windows", ignoreCase = true)
    private val isMac = osName.startsWith("Mac", ignoreCase = true)

    /**
     * A Mac has no DISPLAY to say there is a screen; a terminal there has one unless it is somebody signed in
     * over ssh, whose browser and clipboard are on the machine they are typing at and not on this one.
     */
    private val macDesktop: Boolean get() = isMac && System.getenv("SSH_CONNECTION").isNullOrBlank()

    /** Something worth telling the listener: a link that wants opening, or text that was copied. */
    sealed interface Notice {
        data class OpenLink(val url: String, val opened: Boolean) : Notice
        data class Copied(val text: String) : Notice
    }

    @Volatile
    var notices: (Notice) -> Unit = {}

    /** Writes raw bytes to the terminal, for OSC 52. Set by whichever front end owns the terminal. */
    @Volatile
    var terminalOutput: ((String) -> Unit)? = null

    override fun openUrl(url: String) {
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
            "Only secure links can be opened"
        }
        val opened = runCatching {
            when {
                isWindows -> launch(listOf("rundll32", "url.dll,FileProtocolHandler", url))
                macDesktop -> launch(listOf("/usr/bin/open", url))
                // Linux, and a Mac reached over ssh as before: with no screen to open it on, false at once.
                else -> openOnLinux(url)
            }
        }.getOrDefault(false)
        notices(Notice.OpenLink(url, opened))
    }

    override fun copyToClipboard(text: String) {
        val copied = runCatching {
            val command = when {
                isWindows -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "\$input | Set-Clipboard")
                macDesktop -> listOf("/usr/bin/pbcopy")
                !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() && onPath("wl-copy") -> listOf("wl-copy")
                !System.getenv("DISPLAY").isNullOrBlank() && onPath("xclip") -> listOf("xclip", "-selection", "clipboard")
                !System.getenv("DISPLAY").isNullOrBlank() && onPath("xsel") -> listOf("xsel", "--clipboard", "--input")
                else -> null
            } ?: return@runCatching false
            val process = ProcessBuilder(command).start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
        if (!copied) {
            terminalOutput?.invoke("\u001b]52;c;" + Base64.getEncoder().encodeToString(text.toByteArray()) + "\u0007")
        }
        notices(Notice.Copied(text))
    }

    override fun revealFile(path: Path) {
        val folder = path.parent ?: return
        runCatching {
            when {
                isWindows -> ProcessBuilder("explorer.exe", "/select,${path.toAbsolutePath()}").start()
                macDesktop -> ProcessBuilder("/usr/bin/open", "-R", path.toAbsolutePath().toString()).start()
                onPath("xdg-open") && !System.getenv("DISPLAY").isNullOrBlank() -> ProcessBuilder("xdg-open", folder.toString()).start()
                else -> null
            }
        }
    }

    /** ~/Music, where a terminal user most likely wants their music; the home folder if there is none. */
    override fun defaultExportFolder(): Path? {
        val home = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
        return home.resolve("Music").takeIf(Files::isDirectory) ?: home.takeIf(Files::isDirectory)
    }

    private fun onPath(name: String): Boolean = System.getenv("PATH").orEmpty()
        .split(java.io.File.pathSeparatorChar)
        .filter(String::isNotBlank)
        .any { Files.isExecutable(Path.of(it, name)) }
}

/**
 * Runs an opener, which hands the link on and returns at once: one that failed to find a browser at all is the only
 * thing worth waiting for.
 */
private fun launchAndSettle(command: List<String>): Boolean {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.inputStream.close()
    return !process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() == 0
}
