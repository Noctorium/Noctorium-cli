package app.noctorium.cli.web

import app.noctorium.playback.YtDlpService
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The audio, passed through to the browser.
 *
 * The service's address is never given to the page. YouTube issues a stream to one client from one network
 * address and refuses it to anybody else, and the page is a different client, possibly on a phone on mobile
 * data. So the browser asks Noctorium, and Noctorium asks the service as the client the address was issued
 * to, passing the browser's range requests along so seeking works as it would against the service itself.
 *
 * HLS -- SoundCloud's, for much of its catalogue -- is a playlist of segments, each a separate address; the
 * playlist is rewritten so every segment is fetched through here too, and only from the hosts the playlist
 * named, so this can never be used to fetch anything else.
 */
class AudioProxy(private val engine: BrowserEngine, private val backend: YtDlpService) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** The hosts each HLS stream's playlists have named, which its segments may be fetched from. */
    private val allowedHosts = ConcurrentHashMap<String, MutableSet<String>>()

    suspend fun serve(call: ApplicationCall, token: String, part: String?) {
        val stream = engine.streams[token] ?: return call.respondText("Gone", status = HttpStatusCode.NotFound)
        val range = call.request.headers[HttpHeaders.Range]
        when (stream) {
            is BrowserEngine.Stream.Local -> local(call, stream, range)
            is BrowserEngine.Stream.Remote -> {
                if (part != null) {
                    val host = runCatching { URI(part).host }.getOrNull()
                    if (host == null || host !in allowedHosts[token].orEmpty()) return call.respondText("Not allowed", status = HttpStatusCode.Forbidden)
                    remote(call, token, stream, part, range, rewrite = part.substringBefore('?').endsWith(".m3u8"))
                } else {
                    remote(call, token, stream, stream.found.url, range, rewrite = stream.found.isHls)
                }
            }
        }
    }

    private suspend fun remote(call: ApplicationCall, token: String, stream: BrowserEngine.Stream.Remote, url: String, range: String?, rewrite: Boolean) {
        var response = fetch(url, stream, if (rewrite) null else range)
        // An address that has run out answers 403. Asked again once, freshly, before the browser is told.
        if (response.code in setOf(403, 410) && url == stream.found.url) {
            response.close()
            backend.forgetAudio(stream.track.sourceUrl)
            stream.found = runCatching { backend.resolveBrowserAudio(stream.track.sourceUrl) }.getOrElse {
                return call.respondText("Could not find the audio again", status = HttpStatusCode.BadGateway)
            }
            response = fetch(stream.found.url, stream, if (rewrite) null else range)
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return call.respondText("The service answered $code", status = HttpStatusCode.fromValue(code.coerceIn(400, 599)))
        }
        if (rewrite || response.header("Content-Type").orEmpty().contains("mpegurl", ignoreCase = true)) {
            val text = response.use { it.body?.string().orEmpty() }
            val base = response.request.url.toString()
            return call.respondText(rewritePlaylist(token, text, base), ContentType.parse("application/vnd.apple.mpegurl"))
        }
        val body = response.body ?: return call.respondText("Empty", status = HttpStatusCode.BadGateway)
        val headers = buildList {
            response.header("Content-Range")?.let { add(HttpHeaders.ContentRange to listOf(it)) }
            add(HttpHeaders.AcceptRanges to listOf("bytes"))
            add(HttpHeaders.CacheControl to listOf("no-store"))
        }
        call.respond(
            Streamed(
                body.byteStream(),
                body.contentLength().takeIf { it >= 0 },
                ContentType.parse(response.header("Content-Type") ?: guessType(stream.found.extension)),
                HttpStatusCode.fromValue(response.code),
                headersOf(*headers.toTypedArray()),
                onDone = { response.close() },
            ),
        )
    }

    private suspend fun fetch(url: String, stream: BrowserEngine.Stream.Remote, range: String?): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            stream.found.headers.forEach { (name, value) ->
                // The client yt-dlp impersonated, minus anything that would confuse a plain fetch.
                if (name.lowercase() !in setOf("accept-encoding", "host", "content-length", "connection")) header(name, value)
            }
            range?.let { header("Range", it) }
        }.build()
        client.newCall(request).execute()
    }

    /** Every address in an HLS playlist, pointed back through here. */
    internal fun rewritePlaylist(token: String, text: String, base: String): String {
        val hosts = allowedHosts.getOrPut(token) { ConcurrentHashMap.newKeySet() }
        fun proxied(address: String): String {
            val absolute = URI(base).resolve(address.trim()).toString()
            URI(absolute).host?.let(hosts::add)
            return "/api/audio/$token?part=" + URLEncoder.encode(absolute, StandardCharsets.UTF_8)
        }
        return text.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { match -> "URI=\"${proxied(match.groupValues[1])}\"" }
                else -> proxied(line)
            }
        }
    }

    private suspend fun local(call: ApplicationCall, stream: BrowserEngine.Stream.Local, range: String?) {
        val file = stream.file
        if (!Files.isRegularFile(file)) return call.respondText("The file has gone", status = HttpStatusCode.NotFound)
        val size = Files.size(file)
        val (start, end) = parseRange(range, size) ?: (0L to size - 1)
        val length = end - start + 1
        val input = withContext(Dispatchers.IO) {
            RandomAccessFile(file.toFile(), "r").also { it.seek(start) }
        }
        val partial = range != null
        call.respond(
            Streamed(
                object : InputStream() {
                    var left = length
                    override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (left <= 0) return -1
                        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
                        if (n > 0) left -= n
                        return n
                    }
                    override fun close() = input.close()
                },
                length,
                ContentType.parse(guessType(file.fileName.toString().substringAfterLast('.', "mp3"))),
                if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                headersOf(
                    *listOfNotNull(
                        HttpHeaders.AcceptRanges to listOf("bytes"),
                        if (partial) HttpHeaders.ContentRange to listOf("bytes $start-$end/$size") else null,
                    ).toTypedArray(),
                ),
            ),
        )
    }

    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        val spec = header?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
        val (a, b) = spec.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when {
            a.isEmpty() -> b.toLongOrNull()?.let { (size - it).coerceAtLeast(0) to size - 1 }
            else -> a.toLongOrNull()?.let { start -> start to (b.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1) }
        }?.takeIf { it.first <= it.second }
    }

    private fun guessType(extension: String) = when (extension.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "webm" -> "audio/webm"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** A body copied from an input stream as the browser reads it, never held whole in memory. */
    private class Streamed(
        private val input: InputStream,
        override val contentLength: Long?,
        override val contentType: ContentType,
        override val status: HttpStatusCode,
        override val headers: Headers,
        private val onDone: () -> Unit = {},
    ) : OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buffer = ByteArray(64 * 1024)
            try {
                withContext(Dispatchers.IO) {
                    input.use { stream ->
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            channel.writeFully(buffer, 0, n)
                        }
                    }
                }
            } finally {
                onDone()
            }
        }
    }
}
