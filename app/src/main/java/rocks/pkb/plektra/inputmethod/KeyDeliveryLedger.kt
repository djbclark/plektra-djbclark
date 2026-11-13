package rocks.pkb.plektra.inputmethod

import android.util.LongSparseArray

/**
 * Which physical keys ([HardwareKeyRemapper.physicalKeyId]) the app has seen go down, and in
 * which editor. The IME passes a remapped key to the app itself (through the input connection)
 * when its own handling leaves the key alone, and must keep what the app sees paired: no repeat
 * and no UP of a key whose DOWN the IME consumed, and no UP into an editor that never saw the key
 * go down (a key held from one field into the next).
 *
 * Lifetimes follow the held keys of [HardwareKeyRemapper]: entries survive an editor change
 * (which only starts a new editor generation, [onEditorChanged]) and end with [clear] where the
 * client unbinds, or with [clearDevice].
 */
internal class KeyDeliveryLedger {
    private class Entry(var downEditor: Int, var seenEditor: Int)

    // One entry per key ever pressed, reused: bounded by the keys of the connected keyboards.
    private val entries = LongSparseArray<Entry>()
    private var editor = 0

    /** A new editor has the input connection: nothing was delivered to it yet. */
    fun onEditorChanged() {
        editor++
    }

    /** A new press: whatever the app saw of an earlier one (its UP lost) no longer counts. */
    fun onInitialDown(key: Long) {
        val entry = entries.get(key)
        if (entry == null) {
            entries.put(key, Entry(editor, NOT_SEEN))
        } else {
            entry.downEditor = editor
            entry.seenEditor = NOT_SEEN
        }
    }

    /** The current editor's app received a DOWN (initial or repeat) of this key. */
    fun onDelivered(key: Long) {
        val entry = entries.get(key)
        if (entry == null) entries.put(key, Entry(editor, editor)) else entry.seenEditor = editor
    }

    /**
     * Whether a remapped repeat may go to the app: yes when this editor saw the key go down, and
     * yes in an editor that came after the DOWN (the held key starts repeating into the new
     * field, as an unremapped key does); no when the IME consumed the DOWN in this editor.
     */
    fun mayDeliverRepeat(key: Long): Boolean {
        val entry = entries.get(key) ?: return true
        return entry.seenEditor == editor || entry.downEditor != editor
    }

    /** Ends the press. True when the current editor's app saw this key go down. */
    fun onUp(key: Long): Boolean {
        val entry = entries.get(key) ?: return false
        val seen = entry.seenEditor == editor
        entry.seenEditor = NOT_SEEN
        return seen
    }

    fun clear() {
        entries.clear()
    }

    fun clearDevice(deviceId: Int) {
        for (i in entries.size() - 1 downTo 0) {
            if (HardwareKeyRemapper.deviceIdOf(entries.keyAt(i)) == deviceId) entries.removeAt(i)
        }
    }

    private companion object {
        const val NOT_SEEN = -1
    }
}
