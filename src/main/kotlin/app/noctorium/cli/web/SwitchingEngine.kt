package app.noctorium.cli.web

import app.noctorium.domain.Track
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the music comes out. */
enum class Output { COMPUTER, BROWSER }

/**
 * Two players behind one: mpv on the computer Noctorium runs on, and the browser that opened the web player.
 *
 * Core is handed this and never knows which is answering. Moving the music from one to the other stops it
 * on the first and starts it on the second at the same second -- the same thing Connect does between
 * devices, done inside one program.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingEngine(
    val computer: PlaybackEngine,
    val browser: BrowserEngine,
    start: Output,
) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableOutput = MutableStateFlow(start)
    val output: StateFlow<Output> = mutableOutput.asStateFlow()
    private val switching = Mutex()

    override val state: StateFlow<PlaybackState> = mutableOutput
        .flatMapLatest { if (it == Output.BROWSER) browser.state else computer.state }
        .stateIn(scope, SharingStarted.Eagerly, (if (start == Output.BROWSER) browser else computer).state.value)

    private val current: PlaybackEngine get() = if (mutableOutput.value == Output.BROWSER) browser else computer

    suspend fun switchTo(target: Output) = switching.withLock {
        if (target == mutableOutput.value) return@withLock
        val from = current
        val snapshot = from.state.value
        val to = if (target == Output.BROWSER) browser else computer
        to.setVolume(snapshot.volume)
        to.setMuted(snapshot.isMuted)
        to.setVolumeBoost(snapshot.volumeBoostEnabled)
        mutableOutput.value = target
        from.stop()
        val track = snapshot.track ?: return@withLock
        if (snapshot.status !in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.RESOLVING)) return@withLock
        to.play(track)
        if (snapshot.positionMs > 2_000) to.seekTo(snapshot.positionMs)
        if (snapshot.status == PlaybackStatus.PAUSED) to.pause()
    }

    override suspend fun play(track: Track) = current.play(track)
    override suspend fun pause() = current.pause()
    override suspend fun resume() = current.resume()
    override suspend fun setVolume(value: Float) = current.setVolume(value)
    override suspend fun setVolumeBoost(enabled: Boolean) = current.setVolumeBoost(enabled)
    override suspend fun setMuted(muted: Boolean) = current.setMuted(muted)
    override suspend fun seekTo(positionMs: Long) = current.seekTo(positionMs)
    override suspend fun stop() = current.stop()

    override suspend fun setLooping(enabled: Boolean) {
        computer.setLooping(enabled)
        browser.setLooping(enabled)
    }

    /** Both are told, as with looping, so switching mid-song keeps the sound the listener chose where it can. */
    override suspend fun setEqualizer(settings: app.noctorium.settings.EqualizerSettings) {
        computer.setEqualizer(settings)
        browser.setEqualizer(settings)
    }

    override fun close() {
        computer.close()
        browser.close()
        scope.cancel()
    }
}
