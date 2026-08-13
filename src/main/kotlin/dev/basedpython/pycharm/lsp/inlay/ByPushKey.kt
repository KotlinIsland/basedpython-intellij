package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.util.SystemInfo
import java.awt.event.InputEvent

/**
 * The key you hold to see the hints set to [ByHintMode.ON_PUSH].
 *
 * Modifiers only, and by their *down* masks: a push is a key being held, not a keystroke. That is
 * also why this is not a keymap action — the action system fires on a press and knows nothing about
 * the release, and a shortcut bound here would swallow the keystroke from whatever else wanted it.
 * [ByHintPush] watches the modifier state instead and never consumes an event.
 *
 * [CTRL_ALT] is the default because it is what VS Code uses for the same gesture and because it is
 * the shortest combination that means nothing on its own in an IntelliJ editor. Plain [CTRL] does:
 * held over an identifier it is the platform's own go-to-declaration underline, so hints would
 * appear every time you reached for that.
 *
 * Persisted by [id]; the ids are part of the settings file format and must not change.
 */
enum class ByPushKey(val id: String, private val mask: Int, private val pcLabel: String, private val macLabel: String) {
    CTRL("ctrl", InputEvent.CTRL_DOWN_MASK, "Ctrl", "Control (⌃)"),
    ALT("alt", InputEvent.ALT_DOWN_MASK, "Alt", "Option (⌥)"),
    SHIFT("shift", InputEvent.SHIFT_DOWN_MASK, "Shift", "Shift (⇧)"),
    CTRL_ALT("ctrl-alt", InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK, "Ctrl+Alt", "Control+Option (⌃⌥)"),
    CTRL_SHIFT("ctrl-shift", InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, "Ctrl+Shift", "Control+Shift (⌃⇧)"),
    META("meta", InputEvent.META_DOWN_MASK, "Meta", "Command (⌘)"),
    ;

    /** How the key is named to the user, in the terms their keyboard uses. */
    val display: String get() = if (SystemInfo.isMac) macLabel else pcLabel

    /**
     * Whether this key is down in [modifiersEx], an [InputEvent.getModifiersEx] mask.
     *
     * Every modifier this key names has to be down; others being down as well is not a mismatch.
     * Holding Ctrl+Alt and then pressing Shift to select keeps the hints up, which is the forgiving
     * reading and the one a peek wants.
     */
    fun isHeldIn(modifiersEx: Int): Boolean = modifiersEx and mask == mask

    companion object {
        /** Unknown and blank ids degrade to [CTRL_ALT] rather than throwing. */
        fun fromId(id: String?): ByPushKey = entries.firstOrNull { it.id == id } ?: CTRL_ALT

        /** The modifiers any [ByPushKey] can be made of, and so the only ones worth tracking. */
        val WATCHED_MODIFIERS: Int = entries.fold(0) { all, key -> all or key.mask }
    }
}
