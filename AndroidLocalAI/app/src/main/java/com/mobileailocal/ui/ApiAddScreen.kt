package com.mobileailocal.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileailocal.R
import com.mobileailocal.domain.CloudInputs
import com.mobileailocal.domain.CloudProviders
import com.mobileailocal.domain.ProviderType

/**
 * 連携の追加・編集(設計書 8.2)。ブラウザでキーを発行 → 貼り付け → 接続を確認 → 使うモデルを選ぶ → 保存。
 * 編集のときは種別を固定し、キー欄を空のままにすると今のキーを使い続ける。
 */
@Composable
fun ApiAddScreen(onDone: () -> Unit, vm: ApiAddViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(s.saved) { if (s.saved) onDone() }

    s.consentFor?.let { CloudConsentDialog(it, s.consentHost, onAccept = vm::acceptConsent, onDecline = vm::declineConsent) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val type = s.type
        if (type == null) {
            Text(stringResource(R.string.api_pick_provider), style = MaterialTheme.typography.titleMedium)
            // 並びは定義順で固定し、おすすめ表示は付けない(設計書 8.2-1)
            ProviderType.entries.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { vm.pickType(t) }.padding(vertical = 12.dp)) {
                    Text(stringResource(providerNameRes(t)), Modifier.weight(1f))
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            return@Column
        }

        val provider = CloudProviders.of(type)
        Text(stringResource(providerNameRes(type)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(provider.helpTextRes), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.api_subscription_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        provider.keyIssueUrl?.let { url ->
            OutlinedButton(onClick = { openUrl(context, url) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.api_open_browser))
            }
        }

        if (type == ProviderType.OpenAiCompatCustom) {
            OutlinedTextField(
                value = s.baseUrl, onValueChange = vm::setBaseUrl, singleLine = true,
                label = { Text(stringResource(R.string.api_base_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.key, onValueChange = vm::setKey, singleLine = true,
                label = {
                    Text(
                        stringResource(
                            if (s.editingId != null && s.hasStoredKey) R.string.api_key_keep else R.string.api_key,
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pasteKey(context)?.let(vm::setKey) }) { Text(stringResource(R.string.api_paste)) }
        }

        Button(
            onClick = vm::testConnection,
            enabled = !s.testing && (s.key.isNotEmpty() || s.hasStoredKey || type == ProviderType.OpenAiCompatCustom),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (s.testing) R.string.api_testing else R.string.api_test)) }
        if (s.testOk) Text(stringResource(R.string.api_test_ok), color = MaterialTheme.colorScheme.primary)
        s.testError?.let { err ->
            val ui = cloudErrorUi(err.kind)
            Text(stringResource(ui.messageRes), color = MaterialTheme.colorScheme.error)
            cloudErrorDetail(err)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 上限・課金の失敗でも、キーは有効なので保存はできる
            Text(stringResource(R.string.api_test_failed_hint), style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.api_models), style = MaterialTheme.typography.titleSmall)
        s.fetched.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.toggleModel(m) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = m in s.selected, onCheckedChange = { vm.toggleModel(m) })
                Text(m, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.manual, onValueChange = vm::setManual, singleLine = true,
                label = { Text(stringResource(R.string.api_model_manual)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::addManual, enabled = s.manual.isNotBlank()) { Text(stringResource(R.string.api_model_add)) }
        }

        OutlinedTextField(
            value = s.name, onValueChange = vm::setName, singleLine = true,
            label = { Text(stringResource(R.string.api_display_name)) },
            modifier = Modifier.fillMaxWidth(),
        )

        s.formError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        // 保存に失敗したときは、原因(例外クラス名とメッセージ)を「詳細」で見せる
        s.saveErrorDetail?.let { detail ->
            var open by remember(detail) { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) {
                Text(stringResource(if (open) R.string.error_detail_hide else R.string.error_detail_show))
            }
            if (open) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(onClick = vm::save, enabled = !s.saving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.api_save))
        }

        if (s.editingId != null) {
            OutlinedButton(onClick = vm::revokeConsent, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.api_revoke_consent))
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.api_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.api_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(R.string.api_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** クリップボードの文字列を、キーらしいときだけ返す(押された直後だけ読む)。 */
private fun pasteKey(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    return text?.takeIf { CloudInputs.looksLikeKey(it) }?.trim()
}
