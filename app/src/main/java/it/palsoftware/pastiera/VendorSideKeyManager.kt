package it.palsoftware.pastiera

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import it.palsoftware.pastiera.inputmethod.AssistantTriggerActivity
import it.palsoftware.pastiera.inputmethod.PrivilegedSetup

/**
 * The Titan's orange side key ("func1").
 *
 * The key never reaches an input method — the vendor layer reads a package/activity pair out of
 * `Settings.System` and launches it directly. That makes the key unreachable by normal means but
 * fully redirectable: point the pair at [AssistantTriggerActivity] and a long press starts the
 * assistant listening instead of opening the Gemini app's front door.
 *
 * These writes live at the OS level and SURVIVE AN UNINSTALL, so the original values are captured
 * before the first overwrite and handed back by [restoreLongPress]. Writes need WRITE_SETTINGS;
 * a privileged shell (the wireless-debugging broker) is the optional second route, see
 * [privilegedSettingsPut]. Reading the slot needs no permission, so [isAssistantBound] works
 * without either.
 *
 * Call OFF the main thread: a privileged shell, if one is wired in, blocks.
 */
object VendorSideKeyManager {
    private const val TAG = "VendorSideKeyManager"

    /** The long-press slot: stock points it at the Gemini app's entry activity. */
    private const val LONG_PRESS_PACKAGE = "func1_long_press_package"
    private const val LONG_PRESS_ACTIVITY = "func1_long_press_activity"
    /** The vendor ignores every slot unless this is on. */
    private const val SHORTCUT_ENABLE = "func1_shortcut_key_enable"

    /**
     * Android package and class names, and nothing else.
     *
     * The restore path feeds values that were READ BACK from `Settings.System` to the broker,
     * and the broker is an adb shell — any app holding WRITE_SETTINGS could otherwise plant
     * shell metacharacters in the vendor's slot and have Reset-to-stock run them at shell
     * privilege. Anything that is not a plain component name is refused rather than escaped,
     * so a malformed value can never reach a command line in the first place.
     */
    private val COMPONENT_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.$]*$")

    internal fun isSafeValue(value: String?): Boolean =
        value != null && value.length <= 256 && COMPONENT_NAME.matches(value)

    enum class Outcome { SUCCESS, NEEDS_PERMISSION, FAILED }

    /** Whether the long-press slot currently points at us. */
    fun isAssistantBound(context: Context): Boolean {
        return readSetting(context, LONG_PRESS_ACTIVITY) == triggerComponent(context).className &&
            readSetting(context, LONG_PRESS_PACKAGE) == context.packageName
    }

    /** Points the orange key's long press at [AssistantTriggerActivity]. */
    fun bindAssistantToLongPress(context: Context): Outcome {
        val currentPackage = readSetting(context, LONG_PRESS_PACKAGE)
        val currentActivity = readSetting(context, LONG_PRESS_ACTIVITY)
        // Only a well-formed pair is worth remembering: a value we would refuse to write back
        // is not a restore point, and recording it would only promise an undo we cannot honour.
        if (isSafeValue(currentPackage) && isSafeValue(currentActivity)) {
            SettingsManager.captureSideKeyOriginal(
                context = context,
                packageName = currentPackage,
                activity = currentActivity
            )
        } else {
            Log.w(TAG, "Side key points somewhere unrecognisable; not recording a restore point")
        }
        val component = triggerComponent(context)
        return write(
            context = context,
            values = listOf(
                LONG_PRESS_PACKAGE to context.packageName,
                LONG_PRESS_ACTIVITY to component.className,
                // Stock has this on; set it anyway so the binding cannot land inert.
                SHORTCUT_ENABLE to "1"
            )
        )
    }

    /**
     * Restores whatever the long press did before we touched it. When nothing was captured the
     * slot is left alone rather than guessed at — a wrong guess would be worse than a stale
     * binding the user can see and change in the vendor's own settings app.
     */
    fun restoreLongPress(context: Context): Outcome {
        if (!SettingsManager.isSideKeyOriginalCaptured(context)) return Outcome.SUCCESS
        val previousPackage = SettingsManager.getSideKeyOriginalPackage(context)
        val previousActivity = SettingsManager.getSideKeyOriginalActivity(context)
        if (!isSafeValue(previousPackage) || !isSafeValue(previousActivity)) {
            // Nothing safe to hand back. Drop the capture instead of writing it: the key keeps
            // pointing at Pastiera, which the user can see and change, and no untrusted text
            // reaches the shell.
            Log.w(TAG, "Refusing to restore a malformed side-key target")
            SettingsManager.clearSideKeyOriginal(context)
            return Outcome.SUCCESS
        }
        val outcome = write(
            context = context,
            values = listOf(
                LONG_PRESS_PACKAGE to previousPackage!!,
                LONG_PRESS_ACTIVITY to previousActivity!!
            )
        )
        if (outcome == Outcome.SUCCESS) SettingsManager.clearSideKeyOriginal(context)
        return outcome
    }

    private fun triggerComponent(context: Context) =
        ComponentName(context, AssistantTriggerActivity::class.java)

    private fun readSetting(context: Context, key: String): String? = runCatching {
        Settings.System.getString(context.contentResolver, key)
    }.getOrNull()

    private fun write(context: Context, values: List<Pair<String, String>>): Outcome {
        // Second gate, so no future caller can route unchecked text into the broker's shell.
        if (values.any { !isSafeValue(it.second) }) {
            Log.w(TAG, "Refusing to write an unsafe side-key value")
            return Outcome.FAILED
        }
        if (PrivilegedSetup.canWriteSystemSettings(context)) {
            val ok = runCatching {
                values.forEach { (key, value) ->
                    Settings.System.putString(context.contentResolver, key, value)
                }
            }.onFailure { Log.w(TAG, "Direct write failed", it) }.isSuccess
            if (ok) return Outcome.SUCCESS
        }
        // Only values that passed isSafeValue get here, so they are safe on a command line.
        val viaShell = privilegedSettingsPut(context, values)
        if (viaShell != null) return if (viaShell) Outcome.SUCCESS else Outcome.FAILED
        return Outcome.NEEDS_PERMISSION
    }

    /**
     * Optional second write route: `settings put system <key> <value>` through a privileged shell.
     *
     * @return null when no privileged shell is available (the caller then asks for
     *   WRITE_SETTINGS), otherwise whether every put succeeded.
     */
    // djb/voice: hook for the wireless-debugging broker (djb/broker). That group's
    // EmbeddedAdbShell.isPaired/runShell replaces this body; until it lands this returns null.
    @Suppress("UNUSED_PARAMETER")
    private fun privilegedSettingsPut(context: Context, values: List<Pair<String, String>>): Boolean? = null
}
