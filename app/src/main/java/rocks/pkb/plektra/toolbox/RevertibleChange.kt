package rocks.pkb.plektra.toolbox

import android.content.Context
import android.util.Log
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import rocks.pkb.plektra.inputmethod.PrivilegedShell
import moe.shizuku.manager.adb.EmbeddedAdbInit
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Applies a change that could make the phone unusable, and undoes it unless the user confirms
 * they can still read the screen.
 *
 * A confirmation dialog is the wrong tool here. Density is the proof: set it badly and the UI
 * is unreadable or the buttons sit off-screen, so asking "are you sure?" beforehand protects
 * nobody — the damage is only visible afterwards, and by then the user may not be able to
 * press anything. Every monitor resolution dialog of the last twenty years solves this the
 * same way, and so do we:
 *
 *     apply -> "Keep this? Reverting in 15s" -> revert unless confirmed
 *
 * The undo is recorded BEFORE the change is applied, with its deadline, and it lives in
 * preferences rather than memory, so a killed process or a user who simply puts the phone down
 * ends at the safe value instead of stranding them. djb/toolbox, two differences from the
 * original:
 *  - A density change recreates the settings activity, which used to lose the countdown while
 *    the revert stayed armed with nobody to run it. The deadline is stored, so a recreated
 *    screen resumes the same countdown, and an in-process timer runs the revert even if the
 *    screen is gone.
 *  - The revert is a [Kind], not a stored command line: nothing read back from preferences
 *    is ever turned into a shell command.
 *
 * BLOCKING — every call runs a broker command. Never call from the main thread, and never from
 * the IME input path.
 */
object RevertibleChange {

    private const val TAG = "RevertibleChange"
    private const val KEY_PENDING = "toolbox_pending_revert"

    /** Long enough to read the screen and react, short enough not to strand anyone. */
    const val CONFIRM_WINDOW_MS = 15_000L

    /** Covers the broker's mDNS discovery between arming and the command landing. */
    private const val ARM_SLACK_MS = 20_000L

    /** What can be applied with a revert armed, and the fixed command that undoes each. */
    internal enum class Kind(val revert: PrivilegedCommand) {
        DENSITY(PrivilegedCommand.ResetDensity)
    }

    sealed interface Outcome {
        data object Applied : Outcome
        data object NotPaired : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** A change waiting to be confirmed; [deadlineMs] is wall-clock time (System.currentTimeMillis). */
    internal data class Pending(val kind: Kind, val deadlineMs: Long) {
        fun remainingMs(now: Long = System.currentTimeMillis()): Long = (deadlineMs - now).coerceAtLeast(0L)
    }

    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "toolbox-revert").apply { isDaemon = true }
    }

    @Volatile
    private var scheduled: ScheduledFuture<*>? = null

    internal fun apply(context: Context, kind: Kind, command: PrivilegedCommand): Outcome {
        if (!EmbeddedAdbShell.isPaired(context)) return Outcome.NotPaired
        val appContext = context.applicationContext

        // Recorded first: a change we cannot reverse is worse than one that never lands.
        arm(appContext, Pending(kind, System.currentTimeMillis() + CONFIRM_WINDOW_MS + ARM_SLACK_MS))

        val result = PrivilegedShell.run(appContext, command)
        if (!result.ok) {
            disarm(appContext)
            return Outcome.Failed(result.failure ?: "Command failed")
        }

        // The window starts when the change is on screen, not when we asked for it.
        val pending = Pending(kind, System.currentTimeMillis() + CONFIRM_WINDOW_MS)
        arm(appContext, pending)
        schedule(appContext, pending)
        return Outcome.Applied
    }

    /** The user confirmed the screen is still usable. Stand the revert down. */
    fun keep(context: Context) {
        disarm(context.applicationContext)
    }

    /** The window expired, or the user said no. Put it back. */
    fun revertNow(context: Context): Boolean {
        val appContext = context.applicationContext
        val pending = pending(appContext) ?: return true
        val ok = PrivilegedShell.run(appContext, pending.kind.revert).ok
        if (ok) disarm(appContext)
        return ok
    }

    /**
     * A change still waiting to be confirmed, or null.
     *
     * Checked whenever a screen that can arm one opens: a change that outlived the app —
     * process death, or a reboot inside the window — must still be undone rather than quietly
     * becoming permanent. (Deliberately not checked from the IME: the keyboard's input path
     * never talks to the broker.)
     */
    internal fun pending(context: Context): Pending? {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Pending(
                kind = Kind.valueOf(json.getString("kind")),
                deadlineMs = json.getLong("deadline")
            )
        }.onFailure { Log.w(TAG, "Pending revert unreadable", it) }.getOrNull()
    }

    private fun prefs(context: Context) = EmbeddedAdbInit.prefs(context.applicationContext)

    private fun arm(context: Context, pending: Pending) {
        val json = JSONObject()
            .put("kind", pending.kind.name)
            .put("deadline", pending.deadlineMs)
        prefs(context).edit().putString(KEY_PENDING, json.toString()).apply()
    }

    private fun disarm(context: Context) {
        scheduled?.cancel(false)
        scheduled = null
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    /** Runs the revert even if the screen that applied the change has been destroyed. */
    private fun schedule(appContext: Context, pending: Pending) {
        scheduled?.cancel(false)
        scheduled = timer.schedule({
            val current = pending(appContext)
            if (current != null && current.remainingMs() == 0L) {
                if (!revertNow(appContext)) Log.w(TAG, "Timed revert failed; it stays armed")
            }
        }, pending.remainingMs() + 500L, TimeUnit.MILLISECONDS)
    }
}
