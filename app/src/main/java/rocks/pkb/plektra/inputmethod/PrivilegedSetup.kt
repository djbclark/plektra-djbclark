package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.util.Log

/**
 * Single entry point for everything the app wants to do through the embedded ADB broker.
 *
 * The user pairs Wireless debugging once; from then on every privileged change is applied in one
 * go, at pairing success ([moe.shizuku.manager.adb.AdbPairingService]) and wherever else a feature
 * asks for it. All steps must be idempotent, run off-thread and never throw, so calling this
 * repeatedly is harmless.
 *
 * djb/broker: no step lives here yet. A feature that needs a privileged grant (backlight, ring,
 * toolbox) adds one guarded call inside [applyAll].
 */
object PrivilegedSetup {
    private const val TAG = "PrivilegedSetup"

    fun applyAll(context: Context, reason: String) {
        val appContext = context.applicationContext
        // Log.i is stripped from release builds, so a bail-out here would leave no trace at all.
        // A step records its own failure through PrivilegedDiagnostics.record.
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            Log.w(TAG, "applyAll ($reason) skipped: $blocker")
            return
        }
        Log.i(TAG, "applyAll ($reason)")
    }
}
