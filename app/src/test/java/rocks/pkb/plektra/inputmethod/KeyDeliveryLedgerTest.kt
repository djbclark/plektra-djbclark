package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyDeliveryLedgerTest {

    private val ledger = KeyDeliveryLedger()
    private val key = (3L shl 32) or 66L
    private val otherDeviceKey = (4L shl 32) or 66L
    private val none = KeyDeliveryLedger.NONE

    @Test
    fun aKeyTheImeSent_isKnownByTheKeyCodeTheAppSaw_untilItsUp() {
        assertEquals(none, ledger.onInitialDown(key))
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER)

        assertEquals(KeyEvent.KEYCODE_ENTER, ledger.deliveredKeyCode(key))
        assertTrue(ledger.isSentByIme(key))
        assertEquals(none, ledger.deliveredKeyCode(otherDeviceKey))

        ledger.onUp(key)
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.isSentByIme(key))
    }

    @Test
    fun aKeyTheSystemDelivered_staysTheSystems_andAKeyTheImeSentStaysTheImes() {
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, ledger.deliveredKeyCode(key))
        assertFalse(ledger.isSentByIme(key))

        // The IME sent this one: a repeat the system delivers afterwards does not take it over.
        ledger.onInitialDown(otherDeviceKey)
        ledger.onSentByIme(otherDeviceKey, KeyEvent.KEYCODE_DPAD_LEFT)
        ledger.onDeliveredBySystem(otherDeviceKey, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, ledger.deliveredKeyCode(otherDeviceKey))
        assertTrue(ledger.isSentByIme(otherDeviceKey))
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
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER)
        ledger.onInitialDown(otherDeviceKey)

        ledger.onConnectionChanged()

        // No repeat reached the new connection: it saw nothing, so no UP is owed to it.
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.isSentByIme(key))
        assertTrue(ledger.mayStartWithRepeat(key))
        // A consumed DOWN is no longer held against repeats there either.
        assertTrue(ledger.mayStartWithRepeat(otherDeviceKey))

        // A repeat did: the new connection saw the key go down.
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER)
        assertEquals(KeyEvent.KEYCODE_ENTER, ledger.deliveredKeyCode(key))
        assertTrue(ledger.isSentByIme(key))
    }

    @Test
    fun aNewPress_reportsTheKeyTheImeNeverReleased_once() {
        ledger.onInitialDown(key)
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER)

        // The UP was lost; the key goes down again.
        assertEquals(KeyEvent.KEYCODE_ENTER, ledger.onInitialDown(key))
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertFalse(ledger.mayStartWithRepeat(key))
        assertEquals(none, ledger.onInitialDown(key))
    }

    @Test
    fun aNewPress_owesNothingForAKeyTheSystemDelivered_orOneAnotherConnectionSaw() {
        ledger.onInitialDown(key)
        ledger.onDeliveredBySystem(key, KeyEvent.KEYCODE_SHIFT_RIGHT)
        assertEquals(none, ledger.onInitialDown(key))

        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER)
        ledger.onConnectionChanged()
        assertEquals(none, ledger.onInitialDown(key))
    }

    @Test
    fun clearAndClearDevice_forgetDeliveredKeys() {
        ledger.onInitialDown(key)
        ledger.onSentByIme(key, KeyEvent.KEYCODE_ENTER)
        ledger.onInitialDown(otherDeviceKey)
        ledger.onSentByIme(otherDeviceKey, KeyEvent.KEYCODE_TAB)

        ledger.clearDevice(3)
        assertEquals(none, ledger.deliveredKeyCode(key))
        assertTrue(ledger.mayStartWithRepeat(key))
        assertEquals(KeyEvent.KEYCODE_TAB, ledger.deliveredKeyCode(otherDeviceKey))

        ledger.clear()
        assertEquals(none, ledger.deliveredKeyCode(otherDeviceKey))
        assertEquals(none, ledger.onInitialDown(otherDeviceKey))
    }
}
