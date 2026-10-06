package app.noctorium.cli.tui

/**
 * Where a key does what it does: everywhere, on the track that is chosen, or on one page.
 *
 * Two things may share a key only where they can never both be reached by the same press. A page's own
 * keys come before the track's, and the track's before the ones that work everywhere, so a key bound twice
 * in places that meet would quietly stop doing one of its two jobs.
 */
enum class KeyScope(val title: String) {
    EVERYWHERE("Everywhere"),
    /** On any list with a track chosen, and on Now playing for the one playing. */
    TRACK("The selected track"),
    HOME("Home"),
    LIBRARY("Library"),
    QUEUE("Queue"),
    DOWNLOADS("Downloads"),
    NOW_PLAYING("Now playing");

    /** Whether one press could reach a key here and the same key in [other]. Two pages never meet. */
    fun meets(other: KeyScope): Boolean = this == other || this == EVERYWHERE || other == EVERYWHERE || this == TRACK || other == TRACK
}

/**
 * Everything a key can be bound to in the player, with the keys it has always had.
 *
 * The defaults are the player's keys as they were before they could be changed, letter for letter, so
 * nobody's fingers have to learn anything. The keys that move about -- the arrows, Enter, Escape, Tab --
 * and the ones inside a list or a question that is open are not here: they are the same in every terminal
 * program, and a player whose Escape had been moved would be hard to get out of.
 */
enum class KeyAction(val label: String, val scope: KeyScope, val defaults: List<String>) {
    PLAY_PAUSE("Play or pause", KeyScope.EVERYWHERE, " "),
    NEXT("Next", KeyScope.EVERYWHERE, "n"),
    PREVIOUS("Previous", KeyScope.EVERYWHERE, "p"),
    VOLUME_UP("Louder", KeyScope.EVERYWHERE, listOf("+", "=")),
    VOLUME_DOWN("Quieter", KeyScope.EVERYWHERE, listOf("-", "_")),
    MUTE("Mute", KeyScope.EVERYWHERE, "m"),
    SHUFFLE("Shuffle", KeyScope.EVERYWHERE, "s"),
    REPEAT("Repeat", KeyScope.EVERYWHERE, "r"),
    SLOWER("Slower", KeyScope.EVERYWHERE, "<"),
    FASTER("Faster", KeyScope.EVERYWHERE, ">"),
    LIKE_PLAYING("Like the one playing", KeyScope.EVERYWHERE, "L"),
    SEARCH("Search", KeyScope.EVERYWHERE, "/"),
    SLEEP_TIMER("Sleep timer", KeyScope.EVERYWHERE, "z"),
    THEME_NEXT("Next theme", KeyScope.EVERYWHERE, "t"),
    THEME_PREVIOUS("Previous theme", KeyScope.EVERYWHERE, "T"),
    WEB_PLAYER("The web player", KeyScope.EVERYWHERE, "w"),
    HELP("These keys", KeyScope.EVERYWHERE, "?"),
    QUIT("Quit", KeyScope.EVERYWHERE, "q"),
    PAGE_HOME("Go to Home", KeyScope.EVERYWHERE, "1"),
    PAGE_SEARCH("Go to Search", KeyScope.EVERYWHERE, "2"),
    PAGE_LIBRARY("Go to Library", KeyScope.EVERYWHERE, "3"),
    PAGE_QUEUE("Go to Queue", KeyScope.EVERYWHERE, "4"),
    PAGE_NOW_PLAYING("Go to Now playing", KeyScope.EVERYWHERE, "5"),
    PAGE_DOWNLOADS("Go to Downloads", KeyScope.EVERYWHERE, "6"),
    PAGE_DEVICES("Go to Devices", KeyScope.EVERYWHERE, "7"),
    PAGE_SETTINGS("Go to Settings", KeyScope.EVERYWHERE, "8"),

    ADD_TO_QUEUE("Add to the queue", KeyScope.TRACK, "a"),
    PLAY_NEXT("Play it next", KeyScope.TRACK, "A"),
    LIKE("Like or unlike", KeyScope.TRACK, "l"),
    ADD_TO_PLAYLIST("Add to a playlist", KeyScope.TRACK, "P"),
    DOWNLOAD("Download to keep", KeyScope.TRACK, "d"),
    SAVE_MP3("Save as an MP3", KeyScope.TRACK, "e"),
    COPY_LINK("Copy its link", KeyScope.TRACK, "c"),
    OPEN_PAGE("Open it in the browser", KeyScope.TRACK, "o"),
    PIN("Pin to Home, or unpin", KeyScope.TRACK, "i"),
    REMOVE("Take it out of the queue or playlist", KeyScope.TRACK, "x"),
    MOVE_DOWN("Move it down", KeyScope.TRACK, "J"),
    MOVE_UP("Move it up", KeyScope.TRACK, "K"),

    REFRESH_HOME("Refresh Home", KeyScope.HOME, "R"),
    NEW_PLAYLIST("New playlist", KeyScope.LIBRARY, "N"),
    SHUFFLE_PLAYLIST("Shuffle the playlist", KeyScope.LIBRARY, "S"),
    RENAME_PLAYLIST("Rename the playlist", KeyScope.LIBRARY, "R"),
    PLAYLIST_VISIBILITY("Make it public or private", KeyScope.LIBRARY, "V"),
    DELETE_PLAYLIST("Delete the playlist", KeyScope.LIBRARY, "D"),
    CLEAR_QUEUE("Clear the queue", KeyScope.QUEUE, "C"),
    DELETE_ALL_DOWNLOADS("Delete every download", KeyScope.DOWNLOADS, "X"),
    LYRICS_PREVIOUS("The lyrics source before", KeyScope.NOW_PLAYING, "["),
    LYRICS_NEXT("The lyrics source after", KeyScope.NOW_PLAYING, "]"),
    LYRICS_AGAIN("Ask for the lyrics again", KeyScope.NOW_PLAYING, "R"),
    FOLLOW_ARTIST("Follow the artist", KeyScope.NOW_PLAYING, "f");

    constructor(label: String, scope: KeyScope, key: String) : this(label, scope, listOf(key))
}

/**
 * The keys in force: the defaults, with whatever the listener changed laid over them.
 *
 * Kept as the changes alone (see [TuiPreferences.keys]), so a key added in a later version arrives with
 * its default rather than missing, and putting one back is forgetting the change.
 */
class KeyMap private constructor(
    /** The keys the listener changed, each already checked against the rest; see [with]. */
    val changes: Map<KeyAction, List<String>>,
) {
    fun keys(action: KeyAction): List<String> = changes[action] ?: action.defaults

    /** What [key] does in [scope], if anything. */
    fun action(key: String, scope: KeyScope): KeyAction? =
        KeyAction.entries.firstOrNull { it.scope == scope && key in keys(it) }

    /** Whether [key] is [action]'s, wherever this is asked. */
    fun matches(key: String, action: KeyAction): Boolean = key in keys(action)

    /** The one thing that already has [key] where [action] would meet it, which would make them clash. */
    fun clash(action: KeyAction, key: String): KeyAction? =
        KeyAction.entries.firstOrNull { other -> other != action && key in keys(other) && other.scope.meets(action.scope) }

    /** This map with [action] on [key] instead, or null when [key] cannot be a key or would clash. */
    fun with(action: KeyAction, key: String): KeyMap? {
        if (!bindable(key) || clash(action, key) != null) return null
        val keys = listOf(key)
        return KeyMap(if (keys == action.defaults) changes - action else changes + (action to keys))
    }

    /** This map with [action] back on its own keys, or null when another has taken one of them since. */
    fun reset(action: KeyAction): KeyMap? {
        val back = KeyMap(changes - action)
        return back.takeIf { action.defaults.none { key -> back.clash(action, key) != null } }
    }

    /** The first of [action]'s keys, as a person would name it: "Space" rather than a blank. */
    fun name(action: KeyAction): String = describe(keys(action).first())

    /** All of [action]'s keys, named and separated: "+ =". */
    fun names(action: KeyAction): String = keys(action).joinToString(" ") { describe(it) }

    /** The changes by action name, for the preferences file. */
    fun saved(): Map<String, List<String>> = changes.mapKeys { it.key.name }

    companion object {
        /** The keys as they come. */
        val DEFAULT = KeyMap(emptyMap())

        /** One printable character, which is what a key press arrives as. Escape, Tab and the arrows are fixed. */
        fun bindable(key: String): Boolean =
            key.isNotEmpty() && key.codePointCount(0, key.length) == 1 && !key[0].isISOControl()

        fun describe(key: String): String = if (key == " ") "Space" else key

        /**
         * The map the preferences file describes, judged whole: two keys swapped clash halfway through
         * whichever is laid on first, and are fine together. A change that is unknown or cannot be a key is
         * left out, and so is one that clashes with what remains -- a file edited by hand -- one at a time,
         * until none does, so one bad line never takes the rest with it.
         */
        fun loaded(saved: Map<String, List<String>>): KeyMap {
            val wanted = saved.mapNotNull { (name, keys) ->
                val action = KeyAction.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                val usable = keys.filter(::bindable).distinct().takeIf { it.isNotEmpty() && it != action.defaults }
                usable?.let { action to it }
            }.toMap()
            var map = KeyMap(wanted)
            while (true) {
                val clashing = map.changes.keys.firstOrNull { action -> map.keys(action).any { map.clash(action, it) != null } } ?: break
                map = KeyMap(map.changes - clashing)
            }
            return map
        }
    }
}
