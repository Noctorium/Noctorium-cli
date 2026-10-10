package app.noctorium.cli.tui

import app.noctorium.playback.VolumeBoost
import app.noctorium.settings.ProgressBarStyle

/**
 * The volume and the booster, opened from the volume in the player bar or with its key: the terminal's volume
 * popover.
 *
 * The booster goes from 100% (off) to 1000%, ten times the amplitude, always behind the player's limiter, so it
 * gets loud rather than broken. It says when it is past 200% that it is loud, and where the music plays out of its
 * reach -- Spotify, in Spotify's own app -- it says that instead of moving a slider that would do nothing. The level
 * is core's, kept in the settings, so a restart comes back as loud as it was left; which is why the player bar
 * shows it whenever it is on.
 */
class VolumePanel(booster: Boolean = false) : Overlay {
    /** Which row the arrows change: the volume, or the booster. */
    private var onBooster = booster
    private var sliderX = 0
    private var sliderY = 0
    private var sliderW = 0

    override fun draw(tui: Tui, canvas: Canvas, w: Int, h: Int) {
        val p = tui.palette
        val state = tui.state
        val playback = state.playback.value
        val level = VolumeBoost.clamp(state.settings.value.preferences.volumeBoostPercent)
        val available = playback.volumeBoostAvailable
        val bg = surface(p)
        val ink = if (p.skinned) Skin.text(p) else p.text
        val quiet = if (p.skinned) Skin.greyText(p) else p.subtext
        val warning = VolumeBoost.isLoud(level) && available
        panel(tui, canvas, w, h, 80, 11, "Volume") { x, y, pw, _ ->
            fun marker(row: Int, chosen: Boolean) {
                if (p.skinned) {
                    if (chosen) canvas.fill(x, row, 11, 1, Skin.selection(p))
                    canvas.write(x + 1, row, if (row == y) "Volume" else "Booster", if (chosen) Skin.selectionText(p) else ink, if (chosen) Skin.selection(p) else bg, BOLD)
                } else {
                    canvas.write(x, row, if (chosen) "›" else " ", p.accent, bg, BOLD)
                    canvas.write(x + 2, row, if (row == y) "Volume" else "Booster", if (chosen) p.text else p.subtext, bg, BOLD)
                }
            }

            // The volume: the same eight bars as the player bar, and the percentage.
            marker(y, !onBooster)
            val volume = if (playback.isMuted) 0f else playback.volume
            val levels = "▁▂▃▄▅▆▇█"
            val filled = (volume * 8).toInt()
            for (i in 0 until 8) canvas.set(x + 13 + i, y, levels[i].toString(), if (i < filled) p.accent else if (p.skinned) Skin.greyText(p) else p.line, bg)
            canvas.write(x + 23, y, if (playback.isMuted) "muted" else "${(playback.volume * 100).toInt()}%", quiet, bg)
            tui.clickTargets += Tui.ClickTarget(x + 13, y, 8, 1) { column -> state.setVolume(((column + 1) / 8f).coerceIn(0f, 1f)); onBooster = false }

            // The booster: its level and the times it multiplies by, a slider from 100% to 1000%, or why there is none.
            val row = y + 2
            marker(row, onBooster)
            if (!available) {
                canvas.write(x + 13, row, "Not available here", p.warn, bg, BOLD)
                wrapWords(VolumeBoost.UNAVAILABLE, pw - 13).take(2).forEachIndexed { i, line -> canvas.write(x + 13, row + 1 + i, line, quiet, bg, ITALIC, max = pw - 13) }
            } else {
                val on = VolumeBoost.isOn(level)
                val value = if (on) "${VolumeBoost.percentLabel(level)} ${VolumeBoost.timesLabel(level)}" else "Off"
                val shownValue = if (onBooster) "‹ $value ›" else value
                val used = canvas.write(x + 13, row, shownValue, if (warning) p.warn else if (on) p.accent else quiet, bg, BOLD)
                sliderX = x + 13 + maxOf(used, 15) + 2
                sliderY = row
                sliderW = (x + pw - sliderX).coerceAtLeast(6)
                val fraction = (level - VolumeBoost.OFF_PERCENT).toFloat() / (VolumeBoost.MAX_PERCENT - VolumeBoost.OFF_PERCENT)
                SeekBars.draw(canvas, p, ProgressBarStyle.SLIDER, sliderX, row, sliderW, fraction, false, bg)
                tui.clickTargets += Tui.ClickTarget(sliderX, row, sliderW, 1) { column ->
                    onBooster = true
                    state.setVolumeBoostPercent(VolumeBoost.snap(VolumeBoost.OFF_PERCENT + (VolumeBoost.MAX_PERCENT - VolumeBoost.OFF_PERCENT) * column / (sliderW - 1f)))
                }
                canvas.write(sliderX, row + 1, "100%", quiet, bg)
                canvas.writeRight(sliderX + sliderW, row + 1, "1000%", quiet, bg)
                if (warning) canvas.write(x + 13, row + 2, VolumeBoost.LOUD_WARNING, p.warn, bg, BOLD, max = pw - 13)
                else canvas.write(x + 13, row + 2, "Always behind a limiter: louder, never broken.", quiet, bg, ITALIC, max = pw - 13)
            }
            val k = tui.keys
            val hint = if (p.skinned) Skin.greyText(p) else p.faint
            canvas.write(x, y + 6, "↑ ↓ choose · ← → change it, with Shift by 100% · Space or Enter: on or off", hint, bg, max = pw)
            canvas.write(x, y + 7, "1 to 9 and 0 set the booster to 100% to 1000% · ${k.name(KeyAction.MUTE)} mutes · Esc closes", hint, bg, max = pw)
        }
    }

    override fun handle(tui: Tui, input: Input): Boolean {
        val state = tui.state
        val available = state.playback.value.volumeBoostAvailable
        val level = VolumeBoost.clamp(state.settings.value.preferences.volumeBoostPercent)
        fun boost(by: Int) {
            if (!available) return tui.toast(VolumeBoost.UNAVAILABLE, Row.Tone.WARN)
            state.setVolumeBoostPercent(VolumeBoost.clamp(level + by))
        }
        when (input) {
            is Input.Key -> when (input.key) {
                Keys.UP, Keys.DOWN -> onBooster = !onBooster
                Keys.LEFT, Keys.RIGHT -> {
                    val sign = if (input.key == Keys.RIGHT) 1 else -1
                    if (onBooster) boost(sign * if (input.shift || input.ctrl) 100 else VolumeBoost.STEP_PERCENT)
                    else state.setVolume((state.playback.value.volume + sign * .05f).coerceIn(0f, 1f))
                }
                Keys.ENTER -> if (onBooster) toggle(tui) else state.toggleMute()
                Keys.ESCAPE, Keys.BACKSPACE -> close(tui)
                else -> Unit
            }
            is Input.Text -> when {
                input.char == " " -> if (onBooster) toggle(tui) else state.toggleMute()
                input.char.length == 1 && input.char[0].isDigit() -> {
                    onBooster = true
                    val digit = input.char.toInt()
                    boost((if (digit == 0) 1000 else digit * 100) - level)
                }
                tui.keys.matches(input.char, KeyAction.MUTE) -> state.toggleMute()
                tui.keys.matches(input.char, KeyAction.VOLUME_UP) -> state.setVolume((state.playback.value.volume + .05f).coerceAtMost(1f))
                tui.keys.matches(input.char, KeyAction.VOLUME_DOWN) -> state.setVolume((state.playback.value.volume - .05f).coerceAtLeast(0f))
                tui.keys.matches(input.char, KeyAction.BOOSTER) -> toggle(tui)
                input.char == "q" -> close(tui)
                else -> Unit
            }
            else -> Unit
        }
        return true
    }

    /** The booster's own switch: off, or back to the last level it had -- 200% the first time. */
    private fun toggle(tui: Tui) {
        if (!tui.state.playback.value.volumeBoostAvailable) return tui.toast(VolumeBoost.UNAVAILABLE, Row.Tone.WARN)
        onBooster = true
        tui.state.toggleVolumeBoost()
    }

    override fun mouse(tui: Tui, input: Input.Mouse): Boolean {
        when (input.kind) {
            MouseKind.WHEEL_UP, MouseKind.WHEEL_DOWN -> handle(tui, Input.Key(if (input.kind == MouseKind.WHEEL_UP) Keys.RIGHT else Keys.LEFT))
            MouseKind.PRESS -> close(tui)
            else -> Unit
        }
        return true
    }
}
