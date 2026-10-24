package com.mobileailocal

import android.app.ActivityManager
import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.mobileailocal.data.AccountError
import com.mobileailocal.data.AccountRepository
import com.mobileailocal.data.CloudSync
import com.mobileailocal.data.FirestoreAccountStore
import com.mobileailocal.data.SIGNED_OUT_UID
import com.mobileailocal.domain.CloudIntegration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.mobileailocal.data.CloudConsent
import com.mobileailocal.data.CloudHttp
import com.mobileailocal.data.CloudIntegrationRepository
import com.mobileailocal.data.CloudKeyStore
import com.mobileailocal.data.LOCAL_UID
import com.mobileailocal.data.MIGRATION_1_2
import com.mobileailocal.data.RepositoryCredentials
import com.mobileailocal.domain.ApiErrorKind
import com.mobileailocal.domain.CloudInputs
import com.mobileailocal.domain.CloudModelRef
import com.mobileailocal.domain.ModelSelection
import com.mobileailocal.domain.ProviderType
import com.mobileailocal.domain.RemoteApiEngine
import com.mobileailocal.domain.decodeModelSelection
import com.mobileailocal.domain.encode
import kotlinx.coroutines.flow.update
import com.mobileailocal.data.localizedString
import com.mobileailocal.data.nativeErrorText
import com.mobileailocal.domain.NativeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import androidx.room.Room
import com.mobileailocal.data.AppDatabase
import com.mobileailocal.data.DeviceMonitor
import com.mobileailocal.data.DownloadTracker
import com.mobileailocal.data.JsRunManager
import com.mobileailocal.data.JsRunnerClient
import com.mobileailocal.data.JsWorkspace
import com.mobileailocal.data.ModelRepository
import java.io.File
import com.mobileailocal.data.SettingsRepository
import com.mobileailocal.domain.LlamaCppEngine
import com.mobileailocal.domain.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** reasoning_effort に送ってよい値(Gemini の OpenAI 互換の公式ページで確認した minimal / low / medium / high)。 */
val REASONING_EFFORTS = listOf("minimal", "low", "medium", "high")

/** 依存の置き場。DI ライブラリは入れず、ここで手動で組み立てる。 */
class App : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var settings: SettingsRepository; private set
    lateinit var localEngine: InferenceEngine; private set
    lateinit var remoteEngine: RemoteApiEngine; private set
    lateinit var cloudKeys: CloudKeyStore; private set
    lateinit var cloudRepo: CloudIntegrationRepository; private set
    lateinit var cloudConsent: CloudConsent; private set
    lateinit var accounts: AccountRepository; private set
    lateinit var cloudSync: CloudSync; private set
    lateinit var models: ModelRepository; private set
    lateinit var downloads: DownloadTracker; private set
    lateinit var deviceMonitor: DeviceMonitor; private set
    lateinit var jsRunManager: JsRunManager; private set

    // 想定外の例外でアプリごと落ちないようにする(ログに残す)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("App", "uncaught in app scope", e) },
    )
    private var selectJob: Job? = null
    private val selectSeq = java.util.concurrent.atomic.AtomicInteger()

    /** 画面(ViewModel)より長く生きる IO 用スコープ。生成の後始末の保存に使う。 */
    val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e("App", "uncaught in io scope", e) },
    )
    private val _selectedModelId = MutableStateFlow<String?>(null)
    private val _modelLoading = MutableStateFlow<Float?>(null)
    private val _modelError = MutableStateFlow<String?>(null)

    private val _selectedModel = MutableStateFlow<ModelSelection?>(null)

    /** 選択中のモデル(ローカルまたはクラウド)。未選択は null。 */
    val selectedModel: StateFlow<ModelSelection?> = _selectedModel

    /** 今の選択に対応するエンジン。 */
    val activeEngine: InferenceEngine
        get() = if (_selectedModel.value is ModelSelection.Cloud) remoteEngine else localEngine

    private val _cloudWarnings = MutableStateFlow<Map<String, ApiErrorKind>>(emptyMap())

    /** 上限・課金系のエラーを受けた連携(このプロセスの間だけ。永続化しない。設計書 6.3-4)。 */
    val cloudWarnings: StateFlow<Map<String, ApiErrorKind>> = _cloudWarnings

    fun markCloudWarning(integrationId: String, kind: ApiErrorKind?) {
        _cloudWarnings.update { m ->
            val bad = kind == ApiErrorKind.BillingRequired || kind == ApiErrorKind.SpendLimit || kind == ApiErrorKind.QuotaExceeded
            if (bad) m + (integrationId to kind!!) else m - integrationId
        }
    }

    /** 同意がまだのクラウドモデルを選ぼうとしたとき、確認ダイアログに出す内容。 */
    data class PendingConsent(
        val ref: CloudModelRef,
        val providerType: ProviderType,
        val displayName: String,
        /** 同意の記録の単位(カスタムは接続先ホスト)。 */
        val scope: String,
    )

    // 連続して選ばれたとき、最後の要求だけを有効にする
    private val cloudReqSeq = java.util.concurrent.atomic.AtomicInteger()

    private val _pendingConsent = MutableStateFlow<PendingConsent?>(null)
    val pendingConsent: StateFlow<PendingConsent?> = _pendingConsent

    /** クラウドのモデルを選ぶ入口。同意が済んでいなければダイアログを出し、済んでいれば選ぶ。 */
    fun requestCloudSelect(ref: CloudModelRef) {
        val seq = cloudReqSeq.incrementAndGet()
        _pendingConsent.value = null
        scope.launch {
            val integ = cloudRepo.get(uid(), ref.integrationId)
            val consentScope = integ?.let { CloudInputs.consentScope(it.type, it.baseUrl) }.orEmpty()
            val accepted = integ != null && cloudConsent.isAccepted(uid(), integ.type, consentScope)
            // 待っている間に、さらに新しい選択(クラウドでもローカルでも)が来ていたら、そちらに任せる
            if (cloudReqSeq.get() != seq) return@launch
            if (integ == null) { _modelError.value = localizedString(R.string.cloud_error_integration_missing); return@launch }
            if (accepted) selectCloudModel(ref)
            else _pendingConsent.value = PendingConsent(ref, integ.type, integ.displayName, consentScope)
        }
    }

    /** 同意を取り消す。その連携を選択中なら未選択に戻す(次に使うときは再度確認する)。 */
    fun revokeCloudConsent(type: ProviderType, consentScope: String) {
        scope.launch {
            cloudConsent.revoke(uid(), type, consentScope)
            val cur = _selectedModel.value
            if (cur is ModelSelection.Cloud) {
                val integ = cloudRepo.get(uid(), cur.ref.integrationId)
                if (integ != null && integ.type == type && CloudInputs.consentScope(integ.type, integ.baseUrl) == consentScope) {
                    selectModel(null)
                }
            }
        }
    }

    /** 送信の直前に、選択中のクラウドへの同意が有効かを確かめる(取り消し後の送信を防ぐ)。 */
    suspend fun cloudSendAllowed(): Boolean {
        val cur = _selectedModel.value as? ModelSelection.Cloud ?: return true
        val integ = cloudRepo.get(uid(), cur.ref.integrationId) ?: return false
        return cloudConsent.isAccepted(uid(), integ.type, CloudInputs.consentScope(integ.type, integ.baseUrl))
    }

    fun acceptPendingConsent() {
        val p = _pendingConsent.value ?: return
        _pendingConsent.value = null
        scope.launch {
            cloudConsent.accept(uid(), p.providerType, p.scope)
            selectCloudModel(p.ref)
        }
    }

    fun declinePendingConsent() { _pendingConsent.value = null }

    /**
     * クラウドのモデルを選ぶ。ローカルのモデルはアンロードする。キーが無ければ選ばずにエラーを出す。
     * 通信はしない。
     */
    @Synchronized
    fun selectCloudModel(ref: CloudModelRef) {
        _modelError.value = null
        val previous = selectJob
        val mine = selectSeq.incrementAndGet()
        selectJob = scope.launch {
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            if (selectSeq.get() != mine) return@launch
            val integ = cloudRepo.get(uid(), ref.integrationId)
            // キーが要る種別でキーが無いときは、今の選択を変えずにエラーだけ出す(キー無しのカスタムは選べる)
            val keyOk = integ != null &&
                (integ.type == ProviderType.OpenAiCompatCustom || cloudKeys.has(uid(), ref.integrationId))
            if (integ == null || !keyOk) {
                if (selectSeq.get() == mine) {
                    _modelError.value = localizedString(
                        if (integ == null) R.string.cloud_error_integration_missing else R.string.cloud_error_key_missing,
                    )
                }
                return@launch
            }
            localEngine.unload()
            _modelLoading.value = null
            _selectedModelId.value = null
            remoteEngine.current = ref
            _selectedModel.value = ModelSelection.Cloud(ref)
            settings.setDefaultModel(ModelSelection.Cloud(ref).encode())
            cloudRepo.upsert(uid(), integ.copy(lastModel = ref.model))
        }
    }

    /** 現在のアカウントの uid。サインアウト中は空文字(この値の行・キーは無いので、何も見つからない)。 */
    fun uid(): String = accounts.uid.value ?: SIGNED_OUT_UID

    /** 現在のアカウントの連携の一覧(アカウントが変わると切り替わる。サインアウト中は空)。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun integrationsFlow(): Flow<List<CloudIntegration>> =
        accounts.uid.flatMapLatest { u -> if (u == null) flowOf(emptyList()) else cloudRepo.observe(u) }

    /** 端末とアカウントの連携メタデータを合わせる(失敗・オフラインは黙って次回に回す)。 */
    fun requestSync() {
        val u = accounts.uid.value ?: return
        ioScope.launch { cloudSync.sync(u) }
    }

    /**
     * サインイン導入前に端末内(uid = "local")へ作った連携とキーは、アカウントへ取り込まず片付ける
     * (設計書 O11: 公開前のデータなので引き継がない)。
     */
    private suspend fun discardLegacyLocalData() {
        val legacy = cloudRepo.allForSync(LOCAL_UID)
        if (legacy.isEmpty()) return
        cloudKeys.deleteAllFor(LOCAL_UID)
        cloudRepo.deleteAll(LOCAL_UID)
    }

    /** 連携を削除する。選択中なら未選択にし、キーを消し、行は削除待ちにしてサーバーへ伝える(会話のメッセージは残す)。 */
    fun deleteIntegration(id: String) {
        scope.launch {
            val u = uid()
            val cur = _selectedModel.value
            if (cur is ModelSelection.Cloud && cur.ref.integrationId == id) selectModel(null)
            cloudKeys.delete(u, id)
            cloudRepo.markDeleted(u, id)
            markCloudWarning(id, null)
            // オフラインなら削除待ちのまま残り、次の同期で伝わる(一覧には出ない)
            if (accounts.uid.value != null) cloudSync.sync(u)
        }
    }

    /**
     * アカウントと、サーバー上の連携メタデータ、端末内のこのアカウントの連携・キーを削除する(Play のアカウント削除要件)。
     * 失敗は AccountError(オフラインは Network、直近のサインインが必要なら RecentLoginRequired)。
     */
    suspend fun deleteAccountAndData() {
        val u = accounts.uid.value ?: return
        try {
            cloudSync.deleteRemoteAll(u)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AccountError.Network
        }
        if (_selectedModel.value is ModelSelection.Cloud) selectModel(null)
        withContext(Dispatchers.IO) {
            cloudKeys.deleteAllFor(u)
            cloudRepo.deleteAll(u)
        }
        accounts.deleteAccount()
    }

    /** チャットで使うモデルの id(未選択は null)。 */
    val selectedModelId: StateFlow<String?> = _selectedModelId

    /** モデルを読み込めなかったときの理由。読み込みを始めると null に戻る。 */
    val modelError: StateFlow<String?> = _modelError

    /** モデル読み込み中の進捗(0..1)。読み込み中でなければ null。 */
    val modelLoading: StateFlow<Float?> = _modelLoading

    override fun onCreate() {
        super.onCreate()
        // :runner プロセス(JavaScript 実行)では、Room・llama.cpp・ダウンロードなどを何も作らない
        if (Application.getProcessName() != packageName) return
        db = Room.databaseBuilder(this, AppDatabase::class.java, "app.db")
            .addMigrations(MIGRATION_1_2) // fallbackToDestructiveMigration は使わない(会話が消える)
            .build()
        settings = SettingsRepository(this)
        localEngine = LlamaCppEngine()
        cloudKeys = CloudKeyStore(this)
        cloudRepo = CloudIntegrationRepository(db.cloudIntegrationDao())
        cloudConsent = CloudConsent(this)
        // Firebase は自動初期化を切ってある(マニフェスト)。メインプロセスでだけ、ここで初期化する
        FirebaseApp.initializeApp(this)
        accounts = AccountRepository(this, ioScope)
        cloudSync = CloudSync(cloudRepo, FirestoreAccountStore(FirebaseFirestore.getInstance()), cloudKeys)
        remoteEngine = RemoteApiEngine(RepositoryCredentials(cloudRepo, cloudKeys) { uid() }, CloudHttp())
        // Gemini の推論量(設定)。空なら送らない
        val effortState = settings.settings.map { it.geminiReasoningEffort }.stateIn(ioScope, SharingStarted.Eagerly, "")
        remoteEngine.reasoningEffort = { effortState.value.takeIf { it in REASONING_EFFORTS } }
        models = ModelRepository(this, db.modelDao())
        downloads = DownloadTracker(this, models, settings)
        deviceMonitor = DeviceMonitor(this)
        // 実行の受け付け可否に使う最新の設定値(実行のたびに DataStore を同期で読まないため)
        val jsEnabledState = settings.settings.map { it.jsEnabled }.stateIn(ioScope, SharingStarted.Eagerly, false)
        jsRunManager = JsRunManager(
            workspace = JsWorkspace(File(filesDir, "js-workspace")),
            executor = JsRunnerClient(this, ioScope),
            jsEnabled = { jsEnabledState.value },
            scope = ioScope,
            freeMemoryMb = {
                val mi = ActivityManager.MemoryInfo()
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
                // 空きを取れない場合は実行を許可する(拒否する根拠が無い)
                if (mi.availMem > 0) mi.availMem / (1024 * 1024) else Long.MAX_VALUE
            },
        )
        // コンテキスト長・スレッド数は読み込み時に決まるので、設定が変わったらモデルを読み直す
        scope.launch {
            settings.settings.map { it.params.contextLength to it.params.threads }
                .distinctUntilChanged().drop(1).collect {
                    val id = _selectedModelId.value
                    if (id != null && localEngine.isLoaded) selectModel(id)
                }
        }
        // アカウントの変化(サインイン・サインアウト・切り替え)に追従する
        scope.launch {
            var previous: String? = accounts.uid.value
            accounts.uid.collect { now ->
                if (now != previous) {
                    // 選択中のクラウドは、アカウントが変わったら未選択に戻す(別アカウントの連携は使わない)
                    if (_selectedModel.value is ModelSelection.Cloud) selectModel(null)
                    previous = now
                }
                if (now != null) {
                    withContext(Dispatchers.IO) { discardLegacyLocalData() }
                    cloudSync.sync(now)
                }
            }
        }
        // 前回選んだモデルを復元する(ファイルが消えていれば未選択に戻す)
        scope.launch {
            val saved = settings.settings.first().defaultModelId
            when (val sel = decodeModelSelection(saved)) {
                is ModelSelection.Cloud -> {
                    // 連携とキーが残っていれば復元する。通信はしない
                    val integ = cloudRepo.get(uid(), sel.ref.integrationId)
                    // 同意が有効で、キーが要る種別ならキーが残っているときだけ復元する
                    val ok = integ != null &&
                        (integ.type == ProviderType.OpenAiCompatCustom || cloudKeys.has(uid(), integ.id)) &&
                        cloudConsent.isAccepted(uid(), integ.type, CloudInputs.consentScope(integ.type, integ.baseUrl))
                    if (ok) selectCloudModel(sel.ref) else selectModel(null)
                }
                is ModelSelection.Local -> {
                    val ok = withContext(Dispatchers.IO) { db.modelDao().byId(sel.modelId) } != null
                    selectModel(if (ok) sel.modelId else null)
                }
                null -> selectModel(null)
            }
        }
    }

    /**
     * モデルを選び、エンジンに読み込む。null で選択解除(アンロード)。
     * 前回の読み込みが続いていれば取り消してから始める(最後に選んだものだけが残る)。
     * 読み込みに失敗したら、選択を解除してエラーを modelError に出す(次回起動でも落ち続けないように)。
     */
    @Synchronized
    fun selectModel(id: String?) {
        cloudReqSeq.incrementAndGet()
        _pendingConsent.value = null
        _selectedModelId.value = id
        _selectedModel.value = id?.let { ModelSelection.Local(it) }
        remoteEngine.current = null
        _modelError.value = null
        val previous = selectJob
        val mine = selectSeq.incrementAndGet()
        selectJob = scope.launch {
            // さらに新しい選択に取り消されても、前の読み込みの後始末が終わるまでは待つ(3回以上の連続選択でも順序を保つ)
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            // 待っている間に、さらに新しい選択が来ていたら、その選択に任せて何もしない
            if (selectSeq.get() != mine) return@launch
            _selectedModelId.value = id
            _selectedModel.value = id?.let { ModelSelection.Local(it) }
            remoteEngine.current = null
            _modelError.value = null
            try {
                settings.setDefaultModel(id)
                localEngine.unload()
                if (id == null) return@launch
                val m = withContext(Dispatchers.IO) { db.modelDao().byId(id) }
                    ?: error(localizedString(R.string.chat_model_missing))
                val params = settings.settings.first().params
                _modelLoading.value = 0f
                localEngine.load(models.fileOf(m).path, params) { _modelLoading.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 自分が最新の選択のときだけ状態を変える(古い読み込みの失敗で、新しい選択を消さない)
                if (selectSeq.get() != mine) return@launch
                if (BuildConfig.DEBUG) Log.e("App", "model load failed", e) else Log.e("App", "model load failed")
                _modelError.value =
                    if (NativeError.parse(e.message) != null) nativeErrorText(e.message) else e.message ?: e.toString()
                _selectedModelId.value = null
                _selectedModel.value = null
                withContext(NonCancellable) {
                    settings.setDefaultModel(null)
                    localEngine.unload()
                }
            } finally {
                if (selectSeq.get() == mine) _modelLoading.value = null
            }
        }
    }
}
