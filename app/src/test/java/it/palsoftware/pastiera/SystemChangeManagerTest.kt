package it.palsoftware.pastiera

import android.Manifest
import android.provider.Settings
import it.palsoftware.pastiera.ResetPlan.Item
import it.palsoftware.pastiera.ResetPlan.StepResult
import it.palsoftware.pastiera.inputmethod.KeyboardBacklightManager
import it.palsoftware.pastiera.inputmethod.PrivilegedDiagnostics
import it.palsoftware.pastiera.inputmethod.PrivilegedSetup
import it.palsoftware.pastiera.toolbox.DisplayDensity
import it.palsoftware.pastiera.toolbox.FnCtrlRemap
import it.palsoftware.pastiera.toolbox.PackageState
import it.palsoftware.pastiera.toolbox.RemovalJournal
import it.palsoftware.pastiera.toolbox.SystemTweaks
import moe.shizuku.manager.adb.EmbeddedAdbInit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Detection and the steps that need no broker. The broker itself is not paired here, so a step
 * that needs it must fail with the reason and leave its state alone, never claim success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemChangeManagerTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun items() = SystemChangeManager.plan(context).entries.map { it.item }

    private fun putString(key: String, value: String) =
        Settings.System.putString(context.contentResolver, key, value)

    private fun fnRows() = Settings.System.getInt(context.contentResolver, "fn_programmable_key_enable", -1) to
        Settings.System.getInt(context.contentResolver, "fn_programmable_key_function", -1)

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        SettingsManager.getPreferences(context).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
    }

    private fun grantSecureSettings() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    // ---- detection ---------------------------------------------------------------------------------

    @Test
    fun `a phone with nothing changed has nothing to undo`() {
        assertTrue(SystemChangeManager.plan(context).isEmpty)
        assertEquals(null, SystemChangeManager.plan(context).brokerBlocker)
    }

    @Test
    fun `each change shows up once it is in effect`() {
        Settings.System.putInt(context.contentResolver, "fn_programmable_key_enable", 1)
        Settings.System.putInt(context.contentResolver, "fn_programmable_key_function", 1)
        DisplayDensity.markChanged(context, true)
        SystemTweaks.markChanged(context, true)
        RemovalJournal.record(
            context,
            RemovalJournal.Record("com.agui.factorytest", PackageState.ACTIVE, RemovalJournal.Action.DISABLED, 1L)
        )
        SettingsManager.setNotificationRingEnabled(context, true)
        PrivilegedDiagnostics.setBacklightApplied(context, true)
        SettingsManager.setRingBacklightSuppressed(context, 1)

        assertEquals(
            listOf(
                Item.FN_CTRL, Item.DISPLAY_DENSITY, Item.SYSTEM_TWEAKS, Item.REMOVED_APPS,
                Item.RING_OFF, Item.KEYBOARD_TIMEOUT, Item.KEYBOARD_LIGHT_HELD
            ),
            items()
        )
        val plan = SystemChangeManager.plan(context)
        assertEquals(listOf("com.agui.factorytest"), plan.entries.single { it.item == Item.REMOVED_APPS }.packages)
        // The broker is not paired here, and the plan says so because broker steps are in it.
        assertEquals(PrivilegedDiagnostics.REASON_NOT_PAIRED, plan.brokerBlocker)
    }

    @Test
    fun `a plan with no broker steps never reports a broker blocker`() {
        SettingsManager.setNotificationRingEnabled(context, true)
        assertEquals(listOf(Item.RING_OFF), items())
        assertEquals(null, SystemChangeManager.plan(context).brokerBlocker)
    }

    @Test
    fun `the density marker follows what the app applied and reset`() {
        assertFalse(DisplayDensity.isChangedByApp(context))
        DisplayDensity.markChanged(context, true)
        assertTrue(DisplayDensity.isChangedByApp(context))
        DisplayDensity.markChanged(context, false)
        assertFalse(DisplayDensity.isChangedByApp(context))
        assertTrue(EmbeddedAdbInit.prefs(context).contains("toolbox_density_changed"))
        assertFalse(SettingsManager.getPreferences(context).contains("toolbox_density_changed"))
    }

    // ---- steps that need no broker -------------------------------------------------------------------

    @Test
    fun `fn ctrl is written back through settings alone when the app holds secure settings`() {
        grantSecureSettings()
        assertTrue(PrivilegedSetup.canWriteSystemSettings(context))
        Settings.System.putInt(context.contentResolver, "fn_programmable_key_enable", 1)
        Settings.System.putInt(context.contentResolver, "fn_programmable_key_function", 1)
        assertTrue(FnCtrlRemap.isSet(context))

        val result = SystemChangeManager.resetToStock(context)

        assertEquals(StepResult.DONE, result.resultFor(Item.FN_CTRL))
        // Never captured, so the revert switches the remap off.
        assertEquals(0 to 0, fnRows())
        assertFalse(items().contains(Item.FN_CTRL))
    }

    @Test
    fun `the ring goes off and the smart backlight switch follows when nothing was written`() {
        SettingsManager.setNotificationRingEnabled(context, true)
        SettingsManager.setSmartBacklightEnabled(context, true)

        val result = SystemChangeManager.resetToStock(context)

        assertEquals(StepResult.DONE, result.resultFor(Item.RING_OFF))
        assertEquals(StepResult.DONE, result.resultFor(Item.KEYBOARD_TIMEOUT))
        assertFalse(SettingsManager.isNotificationRingEnabled(context))
        assertFalse(SettingsManager.getSmartBacklightEnabled(context))
        assertTrue(SystemChangeManager.plan(context).isEmpty)
    }

    @Test
    fun `a written timeout that cannot be reverted stays reported and the switch stays on`() {
        SettingsManager.setSmartBacklightEnabled(context, true)
        PrivilegedDiagnostics.setBacklightApplied(context, true)

        val result = SystemChangeManager.resetToStock(context)

        assertEquals(
            StepResult.failed(PrivilegedDiagnostics.REASON_NOT_PAIRED),
            result.resultFor(Item.KEYBOARD_TIMEOUT)
        )
        // Still applied and still on: the next reset sees it again, and a later pairing is not
        // told the user wants the always-on value gone when it is not.
        assertTrue(PrivilegedDiagnostics.backlightApplied(context))
        assertTrue(SettingsManager.getSmartBacklightEnabled(context))
        assertTrue(items().contains(Item.KEYBOARD_TIMEOUT))
        assertFalse(KeyboardBacklightManager.revertToDefaultNow(context).ok)
    }

    @Test
    fun `broker steps fail with the reason and keep their state when the broker is not ready`() {
        DisplayDensity.markChanged(context, true)
        SystemTweaks.markChanged(context, true)
        RemovalJournal.record(
            context,
            RemovalJournal.Record("com.agui.factorytest", PackageState.ACTIVE, RemovalJournal.Action.DISABLED, 1L)
        )

        val result = SystemChangeManager.resetToStock(context)

        val notPaired = StepResult.failed(PrivilegedDiagnostics.REASON_NOT_PAIRED)
        assertEquals(notPaired, result.resultFor(Item.DISPLAY_DENSITY))
        assertEquals(notPaired, result.resultFor(Item.SYSTEM_TWEAKS))
        assertEquals(notPaired, result.resultFor(Item.REMOVED_APPS))
        assertFalse(result.allSucceeded)
        // Nothing claimed done: every marker and the journal entry are still there for a retry.
        assertTrue(DisplayDensity.isChangedByApp(context))
        assertTrue(SystemTweaks.isChanged(context))
        assertEquals(1, RemovalJournal.all(context).size)
    }

    @Test
    fun `the keyboard light is handed back when the app can write it`() {
        grantSecureSettings()
        Settings.Global.putInt(context.contentResolver, KeyboardBacklightManager.KEYBOARD_LIGHT_SWITCH, 0)
        SettingsManager.setRingBacklightSuppressed(context, 1)

        val result = SystemChangeManager.resetToStock(context)

        assertEquals(StepResult.DONE, result.resultFor(Item.KEYBOARD_LIGHT_HELD))
        assertEquals(
            1,
            Settings.Global.getInt(context.contentResolver, KeyboardBacklightManager.KEYBOARD_LIGHT_SWITCH, -1)
        )
        assertFalse(SettingsManager.isRingBacklightSuppressed(context))
    }

    @Test
    fun `the keyboard light stays recorded when the grant is gone`() {
        Settings.Global.putInt(context.contentResolver, KeyboardBacklightManager.KEYBOARD_LIGHT_SWITCH, 0)
        SettingsManager.setRingBacklightSuppressed(context, 1)

        val result = SystemChangeManager.resetToStock(context)

        assertEquals(
            StepResult.failed(SystemChangeManager.REASON_NEEDS_SECURE_SETTINGS),
            result.resultFor(Item.KEYBOARD_LIGHT_HELD)
        )
        assertTrue(SettingsManager.isRingBacklightSuppressed(context))
    }

    @Test
    fun `the side key goes back to what the vendor had`() {
        grantSecureSettings()
        val bard = "com.google.android.apps.bard"
        val bardActivity = "com.google.android.apps.bard.shortcut.Entry"
        putString("func1_long_press_package", bard)
        putString("func1_long_press_activity", bardActivity)
        SettingsManager.captureSideKeyOriginal(context, bard, bardActivity)
        VendorSideKeyManager.bindAssistantToLongPress(context)
        assertTrue(VendorSideKeyManager.isAssistantBound(context))
        assertTrue(items().contains(Item.SIDE_KEY))

        val result = SystemChangeManager.resetToStock(context)

        assertEquals(StepResult.DONE, result.resultFor(Item.SIDE_KEY))
        assertFalse(VendorSideKeyManager.isAssistantBound(context))
        assertEquals(bard, Settings.System.getString(context.contentResolver, "func1_long_press_package"))
        assertEquals(bardActivity, Settings.System.getString(context.contentResolver, "func1_long_press_activity"))
    }

    @Test
    fun `a side key with no recorded original is reported, not called done`() {
        grantSecureSettings()
        VendorSideKeyManager.bindAssistantToLongPress(context)
        SettingsManager.clearSideKeyOriginal(context)
        assertTrue(VendorSideKeyManager.isAssistantBound(context))

        val result = SystemChangeManager.resetToStock(context)

        assertEquals(StepResult.failed(SystemChangeManager.REASON_NOT_APPLIED), result.resultFor(Item.SIDE_KEY))
    }

    @Test
    fun `an empty plan runs nothing and succeeds`() {
        val result = SystemChangeManager.resetToStock(context)
        assertTrue(result.steps.isEmpty())
        assertTrue(result.allSucceeded)
    }

    @Test
    fun `each step is reported as it ends and one failure does not skip the rest`() {
        DisplayDensity.markChanged(context, true) // fails: broker not paired
        SettingsManager.setNotificationRingEnabled(context, true) // succeeds
        val seen = mutableListOf<Item>()

        val result = SystemChangeManager.resetToStock(context) { entry, _ -> seen += entry.item }

        assertEquals(listOf(Item.DISPLAY_DENSITY, Item.RING_OFF), seen)
        assertEquals(StepResult.Status.FAILED, result.resultFor(Item.DISPLAY_DENSITY)?.status)
        assertEquals(StepResult.Status.DONE, result.resultFor(Item.RING_OFF)?.status)
    }
}
