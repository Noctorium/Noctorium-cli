package app.noctorium.cli.tui

/**
 * The symbols the terminal draws its buttons and its pages with, in the set chosen in Settings ([TuiGlyphs]).
 *
 * Core's icon sets -- rounded, outlined, sharp, a thin line -- are drawn shapes, and a cell holds a character, so
 * the terminal has sets of its own instead: its own symbols, which every font has and which are the default so
 * nothing changes until it is chosen; plain ASCII, for a terminal that shows those as boxes; and a Nerd Font's
 * icons, for a terminal set in one. Nerd Fonts patch Font Awesome's icons into the private use area at the code
 * points used here; Noctorium names the code points and ships no font, so the icons are whatever the listener's
 * own font draws there.
 *
 * Every transport symbol is two cells at most, so a layout built for the standard set fits the others too.
 */
class Glyphs private constructor(
    val previous: String,
    /** Play, padded to the two cells pause takes, so the button does not change width as it changes. */
    val play: String,
    val pause: String,
    /** While the audio is being found. */
    val waiting: String,
    val stop: String,
    val next: String,
    val shuffle: String,
    val repeat: String,
    val repeatOne: String,
    /** Add to a playlist: the Toolbar's first button. */
    val add: String,
    /** Toolbar 2's way to the lyrics, on Now playing. */
    val lyrics: String,
    /**
     * Toolbar 2's way to Devices, where Connect is: not the page's own symbol, which in the standard set is
     * shuffle's too, and the two sit side by side there.
     */
    val devices: String,
    val liked: String,
    val like: String,
    /** What the volume is labelled with. */
    val volume: String,
    val sleep: String,
    /** The mark beside the song playing in a list. */
    val playing: String,
    val search: String,
    private val pages: Map<Page, String>,
) {
    fun page(page: Page): String = pages.getValue(page)

    companion object {
        val STANDARD = Glyphs(
            previous = "|◀", play = "▶ ", pause = "▮▮", waiting = "··", stop = "■", next = "▶|",
            shuffle = "⇄", repeat = "↻", repeatOne = "↻1", add = "+", lyrics = "¶", devices = "◎", liked = "♥", like = "♡",
            volume = "vol", sleep = "☾", playing = "▶", search = "⌕",
            pages = Page.entries.associateWith { it.icon },
        )

        val ASCII = Glyphs(
            previous = "|<", play = "> ", pause = "||", waiting = "..", stop = "[]", next = ">|",
            shuffle = "S", repeat = "R", repeatOne = "R1", add = "+", lyrics = "Ly", devices = "@", liked = "<3", like = "<3",
            volume = "vol", sleep = "z", playing = ">", search = "/",
            pages = mapOf(
                Page.HOME to "~", Page.SEARCH to "/", Page.LIBRARY to "#", Page.QUEUE to "=",
                Page.NOW_PLAYING to ">", Page.DOWNLOADS to "v", Page.DEVICES to "@", Page.SETTINGS to "*",
            ),
        )

        /** Font Awesome's, as every Nerd Font has them: fa-step-backward, fa-play, fa-pause, fa-microphone and the rest. */
        val NERD_FONT = Glyphs(
            previous = "", play = " ", pause = " ", waiting = " ", stop = "", next = "",
            shuffle = "", repeat = "", repeatOne = "1", add = "", lyrics = "", devices = "", liked = "", like = "",
            volume = "", sleep = "", playing = "", search = "",
            pages = mapOf(
                Page.HOME to "", Page.SEARCH to "", Page.LIBRARY to "", Page.QUEUE to "",
                Page.NOW_PLAYING to "", Page.DOWNLOADS to "", Page.DEVICES to "", Page.SETTINGS to "",
            ),
        )

        fun of(set: TuiGlyphs): Glyphs = when (set) {
            TuiGlyphs.STANDARD -> STANDARD
            TuiGlyphs.ASCII -> ASCII
            TuiGlyphs.NERD_FONT -> NERD_FONT
        }
    }
}
