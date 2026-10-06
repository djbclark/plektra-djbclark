package it.palsoftware.pastiera

import android.content.Context
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
class VoiceSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetSettings() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun voiceDefaultsKeepEveryTriggerOffAndDictationCuesOn() {
        assertFalse(SettingsManager.getFnLongPressSpeechEnabled(context))
        assertFalse(SettingsManager.getSymLongPressAssistantEnabled(context))
        assertFalse(SettingsManager.getSideKeyAssistantEnabled(context))
        assertEquals(251, SettingsManager.getFnSpeechScanCode(context))
        assertTrue(SettingsManager.getDictationHapticsPreference(context))
        assertEquals(SettingsManager.DICTATION_HAPTIC_STRONG, SettingsManager.getDictationHapticStrength(context))
        assertEquals(0, SettingsManager.getDictationEndSilenceMs(context))
        assertTrue(SettingsManager.getDictationMaskOffensive(context))
        assertEquals(SettingsManager.DICTATION_ENGINE_SYSTEM_DEFAULT, SettingsManager.getDictationEngine(context))
        assertTrue(SettingsManager.getDictationContinuousSession(context))
        assertTrue(SettingsManager.getDictationAutoPunctuation(context))
        assertEquals(SettingsManager.ASSISTANT_ACTION_AUTO, SettingsManager.getAssistantAction(context))
    }

    @Test
    fun endSilenceIsClampedToTenSeconds() {
        SettingsManager.setDictationEndSilenceMs(context, 60_000)
        assertEquals(10_000, SettingsManager.getDictationEndSilenceMs(context))
        SettingsManager.setDictationEndSilenceMs(context, -5)
        assertEquals(0, SettingsManager.getDictationEndSilenceMs(context))
    }

    @Test
    fun sideKeyRestorePointIsCapturedOnceAndNeverRecordsThisApp() {
        SettingsManager.captureSideKeyOriginal(context, context.packageName, "SomeActivity")
        assertFalse(SettingsManager.isSideKeyOriginalCaptured(context))

        SettingsManager.captureSideKeyOriginal(context, "com.vendor.gemini", "com.vendor.Entry")
        SettingsManager.captureSideKeyOriginal(context, "com.other", "com.other.Entry")
        assertTrue(SettingsManager.isSideKeyOriginalCaptured(context))
        assertEquals("com.vendor.gemini", SettingsManager.getSideKeyOriginalPackage(context))
        assertEquals("com.vendor.Entry", SettingsManager.getSideKeyOriginalActivity(context))

        SettingsManager.clearSideKeyOriginal(context)
        assertFalse(SettingsManager.isSideKeyOriginalCaptured(context))
        assertNull(SettingsManager.getSideKeyOriginalPackage(context))
    }

    @Test
    fun everyVoiceControlIsRegisteredOnTheVoiceScreen() {
        val ids = listOf(
            SettingLinkIds.MAIN_VOICE,
            SettingLinkIds.VOICE_FN_LONG_PRESS_SPEECH,
            SettingLinkIds.VOICE_ENGINE,
            SettingLinkIds.VOICE_AUTO_PUNCTUATION,
            SettingLinkIds.VOICE_MASK_OFFENSIVE,
            SettingLinkIds.VOICE_END_SILENCE,
            SettingLinkIds.VOICE_CONTINUOUS_SESSION,
            SettingLinkIds.VOICE_HAPTICS,
            SettingLinkIds.VOICE_HAPTIC_STRENGTH,
            SettingLinkIds.VOICE_SIDE_KEY_ASSISTANT,
            SettingLinkIds.VOICE_SYM_LONG_PRESS_ASSISTANT,
            SettingLinkIds.VOICE_ASSISTANT_ACTION
        )
        ids.forEach { id ->
            val entry = requireNotNull(SettingLinkRegistry.byId(id)) { "$id is not registered" }
            assertEquals(SettingsDestination.Voice, entry.route.toSettingsPage().destination)
        }
    }

    @Test
    fun hapticStrengthIsHiddenWhileTheCuesAreOffAndFallsBackToTheirSwitch() {
        val strength = requireNotNull(SettingLinkRegistry.byId(SettingLinkIds.VOICE_HAPTIC_STRENGTH))
        SettingsManager.setDictationHapticsEnabled(context, true)
        assertEquals(strength.id, SettingLinkRegistry.visibleTarget(context, strength).id)
        SettingsManager.setDictationHapticsEnabled(context, false)
        assertEquals(SettingLinkIds.VOICE_HAPTICS, SettingLinkRegistry.visibleTarget(context, strength).id)
    }

    @Test
    fun voiceControlsAreFoundByTheirTitleAndByEngineSynonyms() {
        val byTitle = SettingLinkRegistry.search(context, context.getString(R.string.dictation_engine_title))
        assertTrue(byTitle.any { it.id == SettingLinkIds.VOICE_ENGINE })
        val byKeyword = SettingLinkRegistry.search(context, "futo")
        assertTrue(byKeyword.any { it.id == SettingLinkIds.VOICE_ENGINE })
    }
}
