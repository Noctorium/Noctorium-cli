package app.noctorium.cli

import app.noctorium.cli.update.CliUpdates
import app.noctorium.connect.DeviceKind
import app.noctorium.core.AppState
import app.noctorium.discord.DiscordPresenceManager
import app.noctorium.downloads.AudioConverter
import app.noctorium.downloads.DownloadManager
import app.noctorium.playback.AccountProbe
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.QueueStore
import app.noctorium.playback.YtDlpService
import app.noctorium.settings.SecureCredentialStore
import app.noctorium.update.Version

/** The version this build was made as, from the resource the build writes. */
val cliVersion: String by lazy {
    runCatching {
        CliHome::class.java.getResourceAsStream("/noctorium-cli-version.properties")?.use { stream ->
            java.util.Properties().apply { load(stream) }.getProperty("version")
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: "development"
}

/** The pieces a terminal Noctorium is made of, before the player is chosen. */
class CliParts(
    val bridge: TerminalBridge = TerminalBridge(),
    val backend: YtDlpService = YtDlpService(),
    val credentials: SecureCredentialStore = SecureCredentialStore(rememberForSession = true),
) {
    val downloads = DownloadManager(backend, converter = AudioConverter())

    /** Updating this copy: found out once, when something first asks, and shared by everything that does. */
    val updates: CliUpdates by lazy { CliUpdates.forThisCopy(Version.parse(cliVersion)) }
}

/**
 * The same AppState the desktop builds, with a terminal's answers where the desktop has a window's.
 *
 * Everything the desktop does that is not drawing comes along: the queue, likes written to the real account,
 * scrobbling, Discord, lyrics, Connect, downloads. What differs is the bridge ([TerminalBridge]), the player
 * (handed in, because `noctorium web` plays in a browser), and the SoundCloud writes, which on the desktop go
 * through its hidden Chromium and here go over plain HTTP -- SoundCloud may ask for a captcha for those, and
 * the message that comes back says so.
 *
 * Updating is the other difference. AppState does not check at launch here: [CliUpdates] does, at most once a
 * day and only for the commands that stay open, and installs what it finds itself.
 *
 * [interactive] is the player and `noctorium web`, which are opened to be used. Only they read Home and the
 * likes at launch, and only they keep the queue between launches and pick it up again. Every other command
 * does one thing and stops, and is started many times a day: a dozen of them should not be a dozen readings of
 * every library -- the traffic VK freezes accounts for -- and none of them should put back a queue it is never
 * going to play, or write the kept one again with nothing playing, which would lose where it was left.
 */
fun cliAppState(parts: CliParts, engine: PlaybackEngine, interactive: Boolean = false): AppState = AppState(
    ytDlp = parts.backend,
    credentials = parts.credentials,
    system = parts.bridge,
    downloads = parts.downloads,
    playbackEngine = engine,
    accountProbe = AccountProbe(),
    discordPresence = DiscordPresenceManager(),
    deviceName = { deviceName() },
    deviceKind = DeviceKind.DESKTOP,
    updateInstaller = parts.updates.installer,
    checkForUpdatesAtLaunch = false,
    refreshAtLaunch = interactive,
    queueStore = keptQueue(interactive),
    // The desktop app may share this computer's credential store, and a VK session cannot be shared: VK
    // replaces its cookies as it renews them, so two programs holding one would sign each other out.
    sessionNamespace = "cli",
)

/**
 * Where a command's AppState keeps the queue between launches: the player's own file for the [interactive]
 * ones, and nowhere for the rest, which then neither put the kept queue back nor write it or clear it.
 */
internal fun keptQueue(interactive: Boolean): QueueStore = if (interactive) QueueStore() else QueueStore(null)

private fun deviceName(): String {
    val machine = app.noctorium.platform.computerName() ?: "This computer"
    return "$machine (terminal)"
}
