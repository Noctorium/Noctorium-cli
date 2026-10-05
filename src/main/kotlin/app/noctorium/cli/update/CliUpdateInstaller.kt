package app.noctorium.cli.update

import app.noctorium.settings.AppDirectories
import app.noctorium.update.UpdateChannel
import app.noctorium.update.UpdateInstaller
import app.noctorium.update.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the terminal player does with a downloaded, verified update of itself.
 *
 * Unpacks it beside the folder it is running from, checks that what came out is a copy of the terminal
 * player with its launcher where the old one has it, leaves it waiting there as `.update-next`, and starts
 * the helper that swaps the two once this copy has quit ([SwapHelper]). The swap is the same on every
 * system, and it never happens under a running player. The PATH entry on Windows and the link in
 * ~/.local/bin elsewhere name the folder's path, which does not change, so neither is touched.
 */
class CliUpdateInstaller(
    override val currentVersion: Version?,
    val installation: CliInstallation = CliInstallation.detect(),
    /**
     * Where archives are downloaded before they are unpacked: a folder per process inside this one, so that
     * two copies fetching the same update at once cannot write into each other's file.
     */
    private val downloads: Path = Path.of(System.getProperty("java.io.tmpdir"), "noctorium-cli-update"),
    /** What the helper says, if anything. */
    private val log: Path? = AppDirectories.resolve("logs", "update.log"),
    /** The processes the swap waits for: this one, normally. Tests name one that has already ended. */
    private val waitFor: () -> List<Long> = { installation.folder?.let(SwapHelper::processesToOutlive) ?: emptyList() },
    /** Starts the helper. Tests replace it to run the helper themselves, or not at all. */
    private val startHelper: (UpdateFolders, List<Long>) -> String? = { folders, pids ->
        SwapHelper.start(folders, pids, installation.windows, log)
    },
) : UpdateInstaller {

    override val channel: UpdateChannel get() = installation.channel

    override fun downloadDirectory(): Path = downloads.resolve(ProcessHandle.current().pid().toString())

    override suspend fun install(file: Path): String? = withContext(Dispatchers.IO) {
        try {
            val folders = installation.folders ?: return@withContext installation.advice.ifBlank {
                "This copy of Noctorium CLI cannot update itself."
            }
            folders.stage(file, installation.launcher, installation.windows)?.let { return@withContext it }
            startHelper(folders, waitFor())
        } finally {
            // Unpacked or not, the archive has done its job; a failed one is not worth keeping either.
            runCatching { Files.deleteIfExists(file) }
            runCatching { Files.deleteIfExists(file.parent) }
        }
    }

    override fun installedMessage(version: Version): String =
        "Noctorium CLI $version is installed, and takes over when you quit."

    /** The newer copy waiting to be swapped in, if there is one. */
    fun waiting(): Version? = installation.folders?.waiting()?.takeIf { waiting ->
        currentVersion == null || waiting > currentVersion
    }

    /**
     * Clears away what an earlier update left behind and, when a newer copy is still waiting, starts a
     * helper for it again: the last one may have been stopped before it could swap -- by a shutdown, by
     * the terminal it ran in taking everything with it -- and without one the update would sit there until
     * the next is found. Returns the version waiting, if any.
     */
    fun tidy(): Version? {
        val folders = installation.folders ?: return null
        // Downloads left by copies that were stopped partway through one.
        runCatching {
            Files.list(downloads).use { it.toList() }.forEach { folder ->
                val owner = folder.fileName.toString().toLongOrNull()
                if (owner == null || !ProcessHandle.of(owner).map { it.isAlive }.orElse(false)) UpdateFolders.deleteTree(folder)
            }
        }
        val waiting = runCatching { folders.tidy(currentVersion, SwapHelper::anythingRunningFrom) }.getOrNull()
            ?: return null
        startHelper(folders, waitFor())
        return waiting
    }
}
