package app.noctorium.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `noctorium` started from a menu rather than a terminal: when that counts as no terminal, where the window
 * app is looked for, and that it is opened by its path, once.
 */
class WindowAppTest {
    private val home = Path.of("/home/sam")

    @Test
    fun `no terminal means none on the streams and no TERM either`() {
        assertTrue(WindowApp.startedWithoutTerminal(hasTerminal = false, term = null))
        assertTrue(WindowApp.startedWithoutTerminal(hasTerminal = false, term = ""))
        // In a terminal with the output piped somewhere: still a terminal's doing, so told it needs one.
        assertFalse(WindowApp.startedWithoutTerminal(hasTerminal = false, term = "xterm-256color"))
        assertFalse(WindowApp.startedWithoutTerminal(hasTerminal = true, term = null))
        assertFalse(WindowApp.startedWithoutTerminal(hasTerminal = true, term = "xterm-256color"))
    }

    @Test
    fun `on Linux the package comes first, then the AppImage, then the Flatpak`() {
        val everything = setOf(
            Path.of("/opt/noctorium/bin/Noctorium"),
            home.resolve("Applications/Noctorium.AppImage"),
            Path.of("/var/lib/flatpak/app/app.noctorium.Noctorium"),
            Path.of("/usr/bin/flatpak"),
        )
        assertEquals(
            listOf(
                listOf(Path.of("/opt/noctorium/bin/Noctorium").toString()),
                listOf(home.resolve("Applications/Noctorium.AppImage").toString()),
                listOf(Path.of("/usr/bin/flatpak").toString(), "run", "app.noctorium.Noctorium"),
            ),
            WindowApp.commands("linux", home, everything::contains),
        )
    }

    @Test
    fun `a Flatpak installed for one person counts, but not without flatpak to run it`() {
        val mine = home.resolve(".local/share/flatpak/app/app.noctorium.Noctorium")
        assertEquals(
            listOf(listOf(Path.of("/usr/bin/flatpak").toString(), "run", "app.noctorium.Noctorium")),
            WindowApp.commands("linux", home, setOf(mine, Path.of("/usr/bin/flatpak"))::contains),
        )
        assertEquals(emptyList(), WindowApp.commands("linux", home, setOf(mine)::contains))
    }

    @Test
    fun `nothing is ever run by name, which might be this program again`() {
        val all = WindowApp.commands("linux", home) { true } + WindowApp.commands("macos", home) { true }
        // From the root: a Linux or Mac path, written however this machine writes them, since the tests also run on Windows.
        all.forEach { command -> assertTrue(command.first().first() in "/\\", "$command starts with a bare name") }
        assertFalse(all.any { command -> command.any { it == "noctorium" } }, "$all")
    }

    @Test
    fun `a Mac opens the app it finds, and Windows has nothing to open instead`() {
        assertEquals(
            listOf(listOf("/usr/bin/open", home.resolve("Applications/Noctorium.app").toString())),
            WindowApp.commands("macos", home, setOf(home.resolve("Applications/Noctorium.app"))::contains),
        )
        assertEquals(emptyList(), WindowApp.commands("windows", home) { true })
        assertEquals(emptyList(), WindowApp.commands("linux", home) { false })
    }

    @Test
    fun `the first one that starts is the one, and none is tried after it`() {
        val tried = mutableListOf<List<String>>()
        val opened = WindowApp.open(
            commands = listOf(listOf("/gone"), listOf("/opt/noctorium/bin/Noctorium"), listOf("/never")),
            environment = { null },
            start = { command -> tried += command; if (command == listOf("/gone")) error("not there") },
        )
        assertTrue(opened)
        assertEquals(listOf(listOf("/gone"), listOf("/opt/noctorium/bin/Noctorium")), tried)
    }

    @Test
    fun `nothing to open, or opened by this program already, is no`() {
        assertFalse(WindowApp.open(commands = emptyList(), environment = { null }, start = { error("not expected") }))
        assertFalse(
            WindowApp.open(
                commands = listOf(listOf("/opt/noctorium/bin/Noctorium")),
                environment = { name -> if (name == WindowApp.OPENED_BY_CLI) "1" else null },
                start = { error("opened again from inside what it opened") },
            ),
        )
    }

    @Test
    fun `it really starts, detached, and tells what it started where it came from`() {
        // This machine's own java running a few lines that write down the variable: as near to the window app
        // as a test can get, and proof that what is opened knows not to open anything again.
        val here = Files.createTempDirectory("window-app")
        try {
            val program = here.resolve("Echo.java")
            Files.writeString(
                program,
                """
                public class Echo {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0] + ".part"), String.valueOf(System.getenv("${WindowApp.OPENED_BY_CLI}")));
                        java.nio.file.Files.move(java.nio.file.Path.of(args[0] + ".part"), java.nio.file.Path.of(args[0]));
                    }
                }
                """.trimIndent(),
            )
            val said = here.resolve("said.txt")
            val runtime = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            assertTrue(WindowApp.open(commands = listOf(listOf(runtime.toString(), program.toString(), said.toString())), environment = { null }))
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!Files.exists(said) && System.nanoTime() < deadline) Thread.sleep(100)
            assertEquals("1", Files.readString(said))
        } finally {
            here.toFile().deleteRecursively()
        }
    }
}
