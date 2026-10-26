package com.mobileailocal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobileailocal.R
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.ToolCall
import com.mobileailocal.domain.ToolResult

/** 返答の中のツール呼び出しカードの状態。 */
enum class ToolCardState { Running, Done, Denied, Failed, NotRun }

/**
 * 表示順の messages[index](assistant)の、ツール呼び出しの状態。
 * 次の行がツール結果なら結果から、最後の行で生成中なら実行中、それ以外は「実行されませんでした」(再開時に自動実行しない)。
 */
fun toolCardStateFor(messages: List<ChatMessage>, index: Int, generating: Boolean): ToolCardState {
    val next = messages.getOrNull(index + 1)
    if (next != null && next.role == Role.User && ToolResult.isResult(next.content)) {
        return when (ToolResult.statusOf(next.content)) {
            ToolResult.Status.Ok -> ToolCardState.Done
            ToolResult.Status.Denied -> ToolCardState.Denied
            ToolResult.Status.Error -> ToolCardState.Failed
        }
    }
    return if (generating && index == messages.lastIndex) ToolCardState.Running else ToolCardState.NotRun
}

/** 返答の中のタグを、生のタグの代わりに見せるカード。 */
@Composable
fun ToolCallCard(call: ToolCall, state: ToolCardState) {
    val label = when (call) {
        is ToolCall.List -> stringResource(R.string.tool_card_list, call.path.ifEmpty { "." })
        is ToolCall.Read -> stringResource(R.string.tool_card_read, call.path)
        is ToolCall.Write -> stringResource(R.string.tool_card_write, call.path)
    }
    val stateText = stringResource(
        when (state) {
            ToolCardState.Running -> R.string.tool_state_running
            ToolCardState.Done -> R.string.tool_state_done
            ToolCardState.Denied -> R.string.tool_state_denied
            ToolCardState.Failed -> R.string.tool_state_failed
            ToolCardState.NotRun -> R.string.tool_state_not_run
        },
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                stateText,
                style = MaterialTheme.typography.labelSmall,
                color = if (state == ToolCardState.Failed || state == ToolCardState.Denied) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ツール結果の user 行は、利用者の吹き出しにせず、小さな灰色のカードに折りたたむ(タップで展開)。 */
@Composable
fun ToolResultCard(content: String) {
    var expanded by rememberSaveable(content.hashCode()) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                stringResource(R.string.tool_result_card),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    content.removePrefix(ToolResult.PREFIX),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 書き込みの確認ダイアログ。外側タップ・戻るは「書き込まない」。「今後は確認しない」は作らない。 */
@Composable
fun ToolApprovalDialog(approval: ToolApproval, folderName: String, onAnswer: (Boolean) -> Unit) {
    val pv = approval.preview
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.tool_approve_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (folderName.isNotBlank()) {
                    Text(stringResource(R.string.tool_approve_folder, folderName), style = MaterialTheme.typography.bodySmall)
                }
                Text(pv.path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Text(
                    if (pv.overwrite) stringResource(R.string.tool_approve_overwrite, pv.oldBytes ?: 0L, pv.bytes.toLong())
                    else stringResource(R.string.tool_approve_new, pv.bytes.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pv.overwrite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(pv.head, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        val more = pv.totalChars - pv.head.length
                        if (more > 0) {
                            Text(
                                stringResource(R.string.tool_approve_more, more),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(
                    stringResource(R.string.tool_approve_yes),
                    color = if (pv.overwrite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.tool_approve_no)) }
        },
    )
}
