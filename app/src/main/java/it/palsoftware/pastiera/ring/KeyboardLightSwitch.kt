package it.palsoftware.pastiera.ring

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The vendor's keyboard-light master switch (`Settings.Global.agui_keyboard_background_light`,
 * 1 = lit, 0 = dark) as the ring sees it: three operations and nothing else.
 *
 * The backlight group owns this switch (`KeyboardBacklightManager`, with the same three operations
 * as `canWriteKeyboardLightSwitch`, `readKeyboardLightSwitch` and `writeKeyboardLightSwitch`), but
 * that class is not in the ring branch. [RingBacklight] therefore talks to this interface, and
 * [DirectKeyboardLightSwitch] does the same in-process settings access until the two branches meet.
 *
 * After the merge, replace the body of [DirectKeyboardLightSwitch] with three one-line calls to
 * `KeyboardBacklightManager` (or point [RingBacklight.switch] at an adapter). Nothing here uses the
 * ADB broker; the switch needs only the `WRITE_SECURE_SETTINGS` grant.
 */
interface KeyboardLightSwitch {
    /** Whether the app may write the switch (it holds WRITE_SECURE_SETTINGS). */
    fun canWrite(context: Context): Boolean

    /** The switch as the vendor holds it, or [default] when the system has no value. */
    fun read(context: Context, default: Int): Int

    /** Writes 0 or 1; false when refused or when the permission is missing. */
    fun write(context: Context, value: Int): Boolean

    companion object {
        const val DARK = 0
        const val LIT = 1
    }
}

/** In-process access to the vendor row, used until the backlight group's manager is merged. */
object DirectKeyboardLightSwitch : KeyboardLightSwitch {
    const val SETTING = "agui_keyboard_background_light"

    override fun canWrite(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun read(context: Context, default: Int): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING, default)
    }.getOrDefault(default)

    override fun write(context: Context, value: Int): Boolean {
        if (value != KeyboardLightSwitch.DARK && value != KeyboardLightSwitch.LIT) return false
        return runCatching {
            Settings.Global.putInt(context.contentResolver, SETTING, value)
        }.getOrDefault(false)
    }
}
