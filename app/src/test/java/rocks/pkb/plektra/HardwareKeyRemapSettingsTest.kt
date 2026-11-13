package rocks.pkb.plektra

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.PreferenceValueType
import rocks.pkb.plektra.inputmethod.KeyRemapJson
import rocks.pkb.plektra.inputmethod.KeyRemapRule
import rocks.pkb.plektra.inputmethod.KeyRemapRules
import rocks.pkb.plektra.inputmethod.KeySource

/** The stored hardware key remap rules: presets, the fork migration and the backup contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HardwareKeyRemapSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = SettingsManager.getPreferences(context)
    private val swapId = HardwareKeyRemapPresets.SWAP_SHIFT_RIGHT_ENTER
    private val swap = requireNotNull(HardwareKeyRemapPresets.byId(swapId))
    private val altAsSym = KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ALT_RIGHT), KeyEvent.KEYCODE_SYM)
    private val enterAsTab = KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_ENTER), KeyEvent.KEYCODE_TAB)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "titan2")
    }

    @Test
    fun presets_areValidRuleListsWithUniqueIds() {
        assertEquals(HardwareKeyRemapPresets.all.size, HardwareKeyRemapPresets.all.map { it.id }.toSet().size)
        for (preset in HardwareKeyRemapPresets.all) {
            assertEquals(preset.id, preset.rules, KeyRemapRules.compile(preset.rules).rules)
            assertTrue(preset.id, preset.profiles.isNotEmpty())
            assertEquals(preset.id, preset.profiles, preset.profiles.map(KeyRemapJson::profileKey).toSet())
        }
        // Both directions, and the right Shift key by scan code whatever key code it sends.
        val rules = KeyRemapRules.compile(swap.rules)
        assertEquals(KeyEvent.KEYCODE_ENTER, rules.targetFor(KeyEvent.KEYCODE_SHIFT_RIGHT, 54))
        assertEquals(KeyEvent.KEYCODE_ENTER, rules.targetFor(KeyEvent.KEYCODE_ALT_RIGHT, 54))
        // Another key that sends a Shift key code (the vendor setting moves Shift there) stays one.
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, rules.targetFor(KeyEvent.KEYCODE_SHIFT_RIGHT, 100))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, rules.targetFor(KeyEvent.KEYCODE_SHIFT_RIGHT, 0))
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, rules.targetFor(KeyEvent.KEYCODE_SHIFT_LEFT, 56))
        assertEquals(KeyEvent.KEYCODE_SHIFT_RIGHT, rules.targetFor(KeyEvent.KEYCODE_ENTER, 28))
        assertEquals(KeyEvent.KEYCODE_NUMPAD_ENTER, rules.targetFor(KeyEvent.KEYCODE_NUMPAD_ENTER, 96))
    }

    @Test
    fun presetSwitch_writesItsRulesAndClearsThemAgain() {
        assertFalse(SettingsManager.isKeyRemapPresetEnabled(context, swapId))
        assertTrue(SettingsManager.getHardwareKeyRemaps(context).isEmpty)

        assertTrue(SettingsManager.setKeyRemapPresetEnabled(context, swapId, true))

        assertTrue(SettingsManager.isKeyRemapPresetEnabled(context, swapId))
        assertEquals(swap.rules, SettingsManager.getHardwareKeyRemapRules(context, "titan2"))
        val stored = JSONObject(prefs.getString(SettingsManager.KEY_HARDWARE_KEY_REMAPS, null)!!)
        assertEquals(KeyRemapJson.VERSION, stored.getInt("v"))
        assertEquals(swap.rules.size, stored.getJSONObject("profiles").getJSONObject("titan2").getJSONArray("rules").length())

        assertFalse(SettingsManager.setKeyRemapPresetEnabled(context, swapId, false))

        assertTrue(SettingsManager.getHardwareKeyRemaps(context).isEmpty)
        assertFalse(prefs.contains(SettingsManager.KEY_HARDWARE_KEY_REMAPS))
    }

    @Test
    fun presetSwitch_replacesRulesForItsKeysAndKeepsTheOthers() {
        assertTrue(SettingsManager.setHardwareKeyRemapRules(context, "titan2", listOf(altAsSym, enterAsTab)))

        assertTrue(SettingsManager.setKeyRemapPresetEnabled(context, swapId, true))
        assertEquals(swap.rules + altAsSym, SettingsManager.getHardwareKeyRemapRules(context, "titan2"))

        assertFalse(SettingsManager.setKeyRemapPresetEnabled(context, swapId, false))
        assertEquals(listOf(altAsSym), SettingsManager.getHardwareKeyRemapRules(context, "titan2"))
    }

    @Test
    fun presetSwitch_isPerKeyboardProfile() {
        SettingsManager.setKeyRemapPresetEnabled(context, swapId, true)

        SettingsManager.setPhysicalKeyboardProfileOverride(context, "key2")
        assertFalse(SettingsManager.isKeyRemapPresetEnabled(context, swapId))
        assertTrue(SettingsManager.setKeyRemapPresetEnabled(context, swapId, true))
        assertFalse(SettingsManager.setKeyRemapPresetEnabled(context, swapId, false))

        SettingsManager.setPhysicalKeyboardProfileOverride(context, "titan2")
        assertTrue(SettingsManager.isKeyRemapPresetEnabled(context, swapId))
    }

    @Test
    fun preset_isOnlyForTheKeyboardsThatHaveItsKeys() {
        assertEquals(listOf(swap), HardwareKeyRemapPresets.forProfile("titan2elite_qwerty"))
        for (profile in listOf("q25", "Q25", "clicks_power", "unknown")) {
            assertTrue(profile, HardwareKeyRemapPresets.forProfile(profile).isEmpty())
        }

        SettingsManager.setPhysicalKeyboardProfileOverride(context, "q25")
        assertFalse(SettingsManager.setKeyRemapPresetEnabled(context, swapId, true))
        assertFalse(prefs.contains(SettingsManager.KEY_HARDWARE_KEY_REMAPS))
        assertFalse(SettingsManager.setKeyRemapPresetEnabled(context, "no_such_preset", true))
    }

    @Test
    fun compiledTable_isKeptUntilTheStoredRulesChange() {
        SettingsManager.setHardwareKeyRemapRules(context, "titan2", listOf(altAsSym))
        val table = SettingsManager.getHardwareKeyRemaps(context)

        assertSame(table, SettingsManager.getHardwareKeyRemaps(context))

        SettingsManager.setHardwareKeyRemapRules(context, "titan2", listOf(enterAsTab))
        assertNotSame(table, SettingsManager.getHardwareKeyRemaps(context))
        assertEquals(listOf(enterAsTab), SettingsManager.getHardwareKeyRemaps(context).rules("titan2"))
    }

    @Test
    fun invalidRules_areNotStored() {
        val capsAsCtrl = KeyRemapRule(KeySource.key(KeyEvent.KEYCODE_CAPS_LOCK), KeyEvent.KEYCODE_CTRL_LEFT)

        assertTrue(SettingsManager.setHardwareKeyRemapRules(context, "titan2", listOf(capsAsCtrl, altAsSym)))
        assertEquals(listOf(altAsSym), SettingsManager.getHardwareKeyRemapRules(context, "titan2"))

        assertTrue(SettingsManager.setHardwareKeyRemapRules(context, "titan2", listOf(capsAsCtrl)))
        assertFalse(prefs.contains(SettingsManager.KEY_HARDWARE_KEY_REMAPS))
    }

    @Test
    fun damagedStore_readsAsNoRulesAndIsReplacedByTheNextWrite() {
        prefs.edit().putString(SettingsManager.KEY_HARDWARE_KEY_REMAPS, "{not json").commit()

        assertTrue(SettingsManager.getHardwareKeyRemaps(context).isEmpty)
        assertTrue(SettingsManager.setKeyRemapPresetEnabled(context, swapId, true))
        assertEquals(swap.rules, SettingsManager.getHardwareKeyRemapRules(context, "titan2"))
    }

    @Test
    fun storeOfANewerVersion_isNotUsedAndNotReplaced() {
        val newer = """{"v":${KeyRemapJson.VERSION + 1},"profiles":{"titan2":{"rules":[{"match":"key","key":66,"to":60}]}}}"""
        prefs.edit().putString(SettingsManager.KEY_HARDWARE_KEY_REMAPS, newer).commit()

        assertTrue(SettingsManager.getHardwareKeyRemaps(context).isEmpty)
        assertFalse(SettingsManager.setKeyRemapPresetEnabled(context, swapId, true))
        assertFalse(SettingsManager.setHardwareKeyRemapRules(context, "titan2", listOf(altAsSym)))
        assertEquals(newer, prefs.getString(SettingsManager.KEY_HARDWARE_KEY_REMAPS, null))
    }

    @Test
    fun forkSwitch_migratesToThePresetsRulesOnce() {
        prefs.edit().putBoolean(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER, true).commit()

        assertEquals(swap.rules, SettingsManager.getHardwareKeyRemapRules(context, "titan2"))
        assertTrue(SettingsManager.isKeyRemapPresetEnabled(context, swapId))
        assertFalse(prefs.contains(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER))

        // Switched off afterwards, it stays off: the migration does not run again.
        SettingsManager.setKeyRemapPresetEnabled(context, swapId, false)
        assertTrue(SettingsManager.getHardwareKeyRemaps(context).isEmpty)
    }

    @Test
    fun forkSwitch_neverReplacesStoredRules() {
        SettingsManager.setHardwareKeyRemapRules(context, "titan2", listOf(altAsSym))
        // A restored backup of an early fork build brings the old switch back.
        prefs.edit().putBoolean(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER, true).commit()

        assertEquals(listOf(altAsSym), SettingsManager.getHardwareKeyRemapRules(context, "titan2"))
        assertFalse(prefs.contains(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER))
        assertEquals(listOf(altAsSym), SettingsManager.getHardwareKeyRemapRules(context, "titan2"))
    }

    @Test
    fun forkSwitch_thatWasOffOrOnAKeyboardWithoutThePreset_leavesNoRules() {
        prefs.edit().putBoolean(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER, false).commit()
        assertTrue(SettingsManager.getHardwareKeyRemaps(context).isEmpty)
        assertFalse(prefs.contains(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER))

        SettingsManager.setPhysicalKeyboardProfileOverride(context, "q25")
        prefs.edit().putBoolean(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER, true).commit()
        assertTrue(SettingsManager.getHardwareKeyRemaps(context).isEmpty)
        assertFalse(prefs.contains(SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER))
        assertFalse(prefs.contains(SettingsManager.KEY_HARDWARE_KEY_REMAPS))
    }

    @Test
    fun backupContract_exportsTheRulesAndStillTakesTheForkSwitch() {
        assertEquals(
            PreferenceValueType.STRING,
            BackupPreferenceContract.expectedExportType("plektra_prefs", SettingsManager.KEY_HARDWARE_KEY_REMAPS)
        )
        assertEquals(
            PreferenceValueType.BOOLEAN,
            BackupPreferenceContract.expectedExportType(
                "plektra_prefs",
                SettingsManager.LEGACY_KEY_REMAP_SWAP_SHIFT_RIGHT_ENTER
            )
        )
    }
}
