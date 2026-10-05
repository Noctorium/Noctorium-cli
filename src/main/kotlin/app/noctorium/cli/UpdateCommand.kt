package app.noctorium.cli

import app.noctorium.cli.update.CliUpdates
import app.noctorium.update.ReleaseFile
import kotlinx.coroutines.runBlocking
import java.io.PrintStream

/**
 * `noctorium update`: the check, the download, the checksum and the install, said out loud as they happen.
 * `noctorium update --check` stops after the first.
 *
 * The same [CliUpdates] the player uses on its own once a day, so asking by hand is never a different road
 * from the automatic one -- and it counts as that day's check.
 */
object UpdateCommand {

    fun run(options: List<String>, updates: CliUpdates, out: Out = Out(), print: PrintStream = System.out): Int {
        val checkOnly = options.any { it == "--check" || it == "-c" }
        options.firstOrNull { it != "--check" && it != "-c" }?.let {
            System.err.println("Unknown option $it. noctorium update, or noctorium update --check.")
            return 2
        }
        val installation = updates.installer.installation
        val waiting = updates.installer.tidy()
        print.println(out.bold("Noctorium CLI ${updates.current ?: cliVersion}"))
        if (updates.canInstall) print.println(out.dim("  Installed in ${installation.folder}"))
        if (waiting != null) print.println("  ${waiting} is already installed beside it, and takes over when every copy running from there has quit.")
        print.println("  Looking for a newer release…")

        val progress = Progress(out, print)
        val result = runBlocking {
            updates.run(
                install = !checkOnly,
                onDownload = progress::start,
                onProgress = progress::show,
            )
        }
        progress.finish()

        return when (result) {
            CliUpdates.Result.UpToDate -> {
                print.println("  ${out.good("✓")} This is the newest Noctorium CLI.")
                0
            }
            is CliUpdates.Result.Available -> {
                print.println("  ${out.accent("●")} Noctorium CLI ${result.update.version} is out; this is ${updates.current}.")
                if (result.update.pageUrl.isNotBlank()) print.println(out.dim("    ${result.update.pageUrl}"))
                if (result.advice.isNotBlank()) print.println("  ${result.advice}")
                // Only looking is a success; being unable to install what was asked for is not.
                if (checkOnly || updates.canInstall) 0 else 1
            }
            is CliUpdates.Result.Installed -> {
                print.println("  ${out.good("✓")} ${result.message}")
                // This command is about to quit, so what it waits for is any other copy still running.
                if (installation.folder != null) {
                    print.println(out.dim("    It is put in place as soon as nothing is running from ${installation.folder}."))
                }
                0
            }
            is CliUpdates.Result.Failed -> {
                System.err.println("  ${out.warn("!")} ${result.message}")
                1
            }
        }
    }

    /**
     * A download's progress: one line redrawn in place in a terminal, and a line every quarter when the output
     * is a file or a pipe, where carriage returns would only be noise.
     */
    private class Progress(private val out: Out, private val print: PrintStream) {
        private val live = System.console() != null
        private var total = 0L
        private var shownQuarter = -1
        private var lastDrawn = -1
        private var started = false
        private var checking = false

        fun start(file: ReleaseFile) {
            total = file.bytes
            started = true
            print.println("  Downloading ${file.name}${if (total > 0) " (${megabytes(total)})" else ""}")
        }

        fun show(fraction: Float) {
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            if (live) {
                if (percent == lastDrawn) return
                lastDrawn = percent
                val width = 30
                val filled = (fraction * width).toInt().coerceIn(0, width)
                print.print("\r    ${out.accent("█".repeat(filled))}${out.dim("░".repeat(width - filled))}  ${percent.toString().padStart(3)}%")
                print.flush()
            } else {
                val quarter = percent / 25
                if (quarter == shownQuarter) return
                shownQuarter = quarter
                print.println("    ${quarter * 25}%")
            }
            if (fraction >= 1f && !checking) {
                checking = true
                if (live) print.println()
                print.println("  Checking it against the release's checksum, and unpacking it…")
            }
        }

        fun finish() {
            if (started && live && !checking) print.println()
        }

        private fun megabytes(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    }
}
