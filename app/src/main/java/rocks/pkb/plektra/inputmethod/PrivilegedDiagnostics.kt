package rocks.pkb.plektra.inputmethod

import android.content.Context
import androidx.core.content.edit
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * Remembers what the ADB broker last did, so a screen can show the truth at once instead of
 * blocking on an 8 s check, and so a failed privileged step leaves a trace.
 *
 * [EmbeddedAdbShell.lastError] is an in-memory field on a process that restarts constantly, and
 * `Log.i`/`Log.d` are stripped from release builds, so without this a step that stopped working
 * (typically because Android turned Wireless debugging off across a reboot) fails in silence.
 *
 * Stored in the broker's own preferences file ([EmbeddedAdbInit.PREFS_NAME]), which is excluded
 * from backup: a verdict restored from another phone describes a pairing this one never had.
 */
object PrivilegedDiagnostics {

    /**
     * One entry per privileged step that reports through [record]. djb/broker: empty on purpose;
     * the group that adds a step adds its entry here.
     */
    enum class Step(val key: String)

    const val REASON_OK = "ok"
    const val REASON_NOT_PAIRED = "not_paired"
    const val REASON_WIRELESS_DEBUGGING_OFF = "wireless_debugging_off"
    const val REASON_SHELL_FAILED = "shell_failed"

    data class Outcome(
        val step: Step,
        val ok: Boolean,
        val reason: String,
        val atMs: Long
    )

    fun record(context: Context, step: Step, ok: Boolean, reason: String) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit {
            putBoolean(okKey(step), ok)
            putString(reasonKey(step), reason)
            putLong(atKey(step), System.currentTimeMillis())
        }
    }

    /** The last recorded outcome for [step], or null if the step has never run. */
    fun last(context: Context, step: Step): Outcome? {
        val prefs = EmbeddedAdbInit.prefs(context.applicationContext)
        val at = prefs.getLong(atKey(step), 0L)
        if (at == 0L) return null
        return Outcome(
            step = step,
            ok = prefs.getBoolean(okKey(step), false),
            reason = prefs.getString(reasonKey(step), REASON_OK) ?: REASON_OK,
            atMs = at
        )
    }

    fun snapshot(context: Context): List<Outcome> =
        Step.entries.mapNotNull { last(context, it) }

    /**
     * Remember the last [EmbeddedAdbShell.verify] result, so a screen can show the truth
     * immediately and refresh it in the background.
     */
    fun recordBrokerStatus(context: Context, status: EmbeddedAdbShell.BrokerStatus) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit {
            putString(KEY_BROKER_STATUS, status.name)
            putLong(KEY_BROKER_STATUS_AT, System.currentTimeMillis())
        }
    }

    /** The last verified broker status and when it was taken, or null if never checked. */
    fun lastBrokerStatus(context: Context): Pair<EmbeddedAdbShell.BrokerStatus, Long>? {
        val prefs = EmbeddedAdbInit.prefs(context.applicationContext)
        val at = prefs.getLong(KEY_BROKER_STATUS_AT, 0L)
        if (at == 0L) return null
        val name = prefs.getString(KEY_BROKER_STATUS, null) ?: return null
        val status = runCatching { EmbeddedAdbShell.BrokerStatus.valueOf(name) }.getOrNull()
            ?: return null
        return status to at
    }

    /**
     * Why the broker cannot run right now, or null when it should work.
     *
     * Checking Wireless debugging matters as much as checking the pairing: [EmbeddedAdbShell.isPaired]
     * only asks whether a key was ever stored, and Android disables Wireless debugging across
     * reboots, so a paired device is routinely unable to connect.
     */
    fun brokerBlocker(context: Context): String? = when {
        !EmbeddedAdbShell.isPaired(context) -> REASON_NOT_PAIRED
        !EmbeddedAdbShell.isWirelessDebuggingEnabled(context) -> REASON_WIRELESS_DEBUGGING_OFF
        else -> null
    }

    private const val KEY_BROKER_STATUS = "privileged_broker_status"
    private const val KEY_BROKER_STATUS_AT = "privileged_broker_status_at"

    private fun okKey(step: Step) = "privileged_${step.key}_ok"
    private fun reasonKey(step: Step) = "privileged_${step.key}_reason"
    private fun atKey(step: Step) = "privileged_${step.key}_at"
}
