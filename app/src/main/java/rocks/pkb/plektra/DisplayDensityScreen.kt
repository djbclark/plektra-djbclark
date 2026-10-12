package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.toolbox.DisplayDensity
import rocks.pkb.plektra.toolbox.RevertibleChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Screen density, with the undo armed before the change lands.
 *
 * The countdown is the whole safety model: after applying, the user has a fixed window to say
 * the screen is still readable, and anything else — expiry, a crash, putting the phone down —
 * puts the panel back where it started.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayDensityScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<DisplayDensity.Info?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deadlineMs by remember { mutableStateOf<Long?>(null) }
    var secondsLeft by remember { mutableStateOf<Int?>(null) }

    fun load() {
        loading = true
        scope.launch {
            // A change armed before this screen was (re)created, which is what a density change
            // does to the settings activity, resumes its countdown; one already past its deadline
            // is undone at once.
            val pending = RevertibleChange.pending(context)
            if (pending != null && pending.remainingMs() == 0L) {
                withContext(Dispatchers.IO) { RevertibleChange.revertNow(context) }
            }
            val read = withContext(Dispatchers.IO) { DisplayDensity.read(context) }
            info = read
            if (read != null && selected == 0f) selected = read.current.toFloat()
            deadlineMs = RevertibleChange.pending(context)?.deadlineMs
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    // The countdown IS the confirmation. Reaching zero reverts; only an explicit Keep stops it. The
    // deadline is stored with the pending change, so it survives this screen being recreated.
    LaunchedEffect(deadlineMs) {
        val deadline = deadlineMs ?: run { secondsLeft = null; return@LaunchedEffect }
        while (true) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0) break
            secondsLeft = ((remainingMs + 999) / 1000).toInt()
            delay(250)
        }
        secondsLeft = null
        deadlineMs = null
        withContext(Dispatchers.IO) { RevertibleChange.revertNow(context) }
        load()
    }

    ToolboxPageScaffold(
        title = stringResource(R.string.density_title),
        onBack = onBack,
        modifier = modifier
    ) {
            Text(
                text = stringResource(R.string.density_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                return@ToolboxPageScaffold
            }

            val current = info
            if (current == null) {
                Text(
                    text = stringResource(R.string.bloat_not_paired),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Button(
                    onClick = onOpenPairing,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.bloat_set_up_pairing)) }
                return@ToolboxPageScaffold
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val range = DisplayDensity.range(current.physical)
            val chosen = selected.roundToInt()

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.density_value, chosen, current.physical),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (chosen < current.physical) {
                        stringResource(R.string.density_smaller)
                    } else if (chosen > current.physical) {
                        stringResource(R.string.density_larger)
                    } else {
                        stringResource(R.string.density_stock)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = (it / 5f).roundToInt() * 5f },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    enabled = !busy && secondsLeft == null,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && secondsLeft == null && chosen != current.current,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    DisplayDensity.apply(context, current, chosen)
                                }
                                busy = false
                                when (outcome) {
                                    is RevertibleChange.Outcome.Applied -> {
                                        deadlineMs = RevertibleChange.pending(context)?.deadlineMs
                                    }
                                    is RevertibleChange.Outcome.NotPaired ->
                                        error = context.getString(R.string.bloat_not_paired)
                                    is RevertibleChange.Outcome.Failed -> error = outcome.reason
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.density_apply)) }

                    if (current.isOverridden) {
                        TextButton(
                            enabled = !busy && secondsLeft == null,
                            onClick = {
                                busy = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { DisplayDensity.reset(context) }
                                    busy = false
                                    selected = current.physical.toFloat()
                                    load()
                                }
                            }
                        ) { Text(stringResource(R.string.density_reset)) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        secondsLeft?.let { remaining ->
            AlertDialog(
                onDismissRequest = { /* only Keep or the countdown may close this */ },
                title = { Text(stringResource(R.string.density_confirm_title)) },
                text = { Text(stringResource(R.string.density_confirm_message, remaining)) },
                confirmButton = {
                    TextButton(onClick = {
                        deadlineMs = null
                        RevertibleChange.keep(context)
                        load()
                    }) { Text(stringResource(R.string.density_keep)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        deadlineMs = null
                        scope.launch {
                            withContext(Dispatchers.IO) { RevertibleChange.revertNow(context) }
                            load()
                        }
                    }) { Text(stringResource(R.string.density_revert)) }
                }
            )
        }
}
