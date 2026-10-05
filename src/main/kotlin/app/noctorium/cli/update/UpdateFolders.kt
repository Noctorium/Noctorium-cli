package app.noctorium.cli.update

import app.noctorium.update.Version
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.name

/**
 * The install folder, and the few names beside it that an update uses.
 *
 * All of them are siblings of the folder, so that every step from "unpacked" to "in place" is a rename on
 * one volume -- instant, and either done or not done, never half -- rather than a copy across two.
 *
 * - `<name>.update-<pid>` is where one process unpacks an archive. The process id is in the name so that
 *   two copies updating at once cannot unpack into each other, and so that one left by a process that has
 *   since died is known to be rubbish.
 * - `<name>.update-next` is the new copy, whole and checked, waiting for nothing to be running out of the
 *   old one.
 * - `<name>.old-update` is the copy that was replaced, until it can be deleted.
 * - `<name>.update-lock` is held while a helper swaps the two, so that two helpers never do it at once.
 * - `<name>.update-helper` holds the process id of the helper waiting to swap them, while it waits, so that
 *   a copy started again in the meantime knows one is already on its way and does not start another.
 *
 * Every one of them starts `<name>.update-` or `<name>.old-`, and none is `<name>.partial` or `<name>.old`,
 * which are the installer's own: noctorium-installer-cli swaps a folder the same way when it installs, and
 * the two leave each other's alone.
 */
class UpdateFolders(val folder: Path) {
    val parent: Path = requireNotNull(folder.parent) { "$folder has no folder above it" }
    private val name = folder.fileName.toString()

    val next: Path = parent.resolve("$name.update-next")
    val old: Path = parent.resolve("$name.old-update")
    val lock: Path = parent.resolve("$name.update-lock")
    val helper: Path = parent.resolve("$name.update-helper")
    fun unpacking(pid: Long): Path = parent.resolve("$name.update-$pid")

    /** Whether [sibling] is one process's unpacking, `<name>.update-<pid>`, and whose. */
    private fun unpackingOwner(sibling: Path): Long? =
        sibling.name.takeIf { it.startsWith("$name.update-") }?.removePrefix("$name.update-")?.toLongOrNull()

    /**
     * Unpacks [archive] and leaves the result waiting as [next], or says why not.
     *
     * The new copy has to have its [launcher] where the old one has it, because that is what the PATH entry
     * and the link in ~/.local/bin point at; a release that moved it would install perfectly and leave the
     * `noctorium` command broken. And when the archive's name says which version it is, the program inside
     * has to say the same: an archive that installed and then reported the old number would be fetched
     * again every day, forever.
     */
    fun stage(archive: Path, launcher: String, windows: Boolean, pid: Long = ProcessHandle.current().pid()): String? {
        val work = unpacking(pid)
        return try {
            deleteTree(work)
            Files.createDirectories(work)
            Archives.unpack(archive, work)
            val root = Archives.singleFolderIn(work) ?: work
            val launcherFile = root.resolve(launcher)
            if (!Files.isRegularFile(launcherFile)) {
                return "The update has no $launcher in it, so it was not installed. The archive is not the shape this copy expects."
            }
            if (!windows && !Files.isExecutable(launcherFile)) {
                return "The update's $launcher cannot be run, so it was not installed."
            }
            val promised = versionInArchiveName(archive.fileName.toString())
            val inside = versionIn(root)
            if (promised != null && inside != null && promised != inside) {
                return "The update calls itself $promised but contains Noctorium CLI $inside, so it was not installed."
            }
            deleteTree(next)
            moveWhenFree(root, next)
            null
        } catch (refused: Archives.Refused) {
            refused.message
        } catch (failure: Exception) {
            "Could not unpack the update beside $folder: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            runCatching { deleteTree(work) }
        }
    }

    /** The version of the copy waiting in [next], or null when there is none, or it does not say. */
    fun waiting(): Version? = if (Files.isDirectory(next)) versionIn(next) else null

    /**
     * Clears away what an earlier update left, and says whether a newer copy is still waiting.
     *
     * Each of these is left only by something that was interrupted -- a process killed while it unpacked, a
     * helper stopped by a shutdown halfway -- so finding one is not an error, just tidying. A copy waiting
     * in [next] that is not newer than [running] is stale: something else has installed this version or a
     * newer one since, and swapping it in would replace that with the same version or an older one.
     */
    fun tidy(running: Version?, busy: (Path) -> Boolean, now: Instant = Instant.now()): Version? {
        runCatching {
            Files.list(parent).use { siblings -> siblings.toList() }.forEach { sibling ->
                val owner = unpackingOwner(sibling) ?: return@forEach
                if (!ProcessHandle.of(owner).map { it.isAlive }.orElse(false)) deleteTree(sibling)
            }
        }
        // Somebody may still be running out of a replaced copy -- an installer can swap one in under a
        // running player -- and on Linux deleting the folder under it is what breaks it.
        if (Files.exists(old, LinkOption.NOFOLLOW_LINKS) && !busy(old)) runCatching { deleteTree(old) }
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && olderThan(lock, Duration.ofMinutes(10), now)) runCatching { deleteTree(lock) }
        if (!Files.isDirectory(next)) return null
        val waiting = versionIn(next)
        if (waiting == null || (running != null && waiting <= running)) {
            runCatching { deleteTree(next) }
            return null
        }
        return waiting
    }

    /**
     * Renames [from] to [to], trying again for a few seconds. On Windows a virus scanner reads every file the
     * moment it is written, and a folder with one of them open cannot be renamed until it lets go.
     */
    private fun moveWhenFree(from: Path, to: Path) {
        var attempt = 0
        while (true) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (busy: java.nio.file.FileSystemException) {
                // Something missing, or already in the way, will not be fixed by waiting.
                val lasting = busy is java.nio.file.NoSuchFileException || busy is java.nio.file.FileAlreadyExistsException
                if (lasting || ++attempt >= 20) throw busy
                Thread.sleep(250)
            }
        }
    }

    private fun olderThan(path: Path, age: Duration, now: Instant): Boolean = runCatching {
        val modified: FileTime = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
        modified.toInstant().plus(age).isBefore(now)
    }.getOrDefault(false)

    companion object {
        /**
         * The version in a release archive's name: `noctorium-cli-0.9.2-linux-x64.tar.gz` is 0.9.2, and
         * `noctorium-cli-1.0.0-beta.1-windows-x64.zip` is 1.0.0-beta.1.
         */
        fun versionInArchiveName(name: String): Version? =
            Regex("""^noctorium-cli-(.+)-(?:windows|linux|macos)-[A-Za-z0-9_]+\.(?:zip|tar\.gz|tgz)$""", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.get(1)?.let(Version::parse)

        /**
         * Which version a CLI folder holds, from the name of its own jar: `noctorium-cli-0.9.2.jar`, in `app/`
         * on Windows, `lib/app/` on Linux and `lib/` on a Mac. The jar is named from the same number the build
         * writes into the program, so the two cannot disagree.
         */
        fun versionIn(folder: Path): Version? = listOf("app", "lib/app", "lib").asSequence()
            .map(folder::resolve)
            .filter(Files::isDirectory)
            .mapNotNull { directory ->
                runCatching {
                    Files.list(directory).use { files ->
                        files.map { it.name }
                            .filter { it.startsWith("noctorium-cli-") && it.endsWith(".jar") }
                            .toList()
                    }
                }.getOrNull()?.firstNotNullOfOrNull { Version.parse(it.removePrefix("noctorium-cli-").removeSuffix(".jar")) }
            }
            .firstOrNull()

        /** Deletes [path] and everything under it, following no links out of it. */
        fun deleteTree(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.list(path).use { it.toList() }.forEach(::deleteTree)
            }
            try {
                Files.delete(path)
            } catch (denied: java.nio.file.AccessDeniedException) {
                // A read-only file on Windows -- a jlink runtime has some -- refuses to go until it is writable.
                path.toFile().setWritable(true)
                Files.delete(path)
            }
        }
    }
}
