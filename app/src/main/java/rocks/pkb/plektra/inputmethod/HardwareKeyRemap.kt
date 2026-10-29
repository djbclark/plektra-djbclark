package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import androidx.annotation.StringRes
import rocks.pkb.plektra.R

/**
 * One user-facing toggle. A toggle owns one or more one-way key mappings (a swap = two).
 *
 * Mappings are between ordinary keys, momentary modifiers (shift, ctrl, alt, meta, sym) and lock
 * keys (Caps Lock, Num Lock, Scroll Lock), or between any two of those. A lock key as a mapping
 * source stops toggling its lock state; as a target it toggles a lock state the remapper keeps
 * itself (see [HardwareKeyRemapper]). Sym is a modifier like the others: as a target the other
 * keys carry META_SYM_ON while it is held and the IME's own Sym handling sees KEYCODE_SYM, as a
 * source META_SYM_ON no longer reflects the physical key.
 *
 * The option is on for every keyboard except [unsupportedModels]: there the settings screen
 * hides it and it stays off (see [DeviceSpecific.supportsUserKeyRemap]).
 */
data class KeyRemapOption(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val mappings: List<KeyMapping>,
    val unsupportedModels: Set<DeviceSpecific.KeyboardModel> = emptySet()
)

data class KeyMapping(val fromKeyCode: Int, val toKeyCode: Int)

/**
 * All hardware key remaps the user can toggle. Adding an entry here is the only change a new
 * remap needs: [HardwareKeyRemapper] derives key codes and modifier meta state from the mappings.
 */
object HardwareKeyRemapRegistry {
    const val SWAP_SHIFT_RIGHT_ENTER = "swap_shift_right_enter"

    val options: List<KeyRemapOption> = listOf(
        KeyRemapOption(
            id = SWAP_SHIFT_RIGHT_ENTER,
            titleRes = R.string.key_remap_swap_shift_right_enter_title,
            summaryRes = R.string.key_remap_swap_shift_right_enter_summary,
            // Only the main Enter key: a numpad Enter becoming Shift would surprise users of
            // external keyboards, and the phone keyboards this targets have no numpad.
            mappings = swap(KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ENTER),
            // The Q25's physical right Shift is its Ctrl key, so the swap would only half apply
            // (Enter would become Shift and nothing would type Enter).
            unsupportedModels = setOf(DeviceSpecific.KeyboardModel.Q25)
        )
    )

    fun byId(id: String): KeyRemapOption? = options.firstOrNull { it.id == id }

    fun swap(a: Int, b: Int): List<KeyMapping> = listOf(KeyMapping(a, b), KeyMapping(b, a))
}

/**
 * Applies the enabled [KeyRemapOption]s to hardware key events.
 *
 * The framework computes [KeyEvent.metaState] from the physical keys, so after a remap it is wrong
 * for modifiers: a held physical key that now acts as a modifier is missing from it, and a held
 * physical modifier that now acts as another key is still in it. The remapper therefore tracks
 * held remapped keys (device and physical key code -> target key code, set on the initial DOWN,
 * cleared on UP so a toggle flipped mid-hold still releases consistently) and rebuilds the meta
 * state of every event. Held state is per device so one keyboard's release cannot clear another's
 * press; the meta state is rebuilt from the held keys of every device because the framework
 * reports it globally. Only an initial DOWN (repeat count 0) starts a hold: a repeat or a long
 * press without a held entry belongs to a DOWN the remapper never saw and stays identity.
 *
 * [reset] ends the holds without ending the physical presses: the keys are still down, and their
 * repeats and release would otherwise arrive as raw events (a held Enter that acted as Shift
 * turns into Enter repeats and a bare Enter UP). Each held key therefore moves to a tombstone;
 * [remap] reports its repeats and its UP as a stale release
 * ([DeviceSpecific.RemappedHardwareEvent.staleRelease]) that the owner swallows and that adds
 * nothing to the meta state. A tombstone ends with its UP, with the next initial DOWN of the
 * key, or with [clearDevice]; there is at most one per (device, key) pair.
 *
 * Lock keys (Caps, Num, Scroll Lock) have a second table next to the modifier one. The framework
 * flips the lock bit of the meta state itself when a physical lock key is released and reports it
 * on every later event, so a lock key that is a mapping source has its bit removed from every
 * event (it no longer acts as a lock, whatever the framework toggled). A lock key that is a
 * mapping target toggles a lock state kept by the remapper, on each initial DOWN of its source,
 * and that state is flipped into the bit of every event, on top of what the framework reports for
 * a physical lock key nothing remapped. The kept state lives while its option is enabled or its
 * source is held, and [reset] clears it. An owner with a lock state of its own (the IME's Caps
 * Lock) toggles it on the events [toggledLockMeta] names.
 *
 * Stateful but free of Android context; callers pass the enabled option ids on every call. The
 * owner must [reset] the held state when it loses track of key releases (editor teardown, focus
 * loss), [clearDevice] when a keyboard disconnects, and report a release that returns before
 * [remap] with [release]. Shrinking the enabled set keeps held entries until their release, so
 * the release still maps to what the DOWN produced; only new presses stop being remapped.
 */
class HardwareKeyRemapper(
    private val options: List<KeyRemapOption> = HardwareKeyRemapRegistry.options
) {
    private class HeldKey(val deviceId: Int, val keyCode: Int, val targetKeyCode: Int)

    private class SupportedIds(
        val input: Set<String>,
        val model: DeviceSpecific.KeyboardModel,
        val output: Set<String>
    )

    private val held = ArrayList<HeldKey>()

    /** Keys a [reset] cut off mid-hold, still physically down; they add nothing to the meta state. */
    private val stale = ArrayList<HeldKey>()

    /** Lock bits of the meta state that remapped lock keys currently have switched on. */
    private var ownLocks: Int = 0

    private var supportedIdsCache: SupportedIds? = null

    /**
     * Ends every hold and the kept lock state. The held keys become tombstones, see the class
     * comment; [forgetHolds] drops them too, for an owner that knows every key is up (tests).
     */
    fun reset(forgetHolds: Boolean = false) {
        if (forgetHolds) {
            stale.clear()
        } else {
            for (i in held.indices) {
                val entry = held[i]
                if (indexOf(stale, entry.deviceId, entry.keyCode) < 0) stale.add(entry)
            }
        }
        held.clear()
        ownLocks = 0
    }

    /** Forgets the held keys of one keyboard, e.g. when it is disconnected. */
    fun clearDevice(deviceId: Int) {
        held.removeAll { it.deviceId == deviceId }
        stale.removeAll { it.deviceId == deviceId }
    }

    /**
     * Bookkeeping for a release the owner handles without calling [remap] (an early return before
     * it): drops the held entry or tombstone of this physical key, if any, and transforms nothing.
     */
    fun release(keyCode: Int, event: KeyEvent?) {
        val deviceId = event?.deviceId ?: NO_DEVICE
        val heldIndex = indexOf(held, deviceId, keyCode)
        if (heldIndex >= 0) held.removeAt(heldIndex)
        val staleIndex = indexOf(stale, deviceId, keyCode)
        if (staleIndex >= 0) stale.removeAt(staleIndex)
    }

    /**
     * [enabledIds] without the options that do not apply to [model] (see
     * [KeyRemapOption.unsupportedModels]). Returns the same set when nothing is dropped, and the
     * result of the last call while it is given the same set instance, so the per-event path does
     * not allocate; callers must not mutate a set they pass.
     */
    fun supportedIds(enabledIds: Set<String>, model: DeviceSpecific.KeyboardModel): Set<String> {
        if (enabledIds.isEmpty()) return enabledIds
        val cached = supportedIdsCache
        if (cached != null && cached.input === enabledIds && cached.model == model) return cached.output
        val supported = HashSet<String>()
        for (id in enabledIds) {
            if (options.none { it.id == id && model in it.unsupportedModels }) supported.add(id)
        }
        val output = if (supported.size == enabledIds.size) enabledIds else supported
        supportedIdsCache = SupportedIds(enabledIds, model, output)
        return output
    }

    /**
     * The key code [remap] would produce for this event, without touching any state. For callers
     * that must classify a physical key before it is remapped (e.g. key filters).
     */
    fun peekKeyCode(keyCode: Int, event: KeyEvent?, enabledIds: Set<String>): Int {
        val deviceId = event?.deviceId ?: NO_DEVICE
        val heldIndex = indexOf(held, deviceId, keyCode)
        if (heldIndex >= 0) return held[heldIndex].targetKeyCode
        val continuesPress = event?.action == KeyEvent.ACTION_UP ||
            (event?.action == KeyEvent.ACTION_DOWN && (event?.repeatCount ?: 0) > 0)
        if (continuesPress) {
            val staleIndex = indexOf(stale, deviceId, keyCode)
            return if (staleIndex >= 0) stale[staleIndex].targetKeyCode else keyCode
        }
        return mappingFor(keyCode, enabledIds)
    }

    fun remap(
        keyCode: Int,
        event: KeyEvent?,
        enabledIds: Set<String>
    ): DeviceSpecific.RemappedHardwareEvent {
        if (ownLocks != 0) ownLocks = ownLocks and activeTargetLocks(enabledIds)

        val deviceId = event?.deviceId ?: NO_DEVICE
        if (event != null && stale.isNotEmpty()) {
            val staleIndex = indexOf(stale, deviceId, keyCode)
            if (staleIndex >= 0) {
                val initialDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
                if (initialDown) {
                    // A new press supersedes a tombstone whose UP was lost.
                    stale.removeAt(staleIndex)
                } else if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    if (event.action == KeyEvent.ACTION_UP) stale.removeAt(staleIndex)
                    return DeviceSpecific.RemappedHardwareEvent(keyCode, event, staleRelease = true)
                }
            }
        }
        val heldIndex = indexOf(held, deviceId, keyCode)
        val heldTarget = if (heldIndex >= 0) held[heldIndex].targetKeyCode else NO_KEY
        val targetKeyCode = when (event?.action) {
            KeyEvent.ACTION_DOWN -> when {
                // A repeat or long press keeps the mapping chosen on the initial DOWN.
                heldTarget != NO_KEY -> heldTarget
                // No held entry: the initial DOWN was not remapped (or was never seen).
                (event?.repeatCount ?: 0) > 0 -> keyCode
                else -> {
                    val target = mappingFor(keyCode, enabledIds)
                    if (target != keyCode) {
                        held.add(HeldKey(deviceId, keyCode, target))
                        ownLocks = ownLocks xor lockMeta(target)
                    }
                    target
                }
            }
            KeyEvent.ACTION_UP -> {
                // No held entry: the DOWN was not remapped (or was never seen), so stay identity.
                if (heldIndex >= 0) held.removeAt(heldIndex)
                if (heldTarget != NO_KEY) heldTarget else keyCode
            }
            // No event, or an action without press/release semantics: remap statelessly.
            else -> if (heldTarget != NO_KEY) heldTarget else mappingFor(keyCode, enabledIds)
        }
        val keyCodeRemapped = targetKeyCode != keyCode
        if (event == null) {
            return DeviceSpecific.RemappedHardwareEvent(
                keyCode = targetKeyCode,
                event = null,
                transformed = keyCodeRemapped,
                keyCodeRemapped = keyCodeRemapped
            )
        }

        val metaState = rebuildMetaState(event.metaState, enabledIds)
        if (!keyCodeRemapped && metaState == event.metaState) {
            return DeviceSpecific.RemappedHardwareEvent(keyCode, event)
        }
        return DeviceSpecific.RemappedHardwareEvent(
            keyCode = targetKeyCode,
            event = KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                targetKeyCode,
                event.repeatCount,
                metaState,
                event.deviceId,
                event.scanCode,
                event.flags,
                event.source
            ),
            transformed = true,
            keyCodeRemapped = keyCodeRemapped
        )
    }

    private fun indexOf(entries: ArrayList<HeldKey>, deviceId: Int, keyCode: Int): Int {
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.deviceId == deviceId && entry.keyCode == keyCode) return i
        }
        return -1
    }

    private fun mappingFor(keyCode: Int, enabledIds: Set<String>): Int {
        if (enabledIds.isEmpty()) return keyCode
        for (optionIndex in options.indices) {
            val option = options[optionIndex]
            if (option.id !in enabledIds) continue
            val mappings = option.mappings
            for (mappingIndex in mappings.indices) {
                val mapping = mappings[mappingIndex]
                if (mapping.fromKeyCode == keyCode) return mapping.toKeyCode
            }
        }
        return keyCode
    }

    /** Lock bits of the source (or target) keys of every enabled mapping. */
    private fun enabledLocks(enabledIds: Set<String>, targets: Boolean): Int {
        if (enabledIds.isEmpty()) return 0
        var locks = 0
        for (optionIndex in options.indices) {
            val option = options[optionIndex]
            if (option.id !in enabledIds) continue
            val mappings = option.mappings
            for (mappingIndex in mappings.indices) {
                val mapping = mappings[mappingIndex]
                locks = locks or lockMeta(if (targets) mapping.toKeyCode else mapping.fromKeyCode)
            }
        }
        return locks
    }

    /** Lock bits some enabled mapping or held key still targets; the kept state ends with them. */
    private fun activeTargetLocks(enabledIds: Set<String>): Int {
        var locks = enabledLocks(enabledIds, targets = true)
        for (i in held.indices) locks = locks or lockMeta(held[i].targetKeyCode)
        return locks
    }

    /**
     * Removes the lock bits of every remapped lock source (the framework toggles them for a key
     * that no longer acts as a lock) and flips in the kept lock state; then removes the bits of
     * every held remapped modifier key of any device (it no longer acts as that modifier), and
     * adds the bits of every held key that now acts as a modifier. Clearing runs first so a swap
     * between two modifiers ends with both targets set.
     */
    private fun rebuildMetaState(metaState: Int, enabledIds: Set<String>): Int {
        val heldCount = held.size
        var sourceLocks = enabledLocks(enabledIds, targets = false)
        for (i in 0 until heldCount) sourceLocks = sourceLocks or lockMeta(held[i].keyCode)

        var result = (metaState and sourceLocks.inv()) xor ownLocks
        if (heldCount == 0) return result

        for (i in 0 until heldCount) {
            val source = modifierBits(held[i].keyCode) ?: continue
            result = result and source.sideMeta.inv()
            // The generic bit stays while the opposite side still holds the modifier.
            if ((result and source.oppositeSideMeta) == 0) {
                result = result and source.genericMeta.inv()
            }
        }
        for (i in 0 until heldCount) {
            val target = modifierBits(held[i].targetKeyCode) ?: continue
            result = result or target.sideMeta or target.genericMeta
        }
        return result
    }

    private class ModifierBits(
        val sideMeta: Int,
        val genericMeta: Int,
        val oppositeSideMeta: Int
    )

    companion object {
        private const val NO_KEY: Int = Int.MIN_VALUE

        /**
         * Device id of events that carry no [KeyEvent] (so no device). Real ids are >= -1, where
         * -1 is the virtual keyboard, so this never collides with a keyboard.
         */
        private const val NO_DEVICE: Int = Int.MIN_VALUE

        private val SHIFT_LEFT = ModifierBits(
            KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_RIGHT_ON
        )
        private val SHIFT_RIGHT = ModifierBits(
            KeyEvent.META_SHIFT_RIGHT_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON
        )
        private val CTRL_LEFT = ModifierBits(
            KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_RIGHT_ON
        )
        private val CTRL_RIGHT = ModifierBits(
            KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON
        )
        private val ALT_LEFT = ModifierBits(
            KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_ALT_RIGHT_ON
        )
        private val ALT_RIGHT = ModifierBits(
            KeyEvent.META_ALT_RIGHT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON
        )
        private val META_LEFT = ModifierBits(
            KeyEvent.META_META_LEFT_ON, KeyEvent.META_META_ON, KeyEvent.META_META_RIGHT_ON
        )
        private val META_RIGHT = ModifierBits(
            KeyEvent.META_META_RIGHT_ON, KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON
        )

        // Sym has one bit and no sides: the generic bit is the side bit.
        private val SYM = ModifierBits(KeyEvent.META_SYM_ON, KeyEvent.META_SYM_ON, 0)

        private fun modifierBits(keyCode: Int): ModifierBits? = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT_LEFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_RIGHT
            KeyEvent.KEYCODE_CTRL_LEFT -> CTRL_LEFT
            KeyEvent.KEYCODE_CTRL_RIGHT -> CTRL_RIGHT
            KeyEvent.KEYCODE_ALT_LEFT -> ALT_LEFT
            KeyEvent.KEYCODE_ALT_RIGHT -> ALT_RIGHT
            KeyEvent.KEYCODE_META_LEFT -> META_LEFT
            KeyEvent.KEYCODE_META_RIGHT -> META_RIGHT
            KeyEvent.KEYCODE_SYM -> SYM
            else -> null
        }

        /**
         * True for the momentary modifier keys the remapper rebuilds meta state for (shift, ctrl,
         * alt, meta, sym): the one list of modifier key codes, for callers that must treat a
         * remap's input or output as a modifier.
         */
        fun isModifierKeyCode(keyCode: Int): Boolean = modifierBits(keyCode) != null

        /** The lock bit of the meta state a lock key toggles, 0 for any other key. */
        private fun lockMeta(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_CAPS_LOCK -> KeyEvent.META_CAPS_LOCK_ON
            KeyEvent.KEYCODE_NUM_LOCK -> KeyEvent.META_NUM_LOCK_ON
            KeyEvent.KEYCODE_SCROLL_LOCK -> KeyEvent.META_SCROLL_LOCK_ON
            else -> 0
        }

        /**
         * The lock bit a remapped event toggles for its owner: the initial DOWN of a key that a
         * remap turned into a lock key, 0 for any other event. A lock key nothing remapped is not
         * one (the system flips its lock bit itself), nor is a repeat, an UP or a stale release.
         */
        fun toggledLockMeta(remapped: DeviceSpecific.RemappedHardwareEvent): Int {
            val event = remapped.event ?: return 0
            if (!remapped.keyCodeRemapped || remapped.staleRelease) return 0
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return 0
            return lockMeta(remapped.keyCode)
        }
    }
}

/**
 * A set of physical keys, each (device id, key code) packed into a long by [keyId], that never
 * boxes. Small and linear: it holds the keys currently down.
 */
internal class PhysicalKeySet {
    private var keys = LongArray(8)
    private var size = 0

    operator fun contains(key: Long): Boolean = indexOf(key) >= 0

    fun add(key: Long) {
        if (indexOf(key) >= 0) return
        if (size == keys.size) keys = keys.copyOf(size * 2)
        keys[size++] = key
    }

    /** True when the key was in the set. */
    fun remove(key: Long): Boolean {
        val index = indexOf(key)
        if (index < 0) return false
        keys[index] = keys[--size]
        return true
    }

    fun removeDevice(deviceId: Int) {
        var i = 0
        while (i < size) {
            if ((keys[i] shr 32).toInt() == deviceId) keys[i] = keys[--size] else i++
        }
    }

    fun clear() {
        size = 0
    }

    private fun indexOf(key: Long): Int {
        for (i in 0 until size) if (keys[i] == key) return i
        return -1
    }

    companion object {
        fun keyId(deviceId: Int, keyCode: Int): Long =
            (deviceId.toLong() shl 32) or (keyCode.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * The key events the IME injected into the app through its input connection, so the app's own
 * remapping of physical keys (MainActivity) can tell them from physical ones: they already went
 * through the remapper. The IME and the activities share a process, and the input connection
 * hands the event on with its times, action and key code unchanged, so those identify it; flags
 * would change what the app sees. A bounded ring touched on the main thread only: an entry is
 * overwritten after [CAPACITY] later injections, long after the event was delivered.
 */
object InjectedKeyEvents {
    private const val CAPACITY = 16
    private val downTimes = LongArray(CAPACITY)
    private val eventTimes = LongArray(CAPACITY)
    private val actionAndKeys = LongArray(CAPACITY) { EMPTY }
    private var next = 0

    fun record(event: KeyEvent) {
        downTimes[next] = event.downTime
        eventTimes[next] = event.eventTime
        actionAndKeys[next] = actionAndKey(event)
        next = (next + 1) % CAPACITY
    }

    fun wasInjected(event: KeyEvent): Boolean {
        val actionAndKey = actionAndKey(event)
        for (i in 0 until CAPACITY) {
            if (actionAndKeys[i] == actionAndKey &&
                eventTimes[i] == event.eventTime &&
                downTimes[i] == event.downTime
            ) return true
        }
        return false
    }

    private const val EMPTY = -1L

    private fun actionAndKey(event: KeyEvent): Long =
        (event.action.toLong() shl 32) or (event.keyCode.toLong() and 0xFFFFFFFFL)
}
