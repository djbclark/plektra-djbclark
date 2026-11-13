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
 *   key could be sent by now; when it stands in for a remapped key and the IME consumes its UP,
 *   the IME ends it ([isDeliveredInsteadOfRemapped]);
 * - a key the app never saw go down gets no repeat and no UP.
 *
 * What was delivered counts for one input connection only ([onConnectionChanged]): a key held
 * into the next connection starts there with a repeat, and its UP goes nowhere unless that
 * connection saw it go down. Where a connection ends, the keys the IME sent down through it are
 * handed back to the caller to be released through it, as far as it still takes them. The
 * entries themselves live as long as the held keys of [HardwareKeyRemapper]: until [clear]
 * where the client unbinds, or [clearDevice].
 *
 * [S] is the caller's record of a DOWN it sent: what it needs to send that key's UP later (the
 * event, and the connection that took it).
 */
internal class KeyDeliveryLedger<S : Any> {
    private class Entry<S>(var downGeneration: Int) {
        var seenGeneration = NONE
        var seenKeyCode = NONE
        var sent: S? = null
        var insteadOfRemapped = false

        fun forget() {
            seenGeneration = NONE
            seenKeyCode = NONE
            sent = null
            insteadOfRemapped = false
        }
    }

    // One entry per key ever pressed, reused: bounded by the keys of the connected keyboards.
    private val entries = LongSparseArray<Entry<S>>()
    private var generation = 0

    /**
     * Another input connection may have taken over: what the previous one saw no longer counts.
     * Called where the connection can change, whether or not it did (the IME gets a new
     * connection object on every start and cannot tell where it leads). Each key the IME sent
     * down through the previous connection and has not released goes to [release] first, once:
     * afterwards nothing is owed for it, whether or not the release arrived.
     */
    fun onConnectionChanged(release: (sent: S) -> Unit) {
        for (i in 0 until entries.size()) {
            val entry = entries.valueAt(i)
            val sent = entry.sent
            // The record goes either way: one of an older connection must not keep it alive.
            entry.sent = null
            if (sent != null && entry.seenGeneration == generation) release(sent)
        }
        generation++
    }

    /**
     * A new press. Returns the record of the DOWN the IME sent for an earlier press of this key
     * and never released (that press lost its UP), null otherwise: the caller owes the app its
     * UP.
     */
    fun onInitialDown(key: Long): S? {
        val entry = entries.get(key)
        if (entry == null) {
            entries.put(key, Entry(generation))
            return null
        }
        val unfinished = if (entry.seenGeneration == generation) entry.sent else null
        entry.downGeneration = generation
        entry.forget()
        return unfinished
    }

    /**
     * The IME sent a DOWN (initial or repeat) of this key as [keyCode] through the connection;
     * [sent] is what it takes to release it.
     */
    fun onSentByIme(key: Long, keyCode: Int, sent: S) {
        val entry = entryFor(key)
        entry.seenGeneration = generation
        entry.seenKeyCode = keyCode
        entry.sent = sent
        entry.insteadOfRemapped = false
    }

    /**
     * The IME left a DOWN of this key to the system, which delivers the raw [keyCode]. Does not
     * take over a press the IME already sent to this connection. [insteadOfRemapped] marks a
     * press whose remapped key the connection did not take, so that the raw key went in its
     * place: see [isDeliveredInsteadOfRemapped].
     */
    fun onDeliveredBySystem(key: Long, keyCode: Int, insteadOfRemapped: Boolean = false) {
        val entry = entryFor(key)
        if (entry.seenGeneration == generation) return
        entry.seenGeneration = generation
        entry.seenKeyCode = keyCode
        entry.sent = null
        entry.insteadOfRemapped = insteadOfRemapped
    }

    /** The key code the current connection's app saw go down for this key, [NONE] when none. */
    fun deliveredKeyCode(key: Long): Int {
        val entry = entries.get(key) ?: return NONE
        return if (entry.seenGeneration == generation) entry.seenKeyCode else NONE
    }

    /** The record of the DOWN the IME itself sent to the current connection, null when none. */
    fun sentByIme(key: Long): S? {
        val entry = entries.get(key) ?: return null
        return if (entry.seenGeneration == generation) entry.sent else null
    }

    /**
     * True when the current connection's app holds the raw key of a remapped press, delivered by
     * the system because the remapped key could not be sent. Unlike a key no remap touched, such
     * a press is the caller's to end when it consumes the UP the system would deliver.
     */
    fun isDeliveredInsteadOfRemapped(key: Long): Boolean {
        val entry = entries.get(key) ?: return false
        return entry.seenGeneration == generation && entry.insteadOfRemapped
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

    private fun entryFor(key: Long): Entry<S> =
        entries.get(key) ?: Entry<S>(generation).also { entries.put(key, it) }

    companion object {
        /** No key: a raw key code can be 0 (an unknown key), so 0 is not free. */
        const val NONE = -1
    }
}
