package rocks.pkb.plektra.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsResetManagerTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // --- Confirmation word ---

    @Test
    fun confirmationAcceptsTheWordIgnoringCaseAndSurroundingSpace() {
        listOf("reset", " Reset ", "RESET", "\treset\n").forEach {
            assertTrue("'$it' should confirm", SettingsResetManager.isConfirmationPhrase(it))
        }
    }

    @Test
    fun confirmationRejectsAnythingElse() {
        listOf("", " ", "rese", "resets", "re set", "reset all", "zurücksetzen").forEach {
            assertFalse("'$it' should not confirm", SettingsResetManager.isConfirmationPhrase(it))
        }
    }

    // --- The plan (no device state) ---

    @Test
    fun planRemovesBackedUpKeysIncludingKeyRemaps() {
        val plan = SettingsResetManager.plan(
            currentPrefKeys = listOf(
                "swipe_to_delete",
                "keyboard_layout",
                "keyboard_theme_saved_themes",
                "key_remap_swap_shift_right_enter",
                "sym_mappings_custom"
            ),
            existingFiles = emptyList()
        )
        assertEquals(
            setOf(
                "swipe_to_delete",
                "keyboard_layout",
                "keyboard_theme_saved_themes",
                "key_remap_swap_shift_right_enter",
                "sym_mappings_custom"
            ),
            plan.prefKeysToRemove
        )
        assertTrue(plan.prefKeysKept.isEmpty())
    }

    @Test
    fun planKeepsWhatBackupDoesNotExportAndWhatTheResetDeliberatelyKeeps() {
        val notBackedUp = listOf(
            "tutorial_completed",
            "last_seen_whats_new_version",
            "dismissed_releases",
            "ring_backlight_prev",
            "ring_backlight_prev_captured",
            "alt_shift_default_initialized",
            "some_future_key_backup_does_not_know"
        )
        val kept = listOf(
            "user_dictionary_entries",
            "snippets_v1",
            "auto_correct_custom_en",
            "privileged_access_backend",
            "trackpad_shizuku_device",
            "clicks_power_keyboard_snapshots_v1",
            "clicks_power_soc_calibration_PK-42",
            "smart_backlight_enabled",
            "notification_ring_enabled",
            "titan2_layout_enabled",
            "titan2_elite_rounded_corner_insets",
            "physical_keyboard_profile_override"
        )
        val plan = SettingsResetManager.plan(notBackedUp + kept + "swipe_to_delete", emptyList())
        assertEquals(setOf("swipe_to_delete"), plan.prefKeysToRemove)
        assertEquals(kept.toSet(), plan.prefKeysKept)
    }

    @Test
    fun everyKeptKeyIsOneThatBackupExports() {
        SettingsResetManager.keptPrefKeys.forEach {
            assertTrue("$it is not a backed-up key, so keeping it is a typo", BackupPreferenceContract.isExportable("plektra_prefs", it))
        }
        // One real backed-up key per kept family, so a renamed family cannot silently stop matching.
        val examples = listOf(
            "auto_correct_custom_en",
            "clicks_power_soc_calibration_PK-42",
            "notification_ring_enabled",
            "titan2_layout_enabled"
        )
        assertEquals(SettingsResetManager.keptPrefKeyPrefixes.size, examples.size)
        SettingsResetManager.keptPrefKeyPrefixes.forEach { prefix ->
            val example = examples.single { it.startsWith(prefix) }
            assertTrue("$example is not backed up", BackupPreferenceContract.isExportable("plektra_prefs", example))
        }
    }

    @Test
    fun planDeletesSettingFilesAndKeepsDictionariesAndUserWords() {
        val plan = SettingsResetManager.plan(
            emptyList(),
            listOf(
                "ctrl_key_mappings.json",
                "variations.json",
                "locale_layout_mapping.json",
                "keyboard_layouts/mine.json",
                "typing_sounds/custom_pack/normal/a.wav",
                "user_defaults.json",
                "dictionary_packages.json",
                "dictionaries_serialized/custom/de.bin",
                "something_unrelated.txt"
            )
        )
        assertEquals(
            listOf(
                "ctrl_key_mappings.json",
                "keyboard_layouts/mine.json",
                "locale_layout_mapping.json",
                "typing_sounds/custom_pack/normal/a.wav",
                "variations.json"
            ),
            plan.filesToDelete
        )
    }

    @Test
    fun everyFileBackupSavesIsEitherDeletedOrKept() {
        val deleted = SettingsResetManager.deletedFileRoots
        val kept = SettingsResetManager.keptFileRoots
        assertTrue("a root is both deleted and kept", deleted.intersect(kept).isEmpty())
        assertEquals(
            "Backup saves a file root that Reset all settings has not decided about",
            FileBackupHelper.managedRestoreRoots(),
            deleted + kept
        )
    }

    // --- The reset on a device-like state ---

    @Test
    fun resetRestoresDefaultsAndKeepsUserContentAndSetupState() {
        prefs.edit()
            .putBoolean("swipe_to_delete", true)
            .putString("keyboard_layout", "my_custom")
            .putString("keyboard_theme_saved_themes", "[{}]")
            .putBoolean("key_remap_swap_shift_right_enter", true)
            .putString("user_dictionary_entries", "hello")
            .putString("auto_correct_custom_en", "{}")
            .putString("privileged_access_backend", "shizuku")
            .putBoolean("notification_ring_enabled", true)
            .putString("ring_backlight_prev", "1")
            .putBoolean("tutorial_completed", true)
            .putInt("last_seen_whats_new_version", 7)
            .putString("physical_keyboard_profile_override", "titan2")
            .commit()
        val notes = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
        notes.edit().putString("body", "my note").commit()

        val ctrlMappings = file("ctrl_key_mappings.json").apply { writeText("{\"custom\":true}") }
        file("variations.json").writeText("{}")
        file("locale_layout_mapping.json").writeText("{}")
        file("keyboard_layouts/mine.json").writeText("{}")
        file("typing_sounds/custom_pack/normal/a.wav").writeText("RIFF")
        file("user_defaults.json").writeText("[{\"w\":\"hello\",\"f\":3}]")
        file("dictionary_packages.json").writeText("{}")
        file("dictionaries_serialized/custom/de.bin").writeText("dictionary")

        val result = SettingsResetManager.resetBlocking(context)

        assertTrue("reset failed: $result", result is SettingsResetResult.Success)

        // Removed: backed-up settings, key remaps (including Shift/Enter swap).
        assertFalse(prefs.contains("swipe_to_delete"))
        assertFalse(prefs.contains("keyboard_layout"))
        assertFalse(prefs.contains("keyboard_theme_saved_themes"))
        assertFalse(prefs.contains("key_remap_swap_shift_right_enter"))
        // Kept: words, privileged setup, toolbox records, onboarding, device identity.
        assertEquals("hello", prefs.getString("user_dictionary_entries", null))
        assertEquals("{}", prefs.getString("auto_correct_custom_en", null))
        assertEquals("shizuku", prefs.getString("privileged_access_backend", null))
        assertTrue(prefs.getBoolean("notification_ring_enabled", false))
        assertEquals("1", prefs.getString("ring_backlight_prev", null))
        assertTrue(prefs.getBoolean("tutorial_completed", false))
        assertEquals(7, prefs.getInt("last_seen_whats_new_version", 0))
        assertEquals("titan2", prefs.getString("physical_keyboard_profile_override", null))
        assertEquals("my note", notes.getString("body", null))

        // Deleted files: the custom Ctrl mappings are gone or back to the bundled default.
        assertTrue(!ctrlMappings.exists() || ctrlMappings.readText() != "{\"custom\":true}")
        assertFalse(file("variations.json").exists())
        assertFalse(file("locale_layout_mapping.json").exists())
        assertFalse(file("keyboard_layouts/mine.json").exists())
        assertFalse(file("typing_sounds").exists())
        // Kept files: the user's words and the downloaded dictionaries.
        assertEquals("[{\"w\":\"hello\",\"f\":3}]", file("user_defaults.json").readText())
        assertEquals("{}", file("dictionary_packages.json").readText())
        assertEquals("dictionary", file("dictionaries_serialized/custom/de.bin").readText())
    }

    @Test
    fun aSecondResetChangesNothingAndStillSucceeds() {
        prefs.edit().putBoolean("swipe_to_delete", true).putString("user_dictionary_entries", "hello").commit()
        file("variations.json").writeText("{}")
        assertTrue(SettingsResetManager.resetBlocking(context) is SettingsResetResult.Success)
        val afterFirst = prefs.all.filterKeys { it != "nav_mode_mappings_updated" && it != "variations_updated" &&
            it != "keyboard_layout_auto_mapping_updated" && it != "typing_sound_updated_at" }

        val second = SettingsResetManager.resetBlocking(context)

        assertTrue(second is SettingsResetResult.Success)
        assertTrue((second as SettingsResetResult.Success).removedPrefKeys.isEmpty())
        assertEquals(
            afterFirst,
            prefs.all.filterKeys { it != "nav_mode_mappings_updated" && it != "variations_updated" &&
                it != "keyboard_layout_auto_mapping_updated" && it != "typing_sound_updated_at" }
        )
        assertFalse(file("variations.json").exists())
    }

    @Test
    fun resetFiresPerKeyListenersAndNeverAClearWithANullKey() {
        prefs.edit().putBoolean("swipe_to_delete", true).putString("keyboard_layout", "my_custom").commit()
        shadowOf(Looper.getMainLooper()).idle()
        val seen = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> seen.add(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        SettingsResetManager.resetBlocking(context)
        shadowOf(Looper.getMainLooper()).idle()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

        assertTrue("swipe_to_delete listener did not fire: $seen", "swipe_to_delete" in seen)
        assertTrue("keyboard_layout listener did not fire: $seen", "keyboard_layout" in seen)
        assertFalse("a null key means clear() was used: $seen", null in seen)
        assertNotEquals(0, seen.size)
    }

    private fun file(relative: String): File =
        File(context.filesDir, relative).also { it.parentFile?.mkdirs() }
}
