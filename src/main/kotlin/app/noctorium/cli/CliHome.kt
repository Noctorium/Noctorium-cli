package app.noctorium.cli

import app.noctorium.platform.isMacOs
import app.noctorium.playback.BackendLocator
import app.noctorium.settings.AppDirectories
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the terminal player keeps its things, and where the desktop keeps its.
 *
 * Inside the desktop's folder, in `cli/`, rather than in it. Sharing the desktop's own settings looked like
 * the friendly choice and is not: both would announce the same Connect device id and each would write over
 * the other's settings file. A folder of its own avoids both, and the sign-in a listener already made in the
 * desktop is offered to it once, as a copy (see [DesktopSignIn]).
 *
 * The tools are the exception. mpv and yt-dlp the desktop already downloaded are borrowed rather than
 * fetched a second time, through [BackendLocator.neighbour].
 */
object CliHome {
    /**
     * The desktop's folder: `%LOCALAPPDATA%\Noctorium`, `~/Library/Application Support/Noctorium` on a Mac, or
     * `~/.local/share/noctorium`.
     */
    val desktop: Path? by lazy {
        val windows = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
        if (windows != null) return@lazy windows.resolve("Noctorium")
        val home = System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: return@lazy null
        if (isMacOs()) Path.of(home, "Library", "Application Support", "Noctorium")
        else Path.of(home, ".local", "share", "noctorium")
    }

    /** This program's folder. Named outright by `-Dnoctorium.home`, as the tests do. */
    val own: Path? by lazy {
        System.getProperty(AppDirectories.BASE_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: System.getenv("NOCTORIUM_CLI_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: desktop?.resolve("cli")
    }

    /**
     * Points everything at [own]. Called first thing in main, before anything resolves a path: after the
     * first lookup the answer is fixed for the run.
     */
    fun install() {
        own?.let { folder ->
            runCatching { Files.createDirectories(folder) }
            AppDirectories.useBase(folder)
        }
        desktop?.resolve("bin")?.takeIf(Files::isDirectory)?.let { BackendLocator.neighbour = it }
    }
}
