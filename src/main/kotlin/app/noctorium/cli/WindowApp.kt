package app.noctorium.cli

import app.noctorium.cli.update.CliInstallation
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The window Noctorium, for a `noctorium` started where there is no terminal to draw the player in.
 *
 * The terminal player is `noctorium` everywhere, and Noctorium's Arch package used to put the window app on
 * the PATH as `noctorium` as well, with a menu entry that ran it by that name. Once the terminal player was
 * installed too, `~/.local/bin` -- which comes before `/usr/bin` -- answered first, and clicking Noctorium in
 * the menu started the terminal player with no terminal: it gave up without a word anybody could see, and the
 * window never opened.
 *
 * The package now names the window app by its own path. This is for whatever was written the old way and is
 * still about -- a menu entry from an older package, a shortcut somebody made, a launcher that runs commands
 * by name: started with no terminal at all, the player cannot be shown, and what was wanted was the window.
 * So when one is installed it is opened instead. Only ever by its full path, never by name, since by name it
 * might be this program again.
 */
internal object WindowApp {
    /** Set for the window app this opens, so that should it ever turn out to be this program, it stops there. */
    const val OPENED_BY_CLI = "NOCTORIUM_OPENED_BY_CLI"

    /** The Flatpak's id, which is how flatpak runs it. */
    const val FLATPAK = "app.noctorium.Noctorium"

    /**
     * Whether this process was started with no terminal at all: none on its input and output, and no TERM,
     * which every terminal sets and a desktop's menu does not. Both, so that a player started in a terminal
     * with its output sent somewhere else is told it needs a terminal, as before, rather than sent a window.
     */
    fun startedWithoutTerminal(hasTerminal: Boolean = hasTerminal(), term: String? = System.getenv("TERM")): Boolean =
        !hasTerminal && term.isNullOrBlank()

    private fun hasTerminal(): Boolean {
        val console = System.console() ?: return false
        // From Java 22 there is a console even with no terminal behind it, and it says which.
        return runCatching { console.javaClass.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true)
    }

    /**
     * The ways this machine has of opening the window app, best first, each a whole command.
     *
     * Where each of Noctorium's own formats puts it: `/opt/noctorium` for the .deb, the .rpm and the Arch
     * package; `~/Applications` for the AppImage the installer places and for a Mac's own copy; the Flatpak by
     * its id, installed for everybody or for one person. Nothing on Windows, where a console program is always
     * given a console to run in, so the question never comes up.
     */
    fun commands(system: String, home: Path?, exists: (Path) -> Boolean): List<List<String>> = when (system) {
        "linux" -> buildList {
            Path.of("/opt/noctorium/bin/Noctorium").takeIf(exists)?.let { add(listOf(it.toString())) }
            home?.resolve("Applications")?.resolve("Noctorium.AppImage")?.takeIf(exists)?.let { add(listOf(it.toString())) }
            val flatpak = Path.of("/usr/bin/flatpak")
            val installed = listOfNotNull(
                Path.of("/var/lib/flatpak/app", FLATPAK),
                home?.resolve(".local/share/flatpak/app")?.resolve(FLATPAK),
            ).any(exists)
            if (installed && exists(flatpak)) add(listOf(flatpak.toString(), "run", FLATPAK))
        }
        "macos" -> listOfNotNull(Path.of("/Applications/Noctorium.app"), home?.resolve("Applications")?.resolve("Noctorium.app"))
            .filter(exists)
            .map { listOf("/usr/bin/open", it.toString()) }
        else -> emptyList()
    }

    /**
     * Opens the window app if this machine has one, and says whether it did.
     *
     * Not waited for, and given nothing to read and nowhere to write: it goes on after this program has gone,
     * as anything opened from a menu does. Opened at most once in a chain, through [OPENED_BY_CLI].
     */
    fun open(
        commands: List<List<String>> = commands(
            CliInstallation.systemName(),
            System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
            Files::exists,
        ),
        environment: (String) -> String? = System::getenv,
        start: (List<String>) -> Unit = ::startDetached,
    ): Boolean {
        if (!environment(OPENED_BY_CLI).isNullOrBlank()) return false
        return commands.any { command -> runCatching { start(command) }.isSuccess }
    }

    private fun startDetached(command: List<String>) {
        val nothing = File(if (File.separatorChar == '\\') "NUL" else "/dev/null")
        ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(nothing))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { it.environment()[OPENED_BY_CLI] = "1" }
            .start()
    }
}
