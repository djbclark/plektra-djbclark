package rocks.pkb.plektra

import android.content.Context
import android.view.KeyEvent
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import rocks.pkb.plektra.inputmethod.HardwareKeyRemapper

/**
 * The user's hardware key remaps for an activity that shows keys as the IME delivers them (the
 * key test screen): device normalization for every event, then the remap rules for the events
 * that still need them.
 *
 * An activity gets a key two ways. The IME's own handling may have left a remapped key alone and
 * sent it on through the input connection: that event is already remapped, and the framework
 * marks it by clearing FLAG_FROM_SYSTEM (ViewRootImpl does, for every key an IME sends). Such an
 * event, an event without a device and an on-screen key pass through unchanged and leave the
 * held-key state alone. Or the system delivers the raw event of a real keyboard (the IME is not
 * bound to this window, or could not send the key): only those are remapped here, with a
 * [HardwareKeyRemapper] of this activity's own.
 *
 * The owner calls [reset] when it stops receiving key events (window focus lost, paused) and
 * [clearDevice] when a keyboard goes away or changes.
 */
internal class ActivityKeyRemap {
    /**
     * The key to show and pass on. [stale]: the rest of a press that [reset] cut off; the owner
     * consumes it and shows nothing.
     */
    class Key(val keyCode: Int, val event: KeyEvent?, val stale: Boolean)

    private val remapper = HardwareKeyRemapper()

    fun remap(context: Context, keyCode: Int, event: KeyEvent?): Key {
        val profileOverride = SettingsManager.getPhysicalKeyboardProfileOverride(context)
        val normalized = DeviceSpecific.remapHardwareKeyEvent(keyCode, event, profileOverride)
        val normalizedEvent = normalized.event
        if (event == null || normalizedEvent == null || !isRawKeyboardEvent(event)) {
            return Key(normalized.keyCode, normalizedEvent, stale = false)
        }
        val table = SettingsManager.getHardwareKeyRemaps(context)
        if (table.isEmpty && remapper.isIdle) return Key(normalized.keyCode, normalizedEvent, stale = false)
        val remapped = remapper.remap(
            normalized.keyCode,
            normalizedEvent,
            table.rulesFor(DeviceSpecific.resolveInputProfile(event, profileOverride)),
            HardwareKeyRemapper.physicalKeyId(keyCode, event)
        )
        return Key(remapped.keyCode, remapped.event, remapped.isStale)
    }

    fun reset() {
        remapper.reset()
    }

    fun clearDevice(deviceId: Int) {
        remapper.clearDevice(deviceId)
    }

    private fun isRawKeyboardEvent(event: KeyEvent): Boolean =
        HardwareKeyRemapper.isPhysicalKeyEvent(event) && (event.flags and KeyEvent.FLAG_FROM_SYSTEM) != 0
}
