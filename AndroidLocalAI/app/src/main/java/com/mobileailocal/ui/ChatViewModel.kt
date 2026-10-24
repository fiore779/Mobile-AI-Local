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
)

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

    /** 導入済みモデル一覧、選択中の id、読み込み進捗(チャット画面のモデル選択用)。 */
    val models: StateFlow<List<ModelEntity>> =
        a.models.installed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedModelId: StateFlow<String?> = a.selectedModelId
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
        job = viewModelScope.launch {
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
            )
            // モデルへ渡す会話: 先頭にシステムプロンプト、過去の回答は思考過程を除いて回答だけ
            val baseSystem = _state.value.systemPrompt.ifBlank { Prompts.DEFAULT_SYSTEM }
            // 拡張機能(JavaScript 実行)が有効なら、使える API の短い説明を足す(設計書 4.6)。クラウドには足さない
            val system = Prompts.systemFor(baseSystem, appSettings.jsEnabled, cloud)
            val rawForModel = listOf(ChatMessage(Role.System, system)) +
                history.map { if (it.role == Role.Assistant) it.copy(content = ThinkingParser.answerOf(it.content)) else it }
            // クラウドは、空の返答を除き、同じ役割が続いたら連結してから送る
            val forModel = if (cloud) normalizeForCloud(rawForModel) else rawForModel

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
            var completed = false
            val sb = StringBuilder()
            val startedAt = System.currentTimeMillis()
            var pieces = 0
            var lastSaved = startedAt
            try {
                var limit = CLOUD_MAX_INPUT_CHARS
                var attempt = 0
                while (true) {
                    // クラウドは、送る前に文字数で古いやり取りを落とす(ローカルは従来どおりそのまま)
                    val (sendList, trimmed) = if (cloud) trimForCloud(forModel, limit) else forModel to false
                    if (trimmed != _state.value.trimmedNotice) _state.value = _state.value.copy(trimmedNotice = trimmed)
                    try {
                        engine.generate(sendList, params).collect { piece ->
                            sb.append(piece)
                            pieces++
                            _state.value = _state.value.copy(messages = history + ChatMessage(Role.Assistant, sb.toString(), origin))
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
                completed = true
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
                // 停止・失敗でも、そこまでの文章を保存する。
                // 取り消し済みの coroutine から withContext(NonCancellable + IO) で戻ると、戻り際に
                // JobCancellationException になって後始末が止まるため、保存は App の IO スコープに任せる
                val finalText = sb.toString()
                finalSave = a.ioScope.launch {
                    dao.updateMessageContent(aid, finalText)
                    dao.touch(cid, System.currentTimeMillis())
                }
                if (!cloud) a.deviceMonitor.recordGeneration(pieces, System.currentTimeMillis() - startedAt)
                InferenceService.stop(a)
                val thermalStop = arbiter.reason == StopReason.Thermal && !completed
                _state.value = _state.value.copy(
                    generating = false,
                    stopNotice = if (thermalStop) StopReason.Thermal else null,
                )
            }
        }
        return true
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
                _state.value = ChatUiState(
                    conversationId = conversationId,
                    messages = rows.map {
                        ChatMessage(
                            Role.entries.first { r -> r.name.equals(it.role, true) }, it.content,
                            if (it.origin == "cloud") Origin.Cloud else Origin.Local,
                        )
                    },
                    systemPrompt = conv?.systemPrompt.orEmpty(),
                )
            } finally {
                switching = false
            }
        }
    }

    fun newConversation() {
        cloudHistoryConfirmed = false
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
