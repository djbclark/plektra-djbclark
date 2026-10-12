package rocks.pkb.plektra

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.KeyboardBacklightManager
import rocks.pkb.plektra.inputmethod.PrivilegedDiagnostics
import rocks.pkb.plektra.inputmethod.PrivilegedSetup
import rocks.pkb.plektra.ui.BrokerStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Smart keyboard backlight, as a section of the Titan 2 Elite tools screen
 * ([Titan2EliteToolsScreen]), below the device setup card.
 *
 * One switch keeps the keyboard lit past the vendor's 30 s limit by writing the vendor timeout
 * once ([KeyboardBacklightManager]); the value is stored on the phone and survives reboots. The
 * pairing it needs is the device setup card above, so this section does not repeat the pairing
 * walkthrough. It reads the broker verdict the card already keeps current
 * ([BrokerStatusMonitor]) and does not start a check of its own.
 *
 * What it shows is the truth about the phone, not the toggle: "configured" is a one-way latch, so
 * once the broker is reachable the phone is asked what it holds, and a lost setting is reported
 * with a button to apply it again.
 */
@Composable
fun SmartBacklightSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(SettingsManager.getSmartBacklightEnabled(context)) }

    // Stored state is cheap to read, so it is polled to follow a write that lands while the screen
    // is open (the write itself is queued off-thread).
    var statusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            statusTick++
        }
    }
    // Readiness keys off the persisted "configured once" flag, not live Wireless debugging: the
    // value outlives Wireless debugging, so once written the feature is set up.
    val configured = remember(statusTick) { PrivilegedDiagnostics.backlightApplied(context) }
    val lastFailure = remember(statusTick) {
        PrivilegedDiagnostics.last(context, PrivilegedDiagnostics.Step.BACKLIGHT)
            ?.takeIf { !it.ok }
            ?.reason
    }

    val brokerStatus by BrokerStatusMonitor.status.collectAsState()

    // Null until the phone has been asked.
    var verifyTick by remember { mutableIntStateOf(0) }
    var alwaysOnOnPhone by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(enabled, brokerStatus, verifyTick) {
        alwaysOnOnPhone = if (enabled && brokerStatus == EmbeddedAdbShell.BrokerStatus.OK) {
            withContext(Dispatchers.IO) { KeyboardBacklightManager.isAlwaysOnOnDevice(context) }
        } else {
            null
        }
    }

    // Enabled before the pairing or the Wireless debugging switch was ready: apply as soon as the
    // broker is verified reachable. Idempotent, off-thread, and only ever on a verified OK.
    LaunchedEffect(enabled, brokerStatus) {
        if (enabled && brokerStatus == EmbeddedAdbShell.BrokerStatus.OK && !configured) {
            PrivilegedSetup.applyAll(context, reason = "backlight_section")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.smart_backlight_enable_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.smart_backlight_enable_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    SettingsManager.setSmartBacklightEnabled(context, on)
                    // One-time write of the persistent vendor setting (survives reboots). Both
                    // calls are no-ops, with the reason recorded, if the broker cannot connect.
                    if (on) {
                        PrivilegedSetup.applyAll(context, reason = "backlight_toggle")
                    } else {
                        KeyboardBacklightManager.revertToDefault(context)
                    }
                }
            )
        }

        if (enabled) {
            // A verified failure outranks the stored-state verdict: it is the only signal that can
            // catch a pairing the phone never accepted.
            val problem = brokerStatus?.takeIf { it != EmbeddedAdbShell.BrokerStatus.OK }
            if (problem != null) {
                StatusLine(
                    text = "! " + stringResource(
                        when (problem) {
                            EmbeddedAdbShell.BrokerStatus.WIRELESS_DEBUGGING_OFF ->
                                R.string.smart_backlight_blocked_wireless_debugging
                            EmbeddedAdbShell.BrokerStatus.NOT_PAIRED ->
                                R.string.broker_blocked_not_paired
                            EmbeddedAdbShell.BrokerStatus.NO_SERVICE ->
                                R.string.broker_blocked_no_service
                            else -> R.string.device_setup_rejected_body
                        }
                    ),
                    isError = true
                )
            } else if (configured && alwaysOnOnPhone != false) {
                StatusLine(
                    text = "✓ " + stringResource(R.string.smart_backlight_setup_ready),
                    isError = false
                )
            }

            // The broker is reachable but the phone no longer holds the setting: it was written
            // once and something took it back. Nothing in the stored state can see this, which is
            // how a toggle stays on while the backlight times out.
            if (alwaysOnOnPhone == false) {
                StatusLine(
                    text = "! " + stringResource(R.string.smart_backlight_not_on_device),
                    isError = true
                )
                Button(
                    onClick = {
                        scope.launch {
                            KeyboardBacklightManager.applyAlwaysOn(context)
                            // The write is queued; look again after it has had time to land.
                            delay(1_500)
                            verifyTick++
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(stringResource(R.string.smart_backlight_reapply))
                }
            }

            lastFailure?.let { failure ->
                Text(
                    text = stringResource(R.string.smart_backlight_last_error, failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
