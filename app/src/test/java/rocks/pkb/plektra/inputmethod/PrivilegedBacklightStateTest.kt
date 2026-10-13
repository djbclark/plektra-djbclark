package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.provider.Settings
import rocks.pkb.plektra.SettingsManager
import moe.shizuku.manager.adb.EmbeddedAdbInit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegedBacklightStateTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        SettingsManager.getPreferences(context).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
    }

    @Test
    fun smartBacklightIsOffByDefaultAndRoundTrips() {
        assertFalse(SettingsManager.getSmartBacklightEnabled(context))
        SettingsManager.setSmartBacklightEnabled(context, true)
        assertTrue(SettingsManager.getSmartBacklightEnabled(context))
    }

    @Test
    fun appliedFlagAndObservedValueLiveInTheBackupExcludedBrokerPrefs() {
        assertFalse(PrivilegedDiagnostics.backlightApplied(context))
        assertNull(PrivilegedDiagnostics.observedBacklightValue(context))

        PrivilegedDiagnostics.setBacklightApplied(context, true)
        PrivilegedDiagnostics.recordObservedBacklightValue(context, "-1")

        assertTrue(PrivilegedDiagnostics.backlightApplied(context))
        assertEquals("-1", PrivilegedDiagnostics.observedBacklightValue(context)?.first)
        assertTrue(EmbeddedAdbInit.prefs(context).contains("privileged_backlight_applied"))
        assertFalse(SettingsManager.getPreferences(context).contains("privileged_backlight_applied"))
    }

    /** An install that turned the backlight on before holders existed keeps the tile's permission. */
    @Test
    fun applyAllRegistersTheBacklightAsAWriteSecureSettingsHolder() {
        SettingsManager.setSmartBacklightEnabled(context, true)
        PrivilegedSetup.applyAll(context, "test", BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))
        assertTrue(WriteSecureSettingsHolders.BACKLIGHT in WriteSecureSettingsHolders.holders(context))

        SettingsManager.setSmartBacklightEnabled(context, false)
        PrivilegedSetup.applyAll(context, "test", BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))
        assertFalse(WriteSecureSettingsHolders.anyNeeded(context))
    }

    @Test
    fun aBlockedBrokerRecordsTheReasonAgainstTheBacklightStep() {
        KeyboardBacklightManager.applyAlwaysOn(context, BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))

        val outcome = requireNotNull(
            PrivilegedDiagnostics.last(context, PrivilegedDiagnostics.Step.BACKLIGHT)
        )
        assertFalse(outcome.ok)
        assertEquals(PrivilegedDiagnostics.REASON_NOT_PAIRED, outcome.reason)
    }

    @Test
    fun theShellRefusesToRunWhileTheBrokerIsBlocked() {
        val result = PrivilegedShell.run(
            context, PrivilegedCommand.ReadBacklightTimeout, BrokerTrigger.forTest(BrokerTrigger.Kind.TAP)
        )

        assertFalse(result.ok)
        assertEquals(EmbeddedAdbShell.REASON_NOT_PAIRED, result.failure)
        assertEquals(EmbeddedAdbShell.BrokerStatus.NOT_PAIRED, result.status)
    }

    @Test
    fun theKeyboardLightSwitchHookRefusesValuesOtherThanZeroAndOne() {
        assertFalse(KeyboardBacklightManager.writeKeyboardLightSwitch(context, 2))
        assertFalse(KeyboardBacklightManager.writeKeyboardLightSwitch(context, -1))
        assertEquals(
            KeyboardBacklightManager.KEYBOARD_LIGHT_ON,
            KeyboardBacklightManager.readKeyboardLightSwitch(context)
        )
    }

    @Test
    fun theKeyboardLightSwitchHookWritesTheVendorRow() {
        assertTrue(KeyboardBacklightManager.writeKeyboardLightSwitch(context, 0))
        assertEquals(0, KeyboardBacklightManager.readKeyboardLightSwitch(context))
        assertEquals(
            0,
            Settings.Global.getInt(context.contentResolver, "agui_keyboard_background_light", 1)
        )
        assertTrue(KeyboardBacklightManager.writeKeyboardLightSwitch(context, 1))
        assertEquals(1, KeyboardBacklightManager.readKeyboardLightSwitch(context))
    }
}
