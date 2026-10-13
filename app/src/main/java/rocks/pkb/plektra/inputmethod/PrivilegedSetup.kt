package rocks.pkb.plektra.inputmethod

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Single entry point for everything the app wants to do through the embedded ADB broker.
 *
 * The user pairs Wireless debugging once; from then on every privileged change is applied in one
 * go, at pairing success ([moe.shizuku.manager.adb.AdbPairingService]) and when the user turns a
 * feature on in the app's screens. All steps must be idempotent, run off-thread and never throw,
 * so calling this repeatedly is harmless. Every call needs a [BrokerTrigger] (finding 1.3g).
 *
 * djb/broker: no feature step lives here yet. A feature that needs a privileged grant adds one
 * guarded step inside [applyAll], queued with [PrivilegedShell.runAsync], and
 * registers in [WriteSecureSettingsHolders] if it needs WRITE_SECURE_SETTINGS.
 */
object PrivilegedSetup {
    private const val TAG = "PrivilegedSetup"

    fun applyAll(context: Context, reason: String, trigger: BrokerTrigger?) {
        val appContext = context.applicationContext
        // Log.i is stripped from release builds, so a bail-out here would leave no trace at all.
        // A step records its own failure through PrivilegedDiagnostics.record.
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            Log.w(TAG, "applyAll ($reason) skipped: $blocker")
            return
        }
        Log.i(TAG, "applyAll ($reason)")
        PrivilegedShell.runAsync {
            // Verify first: right after pairing this is the connect that pins adbd's certificate
            // (trust on first use), seconds after the pairing code authenticated it.
            val commands = buildList {
                add(PrivilegedCommand.Verify)
                writeSecureSettingsAction(appContext)?.let { add(it) }
            }
            val results = PrivilegedShell.runSession(appContext, commands, trigger)
            recordPermissionOutcome(appContext, commands, results)
        }
        // djb/<feature>: queue feature steps here.
    }

    // ---- WRITE_SECURE_SETTINGS, held only while needed (finding 1.3c) ---------------------------

    /** True when this app holds WRITE_SECURE_SETTINGS. Asked of Android, not of the shell. */
    fun hasWriteSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun writeSecureSettingsAction(context: Context): PrivilegedCommand? =
        WriteSecureSettingsHolders.action(
            needed = WriteSecureSettingsHolders.anyNeeded(context),
            held = hasWriteSecureSettings(context)
        )

    /**
     * A feature that needs WRITE_SECURE_SETTINGS calls this when the user turns it on or off.
     * The permission is granted when the first holder appears and revoked when the last one goes.
     * Off-thread, never throws.
     */
    fun setWriteSecureSettingsNeeded(context: Context, holder: String, needed: Boolean, trigger: BrokerTrigger?) {
        val app = context.applicationContext
        WriteSecureSettingsHolders.set(app, holder, needed)
        val command = writeSecureSettingsAction(app) ?: return
        PrivilegedShell.runAsync {
            val result = PrivilegedShell.run(app, command, trigger)
            recordPermissionOutcome(app, listOf(command), listOf(result))
        }
    }

    /** Audit a grant or revoke by what Android now says, never by what the shell printed. */
    private fun recordPermissionOutcome(
        context: Context,
        commands: List<PrivilegedCommand>,
        results: List<PrivilegedResult>,
    ) {
        commands.forEachIndexed { i, command ->
            val failure = results.getOrNull(i)?.failure
            when (command) {
                PrivilegedCommand.GrantWriteSecureSettings -> BrokerAuditLog.record(
                    context, BrokerAuditLog.Kind.GRANT, "write_secure_settings",
                    ok = hasWriteSecureSettings(context), detail = failure
                )
                PrivilegedCommand.RevokeWriteSecureSettings -> BrokerAuditLog.record(
                    context, BrokerAuditLog.Kind.REVOKE, "write_secure_settings",
                    ok = !hasWriteSecureSettings(context), detail = failure
                )
                else -> Unit
            }
        }
    }

    // ---- reset to stock / forget pairing (findings 1.3b, 1.3c) ---------------------------------

    /**
     * What a reset achieved. [unpaired] is true only when a fresh connection was refused after the
     * broker unpaired itself. When it is false the user must remove "plektra-djb" from Settings >
     * Wireless debugging > Paired devices by hand. [writeSecureSettingsStillHeld] is true when the
     * revoke could not run; uninstalling the app, or pairing again and resetting, clears it.
     */
    data class ResetResult(
        val unpaired: Boolean,
        val writeSecureSettingsStillHeld: Boolean,
        val reason: String?,
    )

    /**
     * "Forget pairing", which is also this branch's reset to stock. While the broker can still
     * connect, in one session: revoke WRITE_SECURE_SETTINGS, withdraw notification access the
     * broker gave, and unpair this key (`IAdbManager.unpairDevice`, last, because adbd then drops
     * the connection). Then confirm the unpair (a new connection must be refused) and delete the
     * key and pins here whatever happened. BLOCKING (two mDNS searches); call off the main thread.
     */
    fun resetToStock(context: Context, trigger: BrokerTrigger?): ResetResult {
        val app = context.applicationContext
        var reason: String? = null
        var unpaired = false
        val fingerprint = EmbeddedAdbShell.keyFingerprint(app)
        val unpair = fingerprint?.let { PrivilegedCommand.UnpairSelf.forThisDevice(it) }
        val listeners = BrokerGrantLedger.listenersAllowedByBroker(app)
        val commands = buildList {
            if (hasWriteSecureSettings(app)) add(PrivilegedCommand.RevokeWriteSecureSettings)
            listeners.forEach { add(PrivilegedCommand.DisallowNotificationListener(it)) }
            unpair?.let { add(it) }
        }
        when {
            fingerprint == null -> reason = REASON_NO_KEY
            unpair == null -> reason = REASON_UNPAIR_UNVERIFIED_SDK
        }

        if (commands.isNotEmpty() && fingerprint != null) {
            val results = PrivilegedShell.runSession(app, commands, trigger)
            recordPermissionOutcome(app, commands, results)
            commands.forEachIndexed { i, command ->
                if (command is PrivilegedCommand.DisallowNotificationListener && results[i].ok) {
                    BrokerGrantLedger.recordListenerDisallowed(app, command.listener)
                }
            }
            if (unpair != null) {
                val result = results.last()
                val sent = (result.ok && PrivilegedCommand.UnpairSelf.replyIsSuccess(result.output)) ||
                    result.failure == EmbeddedAdbShell.REASON_CONNECTION_CLOSED
                if (sent && trigger != null) {
                    // The proof is adbd refusing this key now, not anything the shell printed.
                    val after = EmbeddedAdbShell.verify(app, trigger)
                    unpaired = after == EmbeddedAdbShell.BrokerStatus.REJECTED
                    if (!unpaired) reason = "after unpairing, the broker status is $after"
                } else {
                    reason = result.failure ?: "unpairDevice reported an error"
                }
            }
        }
        BrokerAuditLog.record(app, BrokerAuditLog.Kind.UNPAIR, "self", ok = unpaired, detail = reason)

        EmbeddedAdbShell.forgetPairing(app)
        WriteSecureSettingsHolders.clear(app)
        BrokerAuditLog.record(app, BrokerAuditLog.Kind.KEY, "deleted", ok = true)
        val stillHeld = hasWriteSecureSettings(app)
        return ResetResult(unpaired = unpaired, writeSecureSettingsStillHeld = stillHeld, reason = reason)
    }

    const val REASON_NO_KEY = "no readable key to unpair"
    const val REASON_UNPAIR_UNVERIFIED_SDK = "unpairing is not verified on this Android version"
}
