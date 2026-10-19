package rocks.pkb.plektra

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Calls [onChanged] on the main thread when the enabled input methods or the default input method
 * change, wherever the change is made (keyboard settings, the IME picker, a hardware switch
 * shortcut, another window). Returns a function that stops observing.
 *
 * This watches the two secure settings rather than Intent.ACTION_INPUT_METHOD_CHANGED: that
 * broadcast only reports a change of the current input method, not enabling or disabling one.
 */
internal fun observeImeSettings(context: Context, onChanged: () -> Unit): () -> Unit {
    val resolver = context.applicationContext.contentResolver
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onChanged()
        }
    }
    listOf(Settings.Secure.ENABLED_INPUT_METHODS, Settings.Secure.DEFAULT_INPUT_METHOD).forEach {
        resolver.registerContentObserver(Settings.Secure.getUriFor(it), false, observer)
    }
    return { resolver.unregisterContentObserver(observer) }
}

/** Runs [recheck] each time the input method settings change, while this composition is alive. */
@Composable
internal fun RecheckImeStatusOnSettingsChange(recheck: () -> Unit) {
    val context = LocalContext.current
    val currentRecheck by rememberUpdatedState(recheck)
    DisposableEffect(context) {
        val stopObserving = observeImeSettings(context) { currentRecheck() }
        onDispose(stopObserving)
    }
}
