package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent

/*
 * Hardware key remapping: design note
 * ===================================
 *
 * Rule model
 * ----------
 * A rule ([KeyRemapRule]) turns one physical key of one keyboard profile into another key. Its
 * source ([KeySource]) names the key by scan code ([KeyMatch.SCAN]) or by key code
 * ([KeyMatch.KEY]); its target is a key code. Scan codes name the key itself, so they are right
 * where a vendor setting rewrites the key code before the IME sees it (the Titan 2's Shortcut
 * keys > Key setting turns right Shift into Alt; scan code 54 stays) or where two keys send one
 * key code. A profile's rules are compiled once into an immutable [KeyRemapRules]; the rules of
 * all profiles form a [KeyRemapTable], stored as one versioned JSON preference ([KeyRemapJson]).
 * Presets (named rule lists with a title) live in the settings layer and only write rules.
 *
 * Rules apply to the built-in keyboard of the profile they are stored under, and to nothing
 * else: not to an accessory keyboard, not to an event without a device (deviceId < 0: injected
 * by adb, scrcpy or another app) and not to an on-screen key (FLAG_SOFT_KEYBOARD). Those events
 * skip the remapper altogether ([HardwareKeyRemapper.isPhysicalKeyEvent]); an accessory's events
 * pass through it with no rules, because the framework reports modifier state for all keyboards
 * together. A rule is applied once: its output is never matched against the rules again. Lock
 * keys (Caps, Num, Scroll Lock) are neither sources nor targets.
 *
 * Ordering
 * --------
 * raw event -> filters that need raw codes (accidental keys; a filter for synthetic vendor
 * events belongs here) -> device normalization ([DeviceSpecific.remapHardwareKeyEvent]) -> user
 * remap, once ([HardwareKeyRemapper.remap]) -> Clicks button mapper -> layout lookup and the
 * rest of the IME. Everything after the remap sees the remapped key and meta state only.
 *
 * Held keys
 * ---------
 * The framework computes KeyEvent.metaState from the physical keys, so after a remap it is wrong
 * for modifiers: a held key that now acts as a modifier is missing from it, and a held modifier
 * that now acts as another key is still in it. The remapper therefore keeps one hold per pressed
 * remapped key, from its initial DOWN to its UP, and rebuilds the meta state of every physical
 * event from the holds. A hold is identified by the physical key: device id plus scan code, or
 * plus key code for an event without a scan code. It keeps the target chosen on the DOWN, so
 * repeats and the UP stay consistent when the rules change mid-press. Unremapped modifier keys
 * are tracked the same way (as holds onto themselves), so that a second key sending the same
 * modifier keeps its meta bit while a remapped one is held.
 *
 * Lifecycle (what the owner calls, and what a hold does)
 *
 *   onStartInput (restarting or not)       nothing    hold survives
 *   onFinishInput                          nothing    hold survives
 *   onFinishInputView, onWindowHidden      nothing    hold survives
 *   onUnbindInput, onDestroy               reset()    hold becomes a tombstone
 *   keyboard removed or changed            clearDevice()  holds and tombstones dropped
 *   UP consumed before remap()             release()  hold or tombstone dropped
 *   initial DOWN over an existing hold     -          hold replaced, rule looked up again
 *   event whose meta state lacks the bit   -          hold becomes a tombstone (lost UP)
 *   of a held modifier source
 *
 * Holds survive an editor change because key events keep flowing to the IME while its client
 * stays bound: a held Enter-as-Shift keeps shifting in the next field, as a real Shift does. They
 * end where the event stream ends. A tombstone is a key that is still physically down but no
 * longer acts: its repeats and its UP are reported as [KeyRemapResult.Change.STALE] for the owner
 * to swallow, it adds no target modifier, and its own modifier bit stays removed from the meta
 * state (the key did not act as that modifier when it went down, and must not start to). A
 * tombstone ends with its UP, with the next initial DOWN of the key or with clearDevice().
 *
 * The lost-UP rule uses the framework meta state as the truth about modifier keys: when a later
 * event no longer carries the bit a held modifier source set on its own DOWN, the key was
 * released and its UP never arrived. A source that is not a modifier (Enter as Shift) has no such
 * signal; its lost UP is repaired by pressing the key again.
 *
 * An owner with pressed state of its own (the IME service: Shift, Ctrl, Alt and Sym pressed
 * flags, with tap, one-shot and latch state beside them) resets that state on an editor change,
 * where a hold goes on. The two are kept apart: what is held is read from the holds
 * ([HardwareKeyRemapper.forEachHeldTarget]) and set again after such a reset, as a key that is
 * down and has been used, so that its release is a plain release (no tap, no one-shot, no
 * latch); the tap and latch state stays reset.
 *
 * What the app sees
 * -----------------
 * The remapper only transforms events. An owner that passes remapped keys on (the IME service,
 * through the input connection) keeps its own record of what the app saw go down, per physical
 * key and per connection ([KeyDeliveryLedger]): a press ends with the key it began with, or not
 * at all. For a key the IME sent, that record ends with the connection, not with the hold: a
 * held key survives a new editor, what the previous one saw of it does not. (A key the system
 * delivered is another matter: see the end of this note.)
 *
 * Every onStartInput counts as a new connection, a restarting one included. The framework hands
 * the IME a new connection object on each start and no way to tell whether it reaches the view
 * the previous one did ("restarting" means the client already had an editor, which need not be
 * the same view). The choice is between an UP that may land in a view that never saw the DOWN
 * and an UP that is withheld from one that did. The second is taken, and softened as far as the
 * framework allows: where a connection ends (a start, or a finish), each key the IME sent down
 * through it is first released through it as a cancelled press (FLAG_CANCELED, with the DOWN's
 * own down time, device and scan code), and then forgotten. The key itself stays held and
 * reaches the next connection with its next repeat; its UP goes there only after that.
 *
 * The limit of that release: it is an attempt, and the IME is not told whether it arrived. On
 * the app's side the framework stops a connection the moment the app makes another one, before
 * the IME hears of the start, and drops what is sent through it afterwards. So the release
 * arrives where the connection outlived the start (one the IME or the system made over the
 * connection the app already had), and not where the app replaced it, whether for another view
 * or for the same one (InputMethodManager.restartInput, which a TextView calls after setText).
 * There the view keeps a press it saw begin and not end, until the key's next repeat brings it
 * back or it is pressed again.
 *
 * The other route is the new connection: a key event sent through it goes to whatever view has
 * focus in its window, which after a view restarted its own input is the view that saw the
 * DOWN, and after focus moved is one that did not. Nothing the IME can observe proves which.
 * One case is let through that route all the same, because a modifier the app believes held
 * changes what every later key does there: a held Shift, Ctrl or Alt (left or right) that the
 * IME sent down is released through the new connection instead of the old one, never through
 * both, when all of this holds:
 *
 *   - the start is a restarting one (a finish, and a start that is not restarting, mean the
 *     editor is gone or new);
 *   - the editor the key went down in and the editor of this start name the same package, and
 *     it is not empty;
 *   - they name the same field id, and it is positive (a view without an id reports none);
 *   - the start has a connection.
 *
 * The package and field id are copied when the key goes down: by the time a start is reported,
 * the framework has already replaced the editor it reports as current.
 *
 * Equal ids are a filter, not identity. Two views can share an id (rows of a list, fields
 * inside one web view, which reports the id of the web view for all of them), so the release
 * can still land in a view that saw no DOWN; a modifier's UP is the key event least likely to
 * do anything there. Where the ids are missing or differ, nothing is redirected and the limit
 * above stands: a view without an id that restarts its input keeps the modifier down until the
 * key repeats or is pressed again.
 *
 * Every other key keeps the old route. Enter and other keys that act: a view can act on an UP
 * alone, cancelled or not (a single-line TextView moves focus on an Enter UP without looking
 * at the cancel flag or for the DOWN), so an UP in the wrong view costs more than a missing
 * one. Sym: it is the IME's own modifier, whose held state the IME ends itself, and nothing
 * in the framework says that a Sym UP without its DOWN is harmless to an app. A raw key the
 * system delivered is not the IME's to release at all.
 *
 * A raw key the system delivered in place of a remapped one that could not be sent is the
 * system's to continue and to end, with one exception: when the IME consumes its UP (as the
 * remapped key, which the app never saw), the IME ends it, as a cancelled UP of the raw key
 * through the connection, or by letting the system deliver the raw UP when the connection still
 * takes nothing. A key no remap touched keeps the IME's handling as it is.
 *
 * What the system delivered does not end with the connection. The system hands a key to the app
 * without one, so a start or a finish under a held key changes nothing about it: the app still
 * holds the key, the record of it is kept until the key comes up, and its repeats and its UP
 * stay the system's (the IME returns false for them, as within one connection). Forgetting the
 * record at a start would make the IME take the UP for that of a key the app never saw, and
 * consume it: the system would never deliver it. The exception above is narrower after a
 * start. The connection of that start saw nothing of the press, so the IME sends no cancelled
 * UP into it, and leaves to the system also an UP it consumed. That UP is a plain one, as the
 * key's own would be without any remap. None of this is the redirected release of a held
 * Shift, Ctrl or Alt: that one is for keys the IME sent.
 *
 * Nor does it end where the client unbinds. What the IME sent ends there unreleased, and the
 * hold of the remapped key becomes a tombstone. But the app still holds the raw key the system
 * gave it in that key's place, and the client can bind again while the key is down, so that
 * its UP reaches the IME after all. The record of it is therefore kept over the unbind, and
 * the owner leaves to the system the UP its tombstone reports as stale: swallowed with the
 * rest of the press, it would leave the key down in the app. The key itself acts no more, and
 * its repeats still end at the tombstone; nothing is sent through any connection for it.
 *
 * A record kept that long can outlive its press. It is the record of a physical key, with
 * nothing in it that names the press, and it ends only with an UP or an initial DOWN of the
 * key that reaches the owner. When the key's UP goes to a window whose keys the owner does not
 * see, the next UP of the key is taken for it, also the UP of a later press the owner saw no
 * DOWN of. A key no remap touched then gets that UP as a held key would: from the system, or,
 * under a held remapped modifier, through the current connection with the rebuilt meta state
 * (one key event the app saw no DOWN for, where the UP of a key the app never saw go down is
 * otherwise swallowed). A raw key standing in for a remapped one gets it from the system, as
 * after any start or unbind, and never through the connection. A new press of the key
 * replaces the record and is handled as a first press.
 */

/** How a [KeySource] names its physical key. */
enum class KeyMatch { KEY, SCAN }

/**
 * The physical key a rule remaps. [KeyMatch.SCAN] matches [scanCode], the scan code of the raw
 * event (device normalization may rewrite the event's own); [KeyMatch.KEY] matches [keyCode], the
 * key code after device normalization. Both codes are stored where known, so a rule captured
 * from a key press can be shown by name and switched between the two.
 */
data class KeySource(val match: KeyMatch, val keyCode: Int, val scanCode: Int = 0) {
    companion object {
        fun key(keyCode: Int, scanCode: Int = 0) = KeySource(KeyMatch.KEY, keyCode, scanCode)
        fun scan(scanCode: Int, keyCode: Int = KeyEvent.KEYCODE_UNKNOWN) =
            KeySource(KeyMatch.SCAN, keyCode, scanCode)
    }
}

/** One-way: the key [from] acts as key code [toKeyCode]. A swap is two rules. */
data class KeyRemapRule(val from: KeySource, val toKeyCode: Int)

/** The rules of one keyboard profile, validated and compiled for lookup. Immutable. */
class KeyRemapRules private constructor(val rules: List<KeyRemapRule>) {
    private val scanCodes = IntArray(rules.count { it.from.match == KeyMatch.SCAN })
    private val scanTargets = IntArray(scanCodes.size)
    private val keyCodes = IntArray(rules.size - scanCodes.size)
    private val keyTargets = IntArray(keyCodes.size)

    init {
        var scan = 0
        var key = 0
        for (rule in rules) {
            if (rule.from.match == KeyMatch.SCAN) {
                scanCodes[scan] = rule.from.scanCode
                scanTargets[scan++] = rule.toKeyCode
            } else {
                keyCodes[key] = rule.from.keyCode
                keyTargets[key++] = rule.toKeyCode
            }
        }
    }

    val isEmpty: Boolean get() = rules.isEmpty()

    /**
     * The key code this key acts as: a scan-code rule wins over a key-code rule; else [keyCode].
     * A lock key stays itself whatever a scan-code rule says: the framework would go on toggling
     * its lock state.
     */
    fun targetFor(keyCode: Int, scanCode: Int): Int {
        if (isLockKey(keyCode)) return keyCode
        if (scanCode != 0) {
            for (i in scanCodes.indices) if (scanCodes[i] == scanCode) return scanTargets[i]
        }
        for (i in keyCodes.indices) if (keyCodes[i] == keyCode) return keyTargets[i]
        return keyCode
    }

    companion object {
        /** More rules than a keyboard has keys are a corrupt store; the rest is ignored. */
        const val MAX_RULES = 128

        val NONE = KeyRemapRules(emptyList())

        /** Keeps the valid rules, in order; of two rules for one source the first wins. */
        fun compile(rules: List<KeyRemapRule>): KeyRemapRules {
            val kept = ArrayList<KeyRemapRule>()
            for (rule in rules) {
                if (kept.size == MAX_RULES) break
                if (isValid(rule) && kept.none { sameSource(it.from, rule.from) }) kept.add(rule)
            }
            return if (kept.isEmpty()) NONE else KeyRemapRules(kept)
        }

        fun isValid(rule: KeyRemapRule): Boolean {
            val from = rule.from
            val sourceNamed = when (from.match) {
                KeyMatch.SCAN -> from.scanCode > 0
                KeyMatch.KEY -> isRemappableKeyCode(from.keyCode)
            }
            return sourceNamed && isRemappableKeyCode(rule.toKeyCode) &&
                !isLockKey(from.keyCode) && from.keyCode != rule.toKeyCode
        }

        /** True when both name the same physical key the same way. */
        fun sameSource(a: KeySource, b: KeySource): Boolean = a.match == b.match &&
            if (a.match == KeyMatch.SCAN) a.scanCode == b.scanCode else a.keyCode == b.keyCode

        private fun isRemappableKeyCode(keyCode: Int): Boolean =
            keyCode > KeyEvent.KEYCODE_UNKNOWN && keyCode <= KeyEvent.getMaxKeyCode() && !isLockKey(keyCode)

        private fun isLockKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_CAPS_LOCK ||
            keyCode == KeyEvent.KEYCODE_NUM_LOCK || keyCode == KeyEvent.KEYCODE_SCROLL_LOCK
    }
}

/** The compiled rules of every keyboard profile that has any. Immutable. */
class KeyRemapTable(private val byProfile: Map<String, KeyRemapRules>) {
    val isEmpty: Boolean get() = byProfile.isEmpty()

    /** The rules stored under [profileId], valid ones only. */
    fun rules(profileId: String): List<KeyRemapRule> =
        byProfile[KeyRemapJson.profileKey(profileId)]?.rules ?: emptyList()

    /** The rules for events of the keyboard [profile] resolves to: built-in keyboards only. */
    fun rulesFor(profile: DeviceSpecific.ResolvedInputProfile): KeyRemapRules {
        if (profile.kind != DeviceSpecific.InputDeviceKind.BUILT_IN) return KeyRemapRules.NONE
        return byProfile[KeyRemapJson.profileKey(profile.profileId)] ?: KeyRemapRules.NONE
    }

    companion object {
        val EMPTY = KeyRemapTable(emptyMap())
    }
}

/**
 * What [HardwareKeyRemapper.remap] made of one event. [event] is the event to handle in place of
 * the one passed in; it is the same instance when [change] is [Change.NONE] or [Change.STALE].
 */
class KeyRemapResult(val keyCode: Int, val event: KeyEvent, val change: Change) {
    enum class Change {
        /** Untouched. */
        NONE,
        /** Same key, meta state rebuilt for the held remapped keys. */
        META,
        /** Another key code (and the meta state that goes with it). */
        KEY,
        /**
         * A repeat or the UP of a tombstoned key: the owner swallows it, except the UP of a raw
         * key the app holds from the system (the end of the design note).
         */
        STALE
    }

    val isStale: Boolean get() = change == Change.STALE
    val isChanged: Boolean get() = change == Change.META || change == Change.KEY
}

/**
 * Applies a profile's [KeyRemapRules] to the physical key events of one consumer (the IME
 * service, or an activity that shows keys as the IME delivers them) and rebuilds their meta
 * state; see the design note at the top of this file for the held-key rules and what the owner
 * must call when. Stateful, main thread only, free of Android context: the caller resolves the
 * rules of each event and passes them in.
 *
 * [onHoldCancelled] is called with the physical key and its target key code when a remapped
 * key's hold ends without its UP passing through [remap] (reset, device removal, lost UP, a new
 * DOWN over it), so an owner that keeps its own pressed state for the target key, or that told
 * someone else the target key is down, can end that too.
 */
class HardwareKeyRemapper(
    private val onHoldCancelled: ((physicalKey: Long, targetKeyCode: Int) -> Unit)? = null
) {
    private class Hold(
        val key: Long,
        /** The key code that arrived: it names the meta bit the framework sets for this key. */
        val sourceKeyCode: Int,
        /** What the key acts as; [sourceKeyCode] itself for an unremapped modifier. */
        val targetKeyCode: Int,
        /** The side bit this modifier key set on its own DOWN, 0 when it set none. */
        val ownMeta: Int,
        val downEventTime: Long
    ) {
        val remapped: Boolean get() = targetKeyCode != sourceKeyCode
    }

    private val held = ArrayList<Hold>()

    /** Remapped keys cut off mid-hold and still physically down. */
    private val stale = ArrayList<Hold>()

    /** True while no remapped key is held or tombstoned: with no rules either, [remap] is identity. */
    val isIdle: Boolean
        get() {
            if (stale.isNotEmpty()) return false
            for (i in held.indices) if (held[i].remapped) return false
            return true
        }

    /**
     * [keyCode] and [event] are the key after device normalization; [key] names the physical key
     * ([physicalKeyId]) and comes from the raw event where normalization rewrites codes.
     */
    fun remap(
        keyCode: Int,
        event: KeyEvent,
        rules: KeyRemapRules,
        key: Long = physicalKeyId(keyCode, event)
    ): KeyRemapResult {
        val action = event.action
        val initialDown = action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        tombstoneLostReleases(event, key)

        val staleIndex = indexOf(stale, key)
        if (staleIndex >= 0) {
            if (initialDown) {
                // A new press supersedes a tombstone whose UP was lost.
                stale.removeAt(staleIndex)
            } else if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
                if (action == KeyEvent.ACTION_UP) stale.removeAt(staleIndex)
                return KeyRemapResult(keyCode, event, KeyRemapResult.Change.STALE)
            }
        }

        val heldIndex = indexOf(held, key)
        var targetKeyCode = keyCode
        if (initialDown) {
            // A hold still there lost its UP: this press starts over with today's rules.
            if (heldIndex >= 0) cancelled(held.removeAt(heldIndex))
            targetKeyCode = rules.targetFor(keyCode, scanCodeOf(key))
            val sourceBits = modifierBits(keyCode)
            if (targetKeyCode != keyCode || sourceBits != null) {
                val ownMeta = (sourceBits?.sideMeta ?: 0) and event.metaState
                held.add(Hold(key, keyCode, targetKeyCode, ownMeta, event.eventTime))
            }
        } else if (heldIndex >= 0) {
            // Repeats and the UP keep what the DOWN chose; without a hold the DOWN was not
            // remapped (or never seen) and the event stays as it is.
            targetKeyCode = held[heldIndex].targetKeyCode
            if (action == KeyEvent.ACTION_UP) held.removeAt(heldIndex)
        }

        val metaState = rebuildMetaState(event.metaState)
        if (targetKeyCode == keyCode && metaState == event.metaState) {
            return KeyRemapResult(keyCode, event, KeyRemapResult.Change.NONE)
        }
        val rebuilt = KeyEvent(
            event.downTime,
            event.eventTime,
            action,
            targetKeyCode,
            event.repeatCount,
            metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source
        )
        val change = if (targetKeyCode != keyCode) KeyRemapResult.Change.KEY else KeyRemapResult.Change.META
        return KeyRemapResult(targetKeyCode, rebuilt, change)
    }

    /**
     * The key code [remap] would produce for this event, without changing any state: for callers
     * that must classify a physical key before it is remapped (key filters on raw events).
     */
    fun peekKeyCode(
        keyCode: Int,
        event: KeyEvent,
        rules: KeyRemapRules,
        key: Long = physicalKeyId(keyCode, event)
    ): Int {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            return rules.targetFor(keyCode, scanCodeOf(key))
        }
        val heldIndex = indexOf(held, key)
        if (heldIndex >= 0) return held[heldIndex].targetKeyCode
        val staleIndex = indexOf(stale, key)
        return if (staleIndex >= 0) stale[staleIndex].targetKeyCode else keyCode
    }

    /**
     * Ends every remapped hold as a tombstone: the owner stops receiving key events (its client
     * unbinds, it loses focus) while keys may still be down. True when a hold was cut off.
     */
    fun reset(): Boolean {
        var cutOff = false
        var i = held.size - 1
        while (i >= 0) {
            if (held[i].remapped) {
                tombstone(held.removeAt(i))
                cutOff = true
            }
            i--
        }
        return cutOff
    }

    /**
     * Calls [action] with the target key code of each remapped key that is held and still acts
     * (no tombstone). The holds are the one record of held remapped keys that outlives an editor
     * change, so an owner whose own pressed state is reset there takes it from here again.
     */
    fun forEachHeldTarget(action: (targetKeyCode: Int) -> Unit) {
        for (i in held.indices) if (held[i].remapped) action(held[i].targetKeyCode)
    }

    /**
     * The key code [key] is held as, [KeyEvent.KEYCODE_UNKNOWN] when it is not a held remapped
     * key that still acts.
     */
    fun heldTargetOf(key: Long): Int {
        val index = indexOf(held, key)
        return if (index >= 0 && held[index].remapped) held[index].targetKeyCode else KeyEvent.KEYCODE_UNKNOWN
    }

    /** Forgets the keys of one keyboard (disconnected or reconfigured). True when one was held. */
    fun clearDevice(deviceId: Int): Boolean {
        var cutOff = false
        var i = held.size - 1
        while (i >= 0) {
            if (deviceIdOf(held[i].key) == deviceId) {
                val hold = held.removeAt(i)
                if (hold.remapped) {
                    cancelled(hold)
                    cutOff = true
                }
            }
            i--
        }
        stale.removeAll { deviceIdOf(it.key) == deviceId }
        return cutOff
    }

    /**
     * Bookkeeping for a release the owner consumes before it reaches [remap]: drops the hold or
     * tombstone of this physical key, if any, and transforms nothing.
     */
    fun release(key: Long) {
        val heldIndex = indexOf(held, key)
        if (heldIndex >= 0) held.removeAt(heldIndex)
        val staleIndex = indexOf(stale, key)
        if (staleIndex >= 0) stale.removeAt(staleIndex)
    }

    /**
     * The lost-UP rule: a held modifier key set its side bit on its own DOWN, so a later event of
     * another key that no longer carries the bit means the key is up. Its hold ends; a remapped
     * one becomes a tombstone, so that a wrong guess can swallow the key's late events but never
     * let them through raw. An event older than the hold (a replayed one) says nothing.
     */
    private fun tombstoneLostReleases(event: KeyEvent, key: Long) {
        var i = held.size - 1
        while (i >= 0) {
            val hold = held[i]
            if (hold.ownMeta != 0 && hold.key != key && event.eventTime > hold.downEventTime &&
                (event.metaState and hold.ownMeta) == 0
            ) {
                held.removeAt(i)
                if (hold.remapped) tombstone(hold)
            }
            i--
        }
    }

    private fun tombstone(hold: Hold) {
        val staleIndex = indexOf(stale, hold.key)
        if (staleIndex >= 0) stale.removeAt(staleIndex)
        stale.add(hold)
        cancelled(hold)
    }

    private fun cancelled(hold: Hold) {
        if (hold.remapped) onHoldCancelled?.invoke(hold.key, hold.targetKeyCode)
    }

    private fun indexOf(holds: ArrayList<Hold>, key: Long): Int {
        for (i in holds.indices) if (holds[i].key == key) return i
        return -1
    }

    /**
     * Removes the side bit of every remapped or tombstoned modifier source (it does not act as
     * that modifier), unless an unremapped key holds the same bit; a generic bit goes with the
     * last of its side bits. Then adds the bits of every held key that acts as a modifier, last,
     * so a swap between two modifiers ends with both targets set.
     */
    private fun rebuildMetaState(metaState: Int): Int {
        if (held.isEmpty() && stale.isEmpty()) return metaState
        var strip = 0
        var keep = 0
        var add = 0
        for (i in held.indices) {
            val hold = held[i]
            val source = modifierBits(hold.sourceKeyCode)?.sideMeta ?: 0
            if (hold.remapped) {
                strip = strip or source
                modifierBits(hold.targetKeyCode)?.let { add = add or it.sideMeta or it.genericMeta }
            } else {
                keep = keep or source
            }
        }
        for (i in stale.indices) {
            strip = strip or (modifierBits(stale[i].sourceKeyCode)?.sideMeta ?: 0)
        }
        strip = strip and keep.inv()
        if (strip == 0 && add == 0) return metaState

        var result = metaState and strip.inv()
        for (modifier in MODIFIERS) {
            if ((strip and modifier.sideMeta) != 0 && (result and modifier.oppositeSideMeta) == 0) {
                result = result and modifier.genericMeta.inv()
            }
        }
        return result or add
    }

    private class ModifierBits(val sideMeta: Int, val genericMeta: Int, val oppositeSideMeta: Int)

    companion object {
        private const val NO_DEVICE: Int = Int.MIN_VALUE
        private const val SCAN_CODE_FLAG: Long = 1L shl 31

        /**
         * True for the events user remaps are for: a key of a real keyboard. An event without a
         * device (injected by adb, a remote-control tool or another app) and an on-screen key
         * skip the remapper altogether, held keys and meta state included.
         */
        fun isPhysicalKeyEvent(event: KeyEvent?): Boolean = event != null && event.deviceId >= 0 &&
            (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) == 0

        /**
         * Identifies a physical key across its DOWN, repeats and UP: the device and the scan
         * code, or the key code when the event has no scan code. Two keys that send one key code
         * stay apart, and a key whose key code a vendor setting rewrites stays one key.
         */
        fun physicalKeyId(keyCode: Int, event: KeyEvent?): Long {
            val scanCode = event?.scanCode ?: 0
            val code = if (scanCode != 0) {
                SCAN_CODE_FLAG or (scanCode.toLong() and 0x7FFFFFFFL)
            } else {
                keyCode.toLong() and 0x7FFFFFFFL
            }
            return ((event?.deviceId ?: NO_DEVICE).toLong() shl 32) or code
        }

        /** The device id inside a [physicalKeyId]. */
        fun deviceIdOf(physicalKeyId: Long): Int = (physicalKeyId shr 32).toInt()

        /** The scan code inside a [physicalKeyId]: the raw event's, 0 when it had none. */
        fun scanCodeOf(physicalKeyId: Long): Int =
            if ((physicalKeyId and SCAN_CODE_FLAG) != 0L) (physicalKeyId and 0x7FFFFFFFL).toInt() else 0

        private val MODIFIERS = arrayOf(
            ModifierBits(KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_RIGHT_ON),
            ModifierBits(KeyEvent.META_SHIFT_RIGHT_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON),
            ModifierBits(KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_RIGHT_ON),
            ModifierBits(KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON),
            ModifierBits(KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_ALT_RIGHT_ON),
            ModifierBits(KeyEvent.META_ALT_RIGHT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON),
            ModifierBits(KeyEvent.META_META_LEFT_ON, KeyEvent.META_META_ON, KeyEvent.META_META_RIGHT_ON),
            ModifierBits(KeyEvent.META_META_RIGHT_ON, KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON),
            // Sym has one bit and no sides: the generic bit is the side bit.
            ModifierBits(KeyEvent.META_SYM_ON, KeyEvent.META_SYM_ON, 0)
        )

        private fun modifierBits(keyCode: Int): ModifierBits? = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> MODIFIERS[0]
            KeyEvent.KEYCODE_SHIFT_RIGHT -> MODIFIERS[1]
            KeyEvent.KEYCODE_CTRL_LEFT -> MODIFIERS[2]
            KeyEvent.KEYCODE_CTRL_RIGHT -> MODIFIERS[3]
            KeyEvent.KEYCODE_ALT_LEFT -> MODIFIERS[4]
            KeyEvent.KEYCODE_ALT_RIGHT -> MODIFIERS[5]
            KeyEvent.KEYCODE_META_LEFT -> MODIFIERS[6]
            KeyEvent.KEYCODE_META_RIGHT -> MODIFIERS[7]
            KeyEvent.KEYCODE_SYM -> MODIFIERS[8]
            else -> null
        }

        /**
         * True for the momentary modifier keys the remapper rebuilds meta state for (shift, ctrl,
         * alt, meta, sym): the one list of modifier key codes, for callers that must treat a
         * remap's output as a modifier.
         */
        fun isModifierKeyCode(keyCode: Int): Boolean = modifierBits(keyCode) != null

        /**
         * [metaState] as it reads once the key [keyCode] is up: for a modifier key without its
         * own bit, and without the generic bit unless the key of the other side still holds it;
         * unchanged for any other key. For an UP built from a key's DOWN.
         */
        fun metaStateAfterRelease(keyCode: Int, metaState: Int): Int {
            val bits = modifierBits(keyCode) ?: return metaState
            val withoutSide = metaState and bits.sideMeta.inv()
            if ((withoutSide and bits.oppositeSideMeta) != 0) return withoutSide
            return withoutSide and bits.genericMeta.inv()
        }
    }
}
