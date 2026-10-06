package app.noctorium.cli.tui

import app.noctorium.core.SearchMode
import app.noctorium.domain.PlaybackOrigin
import app.noctorium.domain.Playlist
import app.noctorium.domain.ProviderType
import app.noctorium.domain.Track
import app.noctorium.domain.editableOnService
import app.noctorium.domain.pageUrl
import app.noctorium.downloads.DownloadStage
import app.noctorium.lyrics.LyricsProviderStatus

/**
 * What each page shows, and what its keys do.
 *
 * Most pages are a list of [Row]s built fresh from the state every frame -- cheap, and it means nothing here
 * can fall out of step with what the desktop would show for the same state. Now playing is drawn by hand,
 * for the cover and the lyrics; Devices and Settings are lists of [Row.Action]s.
 */
object Pages {
    private const val LIST_TOP = 3

    fun draw(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val p = tui.palette
        when (tui.page) {
            Page.NOW_PLAYING -> NowPlaying.draw(tui, canvas, x, y, w, h)
            Page.SEARCH -> {
                title(canvas, p, x, y, w, "Search", subtitle(tui))
                searchBox(tui, canvas, x + 2, y + 2, w - 4)
                tui.listView.draw(canvas, rows(tui), tui.list(key(tui)), x + 1, y + 6, w - 2, h - 6, focused = !tui.searchFocused)
            }
            else -> {
                title(canvas, p, x, y, w, heading(tui), subtitle(tui))
                tui.listView.draw(canvas, rows(tui), tui.list(key(tui)), x + 1, y + LIST_TOP, w - 2, h - LIST_TOP)
            }
        }
    }

    private fun title(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, title: String, subtitle: String?) {
        val used = canvas.write(x + 2, y + 1, title, p.text, null, BOLD, max = w / 2)
        subtitle?.let { canvas.write(x + 4 + used, y + 1, it, p.subtext, null, max = w - used - 6) }
    }

    private fun heading(tui: Tui): String = when (tui.page) {
        Page.LIBRARY -> openPlaylistTitle(tui) ?: "Your library"
        Page.SETTINGS -> if (tui.keysOpen) "Keys" else tui.page.title
        else -> tui.page.title
    }

    private fun openPlaylistTitle(tui: Tui): String? {
        if (!tui.libraryOpen) return null
        val library = tui.state.library.value
        return library.openLocalPlaylist?.title ?: library.openPlaylist?.title
    }

    private fun subtitle(tui: Tui): String? {
        val state = tui.state
        val k = tui::key
        return when (tui.page) {
            Page.HOME -> if (state.ui.value.homeLoading) "Loading…" else "From every service"
            Page.SEARCH -> if (state.ui.value.searchLoading) "Searching…" else state.ui.value.searchMode.displayName
            Page.LIBRARY -> {
                val library = state.library.value
                val local = library.openLocalPlaylist
                val open = library.openPlaylist
                when {
                    tui.libraryOpen && local != null ->
                        "On this computer · ${local.tracks.size} tracks · Enter play · ${k(KeyAction.RENAME_PLAYLIST)} rename · ${k(KeyAction.DELETE_PLAYLIST)} delete · Esc back"
                    tui.libraryOpen && open != null -> listOfNotNull(
                        when (open.kind) {
                            ListKind.ARTIST -> "Artist on ${open.provider.displayName}"
                            else -> open.provider.displayName
                        },
                        (open.trackCount ?: open.tracks.size).let { "$it tracks" },
                        when (open.isPublic) { true -> "public"; false -> "private"; null -> null },
                        if (library.openPlaylistLoading) "loading…" else null,
                        if (open.editableOnService()) {
                            "${k(KeyAction.RENAME_PLAYLIST)} rename · ${k(KeyAction.PLAYLIST_VISIBILITY)} public/private · ${k(KeyAction.DELETE_PLAYLIST)} delete"
                        } else null,
                        "Esc back",
                    ).joinToString(" · ")
                    library.loading -> "Loading…"
                    else -> "${library.playlists.size + library.localPlaylists.size} playlists · ${k(KeyAction.NEW_PLAYLIST)} new"
                }
            }
            Page.QUEUE -> state.queue.state.value.let { q ->
                if (q.tracks.isEmpty()) null
                else "${q.tracks.size} tracks · ${k(KeyAction.REMOVE)} remove · ${k(KeyAction.MOVE_DOWN)} ${k(KeyAction.MOVE_UP)} move · ${k(KeyAction.CLEAR_QUEUE)} clear"
            }
            Page.DOWNLOADS -> "${state.downloadState.value.entries.size} kept · Enter play · ${k(KeyAction.REMOVE)} delete"
            Page.DEVICES -> "Noctorium Connect and the web player"
            Page.SETTINGS -> if (tui.keysOpen) "Enter changes one · Delete puts it back · Esc back to Settings" else "← → or Enter to change"
            Page.NOW_PLAYING -> null
        }
    }

    private fun key(tui: Tui): String = when {
        tui.page == Page.LIBRARY && tui.libraryOpen -> "library:" + (tui.state.library.value.openLocalPlaylist?.id ?: tui.state.library.value.openPlaylist?.playlistKey)
        tui.page == Page.SETTINGS && tui.keysOpen -> "SETTINGS:keys"
        else -> tui.page.name
    }

    fun rows(tui: Tui): List<Row> = when (tui.page) {
        Page.HOME -> homeRows(tui)
        Page.SEARCH -> searchRows(tui)
        Page.LIBRARY -> libraryRows(tui)
        Page.QUEUE -> queueRows(tui)
        Page.DOWNLOADS -> downloadRows(tui)
        Page.DEVICES -> Settings.deviceRows(tui)
        Page.SETTINGS -> if (tui.keysOpen) Settings.keyRows(tui) else Settings.rows(tui)
        Page.NOW_PLAYING -> emptyList()
    }

    private fun homeRows(tui: Tui): List<Row> = buildList {
        val ui = tui.state.ui.value
        if (ui.pinnedTracks.isNotEmpty()) {
            add(Row.Header("Pinned"))
            ui.pinnedTracks.forEach { add(Row.Song(it, ui.pinnedTracks, PlaybackOrigin.HOME)) }
            add(Row.Gap)
        }
        if (ui.recentTracks.isNotEmpty()) {
            val recent = ui.recentTracks.take(8)
            add(Row.Header("Played lately"))
            recent.forEach { add(Row.Song(it, recent, PlaybackOrigin.HOME)) }
            add(Row.Gap)
        }
        ui.homeSections.forEach { section ->
            add(Row.Header(section.title, section.subtitle ?: section.provider.displayName))
            section.tracks.forEach { add(Row.Song(it, section.tracks, PlaybackOrigin.HOME)) }
            section.playlists.forEach { add(Row.PlaylistItem(it)) }
            add(Row.Gap)
        }
        if (ui.homeSections.isEmpty()) {
            if (ui.homeLoading) add(Row.Note("Gathering your home from every service…"))
            else add(Row.Note("Nothing here yet. Sign in under Settings (${tui.key(KeyAction.PAGE_SETTINGS)}), or search with ${tui.key(KeyAction.SEARCH)}.", Row.Tone.QUIET))
        }
        ui.errorMessage?.let { add(Row.Note(it, Row.Tone.WARN)) }
    }

    private fun searchRows(tui: Tui): List<Row> = buildList {
        val ui = tui.state.ui.value
        val results = ui.searchResults
        if (ui.searchQuery.isBlank()) {
            add(Row.Note("Type what you are looking for. Every service is searched at once; Tab changes which."))
            return@buildList
        }
        /*
         * Bandcamp and Spotify answer with their albums and artists as playlists, which is what lets Enter
         * open either like any other list, so they are listed under what they are rather than as playlists.
         * The same albums and artists come a second time as bare names, in `albums` and `artists`, and are
         * left out there: a name can only be searched for again, and the playlist above it already opens it.
         */
        val opened = setOf(ProviderType.BANDCAMP, ProviderType.SPOTIFY)
        val byKind = results.playlists.groupBy { it.kind }
        val playlists = byKind[ListKind.PLAYLIST].orEmpty()
        val openableAlbums = byKind[ListKind.ALBUM].orEmpty()
        val openableArtists = byKind[ListKind.ARTIST].orEmpty()
        val albums = results.albums.filter { it.provider !in opened }
        val artists = results.artists.filter { it.provider !in opened }
        if (results.tracks.isNotEmpty()) {
            add(Row.Header("Tracks", "${results.tracks.size}"))
            results.tracks.forEach { add(Row.Song(it, results.tracks, PlaybackOrigin.SEARCH)) }
            add(Row.Gap)
        }
        if (playlists.isNotEmpty()) {
            add(Row.Header("Playlists"))
            playlists.forEach { add(Row.PlaylistItem(it)) }
            add(Row.Gap)
        }
        if (openableAlbums.isNotEmpty() || albums.isNotEmpty()) {
            add(Row.Header("Albums"))
            openableAlbums.forEach { add(Row.PlaylistItem(it)) }
            albums.forEach { album ->
                add(
                    Row.Action(
                        album.title,
                        hint = album.artists.joinToString { it.name }.ifBlank { album.provider.displayName },
                        run = { search(tui, "${album.title} ${album.artists.firstOrNull()?.name.orEmpty()}".trim()) },
                    ),
                )
            }
            add(Row.Gap)
        }
        if (openableArtists.isNotEmpty() || artists.isNotEmpty()) {
            add(Row.Header("Artists"))
            openableArtists.forEach { add(Row.PlaylistItem(it)) }
            artists.forEach { artist ->
                add(Row.Action(artist.name, hint = artist.provider.displayName, run = { search(tui, artist.name) }))
            }
        }
        if (isEmpty() && !ui.searchLoading) add(Row.Note("Nothing found for \"${ui.searchQuery}\"."))
        if (ui.searchLoading && isEmpty()) add(Row.Note("Searching…"))
    }

    private fun search(tui: Tui, query: String) {
        tui.searchText = query
        tui.state.search(query)
        tui.list("SEARCH").selected = 0
    }

    private fun libraryRows(tui: Tui): List<Row> = buildList {
        val library = tui.state.library.value
        val local = library.openLocalPlaylist
        val open = library.openPlaylist
        if (tui.libraryOpen && local != null) {
            if (local.tracks.isEmpty()) add(Row.Note("Nothing in it yet: ${tui.key(KeyAction.ADD_TO_PLAYLIST)} on any track adds it here."))
            local.tracks.forEachIndexed { i, track ->
                add(Row.Song(track, local.tracks, PlaybackOrigin.PLAYLIST, number = i + 1, inLocalPlaylist = local, playlistIndex = i))
            }
            return@buildList
        }
        if (tui.libraryOpen && open != null) {
            library.openPlaylistError?.let { add(Row.Note(it, Row.Tone.BAD)) }
            if (open.tracks.isEmpty() && library.openPlaylistLoading) add(Row.Note("Opening…"))
            open.tracks.forEachIndexed { i, track ->
                add(Row.Song(track, open.tracks, PlaybackOrigin.PLAYLIST, number = i + 1, inPlaylist = open, playlistIndex = i))
            }
            if (library.openPlaylistEnriching) add(Row.Note("Filling in covers and lengths…"))
            return@buildList
        }
        tui.libraryOpen = false
        if (library.needsSoundCloudUsername) {
            add(Row.Note("Noctorium needs your SoundCloud profile name to find your playlists: Settings → SoundCloud.", Row.Tone.WARN))
        }
        library.errorMessage?.let { add(Row.Note(it, Row.Tone.BAD)) }
        val byService = library.playlists.groupBy { it.provider }
        // Bandcamp's are a fan's collection, a playlist for each release, and their wishlist; VK's, My music
        // and then the account's playlists.
        listOf(
            ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO, ProviderType.SOUNDCLOUD, ProviderType.SPOTIFY,
            ProviderType.BANDCAMP, ProviderType.VK,
        ).forEach { provider ->
            val lists = byService[provider].orEmpty()
            if (lists.isEmpty()) return@forEach
            add(Row.Header(provider.displayName, "${lists.size}"))
            lists.forEach { add(Row.PlaylistItem(it)) }
            add(Row.Gap)
        }
        if (library.localPlaylists.isNotEmpty()) {
            add(Row.Header("On this computer", "${library.localPlaylists.size}"))
            library.localPlaylists.forEach { add(Row.LocalItem(it)) }
            add(Row.Gap)
        }
        if (library.playlists.isEmpty() && library.localPlaylists.isEmpty()) {
            add(Row.Note(if (library.loading) "Loading your playlists…" else "No playlists yet. Sign in under Settings to see yours."))
        }
        add(Row.Action("New playlist…", tone = Row.Tone.ACCENT, run = { Tracks.newPlaylist(tui, null) }))
    }

    private fun queueRows(tui: Tui): List<Row> = buildList {
        val queue = tui.state.queue.state.value
        if (queue.tracks.isEmpty()) {
            add(Row.Note("The queue is empty. Play something, or press ${tui.key(KeyAction.ADD_TO_QUEUE)} on a track to add it."))
            return@buildList
        }
        val current = queue.currentIndex
        if (current in queue.tracks.indices) {
            add(Row.Header("Now"))
            add(Row.Song(queue.tracks[current], queue.tracks, PlaybackOrigin.QUEUE, queueIndex = current))
            add(Row.Gap)
        }
        if (current + 1 < queue.tracks.size) {
            add(Row.Header("Up next", "${queue.tracks.size - current - 1}"))
            for (i in current + 1 until queue.tracks.size) add(Row.Song(queue.tracks[i], queue.tracks, PlaybackOrigin.QUEUE, number = i - current, queueIndex = i))
        }
        if (current > 0) {
            add(Row.Gap)
            add(Row.Header("Played"))
            for (i in 0 until current) add(Row.Song(queue.tracks[i], queue.tracks, PlaybackOrigin.QUEUE, queueIndex = i))
        }
    }

    private fun downloadRows(tui: Tui): List<Row> = buildList {
        val downloads = tui.state.downloadState.value
        if (downloads.active.isNotEmpty()) {
            add(Row.Header("Downloading"))
            downloads.active.forEach { job ->
                val bar = progress(job.progress, 20)
                val status = when (job.stage) {
                    DownloadStage.QUEUED -> "waiting"
                    DownloadStage.DOWNLOADING -> "$bar ${(job.progress * 100).toInt()}%"
                    DownloadStage.FAILED -> job.detail ?: "failed"
                }
                add(
                    Row.Action(
                        job.track.title,
                        value = status,
                        tone = if (job.stage == DownloadStage.FAILED) Row.Tone.BAD else Row.Tone.NORMAL,
                        run = { tui.state.cancelDownload(job.track.queueKey) },
                    ),
                )
            }
            add(Row.Gap)
        }
        val tracks = downloads.entries.map { it.toTrack() }
        if (tracks.isNotEmpty()) {
            add(Row.Header("Kept to play offline", "${tracks.size}"))
            tracks.forEach { add(Row.Song(it, tracks, PlaybackOrigin.LIBRARY)) }
        } else if (downloads.active.isEmpty()) {
            add(Row.Note("Nothing kept yet. Press ${tui.key(KeyAction.DOWNLOAD)} on a track to keep it for when there is no connection."))
        }
    }

    fun progress(value: Float, width: Int): String {
        val filled = (value.coerceIn(0f, 1f) * width).toInt()
        return "━".repeat(filled) + "─".repeat(width - filled)
    }

    // --- Keys ---

    fun searchBox(tui: Tui, canvas: Canvas, x: Int, y: Int, w: Int) {
        val p = tui.palette
        val focused = tui.searchFocused
        val field = p.card
        canvas.box(x, y, w, 3, if (focused) p.accent else p.line, field)
        canvas.write(x + 2, y + 1, "⌕", if (focused) p.accent else p.subtext, field, BOLD)
        if (tui.searchText.isEmpty()) {
            canvas.write(x + 4, y + 1, "Songs, artists, playlists — or paste a link", p.faint, field, ITALIC, max = w - 6)
        } else {
            val shown = tui.searchText.takeLast(w - 8)
            val used = canvas.write(x + 4, y + 1, shown, p.text, field, max = w - 7)
            if (focused) canvas.set(x + 4 + used, y + 1, "▏", p.accent, field, BOLD)
        }
        tui.clickTargets += Tui.ClickTarget(x, y, w, 3) { tui.searchFocused = true }
        // The service chips, Tab cycles them.
        var cx = x + 1
        SearchMode.entries.forEach { mode ->
            val active = tui.state.ui.value.searchMode == mode
            val label = " ${mode.displayName} "
            val used = canvas.write(cx, y + 3, label, if (active) p.onAccent else p.subtext, if (active) p.accent else null, if (active) BOLD else 0)
            val target = mode
            tui.clickTargets += Tui.ClickTarget(cx, y + 3, used, 1) { tui.state.setSearchMode(target) }
            cx += used + 1
        }
    }

    /** Keys while typing in the search box. True when the key was the box's. */
    fun searchInput(tui: Tui, input: Input): Boolean {
        when (input) {
            is Input.Key -> when (input.key) {
                Keys.ENTER, Keys.DOWN -> {
                    tui.searchFocused = false
                    val text = tui.searchText.trim()
                    if (input.key == Keys.ENTER && looksLikeLink(text)) {
                        tui.state.openLink(text)
                        tui.toast("Opening the link…")
                    } else if (text.isNotEmpty()) {
                        tui.state.search(text)
                    }
                    return true
                }
                Keys.ESCAPE -> { tui.searchFocused = false; return true }
                Keys.BACKSPACE -> {
                    if (tui.searchText.isNotEmpty()) {
                        tui.searchText = tui.searchText.substring(0, tui.searchText.offsetByCodePoints(tui.searchText.length, -1))
                        if (!looksLikeLink(tui.searchText)) tui.state.search(tui.searchText)
                    }
                    return true
                }
                Keys.TAB -> { cycleSearchMode(tui); return true }
                else -> return false
            }
            is Input.Text -> {
                if (input.char == "^u") { tui.searchText = ""; tui.state.search(""); return true }
                if (input.char.startsWith("^") && input.char.length == 2) return false
                if (input.alt) return false
                tui.searchText += input.char
                // Search as it is typed: core waits for a pause in the typing before it asks anybody.
                if (!looksLikeLink(tui.searchText)) tui.state.search(tui.searchText)
                tui.list("SEARCH").selected = 0
                return true
            }
            else -> return false
        }
    }

    private fun cycleSearchMode(tui: Tui) {
        val modes = SearchMode.entries
        tui.state.setSearchMode(modes[(tui.state.ui.value.searchMode.ordinal + 1) % modes.size])
    }

    /** Whether what was typed is an address to open rather than words to look for. */
    fun looksLikeLink(text: String) = text.startsWith("http://") || text.startsWith("https://") ||
        text.startsWith("music.youtube.com") || text.startsWith("soundcloud.com") || text.startsWith("youtu.be") ||
        // Bandcamp's addresses are the artist's own, artist.bandcamp.com/album/…, and pasted without the https.
        ('/' in text && text.substringBefore('/').endsWith(".bandcamp.com"))

    fun handle(tui: Tui, input: Input): Boolean {
        if (tui.page == Page.NOW_PLAYING) return NowPlaying.handle(tui, input)
        val rows = rows(tui)
        val list = tui.list(key(tui))
        list.clamp(rows)
        val row = rows.getOrNull(list.selected)
        val pageSize = (tui.screen.height - 10).coerceAtLeast(3)
        if (input is Input.Key) {
            when (input.key) {
                Keys.UP -> { if (tui.page == Page.SEARCH && list.selected <= rows.indexOfFirst { it.selectable }) tui.searchFocused = true else list.move(rows, -1); return true }
                Keys.DOWN -> { list.move(rows, 1); return true }
                Keys.PAGE_UP -> { list.move(rows, -pageSize); return true }
                Keys.PAGE_DOWN -> { list.move(rows, pageSize); return true }
                Keys.HOME -> { list.toEnd(rows, false); return true }
                Keys.END -> { list.toEnd(rows, true); return true }
                Keys.ENTER -> { row?.let { activate(tui, it) }; return true }
                Keys.ESCAPE, Keys.BACKSPACE -> {
                    if (tui.page == Page.LIBRARY && tui.libraryOpen) {
                        tui.libraryOpen = false
                        tui.state.closePlaylist()
                        tui.state.closeLocalPlaylist()
                        return true
                    }
                    if (tui.page == Page.SETTINGS && tui.keysOpen) {
                        tui.keysOpen = false
                        return true
                    }
                    return input.key == Keys.BACKSPACE
                }
                Keys.DELETE -> if (row is Row.Action && row.remove != null) { row.remove.invoke(); return true }
                Keys.LEFT, Keys.RIGHT -> {
                    if (row is Row.Action && row.step != null) {
                        row.step.invoke(if (input.key == Keys.RIGHT) 1 else -1)
                        return true
                    }
                    return false
                }
                Keys.TAB -> if (tui.page == Page.SEARCH) { cycleSearchMode(tui); return true }
                else -> Unit
            }
        }
        if (input is Input.Text) {
            val keys = tui.keys
            if (row is Row.Song && Tracks.handle(tui, row, input.char)) return true
            // The playlist key on a playlist does nothing, rather than reaching whatever is bound everywhere.
            if (row is Row.PlaylistItem && keys.matches(input.char, KeyAction.ADD_TO_PLAYLIST)) return true
            if (tui.page == Page.LIBRARY && Tracks.playlistKeys(tui, input.char)) return true
            if (tui.page == Page.QUEUE && keys.matches(input.char, KeyAction.CLEAR_QUEUE)) {
                tui.overlays.addLast(Overlay.Confirm("Clear the queue?", "Playback stops too.") { tui.state.clearQueue() })
                return true
            }
            if (tui.page == Page.DOWNLOADS && keys.matches(input.char, KeyAction.DELETE_ALL_DOWNLOADS)) {
                tui.overlays.addLast(Overlay.Confirm("Delete every kept track?", "They can be downloaded again any time.") { tui.state.deleteAllDownloads() })
                return true
            }
            if (tui.page == Page.HOME && keys.matches(input.char, KeyAction.REFRESH_HOME)) {
                tui.state.refreshHome()
                tui.toast("Refreshing your home…")
                return true
            }
        }
        return false
    }

    private fun activate(tui: Tui, row: Row) {
        val state = tui.state
        when (row) {
            is Row.Song -> Tracks.play(tui, row)
            is Row.PlaylistItem -> {
                tui.page = Page.LIBRARY
                tui.libraryOpen = true
                state.openPlaylist(row.playlist)
            }
            is Row.LocalItem -> {
                tui.page = Page.LIBRARY
                tui.libraryOpen = true
                state.openLocalPlaylist(row.playlist)
            }
            is Row.Action -> row.run()
            else -> Unit
        }
    }

    fun scroll(tui: Tui, by: Int) {
        if (tui.page == Page.NOW_PLAYING) { NowPlaying.scroll(by); return }
        val rows = rows(tui)
        tui.list(key(tui)).move(rows, by)
    }

    fun click(tui: Tui, x: Int, y: Int) {
        if (tui.page == Page.NOW_PLAYING) return
        val index = tui.listView.rowAt(y) ?: return
        if (x < tui.listView.left || x >= tui.listView.left + tui.listView.width) return
        val rows = rows(tui)
        val row = rows.getOrNull(index) ?: return
        if (!row.selectable) return
        val list = tui.list(key(tui))
        if (tui.page == Page.SEARCH) tui.searchFocused = false
        if (list.selected == index) activate(tui, row) else list.selected = index
    }
}

/** What can be done to a track wherever it is listed. */
object Tracks {
    fun play(tui: Tui, row: Row.Song) {
        val state = tui.state
        when {
            row.queueIndex != null -> state.jumpToQueueItem(row.queueIndex)
            row.inPlaylist != null -> state.playPlaylist(row.inPlaylist, startAt = row.track)
            row.inLocalPlaylist != null -> state.playLocalPlaylist(row.inLocalPlaylist, startAt = row.track)
            tui.page == Page.DOWNLOADS -> state.playDownloads(row.track)
            else -> state.play(row.track, row.origin, row.list)
        }
    }

    fun handle(tui: Tui, row: Row.Song, key: String): Boolean {
        val state = tui.state
        val track = row.track
        when (tui.keys.action(key, KeyScope.TRACK)) {
            KeyAction.ADD_TO_QUEUE -> { state.addToQueue(track); tui.toast("Added to the queue: ${track.title}") }
            KeyAction.PLAY_NEXT -> { state.playNext(track); tui.toast("Playing next: ${track.title}") }
            KeyAction.LIKE -> tui.like(track)
            KeyAction.DOWNLOAD -> if (!state.canKeep(track)) notKept(tui, track) else { state.downloadTrack(track); tui.toast("Downloading ${track.title}…") }
            KeyAction.SAVE_MP3 -> when {
                !state.canKeep(track) -> notKept(tui, track)
                state.canSaveAsMp3() -> { state.exportTrack(track); tui.toast("Saving ${track.title} as an MP3…") }
                else -> tui.toast("Saving as MP3 needs mpv", Row.Tone.WARN)
            }
            KeyAction.COPY_LINK -> state.copyTrackLink(track)
            KeyAction.OPEN_PAGE -> state.openExternalUrl(track.pageUrl)
            KeyAction.PIN -> { val pinned = state.isPinned(track); state.togglePin(track); tui.toast(if (pinned) "Unpinned from Home" else "Pinned to Home") }
            KeyAction.ADD_TO_PLAYLIST -> tui.overlays.addLast(addToPlaylist(tui, track))
            KeyAction.REMOVE -> remove(tui, row)
            KeyAction.MOVE_DOWN -> move(tui, row, 1)
            KeyAction.MOVE_UP -> move(tui, row, -1)
            else -> return false
        }
        return true
    }

    /**
     * What the download and save keys say on a track that may not be kept (see AppState.canKeep): Bandcamp's,
     * streamed to be heard on the way to being bought, and VK's, which VK licenses for playing and not for
     * keeping. Said here rather than left to core, which would refuse as well, so that "Downloading…" is never
     * shown for a download that is not going to happen.
     */
    fun notKept(tui: Tui, track: Track) = tui.toast(
        if (track.provider == ProviderType.VK) "VK's songs play here but cannot be downloaded (${tui.key(KeyAction.OPEN_PAGE)} opens it on VK)"
        else "Bandcamp's songs are for listening here; to keep one, buy it on its page (${tui.key(KeyAction.OPEN_PAGE)} opens it)",
        Row.Tone.WARN,
        6,
    )

    private fun remove(tui: Tui, row: Row.Song) {
        val state = tui.state
        val track = row.track
        when {
            row.queueIndex != null -> state.removeQueueItem(row.queueIndex)
            row.inLocalPlaylist != null -> state.removeTrackFromPlaylist(row.inLocalPlaylist.id, track.queueKey)
            row.inPlaylist != null && row.inPlaylist.editableOnService() -> tui.overlays.addLast(
                Overlay.Confirm("Take \"${track.title}\" out of ${row.inPlaylist.title}?", "This changes the playlist on ${row.inPlaylist.provider.displayName}.") {
                    when (row.inPlaylist.provider) {
                        ProviderType.SOUNDCLOUD -> state.removeTrackFromSoundCloudPlaylist(row.inPlaylist.id, track.id)
                        else -> state.removeTrackFromYouTubePlaylist(row.inPlaylist.id, track.id)
                    }
                },
            )
            tui.page == Page.DOWNLOADS -> state.deleteDownload(track.queueKey)
            else -> tui.toast("Nothing to remove it from here", Row.Tone.QUIET)
        }
    }

    private fun move(tui: Tui, row: Row.Song, by: Int) {
        val state = tui.state
        val list = tui.lists.values
        when {
            row.queueIndex != null -> {
                val to = row.queueIndex + by
                if (to in state.queue.state.value.tracks.indices && to > state.queue.state.value.currentIndex) {
                    state.moveQueueItem(row.queueIndex, to)
                    list.forEach { it.selected += by }
                }
            }
            row.inLocalPlaylist != null && row.playlistIndex != null -> {
                val to = row.playlistIndex + by
                if (to in row.inLocalPlaylist.tracks.indices) {
                    state.moveInLocalPlaylist(row.inLocalPlaylist.id, row.playlistIndex, to)
                    tui.list(currentKey(tui)).selected += by
                }
            }
            row.inPlaylist != null && row.playlistIndex != null && row.inPlaylist.provider != ProviderType.SOUNDCLOUD && row.inPlaylist.editableOnService() -> {
                val to = row.playlistIndex + by
                if (to in row.inPlaylist.tracks.indices) {
                    state.moveInYouTubePlaylist(row.playlistIndex, to)
                    tui.list(currentKey(tui)).selected += by
                }
            }
            else -> tui.toast("This list cannot be reordered", Row.Tone.QUIET)
        }
    }

    private fun currentKey(tui: Tui): String =
        "library:" + (tui.state.library.value.openLocalPlaylist?.id ?: tui.state.library.value.openPlaylist?.playlistKey)

    fun addToPlaylist(tui: Tui, track: Track): Overlay {
        val state = tui.state
        val library = state.library.value
        val sameService = library.playlists.filter { playlist ->
            playlist.editableOnService() && when (track.provider) {
                ProviderType.SOUNDCLOUD -> playlist.provider == ProviderType.SOUNDCLOUD
                ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO -> playlist.provider != ProviderType.SOUNDCLOUD
                else -> false
            }
        }
        val options = buildList<Pair<String, () -> Unit>> {
            sameService.forEach { playlist ->
                add("${playlist.provider.badge()}  ${playlist.title}" to {
                    state.addTrackToPlaylist(playlist, track)
                    tui.toast("Added to ${playlist.title}", Row.Tone.GOOD)
                })
            }
            library.localPlaylists.forEach { playlist ->
                add("♪   ${playlist.title}" to {
                    state.addTrackToPlaylist(playlist.id, track)
                    tui.toast("Added to ${playlist.title}", Row.Tone.GOOD)
                })
            }
            if (track.provider in setOf(ProviderType.SOUNDCLOUD, ProviderType.YOUTUBE_MUSIC, ProviderType.YOUTUBE_VIDEO)) {
                add("+   New playlist on ${track.provider.displayName}…" to { newPlaylist(tui, track, track.provider) })
            }
            add("+   New playlist on this computer…" to { newPlaylist(tui, track, null) })
        }
        return Overlay.Picker("Add to a playlist", options, if (!library.loaded) "Your playlists are still loading." else null)
    }

    fun newPlaylist(tui: Tui, track: Track?, provider: ProviderType? = null) {
        val state = tui.state
        if (provider == null && track == null) {
            // From the library: which kind first.
            tui.overlays.addLast(
                Overlay.Picker(
                    "New playlist",
                    listOf(
                        "On YouTube Music" to { newPlaylist(tui, null, ProviderType.YOUTUBE_MUSIC) },
                        "On SoundCloud" to { newPlaylist(tui, null, ProviderType.SOUNDCLOUD) },
                        "On this computer only" to { prompt(tui, null, null) },
                    ),
                ),
            )
            return
        }
        prompt(tui, track, provider)
    }

    private fun prompt(tui: Tui, track: Track?, provider: ProviderType?) {
        val state = tui.state
        tui.overlays.addLast(
            Overlay.Prompt(
                if (provider != null) "New playlist on ${provider.displayName}" else "New playlist on this computer",
                if (provider != null) "Private to begin with; V on it makes it public." else "Kept in Noctorium on this computer.",
            ) { title ->
                if (title.isBlank()) return@Prompt
                if (provider != null) {
                    state.createPlaylist(title, provider, listOfNotNull(track))
                } else {
                    state.createPlaylist(title, track)
                }
                tui.toast("Made \"$title\"", Row.Tone.GOOD)
            },
        )
    }

    /** R, V and D on the library page: rename, public or private, delete, for the open playlist or the one chosen. */
    fun playlistKeys(tui: Tui, key: String): Boolean {
        val state = tui.state
        val library = state.library.value
        val rows = Pages.rows(tui)
        val selected = rows.getOrNull(tui.list(if (tui.libraryOpen) currentKey(tui) else "LIBRARY").selected)
        val local = if (tui.libraryOpen) library.openLocalPlaylist else (selected as? Row.LocalItem)?.playlist
        val service: Playlist? = if (tui.libraryOpen) library.openPlaylist?.takeIf { library.openLocalPlaylist == null } else (selected as? Row.PlaylistItem)?.playlist
        when (tui.keys.action(key, KeyScope.LIBRARY)) {
            KeyAction.NEW_PLAYLIST -> { newPlaylist(tui, null); return true }
            KeyAction.SHUFFLE_PLAYLIST -> {
                val playlist = service ?: return false
                if (!state.queue.state.value.shuffleEnabled) state.toggleShuffle()
                state.playPlaylist(playlist)
                return true
            }
            KeyAction.RENAME_PLAYLIST -> {
                if (local != null) {
                    tui.overlays.addLast(Overlay.Prompt("Rename", "A new name for this playlist.", local.title) { if (it.isNotBlank()) state.renamePlaylist(local.id, it) })
                    return true
                }
                val playlist = service?.takeIf { it.editableOnService() } ?: return false
                tui.overlays.addLast(
                    Overlay.Prompt("Rename", "Renamed on ${playlist.provider.displayName} too.", playlist.title) { title ->
                        if (title.isBlank()) return@Prompt
                        when (playlist.provider) {
                            ProviderType.SOUNDCLOUD -> state.renameSoundCloudPlaylist(playlist.id, title)
                            else -> state.renameYouTubePlaylist(playlist.id, title)
                        }
                    },
                )
                return true
            }
            KeyAction.PLAYLIST_VISIBILITY -> {
                val playlist = service?.takeIf { it.editableOnService() } ?: return false
                val makePublic = playlist.isPublic != true
                when (playlist.provider) {
                    ProviderType.SOUNDCLOUD -> state.setSoundCloudPlaylistVisibility(playlist.id, makePublic)
                    else -> state.setYouTubePlaylistVisibility(playlist.id, makePublic)
                }
                tui.toast(if (makePublic) "Making it public…" else "Making it private…")
                return true
            }
            KeyAction.DELETE_PLAYLIST -> {
                if (local != null) {
                    tui.overlays.addLast(Overlay.Confirm("Delete \"${local.title}\"?", "It is only on this computer.") {
                        state.deletePlaylist(local.id)
                        tui.libraryOpen = false
                    })
                    return true
                }
                val playlist = service?.takeIf { it.editableOnService() } ?: return false
                tui.overlays.addLast(
                    Overlay.Confirm("Delete \"${playlist.title}\"?", "It is deleted from ${playlist.provider.displayName}, not only from here.") {
                        when (playlist.provider) {
                            ProviderType.SOUNDCLOUD -> state.deleteSoundCloudPlaylist(playlist.id)
                            else -> state.deleteYouTubePlaylist(playlist.id)
                        }
                        tui.libraryOpen = false
                        state.closePlaylist()
                    },
                )
                return true
            }
            else -> Unit
        }
        return false
    }
}

/** The lyrics provider's status, as a mark on its chip. */
fun LyricsProviderStatus.mark(): String = when (this) {
    LyricsProviderStatus.SEARCHING -> "…"
    LyricsProviderStatus.FOUND -> "✓"
    LyricsProviderStatus.LINK_ONLY -> "↗"
    LyricsProviderStatus.NOT_FOUND -> "·"
    LyricsProviderStatus.NEEDS_KEY -> "×"
    LyricsProviderStatus.ERROR -> "!"
}
