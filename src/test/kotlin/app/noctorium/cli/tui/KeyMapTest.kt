package app.noctorium.cli.tui

import app.noctorium.cli.tui.KeyAction.ADD_TO_PLAYLIST
import app.noctorium.cli.tui.KeyAction.ADD_TO_QUEUE
import app.noctorium.cli.tui.KeyAction.CLEAR_QUEUE
import app.noctorium.cli.tui.KeyAction.CLEAR_UPCOMING
import app.noctorium.cli.tui.KeyAction.COPY_LINK
import app.noctorium.cli.tui.KeyAction.DELETE_ALL_DOWNLOADS
import app.noctorium.cli.tui.KeyAction.DELETE_PLAYLIST
import app.noctorium.cli.tui.KeyAction.DOWNLOAD
import app.noctorium.cli.tui.KeyAction.FASTER
import app.noctorium.cli.tui.KeyAction.FOLLOW_ARTIST
import app.noctorium.cli.tui.KeyAction.HELP
import app.noctorium.cli.tui.KeyAction.LIKE
import app.noctorium.cli.tui.KeyAction.LIKE_PLAYING
import app.noctorium.cli.tui.KeyAction.LYRICS_AGAIN
import app.noctorium.cli.tui.KeyAction.LYRICS_NEXT
import app.noctorium.cli.tui.KeyAction.LYRICS_PREVIOUS
import app.noctorium.cli.tui.KeyAction.MOVE_DOWN
import app.noctorium.cli.tui.KeyAction.MOVE_UP
import app.noctorium.cli.tui.KeyAction.MUTE
import app.noctorium.cli.tui.KeyAction.NEW_PLAYLIST
import app.noctorium.cli.tui.KeyAction.NEXT
import app.noctorium.cli.tui.KeyAction.OPEN_PAGE
import app.noctorium.cli.tui.KeyAction.PIN
import app.noctorium.cli.tui.KeyAction.PLAYLIST_VISIBILITY
import app.noctorium.cli.tui.KeyAction.PLAY_NEXT
import app.noctorium.cli.tui.KeyAction.PLAY_PAUSE
import app.noctorium.cli.tui.KeyAction.PREVIOUS
import app.noctorium.cli.tui.KeyAction.QUIT
import app.noctorium.cli.tui.KeyAction.REFRESH_HOME
import app.noctorium.cli.tui.KeyAction.REFRESH_SUGGESTIONS
import app.noctorium.cli.tui.KeyAction.REMOVE
import app.noctorium.cli.tui.KeyAction.RENAME_PLAYLIST
import app.noctorium.cli.tui.KeyAction.REPEAT
import app.noctorium.cli.tui.KeyAction.SAVE_MP3
import app.noctorium.cli.tui.KeyAction.SAVE_QUEUE
import app.noctorium.cli.tui.KeyAction.SEARCH
import app.noctorium.cli.tui.KeyAction.SHUFFLE
import app.noctorium.cli.tui.KeyAction.SHUFFLE_PLAYLIST
import app.noctorium.cli.tui.KeyAction.SHUFFLE_UPCOMING
import app.noctorium.cli.tui.KeyAction.SLEEP_TIMER
import app.noctorium.cli.tui.KeyAction.SLOWER
import app.noctorium.cli.tui.KeyAction.THEME_NEXT
import app.noctorium.cli.tui.KeyAction.THEME_PREVIOUS
import app.noctorium.cli.tui.KeyAction.VOLUME_DOWN
import app.noctorium.cli.tui.KeyAction.VOLUME_UP
import app.noctorium.cli.tui.KeyAction.WEB_PLAYER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The keys as they come, and changing them: never two jobs for one key where both could be reached. */
class KeyMapTest {
    private val keys = KeyMap.DEFAULT

    @Test
    fun `the keys as they come are the player's keys as they always were`() {
        val everywhere = mapOf(
            " " to PLAY_PAUSE, "n" to NEXT, "p" to PREVIOUS, "+" to VOLUME_UP, "=" to VOLUME_UP, "-" to VOLUME_DOWN,
            "_" to VOLUME_DOWN, "m" to MUTE, "s" to SHUFFLE, "r" to REPEAT, "L" to LIKE_PLAYING, "/" to SEARCH, "?" to HELP,
            "q" to QUIT, "z" to SLEEP_TIMER, "t" to THEME_NEXT, "T" to THEME_PREVIOUS, "w" to WEB_PLAYER,
            // New, on keys nothing used before.
            "<" to SLOWER, ">" to FASTER,
        )
        everywhere.forEach { (key, action) -> assertEquals(action, keys.action(key, KeyScope.EVERYWHERE), "'$key'") }
        (1..8).forEach { n -> assertEquals("PAGE_", keys.action("$n", KeyScope.EVERYWHERE)?.name?.take(5), "'$n'") }
        val track = mapOf(
            "a" to ADD_TO_QUEUE, "A" to PLAY_NEXT, "l" to LIKE, "d" to DOWNLOAD, "e" to SAVE_MP3, "c" to COPY_LINK,
            "o" to OPEN_PAGE, "i" to PIN, "P" to ADD_TO_PLAYLIST, "x" to REMOVE, "J" to MOVE_DOWN, "K" to MOVE_UP,
        )
        track.forEach { (key, action) -> assertEquals(action, keys.action(key, KeyScope.TRACK), "'$key'") }
        assertEquals(REFRESH_HOME, keys.action("R", KeyScope.HOME))
        mapOf("N" to NEW_PLAYLIST, "S" to SHUFFLE_PLAYLIST, "R" to RENAME_PLAYLIST, "V" to PLAYLIST_VISIBILITY, "D" to DELETE_PLAYLIST)
            .forEach { (key, action) -> assertEquals(action, keys.action(key, KeyScope.LIBRARY), "'$key'") }
        // The queue's own: Clear, and four new ones on capitals the Library also uses, a page it never meets.
        mapOf("C" to CLEAR_QUEUE, "S" to SHUFFLE_UPCOMING, "U" to CLEAR_UPCOMING, "N" to SAVE_QUEUE, "R" to REFRESH_SUGGESTIONS)
            .forEach { (key, action) -> assertEquals(action, keys.action(key, KeyScope.QUEUE), "'$key'") }
        assertEquals(DELETE_ALL_DOWNLOADS, keys.action("X", KeyScope.DOWNLOADS))
        mapOf("[" to LYRICS_PREVIOUS, "]" to LYRICS_NEXT, "R" to LYRICS_AGAIN, "f" to FOLLOW_ARTIST)
            .forEach { (key, action) -> assertEquals(action, keys.action(key, KeyScope.NOW_PLAYING), "'$key'") }
        assertEquals("Space", keys.name(PLAY_PAUSE))
    }

    @Test
    fun `no two of them clash`() {
        KeyAction.entries.forEach { action -> keys.keys(action).forEach { key -> assertNull(keys.clash(action, key), "$action on '$key'") } }
    }

    @Test
    fun `the queue's keys move like any other, never onto one the queue or a track already has`() {
        assertEquals(REFRESH_SUGGESTIONS, keys.clash(CLEAR_UPCOMING, "R"))
        assertEquals(ADD_TO_QUEUE, keys.clash(SAVE_QUEUE, "a"), "a track's keys work on the queue too")
        assertEquals(NEXT, keys.clash(SHUFFLE_UPCOMING, "n"))
        // The Library's D is never reached from the queue.
        assertNotNull(keys.with(SAVE_QUEUE, "D"))

        val moved = assertNotNull(keys.with(SHUFFLE_UPCOMING, "W"))
        assertEquals(SHUFFLE_UPCOMING, moved.action("W", KeyScope.QUEUE))
        assertNull(moved.action("S", KeyScope.QUEUE))
        assertEquals(SHUFFLE_PLAYLIST, moved.action("S", KeyScope.LIBRARY), "the Library keeps its own S")
        assertEquals(mapOf("SHUFFLE_UPCOMING" to listOf("W")), moved.saved())
    }

    @Test
    fun `a key that would meet another's is refused, a key on another page is not, and a change can be put back`() {
        assertEquals(PREVIOUS, keys.clash(NEXT, "p"))
        assertNull(keys.with(NEXT, "p"))
        // A track's keys work on every page, so the R of Home, Library and Now playing is taken for them.
        assertNull(keys.with(DOWNLOAD, "R"))
        // Downloads and the pages that use R never meet.
        assertNotNull(keys.with(DELETE_ALL_DOWNLOADS, "R"))
        assertNull(keys.with(NEXT, "^c"), "a control key is not a key that can be given")

        val moved = assertNotNull(keys.with(NEXT, "j"))
        assertEquals(NEXT, moved.action("j", KeyScope.EVERYWHERE))
        assertNull(moved.action("n", KeyScope.EVERYWHERE))
        assertEquals(mapOf("NEXT" to listOf("j")), moved.saved())
        assertEquals(listOf("n"), assertNotNull(moved.reset(NEXT)).keys(NEXT))
        // Its old key given to something else meanwhile, it cannot simply go back.
        assertNull(assertNotNull(moved.with(PREVIOUS, "n")).reset(NEXT))
    }

    @Test
    fun `two keys swapped are kept as they were saved, and a clash in the file is left out alone`() {
        val swapped = assertNotNull(keys.with(NEXT, "j")?.with(PREVIOUS, "n")?.with(NEXT, "p"))
        val again = KeyMap.loaded(swapped.saved())
        assertEquals(NEXT, again.action("p", KeyScope.EVERYWHERE))
        assertEquals(PREVIOUS, again.action("n", KeyScope.EVERYWHERE))

        val edited = KeyMap.loaded(mapOf("NEXT" to listOf("q"), "HELP" to listOf("h"), "NOT_A_THING" to listOf("x"), "MUTE" to listOf("too long")))
        assertEquals(listOf("n"), edited.keys(NEXT), "q is Quit's, so Next keeps its own")
        assertEquals(listOf("h"), edited.keys(HELP))
        assertEquals(listOf("m"), edited.keys(MUTE))
    }
}
