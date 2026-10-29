package rocks.pkb.plektra.inputmethod

import android.util.SparseIntArray
import android.view.KeyEvent
import androidx.annotation.StringRes
import rocks.pkb.plektra.R

/** One user-facing toggle. A toggle owns one or more one-way key mappings (a swap = two). */
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
 * held remapped keys (physical key code -> target key code, set on DOWN, cleared on UP so a toggle
 * flipped mid-hold still releases consistently) and rebuilds the meta state of every event.
 *
 * Stateful but free of Android context; callers pass the enabled option ids on every call.
 */
class HardwareKeyRemapper(
    private val options: List<KeyRemapOption> = HardwareKeyRemapRegistry.options
) {
    private val held = SparseIntArray()

    fun reset() {
        held.clear()
    }

    fun remap(
        keyCode: Int,
        event: KeyEvent?,
        enabledIds: Set<String>
    ): DeviceSpecific.RemappedHardwareEvent {
        val heldTarget = held.get(keyCode, NO_KEY)
        val targetKeyCode = when (event?.action) {
            KeyEvent.ACTION_DOWN -> {
                // A repeat or long press keeps the mapping chosen on the initial DOWN.
                val target = if (heldTarget != NO_KEY) heldTarget else mappingFor(keyCode, enabledIds)
                if (heldTarget == NO_KEY && target != keyCode) held.put(keyCode, target)
                target
            }
            KeyEvent.ACTION_UP -> {
                // No held entry: the DOWN was not remapped (or was never seen), so stay identity.
                if (heldTarget != NO_KEY) held.delete(keyCode)
                if (heldTarget != NO_KEY) heldTarget else keyCode
            }
            // No event, or an action without press/release semantics: remap statelessly.
            else -> if (heldTarget != NO_KEY) heldTarget else mappingFor(keyCode, enabledIds)
        }
        if (event == null) {
            return DeviceSpecific.RemappedHardwareEvent(targetKeyCode, null)
        }

        val metaState = rebuildMetaState(event.metaState)
        if (targetKeyCode == keyCode && metaState == event.metaState) {
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
            )
        )
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
     * Removes the bits of every held remapped modifier key (it no longer acts as that modifier),
     * then adds the bits of every held key that now acts as a modifier. Clearing runs first so
     * a swap between two modifiers ends with both targets set.
     */
    private fun rebuildMetaState(metaState: Int): Int {
        val heldCount = held.size()
        if (heldCount == 0) return metaState

        var result = metaState
        for (i in 0 until heldCount) {
            val source = modifierBits(held.keyAt(i)) ?: continue
            result = result and source.sideMeta.inv()
            // The generic bit stays while the opposite side still holds the modifier.
            if ((result and source.oppositeSideMeta) == 0) {
                result = result and source.genericMeta.inv()
            }
        }
        for (i in 0 until heldCount) {
            val target = modifierBits(held.valueAt(i)) ?: continue
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
