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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import com.mobileailocal.R
import com.mobileailocal.domain.ErrorDetailText
import com.mobileailocal.domain.ApiError
import com.mobileailocal.domain.MarkdownLite
import com.mobileailocal.domain.MdBlock
import com.mobileailocal.domain.MdInline
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.CloudIntegration
import com.mobileailocal.domain.CloudModelRef
import com.mobileailocal.domain.CloudProviders
import com.mobileailocal.domain.ModelSelection
import com.mobileailocal.domain.Origin
import com.mobileailocal.domain.ProviderType
import com.mobileailocal.domain.loadingPercent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** 確認ダイアログに開いたコードブロック(確定してから実行する) */
private data class PendingRun(val key: BlockKey, val code: String)

/** チャット画面(指示書 01 を参照)。 */
@Composable
fun ChatScreen(
    vm: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    /** 設定の API連携を開く(連携 ID があればその詳細)。 */
    onOpenApiSettings: (String?) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val modelLoading by vm.modelLoading.collectAsStateWithLifecycle()
    val deviceStatus by vm.deviceStatus.collectAsStateWithLifecycle()
    val modelError by vm.modelError.collectAsStateWithLifecycle()
    val jsEnabled by vm.jsEnabled.collectAsStateWithLifecycle()
    val jsState by vm.jsState.collectAsStateWithLifecycle()
    val selection by vm.selectedModel.collectAsStateWithLifecycle()
    val integrations by vm.cloudIntegrations.collectAsStateWithLifecycle()
    val warnings by vm.cloudWarnings.collectAsStateWithLifecycle()
    var menuSignal by remember { mutableIntStateOf(0) }
    val cloudSel = selection as? ModelSelection.Cloud
    val cloudInteg = cloudSel?.let { s -> integrations.firstOrNull { it.id == s.ref.integrationId } }
    var text by rememberSaveable { mutableStateOf("") }
    var showPromptDialog by remember { mutableStateOf(false) }
    // 確認ダイアログ(「実行」を押すとここへ入れて、確定したら実行する)
    var confirming by remember { mutableStateOf<PendingRun?>(null) }
    val scope = rememberCoroutineScope()
    // 「AI に渡す」で入力欄へ入れたとき、切り詰めがあった間だけ小さく注意を出す
    var handoffTruncated by remember { mutableStateOf(false) }

    // 送信を断られたとき(同意の取り消しなど)は、入力欄に本文を戻す
    LaunchedEffect(state.rejectedText) {
        state.rejectedText?.let { if (text.isBlank()) text = it; vm.consumeRejectedText() }
    }

    // 拡張機能を切ったら確認ダイアログを閉じる
    LaunchedEffect(jsEnabled) { if (!jsEnabled) confirming = null }
    // 報告する回答の本文(押されたメッセージのもの)。null の間はダイアログは閉じている
    val context = LocalContext.current
    var reportText by remember { mutableStateOf<String?>(null) }

    // AI の回答の上に出す提供元の目印(クラウドは ☁ と接続先名、ローカルはモデル名)
    val localLabel = models.firstOrNull { it.id == selectedModelId }?.name ?: stringResource(R.string.chat_label_local)
    val cloudLabel = "☁ " + (cloudInteg?.displayName ?: stringResource(R.string.cloud_section))
    fun aiLabel(m: ChatMessage): String = if (m.origin == Origin.Cloud) cloudLabel else localLabel

    val visible = state.messages.filterNot { it.role == Role.System }
    val lastUserIndex = visible.indexOfLast { it.role == Role.User }
    val listState = rememberLazyListState()

    // 新しいメッセージ(件数・最後の本文長)が変わったら最下部へ
    LaunchedEffect(visible.size, visible.lastOrNull()?.content?.length, state.stopNotice) {
        val count = visible.size + if (state.stopNotice != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize()) {
        ModelBar(
            vm, models, selectedModelId, modelLoading, selection, integrations, warnings, menuSignal,
            onEditPrompt = { showPromptDialog = true },
        )
        modelLoading?.let { progress ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.chat_loading_model_banner, loadingPercent(progress)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        modelError?.let { reason ->
            val (summary, detail) = ErrorDetailText.split(reason)
            ErrorBlock(stringResource(R.string.chat_model_load_failed, summary), detail, small = true)
        }
        DeviceStatusLine(deviceStatus)
        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text(
                    if (cloudSel != null) stringResource(R.string.chat_empty_cloud, cloudInteg?.displayName ?: "")
                    else stringResource(R.string.chat_empty),
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
                                label = aiLabel(m),
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
                                    (if (m.origin == Origin.Cloud) "☁ " else "") + stringResource(R.string.chat_ai_generated),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                                TextButton(
                                    onClick = { copyPrompt(context, answer ?: "") },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(stringResource(R.string.chat_copy), style = MaterialTheme.typography.labelSmall)
                                }
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
                            label = if (isUser) null else aiLabel(m),
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
        // クラウドの応答を待っている間(最初の文字が来るまで)。推論型のモデルは考えるのに時間がかかる
        val lastMsg = visible.lastOrNull()
        if (state.generating && lastMsg != null && lastMsg.role == Role.Assistant &&
            lastMsg.origin == Origin.Cloud && lastMsg.content.isBlank()
        ) {
            // 経過秒は送信を始めた時刻(状態)から数えるので、画面の回転で 0 に戻らない
            var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(1000); nowMs = System.currentTimeMillis() } }
            val waited = ((nowMs - state.sendStartedAt) / 1000).coerceAtLeast(0).toInt()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.cloud_waiting, waited),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.cloudError?.let { err ->
            CloudErrorBlock(
                err,
                hasProviderPage = cloudInteg?.let { CloudProviders.of(it.type).keyIssueUrl != null } ?: false,
                onAction = { action ->
                    when (action) {
                        CloudErrorAction.ReEnterKey, CloudErrorAction.OpenSettings -> onOpenApiSettings(cloudSel?.ref?.integrationId)
                        CloudErrorAction.OpenProviderConsole -> cloudInteg?.let { openProviderConsole(context, it.type) }
                        CloudErrorAction.Retry -> vm.retry()
                        CloudErrorAction.ReselectModel, CloudErrorAction.SwitchToLocal -> { vm.dismissCloudError(); menuSignal++ }
                    }
                },
            )
        }
        if (state.maxTokensNotice) {
            Text(
                stringResource(R.string.cloud_max_tokens_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        if (state.trimmedNotice) {
            Text(
                stringResource(R.string.cloud_trimmed_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        state.error?.let { message ->
            val (summary, detail) = ErrorDetailText.split(message)
            ErrorBlock(summary, detail, small = false)
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
        if (cloudSel != null) {
            Text(
                "☁ " + stringResource(R.string.cloud_sending_notice, cloudInteg?.displayName ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
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
                    // 会話の途中でクラウドへ切り替えた最初の送信は、VM が保留して確認ダイアログを出させる
                    onClick = { if (vm.send(text)) { text = ""; handoffTruncated = false } },
                    enabled = text.isNotBlank() && modelLoading == null,
                ) {
                    Text(stringResource(R.string.chat_send))
                }
            }
        }
    }

    state.pendingCloudConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.cancelPendingSend() },
            title = { Text(stringResource(R.string.cloud_history_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_history_confirm_body, cloudInteg?.displayName ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmPendingSend()
                    // 新しい送信なら入力欄を空にする(再生成では入力欄に触れない)
                    if (!pending.retry && pending.text == text) { text = ""; handoffTruncated = false }
                }) { Text(stringResource(R.string.cloud_history_confirm_send)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelPendingSend() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
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
                            if (cloudSel != null) (cloudInteg?.displayName ?: "") + " / " + cloudSel.ref.model
                            else models.firstOrNull { m -> m.id == selectedModelId }?.name,
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

/** エラー文。詳細があれば、タップで展開でき、コピーもできる。 */
@Composable
private fun ErrorBlock(summary: String, detail: String?, small: Boolean) {
    var open by remember(summary, detail) { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = if (small) 16.dp else 12.dp, vertical = 2.dp)) {
        Text(
            summary,
            color = MaterialTheme.colorScheme.error,
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
        if (detail != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { open = !open },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        stringResource(if (open) R.string.error_detail_hide else R.string.error_detail_show),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (open) {
                    TextButton(
                        onClick = { copyPrompt(context, "$summary\n$detail") },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.chat_copy), style = MaterialTheme.typography.labelSmall)
                    }
                }
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
    }
}

/** クラウドのエラー警告。文言とボタンは種別から決め、提供側の原文は「詳細」を開いたときだけ出す。 */
@Composable
private fun CloudErrorBlock(err: ApiError, hasProviderPage: Boolean, onAction: (CloudErrorAction) -> Unit) {
    val ui = cloudErrorUi(err.kind).let { u ->
        if (hasProviderPage) u else u.copy(actions = u.actions - CloudErrorAction.OpenProviderConsole)
    }
    var open by remember(err) { mutableStateOf(false) }
    val base = remember(err) { cloudErrorDetail(err) }
    // 時間切れは、接続 / 最初の応答 / 途中の無通信のどれかを詳細に出す
    val phase = err.timeoutPhase?.let { stringResource(timeoutPhaseRes(it), err.timeoutSec ?: 0) }
    val detail = listOfNotNull(phase, base).takeIf { it.isNotEmpty() }?.joinToString("\n")
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(stringResource(ui.messageRes), color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ui.actions.forEach { a ->
                TextButton(onClick = { onAction(a) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(
                        stringResource(
                            when (a) {
                                CloudErrorAction.ReEnterKey -> R.string.cloud_action_reenter_key
                                CloudErrorAction.OpenSettings -> R.string.cloud_action_open_settings
                                CloudErrorAction.OpenProviderConsole -> R.string.cloud_action_open_console
                                CloudErrorAction.Retry -> R.string.chat_retry
                                CloudErrorAction.ReselectModel -> R.string.cloud_action_reselect
                                CloudErrorAction.SwitchToLocal -> R.string.cloud_action_switch_local
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (detail != null) {
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(
                        stringResource(if (open) R.string.error_detail_hide else R.string.error_detail_show),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (open && detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 提供元のコンソール(請求ページの入口)をブラウザで開く。固定の請求 URL は持たない。 */
internal fun openProviderConsole(context: Context, type: ProviderType) {
    val url = CloudProviders.of(type).keyIssueUrl ?: return
    openUrl(context, url)
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.cloud_no_browser), Toast.LENGTH_SHORT).show()
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
    selection: ModelSelection?,
    integrations: List<CloudIntegration>,
    warnings: Map<String, ApiErrorKind>,
    openSignal: Int,
    onEditPrompt: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(openSignal) { if (openSignal > 0) expanded = true }
    val selected = models.firstOrNull { it.id == selectedId }
    val cloudSel = selection as? ModelSelection.Cloud
    val cloudName = cloudSel?.let { s -> integrations.firstOrNull { it.id == s.ref.integrationId }?.displayName }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { expanded = true }) {
                    val label = (
                        if (cloudSel != null) "☁ " + (cloudName ?: "") + " / " + cloudSel.ref.model
                        else selected?.name ?: stringResource(R.string.chat_select_model)
                        ) + " ▾"
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
                    // クラウド(API連携)。連携が無ければ、設定へ誘導する行だけ出す
                    DropdownMenuItem(
                        text = { Text("☁ " + stringResource(R.string.cloud_section), style = MaterialTheme.typography.labelMedium) },
                        onClick = {},
                        enabled = false,
                    )
                    if (integrations.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_none_hint), style = MaterialTheme.typography.bodySmall) },
                            onClick = { expanded = false },
                            enabled = false,
                        )
                    }
                    integrations.forEach { integ ->
                        val warn = warnings[integ.id] != null
                        integ.enabledModels.forEach { model ->
                            val isSel = cloudSel?.ref == CloudModelRef(integ.id, model)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text((if (isSel) "✓ " else "") + (if (warn) "⚠ " else "") + model)
                                        Text(
                                            integ.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = { expanded = false; vm.selectCloud(CloudModelRef(integ.id, model)) },
                            )
                        }
                    }
                    if (selection != null) {
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
    /** AI の回答の上に控えめに出す提供元の目印(クラウドは ☁ と名前、ローカルはモデル名)。 */
    label: String? = null,
) {
    // 思考過程は、回答が出始めたら畳んで、押すと再表示できる(状態は位置ごとに保持)
    val parsed = remember(m.content) { if (isUser) null else ThinkingParser.parse(m.content) }
    var showThinking by rememberSaveable { mutableStateOf(false) }
    val body = parsed?.answer ?: m.content

    if (isUser) {
        // ユーザーの発言: 右寄せの色つき吹き出し(コードブロック処理はしない)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            // 左に余白を残して、本文の長さに合わせた幅にする(短い発言は短い吹き出し)
            Spacer(Modifier.width(48.dp))
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).weight(1f, fill = false),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        body,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        return
    }

    // AI: 左寄せ。上に提供元の目印、思考は吹き出しなしの控えめな表示、最終回答だけを吹き出しにする
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
            )
        }
        if (parsed?.thinking != null) {
            // 推論(思考): 小さめ・薄い文字色・左に細い縦線。生成中は展開、完了後は折りたたむ(タップで開閉)
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val expanded = !parsed.thinkingDone || showThinking
            Row(Modifier.padding(start = 4.dp, bottom = 6.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.padding(start = 8.dp)) {
                    if (!parsed.thinkingDone) {
                        Text(stringResource(R.string.chat_thinking), style = MaterialTheme.typography.labelMedium, color = muted)
                    } else if (parsed.thinking.isNotEmpty()) {
                        Text(
                            stringResource(if (showThinking) R.string.chat_hide_thinking else R.string.chat_show_thinking),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showThinking = !showThinking }.padding(vertical = 2.dp),
                        )
                    }
                    if (expanded && parsed.thinking.isNotEmpty()) {
                        Text(parsed.thinking, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
        if (body.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    // 回答をセグメントへ分けて、コードブロックだけ CodeBlockView / 結果カードで出す
                    val segments = remember(body) { CodeBlockParser.parse(body) }
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        var blockIndex = 0
                        for (segment in segments) {
                            when (segment) {
                                is MessageSegment.Text -> MarkdownText(segment.text)
                                is MessageSegment.Code -> {
                                    // blockIndex は Code セグメントだけを数える(0 始まり)。key は会話内の位置 + コードのハッシュ
                                    val key = BlockKey(messageIndex, blockIndex, segment.code.hashCode())
                                    blockIndex++
                                    CodeBlockView(
                                        lang = segment.lang,
                                        code = segment.code,
                                        runButton = runButtonState(segment, jsEnabled, generating, jsState, m.origin),
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

/** 本文のスタイル(読みやすい大きさと、1.5 前後の行間)。フォントスケールに合わせて sp で伸びる。 */
private val BodyTextSize = 16.sp
private val BodyLineHeight = 24.sp

/** 最小限の Markdown(見出し・箇条書き・太字・インラインコード)で本文を出す。 */
@Composable
internal fun MarkdownText(text: String) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    fun inlines(list: List<MdInline>) = buildAnnotatedString {
        for (i in list) when (i) {
            is MdInline.Plain -> append(i.text)
            is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(i.text) }
            is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(i.text) }
        }
    }
    val base = MaterialTheme.typography.bodyLarge.copy(fontSize = BodyTextSize, lineHeight = BodyLineHeight)
    Column(Modifier.fillMaxWidth()) {
        for (b in blocks) when (b) {
            MdBlock.Blank -> Spacer(Modifier.height(8.dp))
            is MdBlock.Para -> Text(inlines(b.inlines), style = base)
            is MdBlock.Heading -> Text(
                inlines(b.inlines),
                style = base.copy(
                    fontSize = when (b.level) { 1 -> 22.sp; 2 -> 20.sp; 3 -> 18.sp; else -> 16.sp },
                    lineHeight = when (b.level) { 1 -> 30.sp; 2 -> 28.sp; 3 -> 26.sp; else -> 24.sp },
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            is MdBlock.Bullet -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                Text(b.marker, style = base, modifier = Modifier.width(24.dp))
                Text(inlines(b.inlines), style = base, modifier = Modifier.weight(1f))
            }
        }
    }
}

// コードブロックの「実行」ボタンの状態(設計書 6.1 の規則どおりの順で決める)
internal fun runButtonState(
    segment: MessageSegment.Code,
    jsEnabled: Boolean,
    generating: Boolean,
    jsState: JsRunState,
    origin: Origin = Origin.Local,
): RunButtonState {
    // クラウドの返答には「実行」を出さない(js-runtime の P7)。メッセージごとに判定する
    if (origin == Origin.Cloud) return RunButtonState.Hidden
    if (!jsEnabled) return RunButtonState.Hidden
    if (segment.lang !in setOf("js", "javascript")) return RunButtonState.Hidden
    // 生成中の最後の回答は、閉じが来ていないだけなので注記を出さない
    if (!segment.closed) return if (generating) RunButtonState.Hidden else RunButtonState.Unclosed
    if (segment.code.isBlank()) return RunButtonState.Hidden
    if (segment.code.length > JsLimits.MAX_CODE_CHARS) return RunButtonState.TooLong
    if (generating || jsState is JsRunState.Running) return RunButtonState.Disabled
    return RunButtonState.Enabled
}
