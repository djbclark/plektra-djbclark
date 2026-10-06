package it.palsoftware.pastiera.toolbox

import android.content.Context
import android.provider.Settings
import android.util.Log
import it.palsoftware.pastiera.inputmethod.EmbeddedAdbShell
import it.palsoftware.pastiera.inputmethod.PrivilegedCommand
import it.palsoftware.pastiera.inputmethod.PrivilegedResult
import it.palsoftware.pastiera.inputmethod.PrivilegedShell
import moe.shizuku.manager.adb.EmbeddedAdbInit

/**
 * Small system settings that Android supports but this ROM never surfaces.
 *
 * Unlike density these cannot make the phone unusable, so they apply directly rather than
 * through [RevertibleChange] — a countdown on "make animations faster" would be theatre. What
 * they do share is that every one is a plain settings write the broker can also undo, which
 * is why each can be put back to stock.
 *
 * The keys and values are constants here; [PrivilegedCommand] turns them into command lines.
 * Animation writes are confirmed by reading the setting back through the Settings API.
 *
 * BLOCKING — call off the main thread, and never from the IME input path.
 */
object SystemTweaks {

    private const val TAG = "SystemTweaks"

    /**
     * Android animates in three places and reads three separate globals. Setting one and not
     * the others is the classic half-done version of this tweak, where menus feel snappy and
     * app launches still crawl.
     */
    internal val ANIMATION_KEYS = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale"
    )

    /** [arg] is the literal written to the settings table. */
    enum class AnimationSpeed(val scale: Float, val arg: String) {
        OFF(0f, "0"),
        FAST(0.5f, "0.5"),
        NORMAL(1f, "1.0");

        companion object {
            fun nearest(scale: Float): AnimationSpeed =
                entries.minByOrNull { kotlin.math.abs(it.scale - scale) } ?: NORMAL
        }
    }

    /** A boolean the platform understands but this ROM leaves unset. */
    enum class Toggle(val namespace: String, val key: String) {
        /** Pixel's notification history: the log of what you swiped away. */
        NOTIFICATION_HISTORY("secure", "notification_history_enabled"),

        /**
         * Swipe down on the nav bar to pull the top of the screen into reach. The AOSP key is
         * `one_handed_mode_enabled` (PhysiBoard wrote `one_handed_enabled`, which nothing reads).
         */
        ONE_HANDED_MODE("secure", "one_handed_mode_enabled")
    }

    data class State(
        val animation: AnimationSpeed,
        val toggles: Map<Toggle, Boolean>
    )

    fun read(context: Context): State? {
        if (!EmbeddedAdbShell.isPaired(context)) return null
        val out = PrivilegedShell.run(context, PrivilegedCommand.ReadSystemTweaks).output ?: return null
        return parse(out)
    }

    /** Parses the output of [PrivilegedCommand.ReadSystemTweaks]: animation scale, then each toggle. */
    internal fun parse(output: String): State? {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null

        val animation = AnimationSpeed.nearest(lines.first().toFloatOrNull() ?: 1f)
        val toggles = Toggle.entries.mapIndexed { index, toggle ->
            // "null" is what settings prints for a key that was never written — which for
            // these two is the shipped state, not an error.
            toggle to (lines.getOrNull(index + 1) == "1")
        }.toMap()
        return State(animation = animation, toggles = toggles)
    }

    fun setAnimationSpeed(context: Context, speed: AnimationSpeed): Boolean {
        if (!PrivilegedShell.run(context, PrivilegedCommand.SetAnimationSpeed(speed)).ok) return false
        // Normal speed is the stock value; only a different one is a change to remember. Recorded
        // before the readback below, which can reject a write that did land.
        if (speed != AnimationSpeed.NORMAL) markChanged(context, true)
        // Public API, so this is a real second opinion rather than the shell agreeing with itself.
        val seen = runCatching {
            Settings.Global.getFloat(context.contentResolver, ANIMATION_KEYS.first())
        }.getOrNull()
        if (seen != null && kotlin.math.abs(seen - speed.scale) > 0.01f) {
            Log.w(TAG, "Animation scale reads $seen after writing ${speed.scale}")
            return false
        }
        return true
    }

    /**
     * Turning one of these off DELETES the key rather than writing 0: these ship unset, so
     * restoring means putting them back to absent, not to an explicit false the ROM never had.
     *
     * Not read back: the key is a hidden setting this app may not be allowed to read, and an
     * unreadable key looks exactly like an unset one.
     */
    fun setToggle(context: Context, toggle: Toggle, enabled: Boolean): Boolean {
        val ok = PrivilegedShell.run(context, PrivilegedCommand.SetTweak(toggle, enabled)).ok
        // Off deletes the key, which is the shipped state; only turning one on is a change.
        if (ok && enabled) markChanged(context, true)
        return ok
    }

    /** Everything on this screen back to how the phone shipped. */
    fun resetAll(context: Context): Boolean = resetAllWithReason(context).ok

    /**
     * [resetAll] with the reason it did not work. After the command the animation scales are read
     * back through the Settings API (the toggles are hidden keys this app may not read, so they
     * rest on the broker's own result). BLOCKING; off the main thread.
     */
    internal fun resetAllWithReason(context: Context): PrivilegedResult {
        val result = PrivilegedShell.run(context, PrivilegedCommand.ResetSystemTweaks)
        if (!result.ok) return result
        if (localAnimationScales(context).any { it != null && kotlin.math.abs(it - 1f) > ANIMATION_TOLERANCE }) {
            Log.w(TAG, "Animation scales still differ from stock after the reset")
            return PrivilegedResult(ok = false, output = null, failure = "The phone did not report the change")
        }
        markChanged(context, false)
        return result
    }

    // What "Reset to stock" asks. Local reads only, no broker: the animation scales are public
    // globals; the two toggles are hidden keys, so a refused or empty read is "unknown", and the
    // marker set by this app's own writes covers them.

    /** True when a tweak this app made is, or may still be, in effect. */
    fun isChanged(context: Context): Boolean = looksChanged(
        animationScales = localAnimationScales(context),
        toggleValues = Toggle.entries.map { localToggleValue(context, it) },
        touchedByApp = EmbeddedAdbInit.prefs(context.applicationContext).getBoolean(KEY_CHANGED, false)
    )

    /**
     * The decision behind [isChanged]: a marker from this app's own write, an animation scale away
     * from 1.0, or a toggle that reads as on. Null reads are unknown and decide nothing.
     */
    internal fun looksChanged(
        animationScales: List<Float?>,
        toggleValues: List<String?>,
        touchedByApp: Boolean
    ): Boolean = touchedByApp ||
        animationScales.any { it != null && kotlin.math.abs(it - 1f) > ANIMATION_TOLERANCE } ||
        toggleValues.any { it == "1" }

    private const val ANIMATION_TOLERANCE = 0.01f
    private const val KEY_CHANGED = "toolbox_tweaks_changed"

    internal fun markChanged(context: Context, changed: Boolean) {
        EmbeddedAdbInit.prefs(context.applicationContext).edit().putBoolean(KEY_CHANGED, changed).apply()
    }

    private fun localAnimationScales(context: Context): List<Float?> = ANIMATION_KEYS.map { key ->
        runCatching { Settings.Global.getFloat(context.contentResolver, key) }.getOrNull()
    }

    private fun localToggleValue(context: Context, toggle: Toggle): String? = runCatching {
        val cr = context.contentResolver
        when (toggle.namespace) {
            "secure" -> Settings.Secure.getString(cr, toggle.key)
            "global" -> Settings.Global.getString(cr, toggle.key)
            "system" -> Settings.System.getString(cr, toggle.key)
            else -> null
        }
    }.getOrNull()
}
