package app.noctorium.cli.web

import app.noctorium.playback.YtDlpService
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

    /**
     * VK's songs as VK serves them: about one segment in three encrypted, the key switching on and off down the
     * playlist, and the key on an address of its own. hls.js in the page decrypts them itself, so all that is
     * asked of the proxy is that the key comes through it like the segments, and the stretches marked
     * METHOD=NONE are left saying so.
     */
    @Test
    fun `VK's keys come through the proxy like its segments, and its clear stretches stay clear`() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="https://cs9-12v4.vkuseraudio.net/s/v1/ac/x1/key.pub"
            #EXTINF:5.000,
            seg-1-a1.ts?extra=k1
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:10.000,
            seg-2-a1.ts?extra=k1
            #EXT-X-ENDLIST
        """.trimIndent()

        val lines = proxy.rewritePlaylist("vk1", playlist, "https://cs9-12v4.vkuseraudio.net/s/v1/ac/x1/index.m3u8?siren=1").lines()

        fun through(address: String) = "/api/audio/vk1?part=" + URLEncoder.encode(address, StandardCharsets.UTF_8)
        assertTrue(lines.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"${through("https://cs9-12v4.vkuseraudio.net/s/v1/ac/x1/key.pub")}\""), lines.joinToString("\n"))
        assertTrue(lines.contains("#EXT-X-KEY:METHOD=NONE"))
        assertTrue(lines.contains(through("https://cs9-12v4.vkuseraudio.net/s/v1/ac/x1/seg-1-a1.ts?extra=k1")))
        assertTrue(lines.contains(through("https://cs9-12v4.vkuseraudio.net/s/v1/ac/x1/seg-2-a1.ts?extra=k1")))
    }
}
