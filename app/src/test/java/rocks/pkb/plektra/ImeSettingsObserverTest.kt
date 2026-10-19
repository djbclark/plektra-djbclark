package rocks.pkb.plektra

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImeSettingsObserverTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun notifyChange(name: String) {
        context.contentResolver.notifyChange(Settings.Secure.getUriFor(name), null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun enabledListAndDefaultInputMethodChangesEachNotifyOnce() {
        var calls = 0
        val stop = observeImeSettings(context) { calls++ }

        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(1, calls)
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(2, calls)
        stop()
    }

    @Test
    fun otherSettingsAndStoppedObserverDoNotNotify() {
        var calls = 0
        val stop = observeImeSettings(context) { calls++ }

        notifyChange(Settings.Secure.ANDROID_ID)
        assertEquals(0, calls)

        stop()
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(0, calls)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun whileStartedObserverIgnoresChangesBelowStartedAndHearsThemAfterStart() {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        var calls = 0
        val stop = observeImeSettingsWhileStarted(context, owner.lifecycle) { calls++ }

        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(0, calls)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(1, calls)

        // RESUMED and back to STARTED (unfocused multi-window pane): still observing.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(2, calls)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(2, calls)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(3, calls)

        stop()
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(3, calls)
    }

    @Test
    fun whileStartedObserverRegistersAtOnceWhenAlreadyStartedAndStopsOnDispose() {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.STARTED
        var calls = 0
        val stop = observeImeSettingsWhileStarted(context, owner.lifecycle) { calls++ }

        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(1, calls)

        stop()
        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(1, calls)
    }
}
