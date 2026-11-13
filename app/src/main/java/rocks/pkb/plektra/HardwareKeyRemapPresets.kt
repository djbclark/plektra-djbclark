package rocks.pkb.plektra

import android.view.KeyEvent
import androidx.annotation.StringRes
import rocks.pkb.plektra.inputmethod.KeyRemapJson
import rocks.pkb.plektra.inputmethod.KeyRemapRule
import rocks.pkb.plektra.inputmethod.KeySource

/**
 * A named list of hardware key remap rules that the settings offer as one switch. Switching it on
 * writes [rules] into the stored rules of the built-in keyboard's profile, switching it off takes
 * them out again ([SettingsManager.setKeyRemapPresetEnabled]); the remapper only ever sees rules.
 */
class KeyRemapPreset(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    /** The keyboard profiles the preset makes sense on; elsewhere the settings hide it. */
    val profiles: Set<String>,
    val rules: List<KeyRemapRule>
) {
    fun supports(profileId: String): Boolean = KeyRemapJson.profileKey(profileId) in profiles
}

object HardwareKeyRemapPresets {
    const val SWAP_SHIFT_RIGHT_ENTER = "swap_shift_right_enter"

    private const val SCAN_CODE_RIGHT_SHIFT = 54 // KEY_RIGHTSHIFT, linux/input-event-codes.h

    val all: List<KeyRemapPreset> = listOf(
        KeyRemapPreset(
            id = SWAP_SHIFT_RIGHT_ENTER,
            titleRes = R.string.key_remap_swap_shift_right_enter_title,
            summaryRes = R.string.key_remap_swap_shift_right_enter_summary,
            // Only where the right Shift key is known to report scan code 54. Anywhere else half
            // the swap would apply: Enter would become Shift and nothing would type Enter. That
            // is known for the Q25 (its physical right Shift is its Ctrl key) and for the Titan 2
            // (one Shift, scan code 42); the other profiles are unverified. A keyboard joins
            // this list once its right Shift is seen on the key test screen.
            profiles = setOf("titan2elite_qwerty"),
            rules = listOf(
                // The right Shift key by scan code, and by nothing else: a vendor key setting
                // rewrites key codes (the Titan 2's Shortcut keys > Key setting makes this key
                // send Alt), so its key code may be missing here and may come from another key
                // there, which must stay a Shift.
                KeyRemapRule(
                    KeySource.scan(SCAN_CODE_RIGHT_SHIFT, KeyEvent.KEYCODE_SHIFT_RIGHT),
                    KeyEvent.KEYCODE_ENTER
                ),
                // Only the main Enter key, not the numpad one.
                KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER), KeyEvent.KEYCODE_SHIFT_RIGHT)
            )
        )
    )

    fun byId(id: String): KeyRemapPreset? = all.firstOrNull { it.id == id }

    /** The presets offered for the keyboard profile [profileId]. */
    fun forProfile(profileId: String): List<KeyRemapPreset> = all.filter { it.supports(profileId) }
}
