package com.mobileailocal.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileailocal.R
import com.mobileailocal.data.WorkspaceItem
import java.text.DateFormat
import java.util.Date

/** 設定の「作業フォルダ」画面: 有効／無効、中身の一覧(階層をたどれる)、内容表示、共有・書き出し・削除。 */
@Composable
fun WorkspaceScreen(vm: WorkspaceViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dir by vm.dir.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val total by vm.totalBytes.collectAsStateWithLifecycle()
    val file by vm.file.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmEnable by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) } // 削除確認中のパス
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) } // 書き出すパス

    LaunchedEffect(Unit) { vm.refresh() }
    // 共有の準備ができたら、画面側で起動する
    val share by vm.share.collectAsStateWithLifecycle()
    LaunchedEffect(share) {
        share?.let { runCatching { ctx.startActivity(it) }; vm.consumeShare() }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        exporting?.let { vm.export(it, uri) }
        exporting = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.workspace_desc), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.workspace_cloud_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.workspace_enable), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.workspaceEnabled,
                // オンにするときは毎回確認する(U18)。オフはそのまま
                onCheckedChange = { if (it) confirmEnable = true else vm.setEnabled(false) },
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.workspace_usage, Formatter.formatShortFileSize(ctx, total)),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "/" + dir,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
        )
        if (dir.isNotEmpty()) {
            TextButton(onClick = { vm.up() }) { Text(stringResource(R.string.workspace_up)) }
        }
        if (items.isEmpty()) {
            Text(stringResource(R.string.workspace_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (item in items) ItemRow(
            item, Formatter.formatShortFileSize(ctx, item.size), df.format(Date(item.modified)),
            onClick = { if (item.isDir) vm.open(item.name) else vm.view(item.name) },
            onDelete = { deleting = if (dir.isEmpty()) item.name else dir + "/" + item.name },
        )
        if (items.isNotEmpty() || total > 0) {
            OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.workspace_delete_all), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // 書き出しの結果
    result?.let { ok ->
        AlertDialog(
            onDismissRequest = { vm.consumeResult() },
            text = { Text(stringResource(if (ok) R.string.workspace_export_done else R.string.workspace_export_failed)) },
            confirmButton = { TextButton(onClick = { vm.consumeResult() }) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    // ファイルの内容
    file?.let { f ->
        AlertDialog(
            onDismissRequest = { vm.closeFile() },
            title = { Text(f.path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (f.binary) {
                        Text(stringResource(R.string.workspace_file_binary))
                    } else {
                        Text(f.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (f.truncated) {
                            Text(
                                stringResource(R.string.workspace_view_truncated, WorkspaceViewModel.VIEW_MAX_CHARS),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.closeFile() }) { Text(stringResource(R.string.summary_dialog_close)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.requestShare(f.path) }) { Text(stringResource(R.string.workspace_share)) }
                    TextButton(onClick = {
                        exporting = f.path
                        exportLauncher.launch(f.path.substringAfterLast('/'))
                    }) { Text(stringResource(R.string.workspace_export)) }
                    TextButton(onClick = { deleting = f.path }) {
                        Text(stringResource(R.string.workspace_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    deleting?.let { path ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.workspace_delete_title)) },
            text = { Text(stringResource(R.string.workspace_delete_body, path)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(path); deleting = null }) {
                    Text(stringResource(R.string.workspace_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.workspace_delete_all)) },
            text = { Text(stringResource(R.string.workspace_delete_all_body)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteAll(); confirmAll = false }) {
                    Text(stringResource(R.string.workspace_delete_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(stringResource(R.string.workspace_first_enable_title)) },
            text = { Text(stringResource(R.string.workspace_first_enable_body)) },
            confirmButton = {
                TextButton(onClick = { vm.setEnabled(true); confirmEnable = false }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun ItemRow(item: WorkspaceItem, size: String, modified: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.name + if (item.isDir) "/" else "", style = MaterialTheme.typography.bodyLarge)
            Text(
                "$size  ·  $modified",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.workspace_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}
