package com.mobileailocal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileailocal.R

/** 設定の「作業フォルダ」画面(選択・解除・有効化)。 */
@Composable
fun WorkspaceScreen(vm: WorkspaceViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lost by vm.permissionLost.collectAsStateWithLifecycle()
    val pickError by vm.pickError.collectAsStateWithLifecycle()
    var confirmEnable by rememberSaveable { mutableStateOf(false) }

    // 画面を開くたびに永続権限を確認する
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(settings.workspaceTreeUri) { vm.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onPicked(it) }
    val hasFolder = settings.workspaceTreeUri.isNotBlank()

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
        Text(
            if (hasFolder) settings.workspaceName.ifBlank { settings.workspaceTreeUri } else stringResource(R.string.workspace_none),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (lost) {
            Text(stringResource(R.string.workspace_no_permission), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (pickError) {
            Text(stringResource(R.string.workspace_error_permission), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.workspace_pick)) }
            if (hasFolder) {
                OutlinedButton(onClick = { vm.clear() }) { Text(stringResource(R.string.workspace_clear)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.workspace_enable), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = settings.workspaceEnabled && hasFolder && !lost,
                enabled = hasFolder && !lost,
                // オンにするときは毎回確認する(U18)。オフはそのまま
                onCheckedChange = { if (it) confirmEnable = true else vm.setEnabled(false) },
            )
        }
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
