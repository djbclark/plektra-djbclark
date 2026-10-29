package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import androidx.annotation.StringRes
import rocks.pkb.plektra.R

/**
 * One user-facing toggle. A toggle owns one or more one-way key mappings (a swap = two).
 *
 * Mappings are between ordinary keys and momentary modifiers (shift, ctrl, alt, meta), or between
 * two of those. Toggling keys such as Caps Lock are unsupported: they carry a lock bit in the meta
 * state that the remapper neither removes nor synthesizes.
 */
data class KeyRemapOption(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val mappings: List<KeyMapping>
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
            mappings = swap(KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ENTER)
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
 * held remapped keys (device and physical key code -> target key code, set on DOWN, cleared on UP
 * so a toggle flipped mid-hold still releases consistently) and rebuilds the meta state of every
 * event. Held state is per device so one keyboard's release cannot clear another's press; the meta
 * state is rebuilt from the held keys of every device because the framework reports it globally.
 *
 * Only momentary modifiers (shift, ctrl, alt, meta) and ordinary keys are supported as mapping
 * ends; toggling keys such as Caps Lock are not (see [KeyRemapOption]).
 *
 * Stateful but free of Android context; callers pass the enabled option ids on every call. The
 * owner must [reset] the held state when it loses track of key releases (editor teardown, focus
 * loss) and [clearDevice] when a keyboard disconnects. Shrinking the enabled set also drops the
 * held state: a release that was already lost would otherwise keep a disabled remap alive.
 */
class HardwareKeyRemapper(
    private val options: List<KeyRemapOption> = HardwareKeyRemapRegistry.options
) {
    private class HeldKey(val deviceId: Int, val keyCode: Int, val targetKeyCode: Int)

    private val held = ArrayList<HeldKey>()
    private var lastEnabledIds: Set<String> = emptySet()

    fun reset() {
        held.clear()
    }

    /** Forgets the held keys of one keyboard, e.g. when it is disconnected. */
    fun clearDevice(deviceId: Int) {
        held.removeAll { it.deviceId == deviceId }
    }

    /**
     * The key code [remap] would produce for this event, without touching any state. For callers
     * that must classify a physical key before it is remapped (e.g. key filters).
     */
    fun peekKeyCode(keyCode: Int, event: KeyEvent?, enabledIds: Set<String>): Int {
        val heldIndex = indexOfHeld(event?.deviceId ?: NO_DEVICE, keyCode)
        if (heldIndex >= 0) return held[heldIndex].targetKeyCode
        if (event?.action == KeyEvent.ACTION_UP) return keyCode
        return mappingFor(keyCode, enabledIds)
    }

    fun remap(
        keyCode: Int,
        event: KeyEvent?,
        enabledIds: Set<String>
    ): DeviceSpecific.RemappedHardwareEvent {
        if (lastEnabledIds.isNotEmpty() && !enabledIds.containsAll(lastEnabledIds)) held.clear()
        lastEnabledIds = enabledIds

        val deviceId = event?.deviceId ?: NO_DEVICE
        val heldIndex = indexOfHeld(deviceId, keyCode)
        val heldTarget = if (heldIndex >= 0) held[heldIndex].targetKeyCode else NO_KEY
        val targetKeyCode = when (event?.action) {
            KeyEvent.ACTION_DOWN -> {
                // A repeat or long press keeps the mapping chosen on the initial DOWN.
                val target = if (heldTarget != NO_KEY) heldTarget else mappingFor(keyCode, enabledIds)
                if (heldTarget == NO_KEY && target != keyCode) {
                    held.add(HeldKey(deviceId, keyCode, target))
                }
                target
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

        val metaState = rebuildMetaState(event.metaState)
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

    private fun indexOfHeld(deviceId: Int, keyCode: Int): Int {
        for (i in held.indices) {
            val entry = held[i]
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

    /**
     * Removes the bits of every held remapped modifier key of any device (it no longer acts as
     * that modifier), then adds the bits of every held key that now acts as a modifier. Clearing
     * runs first so a swap between two modifiers ends with both targets set.
     */
    private fun rebuildMetaState(metaState: Int): Int {
        val heldCount = held.size
        if (heldCount == 0) return metaState

        var result = metaState
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

    private companion object {
        const val NO_KEY: Int = Int.MIN_VALUE

        /**
         * Device id of events that carry no [KeyEvent] (so no device). Real ids are >= -1, where
         * -1 is the virtual keyboard, so this never collides with a keyboard.
         */
        const val NO_DEVICE: Int = Int.MIN_VALUE

        val SHIFT_LEFT = ModifierBits(
            KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_RIGHT_ON
        )
        val SHIFT_RIGHT = ModifierBits(
            KeyEvent.META_SHIFT_RIGHT_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON
        )
        val CTRL_LEFT = ModifierBits(
            KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_RIGHT_ON
        )
        val CTRL_RIGHT = ModifierBits(
            KeyEvent.META_CTRL_RIGHT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON
        )
        val ALT_LEFT = ModifierBits(
            KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_ALT_RIGHT_ON
        )
        val ALT_RIGHT = ModifierBits(
            KeyEvent.META_ALT_RIGHT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON
        )
        val META_LEFT = ModifierBits(
            KeyEvent.META_META_LEFT_ON, KeyEvent.META_META_ON, KeyEvent.META_META_RIGHT_ON
        )
        val META_RIGHT = ModifierBits(
            KeyEvent.META_META_RIGHT_ON, KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON
        )

        fun modifierBits(keyCode: Int): ModifierBits? = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT_LEFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_RIGHT
            KeyEvent.KEYCODE_CTRL_LEFT -> CTRL_LEFT
            KeyEvent.KEYCODE_CTRL_RIGHT -> CTRL_RIGHT
            KeyEvent.KEYCODE_ALT_LEFT -> ALT_LEFT
            KeyEvent.KEYCODE_ALT_RIGHT -> ALT_RIGHT
            KeyEvent.KEYCODE_META_LEFT -> META_LEFT
            KeyEvent.KEYCODE_META_RIGHT -> META_RIGHT
            else -> null
        }
    }
}
