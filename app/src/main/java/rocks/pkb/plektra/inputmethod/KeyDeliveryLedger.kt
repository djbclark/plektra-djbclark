package rocks.pkb.plektra.inputmethod

import android.util.LongSparseArray

/**
 * What the app has seen go down for each physical key ([HardwareKeyRemapper.physicalKeyId]): which
 * key code, by which route, and through which input connection. The IME passes a remapped key to
 * the app itself (through the input connection) when its own handling leaves the key alone, and
 * the system delivers the raw key when the IME returns false; the two routes carry different key
 * codes. A press must go on and end as the key it began with, by the route that can still reach
 * the app:
 *
 * - a key the IME sent ([onSentByIme]) is the IME's to repeat and to release, also when a later
 *   send fails or the handler consumes the UP: the raw key the system would deliver instead is a
 *   different key;
 * - a key the system delivered ([onDeliveredBySystem]) stays the system's, also when the remapped
 *   key could be sent by now;
 * - a key the app never saw go down gets no repeat and no UP.
 *
 * What was delivered counts for one input connection only ([onConnectionChanged]): a key held
 * into the next connection starts there with a repeat, and its UP goes nowhere unless that
 * connection saw it go down. The entries themselves live as long as the held keys of
 * [HardwareKeyRemapper]: until [clear] where the client unbinds, or [clearDevice].
 */
internal class KeyDeliveryLedger {
    private class Entry(var downGeneration: Int) {
        var seenGeneration = NONE
        var seenKeyCode = NONE
        var sentByIme = false

        fun forget() {
            seenGeneration = NONE
            seenKeyCode = NONE
            sentByIme = false
        }
    }

    // One entry per key ever pressed, reused: bounded by the keys of the connected keyboards.
    private val entries = LongSparseArray<Entry>()
    private var generation = 0

    /**
     * Another input connection may have taken over: what the previous one saw no longer counts.
     * Called where the connection can change, whether or not it did (its identity is not
     * observable from the IME).
     */
    fun onConnectionChanged() {
        generation++
    }

    /**
     * A new press. Returns the key code the IME sent down for an earlier press of this key and
     * never released (that press lost its UP), [NONE] otherwise: the caller owes the app its UP.
     */
    fun onInitialDown(key: Long): Int {
        val entry = entries.get(key)
        if (entry == null) {
            entries.put(key, Entry(generation))
            return NONE
        }
        val unfinished = if (entry.seenGeneration == generation && entry.sentByIme) entry.seenKeyCode else NONE
        entry.downGeneration = generation
        entry.forget()
        return unfinished
    }

    /** The IME sent a DOWN (initial or repeat) of this key as [keyCode] through the connection. */
    fun onSentByIme(key: Long, keyCode: Int) {
        val entry = entryFor(key)
        entry.seenGeneration = generation
        entry.seenKeyCode = keyCode
        entry.sentByIme = true
    }

    /**
     * The IME left a DOWN of this key to the system, which delivers the raw [keyCode]. Does not
     * take over a press the IME already sent to this connection.
     */
    fun onDeliveredBySystem(key: Long, keyCode: Int) {
        val entry = entryFor(key)
        if (entry.seenGeneration == generation) return
        entry.seenGeneration = generation
        entry.seenKeyCode = keyCode
        entry.sentByIme = false
    }

    /** The key code the current connection's app saw go down for this key, [NONE] when none. */
    fun deliveredKeyCode(key: Long): Int {
        val entry = entries.get(key) ?: return NONE
        return if (entry.seenGeneration == generation) entry.seenKeyCode else NONE
    }

    /** True when the IME itself sent this key's DOWN to the current connection. */
    fun isSentByIme(key: Long): Boolean {
        val entry = entries.get(key) ?: return false
        return entry.seenGeneration == generation && entry.sentByIme
    }

    /**
     * Whether a remapped repeat may be the first this connection sees of the key: yes for a key
     * that went down before this connection (it starts repeating into the new field, as an
     * unremapped key does), no when the DOWN was consumed or swallowed in this one.
     */
    fun mayStartWithRepeat(key: Long): Boolean {
        val entry = entries.get(key) ?: return true
        return entry.downGeneration != generation
    }

    /** Ends the press: nothing is owed for this key any more. */
    fun onUp(key: Long) {
        entries.get(key)?.forget()
    }

    fun clear() {
        entries.clear()
    }

    fun clearDevice(deviceId: Int) {
        for (i in entries.size() - 1 downTo 0) {
            if (HardwareKeyRemapper.deviceIdOf(entries.keyAt(i)) == deviceId) entries.removeAt(i)
        }
    }

    private fun entryFor(key: Long): Entry =
        entries.get(key) ?: Entry(generation).also { entries.put(key, it) }

    companion object {
        /** No key: a raw key code can be 0 (an unknown key), so 0 is not free. */
        const val NONE = -1
    }
}
