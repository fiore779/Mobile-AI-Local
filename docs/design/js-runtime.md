# JavaScript 実行機能（拡張機能）設計書（v0.2）

AI が書いた JavaScript を、端末の中だけで実行する拡張機能の設計書です。書式と用語は `設計書.md`（v0.2）に合わせます。根拠にした資料は `spike-runtime/RESULTS.md`（2026-10-23 の技術検証。エミュレーター x86_64 のみ。実機・arm64 の実行は未確認）と、`spike-runtime` のソース（動く実装の参考。本体へそのまま移す前提ではない）です。

## 1. 概要

### 1.1 目的

AI の返答に含まれる JavaScript のコードブロックを、利用者が内容を確認したうえで端末内（QuickJS）で実行し、結果を画面で見たり、次のメッセージとして AI に返したりできるようにする。ネットワークには出さず、時間・メモリ・出力・ファイルに上限を持たせ、実行は別プロセスで行う。

### 1.2 要件一覧

「出典」が「指示」のものは 2026-10-22 のユーザー指示、「設計」のものは設計上の必要から足したもの（ユーザー未確認）。

| ID | 要件 | 出典 |
|---|---|---|
| JS-01 | 同梱する実行系は JavaScript（QuickJS）のみ。Python は対象外 | 指示 |
| JS-02 | 実行の前に毎回、確認ダイアログを出す（JS コードをそのまま見せる）。自動実行はしない | 指示 |
| JS-03 | 標準ライブラリだけ（npm/pip なし）。ネットワークなし | 指示 |
| JS-04 | 作業フォルダの中だけを読み書きできるファイル API（`fs`） | 指示 |
| JS-05 | 時間・メモリ・スタック・出力・コード長・ファイル容量に上限を設け、超過時の挙動を決める | 指示 |
| JS-06 | 設定内の「拡張機能」画面で有効・無効を切り替える。既定は無効 | 指示 |
| JS-07 | AI の返答のコードブロック（js / javascript）に「実行」ボタンを出す | 指示 |
| JS-08 | 実行結果（戻り値・console.log・エラー・時間切れ）を画面に出す | 指示 |
| JS-09 | 結果を「AI に渡す」ボタンで、次のメッセージとして渡せる | 指示 |
| JS-10 | 別プロセスで実行し、時間監視を三段にする | 指示 |
| JS-11 | Google Play 方針：同梱のインタプリタは可。実行時にネットワークから取得したコードは実行しない。確認ダイアログとサンドボックスを必須とする | 指示 |
| JS-12 | 4 言語（日本語・英語・簡体字中国語・繁体字中国語）の文字列 | 指示 |
| JS-13 | 容量目標（設計書 10 章）への影響の見積もりと、見直しの提案 | 指示 |
| JS-14 | 実行中の中止、アプリの背景化、プロセス死のときの状態遷移を決める | 設計 |
| JS-15 | RESULTS.md の未対策（TOCTOU・絶対パス・ホスト関数での固まり・compileSdk 36）への対処方針を決める | 設計 |
| JS-16 | AI が API を知らずに書かないよう、有効時はシステムプロンプトに短い説明を足す | 設計 |

## 2. 前提・スコープ外

### 2.1 前提（2026-10-23 にユーザーが P1〜P10 を採用と回答。P5・P7 の変更点は各行に記載）

| 番号 | 前提 | 関連 |
|---|---|---|
| P1 | 作業フォルダは 1 つだけで、会話ごとには分けない。場所はアプリ内部領域 `filesDir/js-workspace/`（他アプリから見えない。バックアップ対象外＝`allowBackup=false` のまま） | JS-04 |
| P2 | 上限値は固定で、利用者は変更できない（決定 2026-10-23） | JS-05 |
| P3 | 実行結果は端末のメモリ上にだけ置く（Room に保存しない）。アプリのプロセスが死ぬと消える。直近 1 件だけ保持（決定 2026-10-23） | JS-08, JS-14 |
| P4 | 機能が無効のときは「実行」ボタンを表示しない（決定 2026-10-23） | JS-06, JS-07 |
| P5 | 「AI に渡す」は、結果を整形して**入力欄に入れる**。利用者が編集してから送信する（決定 2026-10-23。自動送信はしない） | JS-09 |
| P6 | AI が文章を生成している間は「実行」を押せない（メモリ競合を避ける） | JS-07 |
| P7 | 実行を許す返答は、端末内モデルの返答だけ。v1.1 の API 連携（19 章）の返答では、実行ボタンを出さない（決定 2026-10-23。Play の「実行時に取得するコード」の解釈の不確実さを避けるため） | JS-11 |
| P8 | `quickjs-kt 1.0.15` が、本体の Kotlin 2.0.21 / AGP 8.7.3 でビルドできる。スパイクは Kotlin 2.4.10 で確認したので**本体では未確認**。実装順の手順 0 で最初に確かめる | JS-15 |
| P9 | 本体は arm64-v8a のみ配布（設計書 2 章）。16KB ページ対応は .so の構造で確認済み、実機は未確認 | JS-13 |
| P10 | Android 10 以上（本体の minSdk 29）。runner の判定に `Application.getProcessName()`（API 28+）を使ってよい | JS-10 |

### 2.2 スコープ外

- Python、TypeScript、Node.js 互換（`require`/`import`）、npm。
- ネットワーク（`fetch` 等）、時計以外の端末機能（カメラ・位置情報・クリップボード等）。
- バイナリファイル（`fs` は UTF-8 テキストのみ）。
- JS のコードの編集画面、保存、共有、実行履歴の保存。
- `isolatedProcess`（作業フォルダに触れなくなるため不採用。RESULTS.md 6 の項目 4）。
- 実行結果の画像・グラフ表示。
- 作業フォルダの中身を利用者が見る・取り出す手段（閲覧・共有・エクスポート。決定 2026-10-23）。
- 利用者が書いた JS を単独で実行する画面（チャット内の AI 返答のコードブロックだけが入口）。

## 3. 変更・追加するファイル

パッケージは既存の `com.mobileailocal`。既存の `data` / `domain` / `ui` に合わせ、runner プロセスでだけ動くコードは新設の `runner` に分ける（本体プロセスが QuickJS のクラスを読み込まないようにするため）。担当は 14 章参照。

### 3.1 追加

| パス（`AndroidLocalAI/app/src/main/java/com/mobileailocal/` 以下） | 役割 | 要件 |
|---|---|---|
| `domain/JsModels.kt` | 共有の型（`JsRunRequest`、`JsRunResult`、`JsRunStatus`、`JsLimits`、`BlockKey`、`JsRunState`）と `JsExecutor` インターフェース | JS-05, 08, 10 |
| `domain/CodeBlockParser.kt` | AI の返答を「文章」と「コードブロック」に分ける（純 Kotlin） | JS-07 |
| `domain/JsHandoff.kt` | 結果を「AI に渡す」メッセージに整形（純 Kotlin） | JS-09 |
| `domain/JsPathPolicy.kt` | `fs` のパス検証（字句の検査。純 Kotlin） | JS-04, 15 |
| `domain/JsValueFormatter.kt` | 戻り値・console 引数の文字列化（純 Kotlin） | JS-08 |
| `data/JsWorkspace.kt` | 作業フォルダの実体：パス解決、容量の計算と上限判定、読み書き、シンボリックリンクの掃除、全削除 | JS-04, 05, 15 |
| `data/JsProtocol.kt` | 本体と runner の Messenger のメッセージ種別・Bundle のキー・変換 | JS-10 |
| `data/JsRunnerClient.kt` | 本体側。runner への接続・要求送信・三段目の監視・kill・再接続。`JsExecutor` の実装 | JS-10, 14 |
| `data/JsRunManager.kt` | 本体側の状態管理（`StateFlow<JsRunState>`）。確認済みの要求を 1 件ずつ実行、中止、結果の保持 | JS-07〜09, 14 |
| `runner/JsRunnerService.kt` | `:runner` プロセスの Service。要求を受け、二段目の watchdog を持つ | JS-10 |
| `runner/QuickJsSession.kt` | QuickJS の生成、上限の設定、prelude、評価、結果の組み立て | JS-03, 05, 08 |
| `runner/FsBindings.kt` | JS に見せる `fs` オブジェクト（`JsWorkspace` を呼ぶだけの薄い層） | JS-04 |
| `runner/Prelude.kt` | JS 側の初期化コード（console、タイマー）の文字列定数 | JS-03, 08 |
| `ui/CodeBlockView.kt` | コードブロックの表示と「実行」ボタン | JS-07 |
| `ui/JsConfirmDialog.kt` | 実行前の確認ダイアログ | JS-02 |
| `ui/JsResultCard.kt` | 実行中・結果のカード、「AI に渡す」「閉じる」「中止」 | JS-08, 09, 14 |
| `ui/ExtensionsScreen.kt`, `ui/ExtensionsViewModel.kt` | 拡張機能画面（設定内） | JS-06 |
| `src/test/java/com/mobileailocal/` の `CodeBlockParserTest.kt`、`JsPathPolicyTest.kt`、`JsWorkspaceTest.kt`、`JsHandoffTest.kt`、`JsValueFormatterTest.kt`、`JsRunManagerTest.kt` | 単体テスト（8 章） | 全般 |

### 3.2 変更

| パス | 変更内容 | 要件 |
|---|---|---|
| `App.kt` | `onCreate` の先頭（`super.onCreate()` の直後）で、プロセス名が `packageName` でなければ何も初期化せず return する（`:runner` で Room・llama.cpp・DownloadTracker 等を作らないため）。`lateinit` の `jsRunManager` を追加 | JS-10 |
| `AndroidManifest.xml` | `<service android:name=".runner.JsRunnerService" android:process=":runner" android:exported="false" />` を追加。権限の追加はなし | JS-10, 11 |
| `build.gradle.kts`（app） | `implementation("io.github.dokar3:quickjs-kt:1.0.15")`、`testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")`、AAR メタデータの検査の回避（13.2） | JS-15 |
| `proguard-rules.pro` | 手順 0 の release ビルドで必要か判定（スパイクは追加規則なしで R8 が通った。ただし**実行確認は debug のみ**だったため、release の実機/エミュレーター実行で確認する） | JS-15 |
| `data/SettingsRepository.kt` | `AppSettings.jsEnabled: Boolean = false`、DataStore キー `js_enabled`、`setJsEnabled(Boolean)` | JS-06 |
| `ui/SettingsScreen.kt` | 「拡張機能」への入口の行を追加。ライセンス欄に QuickJS・quickjs-kt を追加 | JS-06, 11 |
| `ui/SettingsViewModel.kt` | 変更なし（拡張機能は `ExtensionsViewModel` が持つ） | - |
| `ui/AppRoot.kt` | 入れ子の経路 `settings/extensions` の追加。上部バーのタイトルと戻る矢印、下部ナビの選択状態の対応（7.6） | JS-06 |
| `ui/ChatScreen.kt` | `Bubble` を `CodeBlockParser` の結果で描き分け。確認ダイアログと結果カードの呼び出し | JS-02, 07〜09 |
| `ui/ChatViewModel.kt` | (a) 有効時にシステムプロンプトへ `Prompts.JS_ADDENDUM` を足す、(b) 「AI に渡す」用の `handoffText(result)`（6.2。入力欄に入れる本文を返すだけで送信はしない）、(c) 会話の切り替え（`open`/`newConversation`）で `jsRunManager.cancelAndClear()` を呼ぶ | JS-09, 14, 16 |
| `domain/Prompts.kt` | `JS_ADDENDUM`（4.6 の文面）を追加 | JS-16 |
| `res/values*/strings.xml`（4 言語） | 11 章の文字列 | JS-12 |

## 4. インターフェース

### 4.1 共有の型（`domain/JsModels.kt`）

```kotlin
/** 上限値(JS-05)。固定。runner の中でも本体でも同じ値を使う */
object JsLimits {
    const val TIMEOUT_MS = 5_000L            // QuickJS の評価時間(一段目)
    const val WATCHDOG_MARGIN_MS = 1_500L    // runner の watchdog = TIMEOUT_MS + これ(二段目)
    const val CLIENT_MARGIN_MS = 2_500L      // 本体の最後の保険 = TIMEOUT_MS + これ(三段目)
    const val MEMORY_BYTES = 64L * 1024 * 1024
    const val STACK_BYTES = 256L * 1024
    const val MAX_CODE_CHARS = 100_000       // 実行するコードの長さ(Kotlin の String.length)
    const val MAX_OUTPUT_CHARS = 65_536      // console 出力の合計(戻り値は別枠 = MAX_RETURN_CHARS)
    const val MAX_RETURN_CHARS = 16_384
    const val MAX_ERROR_CHARS = 8_192
    const val MAX_FILE_BYTES = 1_000_000L    // 1 ファイル(UTF-8 のバイト数。1MB = 10^6)
    const val MAX_FILES = 100                // 作業フォルダ内の通常ファイル数
    const val MAX_DIRS = 50                  // 作業フォルダ内のディレクトリ数
    const val MAX_DEPTH = 8                  // パスの階層数
    const val MAX_TOTAL_BYTES = 10_000_000L  // 作業フォルダの合計(10^7)
    const val MAX_PATH_CHARS = 512
    const val MAX_NAME_CHARS = 100           // パスの 1 要素の長さ
    const val IDLE_UNBIND_MS = 60_000L       // 最後の実行から runner を手放すまで
    const val CONNECT_TIMEOUT_MS = 5_000L
    const val MIN_FREE_MEMORY_MB = 200L      // 実行前の空きメモリの下限(実機で調整。推測値)
}

enum class JsRunStatus { Ok, Error, Timeout, Crashed, Killed }

data class JsRunRequest(
    val id: Int,            // 本体が採番する(1 から単調増加)。古い結果の取り違え防止
    val code: String,       // 確認ダイアログで見せたものと同一の文字列
    val timeoutMs: Long = JsLimits.TIMEOUT_MS,
    val workDir: String,    // JsWorkspace の絶対パス
)

data class JsRunResult(
    val id: Int,
    val status: JsRunStatus,
    val returnValue: String?,   // 最後の式の値の文字列化。undefined/null/無しは null
    val console: String,        // console.* の出力(最大 MAX_OUTPUT_CHARS)
    val error: String?,         // Error: メッセージ + スタック(最大 MAX_ERROR_CHARS)。Ok なら null
    val elapsedMs: Long,        // 本体から見た開始〜結果までの時間
    val consoleTruncated: Boolean,
    val cause: JsStopCause?,    // Timeout / Killed / Crashed の細かい理由(null 可)
)

enum class JsStopCause {
    InterruptTimeout,   // 一段目: QuickJS の割り込み
    WatchdogTimeout,    // 二段目: runner の watchdog
    ClientTimeout,      // 三段目: 本体が kill
    UserCancel,         // 利用者の「中止」(status = Killed)
    ProcessDied,        // runner が突然死(status = Crashed)
    MemoryLimit,        // QuickJS のメモリ超過(status = Error。判別できた場合のみ。9.2 参照)
    ConnectFailed,      // runner に接続できなかった(status = Crashed)
    LowMemory,          // 実行前の空きメモリ不足で実行しなかった(status = Error)
}

/** チャットのどのコードブロックか。会話の切り替えで無効にするので会話 ID は持たない */
data class BlockKey(val messageIndex: Int, val blockIndex: Int, val codeHash: Int)

sealed interface JsRunState {
    data object Idle : JsRunState
    data class Running(val key: BlockKey, val startedAtMs: Long) : JsRunState
    data class Finished(val key: BlockKey, val result: JsRunResult) : JsRunState
}

/** 本体側の実行口。テストでは偽物に差し替える */
interface JsExecutor {
    /** 1 件実行。例外は投げず、失敗も JsRunResult で返す(CancellationException は例外) */
    suspend fun run(request: JsRunRequest): JsRunResult
    /** 実行中なら runner を kill する。実行中の run() は status=Killed, cause=UserCancel で戻る */
    fun cancel()
    /** 接続を手放す(runner は自分でプロセスを終わらせる) */
    fun release()
}
```

### 4.2 `JsRunManager`（`data/JsRunManager.kt`。`App` が保持し、ViewModel より長く生きる）

```kotlin
class JsRunManager(
    private val workspace: JsWorkspace,         // 実装での変更(2026-10-23): context の代わり。root = filesDir/"js-workspace"
    private val executor: JsExecutor,
    private val jsEnabled: () -> Boolean,       // 実装での変更: settings の代わり(JVM テストで偽物にするため)。App が DataStore の最新値を持つ StateFlow の value を渡す(メインスレッドで DataStore を同期で読まない)
    private val scope: CoroutineScope,          // App の ioScope を渡す
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeMemoryMb: () -> Long,       // 実装は ActivityManager.MemoryInfo.availMem。テストでは固定値
) {
    val state: StateFlow<JsRunState>

    /**
     * 確認ダイアログで「実行」が押されたコードを実行する。戻り値は「受け付けたか」。
     * false になる場合(state は変わらない):
     *   - state が Running
     *   - jsEnabled() が false(App が持つ DataStore の最新値。実行のたびに同期で読み直さない。2026-10-23 変更)
     *   - code.length > JsLimits.MAX_CODE_CHARS または code が空白だけ
     * 空きメモリが MIN_FREE_MEMORY_MB 未満のときは受け付け、Finished(Error, cause=LowMemory) にする。
     */
    fun run(key: BlockKey, code: String): Boolean

    /** 実行中なら中止(→ Finished(Killed, UserCancel))。Running でなければ何もしない */
    fun cancel()

    /** 結果カードを閉じる(Finished → Idle)。Running なら何もしない */
    fun dismiss()

    /** 会話の切り替え・機能の無効化用。Running なら cancel してから Idle にする */
    fun cancelAndClear()

    /** 作業フォルダの使用状況(拡張機能画面用)。IO で実行 */
    suspend fun workspaceUsage(): WorkspaceUsage

    /** 作業フォルダの中身をすべて消す。Running のときは false を返して何もしない */
    suspend fun clearWorkspace(): Boolean
}

data class WorkspaceUsage(val files: Int, val dirs: Int, val bytes: Long)
```

`run` の内部手順は 6.1。拡張機能を無効にしたとき（`setJsEnabled(false)`）は、`ExtensionsViewModel` が `jsRunManager.cancelAndClear()` を呼ぶ。

### 4.3 コードブロックの解析（`domain/CodeBlockParser.kt`）

```kotlin
sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment
    data class Code(
        val lang: String,       // 情報文字列の最初の語を小文字にしたもの。無ければ ""
        val code: String,       // 本文。改行は "\n" に正規化。最後の改行は含めない
        val closed: Boolean,    // 閉じのフェンスがあったか
    ) : MessageSegment {
        val runnable: Boolean   // closed && lang in {"js","javascript"} && code.isNotBlank()
    }
}

object CodeBlockParser {
    /** ThinkingParser.parse(...).answer の文字列を渡す。思考過程の中のコードは対象外 */
    fun parse(answer: String): List<MessageSegment>
}
```

規則（実装者が迷わないように固定）:

1. 行頭から 0〜3 個の空白に続く 3 個以上のバッククォート（`` ` ``）の行を開きのフェンスとする。同じ行の残りを情報文字列とし、前後の空白を除いた最初の語（空白区切り）を小文字にして `lang` にする。`~~~` は対象外。
2. 閉じのフェンスは、0〜3 個の空白 + 開きと同数以上のバッククォートだけの行（後ろは空白のみ可）。見つかった時点で `closed = true`。
3. 閉じが見つからないまま文末になった場合は、そこまでを `Code(closed = false)` にする（`runnable` は false）。
4. フェンスの外は `Text`。空の `Text` は出力しない。連続する `Text` は 1 つにまとめる。
5. 行の区切りは `\r\n` / `\r` / `\n` を同じに扱い、`code` では `\n` に統一する。
6. `lang` が `js` / `javascript` 以外（空を含む）のブロックも `Code` として返す（表示は等幅、実行ボタンなし）。

### 4.4 結果の「AI に渡す」整形（`domain/JsHandoff.kt`）

```kotlin
object JsHandoff {
    /**
     * contextLength(設定のコンテキスト長)から、渡す文字数の予算を決める:
     * budget = (contextLength / 4).coerceIn(512, 3000)
     */
    fun budgetChars(contextLength: Int): Int

    /** 戻り値: 送信するメッセージ本文と、切り詰めが起きたか */
    fun format(result: JsRunResult, budgetChars: Int): Pair<String, Boolean>
}
```

`format` の出力形式（ラベルは英語固定。小型モデルでも読み取りやすいように言語で変えない。`strings.xml` に入れない例外とし、コード内の定数にする）:

```
[JavaScript execution result]
status: ok            // ok / error / timeout / crashed / cancelled
time: 123 ms
return value:
<returnValue>         // 無ければこの 2 行ごと省く
console:
<console>             // 空ならこの 2 行ごと省く
error:
<error>               // 無ければこの 2 行ごと省く
```

切り詰め規則: 各セクションの本文に上限を割り当てる（返り値 = budget の 1/4、console = 1/2、error = 1/4。余った分は他に回さない）。超えた分は先頭側を残し、末尾に `\n...[truncated N chars]` を付ける（N = 切り捨てた文字数）。`consoleTruncated` が true のときも、`[output truncated]` の行を console 末尾に残す。いずれかが切り詰められたら `Pair.second = true`（画面に「長いので一部だけ渡しました」と出す）。

### 4.5 値の文字列化（`domain/JsValueFormatter.kt`）

```kotlin
object JsValueFormatter {
    /** 戻り値用。null は null(=「戻り値なし」)。文字列はそのまま、数値・真偽は toString、
     *  List/Map は JSON 風(キーは文字列化。循環は "[Circular]"、深さ 8 超は "[…]")、その他は toString() */
    fun formatReturn(v: Any?): String?
}
```

注意（未確認）: `quickjs-kt` の `evaluate<Any?>` が、JS のオブジェクト・配列・undefined・BigInt を Kotlin のどの型で返すかは**スパイクで文字列の "=> $v" しか確認していない**。手順 0 で型の一覧を確かめ、このクラスのテーブルを確定する（未決事項 U3）。console の引数の文字列化は、JS 側の prelude（`__fmt`。スパイクと同じ。JSON.stringify、BigInt は末尾 `n`）で行うので、本クラスは戻り値だけを担当する。

### 4.6 システムプロンプトへの追記（`Prompts.JS_ADDENDUM`、JS-16）

`jsEnabled` が true のとき、`ChatViewModel.send` が、`system`（会話のシステムプロンプト、空なら `DEFAULT_SYSTEM`）の後ろに `"\n\n" + Prompts.JS_ADDENDUM` を足す。英語の固定文（約 110 トークン）:

```
You can write JavaScript in a ```js code block; the user may run it on the device (QuickJS, no network, no npm).
Available: console.log, setTimeout/sleep(ms), and fs.writeFile(path,text), fs.readFile(path), fs.list(dir), fs.exists(path), fs.remove(path) for text files in a private workspace (relative paths only).
Limits: 5 seconds, small memory. The value of the last expression and console output are returned to you.
```

メモ: コンテキスト長が小さい端末（ランク C は 2048）では約 5% を使う。切り替えると先頭が変わるため KV キャッシュの再利用が一度だけ効かなくなる（設計書 9 章の 5）。

### 4.7 本体⇔runner のメッセージ（`data/JsProtocol.kt`）

Messenger（`Handler` は各プロセスのメインスレッド）。`PROTOCOL_VERSION = 1`。

| `what` | 向き | 内容（Bundle のキー） |
|---|---|---|
| `MSG_PING = 1` | 本体→runner | `replyTo` に本体の Messenger |
| `MSG_PONG = 2` | runner→本体 | `pid: Int`、`version: Int` |
| `MSG_RUN = 3` | 本体→runner | `id: Int`、`code: String`、`timeoutMs: Long`、`workDir: String`、`replyTo` |
| `MSG_RESULT = 4` | runner→本体 | `id`、`status: String`（`ok/error/timeout/crashed/killed`）、`returnValue: String?`（キー無しで null）、`console`、`error?`、`truncated: Boolean`、`cause: String?`、`runnerElapsedMs: Long`、`exiting: Boolean`（実装で追加：runner が返信の後で自分を終わらせる〔watchdog・ホスト側 OOM〕ので、本体は切断を待ってから次を送る） |

- 中止は専用メッセージを使わず、本体が `Process.killProcess(runnerPid)` で runner を殺す（固まっていても確実に止まる）。
- runner は `MSG_RUN` を受けたとき既に実行中なら、`status=error` / `error="runner busy"` で即答する（本体は 1 件ずつしか送らない前提の保険）。
- Binder の 1 回の通信は約 1MB まで。`code`（最大 10 万文字 ≒ 200KB）と結果（console 6.5 万文字 + 戻り値 1.6 万 + エラー 0.8 万 ≒ 180KB）は、それぞれ別のメッセージなので収まる。上限値を上げるときはここを再確認する。
- `JsProtocol` は `Bundle` との変換だけを持つ（`fun JsRunRequest.toMessage(replyTo: Messenger): Message`、`fun Message.toRunRequest(): JsRunRequest`、`fun JsRunResult.toMessage(): Message`、`fun Message.toRunResult(): JsRunResult`）。`version` が合わないときは本体が `ConnectFailed` にする。

### 4.8 `JsWorkspace`（`data/JsWorkspace.kt`）

```kotlin
class JsWorkspace(val root: File) {                  // root = context.filesDir/"js-workspace"
    fun ensureRoot()                                  // mkdirs
    fun sanitize()                                    // シンボリックリンクと *.tmp-* を削除(5.3)。実行の開始前に runner が呼ぶ
    fun usage(): WorkspaceUsage
    fun clear(): Boolean                              // root の中身をすべて削除(root は残す)

    // 以下は fs API の実体。失敗は JsFsException(code, message) を投げる(code は 5.2)
    fun write(path: String, text: String)
    fun read(path: String): String
    fun list(path: String): List<String>              // ディレクトリは末尾に "/" を付ける。名前順
    fun exists(path: String): Boolean
    fun remove(path: String): Boolean
}

class JsFsException(val code: String, message: String) : RuntimeException("$code: $message")
// 実装での置き場所: JsFsException と WorkspaceUsage は domain/JsModels.kt（domain の JsPathPolicy が data を参照しないため）
```

```kotlin
object JsPathPolicy {
    /** 字句の検査と正規化。許可なら正規化した相対パス("a/b.txt"。区切りは "/")、拒否なら JsFsException("EPERM"/"EINVAL"/"ENAMETOOLONG") */
    fun normalize(raw: Any?, allowEmpty: Boolean): String
}
```

## 5. データ構造・仕様

### 5.1 JS から見える API

実行ごとに新しい QuickJS を作る（状態は持ち越さない。ファイルだけが残る）。見えるのは JS 標準の組み込み + 次だけ。

| 名前 | 仕様 |
|---|---|
| `console.log/info/warn/error/debug(...args)` | 引数を空白で連結して 1 行にして出力に追加。文字列はそのまま、オブジェクトは JSON、BigInt は `123n`、`Error` は `stack`（無ければ `名前: メッセージ`）。stdout と stderr の区別はしない（すべて `console` に入る） |
| `setTimeout(fn, ms=0, ...args)` / `clearTimeout` / `setInterval(fn, ms=0, ...args)`（間隔は最低 1ms）/ `clearInterval` / `queueMicrotask(fn)` / `sleep(ms): Promise<void>` | スパイクの prelude と同じ。実時間は 5 秒の上限に含まれる |
| `fs.writeFile(path, text)` | `text` は文字列（それ以外は `String()` 変換はしない。文字列でなければ `EINVAL`）。UTF-8 で書く。親ディレクトリは自動で作る。既存ファイルは上書き。戻り値なし |
| `fs.readFile(path): string` | UTF-8 として読む。不正なバイト列は `EINVAL: not valid UTF-8`（置換しない） |
| `fs.list(dir = "."): string[]` | 直下の名前を名前順で返す。ディレクトリは末尾 `/`（**スパイクとの変更点**：AI がファイルと区別できるように） |
| `fs.exists(path): boolean` | ファイルまたはディレクトリがあれば true。パスが不正なら例外（false にしない） |
| `fs.remove(path): boolean` | **ファイルだけ**消す。消したら true、無い・ディレクトリなら false。ディレクトリの削除は提供しない（掃除は「作業フォルダを空にする」で行う） |

- 戻り値：コードの最後の式の値（トップレベル `await` 可。スパイクで動作確認済み）。
- 無い（スパイクで確認）：`require`、`fetch`、`XMLHttpRequest`、`WebSocket`、`process`、`Buffer`、`window`、`navigator`、`Intl`、`TextEncoder`、`structuredClone`、`crypto`、`performance`。動的 `import("fs")` は失敗する。`eval` / `new Function` は使えるが、同じ囲いの中なので追加の権限にならない。
- 本番に入れない：スパイクの検証用関数 `__block` `__exit` `__oom`。`__log` と `__sleep` は内部用で、prelude の後で `delete globalThis.__log` はしない（prelude のクロージャが使うため）。名前が `__` で始まることで AI に使わせない（システムプロンプトにも書かない）。

### 5.2 fs のエラー

ホスト関数が `JsFsException` を投げると、JS では `Error` として受け取れる（メッセージは `"<code>: <説明>"`）。`try/catch` で捕まえられることを手順 0 で確認する（スパイクは SecurityException の拒否は確認したが、JS 側での捕捉は未確認）。

| code | 場面 |
|---|---|
| `EPERM` | 作業フォルダの外を指す（`..`、絶対パス、シンボリックリンク） |
| `EINVAL` | パスが空（`list` 以外）、`\`・NUL 文字・制御文字を含む、`text` が文字列でない、UTF-8 でない |
| `ENAMETOOLONG` | パスが 512 文字超、1 要素が 100 文字超、階層が 8 超 |
| `ENOENT` | 読み・一覧の対象が無い |
| `EISDIR` / `ENOTDIR` | ファイルを期待する所がディレクトリ、またはその逆 |
| `EFBIG` | 1 ファイルが 1,000,000 バイト超（書き込み前に検査。書かない） |
| `ENOSPC` | ファイル数 100 超、ディレクトリ数 50 超、合計 10,000,000 バイト超（書き込み前に検査。書かない） |
| `EIO` | その他の入出力エラー |

### 5.3 パス検証（JS-04, JS-15）

三重に守る。**字句の検査 → 実体の検査 → 掃除**。

1. 字句（`JsPathPolicy.normalize`、ファイルに触れる前）：
   - `raw` が文字列でなければ `EINVAL`。`list` だけ空文字・`"."` を許す（root を指す）。
   - 先頭が `/` または `\`、`\` を含む、`:` を含む（`C:` 風）、NUL・制御文字（0x00〜0x1F, 0x7F）を含む → 拒否（`/etc/hosts` は**明示的に拒否**。スパイクでは作業フォルダ内の `etc/hosts` 扱いになっていた）。
   - `/` で分け、要素が空（`a//b`）や `.` は取り除く。`..` を含む要素があれば、**正規化せず拒否**（`sub/../x` も拒否）。
   - 長さ・階層の上限（5.2）を検査。要素は NFC に正規化しない（そのままの文字列で比べる）。
2. 実体（`JsWorkspace`、開く直前）：`root` を `canonicalFile` にしたものと、`File(root, normalized)` の**既存の最も深い祖先**を `canonicalFile` にしたものを比べ、祖先が root 配下（root 自身を含む）でなければ `EPERM`。さらに、root から対象までの各要素について `Files.isSymbolicLink` が true なら `EPERM`（リンクをたどらない）。ファイルを書くときは「同じ親ディレクトリに一時ファイル `.<name>.tmp-<nanoTime の数字>` を書き、`Files.move` で置き換え」て、途中で kill されても中途半端な内容が残らないようにする。
3. 掃除（`sanitize`、各実行の開始前に runner が呼ぶ）：root 配下を `Files.walk`（リンクをたどらない）で走査し、シンボリックリンクと一時ファイル（名前が `^..+.tmp-d+$` に合うもの。利用者のファイルを消さないよう設計より狭くした）を削除する。

**TOCTOU（検査と使用の間にリンクを差し替えられる競合）への方針**：作業フォルダは `filesDir` の中（同じ UID のコードしか書き込めない）で、JS から見える API にはリンクの作成がない。したがって攻撃者になりうるのは同じアプリの別プロセスだけで、実際の危険は無いとみなす。上記 2 の「リンクをたどらない」「書き込みは一時ファイル + move」と、3 の掃除で、通常の事故（過去の版が残したリンクなど）を防ぐ。`Files.newByteChannel(NOFOLLOW_LINKS)` が Android で `O_NOFOLLOW` として働くかは**未確認**（未決事項 U5）なので、これには頼らない設計にした。

### 5.4 上限と超過時の挙動（JS-05）

数値は `JsLimits`（4.1）。結果の `status` と `cause` を決める。

| 項目 | 上限 | 超過時の挙動 | status / cause |
|---|---|---|---|
| 実行時間 | 5 秒 | QuickJS の割り込みで停止。それ以前の console 出力は結果に残る | Timeout / InterruptTimeout |
| 実行時間（固まり） | 5 + 1.5 秒 | runner の watchdog が途中経過を返して自分を kill（二段目） | Timeout / WatchdogTimeout |
| 実行時間（最後の保険） | 5 + 2.5 秒 | 返事が無ければ本体が runner を kill（三段目） | Timeout / ClientTimeout |
| メモリ | QuickJS のヒープ 64MB | QuickJS の例外で終了（`InternalError: out of memory` は JS で `catch` 可。メッセージが null のことがある） | Error / MemoryLimit（判別できたとき）。判別できなければ cause = null |
| スタック | 256KB | `InternalError: stack overflow`（`catch` 可） | Error |
| コード長 | 100,000 文字 | 実行ボタンを押せなくし（「長すぎます」を表示）、`run()` も false を返す | - |
| console 出力 | 65,536 文字 | 超えた分は捨て、末尾に `\n[output truncated]`。実行は続ける | `consoleTruncated = true` |
| 戻り値 | 16,384 文字 | 超えた分は切り、末尾に `…[truncated]` | - |
| エラー文 | 8,192 文字 | 同上 | - |
| 1 ファイル | 1,000,000 バイト | `EFBIG` を投げる（書かない） | JS が捕まえなければ Error |
| ファイル数 / ディレクトリ数 / 合計 | 100 / 50 / 10,000,000 バイト | `ENOSPC` を投げる（書かない） | 同上 |
| 空きメモリ | 端末の空き 200MB 未満 | 実行しない | Error / LowMemory |

注意:
- マイクロタスクの無限連鎖は時間切れでは止まらず、メモリ上限で止まる（スパイクで約 1.2 秒）。結果は Error になる。
- ホスト（Kotlin）側で確保するメモリは QuickJS の上限と無関係。`fs.readFile` は 1,000,000 バイトまで、`list` は最大 150 件なので、JS が引き起こせるホスト側の確保は小さい。ホスト関数を足すときは必ず上限を設ける（設計規則）。
- 時間の計測：`TIMEOUT_MS` は QuickJS の `evaluationTimeoutMillis`。`sleep`/`setTimeout` の待ちが評価時間に数えられるかは**未確認**。数えられなくても二段目の watchdog が実時間で止める（Timeout / WatchdogTimeout になる）。手順 0 で確認し、数えられない場合は「`sleep` を使うコードは二段目で止まる」ことを結果の説明に反映する（未決事項 U6）。

### 5.5 設定値（DataStore）

`AppSettings.jsEnabled: Boolean = false`（キー `js_enabled`）。上限値は設定にしない（P2）。

## 6. 処理フロー

### 6.1 実行（正常系）

1. 返答の表示：`ChatScreen` の `Bubble` が `ThinkingParser.parse(m.content).answer` を `CodeBlockParser.parse` に通し、`MessageSegment` ごとに描く。`Code` は等幅のブロックにする。`runnable` かつ `jsEnabled` かつ「生成中でない（P6）」かつ `code.length <= MAX_CODE_CHARS` のときだけ「実行」ボタンを出す。
2. 「実行」押下：ダイアログ用の状態 `confirming: PendingRun(key, code)` を作る（`remember`。**この `code` が確認で見せる内容であり、実行に渡す内容**）。
3. 確認ダイアログ（7.2）で「実行」→ `jsRunManager.run(key, code)`。「キャンセル」・外側タップ・戻る → 何もせず閉じる（実行しない）。
4. `JsRunManager.run`（受け付けのとき）：
   1. `state = Running(key, now)`。
   2. （実装での変更）`jsEnabled()` が false なら、state を変えずに false を返す（Running にする前に判定する）。
   3. 空きメモリ < `MIN_FREE_MEMORY_MB` なら `Finished(Error, LowMemory)`。
   4. `scope.launch` で `executor.run(JsRunRequest(id = nextId++, code, workDir = workspace.root.path))`。
   5. 結果が来たら `state = Finished(key, result)`。
5. `JsRunnerClient.run`（本体側。この関数は `suspend`。メインスレッドをブロックしない）：
   1. runner に接続済みでなければ `bindService(BIND_AUTO_CREATE)`。接続を `CONNECT_TIMEOUT_MS` 待つ。待ちきれなければ `unbind` → `bind` を 1 回やり直し、なお駄目なら `Crashed / ConnectFailed`。
   2. 接続したら `MSG_PING` を送り、`MSG_PONG` の `pid` と `version` を保持する（3 秒待つ。来なければ接続失敗）。
   3. `MSG_RUN` を送り、`CompletableDeferred<JsRunResult>`（id で結び付け）を `timeoutMs + CLIENT_MARGIN_MS` の間待つ（三段目）。
   4. `MSG_RESULT` が来たら完了。`status` が `timeout` かつ `cause = WatchdogTimeout`（runner が自分を kill する直前）または `crashed` のときは、`Process.killProcess(pid)` を本体からも送り、`onServiceDisconnected` が来るまで最大 3 秒待ってから戻る（死ぬ直前の runner に次の要求が当たらないため。スパイクの教訓）。
   5. 待ちが切れたら `Process.killProcess(pid)` → 切断を待つ → `Timeout / ClientTimeout`。
   6. 最後の実行から `IDLE_UNBIND_MS` 経過で `unbindService`（`JsExecutor.release`）。
6. `JsRunnerService`（runner 側）：
   1. `MSG_RUN` を受けたら `busy` を確認し、`JsWorkspace.ensureRoot()` と `sanitize()`、`watchdog`（`handler.postDelayed`、`timeoutMs + WATCHDOG_MARGIN_MS`）を登録し、ワーカースレッド `runner-worker` で `QuickJsSession.run(...)` を呼ぶ。
   2. `QuickJsSession.run`：`quickJs { memoryLimit; maxStackSize; evaluationTimeoutMillis }` を作り、`__log`/`__sleep` と `fs` を公開、prelude を評価、コードを `evaluate<Any?>` する。`QuickJsInterruptedException` → Timeout、`QuickJsException` → Error、`OutOfMemoryError` を含む `Throwable` → Error（プロセスは生存）。戻り値は `JsValueFormatter.formatReturn`。
   3. 終わったら `done.compareAndSet(false, true)` が成功した側だけが `MSG_RESULT` を返す（watchdog と競合しても二重返信しない）。
   4. watchdog が先に発火したら、途中までの console を返して 100ms 後に `Process.killProcess(Process.myPid())`。
   5. `onDestroy`（すべての接続が外れたとき）に、`Process.killProcess(Process.myPid())` でプロセスごと終わらせる（常駐メモリを残さない）。
7. 画面：`JsResultCard` が `state` を見て、実行中は「実行中…」+「中止」、`Finished` は結果（7.3）を出す。

### 6.2 「AI に渡す」（JS-09）

決定（2026-10-23）：結果は**入力欄に入れる**。利用者が編集してから、通常の送信ボタンで送る（自動では送らない）。

1. `JsResultCard` の「AI に渡す」→ `ChatViewModel.handoffText(result): Pair<String, Boolean>`（`suspend`。`budget = JsHandoff.budgetChars(settings.params.contextLength)` を読み、`JsHandoff.format(result, budget)` を返す。送信はしない）。
2. `ChatScreen` が、返ってきた本文を入力欄の `text` に入れる。入力欄が空なら置き換え、すでに文字があれば末尾に「空行 + 本文」を足す。入力欄の最大行数（`maxLines = 4`）を超える分は、入力欄の中でスクロールする。
3. `truncated` が true なら、画面に `js_handoff_truncated`（「結果が長いため一部だけ入力欄に入れました」）を出す。
4. 入力欄に入れたら `jsRunManager.dismiss()`（結果カードを閉じる）。生成中でも入力欄への挿入はできる（送信ボタンは既存どおり生成中は「停止」）。モデル未選択でも挿入でき、送信時に既存の `chat_no_model_loaded` が出る。

### 6.3 状態遷移（JS-14）

```
        run()                      結果/タイムアウト/プロセス死/中止
Idle ─────────▶ Running(key) ───────────────────────────▶ Finished(key, result)
 ▲                                                         │
 └──────── dismiss() / cancelAndClear() / 新しい run() ◀───┘
```

| きっかけ | 動作 |
|---|---|
| 実行中に「中止」 | `executor.cancel()` → runner を kill → `Finished(Killed, UserCancel)`。console の途中経過は本体に無いので空（二段目と違い、runner からの返事がないため。表示で「中止したため出力はありません」とは言わず、空欄にする） |
| 実行中にアプリが背景に回る | 実行は続ける（最大 6.5〜7.5 秒で必ず終わる）。結果は `App` が持つ `JsRunManager` に入るので、戻ったとき画面に出る。`InferenceService` のようなフォアグラウンドサービスは使わない |
| 実行中に Activity が破棄される（回転等） | 状態は `App` にあるので、再作成後も同じ結果カードが出る |
| 実行中に本体プロセスが死ぬ | runner は接続が外れて `onDestroy` で自分を終わらせる。結果は失われる（P3）。次の起動は Idle |
| runner が突然死（OOM kill・SIGSEGV 等） | `onServiceDisconnected`/`onBindingDied` で検知し、待っている要求を `Crashed / ProcessDied` で完了させる。再接続は**次の実行のときに** `unbind`→`bind` をやり直す（スパイクの自動再接続は 5 秒以上かかることがあった） |
| 実行中に機能を無効にする | `cancelAndClear()`（Killed / UserCancel の後 Idle） |
| 実行中に会話を切り替える（履歴から開く・新しい会話） | `cancelAndClear()` |
| `Finished` のまま別のコードブロックの「実行」を押す | 確認ダイアログを経て、新しい `run()` が結果を置き換える |
| 実行中に別のコードブロックの「実行」 | ボタンを無効にする（`run()` も false） |

### 6.4 拡張機能画面（有効・無効）

1. スイッチで `setJsEnabled`。無効にしたら 6.3 のとおり `cancelAndClear()`。
2. 有効にしたときは、画面内に説明（できること・できないこと・上限）が常時表示されているので、追加の確認はしない（実行のたびに確認ダイアログがあるため）。

## 7. 画面仕様

### 7.1 チャット画面のコードブロック（`CodeBlockView`）

- 等幅フォント（`FontFamily.Monospace`）、背景は `surface` より一段濃い色、横スクロール可（折り返さない）。行数が 20 を超える場合は、画面内では先頭 20 行までを表示し「すべて表示」で展開（確認ダイアログは常に全文）。
- 右上に言語名（`js`）。`runnable` なら下に「実行」ボタン（`js_run`）。実行中は別ブロックの「実行」は無効。コード長超過は無効のボタンと `js_too_long` の説明。
- `closed = false`（途中で切れた）の JS ブロックは「実行」を出さず、`js_unclosed` の注記を出す。
- 結果カードは、`state` の `key` に一致するブロックの直下にだけ出す（`key = BlockKey(メッセージの位置, ブロックの位置, コードのハッシュ)`）。

### 7.2 確認ダイアログ（`JsConfirmDialog`、JS-02）

`AlertDialog`。毎回表示し、「今後は確認しない」は**作らない**。

- タイトル：`js_confirm_title`
- 本文の上：`js_confirm_message`（AI が書いたコードであること、端末内で実行すること、ネットワークは使えず作業フォルダのファイルだけを読み書きできること、中身を確認してから実行すること）
- コード全文：等幅、縦横スクロール、選択可。最大の高さは画面の約 50%。省略しない。長さ（行数・文字数）を `js_confirm_size` で併記
- 上限の 1 行：`js_confirm_limits`（時間 5 秒など）
- ボタン：「実行」（`js_confirm_run`）、「キャンセル」（`common_cancel`）。外側タップ・戻るはキャンセル扱い。実行ボタンは強調しない（既定のフォーカスを置かない）
- ダイアログを開いている間に `jsEnabled` が false になったら閉じる

### 7.3 結果カード（`JsResultCard`、JS-08）

| 状態 | 表示 |
|---|---|
| Running | 「実行中…」、細い進行バー、「中止」ボタン |
| Ok | 見出し `js_result_ok`（実行時間付き）、「戻り値」（あれば）、「コンソール出力」（あれば。空なら `js_result_no_output`） |
| Error | 見出し `js_result_error`、エラー文（等幅）、console があればその下 |
| Timeout | 見出し `js_result_timeout`（5 秒）、それまでの console。`cause` が Watchdog/Client のときは「応答が止まったため強制的に止めました」を足す |
| Crashed | 見出し `js_result_crashed`（実行用のプロセスが止まった。アプリ本体は無事） |
| Killed | 見出し `js_result_cancelled` |
| LowMemory | 見出し `js_result_low_memory`（メモリ不足のため実行しませんでした） |

- テキストは選択・コピー可。長い結果はカード内でスクロール（最大の高さは画面の約 40%）。
- `consoleTruncated` なら `js_result_truncated`（64KB で切れた旨）。
- ボタン：「AI に渡す」（`js_handoff`。Running 以外で表示。Crashed/Killed でも表示）、「閉じる」（`common_close` が無ければ `js_close`）。

### 7.4 拡張機能画面（`ExtensionsScreen`）

設定画面の「拡張機能」の行（`settings_extensions`。右に `›`）から、経路 `settings/extensions` で開く。

1. 見出し「JavaScript の実行」（`ext_js_title`）+ スイッチ（`ext_js_enable`）+ 説明（`ext_js_desc`）。既定オフ。
2. 「できること」（`ext_js_can`）と「できないこと」（`ext_js_cannot`）の短い箇条書き（ネットワーク・npm・端末の他のファイルにはアクセスできない）。
3. 「上限」（`ext_js_limits`）：`JsLimits` の値を引数に整形（時間、メモリ、出力、ファイル）。
4. 「作業フォルダ」：`ext_js_workspace_usage`（`%1$d` ファイル、`%2$s` 使用量／上限 10MB）、「作業フォルダを空にする」ボタン→確認（`ext_js_clear_confirm`）→ `clearWorkspace()`。実行中は押せない。
5. 注記：`ext_js_license`（QuickJS = MIT、quickjs-kt = Apache License 2.0。MIT の表記は**上流で未確認**。RESULTS.md 9。配布前に確認）

### 7.5 設定画面の入口

`SettingsScreen` の「ダウンロード」の前に `SectionHeader(settings_extensions)` + クリック可能な 1 行（現在の状態を小さく「オン／オフ」と表示）。ライセンス欄（`LicenseRow`）に `QuickJS` と `quickjs-kt` を追加（`LicenseRow("QuickJS (via quickjs-kt)", "MIT License / Apache License 2.0")` の形。MIT の確認が取れるまで文言を仮置きとして未決事項に載せる）。

### 7.6 画面遷移（`AppRoot` の変更）

- 経路 `settings/extensions` を `NavHost` に追加（`Dest` の enum には足さない＝下部ナビに出さない）。
- `dest`（タイトル用）を決める所で、この経路のときは `Dest.Settings` を使いつつ、タイトルは `settings_extensions`、`navigationIcon` に戻る矢印（`nav.popBackStack()`）を出す。
- 下部ナビの `selected` は、`route == d.route` に加えて `d == Dest.Settings` かつ現在の経路が `settings/extensions` のときも true にする。

## 8. セキュリティ（JS-03, 04, 10, 11, 15）

| 項目 | 対処 |
|---|---|
| 実行のたびの同意 | 毎回確認ダイアログ（7.2）。自動実行・「次回から確認しない」・AI 側からの起動は作らない。`run()` は UI の確認済みの経路からしか呼ばない（ViewModel/Manager のどこにも「確認なしの実行」の入口を作らない） |
| ネットワーク | JS にネットワーク API を公開しない。**本体と runner は同じ UID・同じ権限**（`INTERNET` を宣言している）ので、OS による隔離ではない。公開したホスト API だけが JS から見える、という設計上の壁である点を、拡張機能画面と Play の説明では「ネットワークは使えません」と書き、「OS 機能で遮断」とは書かない |
| 作業フォルダ外 | 5.3 の三重防御。絶対パスは明示的に拒否（`EPERM`） |
| TOCTOU | 5.3 の方針。攻撃者になれるのが同じアプリ内だけで、リンク作成 API が無い。実害なしと判断し、リンクをたどらない・一時ファイル + move・掃除で事故を防ぐ。`NOFOLLOW_LINKS` の効果は未確認（U5） |
| ホスト関数での固まり | 別プロセス + 二段目 watchdog + 三段目の kill（JS-10）。今のホスト関数は `fs` だけで短い。ホスト関数を足すときは、上限と時間を必ず持たせる設計規則を `JsLimits` のコメントに書く |
| ホスト側 OOM | `OutOfMemoryError` は runner のワーカースレッドで捕まえ Error で返す。ART のヒープが尽きた runner は、次の実行の前に再起動したほうが安全なので、OOM を捕まえたときは返信後に runner を自分で終わらせる（`Process.killProcess`） |
| JNI のクラッシュ | runner だけが落ち、本体の会話画面は無事（`Crashed`） |
| 本体の保護 | `:runner` は `App.onCreate` で重い初期化をしない（プロセス名で分岐。3.2）。常駐させず、60 秒の無使用で手放す |
| 実行時に取得するコード（JS-11） | アプリはネットワークからコードを取得しない。実行対象は、利用者の画面にある AI の返答のコードブロックだけ（端末内モデルの生成物）。同梱するのはインタプリタ（QuickJS）のみ。**API 連携（19 章、v1.1）の外部 AI の返答には「実行」を出さない**（決定 2026-10-23。P7）。M6 の前に Play の最新ポリシー（実行時にコードを取得・実行することの扱い）を公式ドキュメントで確認する。この設計書は方針の確認までで、ポリシー文言の引用はしない |
| 権限・データ安全性 | 新しい権限なし。`allowBackup=false` のまま。データ収集なしの申告に影響しない（11 章） |
| 生成 AI ポリシー | 既存の「AI 生成」表示と「報告」ボタンは、コードブロックを含む返答にもそのまま出る |
| compileSdk 36 | 13.2 |
| システムプロンプトへの注入 | 結果の文字列（AI に渡す）は、コードが出力した任意の文字列を含む。AI が指示として読む可能性があるが、AI の返答はやはり利用者の確認なしには実行されない（確認ダイアログが最後の砦）。結果は ``` で囲まない（フェンスを閉じられて、AI の返答のコードブロックの解釈を乱されないため。ただし AI への入力なので表示側の問題はない） |

## 9. エラーハンドリングと境界条件

### 9.1 一覧

| 状況 | 動作 |
|---|---|
| コードが空白だけ | 「実行」を出さない（`runnable=false`）。`run()` も false |
| コードが 10 万文字超 | ボタン無効 + `js_too_long`。`run()` も false |
| JS の構文エラー | `QuickJsException` → Error。メッセージ（行番号つき）をそのまま出す |
| `throw` / reject されない Promise | Error。スタック付き |
| 戻り値が関数・Date | `{}`（quickjs-kt が `JsObject`〔中身が空の Map〕で返すため。手順 0 で確認） |
| 戻り値が BigInt・Symbol | quickjs-kt が例外にするので、成功扱いで戻り値を `[unsupported value type]` にする（コードは最後まで動いている） |
| `fs` の例外 | 5.2 |
| runner に接続できない（サービスが起動しない） | Crashed / ConnectFailed。「実行用のプロセスを起動できませんでした」 |
| `MSG_PONG` の version 不一致 | ConnectFailed（アプリ更新直後の古い runner が残っていた場合の保険。runner を kill して次回に再試行） |
| 結果の id が古い | 無視する（`pending` に無い id） |
| `onServiceDisconnected` が実行中でないときに来る | `service = null` にするだけで、再 bind は次の `run()` に回す |
| 実行直前に端末の空きメモリが少ない | LowMemory（7.3） |
| `ActivityManager` が空きを返さない | 実行を許可する（拒否する根拠が無い） |
| 作業フォルダが作れない | `EIO`（`fs` を使ったときだけ）。`ensureRoot` の失敗で全体を止めない |
| 1 回の実行で `fs` を大量に呼ぶ | 容量の計算は呼び出しごとに全走査（最大 100 ファイル・50 ディレクトリ）。5 秒の上限内なら許容（スパイクと同じ）。遅くなったら、runner 内にキャッシュを持つ（U7） |
| 日本語など多バイト文字 | 上限は文字数（コード・出力）とバイト数（ファイル）の混在。ファイルは UTF-8 のバイト数で数える |
| 同時実行 | 受け付けない（1 件ずつ）。`JsRunManager` は `Mutex` を使わず、state の Running を見て弾く（呼び出しは `synchronized` で保護） |

### 9.2 メモリ超過の判別

スパイクでは `QuickJsException` のメッセージが null になることがあり、「メモリ超過」と他のエラーを区別できる保証がない。判別は `message` に `out of memory` を含む場合と、`QuickJsException` で `message == null` の場合を `MemoryLimit` とみなす案（誤判定の可能性あり）。表示は同じ「エラー」（メモリ不足の可能性）なので、`cause` は表示の補足にだけ使い、振る舞いを変えない。確定は手順 0 のあと（U4）。

## 10. テスト方針

### 10.1 単体テスト（JVM、JUnit4。既存の流儀に合わせ `src/test/java/com/mobileailocal/`）

| 対象 | テストクラス | 主なケース | 要件 |
|---|---|---|---|
| `CodeBlockParser` | `CodeBlockParserTest` | ` ```js ` と ` ```javascript `（大文字 `JS` も）→ runnable。` ```python ` と情報文字列なし → runnable でない。閉じ忘れ → `closed=false`。文章とコードが交互。フェンスが 4 個。インデント 3 個まで可、4 個以上は文章。`\r\n`。空のコードブロック。複数のコードブロックの `blockIndex`。` ```js title="x" `。` ``` ` が文中（行頭でない）にある場合は対象外 | JS-07 |
| `JsPathPolicy` | `JsPathPolicyTest` | `a.txt`・`sub/a.txt`・`./a.txt`・`a//b` は許可（正規化後 `a.txt`/`sub/a.txt`/`a.txt`/`a/b`）。`../x`・`sub/../x`・`..`・`/etc/hosts`・`\\a`・`C:\\x`・`a\\b`・NUL・制御文字・空文字（`allowEmpty=false`）は拒否。`list` の空文字と `"."` は許可。非文字列。512 文字超・要素 100 文字超・階層 9 | JS-04, 15 |
| `JsWorkspace` | `JsWorkspaceTest`（`Files.createTempDirectory`） | 書く→読む→一覧（ディレクトリは `/` 付き）→存在→削除。上書き。1,000,000 バイトちょうどは成功、+1 で `EFBIG`（書かれていない）。100 ファイル目は成功、101 目は `ENOSPC`（既存の上書きは可）。ディレクトリ 50/51。合計 10,000,000 の境界（上書きで差し引き）。日本語のバイト数。リンクのファイル/ディレクトリへの read/write/list/remove がすべて `EPERM`（テストでリンクを作れない環境ではスキップ）。`sanitize` がリンクと `.tmp-` を消す。`clear` 後に使用量 0。不正な UTF-8 の読み → `EINVAL` | JS-04, 05, 15 |
| `JsHandoff` | `JsHandoffTest` | `budgetChars`：2048→512、4096→1024、1,000,000→3000。形式（ラベル、空セクションの省略）。各セクションの切り詰めと `[truncated N chars]` の N。status ごとの表記（ok/error/timeout/crashed/cancelled）。`consoleTruncated` の扱い。日本語の文字数 | JS-09 |
| `JsValueFormatter` | `JsValueFormatterTest` | null→null、文字列、整数と小数（`3.0` の表記は手順 0 で確定）、真偽、List、Map、入れ子、循環、深さ 9 | JS-08 |
| `JsRunManager` | `JsRunManagerTest`（偽の `JsExecutor` + `kotlinx-coroutines-test`） | 遷移（6.3 の全行）：Idle→Running→Finished、Running 中の `run` は false、`cancel` で Killed、`dismiss`、`cancelAndClear`。`jsEnabled=false` なら false。コード長超過・空白だけなら false。空きメモリ不足 → LowMemory。結果の id 違いの無視。`executor` が例外を投げても Finished(Crashed) になる | JS-05, 14 |
| `JsProtocol` | （Bundle は JVM で使えないため単体テストでは扱わない。実機の確認で通す） | - | - |

### 10.2 エミュレーター／実機での確認（手動。AVD `pixel_mal`、x86_64。`run-emulator.bat`。実機は arm64 で別途）

| # | 項目 | 手順と期待 | 要件 |
|---|---|---|---|
| 1 | 無効時 | 既定で拡張機能がオフ。コードブロックに「実行」が出ない | JS-06, 07 |
| 2 | 有効化 | オンにすると「実行」が出る。オフに戻すと消える。実行中にオフ → 中止される | JS-06, 14 |
| 3 | 確認 | 「実行」→ ダイアログにコード全文。キャンセル/外側タップ/戻るで実行されない。毎回出る | JS-02 |
| 4 | 正常 | `1+1`、`console.log("あ")`、`JSON.stringify({a:[1,2]})`、トップレベル `await sleep(100)` | JS-08 |
| 5 | エラー | 構文エラー、`throw new Error("x")`、`null.x` | JS-08 |
| 6 | 時間切れ | `while(true){}` が約 5 秒で Timeout。直後に次の実行が成功。`async` 内ループ、`/(a+)+$/` | JS-05, 10 |
| 7 | メモリ | 配列に push し続ける → Error（約 1 秒）、次の実行が成功。`new Uint8Array(500e6)` を try/catch | JS-05 |
| 8 | スタック | 無限再帰 → `stack overflow` を catch できる | JS-05 |
| 9 | 出力 | `for(;;)` で大量の console.log → 64KB で切れて `[output truncated]`、時間切れまで続く | JS-05 |
| 10 | fs | 書く→読む→一覧→削除。`../x`、`/etc/hosts`、`a\\b` が `EPERM`/`EINVAL` で `try/catch` できる。2MB の文字列 → `EFBIG`。101 ファイル → `ENOSPC`。`adb run-as` で作業フォルダ内に外向きのリンクを作って `list/read/write/remove` が `EPERM`、次の実行の開始時に `sanitize` で消える | JS-04, 15 |
| 11 | ネットワーク | `typeof fetch`、`typeof require`、`import("fs")` | JS-03 |
| 12 | 状態の非継続 | 1 回目に `globalThis.x=1`、2 回目に `typeof x` → `undefined`。ファイルは残る | JS-03 |
| 13 | 固まり | 検証用ビルド限定の `__block`（Thread.sleep）を使い、二段目（約 6.5 秒）で Timeout/WatchdogTimeout。watchdog を無効にした検証用ビルドで三段目（約 7.5 秒、ClientTimeout）。いずれも次の実行が成功。**検証用の関数は release に入れない（`BuildConfig.DEBUG` かつ別 sourceSet〔src/debug〕。adb 用の入口 `JsDebugReceiver` も debug のみで、`android:permission=DUMP` を付けて adb shell からだけ使える。暗黙の intent では届かないので `am broadcast -n com.mobileailocal/com.mobileailocal.debug.JsDebugReceiver -a com.mobileailocal.DEBUG_JS --es op ...` と component を指定する）。release の確認手順：`aapt2 dump xmltree` または `apkanalyzer manifest print` で `JsDebugReceiver` が無いこと、`apkanalyzer dex packages` で `com.mobileailocal.debug` が無いこと** | JS-10 |
| 14 | 中止 | `while(true){}` の実行中に「中止」→ Killed。直後の実行が成功 | JS-14 |
| 15 | 背景化 | 実行中にホームへ → 戻ると結果が出ている。回転でも同様 | JS-14 |
| 16 | プロセス死 | `adb shell am kill`/`kill -9`（runner の pid）→ Crashed。次の実行が成功 | JS-10, 14 |
| 17 | 本体プロセスに影響なし | `adb shell ps`で `:runner` の pid が本体と別。`logcat` で `:runner` 側に Room/llama の初期化ログが出ていない | JS-10 |
| 18 | AI に渡す | 結果 → 「AI に渡す」→ 入力欄に本文が入り（自動送信されない）、結果カードが閉じる。編集して送信すると AI が応答。入力済みの文字があるときは末尾に追記。長い出力で切り詰め表示。生成中・モデル未選択でも入力欄には入る | JS-09 |
| 19 | 連続実行 | 10 回連続（正常/時間切れ/中止を混ぜる）で失敗や固まりがない（スパイクで未実施の負荷確認） | JS-10 |
| 20 | 4 言語 | 端末/アプリの言語を 4 つに切り替え、すべての新しい文字列が崩れず出る（長い文での折り返し、`%1$s` の語順） | JS-12 |
| 21 | release | `assembleRelease`（R8）の APK で 4〜6 が動く。`proguard-rules.pro` の追加の要否を確定 | JS-15 |
| 22 | 容量 | release APK（arm64）のサイズを計測し、9.68MB（2026-10-22 の既存値）との差を記録 | JS-13 |
| 23 | 16KB ページ | 実機または 16KB 設定のエミュレーターで起動と実行 | JS-13 |
| 24 | 速度 | 実機（arm64）で初回実行・2 回目以降の時間、runner の PSS を記録 | JS-13 |

## 11. 文字列キー（JS-12）

原文は日本語（17.1）。英語・簡体字・繁体字は、既存のキーと同じく `values/`（英語、既定）・`values-ja/`・`values-zh-rCN/`・`values-zh-rTW/` に同じキーで作る。`%1$s` 形式を使う。用語の対応：実行＝run／运行／執行、拡張機能＝Extensions／扩展功能／擴充功能、作業フォルダ＝workspace／工作文件夹／工作資料夾、コンソール出力＝console output／控制台输出／主控台輸出。簡体字と繁体字は文字変換でなく用語を確認する。`JS` や `fs`、`QuickJS` はそのまま。

| キー | 日本語（原文） | 英語（案） |
|---|---|---|
| `settings_extensions` | 拡張機能 | Extensions |
| `settings_extensions_on` | オン | On |
| `settings_extensions_off` | オフ | Off |
| `ext_js_title` | JavaScript の実行 | Run JavaScript |
| `ext_js_enable` | AI が書いた JavaScript を実行できるようにする | Allow running JavaScript written by the AI |
| `ext_js_desc` | AI の返答にある JavaScript のコードに「実行」ボタンを表示します。実行の前に、毎回コードの内容を確認します。 | Shows a Run button on JavaScript code in AI replies. You review the code every time before it runs. |
| `ext_js_can` | できること：端末の中での計算・文字列処理、作業フォルダのテキストファイルの読み書き | Can do: calculations and text processing on this device, reading and writing text files in the workspace |
| `ext_js_cannot` | できないこと：ネットワークへの接続、作業フォルダ以外のファイルへのアクセス、パッケージの追加 | Cannot do: connect to the network, access files outside the workspace, install packages |
| `ext_js_limits` | 上限：実行時間 %1$d 秒、メモリ %2$d MB、出力 %3$d KB、ファイル 1 件 %4$s、合計 %5$s | Limits: %1$d s run time, %2$d MB memory, %3$d KB output, %4$s per file, %5$s total |
| `ext_js_workspace` | 作業フォルダ | Workspace |
| `ext_js_workspace_usage` | %1$d ファイル・%2$s 使用中（上限 %3$s） | %1$d files, %2$s used (limit %3$s) |
| `ext_js_clear` | 作業フォルダを空にする | Empty the workspace |
| `ext_js_clear_confirm` | 作業フォルダのファイルをすべて削除します。この操作は取り消せません。 | This deletes all files in the workspace. This cannot be undone. |
| `ext_js_license` | JavaScript エンジン：QuickJS（MIT）、quickjs-kt（Apache License 2.0） | JavaScript engine: QuickJS (MIT), quickjs-kt (Apache License 2.0) |
| `js_run` | 実行 | Run |
| `js_too_long` | コードが長すぎるため実行できません（上限 %1$d 文字）。 | This code is too long to run (limit %1$d characters). |
| `js_unclosed` | コードが途中で切れているため実行できません。 | This code is cut off and cannot be run. |
| `js_show_all` | すべて表示 | Show all |
| `js_confirm_title` | このコードを実行しますか？ | Run this code? |
| `js_confirm_message` | AI が書いたコードです。この端末の中で実行されます。ネットワークは使えず、作業フォルダのファイルだけを読み書きできます。内容を確認してから実行してください。 | This code was written by the AI and will run on this device. It cannot use the network and can only read and write files in the workspace. Review it before running. |
| `js_confirm_size` | %1$d 行・%2$d 文字 | %1$d lines, %2$d characters |
| `js_confirm_limits` | 時間 %1$d 秒を超えると止まります。 | It stops after %1$d seconds. |
| `js_confirm_run` | 実行する | Run |
| `js_running` | 実行中… | Running… |
| `js_cancel_run` | 中止 | Stop |
| `js_result_ok` | 実行しました（%1$d ms） | Finished (%1$d ms) |
| `js_result_error` | エラーが出ました | An error occurred |
| `js_result_timeout` | 時間切れで止めました（%1$d 秒） | Stopped: time limit (%1$d s) |
| `js_result_timeout_forced` | 応答が止まったため、強制的に止めました。 | It stopped responding and was forcibly ended. |
| `js_result_crashed` | 実行用のプロセスが止まりました（アプリ本体には影響ありません）。 | The runner process stopped (the app itself is not affected). |
| `js_result_cancelled` | 中止しました | Stopped by you |
| `js_result_low_memory` | 端末のメモリが足りないため、実行しませんでした。 | Not run: the device is low on memory. |
| `js_result_connect_failed` | 実行用のプロセスを起動できませんでした。 | Could not start the runner process. |
| `js_result_return` | 戻り値 | Return value |
| `js_result_console` | コンソール出力 | Console output |
| `js_result_no_output` | 出力はありません | No output |
| `js_result_truncated` | 出力が長いため、途中で切りました。 | The output was cut because it was too long. |
| `js_handoff` | AI に渡す | Send to AI |
| `js_handoff_truncated` | 結果が長いため、一部だけ入力欄に入れました。 | The result was long, so only part of it was put in the message box. |
| `js_close` | 閉じる | Close |

エラー表示の補足：`js_result_*` の見出しの後ろに続くエラー文・console は JS/QuickJS が出した文字列で、翻訳しない。

## 12. 容量目標（設計書 10 章）への影響（JS-13）

### 12.1 見積もり（RESULTS.md 8 と既存の release APK の実測から）

| 項目 | 値 | 根拠 |
|---|---|---|
| 既存の release APK（arm64、未署名、2026-10-22） | 9,679,621 バイト（約 9.2MiB） | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| `libquickjs.so`（arm64） | +924,936 バイト（約 0.9MB。圧縮前） | RESULTS.md 8 |
| quickjs-kt の Kotlin コード（R8 後） | +数百 KB 以内（推測） | RESULTS.md 8（本体との差分は**未計測**） |
| 追加の文字列・画面 | +数十 KB | 推測 |
| 追加後の release APK（見込み） | 約 10.7〜11.3MB | 計算：9.68 + 0.92 + 0.1〜0.5 + 0.05 |
| 目標（アプリ本体〈モデル抜き〉） | 50MB 以下 | 設計書 10 章 |

結論：追加分は約 1.0〜1.6MB で、目標の 50MB に対して 2〜3%。**容量目標は達成できる**（実測は手順の検証で記録。10.2 の 22）。

### 12.2 実行時メモリの影響

- 待機中の runner は存在しない（使うときだけ起動し、60 秒で手放す）。起動中の PSS は約 20〜25MB、上限まで使うと約 92〜97MB（RESULTS.md 8）。端末の RAM が小さい場合（ランク C）に、モデル読み込み済み + runner の追加で、設計書 10 章の「空きメモリ 70%」を超えやすくなる。そのため実行前に空きメモリ 200MB 未満なら実行しない（推測値。実機で調整）。

### 12.3 見直しの提案（設計書.md の更新案。ユーザーの確認後に反映）

1. 10 章「容量」の定義を明確にする：「アプリ本体（モデル抜き）は 50MB 以下」を、「release の arm64 APK のサイズ」で測ると明記する。現状値は約 9.7MB、JS 実行機能を足して約 11MB 見込み。
2. 機能ごとの目安を足す：「JavaScript 実行機能（QuickJS）の追加は +2MB 以内」を目標にする。超えたら R8 の設定と quickjs-kt の依存を見直す。
3. 10 章に「JS 実行中の追加メモリ：最大 100MB（別プロセス）」の行を足し、「空きメモリが 200MB 未満なら実行しない」を明記する。
4. 他の更新対象章：3 章（機能一覧に追加。`未実装`）、6 章（構成図に `:runner` プロセスを追加）、7 章（拡張機能画面）、8.2（DataStore に `js_enabled`）、8.3（`js-workspace/`）、11 章（権限の追加なしの明記、実行の安全策）、12 章（開発計画に追加）、16 章（Play のポリシー確認項目）、20.4（未実装の項目）。

## 13. ビルドと依存（JS-15）

### 13.1 依存

`implementation("io.github.dokar3:quickjs-kt:1.0.15")`。本体の ABI フィルタ（arm64 のみ。エミュレーター時は `-Pabis=x86_64`）に従う。ライセンス：quickjs-kt は Apache-2.0（POM）。QuickJS 本体は MIT と認識しているが同梱ファイルでは**未確認**（配布前に上流で確認）。

### 13.2 compileSdk 36 の問題への対処方針（結果：案 B を採用。13.3 の実施結果を参照。以下は検討時の記録）

quickjs-kt 1.0.1〜1.0.15 の AAR が `minCompileSdk=36` を宣言し、本体（compileSdk 35 + AGP 8.7.3）では `checkAarMetadata` が失敗する。

| 案 | 内容 | 評価 |
|---|---|---|
| A（採用案） | スパイクと同様に、`check*AarMetadata` のタスクだけ無効にする。ただし**全タスクではなく名前を限定**（`checkDebugAarMetadata`、`checkReleaseAarMetadata`、`checkDebugAndroidTestAarMetadata`、`checkReleaseUnitTestAarMetadata` など、本体のビルドの種類に合わせて列挙）。理由と解除条件をコメントに書く | スパイクで debug/release ともビルドでき、API 35 のエミュレーターで動いた。本体の他の依存の検査は残る |
| B | compileSdk 36 + 対応する AGP（8.9 以上）+ Gradle の更新 | 本体の Kotlin/KSP/Room/Compose のバージョン見直しが連鎖する可能性。本機能とは別の作業として、M6 前の更新に回す |

- 案 A の注意：ライブラリが API 36 のクラス・メソッドを実行時に呼ぶと、API 29〜35 の端末で `NoSuchMethodError` になる。スパイクは Android 35 のエミュレーターでしか確認していない。**Android 10（API 29）相当の環境でも確認する**（BlueStacks は `-PminSdk=28` の Android 9 で、API 29 の確認にはならない。AVD で API 29 を作るか、実機で行う。未決事項 U8）。
- 案 B に切り替える条件：手順 0 で Kotlin 2.0.21 では quickjs-kt を読み込めない場合（Kotlin のバージョンを上げる必要があるため、AGP の更新と一緒に実施）。

### 13.3 Kotlin のバージョンの問題

本体は Kotlin 2.0.21（KSP 2.0.21-1.0.28）。スパイクは Kotlin 2.4.10 で検証した。quickjs-kt 1.0.15 が使う Kotlin のメタデータの版が 2.0.21 の読める範囲かは**未確認**。手順 0 で、`app` に依存を足して `assembleDebug` と `assembleRelease` が通るか確かめる。通らなければ、Kotlin・KSP・Compose プラグインを上げる作業（別の指示書）が先になる。

**実施結果（2026-10-23。ユーザー判断で案 B を採用）**：案 A の回避（`-Xskip-metadata-version-check`、`check*AarMetadata` の無効化）は使わず、次のとおり更新した。

| 項目 | 旧 | 新 | 備考 |
|---|---|---|---|
| Kotlin（`kotlin-android`・`plugin.compose`） | 2.0.21 | 2.4.10 | quickjs-kt 1.0.15 とその依存（stdlib 2.4.10、coroutines 1.11.0）の metadata 2.4 を読める最小の版（スパイクで検証済みの版） |
| KSP | 2.0.21-1.0.28 | 2.3.12 | AGP 8.13.2 で動作。AGP 8.7.3 では 2.3.7 まで動き、2.3.8 以降は AGP 9 の API が必要で失敗した |
| AGP | 8.7.3 | 8.13.2 | Gradle 8.14.5 のまま。R8 の metadata 警告を減らすため |
| compileSdk | 35 | 36 | `check*AarMetadata` の回避が不要になった（targetSdk は 35 のまま。SDK Platform 36 を `sdkmanager` で追加） |
| kotlinx-coroutines（android / test） | 1.9.0 | 1.11.0 | quickjs-kt が引き込む版に揃えた |
| R8 | AGP 同梱 | 9.5.28（root の `buildscript` で差し替え） | AGP 8.13.2 同梱の R8 は Kotlin 2.4 の metadata を読めず警告（約 45 件）が出る。差し替えで消え、代わりに「async parsing」の注意が 2 件出る（無害）。APK は 10,670,061 → 10,506,209 バイト |
| Room 2.6.1・Compose BOM 2024.12.01 ほか | - | 変更なし | そのままビルド・動作した |
| `kotlinOptions { jvmTarget }` | - | `kotlin { compilerOptions { jvmTarget } }` | Kotlin 2.4 で旧 DSL がエラーになるため |

確認：クリーンビルド（debug / release）、単体テスト全件、エミュレーター x86_64 でチャット・履歴・モデル・設定・拡張機能画面と JS 実行の主要経路（debug と release〔R8〕）。実機・API 29 は未確認。
## 14. 実装の分担案と実装順

設計書.md 15 章の方針（根幹は Claude 側の implementer、細かいコードは Strata）に合わせる。「implementer」が自分で書く範囲と、Strata に任せる範囲:

### 14.1 implementer が自分で書く（設計の要）

| 範囲 | 内容 |
|---|---|
| プロセス間通信 | `JsProtocol`（メッセージ種別・Bundle の変換・version）、`JsRunnerClient`（bind/unbind、PING/PONG、要求の送受信、三段目の kill、再接続、アイドルの手放し）、`JsRunnerService`（Handler、二段目 watchdog、`done` の競合制御、`onDestroy` でのプロセス終了、OOM 後の自己終了） |
| ライフサイクル | `App.onCreate` のプロセス分岐、`JsRunManager`（状態遷移、cancel、無効化・会話切り替えとの連動）、`ChatViewModel` の変更（システムプロンプト追記、会話切り替え時の cancel） |
| fs のパス検証と容量 | `JsPathPolicy`、`JsWorkspace`（実体の検査、リンクを辿らない、一時ファイル + move、sanitize、容量の判定）、`FsBindings` |
| インターフェース定義 | `JsModels.kt` の型、`JsExecutor`、`JsLimits`（本設計書の 4 章をそのままコード化） |
| ビルド設定 | `build.gradle.kts`（依存、AAR 検査の限定的な回避）、`AndroidManifest.xml`、`proguard-rules.pro`、手順 0 の検証 |
| QuickJS の接続 | `QuickJsSession`、`Prelude`（console・タイマー）、`JsValueFormatter` のテーブル確定（手順 0 の型調査の結果による） |

### 14.2 Strata に任せる（implementer が指示書を書く。見本のファイル・関数名・キーを指定）

| 範囲 | 内容 | 見本 |
|---|---|---|
| 画面 | `CodeBlockView`、`JsConfirmDialog`、`JsResultCard`、`ExtensionsScreen`/`ExtensionsViewModel`、`SettingsScreen` の入口の行、`AppRoot` の経路追加、`ChatScreen` の `Bubble` の描き分け | `ChatScreen.kt` の `AlertDialog`（システムプロンプト、報告）、`SettingsScreen.kt` の Switch の行とライセンス欄 |
| 文字列 | 11 章の全キーを 4 言語で（英語・中国語は機械翻訳 + 用語確認） | 既存の `values*/strings.xml` |
| 結果表示 | `JsResultCard` の状態ごとの描き分け、`JsHandoff.format` | 4.4 の形式と規則 |
| 単体テスト | 10.1 の各テストクラス（`JsRunManagerTest` は偽の `JsExecutor` を使う） | `DataLogicTest.kt`、`ThinkingParserTest.kt` |
| 定型の追加 | `AppSettings.jsEnabled`、DataStore キー、`setJsEnabled`、`CodeBlockParser`（4.3 の規則を詳細に指定する）、`Prompts.JS_ADDENDUM` | `SettingsRepository.kt` の `wifiOnly` |

Strata の指示書には、4 章の型・シグネチャと 5〜7 章の仕様を貼り、設計書の書き換えは禁止とする（15 章）。

### 14.3 実装順

| 手順 | 内容 | 担当 | 完了条件 |
|---|---|---|---|
| 0 | ビルドの検証：`app` に quickjs-kt を足し、`assembleDebug`/`assembleRelease` が通るか。Kotlin 2.0.21 の可否。`evaluate<Any?>` が返す型の一覧（オブジェクト・配列・undefined・BigInt・関数）。ホスト関数の例外が JS で `catch` できるか。`sleep` が評価時間に数えられるか。release（R8）で実行できるか。APK の増分 | implementer | U2〜U6 に答えが出る。通らなければ中断してユーザーへ報告 |
| 1 | 共有の型 `JsModels.kt`、`JsLimits`、`JsPathPolicy`、`JsWorkspace` + そのテスト（Strata にテストを依頼） | implementer（型・検証）+ Strata（テスト） | 単体テストが通る |
| 2 | runner 側：`QuickJsSession`、`Prelude`、`FsBindings`、`JsRunnerService`、`JsProtocol`、Manifest、`App.onCreate` の分岐 | implementer | 10.2 の 4〜12、17 が動く（仮の呼び出し口で） |
| 3 | 本体側：`JsRunnerClient`、`JsRunManager` + `JsRunManagerTest` | implementer（本体）+ Strata（テスト） | 10.2 の 6、13、14、16、19 が動く |
| 4 | 設定：`jsEnabled`、`ExtensionsScreen`、設定画面の入口、AppRoot の経路、文字列（拡張機能分） | Strata | 10.2 の 1、2 |
| 5 | チャット：`CodeBlockParser`（+ テスト）、`CodeBlockView`、`JsConfirmDialog`、`JsResultCard`、文字列（実行分） | Strata（`ChatViewModel` の変更は implementer） | 10.2 の 3〜5、15 |
| 6 | 「AI に渡す」：`JsHandoff`（+ テスト）、`ChatViewModel.handoffText`、`ChatScreen` の入力欄への挿入、システムプロンプトの追記 | Strata（整形・挿入）+ implementer（`handoffText`・システムプロンプト） | 10.2 の 18 |
| 7 | 総合確認：4 言語、release、容量、実機（arm64）、16KB、速度、API 29 環境 | implementer + ユーザー | 10.2 の 20〜24 |
| 8 | `設計書.md` の更新（12.3 の 4）。`実装状態` の列を更新 | implementer（ユーザー確認後） | - |

## 15. 未決事項

| 番号 | 事項 | 判断がつかなかった理由 | 決める時期 |
|---|---|---|---|
| U2 | quickjs-kt 1.0.15 が Kotlin 2.0.21 / AGP 8.7.3 の本体でビルドできるか | **確認済み（2026-10-23）：そのままでは不可**（metadata 2.4 が読めない）。ユーザー判断で案 B を採用し、Kotlin 2.4.10 / KSP 2.3.12 / AGP 8.13.2 / compileSdk 36 へ更新した（13.3） | 手順 0 |
| U3 | `evaluate<Any?>` の戻り値の型（オブジェクト、配列、undefined、BigInt、関数）と `JsValueFormatter` の表 | **確認済み**：オブジェクト・関数・Date=`JsObject`(Map、関数と Date は空)、配列=`ArrayList`、Map=`LinkedHashMap`、undefined/null=null、整数=`Long`（`3.0` も）、その他=`Double`、`Uint8Array`=`UByteArray`、BigInt・Symbol=例外、Error 値=例外 | 手順 0 |
| U4 | メモリ超過を他のエラーと確実に区別する方法 | **確認済み**：メモリ超過の `QuickJsException.message` は文字列 `"null"`（null でも空でもない）のことがある。`"null"`/null/空、または `out of memory` を含む場合を MemoryLimit とする（誤判定の可能性は残る） | 手順 0 |
| U5 | `Files.newByteChannel(NOFOLLOW_LINKS)` が Android で効くか（TOCTOU の強化に使えるか） | 未確認。使わなくても成立する設計にしてある | 任意（実機） |
| U6 | `sleep`/`setTimeout` の待ち時間が QuickJS の評価時間（5 秒）に数えられるか | **確認済み：数えられない**（1 秒の上限で 3 秒 sleep が完走）。実装では `QuickJsSession` が `withTimeout(5 秒)` を足し、sleep を含むコードも一段目相当（InterruptTimeout）で止まる | 手順 0 |
| U7 | 容量計算の全走査が `fs` を大量に呼ぶ JS で遅くならないか | 負荷試験をしていない | 手順 3 の後 |
| U8 | quickjs-kt（API 36 でビルド）が API 29〜34 の端末でも動くか | スパイクは Android 35 のエミュレーターのみ | 手順 7（AVD で API 29 を作る） |
| U9 | 実機（arm64、16KB ページ端末）での動作・速度・メモリ | エミュレーター x86_64 だけで検証 | 手順 7（M5 と合わせてもよい） |
| U14 | QuickJS 本体の MIT 表記の原文 | 同梱ファイルで確認できていない（RESULTS.md 9） | 配布前 |
| U16 | 空きメモリ 200MB という下限と、「コンテキスト長 / 4 文字」という渡す文字数の予算の妥当性 | 推測値。実機で調整が必要 | 手順 7 |
| U18 | AI の返答のコードに含まれる双方向制御文字・不可視文字（Trojan Source）を確認ダイアログでどう見せるか | 将来の検討（今回はコードをそのまま全文表示するだけ。2026-10-23 のレビュー指摘） | M6 の前 |

### 15.1 決定済み（決定 2026-10-23）

| 番号 | 事項 | 決定 |
|---|---|---|
| U1 | 2.1 の前提 P1〜P10 すべて | 採用（P5 は「入力欄に入れる」に変更） |
| U10 | 上限値を利用者が変えられるようにするか | 固定。設定で変えられない |
| U11 | 「AI に渡す」を即送信にするか、入力欄に入れるか | 入力欄に結果を入れ、利用者が編集してから送信（P5 を変更。6.2 に反映） |
| U12 | 実行結果を会話履歴（Room）に保存するか | 保存しない。メモリに直近 1 件（P3 のまま） |
| U13 | v1.1 の API 連携で、外部 AI の返答に「実行」を出すか | 出さない（P7 のとおり） |
| U15 | 作業フォルダの閲覧・取り出し手段 | 作らない（スコープ外。2.2 に明記） |
| U17 | 機能が無効のとき「実行」ボタンを出すか | 出さない（P4 のとおり） |
