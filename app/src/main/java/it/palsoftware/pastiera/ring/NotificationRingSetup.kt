package it.palsoftware.pastiera.ring

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import it.palsoftware.pastiera.SettingsManager
import it.palsoftware.pastiera.inputmethod.PrivilegedCommand
import it.palsoftware.pastiera.inputmethod.PrivilegedResult
import it.palsoftware.pastiera.inputmethod.PrivilegedShell

/**
 * The grants the ring needs, and the one pairing that supplies all of them.
 *
 * Notification access and the full-screen permission are each a trip into system settings
 * for the user. The paired broker can grant both in one shell line, which is why this is
 * wired into [it.palsoftware.pastiera.inputmethod.PrivilegedSetup]: turn the ring on, and a
 * phone paired once is set up without another tap. The settings intents remain as the route
 * for a phone that was never paired.
 *
 * Every broker command goes through [PrivilegedCommand.RingGrants], a closed set; this object never
 * builds a shell string. The outcome is always read back from a local API, not from the shell.
 * Nothing here turns Wireless debugging on, and nothing here runs from the IME's key path.
 */
object NotificationRingSetup {
    private const val TAG = "NotificationRing"

    fun isListenerGranted(context: Context): Boolean =
        NotificationRingListener.isAccessGranted(context)

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }

    fun canPostNotifications(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    /** Everything is in place for a ring to actually appear. */
    fun isReady(context: Context): Boolean =
        isListenerGranted(context) && canUseFullScreenIntent(context) && canPostNotifications(context)

    /** The keyboard can be darkened for a ring: the switch is on and the app may write the vendor row. */
    fun isKeyboardDarkReady(context: Context): Boolean =
        !SettingsManager.isRingKeyboardDarkEnabled(context) || RingBacklight.canWrite(context)

    /** The broker grants still needed, in order. Empty when nothing is missing. */
    internal fun missingGrants(context: Context): List<PrivilegedCommand.RingGrant> = buildList {
        if (!isListenerGranted(context)) add(PrivilegedCommand.RingGrant.ALLOW_LISTENER)
        if (!canUseFullScreenIntent(context)) add(PrivilegedCommand.RingGrant.ALLOW_FULL_SCREEN_INTENT)
        if (!canPostNotifications(context)) add(PrivilegedCommand.RingGrant.ALLOW_POST_NOTIFICATIONS)
        if (!isKeyboardDarkReady(context)) add(PrivilegedCommand.RingGrant.WRITE_SECURE_SETTINGS)
    }

    /**
     * Grant whatever is missing through the broker, in a single connection: the three ring grants
     * and, when "keep the keyboard dark" is on, the one `WRITE_SECURE_SETTINGS` grant that lets
     * [RingBacklight] work with no broker in its path. Best-effort, off-thread, never throws;
     * no-op when nothing is missing, and a quiet failure when the broker cannot run (not paired,
     * Wireless debugging off). [onDone] runs on the worker thread with the final readiness of the
     * ring grants.
     *
     * Merge note: once the backlight group's `PrivilegedSetup.grantWriteSecureSettings` is in the
     * tree, the `WRITE_SECURE_SETTINGS` entry here can be replaced by a call to it, which also
     * records the outcome in the diagnostics.
     */
    fun grantViaBroker(context: Context, onDone: ((ready: Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        val missing = missingGrants(appContext)
        if (missing.isEmpty()) {
            onDone?.invoke(isReady(appContext))
            return
        }
        PrivilegedShell.runAsync {
            val result = PrivilegedShell.run(appContext, PrivilegedCommand.RingGrants(missing))
            // The shell's own result is not the answer: these commands are quiet on success, and the
            // grants themselves are what matter, so ask the platform for those.
            val ready = isReady(appContext)
            Log.i(TAG, "broker grant ok=${result.ok} ready=$ready keyboardDarkReady=${isKeyboardDarkReady(appContext)}")
            onDone?.invoke(ready)
        }
    }

    /**
     * Take the grants back, for "Reset device settings to stock" ([it.palsoftware.pastiera.SystemChangeManager]):
     * notification access is the one thing here that outlives an uninstall in a way the user could
     * not see. BLOCKING — call off the main thread. The result is [PrivilegedResult.ok] when
     * notification access is no longer granted, read from the platform and never from the shell's
     * output; [PrivilegedResult.failure] says why it is still there. The full-screen permission is
     * handed back to its default in the same command; the platform cannot tell a default from an
     * explicit allow, so it is not part of the verdict.
     */
    internal fun revokeViaBroker(context: Context): PrivilegedResult {
        val appContext = context.applicationContext
        if (!isListenerGranted(appContext) && !canUseFullScreenIntent(appContext)) {
            return PrivilegedResult(ok = true, output = null, failure = null)
        }
        val shell = PrivilegedShell.run(
            appContext,
            PrivilegedCommand.RingGrants(
                listOf(
                    PrivilegedCommand.RingGrant.DISALLOW_LISTENER,
                    PrivilegedCommand.RingGrant.RESET_FULL_SCREEN_INTENT
                )
            )
        )
        val revoked = !isListenerGranted(appContext)
        return PrivilegedResult(
            ok = revoked,
            output = null,
            failure = if (revoked) null else shell.failure ?: "Notification access is still granted"
        )
    }

    fun listenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun fullScreenSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            appNotificationSettingsIntent(context)
        }

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
