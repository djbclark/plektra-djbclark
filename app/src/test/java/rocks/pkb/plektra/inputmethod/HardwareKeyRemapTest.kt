package rocks.pkb.plektra.inputmethod

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The held-key engine: [HardwareKeyRemapper] over compiled rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HardwareKeyRemapTest {

    private val shiftRightMeta = KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON
    private val shiftLeftMeta = KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON
    private val altRightMeta = KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
    private val ctrlLeftMeta = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON

    /** The Shift/Enter swap as the settings preset writes it. */
    private val swap = rules(
        KeyRemapRule(KeySource.scan(SCAN_SHIFT_RIGHT, KeyEvent.KEYCODE_SHIFT_RIGHT), KeyEvent.KEYCODE_ENTER),
        KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_SHIFT_RIGHT), KeyEvent.KEYCODE_ENTER),
        KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER), KeyEvent.KEYCODE_SHIFT_RIGHT)
    )

    private val cancelled = ArrayList<Int>()
    private val remapper = HardwareKeyRemapper { cancelled.add(it) }
    private var clock = 1_000L

    @Test
    fun noRules_leavesEveryEventAsItIs() {
        val shiftDown = down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT)
        val letter = down(KeyEvent.KEYCODE_A, shiftRightMeta)

        assertUnchanged(shiftDown, remapper.remap(shiftDown.keyCode, shiftDown, KeyRemapRules.NONE))
        assertUnchanged(letter, remapper.remap(letter.keyCode, letter, KeyRemapRules.NONE))
        assertTrue(remapper.isIdle)
    }

    @Test
    fun rightShift_actsAsEnterWithoutItsShiftMeta() {
        val input = KeyEvent(
            100L, 120L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_RIGHT, 0, shiftRightMeta,
            7, SCAN_SHIFT_RIGHT, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
        )

        val pressed = remapper.remap(input.keyCode, input, swap)

        assertEquals(KeyRemapResult.Change.KEY, pressed.change)
        assertEquals(KeyEvent.KEYCODE_ENTER, pressed.keyCode)
        assertEquals(KeyEvent.KEYCODE_ENTER, pressed.event.keyCode)
        assertEquals(0, pressed.event.metaState)
        assertEquals(KeyEvent.ACTION_DOWN, pressed.event.action)
        assertEquals(100L, pressed.event.downTime)
        assertEquals(120L, pressed.event.eventTime)
        assertEquals(SCAN_SHIFT_RIGHT, pressed.event.scanCode)
        assertEquals(7, pressed.event.deviceId)
        assertEquals(KeyEvent.FLAG_FROM_SYSTEM, pressed.event.flags)
        assertEquals(InputDevice.SOURCE_KEYBOARD, pressed.event.source)

        val released = remap(up(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, SCAN_SHIFT_RIGHT, deviceId = 7), swap)
        assertEquals(KeyEvent.KEYCODE_ENTER, released.keyCode)
        assertEquals(KeyEvent.ACTION_UP, released.event.action)
        assertTrue(remapper.isIdle)
    }

    @Test
    fun enter_actsAsRightShiftAndShiftsTheKeysPressedMeanwhile() {
        val pressed = remap(down(KeyEvent.KEYCODE_ENTER), swap)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, pressed.keyCode)
        assertEquals(shiftRightMeta, pressed.event.metaState)

        val letter = remap(down(KeyEvent.KEYCODE_A), swap)
        assertEquals(KeyRemapResult.Change.META, letter.change)
        assertEquals(KeyEvent.KEYCODE_A, letter.keyCode)
        assertEquals(shiftRightMeta, letter.event.metaState)

        val repeat = remap(down(KeyEvent.KEYCODE_ENTER, repeat = 3), swap)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, repeat.keyCode)
        assertEquals(3, repeat.event.repeatCount)

        val released = remap(up(KeyEvent.KEYCODE_ENTER), swap)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, released.keyCode)
        assertEquals(0, released.event.metaState)
        val after = down(KeyEvent.KEYCODE_A)
        assertUnchanged(after, remap(after, swap))
    }

    @Test
    fun scanCodeNamesTheKey_whateverKeyCodeTheVendorMakesItSend() {
        // The Titan 2's Shortcut keys > Key setting: right Shift (scan 54) arrives as Alt.
        val pressed = remap(down(KeyEvent.KEYCODE_ALT_RIGHT, altRightMeta, SCAN_SHIFT_RIGHT), swap)
        assertEquals(KeyEvent.KEYCODE_ENTER, pressed.keyCode)
        assertEquals(0, pressed.event.metaState)

        val letter = remap(down(KeyEvent.KEYCODE_A, altRightMeta), swap)
        assertEquals(0, letter.event.metaState)
    }

    @Test
    fun twoKeysThatSendOneKeyCode_areTwoKeys() {
        val scanOnly = rules(KeyRemapRule(KeySource.scan(SCAN_SHIFT_RIGHT), KeyEvent.KEYCODE_ENTER))
        val other = down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, scanCode = 100)

        assertEquals(KeyEvent.KEYCODE_ENTER, remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT), scanOnly).keyCode)
        // The other key is a real Shift, held too: its bit stays although the remapped key sends it as well.
        assertUnchanged(other, remap(other, scanOnly))
        val letter = down(KeyEvent.KEYCODE_A, shiftRightMeta)
        assertUnchanged(letter, remap(letter, scanOnly))

        // Releasing the real Shift leaves the remapped key held: its bit is removed again.
        remap(up(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, scanCode = 100), scanOnly)
        assertEquals(0, remap(down(KeyEvent.KEYCODE_B, shiftRightMeta), scanOnly).event.metaState)
        assertEquals(KeyEvent.KEYCODE_ENTER, remap(up(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, SCAN_SHIFT_RIGHT), scanOnly).keyCode)
    }

    @Test
    fun heldRemappedShift_keepsTheOtherShiftsMeta() {
        remap(down(KeyEvent.KEYCODE_SHIFT_LEFT, shiftLeftMeta, scanCode = 42), swap)
        remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftLeftMeta or shiftRightMeta, SCAN_SHIFT_RIGHT), swap)

        val letter = remap(down(KeyEvent.KEYCODE_A, shiftLeftMeta or shiftRightMeta), swap)

        assertEquals(shiftLeftMeta, letter.event.metaState)
    }

    @Test
    fun anotherKeyboardsModifier_keepsItsMetaWhileARemappedKeySendsTheSameBit() {
        remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT, deviceId = 2), KeyRemapRules.NONE)
        remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT, deviceId = 1), swap)

        val letter = down(KeyEvent.KEYCODE_A, shiftRightMeta, deviceId = 2)

        assertUnchanged(letter, remap(letter, KeyRemapRules.NONE))
    }

    @Test
    fun aHoldKeepsWhatItsDownChose_whenTheRulesChange() {
        remap(down(KeyEvent.KEYCODE_ENTER), swap)

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remap(down(KeyEvent.KEYCODE_ENTER, repeat = 1), KeyRemapRules.NONE).keyCode)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remap(up(KeyEvent.KEYCODE_ENTER), KeyRemapRules.NONE).keyCode)
        assertTrue(remapper.isIdle)
    }

    @Test
    fun repeatAndUpWithoutADown_stayAsTheyAre() {
        val repeat = down(KeyEvent.KEYCODE_ENTER, repeat = 2)
        val release = up(KeyEvent.KEYCODE_ENTER)

        assertUnchanged(repeat, remap(repeat, swap))
        assertUnchanged(release, remap(release, swap))
        assertTrue(remapper.isIdle)
    }

    @Test
    fun reset_turnsAHeldKeyIntoATombstoneThatSwallowsTheRestOfItsPress() {
        remap(down(KeyEvent.KEYCODE_ENTER), swap)

        assertTrue(remapper.reset())
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_RIGHT), cancelled)
        assertFalse(remapper.isIdle)

        val repeat = down(KeyEvent.KEYCODE_ENTER, repeat = 1)
        val stale = remap(repeat, swap)
        assertTrue(stale.isStale)
        assertSame(repeat, stale.event)
        // The key no longer shifts anything.
        val letter = down(KeyEvent.KEYCODE_A)
        assertUnchanged(letter, remap(letter, swap))
        assertTrue(remap(up(KeyEvent.KEYCODE_ENTER), swap).isStale)
        assertTrue(remapper.isIdle)

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remap(down(KeyEvent.KEYCODE_ENTER), swap).keyCode)
    }

    @Test
    fun tombstoneOfAModifierKey_keepsItsBitOutOfTheMetaState() {
        remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT), swap)
        remapper.reset()

        // The key is still down, so the framework still reports its Shift on every event.
        val letter = remap(down(KeyEvent.KEYCODE_A, shiftRightMeta), swap)

        assertEquals(KeyRemapResult.Change.META, letter.change)
        assertEquals(KeyEvent.KEYCODE_A, letter.keyCode)
        assertEquals(0, letter.event.metaState)
    }

    @Test
    fun reset_leavesUnremappedModifiersAlone() {
        remap(down(KeyEvent.KEYCODE_SHIFT_LEFT, shiftLeftMeta, scanCode = 42), swap)

        assertFalse(remapper.reset())
        assertTrue(cancelled.isEmpty())
        val release = up(KeyEvent.KEYCODE_SHIFT_LEFT, 0, scanCode = 42)
        assertUnchanged(release, remap(release, swap))
    }

    @Test
    fun tombstone_endsWithTheNextPressOfItsKey() {
        remap(down(KeyEvent.KEYCODE_ENTER), swap)
        remapper.reset()

        // The UP was lost; the next press is a new one.
        val pressed = remap(down(KeyEvent.KEYCODE_ENTER), swap)

        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, pressed.keyCode)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remap(up(KeyEvent.KEYCODE_ENTER), swap).keyCode)
        assertTrue(remapper.isIdle)
    }

    @Test
    fun lostUpOfAModifierKey_showsInTheMetaStateAndEndsItsHold() {
        val altAsSym = rules(KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ALT_RIGHT), KeyEvent.KEYCODE_SYM))
        remap(down(KeyEvent.KEYCODE_ALT_RIGHT, altRightMeta), altAsSym)
        assertEquals(KeyEvent.META_SYM_ON, remap(down(KeyEvent.KEYCODE_A, altRightMeta), altAsSym).event.metaState)
        remap(up(KeyEvent.KEYCODE_A, altRightMeta), altAsSym)

        // No Alt bit any more: the key was released and its UP never arrived.
        val letter = down(KeyEvent.KEYCODE_B)
        assertUnchanged(letter, remap(letter, altAsSym))
        assertEquals(listOf(KeyEvent.KEYCODE_SYM), cancelled)

        // Had the guess been wrong, the key's late events would be swallowed, not passed on raw.
        assertTrue(remap(down(KeyEvent.KEYCODE_ALT_RIGHT, altRightMeta, repeat = 1), altAsSym).isStale)
        // The next press starts over.
        assertEquals(KeyEvent.KEYCODE_SYM, remap(down(KeyEvent.KEYCODE_ALT_RIGHT, altRightMeta), altAsSym).keyCode)
    }

    @Test
    fun lostUpRule_needsABitTheKeySetItselfAndALaterEventOfAnotherKey() {
        // Enter sets no meta bit, so nothing can tell that its UP was lost.
        remap(down(KeyEvent.KEYCODE_ENTER), swap)
        assertEquals(shiftRightMeta, remap(down(KeyEvent.KEYCODE_A), swap).event.metaState)
        remap(up(KeyEvent.KEYCODE_ENTER), swap)

        // A vendor-rewritten key that came down without its bit never loses it.
        remap(down(KeyEvent.KEYCODE_ALT_RIGHT, 0, SCAN_SHIFT_RIGHT), swap)
        remap(down(KeyEvent.KEYCODE_B), swap)
        assertEquals(KeyEvent.KEYCODE_ENTER, remap(up(KeyEvent.KEYCODE_ALT_RIGHT, 0, SCAN_SHIFT_RIGHT), swap).keyCode)

        // An event older than the press (a replayed one) says nothing about it.
        val replayed = down(KeyEvent.KEYCODE_C)
        remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT), swap)
        remap(replayed, swap)
        assertEquals(KeyEvent.KEYCODE_ENTER, remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT, repeat = 1), swap).keyCode)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun newPressOverAHeldKey_replacesItAndLooksTheRuleUpAgain() {
        val enterAsCtrl = rules(KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER), KeyEvent.KEYCODE_CTRL_LEFT))
        remap(down(KeyEvent.KEYCODE_ENTER), swap)

        // The UP was lost and the rules changed since.
        val pressed = remap(down(KeyEvent.KEYCODE_ENTER), enterAsCtrl)

        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, pressed.keyCode)
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_RIGHT), cancelled)
        assertEquals(ctrlLeftMeta, remap(down(KeyEvent.KEYCODE_A), enterAsCtrl).event.metaState)
        remap(up(KeyEvent.KEYCODE_ENTER), enterAsCtrl)
        assertTrue(remapper.isIdle)
    }

    @Test
    fun clearDevice_forgetsThatKeyboardsKeysOnly() {
        remap(down(KeyEvent.KEYCODE_ENTER, deviceId = 1), swap)
        remap(down(KeyEvent.KEYCODE_ENTER, deviceId = 2), swap)
        remap(down(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRightMeta, SCAN_SHIFT_RIGHT, deviceId = 3), swap)
        remapper.reset()
        cancelled.clear()
        remap(down(KeyEvent.KEYCODE_ENTER, deviceId = 1), swap)

        assertTrue(remapper.clearDevice(1))
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_RIGHT), cancelled)
        assertFalse(remapper.clearDevice(3))

        // Device 1 and 3: no hold, no tombstone. Device 2: still a tombstone.
        val release = up(KeyEvent.KEYCODE_ENTER, deviceId = 1)
        assertUnchanged(release, remap(release, swap))
        val rawRelease = up(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, SCAN_SHIFT_RIGHT, deviceId = 3)
        assertUnchanged(rawRelease, remap(rawRelease, swap))
        assertTrue(remap(up(KeyEvent.KEYCODE_ENTER, deviceId = 2), swap).isStale)
    }

    @Test
    fun release_dropsAHoldOrTombstoneWithoutACallback() {
        val enter = down(KeyEvent.KEYCODE_ENTER)
        remap(enter, swap)
        remapper.release(HardwareKeyRemapper.physicalKeyId(enter.keyCode, enter))
        assertTrue(remapper.isIdle)

        remap(down(KeyEvent.KEYCODE_ENTER), swap)
        remapper.reset()
        cancelled.clear()
        remapper.release(HardwareKeyRemapper.physicalKeyId(enter.keyCode, enter))

        assertTrue(remapper.isIdle)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun peekKeyCode_tellsTheTargetAndChangesNothing() {
        val enter = down(KeyEvent.KEYCODE_ENTER)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapper.peekKeyCode(enter.keyCode, enter, swap))
        assertTrue(remapper.isIdle)

        remap(enter, swap)
        // Held: the DOWN's choice, whatever the rules say now.
        val release = up(KeyEvent.KEYCODE_ENTER)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapper.peekKeyCode(release.keyCode, release, KeyRemapRules.NONE))
        remapper.reset()
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapper.peekKeyCode(release.keyCode, release, KeyRemapRules.NONE))
        // Not held: a repeat or UP is what it is.
        val other = up(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, SCAN_SHIFT_RIGHT)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, remapper.peekKeyCode(other.keyCode, other, swap))
    }

    @Test
    fun twoSwappedModifiers_eachGiveTheOthersMeta() {
        val altLeftMeta = KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON
        val swapCtrlAlt = rules(
            KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_ALT_LEFT),
            KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ALT_LEFT), KeyEvent.KEYCODE_CTRL_LEFT)
        )

        val ctrl = remap(down(KeyEvent.KEYCODE_CTRL_LEFT, ctrlLeftMeta), swapCtrlAlt)
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, ctrl.keyCode)
        assertEquals(altLeftMeta, ctrl.event.metaState)
        assertEquals(altLeftMeta, remap(down(KeyEvent.KEYCODE_A, ctrlLeftMeta), swapCtrlAlt).event.metaState)

        val alt = remap(down(KeyEvent.KEYCODE_ALT_LEFT, ctrlLeftMeta or altLeftMeta), swapCtrlAlt)
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, alt.keyCode)
        assertEquals(ctrlLeftMeta or altLeftMeta, alt.event.metaState)
    }

    @Test
    fun sym_isAModifierLikeTheOthers() {
        val enterAsSym = rules(
            KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER), KeyEvent.KEYCODE_SYM),
            KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_SYM), KeyEvent.KEYCODE_ENTER)
        )

        // As a target: its bit is on the key itself and on the keys pressed while it is held.
        val pressed = remap(down(KeyEvent.KEYCODE_ENTER), enterAsSym)
        assertEquals(KeyEvent.KEYCODE_SYM, pressed.keyCode)
        assertEquals(KeyEvent.META_SYM_ON, pressed.event.metaState)
        assertEquals(KeyEvent.META_SYM_ON, remap(down(KeyEvent.KEYCODE_A), enterAsSym).event.metaState)
        assertEquals(0, remap(up(KeyEvent.KEYCODE_ENTER), enterAsSym).event.metaState)

        // As a source: the physical Sym key no longer sets it.
        val sym = remap(down(KeyEvent.KEYCODE_SYM, KeyEvent.META_SYM_ON), enterAsSym)
        assertEquals(KeyEvent.KEYCODE_ENTER, sym.keyCode)
        assertEquals(0, sym.event.metaState)
        assertEquals(0, remap(down(KeyEvent.KEYCODE_B, KeyEvent.META_SYM_ON), enterAsSym).event.metaState)

        assertTrue(HardwareKeyRemapper.isModifierKeyCode(KeyEvent.KEYCODE_SYM))
        assertTrue(HardwareKeyRemapper.isModifierKeyCode(KeyEvent.KEYCODE_META_RIGHT))
        assertFalse(HardwareKeyRemapper.isModifierKeyCode(KeyEvent.KEYCODE_CAPS_LOCK))
        assertFalse(HardwareKeyRemapper.isModifierKeyCode(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun physicalKeyId_isDeviceAndScanCode_orKeyCodeWithoutOne() {
        val key = HardwareKeyRemapper.physicalKeyId(KeyEvent.KEYCODE_SHIFT_RIGHT, down(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, SCAN_SHIFT_RIGHT, deviceId = 4))

        // The vendor may rewrite the key code; the scan code is the key.
        assertEquals(key, HardwareKeyRemapper.physicalKeyId(KeyEvent.KEYCODE_ALT_RIGHT, up(KeyEvent.KEYCODE_ALT_RIGHT, 0, SCAN_SHIFT_RIGHT, deviceId = 4)))
        assertNotEquals(key, HardwareKeyRemapper.physicalKeyId(KeyEvent.KEYCODE_SHIFT_RIGHT, down(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, SCAN_SHIFT_RIGHT, deviceId = 5)))
        assertNotEquals(key, HardwareKeyRemapper.physicalKeyId(KeyEvent.KEYCODE_SHIFT_RIGHT, down(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, scanCode = 100, deviceId = 4)))
        // A scan code and a key code of the same number are different keys.
        assertNotEquals(key, HardwareKeyRemapper.physicalKeyId(SCAN_SHIFT_RIGHT, down(SCAN_SHIFT_RIGHT, deviceId = 4)))
        assertEquals(4, HardwareKeyRemapper.deviceIdOf(key))
        assertEquals(-1, HardwareKeyRemapper.deviceIdOf(HardwareKeyRemapper.physicalKeyId(KeyEvent.KEYCODE_A, down(KeyEvent.KEYCODE_A, deviceId = -1))))
    }

    @Test
    fun onlyKeysOfARealKeyboard_arePhysicalKeyEvents() {
        assertTrue(HardwareKeyRemapper.isPhysicalKeyEvent(down(KeyEvent.KEYCODE_ENTER, deviceId = 0)))
        assertFalse(HardwareKeyRemapper.isPhysicalKeyEvent(null))
        // Injected by adb, a remote-control tool or another app: no device.
        assertFalse(HardwareKeyRemapper.isPhysicalKeyEvent(down(KeyEvent.KEYCODE_ENTER, deviceId = -1)))
        assertFalse(HardwareKeyRemapper.isPhysicalKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        val onScreen = KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, 1, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD
        )
        assertFalse(HardwareKeyRemapper.isPhysicalKeyEvent(onScreen))
    }

    private fun rules(vararg rules: KeyRemapRule): KeyRemapRules =
        KeyRemapRules.compile(rules.toList()).also { assertEquals(rules.size, it.rules.size) }

    private fun remap(event: KeyEvent, rules: KeyRemapRules): KeyRemapResult =
        remapper.remap(event.keyCode, event, rules)

    private fun assertUnchanged(event: KeyEvent, result: KeyRemapResult) {
        assertEquals(KeyRemapResult.Change.NONE, result.change)
        assertEquals(event.keyCode, result.keyCode)
        assertSame(event, result.event)
    }

    private fun down(
        keyCode: Int,
        metaState: Int = 0,
        scanCode: Int = 0,
        deviceId: Int = 1,
        repeat: Int = 0
    ): KeyEvent = event(KeyEvent.ACTION_DOWN, keyCode, metaState, scanCode, deviceId, repeat)

    private fun up(keyCode: Int, metaState: Int = 0, scanCode: Int = 0, deviceId: Int = 1): KeyEvent =
        event(KeyEvent.ACTION_UP, keyCode, metaState, scanCode, deviceId, 0)

    private fun event(action: Int, keyCode: Int, metaState: Int, scanCode: Int, deviceId: Int, repeat: Int): KeyEvent {
        clock += 10L
        return KeyEvent(
            clock, clock, action, keyCode, repeat, metaState, deviceId, scanCode,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
        )
    }

    private companion object {
        const val SCAN_SHIFT_RIGHT = 54
    }
}
