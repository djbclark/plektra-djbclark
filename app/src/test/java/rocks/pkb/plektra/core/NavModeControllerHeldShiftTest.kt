package rocks.pkb.plektra.core

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

/**
 * A nav-mode arrow takes the Shift the IME tracks as held, also when the event's own meta state
 * lacks it: Enter remapped to Shift ("Swap right Shift and Enter") is not in the framework's
 * meta state of the keys pressed under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavModeControllerHeldShiftTest {

    private lateinit var context: Context
    private lateinit var modifiers: ModifierStateController
    private lateinit var controller: NavModeController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        modifiers = ModifierStateController(300L)
        controller = NavModeController(context, modifiers)
    }

    private fun connection(): InputConnection {
        val inputConnection = mock(InputConnection::class.java)
        `when`(inputConnection.sendKeyEvent(any(KeyEvent::class.java))).thenReturn(true)
        return inputConnection
    }

    private fun sent(inputConnection: InputConnection): List<KeyEvent> {
        val captor = ArgumentCaptor.forClass(KeyEvent::class.java)
        verify(inputConnection, times(2)).sendKeyEvent(captor.capture())
        return captor.allValues
    }

    private fun keyDown(metaState: Int) =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, 0, metaState)

    private fun arrow(event: KeyEvent?, mapping: String = "DPAD_LEFT"): List<KeyEvent> {
        val inputConnection = connection()
        assertTrue(controller.executeMapping("keycode", mapping, event, inputConnection))
        return sent(inputConnection)
    }

    @Test
    fun remappedShiftHeld_eventWithoutShiftMeta_selects() {
        // Enter remapped to SHIFT_RIGHT: the IME's Shift is down, the raw event's meta is not.
        modifiers.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT)

        val events = arrow(keyDown(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))

        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.map { it.action })
        assertTrue(events.all { it.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && it.isShiftPressed })
    }

    @Test
    fun realShiftHeld_eventAlreadyCarriesShift_isNotChanged() {
        modifiers.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        val meta = KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON

        val events = arrow(keyDown(meta))

        assertTrue(events.all { it.metaState == meta })
    }

    @Test
    fun noShiftHeld_doesNotSelect() {
        val events = arrow(keyDown(KeyEvent.META_CTRL_ON))

        assertFalse(events.any { it.isShiftPressed })
        assertTrue(events.all { it.metaState == KeyEvent.META_CTRL_ON })
    }

    @Test
    fun shiftHeld_keyThatDoesNotExtendASelection_isLeftAlone() {
        modifiers.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT)

        val events = arrow(keyDown(0), mapping = "ESCAPE")

        assertFalse(events.any { it.isShiftPressed })
    }

    @Test
    fun shiftHeld_noEvent_staysUnshifted() {
        modifiers.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT)

        val events = arrow(null)

        assertFalse(events.any { it.isShiftPressed })
    }
}
