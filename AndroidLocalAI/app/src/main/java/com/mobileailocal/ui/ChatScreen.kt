package com.mobileailocal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileailocal.R
import com.mobileailocal.data.DeviceStatus
import com.mobileailocal.data.ModelEntity
import com.mobileailocal.domain.BlockKey
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.CodeBlockParser
import com.mobileailocal.domain.JsLimits
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunState
import com.mobileailocal.domain.MessageSegment
import com.mobileailocal.domain.ReportIntent
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.ThinkingParser
import kotlinx.coroutines.launch
import java.util.Locale

/** 確認ダイアログに開いたコードブロック(確定してから実行する) */
private data class PendingRun(val key: BlockKey, val code: String)

/** チャット画面(指示書 01 を参照)。 */
@Composable
fun ChatScreen(vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val modelLoading by vm.modelLoading.collectAsStateWithLifecycle()
    val deviceStatus by vm.deviceStatus.collectAsStateWithLifecycle()
    val modelError by vm.modelError.collectAsStateWithLifecycle()
    val jsEnabled by vm.jsEnabled.collectAsStateWithLifecycle()
    val jsState by vm.jsState.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var showPromptDialog by remember { mutableStateOf(false) }
    // 確認ダイアログ(「実行」を押すとここへ入れて、確定したら実行する)
    var confirming by remember { mutableStateOf<PendingRun?>(null) }
    val scope = rememberCoroutineScope()
    // 「AI に渡す」で入力欄へ入れたとき、切り詰めがあった間だけ小さく注意を出す
    var handoffTruncated by remember { mutableStateOf(false) }

    // 拡張機能を切ったら確認ダイアログを閉じる
    LaunchedEffect(jsEnabled) { if (!jsEnabled) confirming = null }
    // 報告する回答の本文(押されたメッセージのもの)。null の間はダイアログは閉じている
    val context = LocalContext.current
    var reportText by remember { mutableStateOf<String?>(null) }

    val visible = state.messages.filterNot { it.role == Role.System }
    val lastUserIndex = visible.indexOfLast { it.role == Role.User }
    val listState = rememberLazyListState()

    // 新しいメッセージ(件数・最後の本文長)が変わったら最下部へ
    LaunchedEffect(visible.size, visible.lastOrNull()?.content?.length, state.stopNotice) {
        val count = visible.size + if (state.stopNotice != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize()) {
        ModelBar(vm, models, selectedModelId, modelLoading, onEditPrompt = { showPromptDialog = true })
        modelError?.let { reason ->
            Text(
                stringResource(R.string.chat_model_load_failed, reason),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        DeviceStatusLine(deviceStatus)
        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text(
                    stringResource(R.string.chat_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(visible) { index, m ->
                    val isUser = m.role == Role.User
                    // 吹き出しから呼ぶ口(確認ダイアログを開く / 中止 / AI に渡す / 閉じる)
                    val onRunRequest: (BlockKey, String) -> Unit = { key, code -> confirming = PendingRun(key, code) }
                    val onCancelJs: () -> Unit = { vm.cancelJs() }
                    val onDismissJs: () -> Unit = { vm.dismissJs() }
                    val onHandoff: (JsRunResult) -> Unit = { result ->
                        scope.launch {
                            val (body, truncated) = vm.handoffText(result)
                            text = if (text.isBlank()) body else text + "\n\n" + body
                            handoffTruncated = truncated
                            vm.dismissJs()
                        }
                    }
                    // AI の吹き出しの下に「AI 生成」と「報告」の行(生成中の最後のメッセージと、
                    // 回答が空のメッセージには出さない)
                    val answer = if (isUser) null else ThinkingParser.parse(m.content).answer
                    val showReportRow = answer != null && answer.isNotEmpty() &&
                        !(state.generating && m === visible.last())
                    if (showReportRow) {
                        Column(Modifier.fillMaxWidth()) {
                            Bubble(
                                m,
                                isUser = false,
                                messageIndex = index,
                                jsEnabled = jsEnabled,
                                generating = state.generating,
                                jsState = jsState,
                                onRunRequest = onRunRequest,
                                onCancelJs = onCancelJs,
                                onHandoff = onHandoff,
                                onDismissJs = onDismissJs,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.chat_ai_generated),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { reportText = answer },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.chat_report),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    } else {
                        Bubble(
                            m,
                            isUser = isUser,
                            messageIndex = index,
                            jsEnabled = jsEnabled,
                            generating = state.generating,
                            jsState = jsState,
                            onRunRequest = onRunRequest,
                            onCancelJs = onCancelJs,
                            onHandoff = onHandoff,
                            onDismissJs = onDismissJs,
                        )
                        if (isUser) {
                            // ユーザー吹き出しの下: 「コピー」(すべて)と「再試行」(最後の発言で、生成中でないとき)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = { copyPrompt(context, m.content) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(stringResource(R.string.chat_copy), style = MaterialTheme.typography.labelSmall)
                                }
                                if (!state.generating && index == lastUserIndex) {
                                    TextButton(
                                        onClick = { vm.retry() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) {
                                        Text(stringResource(R.string.chat_retry), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.stopNotice != null) {
                    item {
                        Text(
                            stringResource(R.string.chat_thermal_stopped),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        state.error?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        // 「AI に渡す」で切り詰めたときだけ、入力欄の上に小さく注意を出す(送信・編集で消す)
        if (handoffTruncated) {
            Text(
                stringResource(R.string.js_handoff_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; handoffTruncated = false },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                maxLines = 4,
            )
            if (state.generating) {
                TextButton(onClick = { vm.stop() }) { Text(stringResource(R.string.chat_stop)) }
            } else {
                TextButton(
                    onClick = { if (vm.send(text)) { text = ""; handoffTruncated = false } },
                    enabled = text.isNotBlank(),
                ) {
                    Text(stringResource(R.string.chat_send))
                }
            }
        }
    }

    // システムプロンプト(AI の役割)を編集するダイアログ。開くたびに現在の値から始める
    if (showPromptDialog) {
        var prompt by remember { mutableStateOf(state.systemPrompt) }
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text(stringResource(R.string.chat_system_prompt)) },
            text = {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.chat_system_prompt_hint)) },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.setSystemPrompt(prompt); showPromptDialog = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 報告のダイアログ。「開く」でメールアプリで下書きを開く(送信するかは利用者が決める)
    if (reportText != null) {
        AlertDialog(
            onDismissRequest = { reportText = null },
            text = { Text(stringResource(R.string.chat_report_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    reportText?.let {
                        ReportIntent.launch(
                            context,
                            it,
                            models.firstOrNull { m -> m.id == selectedModelId }?.name,
                        )
                    }
                    reportText = null
                }) { Text(stringResource(R.string.chat_report_open)) }
            },
            dismissButton = {
                TextButton(onClick = { reportText = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 実行前の確認ダイアログ(設計書 6.2)。外側タップ・戻るはキャンセル
    confirming?.let { pending ->
        JsConfirmDialog(
            code = pending.code,
            onConfirm = { vm.runJs(pending.key, pending.code); confirming = null },
            onDismiss = { confirming = null },
        )
    }
}

/** プロンプトをクリップボードへコピーする。API 32 以下はシステムの確認表示が無いので Toast を出す。 */
private fun copyPrompt(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("prompt", text))
    if (Build.VERSION.SDK_INT <= 32) {
        Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
    }
}

/** 端末状態(メモリ・生成速度・発熱)の1行表示。 */
@Composable
private fun DeviceStatusLine(status: DeviceStatus) {
    val style = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var line = stringResource(R.string.chat_status_memory, status.appMemoryMb, status.freeMemoryMb)
    val speed = status.tokensPerSec
    if (speed != null) {
        line += " · " + stringResource(R.string.chat_status_speed, speed)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(line, style = style, color = muted)
        if (status.thermalBlocked) {
            Text(stringResource(R.string.chat_thermal_blocked), style = style, color = MaterialTheme.colorScheme.error)
        } else if (status.hot) {
            Text(stringResource(R.string.chat_hot_warning), style = style, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 画面上部のモデル選択バー(指示書 06 を参照)。 */
@Composable
private fun ModelBar(
    vm: ChatViewModel,
    models: List<ModelEntity>,
    selectedId: String?,
    loading: Float?,
    onEditPrompt: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { expanded = true }) {
                    val label = (selected?.name ?: stringResource(R.string.chat_select_model)) + " ▾"
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (models.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_no_models)) },
                            onClick = {},
                            enabled = false,
                        )
                    } else {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text((if (m.id == selectedId) "✓ " else "") + m.name)
                                        Text(
                                            "${m.quant} · ${formatSize(m.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = { expanded = false; vm.selectModel(m.id) },
                            )
                        }
                    }
                    if (selectedId != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_unload_model)) },
                            onClick = { expanded = false; vm.selectModel(null) },
                        )
                    }
                }
            }
            if (loading != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.chat_loading_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEditPrompt) { Text(stringResource(R.string.chat_system_prompt)) }
        }
        if (loading != null) {
            LinearProgressIndicator(
                progress = { loading },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}

// 表示用のサイズ整形(GB/MB 表記はコード側で固定)
private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%d MB", (mb).toLong())
    }
}

@Composable
private fun Bubble(
    m: ChatMessage,
    isUser: Boolean,
    messageIndex: Int,
    jsEnabled: Boolean,
    generating: Boolean,
    jsState: JsRunState,
    onRunRequest: (BlockKey, String) -> Unit,
    onCancelJs: () -> Unit,
    onHandoff: (JsRunResult) -> Unit,
    onDismissJs: () -> Unit,
) {
    // 思考過程は、回答が出始めたら畳んで、押すと再表示できる(状態は位置ごとに保持)
    val parsed = remember(m.content) { if (isUser) null else ThinkingParser.parse(m.content) }
    var showThinking by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(10.dp)) {
                if (parsed?.thinking != null) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    if (!parsed.thinkingDone) {
                        Text(stringResource(R.string.chat_thinking), style = MaterialTheme.typography.labelMedium, color = muted)
                        Text(parsed.thinking, style = MaterialTheme.typography.bodySmall, color = muted)
                    } else if (parsed.thinking.isNotEmpty()) {
                        Text(
                            stringResource(if (showThinking) R.string.chat_hide_thinking else R.string.chat_show_thinking),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showThinking = !showThinking }.padding(vertical = 2.dp),
                        )
                        if (showThinking) {
                            Text(parsed.thinking, style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
                val body = parsed?.answer ?: m.content
                val topPad = Modifier.padding(top = if (parsed?.thinking != null) 6.dp else 0.dp)
                if (body.isNotEmpty()) {
                    if (isUser) {
                        // ユーザーの吹き出しは従来どおりそのまま(コードブロック処理はしない)
                        Text(body, modifier = topPad, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        // 回答をセグメントへ分けて、コードブロックだけ CodeBlockView / 結果カードで出す
                        val segments = remember(body) { CodeBlockParser.parse(body) }
                        Column(topPad) {
                            var blockIndex = 0
                            for (segment in segments) {
                                when (segment) {
                                    is MessageSegment.Text -> Text(
                                        segment.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    is MessageSegment.Code -> {
                                        // blockIndex は Code セグメントだけを数える(0 始まり)。key は会話内の位置 + コードのハッシュ
                                        val key = BlockKey(messageIndex, blockIndex, segment.code.hashCode())
                                        blockIndex++
                                        CodeBlockView(
                                            lang = segment.lang,
                                            code = segment.code,
                                            runButton = runButtonState(segment, jsEnabled, generating, jsState),
                                            onRun = { onRunRequest(key, segment.code) },
                                        )
                                        // このブロックの実行状態をカードで出す(Running / Finished の key と一致するときだけ)
                                        val stateKey = when (jsState) {
                                            is JsRunState.Running -> jsState.key
                                            is JsRunState.Finished -> jsState.key
                                            JsRunState.Idle -> null
                                        }
                                        if (stateKey == key) {
                                            JsResultCard(
                                                state = jsState,
                                                onCancel = onCancelJs,
                                                onHandoff = onHandoff,
                                                onClose = onDismissJs,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// コードブロックの「実行」ボタンの状態(設計書 6.1 の規則どおりの順で決める)
private fun runButtonState(
    segment: MessageSegment.Code,
    jsEnabled: Boolean,
    generating: Boolean,
    jsState: JsRunState,
): RunButtonState {
    if (!jsEnabled) return RunButtonState.Hidden
    if (segment.lang !in setOf("js", "javascript")) return RunButtonState.Hidden
    // 生成中の最後の回答は、閉じが来ていないだけなので注記を出さない
    if (!segment.closed) return if (generating) RunButtonState.Hidden else RunButtonState.Unclosed
    if (segment.code.isBlank()) return RunButtonState.Hidden
    if (segment.code.length > JsLimits.MAX_CODE_CHARS) return RunButtonState.TooLong
    if (generating || jsState is JsRunState.Running) return RunButtonState.Disabled
    return RunButtonState.Enabled
}
