package app.noctorium.cli

import app.noctorium.auth.isSoundCloudSignedIn
import app.noctorium.auth.isYouTubeSignedIn
import app.noctorium.auth.parsePastedCookies
import app.noctorium.auth.readCookieFile
import app.noctorium.auth.writeCookieFile
import app.noctorium.core.AppState
import app.noctorium.settings.AppDirectories
import app.noctorium.settings.NoctoriumPreferences
import app.noctorium.settings.SettingsRepository
import app.noctorium.social.SoundCloudToken
import app.noctorium.social.YouTubeChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Signing the terminal in without a browser in it.
 *
 * The desktop signs in on Google's and SoundCloud's own pages, in a browser of its own; a terminal has
 * nowhere to show one. So there are three ways in instead, all ending in the same cookie file the desktop
 * would have written:
 *
 * - **The desktop's own sign-in, copied.** Somebody with Noctorium on this computer already signed in there.
 *   Its session is copied -- never moved, never shared live -- along with the channel it acts as and the
 *   browser identity it was made with, so YouTube sees the same client it already knows.
 * - **The phone, for YouTube Music.** The phone hands its session over the local network, encrypted, by
 *   scanning a code: AppState already does this for the desktop.
 * - **A cookies.txt**, exported from any browser that is signed in, or cookies pasted as text.
 *
 * Bandcamp needs none of this: a fan's collection is public, and the name in their address is all it takes.
 * The desktop's is offered all the same, so it need not be typed twice.
 */
object DesktopSignIn {
    private fun desktopPreferences(): NoctoriumPreferences? {
        val settings = CliHome.desktop?.resolve("settings.json")?.takeIf(Files::isRegularFile) ?: return null
        return runCatching { SettingsRepository(settings).load() }.getOrNull()
    }

    private fun desktopFile(stored: String, name: String): Path? {
        val named = stored.takeIf(String::isNotBlank)?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf(Files::isRegularFile)
        return named ?: CliHome.desktop?.resolve(name)?.takeIf(Files::isRegularFile)
    }

    /** Whether the desktop on this computer has a YouTube Music session to copy. */
    fun youTubeAvailable(): Boolean {
        val preferences = desktopPreferences() ?: return false
        val file = desktopFile(preferences.youtubeCookies.cookieFile, "youtube.cookies") ?: return false
        return isYouTubeSignedIn(readCookieFile(file))
    }

    fun soundCloudAvailable(): Boolean {
        val preferences = desktopPreferences() ?: return false
        val file = desktopFile(preferences.soundCloudCookies.cookieFile, "soundcloud.cookies") ?: return false
        return isSoundCloudSignedIn(readCookieFile(file))
    }

    /** Copies the desktop's YouTube Music session. Null when it worked, otherwise what went wrong. */
    fun copyYouTube(state: AppState): String? {
        val preferences = desktopPreferences() ?: return "Noctorium on this computer has no settings to copy from."
        val source = desktopFile(preferences.youtubeCookies.cookieFile, "youtube.cookies")
            ?: return "Noctorium on this computer is not signed in to YouTube Music."
        if (!isYouTubeSignedIn(readCookieFile(source))) return "The desktop's YouTube Music session has run out. Sign in there again first."
        val target = AppDirectories.resolve("youtube.cookies") ?: return "There is nowhere to keep the session."
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        state.completeYouTubeSignIn(target.toString(), preferences.youtubeUserAgent)
        if (preferences.youtubePageId.isNotBlank()) {
            state.setYouTubeChannel(
                YouTubeChannel(
                    pageId = preferences.youtubePageId,
                    name = preferences.youtubeChannelName,
                    authUser = preferences.youtubeAuthUser,
                    photoUrl = preferences.youtubeChannelPhoto.takeIf(String::isNotBlank),
                ),
            )
        }
        return null
    }

    fun copySoundCloud(state: AppState): String? {
        val preferences = desktopPreferences() ?: return "Noctorium on this computer has no settings to copy from."
        val source = desktopFile(preferences.soundCloudCookies.cookieFile, "soundcloud.cookies")
            ?: return "Noctorium on this computer is not signed in to SoundCloud."
        val target = AppDirectories.resolve("soundcloud.cookies") ?: return "There is nowhere to keep the session."
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        if (preferences.soundCloudUsername.isNotBlank()) state.setSoundCloudUsername(preferences.soundCloudUsername)
        state.completeSoundCloudSignIn(
            target.toString(),
            SoundCloudToken.fromCookieFile(target),
            preferences.soundCloudUsername.takeIf(String::isNotBlank),
        )
        return null
    }

    /**
     * The Bandcamp name whose collection Noctorium on this computer shows, or null when it shows none.
     *
     * Not a session, only the name in somebody's bandcamp.com address, so there is nothing to copy but the
     * name itself; it is checked with Bandcamp again here, as any name given here is.
     */
    fun bandcampName(): String? = desktopPreferences()?.bandcampUsername?.takeIf(String::isNotBlank)

    /** Shows the collection Noctorium on this computer shows. Null when it has one, otherwise why not. */
    fun copyBandcamp(state: AppState): String? {
        val name = bandcampName() ?: return "Noctorium on this computer shows no Bandcamp collection."
        state.setBandcampUsername(name)
        return null
    }

    /**
     * Signs in from cookies: a cookies.txt [path], or the text of one -- a browser extension's export, or a
     * Cookie header -- for either service.
     */
    fun fromCookies(state: AppState, youTube: Boolean, path: Path?, text: String?): String? {
        val contents = when {
            path != null -> runCatching { Files.readString(path) }.getOrElse { return "Could not read $path: ${it.message}" }
            !text.isNullOrBlank() -> text
            else -> return "No cookies were given."
        }
        if (youTube) {
            // Checked live with YouTube before it is kept, and the answer comes back as a message.
            state.importYouTubeCookies(contents)
            return null
        }
        val cookies = parsePastedCookies(contents, ".soundcloud.com")
        if (!isSoundCloudSignedIn(cookies)) return "These cookies have no SoundCloud sign-in in them (no oauth_token)."
        val target = AppDirectories.resolve("soundcloud.cookies") ?: return "There is nowhere to keep the session."
        writeCookieFile(cookies, target)
        state.completeSoundCloudSignIn(target.toString(), SoundCloudToken.fromCookieFile(target))
        return null
    }
}
