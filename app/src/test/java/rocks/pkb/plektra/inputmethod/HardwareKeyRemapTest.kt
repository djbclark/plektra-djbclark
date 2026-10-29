package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HardwareKeyRemapTest {

    private val swapId = HardwareKeyRemapRegistry.SWAP_SHIFT_RIGHT_ENTER
    private val enabled = setOf(swapId)
    private val shiftRightMeta = KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON
    private val shiftLeftMeta = KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON

    @After
    fun tearDown() {
        DeviceSpecific.clearTestOverrides()
    }

    @Test
    fun optionOff_isIdentity() {
        val remapper = HardwareKeyRemapper()
        val shiftDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta)
        val enterDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)

        val shift = remapper.remap(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftDown, emptySet())
        val enter = remapper.remap(KeyEvent.KEYCODE_ENTER, enterDown, emptySet())

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, shift.keyCode)
        assertSame(shiftDown, shift.event)
        assertEquals(KeyEvent.KEYCODE_ENTER, enter.keyCode)
        assertSame(enterDown, enter.event)
    }

    @Test
    fun shiftRightDown_becomesEnterWithoutShiftMeta() {
        val remapper = HardwareKeyRemapper()
        val input = keyEvent(
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta,
            downTime = 100L, eventTime = 120L, scanCode = 54, deviceId = 7, flags = 8
        )

        val remapped = remapper.remap(KeyEvent.KEYCODE_SHIFT_RIGHT, input, enabled)

        assertEquals(KeyEvent.KEYCODE_ENTER, remapped.keyCode)
        val event = remapped.event ?: error("Expected remapped event")
        assertEquals(KeyEvent.KEYCODE_ENTER, event.keyCode)
        assertEquals(0, event.metaState)
        assertEquals(KeyEvent.ACTION_DOWN, event.action)
        assertEquals(100L, event.downTime)
        assertEquals(120L, event.eventTime)
        assertEquals(54, event.scanCode)
        assertEquals(7, event.deviceId)
        assertEquals(8, event.flags)
    }

    @Test
    fun enterDown_becomesShiftRightWithShiftMeta() {
        val remapper = HardwareKeyRemapper()

        val remapped = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapped.keyCode)
        val event = remapped.event ?: error("Expected remapped event")
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, event.keyCode)
        assertEquals(shiftRightMeta, event.metaState)
    }

    @Test
    fun enterUp_asShiftRightCarriesNoShiftMeta() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val up = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, up.keyCode)
        assertEquals(0, up.event?.metaState)
    }

    @Test
    fun heldPhysicalEnter_givesOtherKeysShiftMeta() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            enabled
        )

        assertEquals(KeyEvent.KEYCODE_A, a.keyCode)
        assertEquals(KeyEvent.KEYCODE_A, a.event?.keyCode)
        assertEquals(shiftRightMeta, a.event?.metaState)
        assertTrue(a.event?.isShiftPressed == true)
    }

    @Test
    fun heldPhysicalShiftRight_clearsShiftMetaOnOtherKeys() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta),
            enabled
        )

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, shiftRightMeta),
            enabled
        )

        assertEquals(KeyEvent.KEYCODE_A, a.keyCode)
        assertEquals(0, a.event?.metaState)
        assertTrue(a.event?.isShiftPressed == false)
    }

    @Test
    fun heldPhysicalShiftRight_keepsLeftShiftMeta() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta),
            enabled
        )

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_A,
                shiftLeftMeta or KeyEvent.META_SHIFT_RIGHT_ON
            ),
            enabled
        )

        assertEquals(shiftLeftMeta, a.event?.metaState)
    }

    @Test
    fun release_clearsHeldState() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)

        val a = remapper.remap(KeyEvent.KEYCODE_A, aDown, enabled)

        assertSame(aDown, a.event)
        assertEquals(0, a.event?.metaState)
    }

    @Test
    fun releaseOfPhysicalShiftRight_emitsEnterUpAndClearsState() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta),
            enabled
        )

        val up = remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, 0),
            enabled
        )
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)
        val a = remapper.remap(KeyEvent.KEYCODE_A, aDown, enabled)

        assertEquals(KeyEvent.KEYCODE_ENTER, up.keyCode)
        assertEquals(KeyEvent.ACTION_UP, up.event?.action)
        assertSame(aDown, a.event)
    }

    @Test
    fun repeatOfHeldKey_keepsRemapAndMeta() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val repeat = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, repeatCount = 3),
            enabled
        )

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, repeat.keyCode)
        assertEquals(3, repeat.event?.repeatCount)
        assertEquals(shiftRightMeta, repeat.event?.metaState)
    }

    @Test
    fun toggleTurnedOffMidHold_stillReleasesRemappedKey() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val up = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            emptySet()
        )
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)
        val a = remapper.remap(KeyEvent.KEYCODE_A, aDown, emptySet())

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, up.keyCode)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, up.event?.keyCode)
        assertSame(aDown, a.event)
    }

    @Test
    fun toggleTurnedOffMidHold_keepsHeldMetaUntilRelease() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            emptySet()
        )

        assertEquals(shiftRightMeta, a.event?.metaState)
    }

    @Test
    fun toggleTurnedOnMidHold_releaseOfUnseenDownStaysIdentity() {
        val remapper = HardwareKeyRemapper()
        val enterUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)

        val up = remapper.remap(KeyEvent.KEYCODE_ENTER, enterUp, enabled)

        assertEquals(KeyEvent.KEYCODE_ENTER, up.keyCode)
        assertSame(enterUp, up.event)
    }

    @Test
    fun unrelatedKeys_areUntouched() {
        val remapper = HardwareKeyRemapper()
        val keys = listOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE
        )
        for (keyCode in keys) {
            val down = keyEvent(KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = keyEvent(KeyEvent.ACTION_UP, keyCode, 0)

            val remappedDown = remapper.remap(keyCode, down, enabled)
            val remappedUp = remapper.remap(keyCode, up, enabled)

            assertEquals(keyCode, remappedDown.keyCode)
            assertSame(down, remappedDown.event)
            assertEquals(keyCode, remappedUp.keyCode)
            assertSame(up, remappedUp.event)
        }
    }

    @Test
    fun nullEvent_stillRemapsKeyCode() {
        val remapper = HardwareKeyRemapper()

        val shift = remapper.remap(KeyEvent.KEYCODE_SHIFT_RIGHT, null, enabled)
        val enter = remapper.remap(KeyEvent.KEYCODE_ENTER, null, enabled)
        val a = remapper.remap(KeyEvent.KEYCODE_A, null, enabled)
        val off = remapper.remap(KeyEvent.KEYCODE_ENTER, null, emptySet())

        assertEquals(KeyEvent.KEYCODE_ENTER, shift.keyCode)
        assertNull(shift.event)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, enter.keyCode)
        assertNull(enter.event)
        assertEquals(KeyEvent.KEYCODE_A, a.keyCode)
        assertEquals(KeyEvent.KEYCODE_ENTER, off.keyCode)
    }

    @Test
    fun resetForgetsHeldKeys() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        remapper.reset()
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)

        assertSame(aDown, remapper.remap(KeyEvent.KEYCODE_A, aDown, enabled).event)
    }

    @Test
    fun registry_hasUniqueIdsAndWellFormedMappings() {
        val options = HardwareKeyRemapRegistry.options
        assertTrue(options.isNotEmpty())
        assertEquals(options.size, options.map { it.id }.toSet().size)
        for (option in options) {
            assertTrue(option.id.isNotBlank())
            assertTrue(option.titleRes != 0)
            assertTrue(option.summaryRes != 0)
            assertTrue(option.mappings.isNotEmpty())
            assertEquals(
                option.mappings.size,
                option.mappings.map { it.fromKeyCode }.toSet().size
            )
            for (mapping in option.mappings) {
                assertTrue(mapping.fromKeyCode != mapping.toKeyCode)
                assertTrue(mapping.fromKeyCode != KeyEvent.KEYCODE_UNKNOWN)
                assertTrue(mapping.toKeyCode != KeyEvent.KEYCODE_UNKNOWN)
            }
            assertSame(option, HardwareKeyRemapRegistry.byId(option.id))
        }
        assertNull(HardwareKeyRemapRegistry.byId("no_such_remap"))
    }

    @Test
    fun registry_swapShiftRightEnter_mapsBothDirections() {
        val option = HardwareKeyRemapRegistry.byId(swapId) ?: error("Missing swap option")

        assertEquals(
            setOf(
                KeyMapping(KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ENTER),
                KeyMapping(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SHIFT_RIGHT)
            ),
            option.mappings.toSet()
        )
    }

    @Test
    fun swapHelper_buildsBothDirections() {
        assertEquals(
            listOf(
                KeyMapping(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B),
                KeyMapping(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_A)
            ),
            HardwareKeyRemapRegistry.swap(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B)
        )
    }

    @Test
    fun syntheticSwap_worksWithoutCodeChanges() {
        val option = KeyRemapOption(
            id = "swap_a_b",
            titleRes = 1,
            summaryRes = 1,
            mappings = HardwareKeyRemapRegistry.swap(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B)
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("swap_a_b")

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )
        val b = remapper.remap(
            KeyEvent.KEYCODE_B,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0),
            ids
        )
        val disabled = HardwareKeyRemapper(listOf(option)).remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            setOf("swap_shift_right_enter")
        )

        assertEquals(KeyEvent.KEYCODE_B, a.keyCode)
        assertEquals(KeyEvent.KEYCODE_B, a.event?.keyCode)
        assertEquals(KeyEvent.KEYCODE_A, b.keyCode)
        assertEquals(KeyEvent.KEYCODE_A, b.event?.keyCode)
        // Non-modifier targets never touch the meta state.
        assertEquals(0, a.event?.metaState)
        assertEquals(KeyEvent.KEYCODE_A, disabled.keyCode)
    }

    @Test
    fun syntheticModifierSwap_usesModifierTableWithoutCodeChanges() {
        val option = KeyRemapOption(
            id = "swap_ctrl_left_caps",
            titleRes = 1,
            summaryRes = 1,
            mappings = HardwareKeyRemapRegistry.swap(
                KeyEvent.KEYCODE_CAPS_LOCK,
                KeyEvent.KEYCODE_CTRL_LEFT
            )
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("swap_ctrl_left_caps")
        val ctrlMeta = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON

        remapper.remap(
            KeyEvent.KEYCODE_CAPS_LOCK,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK, 0),
            ids
        )
        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )

        assertEquals(ctrlMeta, a.event?.metaState)
    }

    @Test
    fun bothModifiersHeld_eachSeesTheOthersMeta() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta),
            enabled
        )
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, shiftRightMeta),
            enabled
        )

        // Physical Shift_R acts as Enter, physical Enter acts as Shift_R: net Shift stays on.
        assertEquals(shiftRightMeta, a.event?.metaState)
    }

    @Test
    fun deviceSpecific_appliesEnabledRemapOnlyWhenPassed() {
        val shiftDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta)

        val off = DeviceSpecific.remapHardwareKeyEvent(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftDown)
        val on = DeviceSpecific.remapHardwareKeyEvent(
            keyCode = KeyEvent.KEYCODE_SHIFT_RIGHT,
            event = shiftDown,
            enabledRemapIds = enabled
        )

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, off.keyCode)
        assertSame(shiftDown, off.event)
        assertEquals(KeyEvent.KEYCODE_ENTER, on.keyCode)
        assertEquals(KeyEvent.KEYCODE_ENTER, on.event?.keyCode)
    }

    @Test
    fun deviceSpecific_q25ShiftRightBecomesCtrlBeforeGenericRemap() {
        val input = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.META_SHIFT_RIGHT_ON
        )

        val remapped = DeviceSpecific.remapHardwareKeyEvent(
            keyCode = KeyEvent.KEYCODE_SHIFT_RIGHT,
            event = input,
            physicalProfileOverride = "Q25",
            enabledRemapIds = enabled
        )

        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, remapped.keyCode)
        assertNotNull(remapped.event)
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, remapped.event?.keyCode)
    }

    private fun keyEvent(
        action: Int,
        keyCode: Int,
        metaState: Int,
        repeatCount: Int = 0,
        downTime: Long = 0L,
        eventTime: Long = 0L,
        scanCode: Int = 0,
        deviceId: Int = 0,
        flags: Int = 0
    ): KeyEvent = KeyEvent(
        downTime,
        eventTime,
        action,
        keyCode,
        repeatCount,
        metaState,
        deviceId,
        scanCode,
        flags,
        android.view.InputDevice.SOURCE_KEYBOARD
    )
}
