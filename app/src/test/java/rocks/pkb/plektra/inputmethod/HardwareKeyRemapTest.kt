package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun enabledSetShrinks_keepsHeldEntryUntilItsRelease() {
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
        val up = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            emptySet()
        )
        val enterAgain = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
        val enter = remapper.remap(KeyEvent.KEYCODE_ENTER, enterAgain, emptySet())

        // The press began under the option, so it keeps its Shift until the release maps back.
        assertEquals(shiftRightMeta, a.event?.metaState)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, up.keyCode)
        assertEquals(0, up.event?.metaState)
        // The next press no longer remaps.
        assertEquals(KeyEvent.KEYCODE_ENTER, enter.keyCode)
        assertSame(enterAgain, enter.event)
    }

    @Test
    fun repeatWithoutHeldEntry_isNotRemappedAndStartsNoHold() {
        val remapper = HardwareKeyRemapper()
        val repeat = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, repeatCount = 2)

        val remappedRepeat = remapper.remap(KeyEvent.KEYCODE_ENTER, repeat, enabled)
        val peeked = remapper.peekKeyCode(KeyEvent.KEYCODE_ENTER, repeat, enabled)
        val up = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
        val remappedUp = remapper.remap(KeyEvent.KEYCODE_ENTER, up, enabled)
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)

        // The initial DOWN never reached the remapper, so a repeat must not pair with a later UP.
        assertEquals(KeyEvent.KEYCODE_ENTER, remappedRepeat.keyCode)
        assertSame(repeat, remappedRepeat.event)
        assertEquals(KeyEvent.KEYCODE_ENTER, peeked)
        assertSame(up, remappedUp.event)
        assertSame(aDown, remapper.remap(KeyEvent.KEYCODE_A, aDown, enabled).event)
    }

    @Test
    fun release_dropsHeldEntryAndTransformsNothing() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        remapper.release(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
        )
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)
        val a = remapper.remap(KeyEvent.KEYCODE_A, aDown, enabled)

        assertSame(aDown, a.event)
        assertFalse(a.transformed)
    }

    @Test
    fun release_leavesOtherDevicesAndKeysHeld() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, deviceId = 1),
            enabled
        )

        remapper.release(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, deviceId = 2)
        )
        remapper.release(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, deviceId = 1)
        )
        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, deviceId = 1),
            enabled
        )

        assertEquals(shiftRightMeta, a.event?.metaState)
    }

    @Test
    fun enabledSetGrowing_keepsHeldState() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            enabled + "some_other_remap"
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
    fun reset_leavesLaterEventsUntransformed() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        remapper.reset()

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            enabled
        )

        assertFalse(a.transformed)
    }

    @Test
    fun metaOnlyChange_isTransformedWithoutKeyCodeRemap() {
        val remapper = HardwareKeyRemapper()
        val noRemap = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            enabled
        )
        val enter = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val dpad = remapper.remap(
            KeyEvent.KEYCODE_DPAD_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0),
            enabled
        )

        assertFalse(noRemap.transformed)
        assertFalse(noRemap.keyCodeRemapped)
        assertTrue(enter.transformed)
        assertTrue(enter.keyCodeRemapped)
        assertTrue(dpad.transformed)
        assertFalse(dpad.keyCodeRemapped)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, dpad.keyCode)
        assertEquals(shiftRightMeta, dpad.event?.metaState)
    }

    @Test
    fun heldPhysicalShiftRight_stripsShiftFromOtherKeysAsTransformed() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta),
            enabled
        )

        val dpad = remapper.remap(
            KeyEvent.KEYCODE_DPAD_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, shiftRightMeta),
            enabled
        )

        assertTrue(dpad.transformed)
        assertFalse(dpad.keyCodeRemapped)
        assertEquals(0, dpad.event?.metaState)
    }

    @Test
    fun keyUpAfterMetaOnlyChange_isTransformedToo() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        val up = remapper.remap(
            KeyEvent.KEYCODE_DPAD_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0),
            enabled
        )

        assertTrue(up.transformed)
        assertEquals(shiftRightMeta, up.event?.metaState)
    }

    @Test
    fun heldState_isPerDevice() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, deviceId = 1),
            enabled
        )
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, deviceId = 2),
            enabled
        )

        val upFirst = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, deviceId = 1),
            enabled
        )
        val aWhileSecondHeld = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, deviceId = 1),
            enabled
        )
        val upSecond = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, deviceId = 2),
            enabled
        )
        val aAfterBoth = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, deviceId = 1),
            enabled
        )

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, upFirst.keyCode)
        // Releasing keyboard 1's Enter must not clear keyboard 2's press.
        assertEquals(shiftRightMeta, aWhileSecondHeld.event?.metaState)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, upSecond.keyCode)
        assertFalse(aAfterBoth.transformed)
    }

    @Test
    fun clearDevice_dropsOnlyThatDevicesHeldKeys() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, deviceId = 1),
            enabled
        )
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, deviceId = 2),
            enabled
        )

        remapper.clearDevice(1)

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, deviceId = 2),
            enabled
        )
        val up2 = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, deviceId = 2),
            enabled
        )
        // Keyboard 2 is released, so nothing is held: keyboard 1's forgotten press stays identity.
        val strayUp = keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, deviceId = 1)
        val up1 = remapper.remap(KeyEvent.KEYCODE_ENTER, strayUp, enabled)

        assertEquals(shiftRightMeta, a.event?.metaState)
        assertEquals(KeyEvent.KEYCODE_ENTER, up1.keyCode)
        assertSame(strayUp, up1.event)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, up2.keyCode)
    }

    @Test
    fun nullEvent_usesSentinelDeviceAndLeavesNoHeldState() {
        val remapper = HardwareKeyRemapper()
        val enter = remapper.remap(KeyEvent.KEYCODE_ENTER, null, enabled)

        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)
        val a = remapper.remap(KeyEvent.KEYCODE_A, aDown, enabled)

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, enter.keyCode)
        assertTrue(enter.transformed)
        assertTrue(enter.keyCodeRemapped)
        assertSame(aDown, a.event)
    }

    @Test
    fun peekKeyCode_predictsRemapWithoutChangingState() {
        val remapper = HardwareKeyRemapper()
        val enterDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)

        val peeked = remapper.peekKeyCode(KeyEvent.KEYCODE_ENTER, enterDown, enabled)
        val aDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)
        val a = remapper.remap(KeyEvent.KEYCODE_A, aDown, enabled)
        remapper.remap(KeyEvent.KEYCODE_ENTER, enterDown, enabled)
        val peekedWhileHeld = remapper.peekKeyCode(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, repeatCount = 1),
            emptySet()
        )
        val peekedUp = remapper.peekKeyCode(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            emptySet()
        )
        val peekedOtherDevice = remapper.peekKeyCode(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, deviceId = 9),
            enabled
        )

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, peeked)
        // The peek recorded no held Enter, so the unrelated key stayed untouched.
        assertSame(aDown, a.event)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, peekedWhileHeld)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, peekedUp)
        // An UP on a device that never pressed it stays identity.
        assertEquals(KeyEvent.KEYCODE_ENTER, peekedOtherDevice)
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
        // Escape stands in for any ordinary key.
        val option = KeyRemapOption(
            id = "swap_ctrl_left_escape",
            titleRes = 1,
            summaryRes = 1,
            mappings = HardwareKeyRemapRegistry.swap(
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_CTRL_LEFT
            )
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("swap_ctrl_left_escape")
        val ctrlMeta = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON

        remapper.remap(
            KeyEvent.KEYCODE_ESCAPE,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0),
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

    @Test
    fun deviceSpecific_q25LeavesTheShiftEnterSwapOff() {
        val enter = DeviceSpecific.remapHardwareKeyEvent(
            keyCode = KeyEvent.KEYCODE_ENTER,
            event = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            physicalProfileOverride = "Q25",
            enabledRemapIds = enabled
        )

        assertEquals(KeyEvent.KEYCODE_ENTER, enter.keyCode)
    }

    @Test
    fun capsLockAsSource_actsAsCtrlAndNeverLeaksTheLockBit() {
        val option = KeyRemapOption(
            id = "caps_as_ctrl",
            titleRes = 1,
            summaryRes = 1,
            mappings = HardwareKeyRemapRegistry.swap(
                KeyEvent.KEYCODE_CAPS_LOCK,
                KeyEvent.KEYCODE_CTRL_LEFT
            )
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("caps_as_ctrl")
        val ctrlMeta = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON
        val caps = KeyEvent.META_CAPS_LOCK_ON

        // The framework toggles the lock bit when the key is released, not when it is pressed.
        val down = remapper.remap(
            KeyEvent.KEYCODE_CAPS_LOCK,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK, 0),
            ids
        )
        val aWhileHeld = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )
        val up = remapper.remap(
            KeyEvent.KEYCODE_CAPS_LOCK,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAPS_LOCK, caps),
            ids
        )
        val aAfterOnePress = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, caps),
            ids
        )
        val aAfterTwoPresses = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )

        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, down.keyCode)
        assertEquals(ctrlMeta, down.event?.metaState)
        assertEquals(ctrlMeta, aWhileHeld.event?.metaState)
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, up.keyCode)
        assertEquals(0, up.event?.metaState)
        assertEquals(0, aAfterOnePress.event?.metaState)
        assertTrue(aAfterOnePress.transformed)
        assertFalse(aAfterTwoPresses.transformed)
    }

    @Test
    fun ctrlAsCapsLockTarget_togglesOnEachDownAndPersistsOnLetters() {
        val option = KeyRemapOption(
            id = "ctrl_as_caps",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CAPS_LOCK))
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("ctrl_as_caps")
        val ctrlMeta = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON
        val caps = KeyEvent.META_CAPS_LOCK_ON

        val firstDown = remapper.remap(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrlMeta),
            ids
        )
        val firstRepeat = remapper.remap(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrlMeta, repeatCount = 1),
            ids
        )
        val firstUp = remapper.remap(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
            ids
        )
        val aWhileOn = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )
        val secondDown = remapper.remap(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrlMeta),
            ids
        )
        remapper.remap(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
            ids
        )
        val aWhileOff = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )

        assertEquals(KeyEvent.KEYCODE_CAPS_LOCK, firstDown.keyCode)
        assertEquals(caps, firstDown.event?.metaState)
        // A held key's repeats keep the one toggle.
        assertEquals(caps, firstRepeat.event?.metaState)
        assertEquals(KeyEvent.KEYCODE_CAPS_LOCK, firstUp.keyCode)
        assertEquals(caps, firstUp.event?.metaState)
        assertEquals(caps, aWhileOn.event?.metaState)
        assertEquals(0, secondDown.event?.metaState)
        assertFalse(aWhileOff.transformed)
    }

    @Test
    fun remappedLockState_endsWithResetAndWithItsOption() {
        val option = KeyRemapOption(
            id = "ctrl_as_caps",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CAPS_LOCK))
        )
        val ids = setOf("ctrl_as_caps")
        fun pressAndRelease(remapper: HardwareKeyRemapper) {
            remapper.remap(
                KeyEvent.KEYCODE_CTRL_LEFT,
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0),
                ids
            )
            remapper.remap(
                KeyEvent.KEYCODE_CTRL_LEFT,
                keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
                ids
            )
        }
        val afterReset = HardwareKeyRemapper(listOf(option))
        pressAndRelease(afterReset)
        afterReset.reset()
        val afterDisable = HardwareKeyRemapper(listOf(option))
        pressAndRelease(afterDisable)

        val aAfterReset = afterReset.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )
        val aAfterDisable = afterDisable.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            emptySet()
        )

        assertFalse(aAfterReset.transformed)
        assertFalse(aAfterDisable.transformed)
    }

    @Test
    fun peekRemappedKeyCode_seesDeviceNormalizationAndUserRemaps() {
        val option = KeyRemapOption(
            id = "m_as_escape",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_ESCAPE))
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("m_as_escape")
        // Scan code 50 is the physical M key; the KEY2 profile reports it as a comma.
        val commaForPhysicalM = keyEvent(
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, 0, scanCode = 50
        )
        val shiftRight = keyEvent(
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.META_SHIFT_RIGHT_ON
        )

        val key2 = DeviceSpecific.peekRemappedKeyCode(
            KeyEvent.KEYCODE_COMMA, commaForPhysicalM, "key2", ids, remapper
        )
        val swapOnQ25 = DeviceSpecific.peekRemappedKeyCode(
            KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRight, "Q25", enabled
        )
        val swapElsewhere = DeviceSpecific.peekRemappedKeyCode(
            KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRight, "key2", enabled
        )
        val key2Result = DeviceSpecific.remapHardwareKeyEvent(
            KeyEvent.KEYCODE_COMMA, commaForPhysicalM, "key2", ids, remapper
        )

        assertEquals(KeyEvent.KEYCODE_ESCAPE, key2)
        // The Q25's right Shift is its Ctrl key and user remaps are off there.
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, swapOnQ25)
        assertEquals(KeyEvent.KEYCODE_ENTER, swapElsewhere)
        // The peek predicted what the full remap then produced.
        assertEquals(key2, key2Result.keyCode)
    }

    @Test
    fun supportsUserKeyRemap_isPerOption() {
        val swap = HardwareKeyRemapRegistry.byId(swapId) ?: error("Missing swap option")
        assertFalse(DeviceSpecific.supportsUserKeyRemap(swap, "Q25"))
        assertTrue(DeviceSpecific.supportsUserKeyRemap(swap, "key2"))
        assertTrue(DeviceSpecific.supportsUserKeyRemap(swap, "clicks_power"))

        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz",
            manufacturer = "unihertz",
            model = "Titan 2",
            device = "titan2",
            product = "titan2"
        )
        assertTrue(DeviceSpecific.supportsUserKeyRemap(swap, "auto"))

        val everywhere = KeyRemapOption(
            id = "m_as_escape",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_ESCAPE))
        )
        val notOnKey2 = everywhere.copy(unsupportedModels = setOf(DeviceSpecific.KeyboardModel.KEY2))
        assertTrue(DeviceSpecific.supportsUserKeyRemap(everywhere, "Q25"))
        assertTrue(DeviceSpecific.supportsUserKeyRemap(notOnKey2, "Q25"))
        assertFalse(DeviceSpecific.supportsUserKeyRemap(notOnKey2, "key2"))
    }

    @Test
    fun unsupportedOption_isOffOnItsKeyboardsOnly() {
        val option = KeyRemapOption(
            id = "m_as_escape",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_ESCAPE)),
            unsupportedModels = setOf(DeviceSpecific.KeyboardModel.Q25)
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("m_as_escape")
        val mDown = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M, 0)

        assertEquals(
            KeyEvent.KEYCODE_M,
            DeviceSpecific.peekRemappedKeyCode(KeyEvent.KEYCODE_M, mDown, "Q25", ids, remapper)
        )
        assertEquals(
            KeyEvent.KEYCODE_ESCAPE,
            DeviceSpecific.peekRemappedKeyCode(KeyEvent.KEYCODE_M, mDown, "key2", ids, remapper)
        )
        assertTrue(remapper.supportedIds(ids, DeviceSpecific.KeyboardModel.Q25).isEmpty())
        assertSame(ids, remapper.supportedIds(ids, DeviceSpecific.KeyboardModel.KEY2))
        assertSame(
            remapper.supportedIds(ids, DeviceSpecific.KeyboardModel.Q25),
            remapper.supportedIds(ids, DeviceSpecific.KeyboardModel.Q25)
        )
    }

    @Test
    fun resetDuringHold_swallowsRepeatsAndUpOfAKeyThatActedAsShift() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        remapper.reset()

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            enabled
        )
        val repeat = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, repeatCount = 1),
            enabled
        )
        val up = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        val unrelatedUp = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        // The cut-off hold adds no Shift to other keys.
        assertFalse(a.transformed)
        assertEquals(0, a.event?.metaState)
        assertTrue(repeat.staleRelease)
        assertTrue(up.staleRelease)
        // The tombstone ended with the UP.
        assertFalse(unrelatedUp.staleRelease)
        assertFalse(unrelatedUp.transformed)
    }

    @Test
    fun resetDuringHold_swallowsRepeatsAndUpOfAKeyThatActedAsEnter() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta),
            enabled
        )
        remapper.reset()

        val repeat = remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, repeatCount = 2),
            enabled
        )
        val up = remapper.remap(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, 0),
            enabled
        )

        assertTrue(repeat.staleRelease)
        assertTrue(up.staleRelease)
    }

    @Test
    fun tombstone_endsWithTheNextInitialDownOfItsKey() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        remapper.reset()

        // The UP was lost; a new press starts a normal hold.
        val down = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        val repeat = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, repeatCount = 1),
            enabled
        )
        val up = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        assertFalse(down.staleRelease)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, down.keyCode)
        assertFalse(repeat.staleRelease)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, repeat.keyCode)
        assertFalse(up.staleRelease)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, up.keyCode)
    }

    @Test
    fun tombstone_isPerDeviceAndEndsWithClearDeviceOrRelease() {
        val remapper = HardwareKeyRemapper()
        fun hold(deviceId: Int) = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, deviceId = deviceId),
            enabled
        )
        fun repeat(deviceId: Int) = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, repeatCount = 1, deviceId = deviceId),
            enabled
        )
        hold(7)
        hold(8)
        hold(9)
        remapper.reset()

        remapper.clearDevice(7)
        remapper.release(KeyEvent.KEYCODE_ENTER, keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, deviceId = 8))

        assertFalse(repeat(7).staleRelease)
        assertFalse(repeat(8).staleRelease)
        assertTrue(repeat(9).staleRelease)
        // A device that never held the key has no tombstone.
        assertFalse(repeat(10).staleRelease)
    }

    @Test
    fun resetWithForgetHolds_dropsTheTombstonesToo() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        remapper.reset(forgetHolds = true)

        val repeat = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, repeatCount = 1),
            enabled
        )
        val up = remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )

        assertFalse(repeat.staleRelease)
        assertEquals(KeyEvent.KEYCODE_ENTER, repeat.keyCode)
        assertFalse(up.staleRelease)
        assertEquals(KeyEvent.KEYCODE_ENTER, up.keyCode)
    }

    @Test
    fun peekOfATombstonedKey_stillSeesWhatItWasRemappedTo() {
        val remapper = HardwareKeyRemapper()
        remapper.remap(
            KeyEvent.KEYCODE_ENTER,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
            enabled
        )
        remapper.reset()

        assertEquals(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            remapper.peekKeyCode(
                KeyEvent.KEYCODE_ENTER,
                keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0),
                enabled
            )
        )
        // A new press is looked up afresh.
        assertEquals(
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            remapper.peekKeyCode(
                KeyEvent.KEYCODE_ENTER,
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
                enabled
            )
        )
        assertEquals(
            KeyEvent.KEYCODE_ENTER,
            remapper.peekKeyCode(
                KeyEvent.KEYCODE_ENTER,
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0),
                emptySet()
            )
        )
    }

    @Test
    fun symTarget_addsSymMetaWhileHeld() {
        val option = KeyRemapOption(
            id = "escape_as_sym",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SYM))
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("escape_as_sym")

        val down = remapper.remap(
            KeyEvent.KEYCODE_ESCAPE,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0),
            ids
        )
        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )
        remapper.remap(
            KeyEvent.KEYCODE_ESCAPE,
            keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0),
            ids
        )
        val aAfter = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )

        assertEquals(KeyEvent.KEYCODE_SYM, down.keyCode)
        assertEquals(KeyEvent.META_SYM_ON, down.event?.metaState)
        assertEquals(KeyEvent.META_SYM_ON, a.event?.metaState)
        assertFalse(aAfter.transformed)
    }

    @Test
    fun symSource_losesItsSymMeta() {
        val option = KeyRemapOption(
            id = "sym_as_escape",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_ESCAPE))
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("sym_as_escape")
        remapper.remap(
            KeyEvent.KEYCODE_SYM,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, KeyEvent.META_SYM_ON),
            ids
        )

        val a = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_SYM_ON),
            ids
        )

        assertEquals(0, a.event?.metaState)
        assertTrue(a.transformed)
    }

    @Test
    fun isModifierKeyCode_isTheRemapperModifierTable() {
        val modifiers = listOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_SYM
        )
        for (keyCode in modifiers) assertTrue(HardwareKeyRemapper.isModifierKeyCode(keyCode))
        for (keyCode in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CAPS_LOCK)) {
            assertFalse(HardwareKeyRemapper.isModifierKeyCode(keyCode))
        }
    }

    @Test
    fun toggledLockMeta_isTheRemappedLockKeysInitialDownOnly() {
        val option = KeyRemapOption(
            id = "ctrl_as_caps",
            titleRes = 1,
            summaryRes = 1,
            mappings = listOf(KeyMapping(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CAPS_LOCK))
        )
        val remapper = HardwareKeyRemapper(listOf(option))
        val ids = setOf("ctrl_as_caps")
        fun ctrl(action: Int, repeatCount: Int = 0) = remapper.remap(
            KeyEvent.KEYCODE_CTRL_LEFT,
            keyEvent(action, KeyEvent.KEYCODE_CTRL_LEFT, 0, repeatCount = repeatCount),
            ids
        )

        val down = ctrl(KeyEvent.ACTION_DOWN)
        val repeat = ctrl(KeyEvent.ACTION_DOWN, repeatCount = 1)
        val up = ctrl(KeyEvent.ACTION_UP)
        val secondDown = ctrl(KeyEvent.ACTION_DOWN)
        val realCapsLock = remapper.remap(
            KeyEvent.KEYCODE_CAPS_LOCK,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK, 0),
            emptySet()
        )
        val letter = remapper.remap(
            KeyEvent.KEYCODE_A,
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0),
            ids
        )

        assertEquals(KeyEvent.META_CAPS_LOCK_ON, HardwareKeyRemapper.toggledLockMeta(down))
        assertEquals(0, HardwareKeyRemapper.toggledLockMeta(repeat))
        assertEquals(0, HardwareKeyRemapper.toggledLockMeta(up))
        assertEquals(KeyEvent.META_CAPS_LOCK_ON, HardwareKeyRemapper.toggledLockMeta(secondDown))
        assertEquals(0, HardwareKeyRemapper.toggledLockMeta(realCapsLock))
        assertEquals(0, HardwareKeyRemapper.toggledLockMeta(letter))
    }

    @Test
    fun physicalKeySet_tracksKeysPerDeviceWithoutLimit() {
        val keys = PhysicalKeySet()
        val a = PhysicalKeySet.keyId(1, KeyEvent.KEYCODE_A)
        val sameKeyOtherDevice = PhysicalKeySet.keyId(2, KeyEvent.KEYCODE_A)
        keys.add(a)
        keys.add(a)
        keys.add(sameKeyOtherDevice)
        for (keyCode in 100 until 130) keys.add(PhysicalKeySet.keyId(3, keyCode))

        assertTrue(a in keys)
        assertTrue(keys.remove(a))
        assertFalse(keys.remove(a))
        assertFalse(a in keys)
        assertTrue(sameKeyOtherDevice in keys)
        assertTrue(PhysicalKeySet.keyId(3, 129) in keys)

        keys.removeDevice(3)
        assertFalse(PhysicalKeySet.keyId(3, 100) in keys)
        assertTrue(sameKeyOtherDevice in keys)

        keys.clear()
        assertFalse(sameKeyOtherDevice in keys)
    }

    @Test
    fun injectedKeyEvents_recognizesByTimesActionAndKeyCodeWithinItsBound() {
        val first = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, downTime = 9_001L, eventTime = 9_002L)
        InjectedKeyEvents.record(first)

        // The input connection may hand on a copy.
        assertTrue(InjectedKeyEvents.wasInjected(KeyEvent(first)))
        assertFalse(
            InjectedKeyEvents.wasInjected(
                keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, downTime = 9_001L, eventTime = 9_002L)
            )
        )
        assertFalse(
            InjectedKeyEvents.wasInjected(
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 0, downTime = 9_001L, eventTime = 9_002L)
            )
        )
        assertFalse(
            InjectedKeyEvents.wasInjected(
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, downTime = 9_001L, eventTime = 9_003L)
            )
        )

        for (i in 0 until 16) {
            InjectedKeyEvents.record(
                keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, downTime = 9_100L + i, eventTime = 9_100L + i)
            )
        }
        assertFalse(InjectedKeyEvents.wasInjected(first))
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
