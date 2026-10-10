package app.noctorium.cli.web

import app.noctorium.domain.Track
import app.noctorium.playback.BrowserStream
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.StreamFormat
import app.noctorium.playback.VolumeBoost
import app.noctorium.playback.YtDlpService
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A player whose speaker is a browser.
 *
 * Core still decides everything -- what is next, what counts as a listen, what to scrobble -- exactly as it
 * does with mpv; this engine only carries its instructions to a browser tab and carries back what the
 * tab's audio element reports. The tab is told "load this address, play, seek there", and answers "playing
 * at 1:23", "paused", "ended". An ended track goes idle with the track still set, which is what core reads as
 * a track finishing, so the queue moves on as it would anywhere else.
 *
 * The address it is given is Noctorium's own (see [AudioProxy]), never the service's: a YouTube stream is
 * tied to the client and the address that asked for it, and the browser is neither.
 */
class BrowserEngine(
    private val backend: YtDlpService,
    private val downloadedFile: (Track) -> Path?,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    /**
     * Sends a message to the browser that is playing; false when there is none. Set by the server. A page that
     * becomes the speaker is told the booster's level at once, since it was set before there was a page to tell.
     */
    @Volatile
    var sink: ((JsonObject) -> Boolean)? = null
        set(value) {
            field = value
            if (value != null) send("boost") { put("percent", state.value.volumeBoostPercent) }
        }

    /** The level the boost button goes back to, as mpv keeps it: the last above 100%, 200% the first time. */
    @Volatile
    private var lastBoostOn = VolumeBoost.FIRST_PERCENT

    /** What each address handed to the browser stands for. */
    val streams = ConcurrentHashMap<String, Stream>()

    /** A stream the browser was told to load: a file on the disk, or one found by yt-dlp. */
    sealed interface Stream {
        val track: Track
        data class Local(override val track: Track, val file: Path) : Stream
        data class Remote(override val track: Track, @Volatile var found: BrowserStream) : Stream
    }

    /** Which load a report belongs to, so a late "ended" from the last track cannot end this one. */
    @Volatile
    private var generation = 0

    @Volatile
    private var looping = false

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Streams already found, for a while. yt-dlp takes seconds over a SoundCloud track and longer over a
     * long one, so the next track in the queue is looked up while this one plays ([prefetch]) and starts
     * without the wait. Twenty minutes is well inside how long either service's addresses last.
     */
    private val found = ConcurrentHashMap<String, Pair<BrowserStream, Long>>()

    private suspend fun stream(track: Track): BrowserStream {
        val now = System.currentTimeMillis()
        found[track.sourceUrl]?.takeIf { now - it.second < 20 * 60_000 }?.let { return it.first }
        if (found.size > 32) found.clear()
        return backend.resolveBrowserAudio(track.sourceUrl).also { found[track.sourceUrl] = it to now }
    }

    /** Finds [track]'s stream in the background, so playing it next starts at once. */
    fun prefetch(track: Track) {
        if (downloadedFile(track) != null) return
        val known = found[track.sourceUrl]
        if (known != null && System.currentTimeMillis() - known.second < 15 * 60_000) return
        scope.launch { runCatching { stream(track) } }
    }

    override suspend fun play(track: Track) {
        val load = ++generation
        mutableState.update {
            it.copy(status = PlaybackStatus.RESOLVING, track = track, errorMessage = null, positionMs = 0, durationMs = track.durationMs ?: 0, loops = 0, format = null)
        }
        val stream = runCatching {
            downloadedFile(track)?.let { Stream.Local(track, it) }
                ?: Stream.Remote(track, stream(track))
        }.getOrElse { failure ->
            if (load == generation) {
                mutableState.update { it.copy(status = PlaybackStatus.ERROR, errorMessage = failure.message ?: "Could not find the audio") }
            }
            return
        }
        if (load != generation) return
        mutableState.update { it.copy(format = formatOf(stream)) }
        if (streams.size > 64) streams.clear()
        val token = UUID.randomUUID().toString().replace("-", "")
        streams[token] = stream
        val sent = send("load") {
            put("generation", load)
            put("url", "/api/audio/$token")
            put("hls", (stream as? Stream.Remote)?.found?.isHls == true)
            put("volume", state.value.volume.toDouble())
            put("muted", state.value.isMuted)
            put("loop", looping)
            // The page puts the booster in front of each song it loads, from this, as well as when told it changed.
            put("boostPercent", state.value.volumeBoostPercent)
        }
        if (!sent) {
            mutableState.update {
                it.copy(status = PlaybackStatus.ERROR, errorMessage = "Open the web player in a browser to play there, or switch to this computer.")
            }
        }
    }

    override suspend fun pause() {
        send("pause")
        mutableState.update { if (it.status == PlaybackStatus.PLAYING) it.copy(status = PlaybackStatus.PAUSED) else it }
    }

    override suspend fun resume() {
        if (state.value.track == null) return
        if (!send("play")) {
            // No tab has the track loaded any more -- it was closed. Start it again where it was.
            val track = state.value.track ?: return
            val at = state.value.positionMs
            play(track)
            if (at > 0) seekTo(at)
        }
    }

    override suspend fun setVolume(value: Float) {
        mutableState.update { it.copy(volume = value.coerceIn(0f, 1f)) }
        send("volume") { put("volume", value.toDouble()) }
    }

    override suspend fun setVolumeBoost(enabled: Boolean) = setVolumeBoostPercent(if (enabled) lastBoostOn else VolumeBoost.OFF_PERCENT)

    /**
     * The booster in a browser: a browser's own volume stops at full, so the page puts Web Audio's gain and a
     * limiter after its audio element and sets the gain from this. Every song reaches the page through this
     * program's own address, so the page can always hear what it plays, and the booster is always there.
     */
    override suspend fun setVolumeBoostPercent(percent: Int) {
        val level = VolumeBoost.clamp(percent)
        if (VolumeBoost.isOn(level)) lastBoostOn = level
        mutableState.update { it.copy(volumeBoostPercent = level, volumeBoostEnabled = VolumeBoost.isOn(level)) }
        send("boost") { put("percent", level) }
    }

    override suspend fun setMuted(muted: Boolean) {
        mutableState.update { it.copy(isMuted = muted) }
        send("mute") { put("muted", muted) }
    }

    override suspend fun seekTo(positionMs: Long) {
        mutableState.update { it.copy(positionMs = positionMs) }
        send("seek") { put("positionMs", positionMs) }
    }

    override suspend fun stop() {
        generation++
        send("stop")
        mutableState.update { it.copy(status = PlaybackStatus.IDLE, track = null, positionMs = 0, durationMs = 0, errorMessage = null) }
    }

    override suspend fun setLooping(enabled: Boolean) {
        looping = enabled
        send("loop") { put("loop", enabled) }
    }

    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        streams.clear()
        sink = null
    }

    /**
     * What the browser's audio element said. [event] is one of playing, paused, time, ended, looped, error,
     * or gone -- the tab went away, which is a pause as far as anybody listening is concerned.
     */
    fun report(event: String, load: Int, positionMs: Long?, durationMs: Long?, message: String?) {
        if (event == "gone") {
            mutableState.update { if (it.status == PlaybackStatus.PLAYING) it.copy(status = PlaybackStatus.PAUSED) else it }
            return
        }
        if (load != generation) return
        mutableState.update { current ->
            val position = positionMs ?: current.positionMs
            val duration = durationMs?.takeIf { it > 0 } ?: current.durationMs
            when (event) {
                "playing" -> current.copy(status = PlaybackStatus.PLAYING, positionMs = position, durationMs = duration, errorMessage = null)
                "paused" -> current.copy(status = PlaybackStatus.PAUSED, positionMs = position, durationMs = duration)
                "waiting" -> if (current.status == PlaybackStatus.RESOLVING) current else current.copy(positionMs = position, durationMs = duration)
                "time" -> current.copy(positionMs = position, durationMs = duration)
                "looped" -> current.copy(loops = current.loops + 1, positionMs = 0)
                // Idle with the track still set: core's sign that it finished and the queue moves on.
                "ended" -> current.copy(status = PlaybackStatus.IDLE, positionMs = position, durationMs = duration)
                "error" -> current.copy(status = PlaybackStatus.ERROR, errorMessage = message ?: "The browser could not play this")
                else -> current
            }
        }
    }

    /**
     * What the page could tell of the stream it is playing -- a browser knows its sample rate and channels once
     * it has decoded some -- put over what yt-dlp said of it. Only for the song loaded now.
     */
    fun reportFormat(load: Int, codec: String?, bitsPerSecond: Int?, sampleRateHz: Int?, channels: Int?) {
        if (load != generation) return
        mutableState.update { current ->
            val known = current.format ?: StreamFormat()
            current.copy(
                format = StreamFormat(
                    codec = codec?.takeIf(String::isNotBlank) ?: known.codec,
                    sampleRateHz = sampleRateHz?.takeIf { it > 0 } ?: known.sampleRateHz,
                    channels = channels?.takeIf { it > 0 } ?: known.channels,
                    bitsPerSecond = bitsPerSecond?.takeIf { it > 0 } ?: known.bitsPerSecond,
                ),
            )
        }
    }

    /** What the stream is, as far as it is known before the page has played any: its codec. */
    private fun formatOf(stream: Stream): StreamFormat? {
        val codec = when (stream) {
            is Stream.Remote -> stream.found.codec.takeIf { it.isNotBlank() && it != "none" }
                ?: stream.found.extension.takeIf { !stream.found.isHls && it.isNotBlank() }
            is Stream.Local -> stream.file.fileName.toString().substringAfterLast('.', "").lowercase().let { extension ->
                when (extension) {
                    "m4a", "mp4", "aac" -> "aac"
                    "ogg", "oga" -> "vorbis"
                    "webm" -> "opus"
                    "" -> null
                    else -> extension
                }
            }
        }
        return codec?.let { StreamFormat(codec = it) }
    }

    private fun send(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): Boolean {
        val out = sink ?: return false
        return out(
            buildJsonObject {
                put("kind", "audio")
                put("command", type)
                put("generation", generation)
                body()
            },
        )
    }
}
