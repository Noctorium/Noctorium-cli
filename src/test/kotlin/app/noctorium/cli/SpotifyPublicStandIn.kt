package app.noctorium.cli

import app.noctorium.domain.Album
import app.noctorium.domain.Artist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.spotify.SpotifyPublicCopies
import app.noctorium.spotify.SpotifyPublicList
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Spotify with nobody signed in, made up: public playlists followed by their links, as their last copies are kept
 * between runs, and a data download as Spotify sends it, with playlists and Liked Songs in it.
 *
 * Nothing here reaches Spotify. The copies are what core would have read from the playlists' public pages, handed to
 * it as if it had, so the pages show them without anything being read; and every playlist a test adds is one core
 * turns away before reading anything -- an album's link, or one already followed. Every name is made up.
 */
object SpotifyPublicStandIn {
    /** Read, and short enough that the page showed all of it. */
    const val NIGHT_BUS = "0MadeUpNightBus0000001"

    /** Read, and as long as the page shows, so it may go on past its hundred songs. */
    const val RAINY_DAYS = "0MadeUpRainyDays000002"

    /** Followed, and not read yet. */
    const val NOT_READ = "0MadeUpNotReadYet00003"

    /**
     * Read once, and private since: not an id Spotify could have, so that core's own Spotify, asked for it, finds it
     * missing without asking Spotify anything. See [BandcampStandIn.state]'s ownServices.
     */
    const val GONE = "madeUpGone"

    const val OWNER = "Sample Listener"

    fun song(n: Int, title: String = "Harbour Lights $n", artist: String = "The Lamplighters") = Track(
        provider = ProviderType.SPOTIFY,
        id = "0MadeUpSong" + n.toString().padStart(11, '0'),
        title = title,
        artists = listOf(Artist("0MadeUpArtist000000001", artist, ProviderType.SPOTIFY)),
        album = Album("0MadeUpAlbum0000000001", "Harbour Lights", emptyList(), ProviderType.SPOTIFY),
        durationMs = 200_000L + n * 1_000L,
        sourceUrl = "https://open.spotify.com/track/0MadeUpSong" + n.toString().padStart(11, '0'),
    )

    val nightBus = SpotifyPublicList(NIGHT_BUS, "Night Bus Home", OWNER, tracks = (1..12).map(::song), readAtEpochSeconds = 1_790_000_000)
    val rainyDays = SpotifyPublicList(
        RAINY_DAYS, "Rainy Days", "Paper Satellites", tracks = (101..200).map(::song), mayBeLonger = true, readAtEpochSeconds = 1_790_000_000,
    )
    val gone = SpotifyPublicList(GONE, "Saturday Morning", OWNER, tracks = (201..208).map(::song), readAtEpochSeconds = 1_780_000_000)

    /** The followed playlists' last copies, in memory: every one but [NOT_READ], which has none yet. */
    fun copies(): SpotifyPublicCopies = SpotifyPublicCopies(null).apply {
        put(nightBus)
        put(rainyDays)
        put(gone)
    }

    /** A link to one of them, as Spotify's share button copies it. */
    fun link(id: String) = "https://open.spotify.com/playlist/$id?si=0madeupshare0"

    /** An album's link, which can be opened but not followed: core says so without reading anything. */
    const val ALBUM_LINK = "https://open.spotify.com/album/0MadeUpAlbum0000000001"

    /** One entry of a playlist in the download, as several years of it have them. */
    private fun item(n: Int, title: String, artist: String = "Night Tram") =
        """{"track":{"trackName":"$title","artistName":"$artist","albumName":"Night Shift","trackUri":"spotify:track:0MadeUpSong${n.toString().padStart(11, '0')}"},""" +
            """"episode":null,"localTrack":null,"audiobook":null,"addedDate":"2025-03-01"}"""

    private const val EPISODE =
        """{"track":null,"episode":{"episodeName":"Made-Up Talk, Part 4","showName":"Made-Up Talk","episodeUri":"spotify:episode:0MadeUpEpisode00000001"},"localTrack":null,"audiobook":null,"addedDate":"2025-03-01"}"""

    /** Spotify's "Account data" download: two playlists, one with a podcast episode in it, and three liked songs. */
    fun download(): ByteArray {
        val playlists = """{"playlists":[""" +
            """{"name":"Late Train","lastModifiedDate":"2025-04-01","collaborators":[],"description":null,"numberOfFollowers":0,"items":[${item(1, "Platform Nine")},${item(2, "Last Stop")},$EPISODE]},""" +
            """{"name":"Sunday Kitchen","lastModifiedDate":"2025-04-01","collaborators":[],"description":null,"numberOfFollowers":0,"items":[${item(3, "Slow Toast")}]}""" +
            "]}"
        val library = """{"tracks":[${(1..3).joinToString(",") { """{"artist":"Night Tram","album":"Night Shift","track":"Liked $it","uri":"spotify:track:0MadeUpSong${(500 + it).toString().padStart(11, '0')}"}""" }}],""" +
            """"albums":[],"shows":[],"episodes":[],"bannedTracks":[],"artists":[],"bannedArtists":[],"other":[]}"""
        return zip(
            "Spotify Account Data/" to ByteArray(0),
            "Spotify Account Data/Userdata.json" to """{"username":"made-up-listener","country":"GB"}""".toByteArray(),
            "Spotify Account Data/Playlist1.json" to playlists.toByteArray(),
            "Spotify Account Data/YourLibrary.json" to library.toByteArray(),
        )
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
