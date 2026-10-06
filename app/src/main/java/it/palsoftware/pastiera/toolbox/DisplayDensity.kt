package it.palsoftware.pastiera.toolbox

import android.content.Context
import android.content.res.Resources
import android.util.Log
import it.palsoftware.pastiera.inputmethod.EmbeddedAdbShell
import it.palsoftware.pastiera.inputmethod.PrivilegedCommand
import it.palsoftware.pastiera.inputmethod.PrivilegedDiagnostics
import it.palsoftware.pastiera.inputmethod.PrivilegedResult
import it.palsoftware.pastiera.inputmethod.PrivilegedShell
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * Screen density, the most-asked-for Android tweak there is.
 *
 * On a 1080x1200 Titan panel the payoff is unusually direct: the screen is short, so every
 * step down in density buys another line or two of whatever you are reading, and the physical
 * keyboard means none of that space goes back to a soft keyboard.
 *
 * Applied through [RevertibleChange] rather than directly, because a bad value is exactly the
 * failure a confirmation dialog cannot save you from. The density is an Int that must sit in
 * [range] AND inside [PrivilegedCommand.SetDensity]'s absolute bounds before a command exists.
 */
object DisplayDensity {

    private const val TAG = "DisplayDensity"

    /**
     * Bounds, not a preference. Below roughly 60% of stock the system UI starts laying out in
     * ways nothing was designed for; above 140% the keyboard's own rows stop fitting. The
     * auto-revert catches a bad choice, but it should not be reachable in the first place.
     */
    private const val MIN_FACTOR = 0.6f
    private const val MAX_FACTOR = 1.4f

    data class Info(
        /** What the panel reports — the value "reset" returns to. */
        val physical: Int,
        /** What is in force now, which may be an override. */
        val current: Int
    ) {
        val isOverridden: Boolean get() = physical != current
    }

    fun range(physical: Int): IntRange =
        (physical * MIN_FACTOR).toInt()..(physical * MAX_FACTOR).toInt()

    /**
     * Reads the stock density from the shell and the density in force from the system itself.
     *
     * `wm density` prints "Physical density: N" and, only when an override is in force, a
     * second "Override density: N" line. The stock value can only come from there; what is in
     * force right now is taken from the system resources, which is what the screen is really
     * drawn with, and falls back to the shell's answer.
     */
    fun read(context: Context): Info? {
        if (!EmbeddedAdbShell.isPaired(context)) return null
        val out = PrivilegedShell.run(context, PrivilegedCommand.ReadDensity).output ?: return null
        val physical = parsePhysical(out) ?: return null
        val shellCurrent = parseOverride(out) ?: physical
        val local = Resources.getSystem().displayMetrics.densityDpi
        return Info(physical = physical, current = if (local > 0) local else shellCurrent)
    }

    internal fun parsePhysical(output: String): Int? =
        Regex("Physical density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()

    internal fun parseOverride(output: String): Int? =
        Regex("Override density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Applies a density with the revert already armed.
     *
     * The revert is always `wm density reset` rather than "back to the previous number":
     * chaining changes would otherwise leave the undo pointing at another override rather
     * than at the value the panel actually shipped with.
     */
    fun apply(context: Context, info: Info, density: Int): RevertibleChange.Outcome {
        val allowed = range(info.physical)
        if (density !in allowed || density !in PrivilegedCommand.SetDensity.MIN_DPI..PrivilegedCommand.SetDensity.MAX_DPI) {
            Log.w(TAG, "Refusing density $density outside $allowed")
            return RevertibleChange.Outcome.Failed("Outside the safe range")
        }
        // Recorded before the command, like the revert itself: a change the app made but forgot is
        // one "Reset to stock" could never offer to undo.
        val alreadyChanged = isChangedByApp(context)
        markChanged(context, true)
        val outcome = RevertibleChange.apply(context, RevertibleChange.Kind.DENSITY, PrivilegedCommand.SetDensity(density))
        if (outcome !is RevertibleChange.Outcome.Applied && !alreadyChanged) markChanged(context, false)
        return outcome
    }

    /** Back to the panel's own density, with no confirmation window — this is always safe. */
    fun reset(context: Context): Boolean = resetWithReason(context).ok

    /** [reset] with the reason it did not work. BLOCKING; off the main thread. */
    internal fun resetWithReason(context: Context): PrivilegedResult {
        if (!EmbeddedAdbShell.isPaired(context)) {
            return PrivilegedResult(ok = false, output = null, failure = PrivilegedDiagnostics.REASON_NOT_PAIRED)
        }
        val result = PrivilegedShell.run(context, PrivilegedCommand.ResetDensity)
        if (result.ok) {
            RevertibleChange.keep(context)
            markChanged(context, false)
        }
        return result
    }

    /**
     * True while this app has applied a density override and not taken it back. The density itself
     * cannot be compared to the panel's stock value without the broker, so "Reset to stock" asks
     * this instead. Kept in the broker's backup-excluded preferences: it describes this phone.
     */
    fun isChangedByApp(context: Context): Boolean =
        EmbeddedAdbInit.prefs(context.applicationContext).getBoolean(KEY_CHANGED, false)

    /** Records that a density override is in force (true) or was put back (false). */
    internal fun markChanged(context: Context, changed: Boolean) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit().putBoolean(KEY_CHANGED, changed).apply()
    }

    private const val KEY_CHANGED = "toolbox_density_changed"
}
