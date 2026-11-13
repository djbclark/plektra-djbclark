package rocks.pkb.plektra.inputmethod

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

    @Test
    fun aKeyTheAppSawGoDown_getsItsRepeatsAndItsUp() {
        ledger.onInitialDown(key)
        ledger.onDelivered(key)

        assertTrue(ledger.mayDeliverRepeat(key))
        assertTrue(ledger.onUp(key))
        // The press is over.
        assertFalse(ledger.onUp(key))
    }

    @Test
    fun aKeyWhoseDownTheImeConsumed_getsNeither() {
        ledger.onInitialDown(key)

        assertFalse(ledger.mayDeliverRepeat(key))
        assertFalse(ledger.onUp(key))
        assertFalse(ledger.onUp(otherDeviceKey))
    }

    @Test
    fun aKeyHeldIntoTheNextEditor_repeatsThereButReleasesOnlyWhatThatEditorSaw() {
        ledger.onInitialDown(key)
        ledger.onDelivered(key)
        ledger.onInitialDown(otherDeviceKey)
        ledger.onDelivered(otherDeviceKey)
        ledger.onEditorChanged()

        // No repeat reached the new editor before the release: no UP without a DOWN.
        assertFalse(ledger.onUp(otherDeviceKey))
        // A repeat did: the new editor saw the key go down and gets its UP.
        assertTrue(ledger.mayDeliverRepeat(key))
        ledger.onDelivered(key)
        assertTrue(ledger.onUp(key))
    }

    @Test
    fun aConsumedDown_isNoLongerHeldAgainstRepeatsInALaterEditor() {
        ledger.onInitialDown(key)
        assertFalse(ledger.mayDeliverRepeat(key))

        ledger.onEditorChanged()

        assertTrue(ledger.mayDeliverRepeat(key))
        assertFalse(ledger.onUp(key))
    }

    @Test
    fun aNewPress_forgetsAnEarlierOneWhoseUpWasLost() {
        ledger.onInitialDown(key)
        ledger.onDelivered(key)

        ledger.onInitialDown(key)

        assertFalse(ledger.mayDeliverRepeat(key))
        assertFalse(ledger.onUp(key))
    }

    @Test
    fun clearAndClearDevice_forgetDeliveredKeys() {
        ledger.onInitialDown(key)
        ledger.onDelivered(key)
        ledger.onInitialDown(otherDeviceKey)
        ledger.onDelivered(otherDeviceKey)

        ledger.clearDevice(3)
        assertFalse(ledger.onUp(key))
        assertTrue(ledger.mayDeliverRepeat(otherDeviceKey))

        ledger.clear()
        assertFalse(ledger.onUp(otherDeviceKey))
    }
}
