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
package it.palsoftware.pastiera.inputmethod

import android.content.Context
import it.palsoftware.pastiera.toolbox.BloatCatalog // djb/toolbox
import it.palsoftware.pastiera.toolbox.SystemTweaks // djb/toolbox

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

    // djb/toolbox: the commands the Titan 2 Elite toolbox needs (bloat remover, display density,
    // system tweaks, Fn -> Ctrl). Package names are validated with [isValidPackageName], removal
    // also needs the catalog's approval; numbers are Ints in a fixed range; settings keys and
    // values come from constants and enums in this block and in `toolbox.SystemTweaks`.

    /** Three read-only listings in one round trip: enabled, disabled, and everything. */
    object ListPackageStates : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String =
            "echo __E__; pm list packages -e; echo __D__; pm list packages -d; echo __U__; pm list packages -u"
    }

    /** Hide a catalog package and stop it running. Reversible. */
    class DisablePackage(val packageName: String) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(isRemovableCatalogPackage(packageName)) { "package is not removable" }
            return "pm disable-user --user 0 $packageName"
        }
    }

    /** Uninstall a catalog package for user 0. The APK stays on /system, so it can come back. */
    class UninstallPackage(val packageName: String) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(isRemovableCatalogPackage(packageName)) { "package is not removable" }
            return "pm uninstall --user 0 $packageName"
        }
    }

    /**
     * Bring back a package the journal says the toolbox removed. Only the name shape is checked:
     * the catalog may have retired the entry since, and a journaled package must still come back.
     */
    class InstallExistingPackage(val packageName: String) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(packageName)) { "invalid package name" }
            return "cmd package install-existing --user 0 $packageName"
        }
    }

    /** Re-enable a package that was disabled. Same name rule as [InstallExistingPackage]. */
    class EnablePackage(val packageName: String) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(isValidPackageName(packageName)) { "invalid package name" }
            return "pm enable --user 0 $packageName"
        }
    }

    object ReadDensity : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String = "wm density"
    }

    object ResetDensity : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String = "wm density reset"
    }

    /** `wm density <dpi>`, the dpi an Int inside [MIN_DPI]..[MAX_DPI]. */
    class SetDensity(val dpi: Int) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(dpi in MIN_DPI..MAX_DPI) { "density out of range" }
            return "wm density $dpi"
        }

        companion object {
            const val MIN_DPI = 100
            const val MAX_DPI = 1000
        }
    }

    /** One `settings get` per tweak, in the order `SystemTweaks.parse` reads them. */
    object ReadSystemTweaks : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String = buildList {
            add("settings get global ${SystemTweaks.ANIMATION_KEYS.first()}")
            SystemTweaks.Toggle.entries.forEach { add("settings get ${it.namespace} ${it.key}") }
        }.joinToString("; ")
    }

    class SetAnimationSpeed(val speed: SystemTweaks.AnimationSpeed) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String =
            SystemTweaks.ANIMATION_KEYS.joinToString("; ") { "settings put global $it ${speed.arg}" }
    }

    /** On writes 1; off DELETES the key, because these ship unset. */
    class SetTweak(val toggle: SystemTweaks.Toggle, val enabled: Boolean) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String =
            if (enabled) {
                "settings put ${toggle.namespace} ${toggle.key} 1"
            } else {
                "settings delete ${toggle.namespace} ${toggle.key}"
            }
    }

    object ResetSystemTweaks : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String = (
            SystemTweaks.ANIMATION_KEYS.map { "settings put global $it 1.0" } +
                SystemTweaks.Toggle.entries.map { "settings delete ${it.namespace} ${it.key}" }
            ).joinToString("; ")
    }

    /**
     * Writes the two vendor programmable-key rows. Both are Ints in 0..[MAX_VALUE]: 1/1 maps Fn to
     * Ctrl, and a revert writes back whatever small integers were there before.
     */
    class SetFnProgrammableKey(val enable: Int, val function: Int) : PrivilegedCommand() {
        override fun commandLine(appPackage: String): String {
            require(enable in 0..MAX_VALUE && function in 0..MAX_VALUE) { "value out of range" }
            return "settings put system $KEY_ENABLE $enable; settings put system $KEY_FUNCTION $function"
        }

        companion object {
            const val KEY_ENABLE = "fn_programmable_key_enable"
            const val KEY_FUNCTION = "fn_programmable_key_function"
            const val MAX_VALUE = 1000
        }
    }

    companion object {
        /** Same shape the broker review asks for: dotted Java package, no shell metacharacters. */
        private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun isValidPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

        // djb/toolbox: valid AND approved by the toolbox catalog (its deny-list and patterns apply).
        fun isRemovableCatalogPackage(name: String): Boolean =
            isValidPackageName(name) && BloatCatalog.isRemovable(name)

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
