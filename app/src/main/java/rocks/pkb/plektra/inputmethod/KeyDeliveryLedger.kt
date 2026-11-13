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
 * What the IME sent counts for one input connection only ([onConnectionChanged]): a key held
 * into the next connection starts there with a repeat, and its UP goes nowhere unless that
 * connection saw it go down. Where a connection ends, the keys the IME sent down through it are
 * handed back to the caller to be released through it, as far as it still takes them.
 *
 * What the system delivered counts until the key comes up, whatever becomes of the connection.
 * The system hands a key to the app without one, so after a start the app still holds that
 * key and the system can still end it: forgetting it there would make its UP look like the UP
 * of a key the app never saw ([isDeliveredBeforeThisConnection]).
 *
 * Where the client unbinds ([onClientGone]) the connection goes with it: what the IME sent is
 * forgotten there unreleased, there being nobody left to send a release to. What the system
 * delivered is kept there as at a start: the client can bind again under the held key, and the
 * key's UP is then still the system's to deliver. [clearDevice] forgets the keys of a keyboard
 * that is gone.
 *
 * A record is that of a physical key, not of one press: it keeps no down time, and nothing in
 * it tells one press of the key from the next. It ends with the next UP ([onUp]) or initial
 * DOWN ([onInitialDown]) of the key that reaches the caller. So where a key the system
 * delivered loses its UP (the system gave the UP to a window whose keys the caller does not
 * see), its record stays, over starts and unbinds alike, and the next UP of that key is taken
 * for the one it waits for, also when it is the UP of a later press whose DOWN the caller saw
 * nothing of. What becomes of that UP:
 *
 * - a key no remap touched gets it as a held key gets its UP: from the system, or, where a
 *   held remapped key changes its meta state, through the current connection with that meta
 *   state. Without the record the second would be swallowed, as the UP of a key the app never
 *   saw go down; with no remapped key held, the system delivers such an UP in any case;
 * - a raw key that stands in for a remapped one gets it as its own UP would come: after a start
 *   or an unbind from the system, never through the connection.
 *
 * A new press replaces the record before anything else and is handled as a first press.
 *
 * [S] is the caller's record of a DOWN it sent: what it needs to send that key's UP later (the
 * event, and the connection that took it).
 */
internal class KeyDeliveryLedger<S : Any> {
    private class Entry<S>(var downGeneration: Int) {
        var seenGeneration = NONE
        var seenKeyCode = NONE
        var sent: S? = null
        var bySystem = false
        var insteadOfRemapped = false

        fun forget() {
            seenGeneration = NONE
            seenKeyCode = NONE
            sent = null
            bySystem = false
            insteadOfRemapped = false
        }
    }

    // One entry per key ever pressed, reused: bounded by the keys of the connected keyboards.
    private val entries = LongSparseArray<Entry<S>>()
    private var generation = 0

    /**
     * Whether the app still holds what [entry] says it saw go down: a key the IME sent, as long
     * as the connection it went through is the current one; a key the system delivered, until
     * its press ends.
     */
    private fun appHolds(entry: Entry<S>): Boolean = entry.bySystem || entry.seenGeneration == generation

    /**
     * Another input connection may have taken over: what the IME sent through the previous one
     * no longer counts. Called where the connection can change, whether or not it did (the IME
     * gets a new connection object on every start and cannot tell where it leads). Each key the
     * IME sent down through the previous connection and has not released goes to [release]
     * first, once: afterwards nothing is owed for it, whether or not the release arrived. A key
     * the system delivered is left as it is.
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
        entry.bySystem = false
        entry.insteadOfRemapped = false
    }

    /**
     * The IME left a DOWN of this key to the system, which delivers the raw [keyCode]. Does not
     * take over a press the IME already sent to this connection, and changes nothing about one
     * the system has been delivering since an earlier connection (it stays a press of then).
     * [insteadOfRemapped] marks a press whose remapped key the connection did not take, so that
     * the raw key went in its place: see [isDeliveredInsteadOfRemapped].
     */
    fun onDeliveredBySystem(key: Long, keyCode: Int, insteadOfRemapped: Boolean = false) {
        val entry = entryFor(key)
        if (appHolds(entry)) return
        entry.seenGeneration = generation
        entry.seenKeyCode = keyCode
        entry.sent = null
        entry.bySystem = true
        entry.insteadOfRemapped = insteadOfRemapped
    }

    /**
     * The key code the app holds for this key: the one the IME sent to the current connection,
     * or the one the system delivered, under this connection or an earlier one. [NONE] when
     * the app holds none.
     */
    fun deliveredKeyCode(key: Long): Int {
        val entry = entries.get(key) ?: return NONE
        return if (appHolds(entry)) entry.seenKeyCode else NONE
    }

    /** The record of the DOWN the IME itself sent to the current connection, null when none. */
    fun sentByIme(key: Long): S? {
        val entry = entries.get(key) ?: return null
        return if (entry.seenGeneration == generation) entry.sent else null
    }

    /**
     * True when the app holds the raw key of a remapped press, delivered by the system because
     * the remapped key could not be sent. Unlike a key no remap touched, such a press is the
     * caller's to end when it consumes the UP the system would deliver.
     */
    fun isDeliveredInsteadOfRemapped(key: Long): Boolean {
        val entry = entries.get(key) ?: return false
        return appHolds(entry) && entry.insteadOfRemapped
    }

    /**
     * True when the system delivered this key under an earlier connection and the key has not
     * come up since. The current connection saw nothing of the press: where the caller would
     * end it through the connection itself ([isDeliveredInsteadOfRemapped]), it leaves the UP
     * to the system instead.
     */
    fun isDeliveredBeforeThisConnection(key: Long): Boolean {
        val entry = entries.get(key) ?: return false
        return entry.bySystem && entry.seenGeneration != generation
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

    /**
     * The client unbinds, or the service ends: the connection is gone and nothing can be sent
     * through it any more, so what the IME sent is forgotten without a release. A key the
     * system delivered is kept, as at [onConnectionChanged], and counts as held since before
     * whatever connection comes next ([isDeliveredBeforeThisConnection]).
     */
    fun onClientGone() {
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
