package com.mobileailocal.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobileailocal.R
import com.mobileailocal.domain.ProviderType

fun providerNameRes(t: ProviderType): Int = when (t) {
    ProviderType.OpenAi -> R.string.cloud_provider_openai
    ProviderType.Anthropic -> R.string.cloud_provider_anthropic
    ProviderType.Gemini -> R.string.cloud_provider_gemini
    ProviderType.OpenAiCompatCustom -> R.string.cloud_provider_custom
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 設定 → API連携(設計書 8.1)。クラウド連携を使うときだけ Google サインインを求める。
 * 未サインインのときはサインインの案内だけを出し、連携の一覧と追加は出さない。
 */
@Composable
fun ApiSettingsScreen(
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    vm: ApiSettingsViewModel = viewModel(),
) {
    val items by vm.integrations.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val warnings by vm.warnings.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDeleteAccount by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.api_settings_intro), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.api_settings_billing_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )

        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }

        val acc = account
        if (acc == null) {
            // 未サインイン: ローカルモデルの利用にはサインインは不要。クラウド連携を使うときだけ求める
            Text(stringResource(R.string.api_signin_why), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.api_signin_play_services_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            Button(
                onClick = { context.findActivity()?.let(vm::signIn) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.api_signin_button)) }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.api_account_signed_in, acc.email ?: acc.displayName ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.api_sync_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::signOut, enabled = !busy) { Text(stringResource(R.string.api_signout)) }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (items.isEmpty()) {
            Text(stringResource(R.string.api_settings_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.forEach { i ->
            val status = when {
                hasKey[i.id] == false && i.type != ProviderType.OpenAiCompatCustom -> R.string.api_status_no_key
                warnings[i.id] != null -> R.string.api_status_limit
                else -> R.string.api_status_ok
            }
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(i.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(i.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(providerNameRes(i.type)) + " · " + stringResource(R.string.api_models_count, i.enabledModels.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status == R.string.api_status_ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text("›", modifier = Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(stringResource(R.string.api_add))
        }
        OutlinedButton(
            onClick = { confirmDeleteAccount = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) { Text(stringResource(R.string.api_delete_account), color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDeleteAccount) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAccount = false },
            text = { Text(stringResource(R.string.api_delete_account_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAccount = false; vm.deleteAccount() }) {
                    Text(stringResource(R.string.api_delete_account))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAccount = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
