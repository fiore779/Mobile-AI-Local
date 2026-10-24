package com.mobileailocal.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mobileailocal.R
import com.mobileailocal.domain.ProviderType

/** 送信先を明示する確認(設計書 8.2-2)。同意しなければ先へ進めない。 */
@Composable
fun CloudConsentDialog(type: ProviderType, host: String?, onAccept: () -> Unit, onDecline: () -> Unit) {
    // カスタムは送信先の実体が分かるように、接続先のホストを出す
    val name = if (type == ProviderType.OpenAiCompatCustom && !host.isNullOrEmpty()) host
    else stringResource(providerNameRes(type))
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.cloud_consent_title)) },
        text = { Text(stringResource(R.string.cloud_consent_body, name)) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.cloud_consent_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.common_cancel)) } },
    )
}
