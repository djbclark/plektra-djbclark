package rocks.pkb.plektra

import android.content.Context
import android.view.KeyEvent
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import rocks.pkb.plektra.inputmethod.HardwareKeyRemapper

/**
 * The user's hardware key remaps for an activity that shows keys as the IME delivers them (the
 * key test screen).
 *
 * An activity gets a key two ways. The IME's own handling may have left a remapped key alone and
 * sent it on through the input connection: that event is what the IME made of the key, device
 * normalization and remap included, and the framework marks it by clearing FLAG_FROM_SYSTEM
 * (ViewRootImpl does, for every key an IME sends). It passes through exactly as it came:
 * normalizing it again would name the key by its scan code and undo the remap (A as B would
 * turn back into A on a keyboard with canonical key positions). Or the system delivers the raw
 * event of a real keyboard (the IME is not bound to this window, or could not send the key):
 * those get device normalization and then the remap rules, with a [HardwareKeyRemapper] of this
 * activity's own. An event without a device and an on-screen key get device normalization only
 * and leave the held-key state alone.
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
        if (event != null && isSentByIme(event) && keyCode == Int.MIN_VALUE) return Key(keyCode, event, stale = false)
        val profileOverride = SettingsManager.getPhysicalKeyboardProfileOverride(context)
        val normalized = DeviceSpecific.remapHardwareKeyEvent(keyCode, event, profileOverride)
        val normalizedEvent = normalized.event
        if (event == null || normalizedEvent == null || !HardwareKeyRemapper.isPhysicalKeyEvent(event)) {
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

    /** A keyboard's key that did not come from the system: the IME handled it and sent it on. */
    private fun isSentByIme(event: KeyEvent): Boolean =
        HardwareKeyRemapper.isPhysicalKeyEvent(event) && (event.flags and KeyEvent.FLAG_FROM_SYSTEM) == 0
}
