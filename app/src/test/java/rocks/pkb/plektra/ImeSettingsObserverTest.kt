package rocks.pkb.plektra

import android.content.Context
import android.os.Looper
import android.provider.Settings
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
}
