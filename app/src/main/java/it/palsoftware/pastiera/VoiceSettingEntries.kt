package it.palsoftware.pastiera

// djb/voice: registry entries for the Voice screen (dictation, speech engine, assistant).
internal fun voiceSettingEntries(): List<SettingEntry> = listOf(
    SettingEntry(
        id = SettingLinkIds.MAIN_VOICE,
        titleRes = R.string.settings_category_voice,
        summaryRes = R.string.voice_screen_intro,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_FN_LONG_PRESS_SPEECH,
        titleRes = R.string.fn_long_press_speech_title,
        summaryRes = R.string.fn_long_press_speech_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_ENGINE,
        titleRes = R.string.dictation_engine_title,
        summaryRes = R.string.dictation_engine_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_AUTO_PUNCTUATION,
        titleRes = R.string.dictation_auto_punctuation_title,
        summaryRes = R.string.dictation_auto_punctuation_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_MASK_OFFENSIVE,
        titleRes = R.string.dictation_mask_offensive_title,
        summaryRes = R.string.dictation_mask_offensive_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_END_SILENCE,
        titleRes = R.string.dictation_end_silence_title,
        summaryRes = R.string.dictation_end_silence_hint,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_CONTINUOUS_SESSION,
        titleRes = R.string.dictation_continuous_title,
        summaryRes = R.string.dictation_continuous_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_HAPTICS,
        titleRes = R.string.dictation_haptics_title,
        summaryRes = R.string.dictation_haptics_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_HAPTIC_STRENGTH,
        titleRes = R.string.dictation_haptic_strength_title,
        summaryRes = R.string.dictation_haptic_strength_description,
        route = SettingRoute(SettingsDestination.Voice),
        availabilityCheck = { SettingsManager.getDictationHapticsPreference(it) },
        unavailableFallbackId = SettingLinkIds.VOICE_HAPTICS
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_SIDE_KEY_ASSISTANT,
        titleRes = R.string.side_key_assistant_title,
        summaryRes = R.string.side_key_assistant_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_SYM_LONG_PRESS_ASSISTANT,
        titleRes = R.string.sym_long_press_assistant_title,
        summaryRes = R.string.sym_long_press_assistant_description,
        route = SettingRoute(SettingsDestination.Voice)
    ),
    SettingEntry(
        id = SettingLinkIds.VOICE_ASSISTANT_ACTION,
        titleRes = R.string.assistant_action_title,
        summaryRes = R.string.assistant_action_description,
        route = SettingRoute(SettingsDestination.Voice)
    )
)
