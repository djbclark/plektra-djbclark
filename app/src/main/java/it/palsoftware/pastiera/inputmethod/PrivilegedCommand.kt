package it.palsoftware.pastiera.inputmethod

import android.content.Context

/**
 * The closed set of shell commands the app may run through the embedded ADB broker.
 *
 * SECURITY RULE (broker review 1.2a): the broker runs whatever string it is given as the `shell`
 * user, so nothing may reach it except a case of this class. Every case is built from constants.
 * A value that has to vary is either an enum of constants ([BacklightTimeout]) or validated here
 * first (the app's own package name, against [PACKAGE_NAME]). No user text, intent extra, deep
 * link parameter, preference or other config value ever becomes part of a command line.
 *
 * A group that needs another privileged command adds one case below, with a comment naming the
 * group, and runs it through [PrivilegedShell]. [EmbeddedAdbShell.runShell] stays `internal` and
 * is called from nowhere else.
 */
internal sealed class PrivilegedCommand {

    /** The command line for this case. Throws if a value fails validation, so it never runs. */
    abstract fun commandLine(appPackage: String): String

    // djb/backlight: the commands the smart keyboard backlight and its Quick Settings tile need.

    /**
     * `pm grant` of WRITE_SECURE_SETTINGS to this app. Only the app's own package is ever granted;
     * the result is confirmed with `checkSelfPermission`, not by reading the shell's output.
     */
    object GrantWriteSecureSettings : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            return "pm grant $appPackage android.permission.WRITE_SECURE_SETTINGS"
        }
    }

    /** Reads the vendor keyboard-backlight timeout (`GET` on the vendor binder service). */
    object ReadBacklightTimeout : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String =
            "service call $VENDOR_SERVICE $TXN_GET s16 \"$KEY_BACKLIGHT_TIMEOUT\""
    }

    /** Writes the vendor keyboard-backlight timeout (`SET` on the vendor binder service). */
    class WriteBacklightTimeout(val timeout: BacklightTimeout) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String =
            "service call $VENDOR_SERVICE $TXN_SET s16 \"$KEY_BACKLIGHT_TIMEOUT\" s16 \"${timeout.wireValue}\""
    }

    /** The only two values the app ever writes for the vendor timeout. */
    enum class BacklightTimeout(val wireValue: String) {
        /** Sentinel: never turn off. Verified on a Titan 2 Elite, 2026-08-21. Survives reboots. */
        ALWAYS_ON("-1"),

        /** The stock 30 s default. */
        STOCK("30000")
    }

    companion object {
        /** Same shape the broker review asks for: dotted Java package, no shell metacharacters. */
        private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun isValidPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

        // The vendor binder service. transaction 2 = SET(String key, String value),
        // transaction 1 = GET(String key); GET returns a String16 parcel with the current value.
        // Confirmed on a Titan 2 Elite, 2026-08-31.
        private const val VENDOR_SERVICE = "agui_functional_service"
        private const val TXN_SET = "2"
        private const val TXN_GET = "1"
        private const val KEY_BACKLIGHT_TIMEOUT = "keyboard_brightness_timeout"
    }
}

/** What a [PrivilegedCommand] did. [failure] is a short reason when [ok] is false. */
internal data class PrivilegedResult(val ok: Boolean, val output: String?, val failure: String?)

/**
 * The one door to the broker for the rest of the app: it takes a [PrivilegedCommand], never a
 * string. Blocking; call it off the main thread, and never from the IME's key-handling path
 * (broker review 1.2b).
 */
internal object PrivilegedShell {
    // One queue for every privileged step, so two steps started together run one after the other
    // instead of competing for the broker's mDNS discovery.
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Runs [block] off the caller's thread, after any step already queued. Never throws. */
    fun runAsync(block: () -> Unit) {
        runCatching { executor.execute { runCatching(block) } }
    }

    fun run(context: Context, command: PrivilegedCommand): PrivilegedResult {
        val appContext = context.applicationContext
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            return PrivilegedResult(ok = false, output = null, failure = blocker)
        }
        val line = runCatching { command.commandLine(appContext.packageName) }
            .getOrElse { return PrivilegedResult(ok = false, output = null, failure = it.message) }
        val output = EmbeddedAdbShell.runShellCapturing(appContext, line)
        return if (output != null) {
            PrivilegedResult(ok = true, output = output, failure = null)
        } else {
            PrivilegedResult(
                ok = false,
                output = null,
                failure = EmbeddedAdbShell.lastError ?: PrivilegedDiagnostics.REASON_SHELL_FAILED
            )
        }
    }
}
package it.palsoftware.pastiera.inputmethod

import android.content.Context

/**
 * The closed set of shell commands the app may run through the embedded ADB broker.
 *
 * SECURITY RULE (broker review 1.2a): the broker runs whatever string it is given as the `shell`
 * user, so nothing may reach it except a case of this class. Every case is built from constants.
 * A value that has to vary is either an enum of constants ([BacklightTimeout]) or validated here
 * first (the app's own package name, against [PACKAGE_NAME]). No user text, intent extra, deep
 * link parameter, preference or other config value ever becomes part of a command line.
 *
 * A group that needs another privileged command adds one case below, with a comment naming the
 * group, and runs it through [PrivilegedShell]. [EmbeddedAdbShell.runShell] stays `internal` and
 * is called from nowhere else.
 */
internal sealed class PrivilegedCommand {

    /** The command line for this case. Throws if a value fails validation, so it never runs. */
    abstract fun commandLine(appPackage: String): String

    // djb/backlight: the commands the smart keyboard backlight and its Quick Settings tile need.

    /**
     * `pm grant` of WRITE_SECURE_SETTINGS to this app. Only the app's own package is ever granted;
     * the result is confirmed with `checkSelfPermission`, not by reading the shell's output.
     */
    object GrantWriteSecureSettings : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            return "pm grant $appPackage android.permission.WRITE_SECURE_SETTINGS"
        }
    }

    /** Reads the vendor keyboard-backlight timeout (`GET` on the vendor binder service). */
    object ReadBacklightTimeout : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String =
            "service call $VENDOR_SERVICE $TXN_GET s16 \"$KEY_BACKLIGHT_TIMEOUT\""
    }

    /** Writes the vendor keyboard-backlight timeout (`SET` on the vendor binder service). */
    class WriteBacklightTimeout(val timeout: BacklightTimeout) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String =
            "service call $VENDOR_SERVICE $TXN_SET s16 \"$KEY_BACKLIGHT_TIMEOUT\" s16 \"${timeout.wireValue}\""
    }

    /** The only two values the app ever writes for the vendor timeout. */
    enum class BacklightTimeout(val wireValue: String) {
        /** Sentinel: never turn off. Verified on a Titan 2 Elite, 2026-08-21. Survives reboots. */
        ALWAYS_ON("-1"),

        /** The stock 30 s default. */
        STOCK("30000")
    }

    // djb/ring: the grants the notification ring needs. ONE command carrying a list, so a single broker
    // connection does all of them; only the cases of [RingGrant] can be named, and the listener
    // component is the validated package plus a constant class name.
    class RingGrants(val grants: List<RingGrant>) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(appPackage)) { "invalid package name" }
            require(grants.isNotEmpty()) { "no grants" }
            return grants.joinToString("; ") { it.line(appPackage) }
        }
    }

    enum class RingGrant {
        /** Notification access for the ring listener. */
        ALLOW_LISTENER,

        /** Lets the ring announce itself with a full-screen intent over the lock screen. */
        ALLOW_FULL_SCREEN_INTENT,

        /** The silent notification the full-screen intent hangs on. */
        ALLOW_POST_NOTIFICATIONS,

        /** The backlight group's WRITE_SECURE_SETTINGS grant, for the keyboard-dark switch. */
        WRITE_SECURE_SETTINGS,

        /** Undo of [ALLOW_LISTENER], for a reset to stock. */
        DISALLOW_LISTENER,

        /** Undo of [ALLOW_FULL_SCREEN_INTENT], for a reset to stock. */
        RESET_FULL_SCREEN_INTENT;

        fun line(appPackage: String): String = when (this) {
            ALLOW_LISTENER -> "cmd notification allow_listener $appPackage/$RING_LISTENER_CLASS"
            ALLOW_FULL_SCREEN_INTENT -> "appops set $appPackage USE_FULL_SCREEN_INTENT allow"
            ALLOW_POST_NOTIFICATIONS -> "appops set $appPackage POST_NOTIFICATION allow"
            WRITE_SECURE_SETTINGS -> GrantWriteSecureSettings.commandLine(appPackage)
            DISALLOW_LISTENER -> "cmd notification disallow_listener $appPackage/$RING_LISTENER_CLASS"
            RESET_FULL_SCREEN_INTENT -> "appops set $appPackage USE_FULL_SCREEN_INTENT default"
        }
    }

    companion object {
        /** Same shape the broker review asks for: dotted Java package, no shell metacharacters. */
        private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun isValidPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

        /** djb/ring: the ring's notification listener, as a constant so no class is loaded to name it. */
        const val RING_LISTENER_CLASS = "it.palsoftware.pastiera.ring.NotificationRingListener"

        // The vendor binder service. transaction 2 = SET(String key, String value),
        // transaction 1 = GET(String key); GET returns a String16 parcel with the current value.
        // Confirmed on a Titan 2 Elite, 2026-08-31.
        private const val VENDOR_SERVICE = "agui_functional_service"
        private const val TXN_SET = "2"
        private const val TXN_GET = "1"
        private const val KEY_BACKLIGHT_TIMEOUT = "keyboard_brightness_timeout"
    }
}

/** What a [PrivilegedCommand] did. [failure] is a short reason when [ok] is false. */
internal data class PrivilegedResult(val ok: Boolean, val output: String?, val failure: String?)

/**
 * The one door to the broker for the rest of the app: it takes a [PrivilegedCommand], never a
 * string. Blocking; call it off the main thread, and never from the IME's key-handling path
 * (broker review 1.2b).
 */
internal object PrivilegedShell {
    // One queue for every privileged step, so two steps started together run one after the other
    // instead of competing for the broker's mDNS discovery.
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Runs [block] off the caller's thread, after any step already queued. Never throws. */
    fun runAsync(block: () -> Unit) {
        runCatching { executor.execute { runCatching(block) } }
    }

    fun run(context: Context, command: PrivilegedCommand): PrivilegedResult {
        val appContext = context.applicationContext
        PrivilegedDiagnostics.brokerBlocker(appContext)?.let { blocker ->
            return PrivilegedResult(ok = false, output = null, failure = blocker)
        }
        val line = runCatching { command.commandLine(appContext.packageName) }
            .getOrElse { return PrivilegedResult(ok = false, output = null, failure = it.message) }
        val output = EmbeddedAdbShell.runShellCapturing(appContext, line)
        return if (output != null) {
            PrivilegedResult(ok = true, output = output, failure = null)
        } else {
            PrivilegedResult(
                ok = false,
                output = null,
                failure = EmbeddedAdbShell.lastError ?: PrivilegedDiagnostics.REASON_SHELL_FAILED
            )
        }
    }
}
