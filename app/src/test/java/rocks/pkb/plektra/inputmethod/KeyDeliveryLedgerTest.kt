package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyDeliveryLedgerTest {

    // The record of a sent DOWN is the caller's own; a name will do here.
    private val ledger = KeyDeliveryLedger<String>()
    private val key = (3L shl 32) or 66L
    private val otherDeviceKey = (4L shl 32) or 66L
    private val none = KeyDeliveryLedger.NONE
    private val released = mutableListOf<String>()

    private fun changeConnection() = ledger.onConnectionChanged { released += it }

    @Test
    fun aKeyTheImeSent_isKnownByTheKeyCodeTheAppSaw_untilItsUp() {
        assertNull(ledger.onInitialDown(key))
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter")

        assertEquals(KeyEvent.KEYCODE_ENTER, ledger.deliveredKeyCode(key))
        assertEquals("enter", ledger.sentByIme(key))
        assertEquals(none, ledger.deliveredKeyCode(otherDeviceKey))

        ledger.onUp(key)
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertNull(ledger.sentByIme(key))
    }

    @Test
    fun aKeyTheSystemDelivered_staysTheSystems_andAKeyTheImeSentStaysTheImes() {
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, ledger.deliveredKeyCode(key))
        assertNull(ledger.sentByIme(key))

        // The IME sent this one: a repeat the system delivers afterwards does not take it over.
        ledger.onInitialDown(otherDeviceKey)
        ledger.onSentByIme(otherDeviceKey, KeyEvent.KEYCODE_DPAD_LEFT, "left")
        ledger.onDeliveredBySystem(otherDeviceKey, KeyEvent.KEYCODE_DPAD_RIGHT, insteadOfRemapped = true)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, ledger.deliveredKeyCode(otherDeviceKey))
        assertEquals("left", ledger.sentByIme(otherDeviceKey))
        assertFalse(ledger.isDeliveredInsteadOfRemapped(otherDeviceKey))
    }

    @Test
    fun aRawKeyDeliveredInPlaceOfARemappedOne_isMarkedSo_untilItsUp() {
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT, insteadOfRemapped = true)
        ledger.onInitialDown(otherDeviceKey)
        ledger.onDeliveredBySystem(otherDeviceKey, KeyEvent.KEYCODE_TAB)

        assertTrue(ledger.isDeliveredInsteadOfRemapped(key))
        assertNull(ledger.sentByIme(key))
        // A key no remap touched is not marked.
        assertFalse(ledger.isDeliveredInsteadOfRemapped(otherDeviceKey))

        ledger.onUp(key)
        assertFalse(ledger.isDeliveredInsteadOfRemapped(key))

        // Another connection changes nothing about it: the app still holds that raw key.
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT, insteadOfRemapped = true)
        changeConnection()
        assertTrue(ledger.isDeliveredInsteadOfRemapped(key))
        // The system delivered it: nothing for the caller to release.
        assertTrue(released.toString(), released.isEmpty())

        // A press the IME then sends itself is no stand-in.
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT, insteadOfRemapped = true)
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter")
        assertFalse(ledger.isDeliveredInsteadOfRemapped(key))
        assertFalse(ledger.isDeliveredBeforeThisConnection(key))
    }

    @Test
    fun aKeyTheSystemDelivered_isWhatTheAppHolds_overEveryConnectionChange_untilItsUp() {
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT, insteadOfRemapped = true)
        ledger.onInitialDown(otherDeviceKey)
        ledger.onSentByIme(otherDeviceKey, KeyEvent.KEYCODE_TAB, "tab")
        assertFalse(ledger.isDeliveredBeforeThisConnection(key))
        assertFalse(ledger.isDeliveredBeforeThisConnection(otherDeviceKey))

        changeConnection()
        changeConnection()

        // The system delivers without a connection: the app holds the key as before.
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, ledger.deliveredKeyCode(key))
        assertTrue(ledger.isDeliveredInsteadOfRemapped(key))
        assertTrue(ledger.isDeliveredBeforeThisConnection(key))
        assertNull(ledger.sentByIme(key))
        // A key the IME sent went through a connection, and ended with it.
        assertEquals(listOf("tab"), released)
        assertEquals(none, ledger.deliveredKeyCode(otherDeviceKey))
        assertFalse(ledger.isDeliveredBeforeThisConnection(otherDeviceKey))

        // A raw repeat the system delivers now belongs to that press: it stays one of before
        // this connection, and keeps what it was delivered as.
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertTrue(ledger.isDeliveredBeforeThisConnection(key))
        assertTrue(ledger.isDeliveredInsteadOfRemapped(key))

        ledger.onUp(key)
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.isDeliveredInsteadOfRemapped(key))
        assertFalse(ledger.isDeliveredBeforeThisConnection(key))

        // The next press is one of this connection.
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, ledger.deliveredKeyCode(key))
        assertFalse(ledger.isDeliveredBeforeThisConnection(key))
        // A new press over a lost UP ends the old one here too.
        changeConnection()
        assertNull(ledger.onInitialDown(key))
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.isDeliveredBeforeThisConnection(key))
    }

    @Test
    fun aConnectionChange_handsBackEachKeyTheImeSentAndHasNotReleased_once() {
        val releasedKey = (3L shl 32) or 67L
        val systemKey = (3L shl 32) or 68L
        ledger.onInitialDown(key)
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter")
        ledger.onInitialDown(otherDeviceKey)
        ledger.onSentByIme(otherDeviceKey, KeyEvent.KEYCODE_TAB, "tab")
        ledger.onInitialDown(releasedKey)
        ledger.onSentByIme(releasedKey, KeyEvent.KEYCODE_DEL, "del")
        ledger.onUp(releasedKey)
        ledger.onInitialDown(systemKey)
        ledger.onDeliveredBySystem(systemKey, KeyEvent.KEYCODE_SPACE)

        changeConnection()
        assertEquals(listOf("enter", "tab"), released.sorted())
        assertEquals(KeyEvent.KEYCODE_SPACE, ledger.deliveredKeyCode(systemKey))

        // Released or not, nothing is owed for them any more: not at the next change, not at
        // the next press, and not when a repeat brings one of them to the new connection.
        released.clear()
        changeConnection()
        assertTrue(released.toString(), released.isEmpty())
        assertNull(ledger.sentByIme(key))
        assertNull(ledger.onInitialDown(otherDeviceKey))

        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter again")
        changeConnection()
        assertEquals(listOf("enter again"), released)
    }

    @Test
    fun aKeyCodeOfZero_isAKeyLikeAnyOther() {
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_UNKNOWN)

        assertEquals(KeyEvent.KEYCODE_UNKNOWN, ledger.deliveredKeyCode(key))
    }

    @Test
    fun aKeyWhoseDownTheAppNeverSaw_hasNothingDelivered_andStartsNoRepeatInThatConnection() {
        ledger.onInitialDown(key)

        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.mayStartWithRepeat(key))
        // A key the ledger never saw go down at all (held since before the IME): no objection.
        assertTrue(ledger.mayStartWithRepeat(otherDeviceKey))
    }

    @Test
    fun aKeyHeldIntoTheNextConnection_owesItNothing_andMayStartThereWithARepeat() {
        ledger.onInitialDown(key)
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter")
        ledger.onInitialDown(otherDeviceKey)

        changeConnection()

        // No repeat reached the new connection: it saw nothing, so no UP is owed to it.
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertNull(ledger.sentByIme(key))
        assertTrue(ledger.mayStartWithRepeat(key))
        // A consumed DOWN is no longer held against repeats there either.
        assertTrue(ledger.mayStartWithRepeat(otherDeviceKey))

        // A repeat did: the new connection saw the key go down.
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter repeat")
        assertEquals(KeyEvent.KEYCODE_ENTER, ledger.deliveredKeyCode(key))
        assertEquals("enter repeat", ledger.sentByIme(key))
    }

    @Test
    fun aNewPress_reportsTheKeyTheImeNeverReleased_once() {
        ledger.onInitialDown(key)
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter")

        // The UP was lost; the key goes down again.
        assertEquals("enter", ledger.onInitialDown(key))
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.mayStartWithRepeat(key))
        assertNull(ledger.onInitialDown(key))
    }

    @Test
    fun aNewPress_owesNothingForAKeyTheSystemDelivered_orOneAnotherConnectionSaw() {
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertNull(ledger.onInitialDown(key))

        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter")
        changeConnection()
        assertNull(ledger.onInitialDown(key))
    }

    @Test
    fun clearAndClearDevice_forgetDeliveredKeys() {
        ledger.onInitialDown(key)
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER, "enter")
        ledger.onInitialDown(otherDeviceKey)
        ledger.onSentByIme(otherDeviceKey, KeyEvent.KEYCODE_TAB, "tab")

        ledger.clearDevice(3)
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertTrue(ledger.mayStartWithRepeat(key))
        assertEquals(KeyEvent.KEYCODE_TAB, ledger.deliveredKeyCode(otherDeviceKey))

        ledger.clear()
        assertEquals(none, ledger.deliveredKeyCode(otherDeviceKey))
        assertNull(ledger.onInitialDown(otherDeviceKey))
        // What was cleared is not handed back for release.
        changeConnection()
        assertTrue(released.toString(), released.isEmpty())

        // A key the system delivered is forgotten the same way, also one held over a change.
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT, insteadOfRemapped = true)
        ledger.onDeliveredBySystem(otherDeviceKey, KeyEvent.KEYCODE_SPACE)
        changeConnection()
        ledger.clearDevice(3)
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.isDeliveredBeforeThisConnection(key))
        assertEquals(KeyEvent.KEYCODE_SPACE, ledger.deliveredKeyCode(otherDeviceKey))
        ledger.clear()
        assertEquals(none, ledger.deliveredKeyCode(otherDeviceKey))
        assertFalse(ledger.isDeliveredBeforeThisConnection(otherDeviceKey))
    }
}
