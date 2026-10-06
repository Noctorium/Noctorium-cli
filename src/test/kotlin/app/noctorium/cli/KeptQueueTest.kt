package app.noctorium.cli

import app.noctorium.playback.RepeatMode
import app.noctorium.playback.SavedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The queue kept between launches belongs to the player and `noctorium web`. Every other command is started to
 * do one thing and stop, and must neither put the kept queue back into something that plays nor write it again
 * with nothing playing, which would lose where it was left. The file here is the test home's (see the build).
 */
class KeptQueueTest {
    @Test
    fun `a one-shot command's state neither reads, writes nor clears the queue the player keeps`() {
        val player = keptQueue(interactive = true)
        val left = SavedQueue(listOf(BandcampStandIn.song), index = 0, positionMs = 90_000, repeatMode = RepeatMode.ALL)
        player.save(left)
        try {
            val oneShot = keptQueue(interactive = false)
            assertNull(oneShot.load())
            oneShot.save(left.copy(positionMs = 0))
            oneShot.clear()
            val kept = player.load()
            assertEquals(listOf(BandcampStandIn.song.queueKey), kept?.tracks?.map { it.queueKey })
            assertEquals(90_000L, kept?.positionMs)
            assertEquals(RepeatMode.ALL, kept?.repeatMode)
        } finally {
            player.clear()
        }
        assertNull(player.load())
    }
}
