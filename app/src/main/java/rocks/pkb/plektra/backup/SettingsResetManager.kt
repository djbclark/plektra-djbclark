package rocks.pkb.plektra.backup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.layout.LayoutFileStore
import rocks.pkb.plektra.inputmethod.subtype.AdditionalSubtypeUtils
import java.io.File

/**
 * "Reset all settings": puts everything Backup saves back to its defaults, except the things
 * listed in [keptPrefKeys], [keptPrefKeyPrefixes] and [keptFileRoots].
 *
 * The scope mirrors [BackupPreferenceContract] and [FileBackupHelper]: only keys that a backup
 * would export are ever removed, so preferences Backup does not know about (first-run and tutorial
 * flags, release-notes state, migration markers, the toolbox's records of system changes) are never
 * touched. [BackupPreferencePolicy.runtimeDerivedKeys] and
 * [BackupPreferencePolicy.targetDeviceDerivedKeys] are kept too.
 *
 * Undoing system changes (density, backlight, ring, Fn/Ctrl, tweaks) is a different feature.
 */
object SettingsResetManager {
    private const val TAG = "SettingsResetManager"
    private const val PLEKTRA_PREFS = "plektra_prefs"

    /** The word the user types to confirm; the same in every locale. */
    internal const val CONFIRMATION_WORD = "reset"

    /** True when [input], trimmed and ignoring case, is exactly [CONFIRMATION_WORD]. */
    internal fun isConfirmationPhrase(input: String): Boolean =
        input.trim().equals(CONFIRMATION_WORD, ignoreCase = true)

    /**
     * Backed-up `plektra_prefs` keys that a reset keeps, as exact names.
     *
     * Words and snippets the user wrote (a reset must not delete content), the privileged-access
     * (Shizuku) setup, and recorded device state. The toolbox's own records of system changes
     * (such as RevertibleChange originals) live in other preference files or in keys Backup does
     * not export, so they never reach the removal list.
     */
    internal val keptPrefKeys: Set<String> = setOf(
        "user_dictionary_entries",
        "snippets_v1",
        "privileged_access_backend",
        "trackpad_shizuku_device",
        "clicks_power_keyboard_snapshots_v1",
        "clicks_charging_automation",
        "clicks_charging_start_percent",
        "clicks_charging_stop_percent",
        "smart_backlight_enabled",
        "backlight_tile_enabled"
    )

    /** Backed-up `plektra_prefs` key families that a reset keeps. */
    internal val keptPrefKeyPrefixes: List<String> = listOf(
        "auto_correct_custom_",
        "clicks_power_soc_calibration_",
        "notification_ring_",
        "titan2_"
    )

    /** Files and folders under `filesDir` that Backup saves and a reset deletes. */
    internal val deletedFileRoots: Set<String> = setOf(
        "ctrl_key_mappings.json",
        "variations.json",
        "locale_layout_mapping.json",
        "keyboard_layouts",
        SettingsManager.TYPING_SOUND_CUSTOM_DIR
    )

    /**
     * Files and folders under `filesDir` that Backup saves and a reset keeps: the downloaded
     * dictionaries and their manifest, and the user's own word list.
     */
    internal val keptFileRoots: Set<String> = setOf(
        "user_defaults.json",
        DictionaryPackageBackup.MANIFEST,
        DictionaryPackageBackup.DIRECTORY
    )

    private val deletedDirectoryRoots = setOf("keyboard_layouts", SettingsManager.TYPING_SOUND_CUSTOM_DIR)

    /** What a reset would do to the keys and files it was given. */
    internal data class Plan(
        /** `plektra_prefs` keys to remove. */
        val prefKeysToRemove: Set<String>,
        /** Backed-up `plektra_prefs` keys that are present and survive. */
        val prefKeysKept: Set<String>,
        /** Paths relative to `filesDir`, with `/` separators. */
        val filesToDelete: List<String>
    )

    internal fun isKeptPrefKey(key: String): Boolean =
        key in keptPrefKeys ||
            keptPrefKeyPrefixes.any(key::startsWith) ||
            "$PLEKTRA_PREFS:$key" in BackupPreferencePolicy.runtimeDerivedKeys ||
            "$PLEKTRA_PREFS:$key" in BackupPreferencePolicy.targetDeviceDerivedKeys

    internal fun isDeletedFile(relativePath: String): Boolean {
        val normalized = relativePath.removePrefix("./")
        return deletedFileRoots.any { root -> normalized == root || normalized.startsWith("$root/") }
    }

    /** Pure: decides what to remove and what to keep, from the keys and files that exist. */
    internal fun plan(currentPrefKeys: Collection<String>, existingFiles: Collection<String>): Plan {
        val backedUp = currentPrefKeys.filter { BackupPreferenceContract.isExportable(PLEKTRA_PREFS, it) }
        val (kept, removed) = backedUp.partition(::isKeptPrefKey)
        return Plan(
            prefKeysToRemove = removed.toSet(),
            prefKeysKept = kept.toSet(),
            filesToDelete = existingFiles.filter(::isDeletedFile).sorted()
        )
    }

    suspend fun reset(context: Context): SettingsResetResult =
        withContext(Dispatchers.IO) { resetBlocking(context) }

    internal fun resetBlocking(context: Context): SettingsResetResult {
        val appContext = context.applicationContext
        return try {
            val prefs = SettingsManager.getPreferences(appContext)
            val filesDir = appContext.filesDir
            val existingFiles = filesDir.walkTopDown().filter { it.isFile }
                .map { it.toRelativeString(filesDir).replace("\\", "/") }.toList()
            val plan = plan(prefs.all.keys, existingFiles)

            // Files first: if one cannot be deleted we stop before touching any preference, and a
            // second reset finishes the job.
            val stuck = plan.filesToDelete.filterNot { File(filesDir, it).delete() }
            if (stuck.isNotEmpty()) {
                return SettingsResetResult.Failure("Unable to delete ${stuck.joinToString(", ")}")
            }
            deletedDirectoryRoots.forEach { root ->
                val dir = File(filesDir, root)
                check(!dir.exists() || dir.deleteRecursively()) { "Unable to delete $root" }
            }

            // One key at a time: a bulk clear() would fire the listeners once with a null key on
            // Android 11 and later, and the IME's per-key listeners would never run.
            val editor = prefs.edit()
            plan.prefKeysToRemove.forEach { editor.remove(it) }
            check(editor.commit()) { "Unable to save the reset preferences" }

            refreshRunningComponents(appContext, plan)
            SettingsResetResult.Success(
                removedPrefKeys = plan.prefKeysToRemove,
                keptPrefKeys = plan.prefKeysKept,
                deletedFiles = plan.filesToDelete
            )
        } catch (e: Exception) {
            Log.e(TAG, "Reset failed", e)
            SettingsResetResult.Failure(e.message ?: "Reset failed")
        }
    }

    /**
     * Makes the running IME and the settings screens pick up the defaults without a restart.
     * The removed keys already fired their own listeners; this covers what lives in files.
     */
    private fun refreshRunningComponents(context: Context, plan: Plan) {
        // Same defaults that a restore re-creates (FileBackupHelper.ensureDefaults).
        runCatching { LayoutFileStore.getLayoutsDirectory(context) }
            .onFailure { Log.w(TAG, "Failed to re-create the layouts directory", it) }
        // Deletes the custom file, copies the asset default back and bumps nav_mode_mappings_updated.
        SettingsManager.resetNavModeKeyMappings(context)
        // These markers are what the IME listens to for file-backed state (variations.json,
        // locale_layout_mapping.json, the typing sound pack).
        SettingsManager.notifyVariationsUpdated(context)
        SettingsManager.notifyKeyboardLayoutAutoMappingUpdated(context)
        SettingsManager.getPreferences(context).edit()
            .putLong(SettingsManager.KEY_TYPING_SOUND_UPDATED_AT, System.currentTimeMillis())
            .apply()
        // Same post-restore effect as RestoreManager.PostRestoreAction.REGISTER_CUSTOM_INPUT_STYLES.
        if ("custom_input_styles" in plan.prefKeysToRemove || "hidden_system_input_styles" in plan.prefKeysToRemove) {
            AdditionalSubtypeUtils.registerAdditionalSubtypes(context)
        }
    }
}

sealed class SettingsResetResult {
    data class Success(
        val removedPrefKeys: Set<String>,
        val keptPrefKeys: Set<String>,
        val deletedFiles: List<String>
    ) : SettingsResetResult()

    data class Failure(val reason: String) : SettingsResetResult()
}
