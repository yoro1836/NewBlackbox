package top.niunaijun.blackboxa.view.apps

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.data.FrameGen

private val TARGETS = listOf(60, 90, 120, 144)
private val SCALES = listOf(50, 67, 75, 100)

/** Per-app frame generation settings; they apply the next time the app starts. */
@Composable
fun FrameGenDialog(app: AppInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(FrameGen.get(context, app.packageName)) }
    var showLog by remember { mutableStateOf(false) }
    val available = remember { FrameGen.library(context) != null }

    if (showLog) {
        val log = remember { FrameGen.logTail(context, app.packageName) }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.framegen_log)) },
            text = {
                SelectionContainer {
                    Text(
                        log ?: stringResource(R.string.framegen_log_empty),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.done)) } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.framegen)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(if (available) R.string.framegen_hint else R.string.framegen_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.framegen_enable), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    Switch(checked = cfg.on, enabled = available, onCheckedChange = { cfg = cfg.copy(on = it) })
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.framegen_mode), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to R.string.framegen_interpolate, true to R.string.framegen_extrapolate)
                        .forEachIndexed { i, (extra, label) ->
                            SegmentedButton(
                                selected = cfg.extrapolate == extra,
                                onClick = { cfg = cfg.copy(extrapolate = extra) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2)
                            ) { Text(stringResource(label)) }
                        }
                }
                Text(
                    stringResource(if (cfg.extrapolate) R.string.framegen_extrapolate_hint else R.string.framegen_interpolate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.framegen_target), style = MaterialTheme.typography.titleSmall)
                ChipRow(TARGETS, cfg.target, { "$it" }) { cfg = cfg.copy(target = it) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.framegen_scale), style = MaterialTheme.typography.titleSmall)
                ChipRow(SCALES, cfg.scale, { "$it%" }) { cfg = cfg.copy(scale = it) }
                Text(
                    stringResource(R.string.framegen_scale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                FrameGen.put(context, app.packageName, cfg)
                onDismiss()
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showLog = true }) { Text(stringResource(R.string.framegen_log)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun ChipRow(values: List<Int>, selected: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        values.forEach { v ->
            FilterChip(selected = selected == v, onClick = { onPick(v) }, label = { Text(label(v)) })
        }
    }
}
