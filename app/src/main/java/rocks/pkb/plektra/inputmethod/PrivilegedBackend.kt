package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Bundle
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.shizuku.ShizukuUserServiceShell

/**
 * plk/shizuku-backend: which route a privileged command takes once it is through the broker's door
 * ([EmbeddedAdbShell.execute]). The door's checks (trigger, read-only screens, the closed command
 * set, terminal-last, the audit log) are the same for both; only the last step differs.
 */
enum class PrivilegedBackend(val auditName: String) {
    /** The app's own wireless-ADB broker (pairing, Wireless debugging, adbd). */
    BROKER("broker"),

    /** A Shizuku server (Shizuku, ShizukuTendCF, ...) through Plektra's UserService. */
    SHIZUKU("shizuku"),
}

/** The user's "Privileged access via" setting. */
enum class PrivilegedAccessMode(val prefValue: String) {
    /** Shizuku while its binder is alive and Plektra is allowed, otherwise the broker. */
    AUTOMATIC(SettingsManager.PRIVILEGED_ACCESS_AUTO),
    SHIZUKU_ONLY(SettingsManager.PRIVILEGED_ACCESS_SHIZUKU),
    BROKER_ONLY(SettingsManager.PRIVILEGED_ACCESS_BROKER);

    companion object {
        fun fromPref(value: String?): PrivilegedAccessMode =
            entries.firstOrNull { it.prefValue == value } ?: AUTOMATIC
    }
}

/** What Shizuku looks like from here. */
enum class ShizukuState {
    /** No Shizuku app on the phone (and no binder from a root-only server such as Sui). */
    NOT_INSTALLED,

    /** Installed, but its server is not running (no binder). */
    NOT_RUNNING,

    /** Running, but Plektra has not been allowed in Shizuku. */
    NOT_ALLOWED,

    /** Running and Plektra is allowed. */
    READY,
}

/**
 * What one command line did through Shizuku. [ok] means the command ran to its end within the
 * deadline and the output limit, whatever its exit code (the broker's adbd shell reports no exit
 * code, so callers judge the result by its output or by asking Android afterwards, as they do for
 * the broker). [exitCode] is null when the command did not finish. [serviceUnavailable] is true
 * when nothing ran because Shizuku or its service could not be reached.
 */
data class ShellRunResult(
    val ok: Boolean,
    val output: String?,
    val exitCode: Int?,
    val failure: String?,
    val serviceUnavailable: Boolean = false,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_OK, ok)
        putString(KEY_OUTPUT, output)
        putInt(KEY_EXIT, exitCode ?: NO_EXIT)
        putString(KEY_FAILURE, failure)
    }

    companion object {
        private const val KEY_OK = "ok"
        private const val KEY_OUTPUT = "output"
        private const val KEY_EXIT = "exit"
        private const val KEY_FAILURE = "failure"
        private const val NO_EXIT = Int.MIN_VALUE

        fun unavailable(reason: String) =
            ShellRunResult(ok = false, output = null, exitCode = null, failure = reason, serviceUnavailable = true)

        /**
         * Reads the service's answer. The output limit is checked here as well, so a misbehaving
         * service cannot hand the app more than the broker would accept. Decoding N bytes gives
         * at most N chars, so a longer string cannot have come from a capped run.
         */
        fun fromBundle(bundle: Bundle?, maxOutputBytes: Int): ShellRunResult {
            if (bundle == null) return unavailable("Shizuku service returned nothing")
            val output = bundle.getString(KEY_OUTPUT)
            val tooLong = output != null && output.length > maxOutputBytes
            if (tooLong) {
                return ShellRunResult(false, null, null, "shell output exceeded $maxOutputBytes bytes")
            }
            val exit = bundle.getInt(KEY_EXIT, NO_EXIT).takeIf { it != NO_EXIT }
            return ShellRunResult(
                ok = bundle.getBoolean(KEY_OK, false),
                output = output,
                exitCode = exit,
                failure = bundle.getString(KEY_FAILURE),
            )
        }
    }
}

/**
 * The Shizuku side of the door, behind an interface so tests can run without a Shizuku server.
 * [runLine] takes a raw command line: only [EmbeddedAdbShell.execute] may call it, after its
 * checks, with a line from [PrivilegedCommand.commandLine] (BrokerSourceRulesTest).
 */
internal interface ShizukuShell {
    fun state(context: Context): ShizukuState

    /** BLOCKING: binds the service if needed, then runs [commandLine]. Never throws. */
    fun runLine(context: Context, commandLine: String): ShellRunResult
}

/** Chooses the backend for the setting and Shizuku's state. */
object PrivilegedBackends {

    /** The real Shizuku; tests swap in a fake. */
    @Volatile
    internal var shizuku: ShizukuShell = ShizukuUserServiceShell

    /** The setting and Shizuku's state at one moment, and the backend they choose. */
    data class Resolved(
        val mode: PrivilegedAccessMode,
        val shizukuState: ShizukuState,
        val backend: PrivilegedBackend,
    )

    fun mode(context: Context): PrivilegedAccessMode =
        PrivilegedAccessMode.fromPref(SettingsManager.getPrivilegedAccessBackend(context))

    fun shizukuState(context: Context): ShizukuState =
        runCatching { shizuku.state(context.applicationContext) }.getOrDefault(ShizukuState.NOT_RUNNING)

    /**
     * The rule: Automatic picks Shizuku only when it is [ShizukuState.READY]; "Shizuku only" picks
     * Shizuku whatever its state (a command then fails with the reason, never falls back to the
     * broker); "Built-in broker only" never uses Shizuku.
     */
    fun choose(mode: PrivilegedAccessMode, state: ShizukuState): PrivilegedBackend = when (mode) {
        PrivilegedAccessMode.AUTOMATIC ->
            if (state == ShizukuState.READY) PrivilegedBackend.SHIZUKU else PrivilegedBackend.BROKER
        PrivilegedAccessMode.SHIZUKU_ONLY -> PrivilegedBackend.SHIZUKU
        PrivilegedAccessMode.BROKER_ONLY -> PrivilegedBackend.BROKER
    }

    fun resolve(context: Context): Resolved {
        val mode = mode(context)
        val state = shizukuState(context)
        return Resolved(mode, state, choose(mode, state))
    }

    fun current(context: Context): PrivilegedBackend = resolve(context).backend

    /**
     * Cheap pre-flight before a privileged action: can the chosen backend run at all? For the
     * broker that is "a key is stored" ([EmbeddedAdbShell.isPaired]); for Shizuku, "running and
     * allowed". Not a readiness claim for the UI: screens use the verified status.
     */
    fun hasAccess(context: Context): Boolean {
        val resolved = resolve(context)
        return when (resolved.backend) {
            PrivilegedBackend.SHIZUKU -> resolved.shizukuState == ShizukuState.READY
            PrivilegedBackend.BROKER -> EmbeddedAdbShell.isPaired(context)
        }
    }

    /** The reason a command could not run through Shizuku in [state]; null when it is ready. */
    fun shizukuUnavailableReason(state: ShizukuState): String? = when (state) {
        ShizukuState.READY -> null
        ShizukuState.NOT_ALLOWED -> REASON_SHIZUKU_NOT_ALLOWED
        ShizukuState.NOT_RUNNING -> REASON_SHIZUKU_NOT_RUNNING
        ShizukuState.NOT_INSTALLED -> REASON_SHIZUKU_NOT_INSTALLED
    }

    const val REASON_SHIZUKU_NOT_ALLOWED = "Shizuku is running but Plektra is not allowed."
    const val REASON_SHIZUKU_NOT_RUNNING = "Shizuku is not running."
    const val REASON_SHIZUKU_NOT_INSTALLED = "Shizuku is not installed."
}
