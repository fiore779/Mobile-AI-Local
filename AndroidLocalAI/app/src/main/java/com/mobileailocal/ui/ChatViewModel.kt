package com.mobileailocal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobileailocal.App
import com.mobileailocal.InferenceService
import com.mobileailocal.R
import com.mobileailocal.data.localizedString
import com.mobileailocal.data.nativeErrorText
import com.mobileailocal.data.DeviceStatus
import com.mobileailocal.data.ConversationEntity
import com.mobileailocal.data.MessageEntity
import com.mobileailocal.data.ModelEntity
import com.mobileailocal.domain.ApiError
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.CLOUD_MAX_INPUT_CHARS
import com.mobileailocal.domain.CloudIntegration
import com.mobileailocal.domain.CloudModelRef
import com.mobileailocal.domain.CompressionBackoff
import com.mobileailocal.domain.ContextBudget
import com.mobileailocal.domain.InferenceEngine
import com.mobileailocal.domain.SummaryOutcome
import com.mobileailocal.domain.TokenEstimator
import com.mobileailocal.domain.ToolCall
import com.mobileailocal.domain.ToolCallParser
import com.mobileailocal.domain.ToolFind
import com.mobileailocal.domain.ToolOutcome
import com.mobileailocal.domain.ToolResult
import com.mobileailocal.domain.ToolResultFormat
import com.mobileailocal.domain.WorkspaceLimits
import com.mobileailocal.domain.WritePreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import com.mobileailocal.domain.planCompression
import com.mobileailocal.domain.summarizeAndValidate
import com.mobileailocal.data.AppSettings
import com.mobileailocal.domain.ModelSelection
import com.mobileailocal.domain.Origin
import com.mobileailocal.domain.encode
import com.mobileailocal.domain.halvedLimit
import com.mobileailocal.domain.trimForCloud
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.ensureActive
import com.mobileailocal.domain.normalizeForCloud
import com.mobileailocal.domain.BlockKey
import com.mobileailocal.domain.ChatMessage
import com.mobileailocal.domain.JsHandoff
import com.mobileailocal.domain.JsRunResult
import com.mobileailocal.domain.JsRunState
import com.mobileailocal.domain.Prompts
import com.mobileailocal.domain.Role
import com.mobileailocal.domain.StopArbiter
import com.mobileailocal.domain.StopReason
import com.mobileailocal.domain.ThermalPolicy
import com.mobileailocal.domain.ThinkingParser
import com.mobileailocal.domain.historyForRetry
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
    /** この会話のシステムプロンプト(空なら既定文を使う)。 */
    val systemPrompt: String = "",
    /** 直近の生成が止まった理由(チャットに注記として出す)。注記を出すのは Thermal のみ。 */
    val stopNotice: StopReason? = null,
    /** クラウドの失敗(分類つき)。画面が文言とボタンを決める。ローカルの失敗は error を使う。 */
    val cloudError: ApiError? = null,
    /** 最大トークン数で打ち切られた(設定で増やせる)。 */
    val maxTokensNotice: Boolean = false,
    /** 送信を断ったとき(同意の取り消しなど)、入力欄へ戻す本文。画面が読んだら consumeRejectedText() で消す。 */
    val rejectedText: String? = null,
    /** 生成(送信)を始めた時刻。応答待ちの経過秒の基準(画面の回転で数え直さないため、状態に持つ)。 */
    val sendStartedAt: Long = 0L,
    /** 会話の途中でクラウドへ切り替えた最初の送信の確認待ち(画面がダイアログを出す)。 */
    val pendingCloudConfirm: PendingSend? = null,
    /** クラウドへ送るとき、古いやり取りを送らなかった。 */
    val trimmedNotice: Boolean = false,
    /** 要約が覆う先頭からの発言数(区切り線の位置。0 なら区切りなし)。 */
    val summaryCount: Int = 0,
    /** 要約の本文(確認ダイアログ用)。 */
    val summaryText: String = "",
    /** 要約の生成中。 */
    val compressing: Boolean = false,
    /** 直近の圧縮の結果(次の送信で消える)。 */
    val compressNotice: CompressNotice? = null,
    /** 書き込みの承認待ち(画面がダイアログを出す)。 */
    val pendingToolApproval: ToolApproval? = null,
    /** 実行中の操作の表示。 */
    val toolStatus: ToolStatus? = null,
    /** ツールの往復が止まった理由。 */
    val toolStopNotice: ToolStopNotice? = null,
)

data class ToolApproval(val preview: WritePreview, val call: ToolCall.Write)

/** 実行中の操作(op は list / read / write)。 */
data class ToolStatus(val op: String, val path: String)

enum class ToolStopNotice { TurnLimit, FormatErrors, NoAccess }

/** 圧縮の結果の注記。 */
sealed interface CompressNotice {
    data class Done(val count: Int) : CompressNotice
    data object Failed : CompressNotice
}

/** 確認待ちの送信。retry が true なら、最後の発言での再生成。 */
data class PendingSend(val text: String, val retry: Boolean)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as App
    private val dao = a.db.chatDao()
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private var job: Job? = null
    /** 直近の生成の最終保存。切り替えの前に待つ。 */
    private var finalSave: Job? = null
    /** 会話の切り替え(履歴を開く・新しい会話)の途中は、送信を受け付けない(メインスレッドからだけ読み書きする)。 */
    private var switching = false
    /** 現在の生成の停止理由の判定(生成ごとに作る)。 */
    private var currentArbiter: StopArbiter? = null
    /** 要約に失敗したあとの抑止(会話を切り替えるとリセット)。 */
    private val compressionBackoff = CompressionBackoff()

    /** 導入済みモデル一覧、選択中の id、読み込み進捗(チャット画面のモデル選択用)。 */
    val models: StateFlow<List<ModelEntity>> =
        a.models.installed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedModelId: StateFlow<String?> = a.selectedModelId
    /** プロンプト処理(prefill)の進捗の割合。処理中でなければ null。端末内モデルだけ。 */
    val prefillPercent: StateFlow<Int?> get() = a.localEngine.prefillPercent
    val modelLoading: StateFlow<Float?> = a.modelLoading
    /** モデルを読み込めなかった理由(選び直すと消える)。 */
    val modelError: StateFlow<String?> = a.modelError

    /** 端末の状態(メモリ・発熱・生成速度)。画面が見えている間だけ2秒ごとに更新する。 */
    val deviceStatus: StateFlow<DeviceStatus> = flow {
        while (true) { emit(a.deviceMonitor.snapshot()); delay(2000) }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceStatus())

    fun selectModel(id: String?) = a.selectModel(id)

    // ---------------- クラウド(設計書 cloud-api) ----------------

    val selectedModel: StateFlow<ModelSelection?> = a.selectedModel
    val cloudWarnings: StateFlow<Map<String, ApiErrorKind>> = a.cloudWarnings

    /** 連携の一覧(モデルバーの「クラウド」区画用)。 */
    val cloudIntegrations: StateFlow<List<CloudIntegration>> =
        a.integrationsFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** クラウドのモデルを選ぶ(同意が済んでいなければ、先に確認ダイアログが出る)。上限・課金系の警告が残っていれば再度出す。 */
    fun selectCloud(ref: CloudModelRef) {
        a.cloudWarnings.value[ref.integrationId]?.let { kind ->
            _state.value = _state.value.copy(cloudError = ApiError(kind))
        }
        a.requestCloudSelect(ref)
    }

    private fun isCloud(): Boolean = a.selectedModel.value is ModelSelection.Cloud

    /** 会話の途中でクラウドへ切り替えたときの確認が済んだか(画面のメモリ上だけ。設計書 6.2-2)。 */
    private var cloudHistoryConfirmed = false

    init {
        // 往復の途中で作業フォルダの設定(有効／フォルダ)が変わったら、往復を止める
        viewModelScope.launch {
            a.settings.settings.map { it.workspaceEnabled }.distinctUntilChanged().drop(1).collect {
                if (toolsActiveSend && _state.value.generating) stop()
            }
        }
        viewModelScope.launch {
            a.selectedModel.drop(1).collect { sel ->
                cloudHistoryConfirmed = false
                _state.value = _state.value.copy(pendingCloudConfirm = null)
                // 開いている会話の「最後に使った選択」を更新する
                val cid = _state.value.conversationId
                if (cid != null) withContext(Dispatchers.IO) { dao.updateModelId(cid, sel?.encode()) }
            }
        }
    }

    /** これまでのローカルの会話がクラウドへ送られることの確認が必要か。 */
    private fun needsCloudHistoryConfirm(): Boolean =
        isCloud() && !cloudHistoryConfirmed && _state.value.messages.any { it.origin == Origin.Local }

    /** 確認ダイアログで「送る」。保留していた送信(または再生成)を続ける。 */
    fun confirmPendingSend() {
        val p = _state.value.pendingCloudConfirm ?: return
        cloudHistoryConfirmed = true
        _state.value = _state.value.copy(pendingCloudConfirm = null)
        if (p.retry) retry() else send(p.text)
    }

    fun consumeRejectedText() { _state.value = _state.value.copy(rejectedText = null) }

    fun cancelPendingSend() { _state.value = _state.value.copy(pendingCloudConfirm = null) }

    fun dismissCloudError() { _state.value = _state.value.copy(cloudError = null) }

    /** システムプロンプトを設定する。会話がまだ無ければ、最初の送信時に保存される。 */
    fun setSystemPrompt(text: String) {
        _state.value = _state.value.copy(systemPrompt = text)
        val cid = _state.value.conversationId ?: return
        viewModelScope.launch(Dispatchers.IO) { dao.updateSystemPrompt(cid, text) }
    }

    /** 送信する。受け付けたら true(拒否されたら false。画面は true のときだけ入力欄を消す)。 */
    fun send(text: String): Boolean = generate(text, retry = false)

    /** 最後のユーザー発言で答えを作り直す(直後の回答は置き換える)。受け付けたら true。 */
    fun retry(): Boolean {
        val s = _state.value
        if (s.generating || switching || s.conversationId == null) return false
        val history = historyForRetry(s.messages) ?: return false
        if (history.last().content.isBlank()) return false
        if (!a.activeEngine.isLoaded) {
            _state.value = s.copy(error = a.localizedString(R.string.chat_no_model_loaded))
            return false
        }
        if (!isCloud() && ThermalPolicy.shouldStop(a.deviceMonitor.currentThermalStatus())) {
            _state.value = s.copy(error = a.localizedString(R.string.chat_thermal_blocked))
            return false
        }
        // 会話の途中でクラウドへ切り替えたあとの最初の送信は、再生成でも確認する
        if (needsCloudHistoryConfirm()) {
            _state.value = s.copy(pendingCloudConfirm = PendingSend(history.last().content, retry = true))
            return false
        }
        // 回答が作り直されると同じ位置が別のコードになるので、結果カードは捨てる
        a.jsRunManager.cancelAndClear()
        return generate(history.last().content, retry = true)
    }

    private fun generate(text: String, retry: Boolean): Boolean {
        if (text.isBlank() || _state.value.generating || switching) return false
        // 会話の途中でクラウドへ切り替えたあとの最初の送信は、これまでの会話も送られるので確認する
        if (!retry && needsCloudHistoryConfirm()) {
            _state.value = _state.value.copy(pendingCloudConfirm = PendingSend(text, retry = false))
            return false
        }
        // モデル読み込み中は送れない(画面側でも送信ボタンを止めている)
        if (a.modelLoading.value != null) return false
        // モデルが無いときは、サービスも空のメッセージも作らずにエラーだけ出す
        if (!a.activeEngine.isLoaded) {
            _state.value = _state.value.copy(error = a.localizedString(R.string.chat_no_model_loaded))
            return false
        }
        // SEVERE 以上の間は、状態もDBも触らずに断る(クラウドは端末の発熱と関係ないので判定しない)
        val cloud = isCloud()
        if (!cloud && ThermalPolicy.shouldStop(a.deviceMonitor.currentThermalStatus())) {
            _state.value = _state.value.copy(error = a.localizedString(R.string.chat_thermal_blocked))
            return false
        }
        val arbiter = StopArbiter()
        currentArbiter = arbiter
        val engine = a.activeEngine
        val origin = if (cloud) Origin.Cloud else Origin.Local
        val originText = if (cloud) "cloud" else "local"
        val selection = a.selectedModel.value
        val integrationId = (selection as? ModelSelection.Cloud)?.ref?.integrationId
        // 送信を受け付けた時点で「生成中」にする(停止ボタンをすぐ出し、二重の送信も防ぐ)。
        // 準備の途中(同意の取り消し、DB の失敗など)で戻ったときは、完了時に元へ戻す。
        var ownsGenerating = false // true になったら、生成の finally が generating を戻す
        _state.value = _state.value.copy(generating = true)
        val started = viewModelScope.launch {
            val self = coroutineContext.job
            // 同意が取り消されていたら送らない(状態もDBも触らない)
            if (cloud && !a.cloudSendAllowed()) {
                _state.value = _state.value.copy(
                    error = a.localizedString(R.string.cloud_error_consent_required),
                    rejectedText = if (retry) null else text, // 入力欄を空にしたままにしない
                )
                return@launch
            }
            finalSave?.join()
            val appSettings = a.settings.settings.first()
            val params = appSettings.params
            val now = System.currentTimeMillis()
            val cid: Long
            val history: List<ChatMessage>
            val aid: Long
            var invalidSummary = false
            try {
                if (retry) {
                    cid = _state.value.conversationId ?: return@launch
                    history = historyForRetry(_state.value.messages) ?: return@launch
                    aid = withContext(Dispatchers.IO) {
                        val uid = dao.lastUserMessageId(cid) ?: return@withContext -1L
                        dao.deleteMessagesAfter(cid, uid)
                        dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = "", createdAt = now, origin = originText))
                    }
                    if (aid < 0) return@launch
                    if (_state.value.summaryCount > history.lastIndex) {
                        invalidSummary = true
                        withContext(Dispatchers.IO) { dao.clearSummary(cid) }
                    }
                } else {
                    cid = _state.value.conversationId ?: withContext(Dispatchers.IO) {
                        dao.insertConversation(
                            ConversationEntity(
                                title = text.take(30), systemPrompt = _state.value.systemPrompt,
                                modelId = selection?.encode(),
                                createdAt = now, updatedAt = now,
                            ),
                        )
                    }
                    history = _state.value.messages + ChatMessage(Role.User, text, origin)
                    withContext(Dispatchers.IO) { dao.updateModelId(cid, selection?.encode()) }
                    aid = withContext(Dispatchers.IO) {
                        dao.insertMessage(MessageEntity(conversationId = cid, role = "user", content = text, createdAt = now, origin = originText))
                        dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = "", createdAt = now, origin = originText))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
                return@launch
            }
            _state.value = _state.value.copy(
                conversationId = cid,
                messages = history + ChatMessage(Role.Assistant, "", origin),
                generating = true,
                sendStartedAt = System.currentTimeMillis(),
                error = null,
                cloudError = null,
                maxTokensNotice = false,
                trimmedNotice = false,
                stopNotice = null,
                compressNotice = null,
                toolStopNotice = null,
                toolStatus = null,
                pendingToolApproval = null,
                // 再試行で要約が覆う範囲より後ろを消すと不整合になるので、そのときは要約をやめる
                summaryCount = if (invalidSummary) 0 else _state.value.summaryCount,
                summaryText = if (invalidSummary) "" else _state.value.summaryText,
            )
            val baseSystem = _state.value.systemPrompt.ifBlank { Prompts.DEFAULT_SYSTEM }

            InferenceService.start(a) // 画面を閉じても生成が続くようにする
            // 発熱の監視(launch の子にしない)。SEVERE 以上になったら生成を止める
            val watcher = viewModelScope.launch {
                if (cloud) return@launch
                try {
                    a.deviceMonitor.thermalStatusUpdates().first { ThermalPolicy.shouldStop(it) }
                    if (arbiter.request(StopReason.Thermal)) self.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 監視が使えない端末では、停止しないだけ
                }
            }
            ownsGenerating = true
            var completed = false
            var sb = StringBuilder()
            var curAid = aid
            var curHistory = history
            val startedAt = System.currentTimeMillis()
            var pieces = 0
            // 作業フォルダのツールを使えるのは、端末内モデルで、フォルダが使える状態のときだけ(設計書 6.4)
            val toolsActive = !cloud && a.workspaceUsable(appSettings)
            toolsActiveSend = toolsActive
            var toolTurns = 0
            var formatErrors = 0
            var writeTotal = 0
            try {
                // 全体: 1 往復(runOneTurn)を、ツールの結果を返しながら繰り返す。ツールが無ければ 1 回で終わる
                while (true) {
                    val turn = runOneTurn(cid, curHistory, curAid, sb, baseSystem, appSettings, cloud, engine, origin, toolsActive)
                    pieces += turn.pieces
                    val find = turn.find
                    if (find is ToolFind.NoCall) {
                        completed = true
                        break
                    }
                    var resultText: String
                    if (find is ToolFind.Call) {
                        // 最初の完成したタグの後ろは捨てる(思考過程は残す)
                        val parsed = ThinkingParser.parse(sb.toString())
                        val cut = parsed.answer.take(find.endIndex)
                        val content = if (parsed.thinking != null && parsed.thinkingDone) "<think>${parsed.thinking}</think>$cut" else cut
                        sb.setLength(0)
                        sb.append(content)
                        withContext(Dispatchers.IO) { dao.updateMessageContent(curAid, content) }
                        _state.value = _state.value.copy(messages = curHistory + ChatMessage(Role.Assistant, content, origin))
                        if (toolTurns >= WorkspaceLimits.MAX_TOOL_TURNS) {
                            _state.value = _state.value.copy(toolStopNotice = ToolStopNotice.TurnLimit)
                            completed = true
                            break
                        }
                        toolTurns++
                        formatErrors = 0
                        val call = find.call
                        val outcome = executeTool(call, appSettings.params.contextLength, WorkspaceLimits.MAX_WRITE_BYTES_PER_SEND - writeTotal)
                        _state.value = _state.value.copy(toolStatus = null, pendingToolApproval = null)
                        if (outcome is ToolOutcome.Failed && outcome.code == "EACCES") {
                            _state.value = _state.value.copy(toolStopNotice = ToolStopNotice.NoAccess)
                            completed = true
                            break
                        }
                        if (call is ToolCall.Write && outcome is ToolOutcome.Ok) writeTotal += call.text.toByteArray(Charsets.UTF_8).size
                        resultText = ToolResult.format(call, outcome)
                    } else {
                        // 形式の誤り(閉じが無い・未知のタグなど)。2 回までは直し方を返し、続けば止める
                        formatErrors++
                        if (formatErrors > WorkspaceLimits.MAX_FORMAT_ERRORS) {
                            _state.value = _state.value.copy(toolStopNotice = ToolStopNotice.FormatErrors)
                            completed = true
                            break
                        }
                        if (toolTurns >= WorkspaceLimits.MAX_TOOL_TURNS) {
                            _state.value = _state.value.copy(toolStopNotice = ToolStopNotice.TurnLimit)
                            completed = true
                            break
                        }
                        toolTurns++
                        val writeTag = (find as? ToolFind.Incomplete)?.tag == "file_write"
                        resultText = ToolResult.formatError(
                            if (writeTag) "file too long or not closed; write a shorter file or split it, and end with </file_write>"
                            else "use exactly <file_list path=\"...\"/>, <file_read path=\"...\"/> or <file_write path=\"...\"> ... </file_write>",
                        )
                        withContext(Dispatchers.IO) { dao.updateMessageContent(curAid, sb.toString()) }
                    }
                    // 結果を user 行として保存し、続きの assistant 行を作って、もう一往復する
                    val assistantMsg = ChatMessage(Role.Assistant, sb.toString(), origin)
                    val nextAid = withContext(Dispatchers.IO) {
                        val t = System.currentTimeMillis()
                        dao.insertMessage(MessageEntity(conversationId = cid, role = "user", content = resultText, createdAt = t, origin = "local"))
                        dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = "", createdAt = t, origin = originText))
                    }
                    curHistory = curHistory + assistantMsg + ChatMessage(Role.User, resultText, Origin.Local)
                    curAid = nextAid
                    sb = StringBuilder()
                    _state.value = _state.value.copy(messages = curHistory + ChatMessage(Role.Assistant, "", origin))
                }
                if (integrationId != null) a.markCloudWarning(integrationId, null)
                if (cloud && a.remoteEngine.truncated) _state.value = _state.value.copy(maxTokensNotice = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // 停止ボタンなどで取り消された後の失敗は、警告にしない
                coroutineContext.ensureActive()
                if (integrationId != null) a.markCloudWarning(integrationId, e.kind)
                _state.value = _state.value.copy(cloudError = e)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = a.nativeErrorText(e.message))
            } finally {
                watcher.cancel()
                approval?.complete(false)
                toolsActiveSend = false
                // 停止・失敗でも、そこまでの文章を保存する。
                // 取り消し済みの coroutine から withContext(NonCancellable + IO) で戻ると、戻り際に
                // JobCancellationException になって後始末が止まるため、保存は App の IO スコープに任せる
                val finalText = sb.toString()
                val finalAid = curAid
                finalSave = a.ioScope.launch {
                    dao.updateMessageContent(finalAid, finalText)
                    dao.touch(cid, System.currentTimeMillis())
                }
                if (!cloud) a.deviceMonitor.recordGeneration(pieces, System.currentTimeMillis() - startedAt)
                InferenceService.stop(a)
                val thermalStop = arbiter.reason == StopReason.Thermal && !completed
                _state.value = _state.value.copy(
                    generating = false,
                    compressing = false,
                    toolStatus = null,
                    pendingToolApproval = null,
                    stopNotice = if (thermalStop) StopReason.Thermal else null,
                )
            }
        }
        job = started
        started.invokeOnCompletion { if (!ownsGenerating) _state.value = _state.value.copy(generating = false) }
        return true
    }

    /** 1 往復の結果。find はツールのタグの検出結果(ツールが無効なら常に NoCall)。 */
    private class TurnResult(val find: ToolFind, val pieces: Int)

    /**
     * 1 往復: 圧縮 → 送るメッセージの組み立て → 生成。出力は sb に積み、画面と DB(約 2 秒ごと)へ反映する。
     * ツールが有効なら、最初の完成したタグが出た時点で生成を打ち切る(設計書 6.4)。
     */
    private suspend fun runOneTurn(
        cid: Long, history: List<ChatMessage>, aid: Long, sb: StringBuilder, baseSystem: String,
        settings: AppSettings, cloud: Boolean, engine: InferenceEngine, origin: Origin, toolsActive: Boolean,
    ): TurnResult {
        // 古い部分が多ければ要約に置き換える(失敗しても主要な生成は続ける。設計書 6.1〜6.3)
        maybeCompress(cid, history, baseSystem, settings, cloud, engine, toolsActive)
        // モデルへ渡す会話(要約で変わりうるので、圧縮のあとに組み立てる)
        val forModel = buildSendList(history, baseSystem, settings.jsEnabled, toolsActive, cloud)
        var limit = CLOUD_MAX_INPUT_CHARS
        var attempt = 0
        var pieces = 0
        var lastSaved = System.currentTimeMillis()
        var found: ToolFind.Call? = null
        while (true) {
            // クラウドは、送る前に文字数で古いやり取りを落とす(ローカルは従来どおりそのまま)
            val (sendList, trimmed) = if (cloud) trimForCloud(forModel, limit) else forModel to false
            if (trimmed != _state.value.trimmedNotice) _state.value = _state.value.copy(trimmedNotice = trimmed)
            try {
                engine.generate(sendList, settings.params)
                    .transformWhile { piece -> emit(piece); found == null } // タグが完成したら止める
                    .collect { piece ->
                        sb.append(piece)
                        pieces++
                        _state.value = _state.value.copy(messages = history + ChatMessage(Role.Assistant, sb.toString(), origin))
                        if (toolsActive && '>' in piece && sb.contains("<file_")) {
                            val answer = ThinkingParser.answerOf(sb.toString())
                            if (ToolCallParser.mayComplete(answer, piece)) {
                                (ToolCallParser.find(answer) as? ToolFind.Call)?.let { found = it }
                            }
                        }
                        // 強制終了されても途中経過が残るように、約2秒ごとに保存する
                        val t = System.currentTimeMillis()
                        if (t - lastSaved >= 2000) {
                            lastSaved = t
                            val partial = sb.toString()
                            // その場で順番に保存する(投げっぱなしにしないので、最後の保存が必ず最後に書かれる)
                            withContext(Dispatchers.IO) { dao.updateMessageContent(aid, partial) }
                        }
                    }
                break
            } catch (e: ApiError) {
                // 入力が長すぎると言われたら、上限を半分にして 1 回だけ自動で送り直す(文字を受け取る前に限る)
                if (cloud && e.kind == ApiErrorKind.ContextTooLong && sb.isEmpty() && attempt == 0) {
                    attempt++
                    limit = halvedLimit(limit)
                    continue
                }
                throw e
            }
        }
        val find: ToolFind = found
            ?: if (toolsActive) ToolCallParser.find(ThinkingParser.answerOf(sb.toString())) else ToolFind.NoCall
        return TurnResult(find, pieces)
    }

    /** 承認待ちの書き込み(あれば)。許可・拒否・時間切れ・停止のどれでも completes する。 */
    private var approval: CompletableDeferred<Boolean>? = null
    /** この送信でツールが有効か(設定の変化で往復を止める判定用)。 */
    private var toolsActiveSend = false

    /**
     * ツールを 1 回実行する(設計書 6.4 の 3〜6)。書き込みは WorkspaceToolRunner.executeApproved だけを通り、
     * 承認(CompletableDeferred の結果)がなければ書かない。承認の待ちは APPROVAL_TIMEOUT_MS で拒否になる。
     */
    private suspend fun executeTool(call: ToolCall, contextLength: Int, remainingWriteBytes: Int): ToolOutcome {
        val runner = a.workspaceRunner()
        val path = ToolResult.pathOf(call)
        val op = ToolResult.opOf(call)
        return when (call) {
            is ToolCall.Write -> runner.executeApproved(
                call,
                approve = { pv ->
                    val d = CompletableDeferred<Boolean>()
                    approval = d
                    _state.value = _state.value.copy(pendingToolApproval = ToolApproval(pv, call))
                    val allowed = try {
                        withTimeoutOrNull(WorkspaceLimits.APPROVAL_TIMEOUT_MS) { d.await() } ?: false
                    } finally {
                        approval = null
                        _state.value = _state.value.copy(pendingToolApproval = null)
                    }
                    if (allowed) _state.value = _state.value.copy(toolStatus = ToolStatus(op, path))
                    allowed
                },
                remainingSendBytes = remainingWriteBytes,
            )
            else -> {
                _state.value = _state.value.copy(toolStatus = ToolStatus(op, path))
                runner.execute(call, contextLength)
            }
        }
    }

    /** 書き込みの承認ダイアログの結果。 */
    fun approveTool(allow: Boolean) { approval?.complete(allow) }

    /**
     * モデルへ渡す会話を組み立てる: 先頭にシステム文(+ 要約)、要約が覆う分を除いた履歴、過去の回答は思考過程を除いて回答だけ。
     * 要約の状態は _state から読む(圧縮のあとにもう一度呼ぶ)。
     */
    private fun buildSendList(history: List<ChatMessage>, baseSystem: String, jsEnabled: Boolean, workspaceOn: Boolean, cloud: Boolean): List<ChatMessage> {
        val s = _state.value
        // 拡張機能(JavaScript 実行)が有効なら、使える API の短い説明を足す(設計書 4.6)。クラウドには足さない
        val system = Prompts.systemFor(baseSystem, jsEnabled, workspaceOn, s.summaryText, cloud)
        val raw = listOf(ChatMessage(Role.System, system)) +
            history.drop(s.summaryCount).map { if (it.role == Role.Assistant) it.copy(content = ThinkingParser.answerOf(it.content)) else it }
        // クラウドは、過去のツール結果の本文を省き(ファイルの内容を送らない)、空の返答を除き、同じ役割が続いたら連結してから送る
        return if (cloud) normalizeForCloud(ToolResultFormat.forCloud(raw)) else raw
    }

    /**
     * 自動圧縮(設計書 6.2)。古い発言を要約に置き換えて Room と状態へ保存する。失敗したら注記だけ出して戻る。
     * CancellationException は再送出する(何も保存しない)。
     */
    private suspend fun maybeCompress(
        cid: Long, history: List<ChatMessage>, baseSystem: String,
        settings: AppSettings, cloud: Boolean, engine: InferenceEngine, workspaceOn: Boolean,
    ) {
        if (!settings.autoCompress) return
        if (compressionBackoff.shouldSkip(history.size)) return
        val params = settings.params
        val limit = if (cloud) 0 else ContextBudget.localLimit(params.contextLength, params.maxTokens)
        if (!cloud && limit < ContextBudget.MIN_LOCAL_LIMIT) return
        val maxPasses = if (cloud) ContextBudget.MAX_PASSES_CLOUD else ContextBudget.MAX_PASSES_LOCAL
        val sizeOf: (ChatMessage) -> Int = if (cloud) { m -> m.content.length } else { m -> TokenEstimator.estimate(m) }
        val sizeOfText: (String) -> Int = if (cloud) { t -> t.length } else { t -> TokenEstimator.estimate(t) }
        val trigger = if (cloud) ContextBudget.CLOUD_TRIGGER_CHARS else ContextBudget.localTrigger(limit)
        val keep = if (cloud) ContextBudget.CLOUD_KEEP_CHARS else ContextBudget.localKeep(limit)
        val summaryMax = if (cloud) ContextBudget.CLOUD_SUMMARY_MAX_TOKENS else ContextBudget.localSummaryMaxTokens(limit)
        val lineCap = if (cloud) ContextBudget.CLOUD_LINE_CAP_CHARS else ContextBudget.LOCAL_LINE_CAP_CHARS
        val maxSummarySize = if (cloud) ContextBudget.CLOUD_SUMMARY_MAX_CHARS else (limit * ContextBudget.SUMMARY_MAX_RATIO).toInt()
        val mapped = history.map { if (it.role == Role.Assistant) it.copy(content = ThinkingParser.answerOf(it.content)) else it }
            .let { if (cloud) ToolResultFormat.forCloud(it) else it }
        repeat(maxPasses) {
            val s = _state.value
            val system = Prompts.systemFor(baseSystem, settings.jsEnabled, workspaceOn, s.summaryText, cloud)
            val fixedSize = sizeOfText(system)
            val inputBudget = if (cloud) ContextBudget.CLOUD_TRIGGER_CHARS - s.summaryText.length
            else params.contextLength - summaryMax - TokenEstimator.estimate(Prompts.SUMMARIZE_SYSTEM) - 100 -
                TokenEstimator.estimate(s.summaryText)
            val plan = planCompression(mapped, s.summaryCount, fixedSize, sizeOf, trigger, keep, inputBudget.coerceAtLeast(0))
                ?: return
            _state.value = s.copy(compressing = true)
            // CancellationException は summarizeAndValidate が再送出する(何も保存せず、通常の停止の流れに任せる)
            val outcome = summarizeAndValidate(
                messages = Prompts.summarizeMessages(s.summaryText, plan.toSummarize, lineCap),
                maxSummarySize = maxSummarySize,
                replacedSize = sizeOfText(s.summaryText) + plan.toSummarize.sumOf(sizeOf), // 前の要約も置き換えられる
                sizeOfText = sizeOfText,
            ) { msgs ->
                val sb = StringBuilder()
                engine.generate(msgs, params.copy(maxTokens = summaryMax, temperature = 0.2f)).collect { sb.append(it) }
                sb.toString()
            }
            when (outcome) {
                is SummaryOutcome.Done -> {
                    withContext(Dispatchers.IO) { dao.updateSummary(cid, outcome.summary, plan.newSummaryCount) }
                    compressionBackoff.reset()
                    _state.value = _state.value.copy(
                        summaryText = outcome.summary, summaryCount = plan.newSummaryCount,
                        compressing = false, compressNotice = CompressNotice.Done(plan.newSummaryCount),
                    )
                }
                SummaryOutcome.Failed -> {
                    compressionBackoff.markFailed(history.size)
                    _state.value = _state.value.copy(compressing = false, compressNotice = CompressNotice.Failed)
                    return
                }
            }
        }
    }

    /** 要約をやめる(区切り線の確認ダイアログから)。以後は要約なしで送り、必要ならまた自動で作られる。 */
    fun clearSummary() {
        val s = _state.value
        val cid = s.conversationId ?: return
        if (s.generating || switching) return
        _state.value = s.copy(summaryText = "", summaryCount = 0, compressNotice = null)
        viewModelScope.launch(Dispatchers.IO) { dao.clearSummary(cid) }
    }

    fun stop() {
        currentArbiter?.request(StopReason.User)
        job?.cancel()
    }

    // ---------------- JavaScript 実行(拡張機能。設計書 6) ----------------

    /** 拡張機能が有効か。無効のときは「実行」ボタンを出さない。 */
    val jsEnabled: StateFlow<Boolean> = a.settings.settings.map { it.jsEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 実行の状態(App が持つので、画面の再作成や背景化をまたいで残る)。 */
    val jsState: StateFlow<JsRunState> = a.jsRunManager.state


    /** 確認ダイアログで「実行」が押されたコードを実行する。受け付けたら true。 */
    fun runJs(key: BlockKey, code: String): Boolean = a.jsRunManager.run(key, code)
    fun cancelJs() = a.jsRunManager.cancel()
    fun dismissJs() = a.jsRunManager.dismiss()

    /** 「AI に渡す」用の本文。入力欄に入れるだけで、送信はしない(決定 2026-10-23)。second は切り詰めがあったか。 */
    suspend fun handoffText(result: JsRunResult): Pair<String, Boolean> {
        val budget = JsHandoff.budgetChars(a.settings.settings.first().params.contextLength)
        return JsHandoff.format(result, budget)
    }

    /** 履歴から会話を開く。生成の後始末(最終保存・サービス停止)が終わってから切り替える。 */
    fun open(conversationId: Long) {
        cloudHistoryConfirmed = false
        compressionBackoff.reset()
        switching = true // 切り替えの途中に send されて、開いた会話を上書きしないようにする
        a.jsRunManager.cancelAndClear() // 実行中の JavaScript と結果カードは会話に属さないので捨てる
        val running = job
        running?.cancel()
        viewModelScope.launch {
            try {
                running?.join()
                finalSave?.join()
                val (conv, rows) = withContext(Dispatchers.IO) {
                    dao.conversation(conversationId) to dao.messages(conversationId).first()
                }
                // 行数より多い summaryCount は不整合なので捨てる
                val validSummary = conv != null && conv.summaryCount in 1..rows.size && conv.summaryText.isNotBlank()
                if (conv != null && !validSummary && (conv.summaryCount != 0 || conv.summaryText.isNotEmpty())) {
                    withContext(Dispatchers.IO) { dao.clearSummary(conversationId) }
                }
                _state.value = ChatUiState(
                    conversationId = conversationId,
                    messages = rows.map {
                        ChatMessage(
                            Role.entries.first { r -> r.name.equals(it.role, true) }, it.content,
                            if (it.origin == "cloud") Origin.Cloud else Origin.Local,
                        )
                    },
                    systemPrompt = conv?.systemPrompt.orEmpty(),
                    summaryText = if (validSummary) conv?.summaryText.orEmpty() else "",
                    summaryCount = if (validSummary) conv?.summaryCount ?: 0 else 0,
                )
            } finally {
                switching = false
            }
        }
    }

    fun newConversation() {
        cloudHistoryConfirmed = false
        compressionBackoff.reset()
        switching = true
        a.jsRunManager.cancelAndClear()
        val running = job
        running?.cancel()
        viewModelScope.launch {
            try {
                running?.join()
                _state.value = ChatUiState()
            } finally {
                switching = false
            }
        }
    }
}
