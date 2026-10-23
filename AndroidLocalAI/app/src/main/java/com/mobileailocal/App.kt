package com.mobileailocal

import android.app.ActivityManager
import android.app.Application
import android.util.Log
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

/** 依存の置き場。DI ライブラリは入れず、ここで手動で組み立てる。 */
class App : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var settings: SettingsRepository; private set
    lateinit var engine: InferenceEngine; private set
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
        db = Room.databaseBuilder(this, AppDatabase::class.java, "app.db").build()
        settings = SettingsRepository(this)
        engine = LlamaCppEngine()
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
                    if (id != null && engine.isLoaded) selectModel(id)
                }
        }
        // 前回選んだモデルを復元する(ファイルが消えていれば未選択に戻す)
        scope.launch {
            val id = settings.settings.first().defaultModelId
            val ok = id != null && withContext(Dispatchers.IO) { db.modelDao().byId(id) } != null
            selectModel(if (ok) id else null)
        }
    }

    /**
     * モデルを選び、エンジンに読み込む。null で選択解除(アンロード)。
     * 前回の読み込みが続いていれば取り消してから始める(最後に選んだものだけが残る)。
     * 読み込みに失敗したら、選択を解除してエラーを modelError に出す(次回起動でも落ち続けないように)。
     */
    @Synchronized
    fun selectModel(id: String?) {
        _selectedModelId.value = id
        _modelError.value = null
        val previous = selectJob
        val mine = selectSeq.incrementAndGet()
        selectJob = scope.launch {
            // さらに新しい選択に取り消されても、前の読み込みの後始末が終わるまでは待つ(3回以上の連続選択でも順序を保つ)
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            // 待っている間に、さらに新しい選択が来ていたら、その選択に任せて何もしない
            if (selectSeq.get() != mine) return@launch
            _selectedModelId.value = id
            _modelError.value = null
            try {
                settings.setDefaultModel(id)
                engine.unload()
                if (id == null) return@launch
                val m = withContext(Dispatchers.IO) { db.modelDao().byId(id) }
                    ?: error(localizedString(R.string.chat_model_missing))
                val params = settings.settings.first().params
                _modelLoading.value = 0f
                engine.load(models.fileOf(m).path, params) { _modelLoading.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 自分が最新の選択のときだけ状態を変える(古い読み込みの失敗で、新しい選択を消さない)
                if (selectSeq.get() != mine) return@launch
                Log.e("App", "model load failed", e)
                _modelError.value =
                    if (NativeError.parse(e.message) != null) nativeErrorText(e.message) else e.message ?: e.toString()
                _selectedModelId.value = null
                withContext(NonCancellable) {
                    settings.setDefaultModel(null)
                    engine.unload()
                }
            } finally {
                if (selectSeq.get() == mine) _modelLoading.value = null
            }
        }
    }
}
