package rocks.pkb.plektra

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import rocks.pkb.plektra.inputmethod.FakeKeyboards
import rocks.pkb.plektra.inputmethod.KeyRemapRule
import rocks.pkb.plektra.inputmethod.KeySource

/**
 * The key test screen's view of the keys: on a Titan 2 Elite with "Swap right Shift and Enter" on.
 * Physical keys by Linux scan code: Enter 28, right Shift 54, A 30.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActivityKeyRemapTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val rightShiftMeta = KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON
    private val swapId = HardwareKeyRemapPresets.SWAP_SHIFT_RIGHT_ENTER
    private lateinit var keys: ActivityKeyRemap
    private var clock = 1_000L

    @Before
    fun setUp() {
        // A Titan 2 Elite reports itself as a Titan 2; its board and display name tell it apart.
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz",
            manufacturer = "unihertz",
            model = "Titan 2",
            device = "Titan_2",
            product = "Titan_2_EEA",
            board = "G72BoardV1",
            display = "Titan 2 Elite"
        )
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "auto")
        FakeKeyboards.register()
        assertTrue(SettingsManager.setKeyRemapPresetEnabled(context, swapId, true))
        keys = ActivityKeyRemap()
    }

    @After
    fun tearDown() {
        DeviceSpecific.clearTestOverrides()
    }

    /** A key event as the system delivers it from a keyboard. */
    private fun key(
        action: Int,
        keyCode: Int,
        scanCode: Int,
        metaState: Int = 0,
        repeatCount: Int = 0,
        deviceId: Int = FakeKeyboards.BUILT_IN,
        flags: Int = KeyEvent.FLAG_FROM_SYSTEM
    ): KeyEvent {
        clock += 50L
        return KeyEvent(
            clock, clock, action, keyCode, repeatCount, metaState, deviceId, scanCode, flags,
            InputDevice.SOURCE_KEYBOARD
        )
    }

    private fun shown(event: KeyEvent): ActivityKeyRemap.Key = keys.remap(context, event.keyCode, event)

    private fun typedAIsShifted(metaState: Int = 0): Boolean {
        val a = shown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 30, metaState))
        shown(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 30, metaState))
        assertEquals(KeyEvent.KEYCODE_A, a.keyCode)
        return requireNotNull(a.event).isShiftPressed
    }

    @Test
    fun rulesSeeTheKeyAfterDeviceNormalization() {
        // The Q25's Ctrl key sends the right Shift key code, and device normalization makes it
        // Ctrl: the user's rules apply to that Ctrl, not to the key code that arrived.
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "zinwa",
            manufacturer = "zinwa",
            model = "Q25",
            device = "Q25",
            product = "q25"
        )
        assertTrue(
            SettingsManager.setHardwareKeyRemapRules(
                context,
                "q25",
                listOf(
                    KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_SHIFT_RIGHT), KeyEvent.KEYCODE_ENTER),
                    KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_TAB)
                )
            )
        )

        val ctrl = shown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, KeyEvent.META_SHIFT_RIGHT_ON))

        assertEquals(KeyEvent.KEYCODE_TAB, ctrl.keyCode)
        assertEquals(KeyEvent.KEYCODE_TAB, ctrl.event?.keyCode)
        assertFalse(requireNotNull(ctrl.event).isCtrlPressed)
        assertFalse(requireNotNull(ctrl.event).isShiftPressed)
    }

    @Test
    fun rawKeyboardEvent_isShownAsTheImeDeliversIt() {
        val down = shown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 28))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, down.keyCode)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, down.event?.keyCode)
        assertFalse(down.stale)
        assertTrue(typedAIsShifted())
        val up = shown(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 28))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, up.keyCode)
        assertFalse(typedAIsShifted())

        val enter = shown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, rightShiftMeta))
        assertEquals(KeyEvent.KEYCODE_ENTER, enter.keyCode)
        assertFalse(requireNotNull(enter.event).isShiftPressed)
        // Held as Enter, the key does not shift what is typed over it.
        assertFalse(typedAIsShifted(rightShiftMeta))
        assertEquals(KeyEvent.KEYCODE_ENTER, shown(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, 54)).keyCode)
    }

    @Test
    fun keyTheImeAlreadyRemappedAndSent_passesThroughUntouched() {
        // The IME sent Shift for the physical Enter key: such an event has no FLAG_FROM_SYSTEM.
        val sent = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 28, rightShiftMeta, flags = 0)
        val down = shown(sent)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, down.keyCode)
        assertSame(sent, down.event)
        assertFalse(down.stale)
        // It is not remapped back into Enter, and it holds nothing here: the meta state of the
        // keys that follow is the system's.
        assertTrue(typedAIsShifted(rightShiftMeta))
        assertFalse(typedAIsShifted())
    }

    @Test
    fun eventWithoutADevice_onScreenKey_andAnotherKeyboard_areNotRemapped() {
        val events = listOf(
            KeyEvent(clock, clock, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0),
            key(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 28,
                flags = KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_SOFT_KEYBOARD
            ),
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 28, deviceId = FakeKeyboards.ACCESSORY)
        )
        for (event in events) {
            val key = shown(event)
            assertEquals("device ${event.deviceId} flags ${event.flags}", KeyEvent.KEYCODE_ENTER, key.keyCode)
            assertFalse(key.stale)
        }
        assertFalse(typedAIsShifted())
    }

    @Test
    fun reset_cutsOffAHeldKey_andTheRestOfItsPressIsStale() {
        shown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, rightShiftMeta))
        keys.reset() // the window lost focus with the key down

        val repeat = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, rightShiftMeta, repeatCount = 1)
        assertTrue(shown(repeat).stale)
        // Still down, it stays what it was: not a Shift.
        assertFalse(typedAIsShifted(rightShiftMeta))
        assertTrue(shown(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, 54)).stale)

        val again = shown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 54, rightShiftMeta))
        assertFalse(again.stale)
        assertEquals(KeyEvent.KEYCODE_ENTER, again.keyCode)
    }

    @Test
    fun clearDevice_forgetsTheHeldKeysOfThatKeyboardOnly() {
        shown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 28))
        assertTrue(typedAIsShifted())
        keys.clearDevice(FakeKeyboards.ACCESSORY)
        assertTrue(typedAIsShifted())
        keys.clearDevice(FakeKeyboards.BUILT_IN)
        assertFalse(typedAIsShifted())
        // Nothing is left of the press, not even a tombstone.
        val up = shown(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 28))
        assertFalse(up.stale)
        assertEquals(KeyEvent.KEYCODE_ENTER, up.keyCode)
    }

    @Test
    fun withoutRules_everyEventPassesThrough() {
        assertFalse(SettingsManager.setKeyRemapPresetEnabled(context, swapId, false))
        val event = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 28)
        val key = shown(event)
        assertEquals(KeyEvent.KEYCODE_ENTER, key.keyCode)
        assertSame(event, key.event)
        assertFalse(key.stale)
    }
}
