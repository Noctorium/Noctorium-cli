package app.noctorium.cli.tui

import app.noctorium.cli.BandcampStandIn
import app.noctorium.cli.BandcampStandIn.waitFor
import app.noctorium.cli.CliParts
import app.noctorium.cli.LyricsStandIn
import app.noctorium.domain.Artist
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.playback.AudioAnalysis
import app.noctorium.playback.AudioAnalysisKind
import app.noctorium.playback.PlaybackEngine
import app.noctorium.playback.PlaybackState
import app.noctorium.playback.PlaybackStatus
import app.noctorium.playback.StreamFormat
import app.noctorium.playback.VolumeBoost
import app.noctorium.settings.HomePlay
import app.noctorium.settings.PlayerButton
import app.noctorium.settings.QueueColumn
import app.noctorium.settings.ThemePreset
import app.noctorium.settings.VisualiserStyle
import app.noctorium.settings.showsPlayerButton
import app.noctorium.settings.withPlayerButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What came with the desktop-music-player look, in the terminal, drawn into frames and read back: the Toolbar and
 * Toolbar 2, the booster on the volume, Stop and stop after this song, the Playlist layout with its table, its lyrics,
 * visualiser and status bar, the symbols, Clear all but this song, a song from Home, and the bang in a search.
 * Bandcamp's stand-in is the only service, and the player only pretends -- with a format and a visualiser feed of its
 * own to report.
 */
class PlayerLooksTest {
    /** A player that keeps what it was asked, says what it plays as, and has a visualiser feed while watched. */
    private class StandIn : PlaybackEngine {
        val mutable = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutable
        val calls = CopyOnWriteArrayList<String>()
        val frames = MutableStateFlow<AudioAnalysis?>(null)
        override val analysis: StateFlow<AudioAnalysis?> get() = frames
        override val analysisKind: AudioAnalysisKind get() = AudioAnalysisKind.SPECTRUM
        override suspend fun play(track: Track) = mutable.update {
            it.copy(
                status = PlaybackStatus.PLAYING, track = track, positionMs = 94_000, durationMs = track.durationMs ?: 240_000, volume = .5f,
                format = StreamFormat("opus", 48_000, 2, 128_000),
            )
        }
        override suspend fun pause() { calls += "pause"; mutable.update { it.copy(status = PlaybackStatus.PAUSED) } }
        override suspend fun resume() = mutable.update { it.copy(status = PlaybackStatus.PLAYING) }
        override suspend fun setVolume(value: Float) = mutable.update { it.copy(volume = value) }
        override suspend fun setVolumeBoost(enabled: Boolean) = Unit
        override suspend fun setVolumeBoostPercent(percent: Int) = mutable.update { it.copy(volumeBoostPercent = percent, volumeBoostEnabled = percent > 100) }
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun seekTo(positionMs: Long) { calls += "seek $positionMs"; mutable.update { it.copy(positionMs = positionMs) } }
        override suspend fun stop() = mutable.update { PlaybackState() }
        override fun close() = Unit
    }

    private val parts = CliParts()
    private val player = StandIn()
    private val state = BandcampStandIn.state(parts, player)
    private val tui = Tui(state, parts, web = null)

    @AfterTest
    fun close() {
        tui.preferences.playerBar = TuiPlayerBar.FULL
        tui.preferences.nowPlaying = TuiNowPlaying.CLASSIC
        tui.preferences.glyphs = TuiGlyphs.STANDARD
        PlaylistLayout.shown = PlaylistLayout.Tab.QUEUE
        state.close()
    }

    private fun song(n: Int) = Track(
        provider = ProviderType.BANDCAMP,
        id = "70$n",
        title = "Song $n",
        artists = listOf(BandcampStandIn.artist),
        durationMs = 200_000,
        sourceUrl = "https://tycho.bandcamp.com/track/song-$n",
    )

    private val songs = listOf(BandcampStandIn.song) + (1..3).map(::song)

    private fun playing() {
        state.setAutoplay(false)
        waitFor { !state.settings.value.preferences.autoplay }
        state.play(BandcampStandIn.song, PlaybackOrigin.SEARCH, songs)
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING && state.queue.state.value.tracks.size == songs.size }
    }

    private fun lines(canvas: Canvas): List<String> = (0 until canvas.height).map { y ->
        (0 until canvas.width).joinToString("") { x -> canvas.text[y * canvas.width + x] ?: " " }.trimEnd()
    }

    private fun render(w: Int = 150, h: Int = 42): Canvas {
        tui.frame(w, h)
        return tui.frame(w, h)
    }

    private fun click(canvas: Canvas, text: String, row: Int = canvas.height - 1) {
        val at = lines(canvas)[row].indexOf(text)
        assertTrue(at >= 0, "\"$text\" is not on row $row:\n" + lines(canvas).joinToString("\n"))
        tui.press(Input.Mouse(MouseKind.PRESS, at, row))
    }

    @Test
    fun `the toolbar is one row of buttons, the slider with its time, and repeat, shuffle and the volume`() {
        playing()
        tui.preferences.playerBar = TuiPlayerBar.TOOLBAR
        tui.page = Page.QUEUE
        val canvas = render()
        val bar = lines(canvas).last()
        listOf(" + ", "|◀", "▶ ", "▮▮", "■", "▶|", "1:34 / 4:43", "↻", "⇄", "vol").forEach { assertTrue(it in bar, "\"$it\" is not in the toolbar:\n$bar") }
        assertTrue(bar.indexOf("■") < bar.indexOf("▶|") && bar.indexOf("▶|") < bar.indexOf("1:34 / 4:43") && bar.indexOf("1:34") < bar.indexOf("↻"), bar)
        // The song is set into the bar's top edge.
        assertTrue("Awake – Tycho" in lines(canvas)[canvas.height - 2], lines(canvas)[canvas.height - 2])
        assertEquals(42 - 1 - 2 - 3, tui.listView.height, "The toolbar is two rows with its edge")

        click(canvas, "▮▮")
        waitFor { state.playback.value.status == PlaybackStatus.PAUSED }
        player.calls.clear()
        click(render(), "■")
        waitFor { "seek 0" in player.calls }
        click(render(), " + ")
        assertEquals("Add to a playlist", assertIs<Overlay.Picker>(tui.overlays.last()).title)
    }

    @Test
    fun `Toolbar 2 is the toolbar with play and pause as one and no add button, and the bar's other buttons after the slider`() {
        playing()
        tui.preferences.playerBar = TuiPlayerBar.TOOLBAR_2
        tui.page = Page.QUEUE
        val canvas = render()
        val bar = lines(canvas).last()
        // In the desktop's order: the transport, the slider and its time, like, lyrics and the queue, repeat and
        // shuffle, then the sleep timer, Devices, the speed and the volume.
        val order = listOf("|◀", "▮▮", "▶|", "1:34 / 4:43", "♡", "¶", "≡", "↻", "⇄", "☾", "◎", "1×", "vol")
        order.forEach { assertTrue(it in bar, "\"$it\" is not in Toolbar 2:\n$bar") }
        assertEquals(order, order.sortedBy { bar.indexOf(it) }, bar)
        // One button for play and pause, saying what it will do; no add button, and no Stop until asked for.
        assertFalse("▶ " in bar || " + " in bar || "■" in bar, bar)
        assertTrue("Awake – Tycho" in lines(canvas)[canvas.height - 2], lines(canvas)[canvas.height - 2])
        assertEquals(42 - 1 - 2 - 3, tui.listView.height, "Toolbar 2 is two rows with its edge, as the Toolbar is")

        click(canvas, "▮▮")
        waitFor { state.playback.value.status == PlaybackStatus.PAUSED }
        click(render(), "▶ ")
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING }
        click(render(), "¶")
        assertEquals(Page.NOW_PLAYING, tui.page)
        click(render(), "≡")
        assertEquals(Page.QUEUE, tui.page)
        click(render(), "◎")
        assertEquals(Page.DEVICES, tui.page)
        tui.page = Page.SEARCH
        val shuffled = state.queue.state.value.shuffleEnabled
        click(render(), "⇄")
        waitFor { state.queue.state.value.shuffleEnabled != shuffled }
        val repeat = state.queue.state.value.repeatMode
        click(render(), "↻")
        waitFor { state.queue.state.value.repeatMode != repeat }
        click(render(), "☾")
        assertEquals("Sleep timer", assertIs<Overlay.Picker>(tui.overlays.last()).title)
        tui.overlays.clear()
        // The speed opens Settings' speeds, and then shows the one chosen.
        click(render(), "1×")
        assertEquals("Speed", assertIs<Overlay.Picker>(tui.overlays.last()).title)
        tui.press(Input.Text("5"))
        waitFor { state.settings.value.preferences.playbackSpeed == 1.5f }
        assertTrue("1.5×" in lines(render()).last(), lines(render()).last())
        // A Bandcamp song cannot be liked from here: the heart is dim, and pressing it says so.
        click(render(), "♡")
        assertTrue(lines(render()).any { "cannot be liked" in it }, lines(render()).joinToString("\n"))
        click(render(), "vol")
        assertIs<VolumePanel>(tui.overlays.last())
    }

    @Test
    fun `Toolbar 2's buttons give way as the terminal narrows, but never one showing something under way`() {
        playing()
        tui.preferences.playerBar = TuiPlayerBar.TOOLBAR_2
        tui.page = Page.SEARCH
        fun bar(w: Int) = lines(render(w, 30)).last()
        // A little narrower, lyrics and the queue go first: they only lead to other pages.
        val middling = bar(92)
        listOf("|◀", "▮▮", "▶|", "♡", "↻", "⇄", "☾", "◎", "1×", "vol", "4:43").forEach { assertTrue(it in middling, "\"$it\" is gone at 92:\n$middling") }
        assertFalse("¶" in middling || "≡" in middling, middling)
        // Narrower again, repeat, shuffle, the sleep timer and the speed; the transport, the time and the volume stay.
        val narrow = bar(84)
        listOf("|◀", "▮▮", "▶|", "♡", "◎", "vol", "4:43").forEach { assertTrue(it in narrow, "\"$it\" is gone at 84:\n$narrow") }
        listOf("¶", "≡", "↻", "⇄", "☾", "1×").forEach { assertFalse(it in narrow, "\"$it\" is still there at 84:\n$narrow") }
        // A sleep timer counting down and a song played faster stay, whatever the room.
        state.startSleepTimer(30)
        state.setPlaybackSpeed(1.25f)
        waitFor { state.sleepTimer.value != null && state.settings.value.preferences.playbackSpeed == 1.25f }
        val busy = bar(84)
        assertTrue("☾" in busy && "1.25×" in busy, busy)
        assertFalse("↻" in busy || "⇄" in busy, busy)
        // And at the narrowest the player allows, it still draws without falling over.
        bar(40)
        state.cancelSleepTimer()
    }

    @Test
    fun `Toolbar 2 has Stop once asked for and leaves out what is put away, in core's player-bar buttons`() {
        playing()
        tui.preferences.playerBar = TuiPlayerBar.TOOLBAR_2
        tui.page = Page.SEARCH
        state.updateDesktop { withPlayerButton(PlayerButton.STOP, true).withPlayerButton(PlayerButton.LYRICS, false) }
        waitFor { state.settings.value.preferences.desktop.showsPlayerButton(PlayerButton.STOP) }
        val bar = lines(render()).last()
        assertTrue(bar.indexOf("▮▮") < bar.indexOf("■") && bar.indexOf("■") < bar.indexOf("▶|"), bar)
        assertFalse("¶" in bar, bar)
        player.calls.clear()
        click(render(), "■")
        waitFor { "seek 0" in player.calls }

        // Settings offers them with Toolbar 2, and ticking one there puts it back.
        tui.page = Page.SETTINGS
        val row = Settings.rows(tui).filterIsInstance<Row.Action>().first { it.id == "playerButtons" }
        assertTrue("Stop" in row.value.orEmpty() && "Lyrics" !in row.value.orEmpty(), row.value)
        row.run()
        val list = assertIs<Overlay.Checklist>(tui.overlays.last())
        assertEquals(PlayerBar.TOOLBAR_2_BUTTONS.size, list.labels.size)
        tui.press(Input.Key(Keys.DOWN))
        tui.press(Input.Text(" "))
        tui.press(Input.Key(Keys.ESCAPE))
        waitFor { state.settings.value.preferences.desktop.showsPlayerButton(PlayerButton.LYRICS) }
        assertTrue(state.settings.value.preferences.desktop.showsPlayerButton(PlayerButton.STOP))
        tui.page = Page.SEARCH
        assertTrue("¶" in lines(render()).last())
    }

    @Test
    fun `the booster's level shows on the volume while it is on, and nowhere it cannot be`() {
        playing()
        tui.page = Page.QUEUE
        assertFalse("×" in lines(render()).takeLast(4).joinToString("\n"))
        state.setVolumeBoostPercent(400)
        waitFor { player.state.value.volumeBoostPercent == 400 }
        assertTrue(lines(render()).takeLast(4).any { "50% ×4" in it }, lines(render()).takeLast(4).joinToString("\n"))
        tui.preferences.playerBar = TuiPlayerBar.TOOLBAR
        assertTrue("×4" in lines(render()).last())
        tui.preferences.playerBar = TuiPlayerBar.TASKBAR
        assertTrue("×4" in lines(render()).last())

        // Where the music plays out of its reach the mark goes, and the panel says why.
        player.mutable.update { it.copy(volumeBoostAvailable = false) }
        waitFor { !state.playback.value.volumeBoostAvailable }
        assertFalse("×4" in lines(render()).last())
        tui.overlays.addLast(VolumePanel(booster = true))
        val panel = lines(render()).joinToString("\n")
        assertTrue("Not available here" in panel, panel)
        tui.press(Input.Key(Keys.RIGHT))
        assertEquals(400, state.settings.value.preferences.volumeBoostPercent, "unchanged where there is no booster")
    }

    @Test
    fun `the volume panel steps the booster, sets it by number, and warns once it is loud`() {
        playing()
        tui.press(Input.Text("b"))
        val panel = assertIs<VolumePanel>(tui.overlays.last())
        assertTrue("Off" in lines(render()).joinToString("\n"))
        tui.press(Input.Text(" "))
        waitFor { state.settings.value.preferences.volumeBoostPercent == VolumeBoost.FIRST_PERCENT }
        assertFalse(VolumeBoost.LOUD_WARNING in lines(render()).joinToString("\n"), "200% is not past the loud point")
        tui.press(Input.Key(Keys.RIGHT))
        waitFor { state.settings.value.preferences.volumeBoostPercent == 210 }
        tui.press(Input.Key(Keys.RIGHT, shift = true))
        waitFor { state.settings.value.preferences.volumeBoostPercent == 310 }
        val loud = lines(render()).joinToString("\n")
        assertTrue(VolumeBoost.LOUD_WARNING in loud && "310% ×3.1" in loud, loud)
        tui.press(Input.Text("0"))
        waitFor { state.settings.value.preferences.volumeBoostPercent == 1000 }
        tui.press(Input.Key(Keys.RIGHT))
        assertEquals(1000, state.settings.value.preferences.volumeBoostPercent)
        tui.press(Input.Text("1"))
        waitFor { state.settings.value.preferences.volumeBoostPercent == 100 }
        tui.press(Input.Key(Keys.ESCAPE))
        assertFalse(panel in tui.overlays)
    }

    @Test
    fun `stop goes back to the start, and stop after this song is a key, a row on the queue and a mark on the bar`() {
        playing()
        player.calls.clear()
        tui.press(Input.Text("v"))
        waitFor { player.calls.toList() == listOf("pause", "seek 0") }

        tui.press(Input.Text("."))
        assertTrue(state.stopAfterCurrent.value)
        tui.page = Page.QUEUE
        val canvas = render()
        assertTrue(lines(canvas).any { "Stop after this song" in it && "On: nothing plays after it" in it }, lines(canvas).joinToString("\n"))
        assertTrue(lines(canvas).takeLast(4).any { "■ stops after this" in it }, lines(canvas).takeLast(4).joinToString("\n"))
        tui.press(Input.Text("."))
        assertFalse(state.stopAfterCurrent.value)
    }

    @Test
    fun `clear all but this song leaves the song playing alone, after asking`() {
        playing()
        state.jumpToQueueItem(2)
        waitFor { state.queue.state.value.currentIndex == 2 }
        tui.page = Page.QUEUE
        val canvas = render()
        assertTrue(lines(canvas).any { "Clear all but this song" in it && it.endsWith(" O") }, lines(canvas).joinToString("\n"))
        assertTrue("O only this" in lines(canvas).joinToString("\n"))
        tui.press(Input.Text("O"))
        assertIs<Overlay.Confirm>(tui.overlays.last())
        tui.press(Input.Text("y"))
        waitFor { state.queue.state.value.tracks.size == 1 }
        assertEquals(songs[2], state.queue.state.value.current)
    }

    @Test
    fun `a song from Home starts a radio from it, or plays on through its shelf, as chosen`() {
        state.setAutoplay(true)
        waitFor { state.settings.value.preferences.autoplay }
        Tracks.play(tui, Row.Song(songs[1], songs, PlaybackOrigin.HOME))
        waitFor { state.queue.state.value.current == songs[1] }
        assertEquals(listOf(songs[1]), state.queue.state.value.tracks)

        state.setHomePlay(HomePlay.SHELF)
        waitFor { state.settings.value.preferences.homePlay == HomePlay.SHELF }
        Tracks.play(tui, Row.Song(songs[2], songs, PlaybackOrigin.HOME))
        waitFor { state.queue.state.value.current == songs[2] }
        assertEquals(songs, state.queue.state.value.tracks)
        state.setAutoplay(false)
    }

    @Test
    fun `the Playlist layout is the queue as a table, an info bar with a visualiser, and a status bar`() {
        playing()
        tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
        tui.page = Page.NOW_PLAYING
        player.frames.value = AudioAnalysis(bands = List(10) { .9f }, peaks = listOf(.8f, .7f), rms = listOf(.5f, .4f))
        val canvas = render()
        val page = lines(canvas)
        val header = page.indexOfFirst { "Title" in it && "Artist" in it && "Album" in it && "Duration" in it }
        assertTrue(header >= 0, page.joinToString("\n"))
        assertTrue(page.any { "Queue (4)" in it }, page.joinToString("\n"))
        val playingRow = page.indexOfFirst { "▶" in it && "Awake" in it && "Tycho" in it && "4:43" in it }
        assertTrue(playingRow > header, page.joinToString("\n"))
        // The status bar: what is playing, and how far into the whole queue -- 1:34 of 4:43 + 3 × 3:20 = 14:43.
        val status = page[canvas.height - 1 - 4]
        assertTrue("Opus, stereo, 48 kHz, 128 kbit/s" in status && "1:34 / 14:43" in status, status)

        // The visualiser is measured while it is on screen, and drawn from the frames: full blocks near the top.
        waitFor { player.frames.subscriptionCount.value > 0 }
        waitFor { lines(render()).any { it.count { c -> c == '█' } >= 10 } }

        // Columns as chosen; the title is kept whatever is asked.
        state.setQueueColumns(listOf(QueueColumn.SERVICE, QueueColumn.NUMBER))
        waitFor { state.settings.value.preferences.queueColumns == listOf(QueueColumn.TITLE, QueueColumn.SERVICE, QueueColumn.NUMBER) }
        val chosen = lines(render())
        assertTrue(chosen.any { "Title" in it && "Service" in it && "#" in it && "Artist" !in it }, chosen.joinToString("\n"))
        assertTrue(chosen.any { "Bandcamp" in it && "Song 2" in it }, chosen.joinToString("\n"))

        // The arrows choose, Enter plays.
        tui.press(Input.Key(Keys.DOWN))
        tui.press(Input.Key(Keys.DOWN))
        tui.press(Input.Key(Keys.ENTER))
        waitFor { state.queue.state.value.currentIndex == 2 }

        // Off its page, nothing measures.
        tui.page = Page.QUEUE
        render()
        waitFor { player.frames.subscriptionCount.value == 0 }

        // Without the status bar and with the visualiser off, neither is drawn.
        state.setStatusBar(false)
        state.setVisualiser(VisualiserStyle.OFF)
        waitFor { !state.settings.value.preferences.statusBar }
        tui.page = Page.NOW_PLAYING
        val plain = lines(render())
        assertFalse(plain.any { "kbit/s" in it }, plain.joinToString("\n"))
        assertEquals(0, player.frames.subscriptionCount.value)
        state.setStatusBar(true)
        state.setVisualiser(VisualiserStyle.BARS)
        state.setQueueColumns(QueueColumn.DEFAULT)
    }

    /** Made-up lines, six and a half seconds apart from 0:20, so that the twelfth is being sung at the stand-in's 1:34. */
    /** Made-up lines; at the stand-in's 1:34 the twelfth is being sung. */
    private val words = LyricsStandIn.words

    /** Made-up songs by a made-up band, which no lyrics source could know. */
    private val harbour = listOf("Harbour Lights", "Salt and Static", "Night Ferry").mapIndexed { i, title ->
        Track(
            provider = ProviderType.BANDCAMP, id = "95$i", title = title, artists = listOf(Artist("lanterns", "The Paper Lanterns", ProviderType.BANDCAMP)),
            durationMs = 200_000, sourceUrl = "https://example.invalid/track/$i",
        )
    }

    private fun playingHarbour() {
        state.setAutoplay(false)
        waitFor { !state.settings.value.preferences.autoplay }
        LyricsStandIn.set(state, harbour[0])
        state.play(harbour[0], PlaybackOrigin.SEARCH, harbour)
        waitFor { state.playback.value.status == PlaybackStatus.PLAYING && state.queue.state.value.tracks.size == harbour.size }
    }

    @Test
    fun `the Playlist layout has the lyrics in a tab after the queue's, lit line by line as they are sung`() {
        playingHarbour()
        tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
        tui.page = Page.NOW_PLAYING
        val table = lines(render())
        val tabs = table.indexOfFirst { "Queue (3)" in it }
        assertTrue(tabs >= 0 && table[tabs].indexOf("Lyrics") > table[tabs].indexOf("Queue (3)"), table.joinToString("\n"))
        assertEquals(PlaylistLayout.Tab.QUEUE, PlaylistLayout.shown)

        // ] goes to the lyrics: the sources' chips over them and the line being sung lit, where the table was; the
        // info bar and the status bar stay.
        tui.press(Input.Text("]"))
        assertEquals(PlaylistLayout.Tab.LYRICS, PlaylistLayout.shown)
        val canvas = render()
        val page = lines(canvas)
        assertTrue(page.any { "LRCLIB" in it && "Genius" in it }, page.joinToString("\n"))
        assertFalse(page.any { "Title" in it && "Artist" in it && "Duration" in it }, page.joinToString("\n"))
        val sung = page.indexOfFirst { words[11] in it }
        assertTrue(sung > tabs, page.joinToString("\n"))
        val at = sung * canvas.width + page[sung].indexOf(words[11])
        assertEquals(tui.palette.accent, canvas.fg[at])
        assertTrue(canvas.style[at] and BOLD != 0)
        assertTrue(page.any { words[10] in it } && page.any { words[12] in it }, page.joinToString("\n"))
        assertTrue("Opus, stereo, 48 kHz, 128 kbit/s" in page[canvas.height - 1 - 4], page[canvas.height - 1 - 4])

        // In a short terminal the arrows scroll the words, and Home goes back to the line being sung.
        val short = lines(render(150, 24))
        assertTrue(short.any { words[11] in it } && short.none { words[0] in it }, short.joinToString("\n"))
        repeat(6) { tui.press(Input.Key(Keys.UP)) }
        assertTrue(lines(render(150, 24)).any { words[0] in it }, lines(render(150, 24)).joinToString("\n"))
        tui.press(Input.Key(Keys.HOME))
        assertTrue(lines(render(150, 24)).none { words[0] in it }, lines(render(150, 24)).joinToString("\n"))
        // Enter has no song to play behind the words, and a track's keys work on the song playing.
        tui.press(Input.Key(Keys.ENTER))
        assertEquals(0, state.queue.state.value.currentIndex)
        tui.press(Input.Text("a"))
        waitFor { state.queue.state.value.tracks.size == 4 }
        assertEquals(harbour[0].title, state.queue.state.value.tracks.last().title)

        // Clicking the queue's tab brings the table back, and [ comes round to the lyrics again.
        click(render(), "Queue (4)", tabs)
        assertEquals(PlaylistLayout.Tab.QUEUE, PlaylistLayout.shown)
        assertTrue(lines(render()).any { "Title" in it && "Artist" in it }, lines(render()).joinToString("\n"))
        tui.press(Input.Text("]"))
        assertEquals(PlaylistLayout.Tab.LYRICS, PlaylistLayout.shown)
        tui.press(Input.Text("["))
        assertEquals(PlaylistLayout.Tab.QUEUE, PlaylistLayout.shown)
        tui.press(Input.Text("["))
        assertEquals(PlaylistLayout.Tab.LYRICS, PlaylistLayout.shown, "With no autoplay songs, the tabs go round the queue and the lyrics")

        // Toolbar 2's lyrics button opens the layout on its lyrics, from anywhere.
        PlaylistLayout.shown = PlaylistLayout.Tab.QUEUE
        tui.preferences.playerBar = TuiPlayerBar.TOOLBAR_2
        tui.page = Page.QUEUE
        click(render(), "¶")
        assertEquals(Page.NOW_PLAYING, tui.page)
        assertEquals(PlaylistLayout.Tab.LYRICS, PlaylistLayout.shown)
        tui.preferences.playerBar = TuiPlayerBar.FULL

        // In a Windows theme the words are in the table's white box, the line being sung picked out as a list's
        // chosen row was.
        state.setTheme(ThemePreset.WINDOWS_98)
        waitFor { state.settings.value.preferences.theme == ThemePreset.WINDOWS_98 }
        val boxed = render()
        val row = lines(boxed).indexOfFirst { words[11] in it }
        val cell = row * boxed.width + lines(boxed)[row].indexOf(words[11])
        assertEquals(Skin.selection(tui.palette), boxed.bg[cell])
        assertEquals(Skin.selectionText(tui.palette), boxed.fg[cell])
        state.setTheme(ThemePreset.NOCTORIUM_NIGHT)
        waitFor { state.settings.value.preferences.theme == ThemePreset.NOCTORIUM_NIGHT }

        // With nothing playing, the tab says what it is for.
        state.clearQueue()
        waitFor { state.playback.value.track == null }
        val empty = lines(render())
        assertTrue(empty.any { "Nothing playing" in it } && empty.any { "The words of the song playing are shown here" in it }, empty.joinToString("\n"))
    }

    @Test
    fun `the Lyrics tab draws in every kind of theme, from the smallest screen the player allows to a large one`() {
        tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
        tui.page = Page.NOW_PLAYING
        PlaylistLayout.shown = PlaylistLayout.Tab.LYRICS
        for (playing in listOf(false, true)) {
            if (playing) playingHarbour()
            for (theme in listOf(ThemePreset.NOCTORIUM_NIGHT, ThemePreset.CATPPUCCIN_LATTE, ThemePreset.WINDOWS_98, ThemePreset.WINDOWS_98_NOCTORIUM, ThemePreset.WINDOWS_XP)) {
                state.setTheme(theme)
                waitFor { state.settings.value.preferences.theme == theme }
                for (bar in TuiPlayerBar.entries) {
                    tui.preferences.playerBar = bar
                    for ((w, h) in listOf(40 to 12, 61 to 19, 97 to 33, 220 to 70)) tui.frame(w, h)
                }
            }
        }
        assertEquals(PlaylistLayout.Tab.LYRICS, PlaylistLayout.shown)
    }

    @Test
    fun `the level meter draws each channel where bars are not wanted`() {
        playing()
        state.setVisualiser(VisualiserStyle.LEVEL_METER)
        waitFor { state.settings.value.preferences.visualiser == VisualiserStyle.LEVEL_METER }
        tui.preferences.nowPlaying = TuiNowPlaying.PLAYLIST
        tui.page = Page.NOW_PLAYING
        player.frames.value = AudioAnalysis(peaks = listOf(.95f, .6f), rms = listOf(.7f, .5f))
        render()
        waitFor { player.frames.subscriptionCount.value > 0 }
        fun meters(page: List<String>) = page.any { Regex("L [▏▎▍▌▋▊▉█]{5}").containsMatchIn(it) } && page.any { Regex("R [▏▎▍▌▋▊▉█]{5}").containsMatchIn(it) }
        val until = System.currentTimeMillis() + 10_000
        while (!meters(lines(render())) && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(meters(lines(render())), lines(render()).joinToString("\n"))
        state.setVisualiser(VisualiserStyle.BARS)
    }

    @Test
    fun `the symbols are the terminal's own, plain ASCII, or a Nerd Font's`() {
        playing()
        tui.page = Page.QUEUE
        assertTrue("|◀" in lines(render()).takeLast(4).joinToString("\n"))
        tui.preferences.glyphs = TuiGlyphs.ASCII
        val ascii = lines(render())
        assertTrue(ascii.takeLast(4).any { "|<" in it && ">|" in it }, ascii.takeLast(4).joinToString("\n"))
        assertTrue(ascii.any { it.startsWith("│ ~ Home") || " ~ Home" in it }, ascii.take(10).joinToString("\n"))
        tui.preferences.glyphs = TuiGlyphs.NERD_FONT
        val nerd = lines(render())
        assertTrue(nerd.takeLast(4).any { "" in it && "" in it }, nerd.takeLast(4).joinToString("\n"))
        assertTrue(nerd.any { " Home" in it }, nerd.take(10).joinToString("\n"))
    }

    @Test
    fun `a bang in the search says where this one search goes`() {
        tui.press(Input.Text("/"))
        "!bc awake".forEach { tui.press(Input.Text(it.toString())) }
        waitFor { state.ui.value.searchBang != null }
        val page = lines(render()).joinToString("\n")
        assertTrue("!bc → Bandcamp" in page, page)
        assertEquals("Bandcamp", state.ui.value.activeSearchMode.displayName)
    }

    @Test
    fun `after stop after this song, the bar is about the song play starts`() {
        playing()
        state.setStopAfterCurrent(true)
        // The song ends: the player goes idle with it still set, and core moves the queue on without playing.
        player.mutable.update { it.copy(status = PlaybackStatus.IDLE, positionMs = it.durationMs) }
        waitFor { state.queue.state.value.currentIndex == 1 && !state.stopAfterCurrent.value }
        tui.page = Page.QUEUE
        val bar = lines(render()).takeLast(4).joinToString("\n")
        assertTrue("Song 1" in bar && "Ready in the queue" in bar, bar)
        assertNull(PlayerBar.shownTrack(tui))
        assertNotNull(state.playback.value.track)
    }
}
