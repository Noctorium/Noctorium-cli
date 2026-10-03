package app.noctorium.cli

import app.noctorium.connect.DeviceKind
import app.noctorium.core.AppState
import app.noctorium.discord.DiscordPresenceManager
import app.noctorium.downloads.AudioConverter
import app.noctorium.downloads.DownloadManager
import app.noctorium.playback.AccountProbe
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.YtDlpService
import app.noctorium.settings.SecureCredentialStore
import app.noctorium.update.UpdateInstaller
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
}

/**
 * The same AppState the desktop builds, with a terminal's answers where the desktop has a window's.
 *
 * Everything the desktop does that is not drawing comes along: the queue, likes written to the real account,
 * scrobbling, Discord, lyrics, Connect, downloads. What differs is the bridge ([TerminalBridge]), the player
 * (handed in, because `noctorium web` plays in a browser), and the SoundCloud writes, which on the desktop go
 * through its hidden Chromium and here go over plain HTTP -- SoundCloud may ask for a captcha for those, and
 * the message that comes back says so.
 */
fun cliAppState(parts: CliParts, engine: PlaybackEngine): AppState = AppState(
    ytDlp = parts.backend,
    credentials = parts.credentials,
    system = parts.bridge,
    downloads = parts.downloads,
    playbackEngine = engine,
    accountProbe = AccountProbe(),
    discordPresence = DiscordPresenceManager(),
    deviceName = { deviceName() },
    deviceKind = DeviceKind.DESKTOP,
    updateInstaller = UpdateInstaller.none(Version.parse(cliVersion)),
)

private fun deviceName(): String {
    val machine = app.noctorium.platform.computerName() ?: "This computer"
    return "$machine (terminal)"
}
