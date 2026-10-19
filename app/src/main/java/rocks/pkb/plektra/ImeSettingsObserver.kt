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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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

/**
 * Like [observeImeSettings], but only while [lifecycle] is at least STARTED: registered on ON_START,
 * unregistered on ON_STOP. STARTED rather than RESUMED, so a change made in another window while
 * this one is visible but unfocused (multi-window) is still seen. A stopped activity needs no
 * observer: every re-check costs system_server an AppsFilter pass per installed keyboard, and the
 * window-focus re-check covers the return. Returns a function that stops observing.
 */
internal fun observeImeSettingsWhileStarted(
    context: Context,
    lifecycle: Lifecycle,
    onChanged: () -> Unit,
): () -> Unit {
    var stopObserving: (() -> Unit)? = null
    val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (stopObserving == null) {
                stopObserving = observeImeSettings(context, onChanged)
            }
            Lifecycle.Event.ON_STOP -> {
                stopObserving?.invoke()
                stopObserving = null
            }
            else -> Unit
        }
    }
    lifecycle.addObserver(lifecycleObserver)
    return {
        lifecycle.removeObserver(lifecycleObserver)
        stopObserving?.invoke()
        stopObserving = null
    }
}

/**
 * Runs [recheck] each time the input method settings change, while this composition is alive and
 * its activity is at least STARTED.
 */
@Composable
internal fun RecheckImeStatusOnSettingsChange(recheck: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRecheck by rememberUpdatedState(recheck)
    DisposableEffect(context, lifecycleOwner) {
        val stopObserving = observeImeSettingsWhileStarted(context, lifecycleOwner.lifecycle) {
            currentRecheck()
        }
        onDispose(stopObserving)
    }
}
