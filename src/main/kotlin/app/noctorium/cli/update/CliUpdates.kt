package app.noctorium.cli.update

import app.noctorium.settings.AppDirectories
import app.noctorium.update.AvailableUpdate
import app.noctorium.update.InstallOutcome
import app.noctorium.update.ReleaseFile
import app.noctorium.update.UpdateChannel
import app.noctorium.update.UpdateCheck
import app.noctorium.update.UpdateChecker
import app.noctorium.update.UpdateDownloader
import app.noctorium.update.Version
import app.noctorium.update.fetchAndInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Keeping the terminal player up to date: the check, the download and the install, for the settings row,
 * for `noctorium update`, and on their own once a day.
 *
 * Built from the same parts as the desktop's updater -- [UpdateChecker] to ask GitHub, and
 * [fetchAndInstall] to download, verify and hand over -- and not from AppState's, because two of the three
 * places that use it have no AppState to drive, and the third wants to update without a prompt.
 *
 * Once a day, not every start. The player is started for a single search as often as for an evening, and
 * GitHub answers sixty questions an hour from an address with no account -- shared with everything else on
 * the same connection. When the last check was is kept in this program's own folder.
 */
class CliUpdates(
    val installer: CliUpdateInstaller,
    private val checker: UpdateChecker = UpdateChecker(installer.currentVersion, installer.channel, apiBase = apiBase()),
    private val record: UpdateRecord = UpdateRecord(),
    private val downloader: UpdateDownloader = UpdateDownloader(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val environment: (String) -> String? = System::getenv,
) {
    /** What the settings row shows while something is happening, and once a new copy is waiting. */
    data class Status(
        val checking: Boolean = false,
        /** Between 0 and 1 while an archive is downloading. */
        val downloading: Float? = null,
        /** A newer copy, installed beside this one and taking over when it quits. */
        val waiting: Version? = null,
    )

    sealed interface Result {
        data object UpToDate : Result

        /** Installed now, or [earlier] by another check, and waiting for this copy to quit. */
        data class Installed(val version: Version, val message: String, val earlier: Boolean = false) : Result

        /** Newer, and not installed: this copy cannot install it, or was only asked to look. */
        data class Available(val update: AvailableUpdate, val advice: String) : Result

        data class Failed(val message: String) : Result
    }

    private val mutableStatus = MutableStateFlow(Status(waiting = installer.waiting()))
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val running = Mutex()

    val current: Version? get() = installer.currentVersion

    /** Whether this copy can put an update in place itself. */
    val canInstall: Boolean get() = installer.channel == UpdateChannel.CLI_ARCHIVE

    /**
     * Checks for a newer release and, when [install] is true and this copy can, downloads, verifies and
     * installs it. [onDownload] is told which file is about to be fetched, and [onProgress] how far along.
     */
    suspend fun run(
        install: Boolean,
        onDownload: (ReleaseFile) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Result {
        if (!running.tryLock()) return Result.Failed("Already checking for an update.")
        try {
            record.checked(clock())
            mutableStatus.update { it.copy(checking = true) }
            val update = when (val check = checker.check()) {
                is UpdateCheck.Failed -> return Result.Failed(check.message)
                UpdateCheck.UpToDate -> return Result.UpToDate
                is UpdateCheck.Available -> check.update
            }
            // Fetched already, by this copy or another one, and waiting for its turn.
            installer.waiting()?.takeIf { it >= update.version }?.let { waiting ->
                return Result.Installed(waiting, installer.installedMessage(waiting), earlier = true)
            }
            if (!canInstall) return Result.Available(update, installer.installation.advice)
            if (!install) return Result.Available(update, "Install it with: noctorium update")
            val file = update.file ?: return Result.Failed(
                "Noctorium ${update.version} has no Noctorium CLI for this computer (${CliInstallation.systemName()}).",
            )
            onDownload(file)
            mutableStatus.update { it.copy(downloading = 0f) }
            val outcome = installer.fetchAndInstall(update, downloader) { fraction ->
                mutableStatus.update { it.copy(downloading = fraction) }
                onProgress(fraction)
            }
            return when (outcome) {
                is InstallOutcome.Installed -> Result.Installed(outcome.version, outcome.message)
                is InstallOutcome.Failed -> Result.Failed(outcome.message)
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return Result.Failed(failure.message ?: "The update did not finish.")
        } finally {
            // This process's download folder, emptied by the download or the install whichever way they went,
            // and the folder above it once no other copy is using it.
            runCatching { Files.deleteIfExists(installer.downloadDirectory()) }
            runCatching { installer.downloadDirectory().parent?.let(Files::deleteIfExists) }
            mutableStatus.update { Status(waiting = installer.waiting()) }
            running.unlock()
        }
    }

    /**
     * The check nobody asked for, made a little after the start so it does not compete with it, and only
     * when it is switched on and a day has passed since the last one. A copy that can install the update
     * does, with no prompt; one that cannot says where to get it. [tell] hears whatever is worth hearing --
     * nothing at all when there is nothing new -- and a failure never reaches anything but [tell].
     */
    fun automatically(scope: CoroutineScope, switchedOn: Boolean, after: Duration = 20.seconds, tell: (Result) -> Unit): Job? {
        if (automaticOffBecause(switchedOn) != null) return null
        // A build that was never told its version has nothing to compare a release with.
        if (current == null) return null
        if (!record.due(clock())) return null
        return scope.launch(Dispatchers.IO) {
            delay(after)
            // run() turns every failure into a Result; only the player closing in the meantime ends this early.
            val result = run(install = canInstall)
            if (result !is Result.UpToDate) runCatching { tell(result) }
        }
    }

    /** Why automatic updating is off, or null when it is on. */
    fun automaticOffBecause(switchedOn: Boolean): String? = when {
        disabledByEnvironment(environment) -> "$ENVIRONMENT is set"
        !switchedOn -> "switched off"
        else -> null
    }

    /**
     * A result as one line for a person, or null when there is nothing to say. [asked] is a check somebody
     * started, who deserves an answer even when it is that nothing has changed.
     */
    fun headline(result: Result, asked: Boolean): String? = when (result) {
        Result.UpToDate -> if (asked) "This is the newest Noctorium CLI${current?.let { " ($it)" }.orEmpty()}." else null
        is Result.Installed -> result.message
        is Result.Available -> "Noctorium CLI ${result.update.version} is out. ${result.advice}".trim()
        is Result.Failed -> if (asked) result.message else "Could not update Noctorium CLI: ${result.message}"
    }

    companion object {
        /** Set to anything but 0, false, no or off, it turns automatic updating off, whatever the setting says. */
        const val ENVIRONMENT = "NOCTORIUM_NO_UPDATE"

        /**
         * Where to ask instead of api.github.com, for rehearsing an update against a release served locally.
         * Not for anything else: the release it names is trusted exactly as GitHub's would be.
         */
        const val API_PROPERTY = "noctorium.update.api"

        fun apiBase(): String = System.getProperty(API_PROPERTY)?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)
            ?: UpdateChecker.DEFAULT_API_BASE

        fun disabledByEnvironment(environment: (String) -> String?): Boolean {
            val value = environment(ENVIRONMENT)?.trim()?.lowercase() ?: return false
            return value.isNotEmpty() && value !in setOf("0", "false", "no", "off")
        }

        /** The updater for the copy that is running. */
        fun forThisCopy(version: Version?): CliUpdates = CliUpdates(CliUpdateInstaller(version))
    }
}

/**
 * When the last check was, in `update.json` in this program's folder.
 *
 * Kept in a file of its own rather than the settings, which are the same shape on every Noctorium and are
 * rewritten whole whenever anything in them changes.
 */
class UpdateRecord(
    private val file: Path? = AppDirectories.resolve("update.json"),
    private val interval: Duration = 24.hours,
) {
    @Serializable
    private data class Stored(val lastChecked: Long = 0)

    fun lastChecked(): Long? = runCatching {
        val path = file?.takeIf(Files::isRegularFile) ?: return null
        json.decodeFromString(Stored.serializer(), Files.readString(path)).lastChecked.takeIf { it > 0 }
    }.getOrNull()

    /**
     * Whether a day has passed since the last check. A last check in the future means the clock was moved
     * back, and is no reason to stop checking until it catches up.
     */
    fun due(now: Long): Boolean {
        val last = lastChecked() ?: return true
        return now - last >= interval.inWholeMilliseconds || last > now + 60_000
    }

    fun checked(now: Long) {
        val path = file ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(Stored.serializer(), Stored(now)))
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
