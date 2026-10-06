package it.palsoftware.pastiera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.window.DialogProperties
import it.palsoftware.pastiera.inputmethod.PrivilegedDiagnostics
import it.palsoftware.pastiera.toolbox.BloatCatalog
import it.palsoftware.pastiera.toolbox.FnCtrlRemap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * djb/toolbox: everything the Titan 2 Elite toolbox adds to the Titan 2 Elite tools screen.
 *
 * [ToolboxSection] is the block of rows rendered under the device setup card. Four of them open a
 * page ([ToolboxPage]) as a child settings activity, the pattern the rest of Settings uses; the
 * Fn -> Ctrl card, the status-bar switch and the reset row work in place.
 *
 * None of this runs in the IME: every privileged call is on a settings screen, on an IO
 * dispatcher, through the typed command layer (inputmethod/PrivilegedCommand.kt).
 */

/** The pages the toolbox opens as children of the Titan 2 Elite tools screen. */
enum class ToolboxPage(val child: String) {
    BLOAT("bloat"),
    DENSITY("density"),
    TWEAKS("tweaks"),
    KEY_MAPPING("keymap");

    companion object {
        /** The `settingsChild` section this screen reads. */
        const val SECTION = "t2e_toolbox"

        fun fromChild(value: String?): ToolboxPage? = entries.firstOrNull { it.child == value }
    }
}

private fun openToolboxPage(context: Context, page: ToolboxPage) =
    openSettingsChild(context, ToolboxPage.SECTION, page.child)

/** Renders the page a child activity was opened for. */
@Composable
fun ToolboxPageHost(page: ToolboxPage, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    when (page) {
        // The pairing card is at the top of the tools screen, which is where "back" lands.
        ToolboxPage.BLOAT -> BloatRemoverScreen(modifier = modifier, onBack = onBack, onOpenPairing = onBack)
        ToolboxPage.DENSITY -> DisplayDensityScreen(modifier = modifier, onBack = onBack, onOpenPairing = onBack)
        ToolboxPage.TWEAKS -> SystemTweaksScreen(modifier = modifier, onBack = onBack, onOpenPairing = onBack)
        ToolboxPage.KEY_MAPPING -> KeyMappingScreen(
            modifier = modifier,
            onBack = onBack,
            // Fn's layer and modifier role live on Pastiera's Fn layer (nav mode) screen.
            onFnLayer = { openSettingsPage(context, SettingsPage(SettingsDestination.NavMode)) }
        )
    }
}

/** Top bar and scroll container shared by the toolbox pages. */
@Composable
internal fun ToolboxPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler { onBack() }
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
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            content = content
        )
    }
}

/** The toolbox rows for the Titan 2 Elite tools screen. */
@Composable
fun ToolboxSection() {
    val context = LocalContext.current

    SettingsSectionDivider(stringResource(R.string.toolbox_group_system))

    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_BLOAT, R.string.bloat_title, R.string.bloat_row_description) {
        openToolboxPage(context, ToolboxPage.BLOAT)
    }
    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_DENSITY, R.string.density_title, R.string.density_row_description) {
        openToolboxPage(context, ToolboxPage.DENSITY)
    }
    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_TWEAKS, R.string.tweaks_title, R.string.tweaks_row_description) {
        openToolboxPage(context, ToolboxPage.TWEAKS)
    }
    ToolboxLinkRow(SettingLinkIds.TITAN2_ELITE_TOOLS_KEY_MAPPING, R.string.keymap_title, R.string.keymap_row_description) {
        openToolboxPage(context, ToolboxPage.KEY_MAPPING)
    }

    FnCtrlCard(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_FN_CTRL))
    ShowStatusBarRow(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_SHOW_STATUS_BAR))
    ResetToStockRow(modifier = Modifier.settingRow(SettingLinkIds.TITAN2_ELITE_TOOLS_RESET_TO_STOCK))
}

@Composable
private fun ToolboxLinkRow(
    linkId: String,
    @StringRes title: Int,
    @StringRes description: Int,
    onClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().settingRow(linkId, onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(description),
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

/** The master "Show status bar" switch. Off collapses the on-screen strip; the keyboard stays active. */
@Composable
private fun ShowStatusBarRow(modifier: Modifier) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(SettingsManager.getShowStatusBar(context)) }
    fun toggle(value: Boolean) {
        show = value
        SettingsManager.setShowStatusBar(context, value)
    }
    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.show_status_bar_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.show_status_bar_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = show, onCheckedChange = ::toggle)
        }
    }
}

private enum class FnAction { APPLY, REVERT }

/**
 * Fn -> Ctrl at the system level (experimental). The write goes through "Modify system settings"
 * when granted, otherwise the paired broker; if neither is available the grant screen opens, and
 * whichever action was asked for is the one that resumes when the user comes back.
 */
@Composable
private fun FnCtrlCard(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var alreadySet by remember { mutableStateOf(false) }
    var statusRes by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf<FnAction?>(null) }
    var grantReturned by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        alreadySet = withContext(Dispatchers.IO) { FnCtrlRemap.isSet(context) }
    }

    val grantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        grantReturned++
    }

    fun startFn(action: FnAction, askPermission: () -> Unit) {
        if (busy) return
        busy = true
        statusRes = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (action == FnAction.APPLY) FnCtrlRemap.apply(context) else FnCtrlRemap.revert(context)
            }
            when (result) {
                FnCtrlRemap.Result.SUCCESS -> {
                    alreadySet = action == FnAction.APPLY
                    statusRes = if (action == FnAction.APPLY) R.string.fn_ctrl_status_success else R.string.fn_ctrl_reset_success
                }
                FnCtrlRemap.Result.NEEDS_PERMISSION -> {
                    statusRes = R.string.fn_ctrl_status_needs_permission
                    resume = action
                    askPermission()
                }
                FnCtrlRemap.Result.FAILED -> {
                    statusRes = if (action == FnAction.APPLY) R.string.fn_ctrl_status_failed else R.string.fn_ctrl_reset_failed
                }
            }
            busy = false
        }
    }

    fun launchGrantScreen() {
        runCatching {
            grantLauncher.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            )
        }
    }

    // Back from the grant screen: if it was granted, finish what the user asked for.
    LaunchedEffect(grantReturned) {
        val action = resume
        if (grantReturned > 0 && action != null && Settings.System.canWrite(context)) {
            resume = null
            startFn(action) {}
        }
    }

    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fn_ctrl_map_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.fn_ctrl_map_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = stringResource(R.string.fn_ctrl_map_warning),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (alreadySet) {
                    Text(
                        text = stringResource(R.string.fn_ctrl_map_already_set),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                statusRes?.let { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (alreadySet && !busy) {
                    TextButton(
                        onClick = { startFn(FnAction.REVERT, ::launchGrantScreen) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.fn_ctrl_reset_action),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(onClick = { startFn(FnAction.APPLY, ::launchGrantScreen) }) {
                    Text(
                        text = stringResource(R.string.fn_ctrl_map_action),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** Where the reset flow is: the row, the confirm dialog, the running dialog. */
private sealed interface ResetStage {
    data object Idle : ResetStage
    data object Preparing : ResetStage
    data class Confirm(val plan: SystemChangeManager.Plan) : ResetStage
    data class Running(val entries: List<ResetPlan.Entry>, val finished: Boolean) : ResetStage
}

/**
 * "Reset device settings to stock": undoes what survives an uninstall, so it comes before one.
 *
 * Tapping it reads what is actually changed on the phone right now (off the main thread), lists
 * exactly that in a confirm dialog, and then runs those steps off the main thread while the same
 * dialog shows each step's result as it ends: done, failed with the reason, or nothing to do.
 */
@Composable
private fun ResetToStockRow(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<ResetStage>(ResetStage.Idle) }
    val results = remember { mutableStateMapOf<ResetPlan.Item, ResetPlan.StepResult>() }
    val busy = stage is ResetStage.Preparing || (stage as? ResetStage.Running)?.finished == false

    fun prepare() {
        if (stage != ResetStage.Idle) return
        stage = ResetStage.Preparing
        scope.launch {
            val plan = withContext(Dispatchers.IO) { SystemChangeManager.plan(context) }
            stage = ResetStage.Confirm(plan)
        }
    }

    fun start(plan: SystemChangeManager.Plan) {
        results.clear()
        stage = ResetStage.Running(plan.entries, finished = false)
        scope.launch {
            // Each step reports as it ends; writes to Compose state are safe from any thread.
            withContext(Dispatchers.IO) {
                SystemChangeManager.resetToStock(context, plan.entries) { entry, result ->
                    results[entry.item] = result
                }
            }
            stage = ResetStage.Running(plan.entries, finished = true)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .settingRow(null) { prepare() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SettingsBackupRestore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.reset_to_stock_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Text(
                    text = stringResource(R.string.reset_to_stock_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    when (val current = stage) {
        is ResetStage.Confirm -> if (current.plan.isEmpty) {
            AlertDialog(
                onDismissRequest = { stage = ResetStage.Idle },
                title = { Text(stringResource(R.string.reset_to_stock_confirm_title)) },
                text = { Text(stringResource(R.string.reset_to_stock_nothing)) },
                confirmButton = {
                    TextButton(onClick = { stage = ResetStage.Idle }) {
                        Text(stringResource(R.string.reset_to_stock_close))
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { stage = ResetStage.Idle },
                title = { Text(stringResource(R.string.reset_to_stock_confirm_title)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.reset_to_stock_confirm_message))
                        current.plan.entries.forEach { entry -> ResetEntryLines(entry, result = null, showResult = false) }
                        current.plan.brokerBlocker?.let { blocker ->
                            Text(
                                text = stringResource(R.string.reset_to_stock_broker_blocked, resetReasonText(blocker)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = stringResource(R.string.reset_to_stock_confirm_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { start(current.plan) }) {
                        Text(stringResource(R.string.reset_to_stock_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stage = ResetStage.Idle }) {
                        Text(stringResource(R.string.reset_to_stock_confirm_cancel))
                    }
                }
            )
        }
        is ResetStage.Running -> AlertDialog(
            // Not dismissable while it runs: the steps keep going either way, and the results are
            // the only place their outcome is shown.
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(
                    stringResource(
                        if (current.finished) R.string.reset_to_stock_finished_title
                        else R.string.reset_to_stock_running_title
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // The first step with no result yet is the one running.
                    val runningItem = current.entries.firstOrNull { results[it.item] == null }?.item
                    current.entries.forEach { entry ->
                        ResetEntryLines(
                            entry,
                            result = results[entry.item],
                            showResult = true,
                            running = !current.finished && entry.item == runningItem
                        )
                    }
                    if (current.finished) {
                        val allOk = current.entries.none { results[it.item]?.status != ResetPlan.StepResult.Status.DONE &&
                            results[it.item]?.status != ResetPlan.StepResult.Status.NOTHING_TO_DO }
                        Text(
                            text = stringResource(
                                if (allOk) R.string.reset_to_stock_result_success
                                else R.string.reset_to_stock_result_some_failed
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (current.finished) {
                    TextButton(onClick = { stage = ResetStage.Idle }) {
                        Text(stringResource(R.string.reset_to_stock_close))
                    }
                }
            }
        )
        else -> Unit
    }
}

/** One plan entry: its label, the packages for the apps step, and (once running) its result. */
@Composable
private fun ResetEntryLines(
    entry: ResetPlan.Entry,
    result: ResetPlan.StepResult?,
    showResult: Boolean,
    running: Boolean = false
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!showResult) Text("•", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = resetItemLabel(entry),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
        if (entry.item == ResetPlan.Item.REMOVED_APPS) {
            val names = remember(entry.packages) { entry.packages.map(::packageDisplayName) }
            Text(
                text = names.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = if (showResult) 0.dp else 16.dp)
            )
        }
        if (showResult) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = resetStatusText(result),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (result?.status == ResetPlan.StepResult.Status.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** The bloat catalog's name for a package, or the package name when the catalog does not know it. */
private fun packageDisplayName(packageName: String): String =
    BloatCatalog.entries().firstOrNull { it.packageName == packageName }?.label ?: packageName

@Composable
private fun resetItemLabel(entry: ResetPlan.Entry): String = when (entry.item) {
    ResetPlan.Item.FN_CTRL -> stringResource(R.string.reset_item_fn_ctrl)
    ResetPlan.Item.DISPLAY_DENSITY -> stringResource(R.string.reset_item_density)
    ResetPlan.Item.SYSTEM_TWEAKS -> stringResource(R.string.reset_item_tweaks)
    ResetPlan.Item.REMOVED_APPS -> stringResource(R.string.reset_item_apps, entry.packages.size)
    ResetPlan.Item.RING_OFF -> stringResource(R.string.reset_item_ring_off)
    ResetPlan.Item.RING_GRANTS -> stringResource(R.string.reset_item_ring_grants)
    ResetPlan.Item.KEYBOARD_TIMEOUT -> stringResource(R.string.reset_item_timeout)
    ResetPlan.Item.KEYBOARD_LIGHT_HELD -> stringResource(R.string.reset_item_light_held)
    ResetPlan.Item.SIDE_KEY -> stringResource(R.string.reset_item_side_key)
}

/** A reason code as words; a reason that is not a known code is already a message. */
@Composable
private fun resetReasonText(reason: String): String {
    val res = when (reason) {
        PrivilegedDiagnostics.REASON_NOT_PAIRED -> R.string.reset_reason_not_paired
        PrivilegedDiagnostics.REASON_WIRELESS_DEBUGGING_OFF -> R.string.reset_reason_wireless_debugging_off
        PrivilegedDiagnostics.REASON_SHELL_FAILED -> R.string.reset_reason_shell_failed
        SystemChangeManager.REASON_NEEDS_PERMISSION -> R.string.reset_reason_needs_permission
        SystemChangeManager.REASON_NEEDS_SECURE_SETTINGS -> R.string.reset_reason_needs_secure_settings
        SystemChangeManager.REASON_NOT_APPLIED -> R.string.reset_reason_not_applied
        else -> null
    }
    return if (res != null) stringResource(res) else reason
}

/** done / failed with the reason / nothing to do; "waiting" while a step has not ended yet. */
@Composable
private fun resetStatusText(result: ResetPlan.StepResult?): String {
    if (result == null) return stringResource(R.string.reset_status_waiting)
    val counts = if (result.total > 0) stringResource(R.string.reset_apps_count, result.count, result.total) else null
    return when (result.status) {
        ResetPlan.StepResult.Status.DONE -> counts ?: stringResource(R.string.reset_status_done)
        ResetPlan.StepResult.Status.NOTHING_TO_DO -> stringResource(R.string.reset_status_nothing)
        ResetPlan.StepResult.Status.FAILED -> {
            val base = result.reason?.let { stringResource(R.string.reset_status_failed_reason, resetReasonText(it)) }
                ?: stringResource(R.string.reset_status_failed)
            if (counts != null) "$base ($counts)" else base
        }
    }
}
