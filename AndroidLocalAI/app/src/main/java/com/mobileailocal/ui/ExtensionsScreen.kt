package com.mobileailocal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileailocal.R
import com.mobileailocal.domain.JsLimits
import java.util.Locale

/** 拡張機能画面(設計書 6.4, 7.4〜7.6 を参照)。 */
@Composable
fun ExtensionsScreen(vm: ExtensionsViewModel = viewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    // 画面を開いたときに一度だけ使用状況を読む
    LaunchedEffect(Unit) { vm.refreshUsage() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SectionHeader(stringResource(R.string.ext_js_title))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ext_js_enable),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = { vm.setEnabled(it) })
        }
        Text(
            stringResource(R.string.ext_js_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.ext_js_can), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.ext_js_cannot), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(
                R.string.ext_js_limits,
                (JsLimits.TIMEOUT_MS / 1000).toInt(),
                (JsLimits.MEMORY_BYTES / (1024 * 1024)).toInt(),
                JsLimits.MAX_OUTPUT_CHARS / 1024,
                formatBytes(JsLimits.MAX_FILE_BYTES),
                formatBytes(JsLimits.MAX_TOTAL_BYTES),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader(stringResource(R.string.ext_js_workspace))
        usage?.let {
            Text(
                stringResource(
                    R.string.ext_js_workspace_usage,
                    it.files,
                    formatBytes(it.bytes),
                    formatBytes(JsLimits.MAX_TOTAL_BYTES),
                ),
            )
        }
        OutlinedButton(onClick = { showClearDialog = true }, enabled = !running) {
            Text(stringResource(R.string.ext_js_clear))
        }

        Text(
            stringResource(R.string.ext_js_license),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            text = { Text(stringResource(R.string.ext_js_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearWorkspace(); showClearDialog = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// SettingsScreen の SectionHeader と同じ見た目(あちらは private なのでここにも置く)
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    )
}

// 表示用のサイズ整形(MB は 10^6、KB は 1000 基準。設計書の上限と同じ単位)
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1000 -> String.format(Locale.getDefault(), "%d KB", bytes / 1000)
    else -> String.format(Locale.getDefault(), "%d B", bytes)
}
