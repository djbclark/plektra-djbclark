package rocks.pkb.plektra

import android.content.Intent
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
            SettingsToggleRow(
                title = stringResource(R.string.screen_trackpad_enabled_title),
                description = stringResource(R.string.screen_trackpad_enabled_description),
                icon = Icons.Filled.TouchApp,
                checked = enabled,
                linkId = SettingLinkIds.SCREEN_TRACKPAD_ENABLED,
                onCheckedChange = {
                    enabled = it
                    SettingsManager.setScreenTrackpadEnabled(context, it)
                    if (it && !Settings.canDrawOverlays(context)) openOverlayPermission()
                }
            )

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
