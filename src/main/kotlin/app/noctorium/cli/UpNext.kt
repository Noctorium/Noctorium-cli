package app.noctorium.cli

import app.noctorium.playback.QueueState
import app.noctorium.playback.RepeatMode

/**
 * What autoplay is doing after the queue, in the few ways the queue says it. The terminal player's Queue page
 * and the web player's queue both show it from this, so the two never disagree.
 *
 * Core lines autoplay's songs up once the queue's last song, or the one before it, is playing -- from that
 * song's own service -- and keeps them apart from the queue until they play. See QueueState.suggestions.
 */
enum class AutoplayState {
    /** Switched off: the queue stops when it runs out. */
    OFF,

    /** The queue repeats, so it never runs out and autoplay has nothing to add. */
    REPEATING,

    /** Songs are lined up, shown under the queue. */
    READY,

    /** Spotify, playing on Spotify itself, chooses what comes next there; next asks it to move on. */
    SPOTIFY,

    /** The queue is nearly over and nothing is lined up yet: they are being looked for. */
    WAITING,

    /** Looked for and none came back, or every one was dropped. */
    NOTHING,

    /** Not yet: they are looked for once the queue is nearly over. */
    LATER;

    companion object {
        fun of(queue: QueueState, autoplay: Boolean): AutoplayState = when {
            !autoplay -> OFF
            queue.repeatMode != RepeatMode.OFF -> REPEATING
            queue.suggestions.isNotEmpty() -> READY
            queue.continuesElsewhere -> SPOTIFY
            queue.tracks.isEmpty() -> LATER
            queue.suggestionsSeed != null -> NOTHING
            queue.currentIndex >= queue.tracks.lastIndex - 1 -> WAITING
            else -> LATER
        }
    }
}
