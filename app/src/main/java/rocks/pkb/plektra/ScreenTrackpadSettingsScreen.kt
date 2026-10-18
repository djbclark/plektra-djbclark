package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Settings for the screen trackpad: hold (or tap) a trigger key and swipe anywhere on the
 * display to move the text cursor.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ScreenTrackpadSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsManager.isScreenTrackpadEnabled(context)) }
    var triggerKey by remember { mutableStateOf(SettingsManager.getScreenTrackpadTriggerKey(context)) }
    var activation by remember { mutableStateOf(SettingsManager.getScreenTrackpadActivation(context)) }
    var stepPx by remember { mutableStateOf(SettingsManager.getScreenTrackpadStepPx(context).toFloat()) }
    var showHint by remember { mutableStateOf(SettingsManager.isScreenTrackpadHintEnabled(context)) }
    var triggerMenuExpanded by remember { mutableStateOf(false) }
    var activationMenuExpanded by remember { mutableStateOf(false) }
    // Turning on while Trackpad Gestures is on asks first (TrackpadFeatureExclusivity).
    var confirmEnable by remember { mutableStateOf(false) }
    // Follow the other screen, a restore or the IME's reconcile while this page is up.
    ObserveTrackpadFeaturePrefs { enabled = SettingsManager.isScreenTrackpadEnabled(context) }

    // Poll the overlay permission so the status updates when the user comes back from system settings.
    var statusTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            statusTick++
            delay(1500)
        }
    }
    val overlayGranted = remember(statusTick) { Settings.canDrawOverlays(context) }

    val triggerOptions = listOf(
        SettingsManager.SCREEN_TRACKPAD_TRIGGER_SPACE to stringResource(R.string.screen_trackpad_trigger_space),
        SettingsManager.SCREEN_TRACKPAD_TRIGGER_SHIFT_LEFT to stringResource(R.string.screen_trackpad_trigger_shift_left),
        SettingsManager.SCREEN_TRACKPAD_TRIGGER_SHIFT_RIGHT to stringResource(R.string.screen_trackpad_trigger_shift_right),
        SettingsManager.SCREEN_TRACKPAD_TRIGGER_SHIFT_EITHER to stringResource(R.string.screen_trackpad_trigger_shift_either),
        SettingsManager.SCREEN_TRACKPAD_TRIGGER_SYM to stringResource(R.string.screen_trackpad_trigger_sym)
    )
    val activationOptions = listOf(
        SettingsManager.SCREEN_TRACKPAD_ACTIVATION_HOLD to stringResource(R.string.screen_trackpad_activation_hold),
        SettingsManager.SCREEN_TRACKPAD_ACTIVATION_DOUBLE_TAP to stringResource(R.string.screen_trackpad_activation_double_tap),
        SettingsManager.SCREEN_TRACKPAD_ACTIVATION_SINGLE_TAP to stringResource(R.string.screen_trackpad_activation_single_tap)
    )

    fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    Scaffold(
        topBar = {
            PlektraSettingsHeader(
                title = stringResource(R.string.screen_trackpad_title),
                onBack = onBack
            )
        }
    ) { paddingValues ->
        SettingsSectionScrollColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(paddingValues)
        ) {
            fun applyEnabled(wanted: Boolean) {
                TrackpadFeatureExclusivity.setEnabled(context, TrackpadFeature.SCREEN_TRACKPAD, wanted)
                enabled = wanted
                if (wanted && !Settings.canDrawOverlays(context)) openOverlayPermission()
            }
            SettingsToggleRow(
                title = stringResource(R.string.screen_trackpad_enabled_title),
                description = stringResource(R.string.screen_trackpad_enabled_description),
                icon = Icons.Filled.TouchApp,
                checked = enabled,
                linkId = SettingLinkIds.SCREEN_TRACKPAD_ENABLED,
                onCheckedChange = { wanted ->
                    val gesturesOn = SettingsManager.getTrackpadGesturesEnabled(context)
                    if (wanted && TrackpadFeatureExclusivity.enablingNeedsConfirmation(TrackpadFeature.SCREEN_TRACKPAD, gesturesOn)) {
                        confirmEnable = true
                    } else {
                        applyEnabled(wanted)
                    }
                }
            )
            if (confirmEnable) {
                TrackpadExclusiveDialog(
                    feature = TrackpadFeature.SCREEN_TRACKPAD,
                    onContinue = {
                        confirmEnable = false
                        applyEnabled(true)
                    },
                    onDismiss = { confirmEnable = false }
                )
            }

            // Permission status row
            SettingsRow(
                title = stringResource(R.string.screen_trackpad_permission_title),
                description = stringResource(
                    if (overlayGranted) R.string.screen_trackpad_permission_granted
                    else R.string.screen_trackpad_permission_missing
                ),
                icon = if (overlayGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                alwaysInlineDescription = true,
                modifier = Modifier.clickable(enabled = !overlayGranted) { openOverlayPermission() }
            )

            SettingsDropdownRow(
                title = stringResource(R.string.screen_trackpad_trigger_key_title),
                value = triggerOptions.firstOrNull { it.first == triggerKey }?.second ?: triggerKey,
                options = triggerOptions,
                expanded = triggerMenuExpanded,
                onExpandedChange = { triggerMenuExpanded = it },
                onSelected = { value ->
                    triggerKey = value
                    SettingsManager.setScreenTrackpadTriggerKey(context, value)
                    triggerMenuExpanded = false
                },
                enabled = enabled,
                linkId = SettingLinkIds.SCREEN_TRACKPAD_TRIGGER_KEY
            )

            SettingsDropdownRow(
                title = stringResource(R.string.screen_trackpad_activation_title),
                value = activationOptions.firstOrNull { it.first == activation }?.second ?: activation,
                options = activationOptions,
                expanded = activationMenuExpanded,
                onExpandedChange = { activationMenuExpanded = it },
                onSelected = { value ->
                    activation = value
                    SettingsManager.setScreenTrackpadActivation(context, value)
                    activationMenuExpanded = false
                },
                enabled = enabled,
                linkId = SettingLinkIds.SCREEN_TRACKPAD_ACTIVATION
            )

            SettingsSupportingText(
                stringResource(
                    if (activation == SettingsManager.SCREEN_TRACKPAD_ACTIVATION_HOLD) {
                        R.string.screen_trackpad_activation_hold_note
                    } else {
                        R.string.screen_trackpad_activation_sticky_note
                    }
                )
            )

            // Sensitivity slider
            SettingsSliderRow(
                title = stringResource(R.string.screen_trackpad_sensitivity_title),
                description = stringResource(R.string.screen_trackpad_sensitivity_description),
                icon = Icons.Filled.Speed,
                valueLabel = "${stepPx.roundToInt()} px",
                value = stepPx,
                onValueChange = { stepPx = it },
                onValueChangeFinished = { SettingsManager.setScreenTrackpadStepPx(context, stepPx.roundToInt()) },
                valueRange = SettingsManager.MIN_SCREEN_TRACKPAD_STEP_PX.toFloat()..SettingsManager.MAX_SCREEN_TRACKPAD_STEP_PX.toFloat(),
                steps = 13,
                enabled = enabled,
                linkId = SettingLinkIds.SCREEN_TRACKPAD_SENSITIVITY
            )

            SettingsToggleRow(
                title = stringResource(R.string.screen_trackpad_hint_enabled_title),
                description = stringResource(R.string.screen_trackpad_hint_enabled_description),
                icon = Icons.Filled.Visibility,
                checked = showHint,
                enabled = enabled,
                linkId = SettingLinkIds.SCREEN_TRACKPAD_SHOW_HINT,
                onCheckedChange = {
                    showHint = it
                    SettingsManager.setScreenTrackpadHintEnabled(context, it)
                }
            )

            SettingsBottomSpacer()
        }
    }
}

/**
 * Asks before turning [feature] on while the other trackpad feature is on. Continue turns
 * [feature] on and the other off; Cancel changes nothing (the switch stays off).
 */
@Composable
internal fun TrackpadExclusiveDialog(
    feature: TrackpadFeature,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.trackpad_exclusive_dialog_title)) },
        text = {
            Text(
                text = stringResource(
                    when (feature) {
                        TrackpadFeature.SCREEN_TRACKPAD -> R.string.trackpad_exclusive_screen_trackpad_message
                        TrackpadFeature.TRACKPAD_GESTURES -> R.string.trackpad_exclusive_trackpad_gestures_message
                    }
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text(text = stringResource(R.string.trackpad_exclusive_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Calls [onChanged] whenever either trackpad feature's preference changes, so a screen's
 * switch follows the other screen's confirm dialog, a backup restore or the IME's reconcile.
 */
@Composable
internal fun ObserveTrackpadFeaturePrefs(onChanged: () -> Unit) {
    val context = LocalContext.current
    val currentOnChanged by rememberUpdatedState(onChanged)
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "screen_trackpad_enabled" || key == "trackpad_gestures_enabled") currentOnChanged()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
