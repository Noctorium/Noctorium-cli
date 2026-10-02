package app.noctorium.cli.web

import app.noctorium.playback.YtDlpService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The HLS rewrite: every address in a playlist comes back pointing through Noctorium, and the hosts it named
 * are the only ones its segments may be fetched from -- so the proxy cannot be talked into fetching anything
 * else for anybody.
 */
class AudioProxyTest {
    private val proxy = AudioProxy(BrowserEngine(YtDlpService()) { null }, YtDlpService())

    @Test
    fun `segments and keys are pointed back through the proxy`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=AES-128,URI="https://cf-hls-media.sndcdn.com/key/1"
            #EXTINF:9.98,
            https://cf-hls-media.sndcdn.com/media/0/9980/abc.mp3?Policy=x
            #EXTINF:10.0,
            segment2.mp3
            #EXT-X-ENDLIST
        """.trimIndent()

        val rewritten = proxy.rewritePlaylist("token1", playlist, "https://cf-hls-media.sndcdn.com/playlist/abc.m3u8")
        val lines = rewritten.lines()

        assertTrue(lines.any { it.startsWith("#EXT-X-KEY") && "URI=\"/api/audio/token1?part=" in it })
        assertTrue(lines.contains("/api/audio/token1?part=https%3A%2F%2Fcf-hls-media.sndcdn.com%2Fmedia%2F0%2F9980%2Fabc.mp3%3FPolicy%3Dx"))
        // A relative segment is resolved against the playlist it was in.
        assertTrue(lines.contains("/api/audio/token1?part=https%3A%2F%2Fcf-hls-media.sndcdn.com%2Fplaylist%2Fsegment2.mp3"))
        assertEquals("#EXTM3U", lines.first())
        assertFalse(rewritten.lines().any { !it.startsWith("#") && it.isNotBlank() && !it.startsWith("/api/audio/") })
    }
}
