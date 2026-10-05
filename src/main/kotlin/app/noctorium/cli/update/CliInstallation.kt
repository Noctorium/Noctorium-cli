package app.noctorium.cli.update

import app.noctorium.update.UpdateChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where this copy of the terminal player is running from, and whether it may replace itself there.
 *
 * The release's archive is a whole program folder -- a launcher, its own Java runtime, its jars -- and an
 * installed copy is simply that folder, unpacked somewhere its user owns: `%LOCALAPPDATA%\Programs\Noctorium
 * CLI` on Windows, put on the PATH, and `~/.local/share/noctorium-cli` elsewhere, with a link to its launcher
 * in `~/.local/bin`. Updating it is putting the next archive's folder in the same place, so the PATH entry
 * and the link go on pointing at the right thing without being touched.
 *
 * Only a folder of that shape, that this account can change, gets the [UpdateChannel.CLI_ARCHIVE] channel.
 * Anything else is [UpdateChannel.UNMANAGED] and told how it should be updated instead: a run from Gradle has
 * no such folder at all, and one under Program Files, /usr or /opt was put there by somebody or something
 * with the right to -- an administrator, a package manager -- and replacing it behind their back would leave
 * whatever keeps track of it believing something untrue.
 */
data class CliInstallation(
    /** The folder the archive became, or null when this copy is not running out of one. */
    val folder: Path?,
    /**
     * The launcher, relative to [folder]: `noctorium.exe` on Windows, `bin/noctorium` elsewhere. What the PATH
     * entry and the link point at, so the new copy has to have it in the same place.
     */
    val launcher: String,
    val channel: UpdateChannel,
    /** For an unmanaged copy, the sentence that says how to update it instead. Empty otherwise. */
    val advice: String,
    val windows: Boolean,
) {
    /** The names beside the folder that an update uses, for a copy that can update itself. */
    val folders: UpdateFolders? get() = folder?.takeIf { channel == UpdateChannel.CLI_ARCHIVE }?.let(::UpdateFolders)

    /**
     * What there is to go on, read from this process, or given by a test.
     *
     * [launcherPath] is `jpackage.app-path`, which the launcher jpackage builds sets to its own path on
     * Windows and Linux. The Mac's launcher is a shell script of the build's own, which says where its
     * folder is with [folderProperty] instead (see `macCliFolder` in build.gradle.kts).
     */
    data class Facts(
        val system: String,
        val launcherPath: String?,
        val folderProperty: String?,
        val environment: (String) -> String?,
    ) {
        companion object {
            fun here() = Facts(
                system = systemName(),
                launcherPath = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank),
                folderProperty = System.getProperty(FOLDER_PROPERTY)?.takeIf(String::isNotBlank),
                environment = System::getenv,
            )
        }
    }

    companion object {
        /** Set by the Mac's launcher script to the folder it found itself in. */
        const val FOLDER_PROPERTY = "noctorium.cli.folder"

        /** How the README says to install it, for the copies that cannot update themselves. */
        private const val INSTALLER = "noctorium-installer-cli --product cli"

        fun detect(facts: Facts = Facts.here()): CliInstallation {
            val windows = facts.system == "windows"
            val launcher = if (windows) "noctorium.exe" else "bin/noctorium"
            fun unmanaged(folder: Path?, advice: String) = CliInstallation(folder, launcher, UpdateChannel.UNMANAGED, advice, windows)

            val named = facts.folderProperty?.let(Path::of)
                ?: facts.launcherPath?.let(Path::of)?.let { path ->
                    val parent = path.toAbsolutePath().parent
                    // bin/noctorium on Linux; noctorium.exe at the top on Windows.
                    if (!windows && parent?.fileName?.toString() == "bin") parent.parent else parent
                }
                ?: return unmanaged(
                    null,
                    "This copy runs from a build rather than from the release's folder, so it does not update " +
                        "itself: build it again, or install Noctorium CLI with $INSTALLER.",
                )
            val folder = runCatching { named.toRealPath() }.getOrNull()
                ?: return unmanaged(named, "Could not find the folder this copy runs from, $named, so it does not update itself.")

            if (!Files.isRegularFile(folder.resolve(launcher))) {
                return unmanaged(
                    folder,
                    "$folder is not laid out like the release's folder, so this copy does not update itself: " +
                        "install Noctorium CLI with $INSTALLER.",
                )
            }
            // The folder `./gradlew packageCli` makes is shaped exactly like an installed one, and is somebody's
            // build: replacing it with the release would quietly undo whatever they were building.
            if (folder.parent?.fileName?.toString() == "package" && folder.parent?.parent?.fileName?.toString() == "build") {
                return unmanaged(
                    folder,
                    "This copy is a build's output, in $folder, so it does not update itself: build it again.",
                )
            }
            if (belongsToTheSystem(folder.toString(), windows, facts.environment)) {
                return unmanaged(
                    folder,
                    "This copy is in $folder, which belongs to the system or a package manager rather than to " +
                        "you, so it does not update itself: update it with whatever installed it there.",
                )
            }
            val parent = folder.parent
            if (parent == null || !Files.isWritable(folder) || !Files.isWritable(parent)) {
                return unmanaged(
                    folder,
                    "This account cannot change $folder, so this copy does not update itself: update it the " +
                        "way it was installed, or install Noctorium CLI for yourself with $INSTALLER.",
                )
            }
            return CliInstallation(folder, launcher, UpdateChannel.CLI_ARCHIVE, "", windows)
        }

        /**
         * Whether [folder] is somewhere a person's own copy would not be: a system folder, or one a package
         * manager owns. Decided by place rather than by asking whether it can be written, because an
         * administrator can write to Program Files and Homebrew's prefix is often the user's own -- and in
         * both cases somebody else is keeping track of what is there.
         */
        internal fun belongsToTheSystem(folder: String, windows: Boolean, environment: (String) -> String?): Boolean {
            if (windows) {
                val places = listOf("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "SystemRoot", "ProgramData")
                    .mapNotNull { environment(it)?.takeIf(String::isNotBlank) }
                // Either slash, which Windows takes as the same thing, so a path written either way is read alike.
                fun normal(path: String) = path.replace('/', '\\').trimEnd('\\').lowercase() + "\\"
                val here = normal(folder)
                return places.any { place -> here.startsWith(normal(place)) }
            }
            val here = folder.trimEnd('/') + "/"
            return UNIX_SYSTEM_PLACES.any { here.startsWith("$it/") }
        }

        /**
         * Everything under these is the system's, or a package manager's: /usr covers /usr/local, where
         * Homebrew lives on an Intel Mac, and /opt covers /opt/homebrew on Apple silicon.
         */
        private val UNIX_SYSTEM_PLACES = listOf(
            "/usr", "/opt", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32", "/etc", "/var", "/snap",
            "/nix", "/gnu", "/home/linuxbrew", "/Applications", "/Library", "/System",
        )

        /** This system, as the release names it: windows, macos or linux. */
        fun systemName(): String {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.startsWith("windows") -> "windows"
                name.startsWith("mac") -> "macos"
                else -> "linux"
            }
        }
    }
}
