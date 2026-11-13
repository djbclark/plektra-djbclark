package rocks.pkb.plektra.inputmethod

import android.hardware.input.InputManager
import android.util.SparseArray
import android.view.InputDevice
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers

/**
 * Input devices for Robolectric tests, so that [InputDevice.getDevice] resolves the device id of
 * a test key event: a built-in keyboard and an accessory one. Robolectric starts every test with
 * a fresh InputManager, so there is nothing to undo.
 */
internal object FakeKeyboards {
    const val BUILT_IN = 3
    const val ACCESSORY = 7

    fun register() {
        val devices = SparseArray<InputDevice>()
        devices.put(BUILT_IN, keyboard(BUILT_IN, "Built-in keyboard", external = false))
        devices.put(ACCESSORY, keyboard(ACCESSORY, "USB keyboard", external = true))
        // The instance InputDevice.getDevice() asks.
        val manager = ReflectionHelpers.callStaticMethod<InputManager>(InputManager::class.java, "getInstance")
        ReflectionHelpers.setField(manager, "mInputDevices", devices)
    }

    private fun keyboard(id: Int, name: String, external: Boolean): InputDevice {
        val device = ShadowInputDevice.makeInputDeviceNamed(name)
        ReflectionHelpers.setField(device, "mId", id)
        ReflectionHelpers.setField(device, "mSources", InputDevice.SOURCE_KEYBOARD)
        ReflectionHelpers.setField(device, "mKeyboardType", InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        ReflectionHelpers.setField(device, "mIsExternal", external)
        return device
    }
}
