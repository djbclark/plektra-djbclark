package it.palsoftware.pastiera

import android.content.Context
import it.palsoftware.pastiera.ResetPlan.Item
import it.palsoftware.pastiera.ResetPlan.StepResult
import it.palsoftware.pastiera.inputmethod.KeyboardBacklightManager
import it.palsoftware.pastiera.inputmethod.PrivilegedDiagnostics
import it.palsoftware.pastiera.inputmethod.PrivilegedResult
import it.palsoftware.pastiera.ring.NotificationRingSetup
import it.palsoftware.pastiera.ring.RingBacklight
import it.palsoftware.pastiera.toolbox.DisplayDensity
import it.palsoftware.pastiera.toolbox.FnCtrlRemap
import it.palsoftware.pastiera.toolbox.PackageRemover
import it.palsoftware.pastiera.toolbox.RemovalJournal
import it.palsoftware.pastiera.toolbox.SystemTweaks

/**
 * One-button "Reset device settings to stock" safety net.
 *
 * Some of what the app writes lives at the OS / vendor level and SURVIVES an uninstall. Android
 * gives an app no uninstall hook, so we cannot auto-revert: the supported path is the user tapping
 * this before uninstalling. [resetToStock] undoes every change this app made to the phone that is
 * still in effect. [plan] decides what that is, from the phone's own state, and the confirm dialog
 * shows exactly that list; the reset then runs those entries, in this order:
 *
 *   1. Fn -> Ctrl remap: `Settings.System` rows, restored from the capture (or switched off).
 *   2. Display density: `wm density reset`, through the broker.
 *   3. System tweaks: animation scales and the two hidden toggles, through the broker.
 *   4. Removed apps: each journaled package is restored (install-existing and enable), through
 *      the broker; a journal entry is dropped only when its package came back.
 *   5. The notification ring: the switch goes off, then its notification access and full-screen
 *      permission are handed back through the broker.
 *   6. The keyboard backlight timeout: back to the stock 30 s through the broker, and the smart
 *      backlight switch off so a later pairing does not write it again.
 *   7. The keyboard light switch, if a ring is holding it dark: given back with no broker.
 *   8. The orange side key: restored from its capture with `Settings.System`, no broker.
 *
 * Every step is independent (one failing never skips the others) and is wrapped so nothing
 * throws. Every step uses the cheapest route that works: a step that only writes settings goes
 * straight to the settings API when the app holds "Modify system settings" or
 * WRITE_SECURE_SETTINGS, and the broker is used only where the shell is the one way in.
 *
 * Everything here is BLOCKING (the broker path does network IO): callers MUST run it OFF the main
 * thread, and never from the IME input path. The WRITE_SECURE_SETTINGS grant itself is kept.
 */
object SystemChangeManager {

    /** Reason codes a failed [StepResult] can carry besides a [PrivilegedDiagnostics] code. */
    const val REASON_NEEDS_PERMISSION = "needs_permission"
    const val REASON_NEEDS_SECURE_SETTINGS = "needs_write_secure_settings"
    const val REASON_NOT_APPLIED = "not_applied"

    /** What a reset would undo now, and whether the broker it needs is reachable. */
    data class Plan(
        val entries: List<ResetPlan.Entry>,
        /** Why the broker cannot run right now; set only when an entry needs it. */
        val brokerBlocker: String?
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** Reads the phone and builds the list the confirm dialog shows. Quick, local; off the main thread. */
    fun plan(context: Context): Plan {
        val app = context.applicationContext
        val entries = ResetPlan.build(detect(app))
        val blocker = if (ResetPlan.needsBroker(entries)) {
            runCatching { PrivilegedDiagnostics.brokerBlocker(app) }.getOrNull()
        } else {
            null
        }
        return Plan(entries, blocker)
    }

    /**
     * Revert [entries] (default: a fresh [plan]). Reports each step through [onStep] as it ends, on
     * the calling thread, and returns every outcome. Never throws.
     */
    fun resetToStock(
        context: Context,
        entries: List<ResetPlan.Entry> = plan(context).entries,
        onStep: (ResetPlan.Entry, StepResult) -> Unit = { _, _ -> }
    ): ResetPlan.Result {
        val app = context.applicationContext
        val done = entries.map { entry ->
            val result = runCatching { runStep(app, entry) }
                .getOrElse { StepResult.failed(it.message ?: it.javaClass.simpleName) }
            runCatching { onStep(entry, result) }
            entry to result
        }
        return ResetPlan.Result(done)
    }

    // ---- what is changed right now ---------------------------------------------------------------

    private fun detect(app: Context): ResetPlan.ChangedState {
        fun flag(block: () -> Boolean) = runCatching(block).getOrDefault(false)
        val journal = runCatching {
            ResetPlan.partitionJournal(RemovalJournal.all(app)) { PackageRemover.localState(app, it) }
        }.getOrNull()
        return ResetPlan.ChangedState(
            fnCtrlMapped = flag { FnCtrlRemap.isSet(app) },
            densityChanged = flag { DisplayDensity.isChangedByApp(app) },
            tweaksChanged = flag { SystemTweaks.isChanged(app) },
            packagesToRestore = journal?.restore?.map { it.packageName }.orEmpty(),
            ringEnabled = flag { SettingsManager.isNotificationRingEnabled(app) },
            ringAccessGranted = flag { NotificationRingSetup.isListenerGranted(app) },
            timeoutApplied = flag { PrivilegedDiagnostics.backlightApplied(app) },
            smartBacklightEnabled = flag { SettingsManager.getSmartBacklightEnabled(app) },
            ringHoldsKeyboardLight = flag { SettingsManager.isRingBacklightSuppressed(app) },
            sideKeyBound = flag { VendorSideKeyManager.isAssistantBound(app) }
        )
    }

    // ---- the steps ---------------------------------------------------------------------------------

    private fun runStep(app: Context, entry: ResetPlan.Entry): StepResult = when (entry.item) {
        Item.FN_CTRL -> revertFnCtrl(app)
        Item.DISPLAY_DENSITY -> brokerGate(app) ?: fromShell(DisplayDensity.resetWithReason(app))
        Item.SYSTEM_TWEAKS -> brokerGate(app) ?: fromShell(SystemTweaks.resetAllWithReason(app))
        Item.REMOVED_APPS -> restoreApps(app, entry.packages)
        Item.RING_OFF -> switchRingOff(app)
        Item.RING_GRANTS -> brokerGate(app) ?: fromShell(NotificationRingSetup.revokeViaBroker(app))
        Item.KEYBOARD_TIMEOUT -> revertKeyboardTimeout(app)
        Item.KEYBOARD_LIGHT_HELD -> giveBackKeyboardLight(app)
        Item.SIDE_KEY -> restoreSideKey(app)
    }

    /** The broker's blocker as a failed step, or null when the broker should work. */
    private fun brokerGate(app: Context): StepResult? =
        PrivilegedDiagnostics.brokerBlocker(app)?.let { StepResult.failed(it) }

    private fun fromShell(result: PrivilegedResult): StepResult =
        if (result.ok) StepResult.DONE else StepResult.failed(result.failure)

    // 1. Fn -> Ctrl. Restore the captured original programmable-key values (or write 0/0 when the
    //    original was unset / never captured). The same code path as the "Reset Fn key" button, so
    //    the two cannot drift apart. It writes through Settings when the app may, else the broker.
    private fun revertFnCtrl(app: Context): StepResult = when (FnCtrlRemap.revert(app)) {
        FnCtrlRemap.Result.SUCCESS -> StepResult.DONE
        FnCtrlRemap.Result.NEEDS_PERMISSION -> StepResult.failed(REASON_NEEDS_PERMISSION)
        FnCtrlRemap.Result.FAILED -> StepResult.failed()
    }

    // 4. Removed apps. The journal is the source of truth (the catalog may have retired an entry);
    //    PackageRemover.restore forgets a record only when the package really came back.
    private fun restoreApps(app: Context, packages: List<String>): StepResult {
        brokerGate(app)?.let { return it }
        var restored = 0
        var firstFailure: String? = null
        packages.forEach { name ->
            when (val result = PackageRemover.restore(app, name)) {
                is PackageRemover.Result.Success -> restored++
                is PackageRemover.Result.NotPaired ->
                    firstFailure = firstFailure ?: PrivilegedDiagnostics.REASON_NOT_PAIRED
                is PackageRemover.Result.Refused -> firstFailure = firstFailure ?: result.reason
                is PackageRemover.Result.Failed -> firstFailure = firstFailure ?: result.reason
            }
        }
        // Records for packages the phone already shows as active have nothing left to undo.
        runCatching {
            ResetPlan.partitionJournal(RemovalJournal.all(app)) { PackageRemover.localState(app, it) }
                .stale.forEach { RemovalJournal.forget(app, it.packageName) }
        }
        return ResetPlan.appsResult(restored, packages.size, firstFailure)
    }

    // 5a. The ring's own switch. Needs nothing from the phone.
    private fun switchRingOff(app: Context): StepResult {
        SettingsManager.setNotificationRingEnabled(app, false)
        return if (SettingsManager.isNotificationRingEnabled(app)) StepResult.failed() else StepResult.DONE
    }

    // 6. Keyboard backlight timeout. The smart-backlight switch goes off only once the phone is
    //    back at 30 s (or never had the always-on value), so a failed reset leaves the setting
    //    honest: still on, and still detected as a change on the next try.
    private fun revertKeyboardTimeout(app: Context): StepResult {
        if (PrivilegedDiagnostics.backlightApplied(app)) {
            brokerGate(app)?.let { return it }
            val result = KeyboardBacklightManager.revertToDefaultNow(app)
            if (!result.ok) return StepResult.failed(result.failure)
        }
        SettingsManager.setSmartBacklightEnabled(app, false)
        return StepResult.DONE
    }

    // 7. A ring holding the vendor light switch dark. RingBacklight writes the saved value back
    //    in-process; the answer is whether the record it keeps is gone, not what the write returned.
    private fun giveBackKeyboardLight(app: Context): StepResult {
        RingBacklight.restore(app)
        return if (SettingsManager.isRingBacklightSuppressed(app)) {
            StepResult.failed(REASON_NEEDS_SECURE_SETTINGS)
        } else {
            StepResult.DONE
        }
    }

    // 8. The orange side key. restoreLongPress answers SUCCESS when it had nothing safe to write, so
    //    the check is whether the slot still points at Pastiera.
    private fun restoreSideKey(app: Context): StepResult {
        val outcome = VendorSideKeyManager.restoreLongPress(app)
        return when {
            outcome == VendorSideKeyManager.Outcome.NEEDS_PERMISSION -> StepResult.failed(REASON_NEEDS_PERMISSION)
            outcome == VendorSideKeyManager.Outcome.FAILED -> StepResult.failed()
            VendorSideKeyManager.isAssistantBound(app) -> StepResult.failed(REASON_NOT_APPLIED)
            else -> StepResult.DONE
        }
    }
}
