package it.palsoftware.pastiera.inputmethod

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import it.palsoftware.pastiera.SettingsManager

/**
 * Single entry point for everything the app wants to do through the embedded ADB broker.
 *
 * The user pairs Wireless debugging once; from then on every privileged change is applied in one
 * go, at pairing success ([moe.shizuku.manager.adb.AdbPairingService]) and wherever else a feature
 * asks for it. All steps must be idempotent, run off-thread and never throw, so calling this
 * repeatedly is harmless.
 *
 * A feature that needs a privileged grant adds one guarded call inside [applyAll], and its
 * commands to [PrivilegedCommand]. Nothing here is called from the IME's key-handling path, and
 * nothing here turns Wireless debugging on.
 */
object PrivilegedSetup {
    private const val TAG = "PrivilegedSetup"

    fun applyAll(context: Context, reason: String) {
        val appContext = context.applicationContext
        // Log.i is stripped from release builds, so a bail-out here would leave no trace at all.
        // A step records its own failure through PrivilegedDiagnostics.record.
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            Log.w(TAG, "applyAll ($reason) skipped: $blocker")
            // djb/backlight: a blocked broker means no step runs, so record it for the steps whose
            // screens show a failure reason. Backlight only counts when the user turned it on.
            recordBlocked(appContext, PrivilegedDiagnostics.Step.WRITE_SECURE_SETTINGS, blocker)
            if (SettingsManager.getSmartBacklightEnabled(appContext)) {
                recordBlocked(appContext, PrivilegedDiagnostics.Step.BACKLIGHT, blocker)
            }
            return
        }
        Log.i(TAG, "applyAll ($reason)")
        // djb/backlight: the grant first (the Quick Settings tile needs it whether or not the
        // always-on backlight is enabled), then the always-on timeout if the user wants it.
        grantWriteSecureSettings(appContext)
        if (SettingsManager.getSmartBacklightEnabled(appContext)) {
            KeyboardBacklightManager.applyAlwaysOn(appContext)
        }
    }

    private fun recordBlocked(context: Context, step: PrivilegedDiagnostics.Step, blocker: String) {
        PrivilegedDiagnostics.record(context, step, ok = false, reason = blocker)
    }

    // djb/backlight: WRITE_SECURE_SETTINGS grant

    /** True when this app already holds WRITE_SECURE_SETTINGS. Asked of Android, not of the shell. */
    fun hasWriteSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * True when this app can write `Settings.System` itself: through the "Modify system settings"
     * grant, or through WRITE_SECURE_SETTINGS (the settings provider lets a holder of that
     * permission skip the app-op check). Asked of Android, never of the shell. Callers still read
     * the value back, so a write that did not take is caught.
     */
    fun canWriteSystemSettings(context: Context): Boolean = runCatching {
        android.provider.Settings.System.canWrite(context)
    }.getOrDefault(false) || hasWriteSecureSettings(context)

    /**
     * Take WRITE_SECURE_SETTINGS from the paired broker, once. Idempotent: a no-op when already
     * held. Best-effort, off-thread, never throws. The outcome is decided by
     * [hasWriteSecureSettings] afterwards, never by what the shell printed: `pm grant` is silent on
     * success, and a local app posing as adbd could print anything (broker review 1.4).
     *
     * The ring group's keyboard-dark switch and the Quick Settings tile both write through this one
     * permission, so the ring does not need a `pm grant` of its own: call this instead.
     */
    fun grantWriteSecureSettings(context: Context) {
        val appContext = context.applicationContext
        val step = PrivilegedDiagnostics.Step.WRITE_SECURE_SETTINGS
        if (hasWriteSecureSettings(appContext)) {
            PrivilegedDiagnostics.record(appContext, step, ok = true, reason = PrivilegedDiagnostics.REASON_OK)
            return
        }
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            recordBlocked(appContext, step, blocker)
            return
        }
        PrivilegedShell.runAsync {
            val result = PrivilegedShell.run(appContext, PrivilegedCommand.GrantWriteSecureSettings)
            val granted = hasWriteSecureSettings(appContext)
            PrivilegedDiagnostics.record(
                appContext,
                step,
                ok = granted,
                reason = if (granted) {
                    PrivilegedDiagnostics.REASON_OK
                } else {
                    result.failure ?: PrivilegedDiagnostics.REASON_SHELL_FAILED
                }
            )
        }
        // djb/ring: the notification ring's grants (notification access, full-screen intent, posting,
        // and WRITE_SECURE_SETTINGS when "keep the keyboard dark" is on), in one broker connection.
        // Only when the user turned the ring on; each grant that is already held is skipped.
        if (it.palsoftware.pastiera.SettingsManager.isNotificationRingEnabled(appContext)) {
            it.palsoftware.pastiera.ring.NotificationRingSetup.grantViaBroker(appContext)
        }
    }
}
