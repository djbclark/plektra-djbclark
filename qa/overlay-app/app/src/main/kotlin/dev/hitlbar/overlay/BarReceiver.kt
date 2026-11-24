package dev.hitlbar.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Accepts exactly two actions, SHOW and HIDE. Everything else is ignored. Registered in the
 * manifest, so it works while the app has no activity.
 */
class BarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SHOW -> Bar.show(
                context,
                clampText(intent.getStringExtra(EXTRA_TEXT)),
                parseButtons(intent.getStringExtra(EXTRA_BUTTONS)),
            )
            ACTION_HIDE -> Bar.hide(context)
        }
    }
}
