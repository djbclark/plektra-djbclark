package it.palsoftware.pastiera

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.palsoftware.pastiera.R
import it.palsoftware.pastiera.inputmethod.RecognitionEngines
import it.palsoftware.pastiera.inputmethod.DictationHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice settings screen: hold-Fn dictation triggers, transcription (engine, punctuation, pause),
 * dictation haptics and the assistant keys. Every row reads and writes the same
 * [SettingsManager] keys the keyboard uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var fnLongPressSpeech by remember {
        mutableStateOf(SettingsManager.getFnLongPressSpeechEnabled(context))
    }
    var dictationMaskOffensive by remember {
        mutableStateOf(SettingsManager.getDictationMaskOffensive(context))
    }
    var dictationEndSilenceMs by remember {
        mutableStateOf(SettingsManager.getDictationEndSilenceMs(context))
    }
    var dictationHaptics by remember {
        mutableStateOf(SettingsManager.getDictationHapticsPreference(context))
    }
    var dictationHapticStrength by remember {
        mutableStateOf(SettingsManager.getDictationHapticStrength(context))
    }
    var dictationEngine by remember {
        mutableStateOf(SettingsManager.getDictationEngine(context))
    }
    var dictationContinuous by remember {
        mutableStateOf(SettingsManager.getDictationContinuousSession(context))
    }
    var dictationAutoPunctuation by remember {
        mutableStateOf(SettingsManager.getDictationAutoPunctuation(context))
    }
    var symLongPressAssistant by remember {
        mutableStateOf(SettingsManager.getSymLongPressAssistantEnabled(context))
    }
    // The stored flag only records that a binding was once asked for. The vendor slot it writes
    // lives in Settings.System, where a system update or another app can take it back without
    // telling us — so the switch reflects what the slot ACTUALLY points at right now. Reading it
    // needs no permission, unlike writing it.
    var sideKeyAssistant by remember {
        mutableStateOf(SettingsManager.getSideKeyAssistantEnabled(context))
    }
    LaunchedEffect(Unit) {
        val bound = withContext(Dispatchers.IO) { VendorSideKeyManager.isAssistantBound(context) }
        if (bound != sideKeyAssistant) {
            sideKeyAssistant = bound
            SettingsManager.setSideKeyAssistantEnabled(context, bound)
        }
    }
    var sideKeyBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Set when a binding was refused for lack of WRITE_SETTINGS, so returning from the grant
    // screen can finish what the user asked for instead of making them toggle again.
    var pendingSideKeyEnable by remember { mutableStateOf<Boolean?>(null) }
    var writeSettingsGrantReturns by remember { mutableStateOf(0) }
    val writeSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { writeSettingsGrantReturns++ }
    var assistantAction by remember {
        mutableStateOf(SettingsManager.getAssistantAction(context))
    }
    var showAssistantActionPicker by remember { mutableStateOf(false) }
    val assistantActions = listOf(
        Triple(SettingsManager.ASSISTANT_ACTION_AUTO, R.string.assistant_action_auto, R.string.assistant_action_auto_detail),
        Triple(SettingsManager.ASSISTANT_ACTION_VOICE_COMMAND, R.string.assistant_action_voice_command, R.string.assistant_action_voice_command_detail),
        Triple(SettingsManager.ASSISTANT_ACTION_HANDS_FREE, R.string.assistant_action_hands_free, R.string.assistant_action_hands_free_detail),
        Triple(SettingsManager.ASSISTANT_ACTION_ASSIST, R.string.assistant_action_assist, R.string.assistant_action_assist_detail)
    )
    val hapticLevels = listOf(
        SettingsManager.DICTATION_HAPTIC_LIGHT to R.string.dictation_haptic_light,
        SettingsManager.DICTATION_HAPTIC_STANDARD to R.string.dictation_haptic_standard,
        SettingsManager.DICTATION_HAPTIC_STRONG to R.string.dictation_haptic_strong
    )
    var showEnginePicker by remember { mutableStateOf(false) }
    val engines = remember { RecognitionEngines.available(context) }

    fun applySideKeyBinding(enabled: Boolean) {
        sideKeyBusy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (enabled) {
                    VendorSideKeyManager.bindAssistantToLongPress(context)
                } else {
                    VendorSideKeyManager.restoreLongPress(context)
                }
            }
            sideKeyBusy = false
            when (outcome) {
                VendorSideKeyManager.Outcome.SUCCESS -> {
                    sideKeyAssistant = enabled
                    SettingsManager.setSideKeyAssistantEnabled(context, enabled)
                }
                VendorSideKeyManager.Outcome.NEEDS_PERMISSION -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.side_key_assistant_needs_permission),
                        Toast.LENGTH_LONG
                    ).show()
                    pendingSideKeyEnable = enabled
                    runCatching {
                        writeSettingsLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
                VendorSideKeyManager.Outcome.FAILED -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.side_key_assistant_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(writeSettingsGrantReturns) {
        val pending = pendingSideKeyEnable
        if (writeSettingsGrantReturns > 0 && pending != null) {
            pendingSideKeyEnable = null
            applySideKeyBinding(pending)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description)
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_category_voice),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.voice_screen_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SettingsSectionDivider(label = stringResource(R.string.voice_section_triggers))
            VoiceSwitchRow(
                linkId = SettingLinkIds.VOICE_FN_LONG_PRESS_SPEECH,
                title = stringResource(R.string.fn_long_press_speech_title),
                description = stringResource(R.string.fn_long_press_speech_description),
                checked = fnLongPressSpeech,
                onCheckedChange = { enabled ->
                    fnLongPressSpeech = enabled
                    SettingsManager.setFnLongPressSpeechEnabled(context, enabled)
                }
            )
            SettingsSectionDivider(label = stringResource(R.string.voice_section_transcription))
            VoiceNavigationRow(
                linkId = SettingLinkIds.VOICE_ENGINE,
                title = stringResource(R.string.dictation_engine_title),
                description = engines.firstOrNull { it.id == dictationEngine }?.label
                    ?: stringResource(R.string.dictation_engine_system_default),
                icon = Icons.Filled.RecordVoiceOver,
                onClick = { showEnginePicker = true }
            )
            VoiceSwitchRow(
                linkId = SettingLinkIds.VOICE_AUTO_PUNCTUATION,
                title = stringResource(R.string.dictation_auto_punctuation_title),
                description = stringResource(R.string.dictation_auto_punctuation_description),
                checked = dictationAutoPunctuation,
                onCheckedChange = { enabled ->
                    dictationAutoPunctuation = enabled
                    SettingsManager.setDictationAutoPunctuation(context, enabled)
                }
            )
            VoiceSwitchRow(
                linkId = SettingLinkIds.VOICE_MASK_OFFENSIVE,
                title = stringResource(R.string.dictation_mask_offensive_title),
                description = stringResource(R.string.dictation_mask_offensive_description),
                checked = dictationMaskOffensive,
                onCheckedChange = { enabled ->
                    dictationMaskOffensive = enabled
                    SettingsManager.setDictationMaskOffensive(context, enabled)
                }
            )
            Surface(modifier = Modifier.fillMaxWidth().settingRow(SettingLinkIds.VOICE_END_SILENCE)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.dictation_end_silence_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (dictationEndSilenceMs <= 0) {
                            stringResource(R.string.dictation_end_silence_system_default)
                        } else {
                            stringResource(
                                R.string.dictation_end_silence_seconds,
                                dictationEndSilenceMs / 1000f
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = dictationEndSilenceMs.toFloat(),
                        onValueChange = { value ->
                            // Snap to 500ms steps; 0 = system default.
                            val snapped = ((value / 500f).toInt() * 500)
                            dictationEndSilenceMs = snapped
                            SettingsManager.setDictationEndSilenceMs(context, snapped)
                        },
                        valueRange = 0f..10000f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.dictation_end_silence_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            VoiceSwitchRow(
                linkId = SettingLinkIds.VOICE_CONTINUOUS_SESSION,
                title = stringResource(R.string.dictation_continuous_title),
                description = stringResource(R.string.dictation_continuous_description),
                checked = dictationContinuous,
                onCheckedChange = { enabled ->
                    dictationContinuous = enabled
                    SettingsManager.setDictationContinuousSession(context, enabled)
                }
            )

            SettingsSectionDivider(label = stringResource(R.string.voice_section_feedback))
            VoiceSwitchRow(
                linkId = SettingLinkIds.VOICE_HAPTICS,
                title = stringResource(R.string.dictation_haptics_title),
                description = stringResource(R.string.dictation_haptics_description),
                checked = dictationHaptics,
                icon = Icons.Filled.Vibration,
                onCheckedChange = { enabled ->
                    dictationHaptics = enabled
                    SettingsManager.setDictationHapticsEnabled(context, enabled)
                }
            )
            // Only shown while the cues are on: a strength for a cue that never plays is noise.
            if (dictationHaptics) {
                Surface(modifier = Modifier.fillMaxWidth().settingRow(SettingLinkIds.VOICE_HAPTIC_STRENGTH)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dictation_haptic_strength_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.dictation_haptic_strength_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            hapticLevels.forEach { (value, labelRes) ->
                                FilterChip(
                                    selected = dictationHapticStrength == value,
                                    onClick = {
                                        dictationHapticStrength = value
                                        SettingsManager.setDictationHapticStrength(context, value)
                                        // Play it now: the only way to judge a haptic is to feel it.
                                        DictationHaptics.play(context, value)
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                        }
                    }
                }
            }

            SettingsSectionDivider(label = stringResource(R.string.voice_section_assistant))
            VoiceSwitchRow(
                linkId = SettingLinkIds.VOICE_SIDE_KEY_ASSISTANT,
                title = stringResource(R.string.side_key_assistant_title),
                description = stringResource(R.string.side_key_assistant_description),
                checked = sideKeyAssistant,
                enabled = !sideKeyBusy,
                onCheckedChange = { enabled -> applySideKeyBinding(enabled) }
            )
            VoiceSwitchRow(
                linkId = SettingLinkIds.VOICE_SYM_LONG_PRESS_ASSISTANT,
                title = stringResource(R.string.sym_long_press_assistant_title),
                description = stringResource(R.string.sym_long_press_assistant_description),
                checked = symLongPressAssistant,
                onCheckedChange = { enabled ->
                    symLongPressAssistant = enabled
                    SettingsManager.setSymLongPressAssistantEnabled(context, enabled)
                }
            )
            VoiceNavigationRow(
                linkId = SettingLinkIds.VOICE_ASSISTANT_ACTION,
                title = stringResource(R.string.assistant_action_title),
                description = assistantActions.firstOrNull { it.first == assistantAction }
                    ?.let { stringResource(it.second) }
                    ?: stringResource(R.string.assistant_action_auto),
                icon = Icons.Filled.RecordVoiceOver,
                onClick = { showAssistantActionPicker = true }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showAssistantActionPicker) {
            AlertDialog(
                onDismissRequest = { showAssistantActionPicker = false },
                title = { Text(text = stringResource(R.string.assistant_action_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(R.string.assistant_action_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        assistantActions.forEach { (mode, labelRes, detailRes) ->
                            val select = {
                                assistantAction = mode
                                SettingsManager.setAssistantAction(context, mode)
                                showAssistantActionPicker = false
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = select)
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(selected = mode == assistantAction, onClick = select)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(labelRes),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = stringResource(detailRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAssistantActionPicker = false }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                }
            )
        }

        if (showEnginePicker) {
            AlertDialog(
                onDismissRequest = { showEnginePicker = false },
                title = { Text(text = stringResource(R.string.dictation_engine_picker_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(R.string.dictation_engine_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        engines.forEach { engine ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        dictationEngine = engine.id
                                        SettingsManager.setDictationEngine(context, engine.id)
                                        showEnginePicker = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = engine.id == dictationEngine,
                                    onClick = {
                                        dictationEngine = engine.id
                                        SettingsManager.setDictationEngine(context, engine.id)
                                        showEnginePicker = false
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = engine.label,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    if (engine.isSystemDefault) {
                                        Text(
                                            text = stringResource(R.string.dictation_engine_is_system_default),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    engine.detail?.let { detail ->
                                        Text(
                                            text = detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEnginePicker = false }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun VoiceSwitchRow(
    linkId: String,
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Mic,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (description == null) 64.dp else 72.dp)
            .settingRow(linkId)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun VoiceNavigationRow(
    linkId: String,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .settingRow(linkId, onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
