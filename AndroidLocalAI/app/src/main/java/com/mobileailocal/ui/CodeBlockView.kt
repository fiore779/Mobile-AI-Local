package com.mobileailocal.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mobileailocal.R
import com.mobileailocal.domain.JsLimits

/** コードブロックの「実行」ボタンの状態(設計書 6.1)。 */
enum class RunButtonState { Hidden, Enabled, Disabled, TooLong, Unclosed }

/** 行数がこれを超えたら省略して「すべて表示」を出す(設計書 6.1) */
private const val MAX_VISIBLE_LINES = 20

/**
 * AI の回答の中のコードブロック。等幅・横スクロール(折り返さない)で、右上に言語名。
 * コード自体は選択不可(コピーは確認ダイアログ側でできる)。
 */
@Composable
fun CodeBlockView(
    lang: String,
    code: String,
    runButton: RunButtonState,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 省略表示の状態はコードが変わったらリセット(生成中はコードが伸びるので remember(code) にする)
    var showAll by remember(code) { mutableStateOf(false) }
    val lines = code.lines()
    val truncated = lines.size > MAX_VISIBLE_LINES
    val display = if (truncated && !showAll) lines.take(MAX_VISIBLE_LINES).joinToString("\n") else code

    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        // 吹き出し(Bubble)の色から浮く色。Bubble は surfaceVariant なので、濃い方の surfaceContainerHighest
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Column(Modifier.padding(8.dp)) {
                if (lang.isNotEmpty()) {
                    Text(
                        lang,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                Text(
                    display,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
        if (truncated && !showAll) {
            TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.js_show_all)) }
        }
        when (runButton) {
            RunButtonState.Hidden -> {}
            RunButtonState.Enabled -> Button(onClick = onRun) { Text(stringResource(R.string.js_run)) }
            RunButtonState.Disabled -> Button(onClick = onRun, enabled = false) {
                Text(stringResource(R.string.js_run))
            }
            RunButtonState.TooLong -> Column {
                Button(onClick = onRun, enabled = false) { Text(stringResource(R.string.js_run)) }
                Text(
                    stringResource(R.string.js_too_long, JsLimits.MAX_CODE_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 閉じていないコードは実行できないのでボタンを出さない(設計書 6.1)
            RunButtonState.Unclosed -> Text(
                stringResource(R.string.js_unclosed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
