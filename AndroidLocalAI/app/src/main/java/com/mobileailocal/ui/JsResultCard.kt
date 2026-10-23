package com.mobileailocal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobileailocal.R
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunState
import com.mobileailocal.domain.JsRunStatus
import com.mobileailocal.domain.JsStopCause

/**
 * 実行結果のカード(設計書 6.3)。Running か Finished のときだけ描く(Idle なら何も描かない)。
 * 本文は選択・コピー可。全体で画面高さの約 40%まで、超えたら中スクロール。
 */
@Composable
fun JsResultCard(
    state: JsRunState,
    onCancel: () -> Unit,
    onHandoff: (JsRunResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 本文の最大高さは画面高さの約 40%
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp

    when (state) {
        JsRunState.Idle -> {}

        is JsRunState.Running -> Surface(
            modifier = modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(stringResource(R.string.js_running), style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.js_cancel_run)) }
            }
        }

        is JsRunState.Finished -> {
            val result = state.result
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val heading = when {
                result.status == JsRunStatus.Ok ->
                    stringResource(R.string.js_result_ok, result.elapsedMs.toInt())
                result.status == JsRunStatus.Error && result.cause == JsStopCause.LowMemory ->
                    stringResource(R.string.js_result_low_memory)
                result.status == JsRunStatus.Error -> stringResource(R.string.js_result_error)
                result.status == JsRunStatus.Timeout ->
                    stringResource(R.string.js_result_timeout, (JsLimits.TIMEOUT_MS / 1000).toInt())
                result.status == JsRunStatus.Crashed && result.cause == JsStopCause.ConnectFailed ->
                    stringResource(R.string.js_result_connect_failed)
                result.status == JsRunStatus.Crashed -> stringResource(R.string.js_result_crashed)
                else -> stringResource(R.string.js_result_cancelled) // Killed
            }
            // 二段目・三段目で止めた Timeout だけ「強制的に止めた」ことを添える
            val forced = result.status == JsRunStatus.Timeout &&
                (result.cause == JsStopCause.WatchdogTimeout || result.cause == JsStopCause.ClientTimeout)
            val showNoOutput = result.status == JsRunStatus.Ok && result.console.isEmpty() && result.returnValue == null

            Surface(
                modifier = modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(heading, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (forced) {
                        Text(
                            stringResource(R.string.js_result_timeout_forced),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                    SelectionContainer(
                        modifier = Modifier.fillMaxWidth().heightIn(max = maxBodyHeight).padding(top = 4.dp),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            result.returnValue?.let { value ->
                                Text(
                                    stringResource(R.string.js_result_return),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                )
                                Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            // エラー文は console の上
                            result.error?.let { errorText ->
                                Text(
                                    errorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                )
                            }
                            if (result.console.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.js_result_console),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                )
                                Text(result.console, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            if (showNoOutput) {
                                Text(stringResource(R.string.js_result_no_output), style = MaterialTheme.typography.bodySmall)
                            }
                            if (result.consoleTruncated) {
                                Text(
                                    stringResource(R.string.js_result_truncated),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = muted,
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        // 「AI に渡す」は成功しなくても出す(結果をそのまま文章にできる)
                        TextButton(onClick = { onHandoff(result) }) { Text(stringResource(R.string.js_handoff)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.js_close)) }
                    }
                }
            }
        }
    }
}
