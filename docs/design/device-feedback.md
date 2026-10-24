# 端末フィードバック機能 設計書（v0.1）— 発熱での自動停止 / プロンプトのコピーと再試行 / 端末に合わせたおすすめモデル

書式と用語は `設計書.md`（v0.2）と `docs/design/js-runtime.md` に合わせます。実装コードは含みません。根拠にした既存コードは、`ChatViewModel` / `ChatScreen` / `ModelsScreen` / `ModelsViewModel` / `ModelRepository` / `DeviceMonitor` / `InferenceEngine` / `LlamaCppEngine` / `Entities`(ChatDao) / `App` / `strings.xml`（既定=英語、`values-ja`、`values-zh-rCN`、`values-zh-rTW`）です。

## 1. 概要

### 1.1 目的

POCO F7 実機で Qwen3.5-4B（IQ3_XXS）が動いた知見を受けて、次の3点を足す。

1. 端末が危険な温度になったら、生成を自動で止めて理由をチャットに出す。
2. 送信済みのプロンプトをコピーでき、そのプロンプトで答えを作り直せる（再試行）。
3. モデル検索画面の未入力時に、端末のメモリから「入る目安」を計算し、Hugging Face から取得した GGUF モデルを目安内のものだけおすすめとして出す（固定のモデル名リストは持たない）。

### 1.2 要件一覧

要件はすべて 2026-10-24 のユーザー依頼に基づく。「設計」は設計上の必要から足したもの（ユーザー未確認）。

| ID | 要件 | 出典 |
|---|---|---|
| DF-01 | `currentThermalStatus` が SEVERE 以上になったら生成を止め、理由をチャットに表示する | 依頼1 |
| DF-02 | MODERATE は現状どおり警告表示のみ（停止しない） | 依頼1 |
| DF-03 | 生成中の監視方法（OnThermalStatusChangedListener、API29 以上）と、利用者の停止・正常完了との競合の扱いを決める | 依頼1 |
| DF-04 | 停止・送信拒否の文言を、日本語・英語・簡体字中国語・繁体字中国語の4言語で用意する（依頼文は「3言語」だが、既存が4ロケールなので4つ作る） | 依頼1 / 設計 |
| DF-05 | SEVERE 以上の間は、新しい送信・再試行を受け付けない（止めた直後に再開して再発熱しないように） | 設計 |
| DF-06 | ユーザーの吹き出しの本文をクリップボードへコピーできる | 依頼2 |
| DF-07 | ユーザーの吹き出しから「再試行」でき、そのプロンプトで答えを作り直せる | 依頼2 |
| DF-08 | 再試行の履歴・DB の扱いを決める（直後の AI 回答を置き換える） | 依頼2 |
| DF-09 | 再試行と生成中・会話切り替え・モデル未読み込みの関係を決める | 依頼2 |
| DF-10 | 再試行と JavaScript 実行結果カードの関係を決める | 依頼2 |
| DF-11 | モデル検索画面で未入力のとき、端末に合わせたおすすめを出す | 依頼3 |
| DF-12 | 総メモリ・空きメモリ・ABI から「入る目安サイズ」を算出する（計算式と根拠を明記） | 依頼3 |
| DF-13 | Hugging Face の検索 API で GGUF モデルを取得し、目安に収まるものを人気順で出す | 依頼3 |
| DF-14 | 1リポジトリの中から、入る量子化ファイルを選ぶ規則を決める | 依頼3 |
| DF-15 | ネットワーク失敗時の表示を決める | 依頼3 |
| DF-16 | ダウンロード済み・ダウンロード中の表示を決める | 依頼3 |
| DF-17 | 固定のモデル名リストを持たない | 依頼3 |
| DF-18 | 上記のテスト項目（単体・実機） | 依頼4 |
| DF-19 | 実装者向けの小タスク分割 | 依頼 |

## 2. 前提・スコープ外

### 2.1 前提

- minSdk は 29 以上（js-runtime.md の P10）。`PowerManager.addThermalStatusListener` / `currentThermalStatus`（API29）を分岐なしで使ってよい。既存の `DeviceMonitor` にある `SDK_INT >= 29` の判定は、このとき不要になるが、触らずに残してよい。
- 推論は `LlamaCppEngine.generate` の Flow を collect して行う。collect を止める（Job の cancel）と `awaitClose { nativeStop() }` でネイティブの生成も止まる。この経路を発熱停止にも使う（新しいネイティブ API は足さない）。
- 履歴の DB（Room）は `version = 1`・`exportSchema = false`。このとき ChatDao に**クエリを足すだけ**で、テーブルの形は変えない（マイグレーション不要）。
- INTERNET 権限は既にある（モデル検索・ダウンロードで使用中）。
- 文言は4ファイル（`values/strings.xml`=英語、`values-ja`、`values-zh-rCN`、`values-zh-rTW`）に同じキーで足す。

### 2.2 スコープ外

- JavaScript 実行用の `:runner` プロセス内の発熱監視（実行は時間上限があり、数秒〜十数秒で終わる）。
- バッテリー温度・充電状態による制御、スレッド数の自動調整（設計書 13 章にある案。今回は停止のみ）。
- 発熱の履歴保存、通知（Notification）での発熱告知。
- AI の回答のコピー、会話途中のユーザー発言の再試行（8.2 の決定を参照）、再試行前の回答の復元（元に戻す）。
- 速度の実測による推奨（設計書 5.0「初回に短い文章で実測」）。ランク判定（5.0）との統合は未決事項 U2 に書く。
- Hugging Face の API キー・ログイン。モデルのライセンス自動判定（既存の `licenses_models_note` の案内を使う）。

## 3. 変更・追加するファイル

パスはすべて `AndroidLocalAI/app/src/main/java/com/mobileailocal/` からの相対。テストは `AndroidLocalAI/app/src/test/java/com/mobileailocal/`。

| 区分 | パス | 役割 | 要件 |
|---|---|---|---|
| 新規 | `domain/ThermalPolicy.kt` | 発熱の閾値の判定（純粋関数）と、停止理由の競合を決める `StopArbiter` | DF-01/02/03/05 |
| 新規 | `domain/ChatRetry.kt` | 再試行で残す履歴を決める純粋関数 | DF-07/08 |
| 新規 | `domain/ModelBudget.kt` | 総メモリ・空き・ABI・コンテキスト長から「入る目安（MB）」を計算 | DF-12 |
| 新規 | `domain/ModelRecommender.kt` | 量子化ファイルの選択、リポジトリの絞り込み・並べ替え、名前からのサイズ推定 | DF-13/14/17 |
| 変更 | `data/DeviceMonitor.kt` | 現在の熱状態の取得、熱状態の変化を流す Flow、`DeviceStatus` への熱状態の追加、メモリ寸法の取得 | DF-02/03/05/12 |
| 変更 | `data/Entities.kt` | `ChatDao` に再試行用のクエリを2つ追加 | DF-08 |
| 変更 | `data/ModelRepository.kt` | おすすめ候補の取得 `popular()`、ファイルの導入名を返す関数 `installedName()` | DF-13/16 |
| 変更 | `ui/ChatViewModel.kt` | 送信処理の共通化、発熱の監視、`retry()`、停止理由の状態 | DF-01〜10 |
| 変更 | `ui/ChatScreen.kt` | ユーザー吹き出しの「コピー」「再試行」、停止理由の注記、熱状態の表示 | DF-01/02/04/06/07 |
| 変更 | `ui/ModelsViewModel.kt` | おすすめの状態（取得・計算）の保持 | DF-11〜16 |
| 変更 | `ui/ModelsScreen.kt` | 未入力時のおすすめ表示 | DF-11/15/16 |
| 変更 | `res/values/strings.xml`、`values-ja`、`values-zh-rCN`、`values-zh-rTW` | 文言（6 章） | DF-04 他 |
| 新規 | `test/.../ThermalPolicyTest.kt`、`ChatRetryTest.kt`、`ModelBudgetTest.kt`、`ModelRecommenderTest.kt` | 単体テスト | DF-18 |

## 4. インターフェース

### 4.1 発熱（DF-01/02/03/05）

`domain/ThermalPolicy.kt`

```
object ThermalPolicy {
    const val MODERATE = 2   // PowerManager.THERMAL_STATUS_MODERATE と同値
    const val SEVERE = 3     // PowerManager.THERMAL_STATUS_SEVERE と同値
    fun isHot(status: Int): Boolean = status >= MODERATE      // 警告表示(従来どおり)
    fun shouldStop(status: Int): Boolean = status >= SEVERE   // 停止・送信拒否
}

enum class StopReason { User, Thermal }

/** 停止理由の先着判定。最初に request した理由だけが残る。スレッド安全。 */
class StopArbiter {
    fun request(reason: StopReason): Boolean   // 先着なら true、すでに決まっていれば false
    val reason: StopReason?                    // まだ無ければ null
}
```

定数は `PowerManager` を参照せず、値を直書きする（単体テストを Android 抜きで動かすため。値は API で固定）。

`data/DeviceMonitor.kt` への追加

```
data class DeviceStatus(
    ...既存...,
    val hot: Boolean = false,              // 意味は従来どおり(MODERATE 以上)
    val thermalBlocked: Boolean = false,   // 追加: SEVERE 以上
)

class DeviceMonitor {
    fun currentThermalStatus(): Int                      // PowerManager.currentThermalStatus。読めない例外は 0 を返す
    fun thermalStatusUpdates(): Flow<Int>                // callbackFlow。登録時に currentThermalStatus() を 1 回 emit し、以後は変化のたびに emit
    fun memoryInfo(): DeviceMemory                       // 総メモリ・空きメモリ・アプリ使用量(MB)。snapshot() と同じ取り方
}
data class DeviceMemory(val totalMb: Long, val freeMb: Long, val appMb: Long)
```

`thermalStatusUpdates()` の仕様:
- `callbackFlow` で `PowerManager.addThermalStatusListener(executor, listener)` を登録する。executor は `Dispatchers.Default.asExecutor()`（リスナーの中で重い処理をしないため、どれでもよいが、メインスレッドは避ける）。
- 登録直後に `trySend(currentThermalStatus())`（リスナーは登録時に必ず呼ばれる保証がないため）。
- `awaitClose { removeThermalStatusListener(listener) }`。
- `distinctUntilChanged()` を付けて返す。

### 4.2 チャット（DF-01〜10）

`ui/ChatViewModel.kt`

```
data class ChatUiState(
    ...既存...,
    /** 直近の生成が止まった理由(チャットに注記として出す)。null なら注記なし。 */
    val stopNotice: StopReason? = null,   // 注記を出すのは Thermal のみ。User は null のまま
)

fun send(text: String)     // 既存の入口。中身は generate(text, retry = false) に委ねる
fun retry()                // 追加。最後のユーザー発言で答えを作り直す
fun stop()                 // 既存。arbiter に User を登録してから cancel する
```

内部(private): `generate(text: String, retry: Boolean)`。`send` と `retry` が共有する。

`domain/ChatRetry.kt`

```
/**
 * 再試行で残す履歴。messages(System を除く、表示順)のうち、最後の User 発言までを返す。
 * User 発言が1つも無ければ null。
 */
fun historyForRetry(messages: List<ChatMessage>): List<ChatMessage>?
```

`ChatDao` への追加（`data/Entities.kt`）

```
@Query("SELECT id FROM message WHERE conversationId = :cid AND role = 'user' ORDER BY id DESC LIMIT 1")
fun lastUserMessageId(cid: Long): Long?

@Query("DELETE FROM message WHERE conversationId = :cid AND id > :id")
fun deleteMessagesAfter(cid: Long, id: Long)
```

UI（`ChatScreen`）の新規パラメータ・状態:
- ユーザー吹き出しの下に「コピー」「再試行」の行（8.1）。
- LazyColumn の末尾に `stopNotice != null` のとき1項目（停止理由の注記）。
- `DeviceStatusLine` は `thermalBlocked` を受け取り、真なら `chat_thermal_blocked`（赤）、そうでなく `hot` なら従来の `chat_hot_warning`。

### 4.3 モデル検索のおすすめ（DF-11〜17）

`domain/ModelBudget.kt`

```
object ModelBudget {
    const val TOTAL_RATIO = 0.50
    const val AVAIL_RATIO = 0.85
    const val RESERVE_BASE_MB = 400
    const val RESERVE_PER_4K_CTX_MB = 200
    const val NON_ARM64_RATIO = 0.5

    /**
     * モデルファイル(GGUF)の大きさとして入る目安(MB)。0 以上。
     * @param totalMb  ActivityManager.MemoryInfo.totalMem(MB)
     * @param freeMb   同 availMem(MB)
     * @param appMb    このアプリの PSS(MB)。読み込み済みモデルは解放されて置き換わるので空きに戻して数える
     * @param contextLength 設定のコンテキスト長
     * @param abis     Build.SUPPORTED_ABIS
     */
    fun budgetMb(totalMb: Long, freeMb: Long, appMb: Long, contextLength: Int, abis: List<String>): Long
}
```

`domain/ModelRecommender.kt`

```
enum class Fit { Comfortable, Tight }

data class Recommendation(val model: RemoteModel, val file: RemoteFile, val fit: Fit)

object ModelRecommender {
    const val COMFORT_RATIO = 0.70      // 目安の 70% 以下なら「余裕あり」
    const val MIN_USEFUL_RATIO = 0.25   // 目安の 25% 未満のファイルは、より大きい同系統があるので後回し
    const val MAX_ITEMS = 10
    const val MAX_CANDIDATES_FOR_TREE = 20

    /** 1リポジトリのファイルから推奨ファイルを選ぶ。無ければ null。 */
    fun pickFile(files: List<RemoteFile>, budgetMb: Long): Pair<RemoteFile, Fit>?

    /** リポジトリ名の "7B" "0.6B" "1.5b" "E4B" などから推定したパラメータ数(十億)。読めなければ null。 */
    fun paramsBillionOf(repoId: String): Double?

    /** ツリー取得の前に除外してよいか(推定パラメータ数から見て、最小の量子化でも入らないもの) */
    fun clearlyTooBig(repoId: String, budgetMb: Long): Boolean

    /** 人気順に並んだ候補ごとの pickFile の結果から、表示する最大 MAX_ITEMS 件を決める。 */
    fun rank(picked: List<Recommendation>, budgetMb: Long): List<Recommendation>
}
```

`data/ModelRepository.kt` への追加

```
/** 人気の GGUF テキスト生成モデルのリポジトリ(取得順=人気順)。失敗は例外。 */
suspend fun popular(limit: Int = 30): List<RemoteModel>

/** 導入名(ModelEntity.fileName と同じ規則)。ModelsScreen の導入済み判定を共通化する。 */
fun installedName(file: RemoteFile): String   // repoId の / を _ に置き換え + "__" + fileName
```

`ui/ModelsViewModel.kt`

```
data class RecommendUiState(
    val phase: Phase = Phase.Idle,             // Idle / Loading / Done / Error
    val budgetMb: Long = 0,
    val memory: DeviceMemory? = null,          // 目安の説明表示用
    val nonArm64: Boolean = false,
    val items: List<Recommendation> = emptyList(),
    val error: String? = null,
)
// SearchUiState に val recommend: RecommendUiState = RecommendUiState() を追加
fun loadRecommendations(force: Boolean = false)   // Idle のとき、または force のときだけ取得する
```

## 5. データ構造・設定値

### 5.1 目安サイズの計算式（DF-12）

```
reserveMb   = 400 + 200 × (contextLength / 4096.0)               ← 文脈(KV キャッシュ)と計算用バッファの見込み
limitTotal  = totalMb × 0.50 − reserveMb                          ← 端末の総メモリを基準にした上限
limitAvail  = (freeMb + appMb) × 0.85 − reserveMb                 ← 今の空きを基準にした上限
budgetMb    = max(0, min(limitTotal, limitAvail))
非 arm64-v8a(abis に "arm64-v8a" が無い) のときは budgetMb × 0.5
```

端数は切り捨てて Long にする。`contextLength` は設定（`GenerationParams.contextLength`、既定 4096）。

根拠:
- Android ではOS・常駐アプリ・描画が総メモリの半分前後を使い、残りを前面アプリが取り合う。アプリが落とされない範囲（lmkd が殺しにくい範囲）の経験則として、モデルの重み＋文脈で総メモリの約半分を上限とした（`TOTAL_RATIO`）。
- 重みは mmap で読むためメモリ圧迫時に回収されうるが、生成中は繰り返し触るので、回収を前提にしない。
- `totalMem` は広告上の容量より小さく出る（12GB 機で約 11GB 台）。この値を使うので、宣伝容量ではなく実測ベースになる。
- 今の空き（`availMem`）は他アプリの使用量を反映する。上限を `min` で取り、空きが少ないときは小さく出す（`AVAIL_RATIO`）。`appMb`（このアプリの PSS）を空きに戻して数えるのは、モデルを切り替えるときは今のモデルが解放されるため（そうしないと、読み込み中のモデルのせいで目安が不当に小さくなる）。
- `reserveMb` は 4096 で 600MB（重み以外の固定的な負担の見込み。KV キャッシュの実寸はモデルの層数・次元で変わるが、ファイル一覧からは分からないので、粗い定数とする）。
- 非 arm64: 配布は arm64-v8a のみ（設計書 2 章）で、x86_64 はエミュレーターでのデバッグ用。実機の性能と大きく違うので、目安を半分にして「参考値」と表示する（ABI の用途はこれだけ）。

計算例（総メモリ・空きは仮定。実機値ではない）:

| 総メモリ(MB) | 空き(MB) | ctx | limitTotal | limitAvail | 目安 |
|---|---|---|---|---|---|
| 11,300 | 5,000 | 4096 | 5,050 | 3,650 | 3,650 |
| 7,400 | 3,500 | 4096 | 3,100 | 2,375 | 2,375 |
| 3,600 | 1,800 | 4096 | 1,200 | 930 | 930 |

この定数は ModelBudget にまとめ、実機で見直しやすくする（未決事項 U1）。実機知見（Qwen3.5-4B IQ3_XXS が POCO F7 で動作）は、実機確認の項目に回す（8.3）。

### 5.2 量子化ファイルの選び方（DF-14）

`pickFile(files, budgetMb)` の手順:

1. 候補から除外する:
   - ファイル名に `mmproj` または `imatrix` を含むもの（モデル本体ではない）。
   - 量子化（`RemoteFile.quant`）が `?`、`F16` / `BF16` / `F32`、`Q8_*`、および `Q2*` / `IQ1*` / `IQ2*` のもの。判定は「量子化名が `Q` か `IQ` で始まり、続く最初の数字が 3〜6」のものだけを許可する（Unsloth の `Q4_K_XL` なども通る）。最小は IQ3_XXS 相当とする。理由は、実機で動いた最低ラインが IQ3_XXS で、これより低いと質が崩れやすいから。Q8_0 以上を除くのは、携帯では速度が落ちる割に質の差が小さいから。
   - `sizeBytes` が 0 のもの。
2. 残りのうち、`sizeBytes ≤ budgetMb` のものが無ければ null（このリポジトリは出さない）。
3. `sizeBytes ≤ budgetMb × 0.70` のものがあれば、その中で**最大**を選び `Fit.Comfortable`。無ければ、`budgetMb` 以下の中で最大を選び `Fit.Tight`。同じサイズなら量子化名の辞書順で小さいほう。
4. 単位は MB = 1024×1024 バイトに揃える。

### 5.3 リポジトリの絞り込み・並べ替え（DF-13、DF-17）

- 取得（`popular`）: `https://huggingface.co/api/models?filter=gguf&pipeline_tag=text-generation&sort=trendingScore&direction=-1&limit=30`。
  - HTTP 400 が返ったとき（`sort=trendingScore` が受け付けられない場合）は、`sort=downloads` で1回だけ再取得する。既存の `search()` が使っている `sort=downloads` が確実に動くことは確認済み。`trendingScore` と `pipeline_tag` の組み合わせは**実機で確認する**（8.3、未決事項 U3）。
  - 返ってきた順がそのまま人気順。`id`・`downloads`・`likes` だけを読む（`RemoteModel`）。
- ツリー取得の前に、`clearlyTooBig`: `paramsBillionOf(repoId) × 0.2 × 1024 > budgetMb` のものを除外する（最小の許可量子化でも約 0.2GB/十億パラメータ以上かかる。IQ3_XXS の約 3.1bit ≒ 0.39GB/十億パラメータより十分小さく見積もっているので、取り逃がしは起きにくい）。名前から読めないときは除外しない。
- 残りの先頭から最大 `MAX_CANDIDATES_FOR_TREE`（20）件について、既存の `files(repoId)` でファイル一覧を取り、同時 4 件までで並列に取得する（`Semaphore(4)`）。1件の失敗は無視してそのリポジトリを飛ばす。全件が失敗したら画面のエラーにする。
- `rank(picked, budgetMb)`:
  1. 取得順（人気順）を保つ。
  2. `file.sizeBytes < budgetMb × 0.25` のものは後ろへ回す（0.5B などの小モデルが、大きな端末で上位を占めないようにする）。後ろへ回しただけで捨てない。
  3. 先頭から `MAX_ITEMS`（10）件。
- 固定のモデル名・リポジトリ名の一覧は持たない（DF-17）。コードに現れる名前由来の処理は、`paramsBillionOf` の正規表現（`(\d+(\.\d+)?)\s*[bB]` と `E(\d+)B`）だけ。

### 5.4 文字列キー（DF-04）

4ファイルに同じキーで足す。英語は `values/strings.xml`（既定）。

| キー | 日本語 | English | 简体中文 | 繁體中文 |
|---|---|---|---|---|
| `chat_thermal_stopped` | 端末の温度が高くなりすぎたため、生成を止めました。ここまでの回答は残してあります。端末が冷めてから、もう一度お試しください。 | Generation was stopped because the device got too hot. The answer so far is kept. Try again after it cools down. | 设备温度过高，已停止生成。已生成的内容会保留。请等设备降温后再试。 | 裝置溫度過高，已停止生成。已生成的內容會保留。請待裝置降溫後再試。 |
| `chat_thermal_blocked` | 端末の温度が高いため、今は生成できません。少し休ませてから送信してください。 | The device is too hot to generate right now. Let it cool down, then send again. | 设备温度过高，暂时无法生成。请先降温再发送。 | 裝置溫度過高，暫時無法生成。請先降溫再傳送。 |
| `chat_copy` | コピー | Copy | 复制 | 複製 |
| `chat_copied` | コピーしました | Copied | 已复制 | 已複製 |
| `chat_retry` | 再試行 | Retry | 重试 | 重試 |
| `models_recommend_title` | この端末のおすすめ | Recommended for this device | 适合此设备的推荐 | 適合此裝置的推薦 |
| `models_recommend_budget` | 目安: 約 %1$s まで（メモリ合計 %2$s・空き %3$s） | Guide: up to about %1$s (total memory %2$s, free %3$s) | 参考：约 %1$s 以内（总内存 %2$s，可用 %3$s） | 參考：約 %1$s 以內（總記憶體 %2$s，可用 %3$s） |
| `models_recommend_fit_ok` | 余裕あり | Comfortable | 较宽裕 | 較寬裕 |
| `models_recommend_fit_tight` | ぎりぎり | Tight | 勉强 | 勉強 |
| `models_recommend_error` | おすすめを取得できませんでした: %1$s | Could not load recommendations: %1$s | 无法获取推荐：%1$s | 無法取得推薦：%1$s |
| `models_recommend_none` | この端末に入りそうなモデルが見つかりませんでした。検索で探してください。 | No models that fit this device were found. Try searching. | 没有找到适合此设备的模型。请尝试搜索。 | 找不到適合此裝置的模型。請嘗試搜尋。 |
| `models_recommend_nonarm` | この端末の CPU は想定外の構成のため、目安は参考値です。 | This device's CPU is not a supported configuration, so the guide is only approximate. | 此设备的 CPU 不在预期范围内，参考值仅供参考。 | 此裝置的 CPU 不在預期範圍內，參考值僅供參考。 |

再読み込みボタンは既存の `models_retry`（「再試行」）を再利用する。サイズの表記（GB/MB）は既存の `formatSize`（コード側で固定）を使う。

### 5.5 状態・永続化

- 停止理由 `stopNotice` は ChatUiState（メモリ）だけ。DB には保存しない（履歴を開き直すと出ない）。理由は、メッセージ本文に混ぜると次回以降モデルに渡す履歴に入ってしまうから。
- おすすめの結果は ViewModel のメモリだけ。保存しない。アプリ再起動・画面の作り直しでは再取得する（`ModelsViewModel` が生きている間は再取得しない）。

## 6. 処理フロー

### 6.1 発熱での自動停止（DF-01/02/03/05）

`ChatViewModel.generate()` の中で、生成を始める前に次を行う。

1. 送信前チェック（DF-05）: `ThermalPolicy.shouldStop(deviceMonitor.currentThermalStatus())` が真なら、状態やDBを触らずに `error = chat_thermal_blocked` を出して終了する。順序は「`generating` 中でないか → `switching` か → エンジン読み込み済みか → 熱」。
2. `val arbiter = StopArbiter()`、`val self = coroutineContext.job` を作る。`var completed = false`。
3. 監視ジョブを `viewModelScope.launch` で別に起動する（`launch` の子にしない。子にすると親の完了待ちで残るため）:
   ```
   watcher = viewModelScope.launch {
       deviceMonitor.thermalStatusUpdates().first { ThermalPolicy.shouldStop(it) }
       if (arbiter.request(StopReason.Thermal)) self.cancel()
   }
   ```
4. 既存どおり `engine.generate(...).collect`。正常に最後まで流れ終わったら `completed = true`。
5. `finally` の先頭で `watcher.cancel()`。そのあと既存の後始末（最終保存・`recordGeneration`・`InferenceService.stop`）に続き、`generating = false` を設定するときに、次を同時に行う:
   - `arbiter.reason == StopReason.Thermal && !completed` なら `stopNotice = Thermal`。それ以外は `stopNotice = null`。
6. 利用者の停止（`stop()`）: `arbiter.request(User)` してから `job.cancel()`。arbiter は生成ごとに作るので、現在の arbiter をフィールド `currentArbiter` に持つ。

競合の整理:

| 場面 | 結果 |
|---|---|
| 熱の通知と利用者の停止がほぼ同時 | 先に `request` できたほうが勝つ。User が勝てば注記を出さない。Thermal が勝てば注記を出す |
| 熱の通知と正常完了がほぼ同時 | 回答は最後まで出ているので `completed = true` となり注記を出さない（`watcher.cancel()` が先に効いた場合も同様） |
| 熱の通知のあとに例外（ネイティブのエラー） | 既存の `error` 表示を優先する。`reason == Thermal` かつ例外なら、注記は出す（理由が熱であることは変わらないため）。ただし通常、キャンセルは `CancellationException` で `catch` を通らない |
| 会話切り替え（`open` / `newConversation`） | これらは `running.cancel()` を呼ぶ。arbiter に登録されないので `reason == null`、注記なし。新しい `ChatUiState` で上書きされる |
| 画面を離れて ViewModel が破棄された | `viewModelScope` が取り消され、監視も生成も止まる（既存の挙動）。注記は残らない |

止めたあとの回答: 途中までの文章は既存の `finally` の保存処理でそのまま保存され、画面にも残る（注記は文章の下に出す）。

- MODERATE（DF-02）: 何も止めない。`DeviceStatus.hot` による既存の `chat_hot_warning` 表示のまま。
- `DeviceStatus.thermalBlocked`（SEVERE 以上）は 2 秒ごとのスナップショットで更新する。生成の停止そのものはリスナーで即時に行い、2 秒の遅れに依存しない。

### 6.2 コピー（DF-06）

1. ユーザー吹き出しの「コピー」を押す。
2. `ClipboardManager` を `LocalContext.current.getSystemService` で取り、`ClipData.newPlainText("prompt", message.content)` を `setPrimaryClip`。本文は表示している文字列そのまま（加工しない）。
3. Android 13（API 33）以上はシステムが確認表示を出すので何もしない。API 32 以下は `Toast.makeText(context, chat_copied, SHORT)`。
4. 生成中でもコピーはできる。

### 6.3 再試行（DF-07/08/09/10）

対象と見た目:
- 「コピー」は、すべてのユーザー吹き出しに出す。
- 「再試行」は、**最後のユーザー発言にだけ**出す（`visible.indexOfLast { it.role == Role.User }` の項目）。会話の途中の発言を再試行すると、その後の会話を捨てることになるので出さない。
- 「再試行」は `generating` の間は出さない（無効化でなく非表示。吹き出し下の行は「コピー」だけになる）。
- 行の配置: `AI 生成`＋`報告` の行（`ChatScreen` 既存）と同じ流儀で、ユーザー吹き出しの右寄せの下に `TextButton`（`labelSmall`、`contentPadding = 8.dp, 0.dp`）を並べる。

処理（`retry()`）:

1. 受け付け条件。満たさなければ何もせず戻る（エラー表示が必要なのは、モデル未読み込みと熱のときだけ。それぞれ下に書いたとおり）:
   - `generating` でない／`switching` でない。
   - `conversationId` が null でない。
   - `historyForRetry(state.messages)` が null でない（User 発言がある）。
   - エンジン読み込み済み（なければ `chat_no_model_loaded` を `error` に出す。`send` と同じ）。
   - 熱が SEVERE 未満（6.1 の送信前チェック）。
2. `a.jsRunManager.cancelAndClear()`（8.3 の理由）。
3. `generate(text = 最後の User 発言, retry = true)` に入る。`generate` の `launch` の中身は `retry` で次のように分かれる:
   - 共通: `finalSave?.join()`（保存の完了を待つ）、設定の取得。
   - `retry = false`（既存の send）: 会話の作成（未作成なら）、ユーザー行と空のアシスタント行を挿入。
   - `retry = true`:
     1. `val uid = dao.lastUserMessageId(cid)`（IO）。null なら何もせず終了（`error` は出さない。あり得ない状態）。
     2. `dao.deleteMessagesAfter(cid, uid)`（直後のAI回答、およびそれ以降の行をすべて削除。通常は AI 回答 1 件だけ）。
     3. 空のアシスタント行を1件挿入（`aid`）。ユーザー行は触らない（`createdAt`・タイトルもそのまま）。
     4. `history = historyForRetry(state.messages)`。
   - 共通: `_state` を `messages = history + ChatMessage(Role.Assistant, "")`、`generating = true`、`error = null`、`stopNotice = null` に更新し、以降（システムプロンプトの組み立て、`generate().collect`、保存）は既存の send と同一。
4. `historyForRetry`: `messages`（System 以外の表示順）の最後の User 発言の位置までを返す。それより後（AI 回答、途中で止めた回答、空の回答）は含めない。

履歴・DB の扱い（DF-08）— **置き換え**:
- 直後の AI 回答は削除して作り直す。旧回答は復元できない（分岐保存はしない。DB のテーブルを増やさない）。
- 途中で止めた回答・エラーで空になった回答・熱で止めた回答にも同じく使える（これが再試行の主な使い道）。
- 会話の `updatedAt` は生成終了時の既存処理（`dao.touch`）で更新される。
- 乱数の種は `System.nanoTime()` なので、同じプロンプトでも別の答えになる。

生成中の扱い（DF-09）: 生成中は「再試行」を出さず、`retry()` も 1. で弾く。生成を止めてから押す。会話切り替え中（`switching`）も弾く。

JavaScript 実行結果カードとの関係（DF-10）:
- カードのキー `BlockKey(messageIndex, blockIndex, codeHash)` は「何番目のメッセージの何番目のコード」で決まる。再試行で回答が作り直されると、同じ位置の別のコードになり、結果が別コードのカードに貼り付いて見えるおそれがある。
- そのため `retry()` の最初に `jsRunManager.cancelAndClear()` を呼ぶ（実行中なら中止し、結果カードも捨てる）。`open` / `newConversation` が行っているのと同じ扱い。
- 入力欄に入れてある「AI に渡す」の本文は変えない。
- 「AI に渡す」で送った結果メッセージ（ユーザー発言）が最後の発言のとき、再試行はその発言への回答を作り直す。コードの再実行はしない（実行は常に確認ダイアログを通す）。

### 6.4 おすすめ（DF-11〜16）

表示条件: モデル画面の「検索」タブで、`query` が空（空白のみを含む）で、`results` が空で、`searching` でないとき。`setQuery` で `query` が空になったら `results`・`files`・`fileErrors` もクリアして、おすすめ表示に戻す（既存は `setQuery` で結果が残る）。

取得のフロー（`loadRecommendations(force)`）:

1. `phase` が `Idle` でなく、かつ `force` でないなら何もしない。`phase = Loading` にして `error = null`。
2. 端末の寸法: `deviceMonitor.memoryInfo()`（総・空き・アプリ）、`settings.params.contextLength`、`Build.SUPPORTED_ABIS.toList()`。`budgetMb = ModelBudget.budgetMb(...)`。`nonArm64 = "arm64-v8a" !in abis`。
3. `repo.popular()` を呼ぶ。例外なら `phase = Error`、`error = errorText(e)`（既存の `HttpStatusException` の文言化を使う）。
4. `clearlyTooBig` で除外 → 先頭 20 件を並列（同時 4）で `repo.files()` → `pickFile` → `Recommendation` を作る（1件の失敗は飛ばす）。
5. 取れた件数が 0 で、かつ `files()` 呼び出しが 1 件以上あり全て失敗していたら `phase = Error`（`error = 最後の例外の文言`）。
6. それ以外は `ModelRecommender.rank(...)` の結果を `items` に入れ、`phase = Done`。`items` が空なら「該当なし」の表示（`models_recommend_none`）。

画面（`SearchTab` の、`results` が空のときの表示を差し替える）:

- 見出し `models_recommend_title`（`titleMedium`）。
- 目安の説明 1 行（`models_recommend_budget`、`bodySmall`、`onSurfaceVariant`）。`nonArm64` なら次の行に `models_recommend_nonarm`。
- `Loading`: 中央に `CircularProgressIndicator`（既存の検索中の表示と同じ）。
- `Error`: `models_recommend_error` を赤で表示し、`models_retry` ボタンで `loadRecommendations(force = true)`。その下に従来の `models_search_empty`（「Hugging Face で GGUF モデルを検索」）も出し、検索は使えることが分かるようにする。
- `Done` かつ空: `models_recommend_none`。
- `Done`: `LazyColumn`。各項目は、リポジトリ名（`repoId`、1 行）、`models_repo_stats`（既存）、そのすぐ下に既存の `FileRow`（ファイル名・量子化・サイズ・ダウンロードボタン）、`FileRow` の右上または量子化の行に `Fit` のラベル（`models_recommend_fit_ok` / `models_recommend_fit_tight`。Tight は `error` 色でなく `tertiary` 色）。
- ダウンロード中・導入済みの表示（DF-16）: 既存の `FileRow(download = downloads[f.url], alreadyInstalled = ...)` をそのまま使う。`alreadyInstalled` は `installed.any { it.fileName == repo.installedName(f) }` に置き換え（今は `ModelsScreen` に式が直書き。共通化する）。導入済みでも項目は出す（「導入済み」ボタンが無効で表示される）。
- 更新: 画面が開いたとき（`LaunchedEffect(Unit)` で `loadRecommendations(false)`）。`SearchTab` の下に更新ボタンは置かない（`Error` の再試行だけ）。メモリは取得時点の値であり、更新したいときはタブを作り直す（ViewModel が破棄されるまで保持）。この割り切りは未決事項 U4。

## 7. エラーハンドリングと境界条件

| 場面 | 扱い |
|---|---|
| `currentThermalStatus` や `addThermalStatusListener` が例外（OEM の不具合） | `currentThermalStatus()` は 0（正常）を返す。`thermalStatusUpdates()` は例外を握りつぶして flow を閉じる（`close()`）。監視が無くなるだけで、生成は止まらない。ログ（`Log.w("DeviceMonitor", ...)`）に残す |
| 熱の通知が遅い・来ない端末 | 保証できない。送信前チェックと、リスナー登録直後の `currentThermalStatus` で拾える範囲を拾う。2 秒ごとのスナップショットは表示用で、停止には使わない |
| SEVERE 以上のまま「送信」 | `chat_thermal_blocked` を `error` に出して何も保存しない。入力欄の文字は消さない（`ChatScreen` は `send` の呼び出し後に `text = ""` を実行する。**送信前に拒否したことが分かるよう、`send` / `retry` は受け付けたら true を返し、`ChatScreen` は true のときだけ入力を消す**）。この変更は既存の `send()` の戻り値を Unit から Boolean にする |
| 熱で止めた直後に、同じ会話へ `retry` | 熱が下がっていなければ `chat_thermal_blocked`。下がっていれば再試行できる |
| 再試行の DB 削除に失敗（例外） | `generate` の `try/catch` 外の準備段階で例外が出る場合は、既存の準備段階と同様に `viewModelScope` の例外ハンドラに任せず、`try` で囲んで `error = e.message`、`generating = false` のまま戻る。DB が中途半端でも、次の `retry` で `deleteMessagesAfter` が再び効くので壊れない |
| 最後の User 発言が空文字（あり得ない） | `retry()` は何もしない（`text.isBlank()` を `generate` の入口で弾く） |
| モデル未読み込みで `retry` | `chat_no_model_loaded` を `error` に出す |
| おすすめ: `totalMb`・`freeMb` が 0 や取得不能 | `budgetMb = 0` → すべて `pickFile` が null → 「該当なし」。メモリ情報が取れない端末は想定しない |
| おすすめ: 目安が極端に小さい（< 300MB） | 同じ。`models_recommend_none` を出し、検索を促す |
| おすすめ: `files()` が分割ファイルだけ（`-00001-of-00002`）のリポジトリ | 既存の `files()` が除外するので候補なし → そのリポジトリは出ない |
| おすすめ: サブフォルダにある量子化（`Q4_K_M/xxx.gguf`） | `tree/main` は再帰しないため拾えない（既存の `files()` と同じ制約。そのリポジトリは出ない） |
| おすすめ: 429（レート制限）・5xx | 1件の `files()` 失敗は無視。`popular()` の失敗は `Error` 表示。HTTP 文言は既存の `download_http_error` |
| おすすめ取得中にタブ移動 | `viewModelScope` の中で動いているので続行する。戻ったら結果が出ている |
| おすすめ取得中に検索を始めた | `runSearch` は `results` を置き換える。おすすめの状態（`recommend`）は保持するが、`results` が空でない間は表示しない |
| オフライン | `popular()` が `UnknownHostException` 等 → `Error` 表示（`e.message`）。検索・ダウンロードも同様に使えないため、「導入済み」タブは使える |
| `paramsBillionOf` が誤判定（例: "Llama-3.1-8B" の "3.1"） | 正規表現は `[bB]` が直後に付く数字だけを採る（"3.1-8B" なら 8）。誤判定の影響は「除外が過剰/不足」で、除外が不足しても `pickFile` が最終判定する。過剰に除外しないよう、読めなければ除外しない |

## 8. テスト方針

### 8.1 単体テスト（JVM、`src/test/java/com/mobileailocal/`）

流儀は既存どおり JUnit4・クラス名は `〜Test`・Android 依存なし。

`ThermalPolicyTest`（DF-01/02/03/05）
- `isHot`: 0・1 は偽、2・3・4・5・6 は真。
- `shouldStop`: 0〜2 は偽、3・4・5・6 は真。
- `StopArbiter`: 初期 `reason == null`。`request(Thermal)` が true で、続く `request(User)` は false、`reason == Thermal`。逆順なら User が残る。同じ理由を2回は2回目が false。2スレッドから同時に `request` して、true を返すのがちょうど1つ（100 回繰り返す）。

`ChatRetryTest`（DF-07/08）
- `[User, Assistant]` → `[User]`。
- `[User, Assistant, User, Assistant]` → 最初の 3 件（最後の User まで）。
- `[User]`（回答なし）→ `[User]`。
- `[User, Assistant(空)]` → `[User]`。
- `[]` → null。`[Assistant]` のみ → null。
- `[User, Assistant, User]` → 全件。

`ModelBudgetTest`（DF-12）
- 5.1 の表の 3 行を、期待値（3,650 / 2,375 / 930）で確認。
- `contextLength = 8192` で `reserve` が 800 になり、`limitTotal`・`limitAvail` とも 200 小さくなる。
- `freeMb` が大きく `limitAvail > limitTotal` のとき `limitTotal` が採られる。逆も。
- `appMb` を足すと目安が大きくなる（`appMb = 0` と比較）。
- 結果が負になる入力（総メモリ 500MB）で 0 になる。
- `abis = ["x86_64"]` で値が半分（切り捨て）。`["arm64-v8a","armeabi-v7a"]` は半分にならない。`[]` は非 arm64 扱い。

`ModelRecommenderTest`（DF-13/14/17）
- `pickFile`:
  - `Q4_K_M`・`Q8_0`・`F16`・`IQ3_XXS`・`mmproj-F16` を含む一覧で、目安が十分大きいとき、`Q8_0`・`F16`・`mmproj` は選ばれない。
  - 目安が `Q4_K_M` の 1.2 倍のとき、`Q4_K_M` が `Comfortable`（70% 以下）なら Comfortable、70% 超なら `Tight`。
  - 全ファイルが目安を超えるとき null。
  - `Q2_K`・`IQ2_XS`・`IQ1_S`・`?`（量子化が読めない）だけの一覧は null。`IQ3_XXS` は許可。`UD-Q4_K_XL` は許可（Q4_K_XL）。
  - サイズ 0 のファイルは無視。同サイズの2ファイルは辞書順で小さいほう。
- `paramsBillionOf`: "Qwen3-4B-GGUF" → 4.0、"Qwen3-0.6B-GGUF" → 0.6、"Llama-3.1-8B-Instruct" → 8.0、"gemma-3n-E4B-it" → 4.0、"mistral-7b" → 7.0、"foo-bar" → null。
- `clearlyTooBig`: 70B で目安 3,650MB → 真。4B で 3,650MB → 偽（4×0.2×1024 = 819）。名前から読めなければ偽。
- `rank`: 入力順（人気順）が保たれる。目安の 25% 未満のものは後ろへ回る（捨てない）。11 件を渡すと 10 件。空を渡すと空。

`DataLogicTest`（既存）への追加は不要。

画面・ViewModel のテストは既存に無い（Compose・Android 依存）ため、単体テストでは対象にしない。

### 8.2 実機確認（POCO F7 ほか）

発熱（DF-01〜05）
- [ ] 熱の疑似設定: `adb shell cmd thermalservice override-status 3` を生成中に実行 → 生成が止まり、回答の途中までが残り、`chat_thermal_stopped` の注記が出る。`override-status 2` では止まらず、警告表示のみ。`adb shell cmd thermalservice reset` で戻る。
- [ ] `override-status 3` のまま「送信」→ `chat_thermal_blocked`、入力欄の文字が消えない、履歴に何も増えない。
- [ ] 熱で止めた直後に画面を履歴へ切り替えて戻る → 回答の途中までが保存されている。注記は出ない（保存しない仕様）。
- [ ] 生成中に利用者が「停止」とほぼ同時に `override-status 3` → どちらでも落ちない。注記は出ても出なくてもよいが、二重に出ない。
- [ ] 本物の発熱（連続生成）で停止まで至るか。止まったあとに熱が下がって再試行できるか。（通知の遅れ・SEVERE に達するかは端末依存）
- [ ] 4 言語で文言が崩れずに表示される。

コピー・再試行（DF-06〜10）
- [ ] コピー: ユーザー吹き出しの「コピー」→ 他のアプリへ貼り付けて同じ本文（改行・絵文字を含む）。Android 13 以上でシステムの確認表示、12 以下で Toast。
- [ ] 再試行: 最後のユーザー発言にだけ「再試行」が出る。押すと直後の回答が消えて、新しい回答が生成される。アプリを再起動して履歴を開くと、新しい回答だけが残っている。
- [ ] 生成中は「再試行」が出ない。「停止」で止めた回答に対して「再試行」できる。
- [ ] JS 拡張を有効にして、実行結果カードがある状態で「再試行」→ カードが消え、新しい回答の同じ位置のコードブロックにカードが貼り付かない。
- [ ] 会話途中で履歴から別の会話を開いて、再試行が正しい会話に作用する。
- [ ] モデルを外した状態で「再試行」→ `chat_no_model_loaded`。

おすすめ（DF-11〜17）
- [ ] 実機の総メモリ・空き・目安の値が画面に出る（`adb shell dumpsys meminfo` の Total RAM と比較して `totalMem` の差を記録）。
- [ ] 本物の Hugging Face API で `sort=trendingScore&pipeline_tag=text-generation&filter=gguf` が通るか（通らなければ `downloads` へのフォールバックが動くか）。
- [ ] 実機で Qwen3.5-4B IQ3_XXS が `Comfortable` か `Tight` で提示されるか（目安の定数 U1 を調整する材料にする）。目安を超える Q8_0 や F16 が出ない。
- [ ] 機内モードで開く → `models_recommend_error` と再試行ボタン。復帰して再試行 → 一覧が出る。
- [ ] すでに導入したファイルが「導入済み」表示。ダウンロード中は進捗が出て、完了後に「導入済み」に変わる。
- [ ] 検索窓に入力して検索 → 結果表示。入力を消すとおすすめに戻る。
- [ ] エミュレーター（x86_64）で `models_recommend_nonarm` が出る。

## 9. 実装者向けタスク分割（順序つき。1タスク=1ファイル群）

前のタスクが終わってから次へ進む。各タスクの最後に、ビルドとテストが通ること（`./gradlew testDebugUnitTest`）を確認する。

| 順 | タスク | 触るファイル | 内容 | 要件 | 完了条件 |
|---|---|---|---|---|---|
| 1 | 文字列を足す | `res/values/strings.xml`、`values-ja`、`values-zh-rCN`、`values-zh-rTW` | 5.4 のキーを4ファイルへ追加（既存の並びの最後に、機能ごとのコメント付きで） | DF-04 | 4ファイルでキーが一致。ビルドが通る |
| 2 | 発熱の純粋ロジック＋テスト | `domain/ThermalPolicy.kt`、`test/.../ThermalPolicyTest.kt` | `ThermalPolicy`・`StopReason`・`StopArbiter` | DF-01/02/03/05 | 8.1 の `ThermalPolicyTest` が通る |
| 3 | 再試行の純粋ロジック＋テスト | `domain/ChatRetry.kt`、`test/.../ChatRetryTest.kt` | `historyForRetry` | DF-07/08 | `ChatRetryTest` が通る |
| 4 | 目安の計算＋テスト | `domain/ModelBudget.kt`、`test/.../ModelBudgetTest.kt` | 5.1 の式と定数 | DF-12 | `ModelBudgetTest` が通る |
| 5 | 推奨ロジック＋テスト | `domain/ModelRecommender.kt`、`test/.../ModelRecommenderTest.kt` | 5.2・5.3 | DF-13/14/17 | `ModelRecommenderTest` が通る |
| 6 | DeviceMonitor と DAO | `data/DeviceMonitor.kt`、`data/Entities.kt` | `currentThermalStatus`・`thermalStatusUpdates`・`memoryInfo`・`DeviceStatus.thermalBlocked`、`ChatDao` の2クエリ | DF-02/03/05/08/12 | ビルドが通る。DB のバージョンは 1 のまま |
| 7 | ModelRepository | `data/ModelRepository.kt` | `popular()`（400 のときは `downloads` へフォールバック）、`installedName()` | DF-13/16 | ビルドが通る |
| 8 | ChatViewModel | `ui/ChatViewModel.kt` | `generate(text, retry)` への共通化、監視ジョブ、`StopArbiter` の組み込み、`retry()`、`stop()`、`stopNotice`、`send` / `retry` の戻り値（受け付けたら true）、`retry` での `jsRunManager.cancelAndClear()` | DF-01〜10 | 6.1・6.3 のとおり。既存の send の動作（保存・サービス開始停止・JS 追記）が変わらない |
| 9 | ChatScreen | `ui/ChatScreen.kt` | ユーザー吹き出しの「コピー」「再試行」、末尾の注記項目、`DeviceStatusLine` の `thermalBlocked`、`send` の戻り値で入力を消す、スクロールのキーに `stopNotice` を追加 | DF-01/02/04/06/07 | 8.2 の発熱・コピー・再試行の項目 |
| 10 | ModelsViewModel | `ui/ModelsViewModel.kt` | `RecommendUiState`、`loadRecommendations`、`setQuery` の空時クリア | DF-11〜16 | 6.4 の手順 1〜6 |
| 11 | ModelsScreen | `ui/ModelsScreen.kt` | おすすめの表示、`installedName` による導入済み判定の共通化 | DF-11/15/16 | 8.2 のおすすめの項目 |
| 12 | 実機確認 | （コードなし） | 8.2 を POCO F7 で実施し、結果と調整した定数（U1）を記録 | DF-18 | 8.2 のチェックが完了 |

タスク 2〜5 は互いに独立で順不同でよい。6 は 2 のあと、8 は 2・3・6 のあと、10 は 4・5・7 のあと。

## 10. 未決事項（ユーザー確認が要る点）

推奨案を本文に採用済み。次は決めきれなかった点で、変えたいときは言ってください。

- U1 目安の係数（総メモリの 50%、空きの 85%、予備 400MB＋文脈 200MB/4096）は経験則で、実機データは POCO F7 の「Qwen3.5-4B IQ3_XXS が動いた」1点のみ。この端末の総メモリ・空きの実測値が無いので、係数の妥当性は確認できていない。定数は `ModelBudget` に集約したので、実機確認（8.2）で調整する。POCO F7 の総メモリ（12GB か 16GB か）と、動いたときの空きメモリを教えてもらえると、係数を詰められる。
- U2 `設計書.md` 5.0 の「端末ランク（S/A/B/C）」との統合はしていない。今回は総メモリから連続値で計算し、ランク判定は使わない（ランク判定自体が未実装）。ランクを実装するときに、この `budgetMb` を元に作り直すのが自然。
- U3 Hugging Face の `sort=trendingScore` と `pipeline_tag=text-generation` の組み合わせが実際に通るかは、実際に API を叩いて確認していない（`sort=downloads&filter=gguf` は既存コードが使っていて動作済み）。通らなければ `downloads` 順にフォールバックする設計にしたが、「新しいモデルが上に来る」効果は無くなる。
- U4 おすすめのメモリ値は取得時点のもので、画面の中に「更新」ボタンを置いていない（失敗時の再試行のみ）。メモリの状況が変わったら更新したい場合は、ボタンを足す（小さな変更）。
- U5 停止理由の注記は保存しない（履歴を開き直すと出ない）。保存したい場合は、`message` に種別の列を足す DB の更新（Room のバージョン 2 とマイグレーション）が必要になる。
- U6 「再試行」は最後のユーザー発言のみ、旧回答は復元不可（置き換え）。複数の回答を並べて見比べたい場合は、別の設計（分岐保存）が必要。
- U7 依頼文は「3言語」だが、アプリは4ロケール（英語・日本語・簡体字・繁体字）なので4つ作る。繁体字の文言は簡体字から機械的に作った案で、確認していない。
