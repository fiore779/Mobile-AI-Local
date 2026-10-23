package com.mobileailocal.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobileailocal.R
import com.mobileailocal.domain.JsLimits

/**
 * 実行前の確認ダイアログ(設計書 6.2)。コードは省略せず全文を見せる。
 * 外側タップ・戻るはキャンセル(onDismissRequest = onDismiss)。
 */
@Composable
fun JsConfirmDialog(
    code: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // コード欄の最大高さは画面高さの約 50%(超えたら中スクロール)
    val maxCodeHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.js_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.js_confirm_message))
                Text(
                    stringResource(R.string.js_confirm_size, code.lines().size, code.length),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                SelectionContainer(
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxCodeHeight).padding(vertical = 8.dp),
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            code,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                        )
                    }
                }
                Text(
                    stringResource(R.string.js_confirm_limits, (JsLimits.TIMEOUT_MS / 1000).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // 「実行」は強調しない(既定のフォーカスもボタンに置かない)
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.js_confirm_run)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
