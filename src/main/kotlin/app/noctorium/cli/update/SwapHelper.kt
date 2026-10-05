package app.noctorium.cli.update

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.jvm.optionals.getOrNull

/**
 * The few lines that put a downloaded update in place once this copy has gone.
 *
 * Something has to outlive this process, on every system, for different reasons.
 *
 * On Windows a running program's files are locked: the folder cannot be moved while anything runs out of it.
 *
 * On Linux and a Mac it can, and a running program keeps every file it has open -- but a JVM does not open
 * everything at the start. It opens a jar the first time it needs a class or a resource from it, by path,
 * and a resource read through a URL (the web player's pages) opens the jar again by path; it runs
 * `lib/jspawnhelper`, by path, every time it starts a process, which is every track mpv or yt-dlp plays;
 * it loads native libraries the first time something wants them. Swap the folder underneath a running
 * player and each of those finds the *new* copy's file at the old path: a jar of a different version, or
 * none at all since the jar's name carries the version, and a jspawnhelper from a different Java build,
 * which refuses to start anything. The player would go on playing the current track and fail at the next.
 *
 * So the new copy waits beside the old one in `.update-next`, and a helper started now -- PowerShell on
 * Windows, the system's sh elsewhere -- waits for this process, and then for anything else still running out
 * of the folder (a second player in another terminal, `noctorium web`), before it renames the old folder
 * aside, renames the new one in, and deletes the old. Until then nothing about the running copy changes.
 * Two helpers waiting for the same folder are harmless: the swap itself is taken under a lock, and whichever
 * comes second finds nothing left to do.
 *
 * Built here as text, apart from the process that runs it, so what it says can be read and tested.
 */
object SwapHelper {

    /**
     * Starts the helper for [folders], waiting first for [waitFor], and returns null, or why it could not.
     * [log] receives anything the helper says, which is normally nothing.
     */
    fun start(folders: UpdateFolders, waitFor: List<Long>, windows: Boolean, log: Path?): String? {
        if (helperWaiting(folders)) return null
        return runCatching { launch(folders, waitFor, windows, log) }
            .fold(onSuccess = { null }, onFailure = { "Could not start what puts the update in place: ${it.message}" })
    }

    /**
     * Whether a helper started earlier is still waiting to swap [folders] in.
     *
     * One is enough: it waits for everything running out of the folder, not only for the copy that started
     * it, so a copy started again in the meantime is waited for as well. Without this, every start of the
     * old copy while an update waits would add a helper -- fifty quick commands while `noctorium web` runs
     * would leave fifty, each looking through every process on the machine every two seconds.
     *
     * Each helper writes its process id into [UpdateFolders.helper] as it starts and removes it as it ends.
     * A process id is used again once its process has gone, so the process found has to have started about
     * when the file was written, as well as be alive; one that does not is somebody else, and the file a
     * helper's left behind by a shutdown.
     */
    internal fun helperWaiting(folders: UpdateFolders): Boolean = runCatching {
        val pid = Files.readString(folders.helper).trim().toLong()
        val written = Files.getLastModifiedTime(folders.helper).toInstant()
        val process = ProcessHandle.of(pid).getOrNull()?.takeIf(ProcessHandle::isAlive) ?: return@runCatching false
        val started = process.info().startInstant().getOrNull() ?: return@runCatching true
        Duration.between(started, written).abs() < Duration.ofMinutes(2)
    }.getOrDefault(false)

    /** The same, handing back the helper itself, for a test to wait on. */
    internal fun launch(folders: UpdateFolders, waitFor: List<Long>, windows: Boolean, log: Path?): Process {
        val builder = if (windows) {
            val systemRoot = System.getenv("SystemRoot")?.takeIf(String::isNotBlank) ?: "C:\\Windows"
            // No -WindowStyle Hidden: Java starts every child with a console of its own that has no window,
            // so there is nothing to hide, and on a console shared with the terminal that switch would hide
            // the terminal itself.
            ProcessBuilder(
                "$systemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded(windowsScript(folders, waitFor)),
            )
        } else {
            ProcessBuilder("/bin/sh", "-c", POSIX_SCRIPT, "noctorium-update").also { builder ->
                builder.environment().putAll(posixEnvironment(folders, waitFor))
            }
        }
        builder.redirectErrorStream(true)
        val output = log?.let { file ->
            runCatching { Files.createDirectories(file.parent) }
            ProcessBuilder.Redirect.appendTo(file.toFile())
        } ?: ProcessBuilder.Redirect.DISCARD
        builder.redirectOutput(output)
        val process = builder.start()
        // Nothing is ever sent to it. Closed now, so it reads the end of its input rather than a pipe that
        // breaks when this process goes.
        process.outputStream.close()
        return process
    }

    /**
     * What the helper waits for: this process, and the launcher above it when there is one -- jpackage's
     * Windows launcher can start the JVM as a second process and wait for it, and that launcher is
     * noctorium.exe, inside the folder.
     */
    fun processesToOutlive(folder: Path): List<Long> {
        val self = ProcessHandle.current()
        val parent = self.parent().getOrNull()?.takeIf { handle -> runsFrom(handle, folder) }
        return listOfNotNull(self.pid(), parent?.pid())
    }

    /**
     * Whether anything at all, this process included, is running a program out of [folder]: on Windows and
     * Linux the executable's own path, on a Mac the launcher script's java, both of which are inside it.
     */
    fun anythingRunningFrom(folder: Path): Boolean = runCatching {
        ProcessHandle.allProcesses().use { all -> all.anyMatch { runsFrom(it, folder) } }
    }.getOrDefault(false)

    private fun runsFrom(process: ProcessHandle, folder: Path): Boolean {
        val command = process.info().command().getOrNull() ?: return false
        return runCatching { Path.of(command).toAbsolutePath().normalize().startsWith(folder) }.getOrDefault(false)
    }

    // --- Windows ---

    /**
     * The PowerShell that does it on Windows.
     *
     * The renames are tried for a minute, not once: the moment a program exits, Windows can still be holding
     * its files -- an antivirus scanner reading the exe, the loader letting go of a DLL -- and a folder with
     * anything held open in it will not move. If they still fail, everything stays where it was: the old
     * copy in place, the new one waiting, and the next start of the old copy starts another helper.
     */
    fun windowsScript(folders: UpdateFolders, waitFor: List<Long>): String = """
        |${'$'}ErrorActionPreference = 'SilentlyContinue'
        |${'$'}ProgressPreference = 'SilentlyContinue'
        |${'$'}folder = ${quoted(folders.folder.toString())}
        |${'$'}next = ${quoted(folders.next.toString())}
        |${'$'}old = ${quoted(folders.old.toString())}
        |${'$'}lock = ${quoted(folders.lock.toString())}
        |${'$'}helper = ${quoted(folders.helper.toString())}
        |Set-Content -LiteralPath ${'$'}helper -Value ${'$'}PID
        |try {
        |foreach (${'$'}id in @(${waitFor.joinToString(", ")})) {
        |    ${'$'}process = Get-Process -Id ${'$'}id
        |    if (${'$'}process) { ${'$'}process.WaitForExit() }
        |}
        |${'$'}inside = ${'$'}folder.TrimEnd('\') + '\'
        |function Test-Busy {
        |    [bool](Get-Process | Where-Object { ${'$'}_.Path -and ${'$'}_.Path.StartsWith(${'$'}inside, [StringComparison]::OrdinalIgnoreCase) })
        |}
        |function Move-Folder([string] ${'$'}from, [string] ${'$'}to) {
        |    for (${'$'}attempt = 0; ${'$'}attempt -lt 120; ${'$'}attempt++) {
        |        try { [System.IO.Directory]::Move(${'$'}from, ${'$'}to); return ${'$'}true } catch { Start-Sleep -Milliseconds 500 }
        |    }
        |    return ${'$'}false
        |}
        |function Remove-Folder([string] ${'$'}path) {
        |    for (${'$'}attempt = 0; ${'$'}attempt -lt 10 -and (Test-Path -LiteralPath ${'$'}path); ${'$'}attempt++) {
        |        Remove-Item -LiteralPath ${'$'}path -Recurse -Force
        |        if (Test-Path -LiteralPath ${'$'}path) { Start-Sleep -Milliseconds 500 }
        |    }
        |}
        |while (Test-Busy) { Start-Sleep -Seconds 2 }
        |if (-not (Test-Path -LiteralPath ${'$'}next -PathType Container)) { exit 0 }
        |try { ${'$'}held = [System.IO.File]::Open(${'$'}lock, 'CreateNew', 'Write', 'None') } catch { exit 0 }
        |try {
        |    if (-not (Test-Path -LiteralPath ${'$'}next -PathType Container)) {
        |        # Another helper swapped it in between the look above and the lock.
        |    } elseif (-not (Test-Path -LiteralPath ${'$'}folder -PathType Container)) {
        |        # The CLI was removed while this waited, which is not something to undo.
        |        Remove-Folder ${'$'}next
        |    } else {
        |        Remove-Folder ${'$'}old
        |        if (Move-Folder ${'$'}folder ${'$'}old) {
        |            if (Move-Folder ${'$'}next ${'$'}folder) { Remove-Folder ${'$'}old } else { ${'$'}null = Move-Folder ${'$'}old ${'$'}folder }
        |        }
        |    }
        |} finally {
        |    ${'$'}held.Close()
        |    Remove-Item -LiteralPath ${'$'}lock -Force
        |}
        |} finally {
        |    # Reached on every way out, the early exits included, so a helper that has finished never looks
        |    # like one still waiting. Only its own id is removed: a newer helper may have written its own.
        |    if ((Get-Content -LiteralPath ${'$'}helper) -eq "${'$'}PID") { Remove-Item -LiteralPath ${'$'}helper -Force }
        |}
    """.trimMargin()

    /**
     * The script as `-EncodedCommand` wants it: UTF-16LE, then Base64.
     *
     * Passed this way rather than as `-Command` text because nothing then stands between the script and
     * PowerShell that could re-quote it -- not Java's rules for building a Windows command line, and not
     * cmd's -- and a folder with a space, an accent or an apostrophe in its name arrives exactly as written.
     */
    fun encoded(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    /**
     * A PowerShell string that means exactly [text].
     *
     * Single-quoted, so nothing in it is expanded, which leaves the quote itself as the one character to
     * double. PowerShell also closes a single-quoted string at a typographic quote, and a folder can be
     * named with one of those, so they are doubled as well.
     */
    fun quoted(text: String): String = "'" + text.replace(Regex("['‘’‚‛]")) { it.value + it.value } + "'"

    // --- Linux and the Mac ---

    /**
     * The same for sh, on Linux and a Mac.
     *
     * The folders come in through the environment rather than in the script, so the script never has to
     * quote a path, and no path appears on its command line -- where the check for anything still running
     * out of the folder would otherwise find the helper itself. Terminal signals are ignored: it starts in
     * the terminal's process group, and Ctrl+C or closing the window must not take it down with the player.
     *
     * What counts as running out of the folder: on Linux, any process whose executable is under it, from
     * /proc -- jpackage's launcher runs the JVM inside itself, so that is bin/noctorium; on a Mac, any
     * process whose command line names the folder, which the launcher script's `java` does. (The tests point
     * NOCTORIUM_UPDATE_PROC somewhere empty to try the Mac's way on a machine that has a /proc.)
     */
    val POSIX_SCRIPT: String = """
trap '' HUP INT QUIT TSTP
helper=${'$'}NOCTORIUM_UPDATE_HELPER
echo ${'$'}${'$'} > "${'$'}helper"
trap '[ "${'$'}(cat "${'$'}helper" 2>/dev/null)" = "${'$'}${'$'}" ] && rm -f "${'$'}helper"' EXIT
dir=${'$'}NOCTORIUM_UPDATE_FOLDER
next=${'$'}NOCTORIUM_UPDATE_NEXT
old=${'$'}NOCTORIUM_UPDATE_OLD
lock=${'$'}NOCTORIUM_UPDATE_LOCK
proc=${'$'}{NOCTORIUM_UPDATE_PROC:-/proc}
for pid in ${'$'}NOCTORIUM_UPDATE_WAIT_FOR; do
    while kill -0 "${'$'}pid" 2>/dev/null; do sleep 1; done
done
busy() {
    if [ -e "${'$'}proc/self/exe" ]; then
        for exe in "${'$'}proc"/[0-9]*/exe; do
            case ${'$'}(readlink "${'$'}exe" 2>/dev/null) in
                "${'$'}dir"/*) return 0 ;;
            esac
        done
        return 1
    fi
    ps -A -o command= 2>/dev/null | while IFS= read -r line; do
        case ${'$'}line in
            *"${'$'}dir"/*) exit 3 ;;
        esac
    done
    [ ${'$'}? -eq 3 ]
}
while busy; do sleep 2; done
[ -d "${'$'}next" ] || exit 0
mkdir "${'$'}lock" 2>/dev/null || exit 0
if [ ! -d "${'$'}next" ]; then
    # Another helper swapped it in between the look above and the lock.
    :
elif [ ! -d "${'$'}dir" ]; then
    # The CLI was removed while this waited, which is not something to undo.
    rm -rf "${'$'}next"
elif rm -rf "${'$'}old" && mv "${'$'}dir" "${'$'}old"; then
    if mv "${'$'}next" "${'$'}dir"; then rm -rf "${'$'}old"; else mv "${'$'}old" "${'$'}dir"; fi
fi
rmdir "${'$'}lock"
"""

    /** What [POSIX_SCRIPT] reads its folders and processes from. */
    fun posixEnvironment(folders: UpdateFolders, waitFor: List<Long>): Map<String, String> = mapOf(
        "NOCTORIUM_UPDATE_FOLDER" to folders.folder.toString(),
        "NOCTORIUM_UPDATE_NEXT" to folders.next.toString(),
        "NOCTORIUM_UPDATE_OLD" to folders.old.toString(),
        "NOCTORIUM_UPDATE_LOCK" to folders.lock.toString(),
        "NOCTORIUM_UPDATE_HELPER" to folders.helper.toString(),
        "NOCTORIUM_UPDATE_WAIT_FOR" to waitFor.joinToString(" "),
    )
}
