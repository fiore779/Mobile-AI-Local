# クラウドAI API連携 設計書（v0.1）

`設計書.md` 19 章「API連携」の具体化です。19 章は「接続先 URL・モデル名・API キーを利用者が入力する汎用方式」だけを定めていました。この文書は、それに次の 4 点を加えて詳細化します。(1) プロバイダー抽象化（連携先を順次増やせる構造）、(2) Google アカウントごとの連携状況の記録、(3) 利用上限・課金エラーの分類と警告、(4) ブラウザを使った連携手順。書式と用語は `設計書.md`（v0.2）と `docs/design/js-runtime.md` に合わせます。

公式ドキュメントの確認日は **2026-10-24**。確認できた事実と、確認できず推測（または実装時に実機で確認が必要）の事柄を、本文中で「確認済み」「未確認」と区別して書きます。

## 1. 概要

### 1.1 目的

クラウドAI（Anthropic・OpenAI・Google Gemini など）を、ローカルモデルと同じチャット画面・同じモデル選択で使えるようにする。API キーは端末内で暗号化して保存し、利用上限などで使えなくなったときは原因ごとの警告を出す。連携の状況（どのプロバイダーを連携済みか）は Google アカウントごとに記録する。既定は従来どおり端末内のみで、連携は利用者が設定したときだけ動く。

### 1.2 要件一覧

「出典」が「指示」のものは 2026-10-24 のユーザー要望、「設計」のものは設計上の必要から足したもの（ユーザー未確認）。

| ID | 要件 | 出典 |
|---|---|---|
| R1 | 設定画面に「API連携」を作り、連携するクラウドAIを順次増やせる構造（プロバイダー抽象化）にする | 指示 |
| R2 | 連携したクラウドAIは、モデル選択（チャット画面のモデルバーとモデル画面）にローカルモデルと並んで出る | 指示 |
| R3 | 使用料上限・課金切れ・レート制限・キー無効などで使えないとき、原因を分類して警告文を出す（4 言語） | 指示 |
| R4 | 連携時の確認はブラウザに移動して行う形にする。可否をプロバイダーごとに整理する | 指示 |
| R5 | 連携状況を Google アカウントごとに記録する（ローカルモデルは端末ごと）。保存先の選択肢を比較し、推奨案を出す | 指示 |
| R6 | ストリーミング応答の表示と、中止（停止ボタン）に対応する | 指示 |
| R7 | 会話履歴（Room）との整合：クラウドの返答も同じ会話・同じテーブルに保存し、途中切断でもそこまでを残す | 指示 |
| R8 | JS 実行機能との関係：クラウドの返答には「実行」を出さない（js-runtime.md の P7 を維持） | 指示 |
| R9 | 既存の生成設定（温度・top_p・最大トークン・コンテキスト長・スレッド数）の扱いを決める | 指示 |
| R10 | プライバシー：初回確認、利用中の目印、プライバシーポリシー・Play データセーフティへの影響を整理する | 指示 |
| R11 | API キーは Android Keystore で暗号化して保存し、ログ・バックアップ・画面に平文を出さない | 指示 |
| R12 | 最初に対応するプロバイダーを比較し、推奨する（認証方式・課金上限エラーの形を含む） | 指示 |
| R13 | 実装者向けの小タスク分割（順序つき）と、ユーザー判断が必要な点の一覧 | 指示 |
| R14 | Room の移行（バージョン 1→2）で既存の会話を失わない | 設計 |
| R15 | 4 言語（日本語・英語・簡体字中国語・繁体字中国語）の文字列 | 設計（設計書 17 章） |

## 2. 前提・スコープ外

### 2.1 前提

| 番号 | 前提 | 関連 |
|---|---|---|
| C1 | 既存の通信は `HttpURLConnection` と `org.json`（`ModelRepository`）。クラウド通信も同じ方式で書き、OkHttp・Retrofit・各社 SDK は入れない（容量目標 50MB 以下、現在 約 10MB。SDK は Kotlin/Android で使えるものが揃わず、SSE と中止の制御も自前のほうが単純） | R6 |
| C2 | `AndroidManifest.xml` には既に `INTERNET` がある。新しい権限は追加しない | R10 |
| C3 | `android:allowBackup="false"` と `data_extraction_rules.xml`（クラウドバックアップ・端末間移行とも全除外）は維持する。これが API キーのバックアップ除外（19.4）を兼ねる | R11 |
| C4 | 最小 Android は 10（API 29）。`AndroidKeyStore` の AES-GCM と StrongBox 試行はこの範囲で使える | R11 |
| C5 | Google サインインは、**API連携を使うときだけ**求める。ローカルモデルだけの利用者には、サインインを一切求めない（`設計書.md` 1 章の「アカウント登録を求めない」は、ローカル利用については維持） | R5, R10 |
| C6 | 画面（Compose）の流儀：`ui/XxxScreen.kt` ＋ `ui/XxxViewModel.kt`、依存は `App` が手動で組み立てる（DI ライブラリなし）、文字列は `res/values*/strings.xml`、ViewModel 内の文言は `Context.localizedString()` | R15 |
| C7 | 対象にするクラウドは「API キー（従量課金・プリペイド）で呼ぶ公式 API」。ChatGPT Plus・Claude Pro・Gemini Advanced などのチャット用サブスクのログインでは、API は使えない（各社とも API は別契約・別課金という一般的な理解。個別ページでの確認はしていない＝未確認）。画面にもこの注意を書く | R4 |

### 2.2 スコープ外

- 画像・音声・ファイルの送信（将来。送るときは送信前確認を追加する。19.5）。
- ツール呼び出し（function calling）、Web 検索などクラウド側の組み込みツール、構造化出力。
- クラウドの思考（thinking）ブロックの表示。受信しても捨てる（6.4）。
- 利用料金の計算・表示（アプリは請求しない。トークン数の表示もしない）。
- 自前サーバー（バックエンド）の運用。Google サインインと Firestore（推奨案）以外のサーバーは作らない。
- API キーのクラウド同期（推奨しない。5.3 の案 C・D）。
- PC のローカル AI（LAN の `http://`）への接続は**対応しない**（確定：連携の接続先は `https://` のみ。暗号化されない通信にキーと会話を載せないため。`設計書.md` 19.3 の「LAN に限り `http://` 可」は廃止。将来、LAN 内の AI への対応が必要になったら、別の設計で再検討する）。

## 3. 変更・追加するファイル

パッケージは `com.mobileailocal`。既存ファイルのパスは `AndroidLocalAI/app/src/main/java/com/mobileailocal/` からの相対。

### 3.1 追加

| パス | 役割 | 要件 |
|---|---|---|
| `domain/CloudModels.kt` | `ProviderType`、`CloudIntegration`、`CloudModelRef`、`ModelSelection`、`ApiErrorKind`、`ApiError`（純粋なデータ型。JVM でテストできる） | R1, R2, R3 |
| `domain/CloudProvider.kt` | プロバイダーのインターフェース `CloudProvider` と、`CloudProviders`（種別→実装のレジストリ） | R1 |
| `domain/OpenAiCompatProvider.kt` | OpenAI 互換形式（`/chat/completions`）のリクエスト組み立て・SSE 解釈・エラー分類 | R6, R3, R12 |
| `domain/AnthropicProvider.kt` | Anthropic Messages 形式（`/v1/messages`）の同上 | R6, R3, R12 |
| `domain/GeminiProvider.kt` | Gemini（フェーズ 2。まずは OpenAI 互換のプリセットで足りる。12.2） | R12 |
| `domain/ApiErrorClassifier.kt` | HTTP 状態・ヘッダー・本文から `ApiError` を作る関数（プロバイダー共通部分と、各社の差分） | R3 |
| `domain/SseParser.kt` | SSE の行を `event`/`data` に切り出す純粋関数 | R6 |
| `domain/RemoteApiEngine.kt` | `InferenceEngine` の実装。選択中のクラウドモデルへ HTTPS で問い合わせ、`Flow<String>` を返す | R6, R2 |
| `data/CloudHttp.kt` | `HttpURLConnection` で POST し、行を 1 本ずつ読む薄いラッパー。中止時に接続を切る | R6 |
| `data/CloudKeyStore.kt` | API キーの暗号化保存（Android Keystore の AES-256-GCM で包んで保存） | R11 |
| `data/CloudIntegrationRepository.kt` | 連携情報の読み書き（Room のローカル写し ＋ アカウントストアとの同期） | R5, R1 |
| `data/AccountRepository.kt` | Google サインイン（Credential Manager）、サインアウト、現在のアカウント `StateFlow` | R5 |
| `data/CloudAccountStore.kt` | アカウント別の連携メタデータの保存先インターフェース。実装は `LocalOnlyAccountStore`（フェーズ 1）と `FirestoreAccountStore`（フェーズ 2） | R5 |
| `data/CloudConsent.kt` | 送信に関する同意の記録（DataStore）。単位はアカウント × プロバイダー種別。`OpenAiCompatCustom` だけは接続先のホストごと（4.7） | R10 |
| `ui/ApiSettingsScreen.kt` / `ui/ApiSettingsViewModel.kt` | 設定内の「API連携」一覧・サインイン状態・削除 | R1, R5 |
| `ui/ApiAddScreen.kt` / `ui/ApiAddViewModel.kt` | プロバイダー選択 → 同意 → ブラウザでキー発行 → 貼り付け → 接続テスト → 使うモデル選択 | R1, R4, R10 |
| `ui/CloudConsentDialog.kt` | 送信先を明示する確認ダイアログ | R10 |
| `ui/CloudErrorText.kt` | `ApiError` → 文言（`Context.localizedString`）と、画面に出すボタン（再入力・設定を開く・再試行） | R3, R15 |
| `src/test/java/com/mobileailocal/` 配下: `SseParserTest.kt`、`ApiErrorClassifierTest.kt`、`OpenAiCompatProviderTest.kt`、`AnthropicProviderTest.kt`、`ModelSelectionTest.kt` | JVM 単体テスト（既存の `*Test.kt` と同じ置き場） | 14 章 |

### 3.2 変更

| パス | 変更内容 | 要件 |
|---|---|---|
| `domain/InferenceEngine.kt` | `ChatMessage` に `val origin: Origin = Origin.Local` を追加（`enum class Origin { Local, Cloud }`）。`InferenceEngine` 自体は変更しない（`load` の `modelPath` は文字列で、クラウドには使わない。6.1 の理由） | R7, R8 |
| `data/Entities.kt` | `MessageEntity` に `@ColumnInfo(defaultValue = "local") val origin: String = "local"` を追加。新エンティティ `CloudIntegrationEntity` と `CloudIntegrationDao` を追加。`AppDatabase` を `version = 2` にし、`Migration(1, 2)` を `App` の `Room.databaseBuilder` に `addMigrations` で渡す。**`fallbackToDestructiveMigration` は使わない**（会話が消える。R14） | R7, R14 |
| `data/SettingsRepository.kt` | `AppSettings` は変更しない。`defaultModelId` に、クラウド選択を表す文字列（5.2）も入れる | R2 |
| `App.kt` | `localEngine`（既存 `LlamaCppEngine`）と `remoteEngine` を持つ。`engine` を `activeEngine`（`selection` に応じて返す）に置き換える。`selectedModelId: StateFlow<String?>` は維持し、`selectedModel: StateFlow<ModelSelection?>` を追加。`selectModel(id: String?)` は維持し、`selectCloudModel(ref: CloudModelRef)` を追加。`AccountRepository`・`CloudKeyStore`・`CloudIntegrationRepository` を組み立てる | R2 |
| `ui/ChatViewModel.kt` | `a.engine` の参照を `a.activeEngine` に変更。クラウド選択のときは `ThermalPolicy` の判定を飛ばし、`modelLoading` を使わない。エラーを `ApiError` で受けて `CloudErrorText` で文言にする。保存する `MessageEntity.origin` を設定する。`models` と並んで `cloudModels` を公開する（6.2, 6.3） | R2, R3, R6, R7 |
| `ui/ChatScreen.kt` | `ModelBar` のドロップダウンに「クラウド」区画を追加。クラウド使用中の目印（雲アイコン＋接続先名）。`runButtonState` で、`origin == Cloud` のメッセージは常に `Hidden`。エラー表示にボタン（再入力など）を足す | R2, R8, R10, R3 |
| `ui/ModelsScreen.kt` / `ui/ModelsViewModel.kt` | 「導入済み」タブの下に「クラウド（API連携）」区画（選択のみ。削除は設定画面へ誘導） | R2 |
| `ui/SettingsScreen.kt` | 「API連携」への行を追加（拡張機能の行と同じ並び）。`onOpenApi` を引数に追加 | R1 |
| `ui/AppRoot.kt` | `EXTENSIONS_ROUTE` 専用の分岐を、設定配下の下位ルートの集合に一般化する（8.3）。ルート `settings/api`、`settings/api/add`、`settings/api/{integrationId}` を追加 | R1 |
| `InferenceService.kt` | 変更なし（クラウド生成中も画面を閉じて続けるため、同じサービスを使う）。ただし Play Console の「フォアグラウンドサービスの用途」の説明と、`AndroidManifest.xml` の `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` の文言を「端末内生成、またはクラウド API の応答受信」に更新する（5.5） | R10 |
| `domain/Prompts.kt` | クラウド選択のときは `JS_ADDENDUM`（JS 実行の説明）をシステムプロンプトに足さない（R8 の裏付け。実行ボタンが出ないのに書き方だけ教えるのを避ける） | R8 |
| `res/values/strings.xml` と `values-ja` `values-zh-rCN` `values-zh-rTW` | 文字列追加（キー名は `cloud_` で始める） | R15 |
| `app/build.gradle.kts` | 依存追加（フェーズ 2 の署名つき機能だけ）：`androidx.credentials:credentials`、`androidx.credentials:credentials-play-services-auth`、`com.google.android.libraries.identity.googleid:googleid`、Firebase BoM ＋ `firebase-auth` ＋ `firebase-firestore`。`com.google.gms.google-services` プラグイン。**バージョンは実装時点の最新安定版を公式で確認して決める**（この設計では数字を決めない）。フェーズ 1 では依存を追加しない | R5 |
| `設計書.md` | 19 章・11 章・14 章を本書に合わせて更新（11 章） | — |
| `store/privacy/*.md`（4 言語）、`store/listing.md` | 9 章に従って改訂（リリース前。`設計書.md` 19.7 の義務） | R10 |

## 4. インターフェース

型名・シグネチャは Kotlin 風の疑似コードで書く（実装コードではない）。

### 4.1 プロバイダー抽象化（R1, R6, R3）

```kotlin
/** プロバイダーの種類。増やすときは、ここに値を足し、CloudProviders に実装を登録する。 */
enum class ProviderType { OpenAi, Anthropic, Gemini, OpenAiCompatCustom }

/** 1 つの連携（接続先）。キーは含まない（キーは CloudKeyStore）。 */
data class CloudIntegration(
    val id: String,                 // UUID 文字列。'/' と ':' を含まない
    val type: ProviderType,
    val displayName: String,        // 画面表示名。既定はプロバイダー名。利用者が変更できる（1〜40 文字）
    val baseUrl: String,            // 既定値は種別ごと(4.1.1)。Custom のみ利用者が入力。末尾の '/' は保存時に除く
    val enabledModels: List<String>,// モデルバーに出すモデル名。1 件以上、最大 10 件
    val lastModel: String?,         // 最後に使ったモデル(enabledModels の要素)
    val createdAt: Long,
    val updatedAt: Long,            // 同期の競合解決に使う(後勝ち)
)

/** 送る内容のスナップショット。 */
data class CloudRequest(
    val baseUrl: String,
    val apiKey: String,             // ログに出さない。toString() を上書きして伏せる
    val model: String,
    val messages: List<ChatMessage>,// 先頭に Role.System を 0 or 1 件。以降は User/Assistant が交互
    val params: CloudParams,
)

/** 送る生成設定。null の項目はリクエストに含めない(9 章)。 */
data class CloudParams(val temperature: Float?, val topP: Float?, val maxTokens: Int)

/** ストリームから取り出した 1 イベント。 */
sealed interface StreamEvent {
    data class Text(val piece: String) : StreamEvent
    data object Done : StreamEvent
    data class Failure(val error: ApiError) : StreamEvent   // 200 応答の途中で届くエラー
}

interface CloudProvider {
    val type: ProviderType
    val defaultBaseUrl: String
    /** ブラウザで開くキー発行ページ。null なら開く操作を出さない(Custom)。 */
    val keyIssueUrl: String?
    /** 画面に出す短い説明(キーの作り方・課金の注意)の文字列リソース id。 */
    val helpTextRes: Int

    /** ストリーミング呼び出しのリクエスト(URL・ヘッダー・本文)を作る。純粋関数。 */
    fun buildStreamRequest(req: CloudRequest): HttpRequestSpec
    /** SSE の 1 イベントを解釈する。純粋関数。 */
    fun parseEvent(event: String?, data: String): StreamEvent?
    /** 接続テストと、モデル一覧取得のリクエスト。 */
    fun buildListModelsRequest(baseUrl: String, apiKey: String): HttpRequestSpec
    fun parseModelList(body: String): List<String>
    /** 失敗した HTTP 応答を分類する。純粋関数。 */
    fun classify(status: Int, headers: Map<String, String>, body: String): ApiError
}

data class HttpRequestSpec(val url: String, val method: String, val headers: Map<String, String>, val body: String?)
```

#### 4.1.1 種別ごとの既定値（確認済みの事実のみ）

| 種別 | 既定の baseUrl | 認証ヘッダー | キー発行ページ |
|---|---|---|---|
| OpenAi | `https://api.openai.com/v1` | `Authorization: Bearer <key>` | `https://platform.openai.com/settings/organization/api-keys`（OpenAI の公式ページ内のリンクで確認） |
| Anthropic | `https://api.anthropic.com/v1` | `x-api-key: <key>`、`anthropic-version: <日付>`、`content-type: application/json`（Anthropic の概要ページで確認。`anthropic-version` の値は実装時にドキュメントの最新の例を使い、定数 `ANTHROPIC_VERSION` に置く） | Console の API キー画面。Anthropic の Console は現在 `platform.claude.com`（概要ページの記述で確認）だが、キー画面の正確な URL は未確認。実装時に実際に開いて確認し、確認できなければ `https://platform.claude.com/` を開く |
| Gemini | OpenAI 互換: `https://generativelanguage.googleapis.com/v1beta/openai`（`/chat/completions`、`Authorization: Bearer <GEMINI_API_KEY>`。公式の互換性ページで確認）。ネイティブ API はフェーズ 2 | 同左 | `https://aistudio.google.com/apikey`（公式ページ内のリンクで確認） |
| OpenAiCompatCustom | 利用者入力 | `Authorization: Bearer <key>`（キー空欄なら付けない） | なし |

エンドポイントの結合は、`baseUrl + "/chat/completions"`（OpenAI 互換）、`baseUrl + "/messages"`（Anthropic）、`baseUrl + "/models"`（モデル一覧。OpenAI・Anthropic・Gemini 互換とも `GET`。Anthropic のモデル一覧は未確認なので、実装時に確認し、失敗したら手入力にフォールバック）。

### 4.2 エラーの型（R3）

```kotlin
enum class ApiErrorKind {
    AuthInvalid,        // キーが無効・失効・未設定(401 など)
    PermissionDenied,   // キーに権限なし・地域制限・プロジェクト停止(403)
    BillingRequired,    // クレジット切れ・支払い情報の問題・課金未設定(402 など)
    SpendLimit,         // 設定した/契約上の月額上限に到達
    QuotaExceeded,      // 日次などの無料枠・クォータ超過
    RateLimited,        // 短時間の回数制限(待てば直る)
    Overloaded,         // 提供側の混雑(Anthropic 529、OpenAI/Gemini 503 など)
    ModelNotFound,      // モデル名が無効(404)
    ContextTooLong,     // 入力が長すぎる(413 や 400 の長さ超過)
    InvalidRequest,     // 上記以外の 400(パラメーター不正など)
    ContentBlocked,     // 安全性などで応答が拒否された
    ServerError,        // 提供側の 500 系
    Timeout,            // 接続・読み取りの時間切れ(クライアント側)
    Network,            // 接続できない(クライアント側)
    StreamCut,          // 応答の途中で切れた(クライアント側)
    Unknown,
}

data class ApiError(
    val kind: ApiErrorKind,
    val httpStatus: Int?,            // クライアント側の種別は null
    val providerCode: String?,       // 例: "credit_balance_exhausted"。ログと「詳細」表示用
    val providerMessage: String?,    // 提供側の message 原文。「詳細」でのみ表示し、本文に混ぜない
    val retryAfterSec: Long?,        // Retry-After ヘッダーがあれば
    val resetAt: String?,            // 上限の解除日時が本文にあれば(Anthropic の月額上限など。表示用の原文)
) : Exception()
```

### 4.3 `RemoteApiEngine`（R2, R6）

```kotlin
class RemoteApiEngine(
    private val keys: CloudKeyStore,
    private val integrations: CloudIntegrationRepository,
    private val http: CloudHttp,
) : InferenceEngine {
    /** 現在のクラウド選択。App が selectCloudModel で設定する。null なら isLoaded は false。 */
    var current: CloudModelRef? = null
    override val isLoaded: Boolean            // current != null かつキーが保存済み
    override suspend fun load(modelPath: String, params: GenerationParams, onProgress: (Float) -> Unit) // 使わない。UnsupportedOperationException
    override suspend fun unload()             // current = null にするだけ
    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<String>
    // 失敗は ApiError を throw する(Flow を collect している側の catch で受ける)。
}
data class CloudModelRef(val integrationId: String, val model: String)
```

- `load` を使わない理由：`InferenceEngine.load` の第一引数は「モデルファイルのパス」で、進捗もローカル専用の概念。クラウド選択はファイルを読まないので `App.selectCloudModel` が `current` を設定するだけにする。
- `generate` が `ApiError` を投げる。`CancellationException` はそのまま再スローする（既存の `ChatViewModel` の流儀）。

### 4.4 モデル選択（R2）

```kotlin
sealed interface ModelSelection {
    data class Local(val modelId: String) : ModelSelection
    data class Cloud(val ref: CloudModelRef) : ModelSelection
}
fun ModelSelection.encode(): String     // Local → modelId そのまま(後方互換)。Cloud → "cloud:" + integrationId + "/" + model
fun decodeModelSelection(s: String?): ModelSelection?   // "cloud:" で始まれば Cloud(最初の '/' で分割)。それ以外は Local。null/空は null
```

`defaultModelId`（DataStore）と `ConversationEntity.modelId`（既に Room にあり未使用の列）にこの文字列を入れる。ローカルの id は `<repo>__<file>.gguf` の形（`ModelRepository.installedName`）で、`cloud:` で始まることはない。

### 4.5 アカウント（R5）

```kotlin
data class GoogleAccount(val uid: String, val email: String?, val displayName: String?)

class AccountRepository(context: Context) {
    val current: StateFlow<GoogleAccount?>
    /** Credential Manager で Google サインイン。Activity が必要(画面から渡す)。失敗は AccountError。 */
    suspend fun signIn(activity: Activity): GoogleAccount
    suspend fun signOut()
    /** アカウントと、アカウント側に保存した連携メタデータを削除する(Play のアカウント削除要件。5.5)。 */
    suspend fun deleteAccountAndData()
}
sealed class AccountError : Exception() { object Cancelled; object NoCredential; class Network; class Other(msg: String?) }
```

```kotlin
/** アカウント別の連携メタデータの保存先。キーは持たない。 */
interface CloudAccountStore {
    suspend fun load(uid: String): List<CloudIntegration>
    suspend fun upsert(uid: String, item: CloudIntegration)
    suspend fun delete(uid: String, integrationId: String)
    suspend fun deleteAll(uid: String)
}
```

`uid` の定義：フェーズ 1（Firebase なし）は Google ID トークンの `sub`（Google アカウント固有の不変の ID）。フェーズ 2 は Firebase Auth の `uid`。**移行時に値が変わる**ため、フェーズ 1 のデータは移行しない（フェーズ 1 は内部テスト用に限る。12.1 の推奨は最初からフェーズ 2 の ID に揃える案）。

### 4.6 キーの保存（R11）

```kotlin
class CloudKeyStore(context: Context) {
    suspend fun put(uid: String, integrationId: String, apiKey: String)
    /** キーが無い、または復号できない(Keystore が無効になった等)なら null。例外は投げない。 */
    suspend fun get(uid: String, integrationId: String): String?
    suspend fun has(uid: String, integrationId: String): Boolean
    suspend fun delete(uid: String, integrationId: String)
    suspend fun deleteAllFor(uid: String)
}
```

### 4.7 同意（R10）

```kotlin
class CloudConsent(context: Context) {
    /**
     * 同意済みの版。0 は未同意。本文を変えたら CONSENT_VERSION を上げ、再同意を求める。
     * scope: OpenAiCompatCustom のときは接続先のホスト（接続先が変わったら再度同意を求める）。それ以外は空文字。
     * 組み立ては CloudInputs.consentScope(type, baseUrl)。
     */
    suspend fun acceptedVersion(uid: String, type: ProviderType, scope: String = ""): Int
    suspend fun isAccepted(uid: String, type: ProviderType, scope: String = ""): Boolean   // acceptedVersion >= CONSENT_VERSION
    suspend fun accept(uid: String, type: ProviderType, scope: String = "", version: Int = CONSENT_VERSION)
    suspend fun revoke(uid: String, type: ProviderType, scope: String = "")
}
const val CONSENT_VERSION = 1
```

## 5. データ構造

### 5.1 Room（R14, R7, R5）

| テーブル | 変更 | 内容 |
|---|---|---|
| `message` | 列追加 | `origin TEXT NOT NULL DEFAULT 'local'`（`local` / `cloud`）。クラウドの返答に付ける。ユーザー発言と assistant 返答の両方に、そのときの選択の `origin` を付ける |
| `conversation` | 変更なし | 既存の `modelId`（`String?`、未使用）に `ModelSelection.encode()` の値を、会話の作成時と選択変更時に入れる。表示は「最後に使った選択」の記録のみで、再開時に自動選択はしない（意図せずクラウドに送らないため） |
| `cloud_integration`（新規） | 新規 | 下記 |

`cloud_integration`（連携メタデータのローカル写し。アカウントごとに行を持つ）:

| 列 | 型 | 内容 |
|---|---|---|
| `uid` | TEXT | アカウント ID（主キーの一部） |
| `id` | TEXT | 連携 ID（主キーの一部。UUID） |
| `type` | TEXT | `ProviderType.name` |
| `displayName` | TEXT | 表示名 |
| `baseUrl` | TEXT | 接続先 URL |
| `enabledModelsJson` | TEXT | モデル名の JSON 配列 |
| `lastModel` | TEXT? | 最後に使ったモデル |
| `createdAt` / `updatedAt` | INTEGER | ミリ秒 |
| `syncState` | TEXT | `local`（未送信）/ `synced` / `deleted`（削除待ち）。フェーズ 1 では常に `local` |

主キーは (`uid`, `id`)。

`Migration(1, 2)` が実行する SQL（実装者はこのとおり書く）:

1. `ALTER TABLE message ADD COLUMN origin TEXT NOT NULL DEFAULT 'local'`
2. `CREATE TABLE IF NOT EXISTS cloud_integration (uid TEXT NOT NULL, id TEXT NOT NULL, type TEXT NOT NULL, displayName TEXT NOT NULL, baseUrl TEXT NOT NULL, enabledModelsJson TEXT NOT NULL, lastModel TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, syncState TEXT NOT NULL, PRIMARY KEY(uid, id))`

Room が生成する期待スキーマとの差（`@ColumnInfo(defaultValue = ...)` の有無、列の NOT NULL）で起動時に `IllegalStateException: Migration didn't properly handle` が出るため、`MessageEntity.origin` には `@ColumnInfo(defaultValue = "local")` を付ける（既存の `tokenCount` が `defaultValue = "0"` なのと同じ書き方）。エンティティの定義と SQL の一致は、実機（または更新インストール）で確認する（14 章の R14）。

### 5.2 DataStore（設定）

| キー | 型 | 内容 |
|---|---|---|
| `default_model`（既存） | String? | 4.4 のエンコード文字列。クラウド選択もここに入る |
| `cloud_consent_<uid>_<type>`（`OpenAiCompatCustom` のみ末尾に `_<ホスト>`） | Int | 同意済みの版。カスタム接続先は、ホストが変わると別の同意として扱う（DataStore 名 `cloud`） |
| `cloud_intro_seen_<uid>` | Boolean | 「API連携の説明」を見た |
| `cloud_history_notice_<convId>` | 保存しない | 会話途中でクラウドに切り替えたときの確認は、画面のメモリ上だけ（6.2） |

### 5.3 アカウント別の保存先の比較と推奨（R5, R11）

要件：「連携状況を Google アカウントごとに記録する」。ここで問題になるのは、**何を**保存するかと、**どこに**置くか。

| 案 | 保存先 | 何を持つか | 長所 | 短所・リスク |
|---|---|---|---|---|
| A | 端末内のみ（Room） | 連携メタデータ。アカウント ID（`sub`）で行を分ける | サーバー不要。運営が個人情報を持たない。Play のデータセーフティも小さく済む | 端末を替える・再インストールすると記録が消える。「Google アカウントごと」は端末内の見かけの分離にすぎない |
| **B（推奨）** | Firebase Auth ＋ Firestore | **キーを含まない**メタデータ（プロバイダー種別・表示名・URL・使うモデル・更新日時）。キーは各端末の Keystore のまま | 別端末でも「連携済み」が復元され、キーの再入力だけで使える。キーの漏えい面が増えない。Firestore の無料枠（Spark）で足りる規模。セキュリティルールで本人のみ読み書き | Firebase SDK が増える（容量増。目標 50MB 以内を実測で確認）。運営が Google アカウント ID を持つので、データセーフティとポリシーの更新が必要。Firebase プロジェクトの用意が必要 |
| C | Firestore ＋ **端末側で暗号化したキー** | 案 B ＋ 暗号文のキー | 新端末でキー入力も不要にできる | 暗号鍵をどこに置くか（利用者のパスフレーズか、端末間の鍵共有）が必要で、設計・サポートが重い。紛失・忘却で復旧不能。事故のとき被害が大きい |
| D | Firestore にキーを平文（または運営の鍵で暗号化） | キー | 実装は最も簡単 | **採用しない**。秘密鍵（課金に直結）を運営のサーバーに置くことになり、漏えい時の責任・審査上のリスク・ポリシー負担が大きい |
| E | Google Drive の appDataFolder（利用者本人の Drive の隠し領域） | 案 B と同じメタデータ | 運営のサーバーにデータが残らず、プライバシー面で最も軽い | Drive API の同意（`drive.appdata` スコープ）と OAuth クライアントの審査設定が必要。同期・競合の実装が自前。Firestore より実装が重い |

**推奨：案 B（メタデータのみ Firestore、キーは端末の Keystore のみ）。** 理由は、(1) 要件の「アカウントごとに記録」を端末をまたいで実現でき、(2) 秘密情報（キー）をクラウドに置かない、(3) 必要な設定が Firebase の標準機能で済む、の 3 点。案 A は実装が軽いので**フェーズ 1 の足場**として使い、`CloudAccountStore` の実装を `LocalOnlyAccountStore` → `FirestoreAccountStore` に差し替えて案 B に進む（4.5）。ユーザーが「運営のサーバーに一切データを置きたくない」と判断する場合は案 E を選ぶ（11 章 U2）。

Firestore の構造（案 B）:

- コレクション：`users/{uid}/integrations/{integrationId}`（`uid` は Firebase Auth の uid）。
- 文書の項目：`type`（文字列）、`displayName`、`baseUrl`、`enabledModels`（文字列配列、最大 10）、`lastModel`、`createdAt`、`updatedAt`（数値のミリ秒）、`schemaVersion`（数値、初期値 1）。**キー・トークン・会話内容の項目は作らない。**
- セキュリティルール（実装者がそのまま登録する要件）：
  - `users/{uid}/**` は `request.auth != null && request.auth.uid == uid` のときだけ read / write を許可する。
  - write は、許可した項目名だけを含むこと（上記の項目以外があれば拒否）、`enabledModels` が 10 件以下であること、`displayName` が 40 文字以下であることを検証する。
  - それ以外のパスは全拒否。
- 競合解決：`updatedAt` の新しいほうを採用（後勝ち）。削除は文書を削除し、他端末へは次回の読み込みで反映する（削除の伝播の遅れは許容。ローカルは `deleted` を保持して送る）。
- 接続先 URL は `https://` のみ（確定）。同期してよい URL は `CloudInputs.syncable(baseUrl)` ＝ `https://` で、ユーザー情報（`user:pass@`）・クエリ・フラグメントを含まないものだけ。これを満たさない連携は保存自体を拒否する（`http://` は入力時点で拒否されるため、「端末内のみ」の連携は存在しない）。

### 5.4 キーの暗号化保存（R11）

- Android Keystore に AES-256 の鍵を 1 つ作る（別名 `mal_cloud_key_v1`、用途 ENCRYPT/DECRYPT、ブロックモード GCM、パディングなし、`setRandomizedEncryptionRequired(true)` のまま）。可能なら `setIsStrongBoxBacked(true)` で作り、`StrongBoxUnavailableException` なら通常の Keystore で作り直す。ユーザー認証要求（生体など）は付けない（付けるとバックグラウンド生成で復号できないため。強化は将来の検討事項）。
- 保存は、`filesDir/cloud-keys/` に連携ごと 1 ファイル。名前は `<uid のハッシュ>_<integrationId>.bin`（`uid` 自体はファイル名に出さない。ハッシュは SHA-256 の先頭 16 バイトを 16 進）。中身は `[版 1B][IV 12B][暗号文＋GCM タグ]`。AAD（追加認証データ）に `uid + ":" + integrationId` を使い、別の連携にファイルを入れ替えても復号に失敗するようにする。
- 復号に失敗したとき（`AEADBadTagException`、`KeyPermanentlyInvalidatedException`、鍵なし）：そのファイルを削除し、`get` は null を返す。画面は「キーを再入力してください」（`AuthInvalid` と同じ扱い）。例外・キーの値・暗号文は、ログ（`Log`）にも出さない。
- `security-crypto`（`EncryptedSharedPreferences`）は非推奨のため使わない。
- 画面に出す表示は先頭を伏せた形（`••••` ＋ 末尾 4 文字）。コピー・共有の操作は作らない。入力欄は `visualTransformation = PasswordVisualTransformation()`、`KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password)`。入力欄に貼り付けた直後、キーの前後の空白と改行を除く。

### 5.5 Play 申告・ポリシーへの影響（R10）

これは設計書 19.7 の義務を具体化した、リリース前の作業リスト。

| 項目 | 内容 |
|---|---|
| データセーフティ：個人情報 | フェーズ 2（Firebase Auth）で、メールアドレス・ユーザー ID（Google アカウント）を**収集する**と申告。目的は「アプリの機能（連携状況の保存）」。第三者共有の区分と暗号化（転送中）の区分は Play の最新の定義を確認して選ぶ（19.7 の方針どおり） |
| データセーフティ：会話内容 | 会話は運営に送られない。ただし利用者の操作でクラウド事業者（Anthropic・OpenAI・Google）に送信される。Play の「共有」の定義に該当するかを、公式の最新定義で確認して申告する（**ここは未確認**。該当しうる前提で、「利用者が開始した、利用者が選んだサービスへの送信」の例外条件も確認する） |
| アカウント削除 | アカウントの作成を伴うアプリには、アプリ内の削除導線と、アプリ外（Web）の削除依頼の URL が求められる（Play のアカウント削除ポリシーの理解。**適用の可否と要件は公式で再確認が必要**）。`AccountRepository.deleteAccountAndData()` と、設定の「アカウントとクラウド上の連携情報を削除」ボタンを用意する |
| プライバシーポリシー | 4 言語とも、(1) Google サインインと、保存する項目（メールアドレス、ユーザー ID、連携のメタデータ。キー・会話は含まない）、(2) クラウドに会話を送る場合の送信先と範囲（直近の履歴すべて）、(3) 削除方法を追記する。現行の「アカウント登録を求めません」「会話を送信しません」の記述（`store/privacy/privacy-policy.*.md` 1・3 節）は書き換える。末尾 9 節の予告文を、本文に反映する |
| フォアグラウンドサービス | 用途の説明を「端末内の AI 生成、またはクラウド AI の応答受信を、アプリが背面にあっても続ける」に更新 |
| ストア説明 | 「外部 AI を使う場合は、接続先の利用規約とプライバシーポリシーが適用される」を入れる（19.7） |
| Firebase 側 | Firebase プロジェクトの Analytics は**有効にしない**（`設計書.md` 11 章の「解析 SDK は入れない」を維持）。`google-services.json` に Analytics 用の設定が入っていないか確認する |

## 6. 処理フロー

### 6.1 エンジンの切り替え（R2）

`ChatViewModel` は今 `a.engine`（常に `LlamaCppEngine`）を使う。これを `a.activeEngine` に置き換える。

1. `App.selectModel(id: String?)`（既存）：ローカル選択。`remoteEngine.current = null`、`_selectedModel = Local(id)` にしてから、既存の読み込み処理を行う。
2. `App.selectCloudModel(ref)`（新規）：`localEngine.unload()`（ローカルの選択を解除。メモリを空ける）、`_selectedModelId = null`、`remoteEngine.current = ref`、`_selectedModel = Cloud(ref)`、`settings.setDefaultModel(encode)`。キーが無い（`keys.has` が false）なら選択せず、`modelError` に「キーが未設定です」を出す。
3. `activeEngine`：`_selectedModel` が `Cloud` なら `remoteEngine`、それ以外は `localEngine`。
4. 起動時の復元（既存の `defaultModelId` の復元処理）：`decodeModelSelection` が `Cloud` で、連携が Room に存在し、キーが保存されていれば `selectCloudModel`。どれか欠けていれば未選択に戻す（ローカルと同じ扱い）。**ネットワークには何もしない**（起動時に問い合わせない）。
5. `ThermalPolicy` と、`modelLoading` のゲージは、`Cloud` のときは使わない。`DeviceMonitor.recordGeneration`（トークン/秒の表示）は `Cloud` のときは記録しない（通信速度で表示が上書きされるため）。

### 6.2 送信（R6, R7, R10）

`ChatViewModel.generate` の既存の流れ（会話作成 → 空の返答行を挿入 → `engine.generate().collect` → 2 秒ごとに保存 → `finally` で最終保存）を**そのまま使う**。差分は次のとおり。

1. 事前判定：`a.activeEngine.isLoaded` が false なら既存のエラー（モデル未選択）。`Cloud` のとき `ThermalPolicy` の判定は飛ばす。
2. **初回の送信確認（会話途中の切り替え）**：選択が `Cloud` で、`state.messages` に `origin == Local` のメッセージが 1 件以上あるときは、その会話でクラウド選択後の最初の送信の前に確認ダイアログ「これまでの会話も ○○ に送られます。送りますか？」を出す（`generate` を開始する前に `ChatScreen` が出し、「送る」で `send` を呼ぶ）。キャンセルしたら送信しない。これはメモリ上の状態で、会話を開き直すと再度出る。
3. 挿入する `MessageEntity.origin` ＝ 現在の選択（ユーザー発言・assistant 返答とも）。
4. 履歴の組み立て（既存）：`Prompts.DEFAULT_SYSTEM` ／ 会話のシステムプロンプトを先頭の `System` に置く。`jsEnabled` でも、`Cloud` のときは `JS_ADDENDUM` を足さない（R8）。過去の assistant 返答は `ThinkingParser.answerOf` を通す（既存）。
5. **履歴の切り詰め**：ローカルの `contextLength` は使えない（クラウド側の上限はモデルごとに違う）。送信前に次の手順で絞る。(a) `System` と最新のユーザー発言は必ず残す。(b) 古い発言から順に落とし、合計が `CLOUD_MAX_INPUT_CHARS = 60000` 文字以下になるまで続ける（文字数での目安。トークン換算はしない）。(c) 落とした場合は、画面に注記「古いやり取りは送っていません」を出す（`ChatUiState` に `trimmedNotice: Boolean` を足す）。サーバーが `ContextTooLong` を返したら、この上限を半分にして 1 回だけ自動で再送する。値の根拠は未確認で、実機で調整する（未決 O3）。
6. `RemoteApiEngine.generate` の手順：
   1. `current` から連携・キーを取得（`keys.get`。null なら `ApiError(AuthInvalid)`）。
   2. `CloudParams` を作る（9 章）。
   3. `provider.buildStreamRequest(...)` で `HttpRequestSpec` を作る。
   4. `CloudHttp` で接続する。時間切れは 3 種類に分け、`ApiError.timeoutPhase` と待った秒数を「詳細」に出す。(a) 接続（Connect）15 秒。(b) 最初の応答が来ない（FirstByte）**120 秒**：リクエストを送ってから、本文の最初のバイトが来るまで。推論（thinking）型のモデルは最初の文字まで 60 秒を超えることがあるため、ストリーム途中より長くした。(c) 途中で止まった（Idle）60 秒：最初のバイトの後に、データが来ない時間。`HttpURLConnection` の `readTimeout` は接続後に切り替えられないため、見張りのコルーチン（250ms ごと）が時間を数え、超えたら接続を切る（`CloudTimeoutState`）。取り消しも同じ見張りが接続を切るので、待っている間でも中止がすぐ効く。HTTP 504/408 は提供元の原文を詳細に出す。待っている間（最初の文字が来るまで）、チャット画面に「応答を待っています…（モデルが考えている場合があります）」と経過秒を出す。時間切れは自動では再送しない。
      - Gemini の OpenAI 互換の推論量：公式（`ai.google.dev/gemini-api/docs/openai`、2026-10-24）に、`reasoning_effort`（`minimal` / `low` / `medium` / `high`）が Gemini の思考レベルに対応すること、未指定ならモデルの既定になること、`"none"` は 2.5 系でのみ使えること（Gemini 2.5 Pro と 3 系は思考を切れない）が書かれている。そこで、**Gemini のプリセットだけ**に、設定（API連携画面）で `minimal`〜`high` を送れるようにした（既定は「送らない」）。本文に reasoning / thinking の語を含む 400 が返ったら、指定を外して再送し、そのモデルは送らない。`"none"` は対応モデルが限られるため選択肢に入れていない。
   5. 応答コードが 200 以外なら、本文（最大 64KB）を読み、`provider.classify(...)` の結果を投げる。
   6. 200 なら、1 行ずつ読み、`SseParser` で `event`/`data` にして `provider.parseEvent` に渡し、`Text` を `emit`、`Done` で完了、`Failure` なら投げる。
   7. ストリームが `Done` なしで終わった（EOF）なら `StreamCut` を投げる。
7. 中止：`stop()` → `job.cancel()`（既存）。`CloudHttp` は、協調キャンセルされたとき `HttpURLConnection.disconnect()` を呼んで読み取りを中断する（`coroutineContext.job.invokeOnCompletion` で登録。ブロッキング読み取りは coroutine のキャンセルだけでは止まらないため）。キャンセル後、それまでの文章は既存の `finally` で保存される。
8. `InferenceService.start/stop` は既存どおり呼ぶ（クラウド中も背景で受信を続ける）。
9. 自動再送：`RateLimited` と `Overloaded` と `ServerError`（500/503/529）で、**最初の文字を受け取る前**に限り、2 秒、4 秒の待ちで最大 2 回再送する（`Retry-After` があればそれを優先、上限 30 秒）。`BillingRequired` `SpendLimit` `QuotaExceeded` `AuthInvalid` `PermissionDenied` `InvalidRequest` `ModelNotFound` は再送しない（各社の公式も、課金・上限・認証の失敗は再試行で直らないと明記）。文字を受け取った後の失敗は再送せず `StreamCut` 等で終える（二重に課金されうるため、自動では続きを生成しない）。

### 6.3 エラーの表示（R3）

1. `ChatViewModel` が `ApiError` を受けたら、`state.error` に文言を入れる代わりに、`ChatUiState.cloudError: ApiError?` に入れる（ローカルの `error: String?` は従来どおり）。`CloudErrorText` が、種別から文言（7.1 の表）とボタンを決める。
2. 返答の途中で失敗したときは、そこまでの文章を保存したまま、吹き出しの下に警告を出す（`StreamCut` は「途中で切れました。続きを再生成できます」＋ 既存の再試行ボタン `retry()`）。
3. 警告に付けるボタン（`ChatScreen` 内、既存のエラー表示行に足す）:
   - `AuthInvalid`：「キーを入れ直す」→ 設定の該当連携へ。
   - `BillingRequired` `SpendLimit` `QuotaExceeded`：「設定を開く」（連携の詳細画面）と「提供元の請求ページを開く」。後者は固定 URL を持たない（変わりやすいため、プロバイダーのコンソールのトップを開く）。再試行ボタンは**出さない**。
   - `RateLimited` `Overloaded` `ServerError` `Timeout` `Network`：「再試行」。
   - `ModelNotFound`：「モデルを選び直す」。
4. 上限・課金系のエラーを受けた連携は、その実行中の間だけ（アプリのプロセスが生きている間）、モデルバーの該当モデルに「⚠」を付け、選択時に再度警告を出す（`App` のメモリ上の `Map<integrationId, ApiError>`。永続化しない：上限は復旧しうるため、次回は再度試す）。
5. `providerMessage`（提供側の原文）は「詳細」を開いたときだけ表示する（原文には英語の説明や ID が含まれるため）。キー断片が含まれることはない前提だが、表示の前に `sk-` `AIza` などで始まる 20 文字以上の連続文字列は `••••` に置換する（保険）。

### 6.4 ストリーミングの解釈（R6）

共通：SSE は `data: ...` の行が 1 イベント（`event: ...` 行があればその名前つき）で、空行が区切り。複数行の `data:` は改行で連結する。`:` で始まる行（コメント）は捨てる。

- OpenAI 互換（`/chat/completions`、`stream: true`）：`data: {json}` が続き、最後が `data: [DONE]`。文字は `choices[0].delta.content`（文字列。`null` や欠落は無視）。`choices` が空の配列のイベントは無視（使用量だけのイベント）。`reasoning_content` など未知の項目は無視（思考は捨てる）。エラーは 200 のまま `data: {"error": {...}}` で届くことがある（この形は OpenAI 互換サービスの慣習。**未確認**：実装時に各社で再現を試し、`Failure` に変換する）。
- Anthropic（`/v1/messages`、`stream: true`）：イベントの流れは `message_start` → （`content_block_start` → `content_block_delta`* → `content_block_stop`）* → `message_delta` → `message_stop`（確認済み。Anthropic のストリーミングのページ）。文字は `content_block_delta` の `delta.type == "text_delta"` の `delta.text`。`thinking_delta` と `input_json_delta` は捨てる。`ping` は無視。未知のイベントは無視する（公式が「新しいイベント型が追加されうる」と明記）。ストリームの途中のエラーは `event: error` ／ `data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}`（確認済み）で、`Failure` に変換する。`message_delta` の `stop_reason` が `refusal` なら `ContentBlocked` として扱う（`stop_reason` の値の一覧に `refusal` があることは確認済み。画面にどう出るかは実機確認）。`stop_reason == "max_tokens"` のときは、完了として扱い、注記「最大トークン数で打ち切られました」を出す（9 章の設定を変える案内）。
- Gemini（フェーズ 1 は OpenAI 互換プリセット）。ネイティブ API に移すときは別途設計する。

## 7. エラーハンドリングと境界条件

### 7.1 エラー分類表（R3）

HTTP 状態を第一に、本文のコードを第二に見る。表の「確認」列は、公式ドキュメントで確認した事実か（確認済み）、推測か（未確認）。

#### Anthropic（確認済み：Claude API errors、Rate limits のページ）

| HTTP | `error.type` ／ 目印 | 分類 | 備考 |
|---|---|---|---|
| 400 | `invalid_request_error` | `InvalidRequest`（ただし下記） | 組織・ワークスペースに設定した**使用上限に達したときも 400**（公式が明記。Claude Code ワークスペースの上限は 429 の場合あり）。本文の message に limit / spend / usage を含む場合は `SpendLimit` にする（**文言は未確認**。実機で上限を設定して再現確認する） |
| 401 | `authentication_error` | `AuthInvalid` | キーの形式不正・失効・期限切れ |
| 402 | `billing_error` | `BillingRequired` | 請求・支払い情報の問題 |
| 403 | `permission_error` | `PermissionDenied` | |
| 404 | `not_found_error` | `ModelNotFound`（URL が正しい前提で） | |
| 413 | `request_too_large` | `ContextTooLong` | 上限 32MB（確認済み） |
| 429 | `rate_limit_error` | **`details.error_code == "enforced_spend_limit_reached"` なら `SpendLimit`**（`resetAt` に本文の再開日時を入れる）。そうでなければ `RateLimited` | 階層の月額上限（Start 500 / Build 1,000 / Scale 200,000 USD。公式表）に達した 429 は `retry-after` ヘッダーが**付かず**、翌月 1 日 00:00 UTC まで続く（確認済み）。`retry-after` の有無も判定に使う |
| 500 | `api_error` | `ServerError` | |
| 504 | `timeout_error` | `Timeout` | |
| 529 | `overloaded_error` | `Overloaded` | |

エラー本文の形：`{"type":"error","error":{"type":...,"message":...},"request_id":"req_..."}`。`request_id` は「詳細」に出す（サポート問い合わせ用）。

#### OpenAI（確認済み：Error codes のページ）

| HTTP | `error.code`／`type` | 分類 |
|---|---|---|
| 401 | （Incorrect API key / Invalid Authentication など） | `AuthInvalid` |
| 403 | （地域など） | `PermissionDenied`（個別コードは未確認） |
| 429 | `credit_balance_exhausted`（前払いクレジット切れ） | `BillingRequired` |
| 429 | `organization_spend_limit_exceeded`、`project_spend_limit_exceeded`（設定した月額上限） | `SpendLimit` |
| 429 | `organization_usage_limit_exceeded`（OpenAI が割り当てた月額使用上限） | `SpendLimit` |
| 429 | `error.type == "insufficient_quota"`（上のコード以外・古い形式） | `QuotaExceeded` |
| 429 | `rate_limit_exceeded`／type `rate_limit_error`、`slow_down` | `RateLimited`（`Retry-After` を優先） |
| 500 | | `ServerError` |
| 503 | `server_is_overloaded`（type `service_unavailable_error`） | `Overloaded` |

公式は「課金系は `error.code` で見分ける。`error.type` は `insufficient_quota` のままのことがある。課金・上限・クォータの失敗は再試行で直らない」と書いている（確認済み）。**分類の優先順位：`error.code` が表の課金系コードに一致 → それ。次に `type == insufficient_quota` → `QuotaExceeded`。最後に一般の 429 → `RateLimited`。**

#### Gemini（確認済み：API errors、Troubleshooting のページ。**ただし API errors のページは Interactions API の形式**）

| HTTP | コード（snake_case） | 分類 |
|---|---|---|
| 400 | `failed_precondition`（例：課金の無効） | `BillingRequired` |
| 400 | `invalid_request`、`parameter_unknown` | `InvalidRequest` |
| 401 | `authentication` | `AuthInvalid` |
| 402 | `payment_required`（前払いクレジット切れ） | `BillingRequired` |
| 403 | `permission_denied` | `PermissionDenied` |
| 404 | `model_not_found` | `ModelNotFound` |
| 429 | `quota_exceeded`（日次のクォータ超過） | `QuotaExceeded` |
| 429 | `rate_limit_exceeded`、`too_many_requests` | `RateLimited` |
| 500 / 503 / 504 | `api_error` / `service_unavailable` / `deadline_exceeded` | `ServerError` / `Overloaded` / `Timeout` |

- 本文は `{"error":{"code":"<snake_case>","message":"..."}}`（Interactions API の形式。確認済み）。従来の `generateContent` 系や OpenAI 互換エンドポイントでは、`error.status`（`RESOURCE_EXHAUSTED` など）や配列で包まれる形が返る可能性がある（**未確認**）。そのため分類は「HTTP 状態を主に、`code` と `status` の両方を見る」実装にする。`429` で `RESOURCE_EXHAUSTED` かつ日次を示す語（`per day` など）があれば `QuotaExceeded`、なければ `RateLimited`（未確認の推測。実機で確認）。
- 公式の再試行指針：429・408・5xx のみ再試行し、400・402・403 は再試行しない（確認済み）。
- Gemini 4 と 3.6 以降のモデルは `temperature`、`top_p`、`top_k` の指定を**無視**し、将来 400 エラーにする予定（確認済み。Troubleshooting のページ）。9 章に反映。

#### クライアント側

| 状況 | 分類 | 備考 |
|---|---|---|
| `UnknownHostException`、`ConnectException`、`SSLException`（証明書エラー含む） | `Network` | 証明書検証は無効にしない（19.4） |
| `SocketTimeoutException`（接続 15 秒／読み取り 60 秒） | `Timeout` | |
| 200 のストリームが `Done` なしで EOF／`IOException` | `StreamCut` | 途中までは保存済み |
| JSON として読めない 200 の行 | その行を捨てる。連続 20 行以上なら `Unknown` | |
| `Retry-After` が数値でない | 無視（日付形式は対応しない） | |

### 7.2 その他の境界条件

| 状況 | 動作 |
|---|---|
| キーが空・空白のみ | 追加画面で「接続テスト」を押せない |
| 接続テスト：キー有効 | `GET <baseUrl>/models` が 200 なら成功（Anthropic で `/models` が使えない場合は、`max_tokens=1` の 1 回の短い問い合わせ。**この方式は課金が発生する**ため、画面に注意書きを出す。Anthropic の一覧 API の可否は未確認） |
| 接続テスト：上限・課金の失敗 | 連携は**保存する**（キーは有効で、課金だけが問題のため）。警告を表示し、「あとで使えるようになります」を案内する |
| モデル一覧に、チャットに使えないモデルが混じる | 接続確認で取得した一覧のうち、**名前**から音声合成・画像・埋め込みなどと分かるものを初期の選択肢から外す（`ModelFilter.isChatModel`）。名前を英数字以外の区切りで分け、`tts` `speech` `audio` `transcribe` `whisper` `realtime` `live` `image` `imagen` `dall` `veo` `sora` `embedding` `embeddings` `embed` `aqa` `moderation` `rerank` のどれかに一致する語を含むものが対象（部分一致ではなく語単位。迷うものは残す）。「すべてのモデルを表示」の切り替えで除外分も見られ、すでに選んだモデルと手入力は常に有効。OpenAI・Anthropic・Gemini・カスタムの一覧に同じ規則を使う。**根拠**：Gemini のネイティブ `models.list` には `supportedGenerationMethods`（`generateContent` など）の項目があることを公式（`ai.google.dev/api/models`）で確認したが、**TTS モデルがこの項目で除外できるかは確認できていない**（未確認。TTS も `generateContent` 系で呼ぶ可能性があるため）。また OpenAI 互換の `/models` にはこの項目が無いので、名前による判定を採用した。実際に利用者が「gemini 3.8 flash lite tts」を選んで 400 になった事例が背景（TTS はチャット用ではない）。 |
| モデル一覧が長い（数十件以上） | 検索欄を出し、最大 10 件選ぶ。手入力も可 |
| モデル名の手入力 | 前後の空白を除く。空・200 文字超は拒否。許可する文字は `[A-Za-z0-9._:/@-]` |
| `baseUrl`（Custom） | **`https://` のみ許可**（確定）。`http://` は、LAN・`localhost` を含め拒否し、「暗号化されない接続は使えません」と表示する（`CloudInputs.checkBaseUrl` → `HttpNotAllowed`）。ユーザー情報（`@`）・クエリ（`?`）・フラグメント（`#`）を含む URL、ホストを読めない URL は `Invalid` で拒否。末尾の `/` は保存時に除く |
| サインアウト中 | 「API連携」画面は「Google でサインイン」ボタンだけ。モデルバーの「クラウド」区画は出さない。選択中だったクラウドは未選択に戻す |
| アカウント切り替え | `uid` が変わったら、選択中がクラウドなら未選択にし、連携一覧はそのアカウントの行に切り替える。キーのファイルは `uid` ごとに別なので混ざらない |
| 別端末でアカウントに連携メタデータだけがある | 一覧に「このデバイスにはキーがありません」を出し、「キーを入力」から追加画面のキー貼り付け手順に入る |
| 連携の削除 | 確認ダイアログの後、(1) 選択中なら未選択、(2) キーのファイル削除、(3) Room の行を削除、(4) アカウントストアから削除。会話の `origin = cloud` のメッセージは残す（履歴は消さない） |
| 同意の撤回 | 連携の詳細画面の「同意を取り消す」：その種別（カスタムは、その接続先のホスト）の同意記録だけを削除し、その連携を使うと再度確認を出す |
| 通信が `Network` で失敗 | 自動でローカルモデルへは切り替えない（19.6 の方針）。「ローカルモデルに切り替える」ボタンだけ出す |
| キーが Keystore の事故で復号不能 | 5.4 のとおり `AuthInvalid` 扱い（再入力へ） |
| DB 移行の失敗 | 起動時の例外で落ちるため、移行は必ず手書きで用意し、更新インストールで確認する（14 章の R14）。`fallbackToDestructiveMigration` 禁止 |
| ログ | OkHttp 風の本文ログは作らない。`CloudRequest.toString()` は `apiKey = ••••`、`messages = [n 件]` と出す。例外メッセージに URL のクエリが入らないようにする（キーは URL に付けない設計：ヘッダーのみ） |
| 画面回転・プロセス死 | 生成中のクラウド通信は ViewModel のスコープなので、画面回転では続く。プロセスが死んだら通信も切れる（ローカルと同じ。2 秒ごとの途中保存が残る） |

## 8. 画面とナビゲーション（R1, R2, R4, R10）

### 8.1 設定 → 「API連携」（`ui/ApiSettingsScreen`）

- 設定画面の「拡張機能」行の下に「API連携」行を追加（説明文：「クラウドのAIに接続します。会話の内容が提供元に送られます」）。
- 画面の内容（上から）:
  1. サインイン状態の欄：未サインインなら「Google でサインイン」ボタンと、サインインが必要な理由の説明。サインイン済みならメールアドレスと「サインアウト」「アカウントと連携情報を削除」。
  2. 連携の一覧（サインイン済みのときのみ）：各行は、種別名・表示名・使うモデルの数・状態バッジ（「使える」／「キー未設定」／「⚠ 上限」）。タップで詳細（表示名の変更、モデルの追加・削除、キーの入れ直し、同意の取り消し、削除）。
  3. 「接続先を追加」ボタン。
  4. 注意書き：「使用料は、各提供元との契約に基づいて、あなたに請求されます。アプリは請求しません」（19.5）。

### 8.2 追加フロー（`ui/ApiAddScreen`、R4, R10）

1. **プロバイダー選択**：リスト（Anthropic／OpenAI／Google Gemini／OpenAI互換（カスタム））。アプリは特定のサービスをおすすめしない（19.1）ため、並びは固定の五十音・アルファベット順ではなく、リソースの定義順（Anthropic・OpenAI・Gemini・カスタム）とし、おすすめ表示は付けない。
2. **同意の確認**（`CloudConsentDialog`、`CloudConsent.isAccepted(uid, type, CloudInputs.consentScope(type, baseUrl))` が false のとき）。同意の単位は、アカウント × プロバイダー種別（Anthropic・OpenAI・Gemini）。カスタム（`OpenAiCompatCustom`）は接続先のホストごとで、ホストを入力して保存する時点で確認し、ダイアログの `<提供元名>` にはホスト名を出す。本文（4 言語。以下は日本語の骨子）:
   - 「この接続先を使うと、チャットの内容（あなたの入力、これまでの会話、システムプロンプト）が **<提供元名>** のサーバーに送られます。」
   - 「送られた内容の扱いは、<提供元名> の利用規約とプライバシーポリシーに従います。このアプリの開発者には送られません。」
   - 「利用料金がかかる場合があります。料金は <提供元名> との契約に基づきます。」
   - 「ローカルモデルの会話は、端末の外に出ません。」
   - ボタン：「同意して続ける」「キャンセル」。同意しなければ次へ進めない。
3. **ブラウザでキーを発行**（`keyIssueUrl` があるとき）：説明文（`helpTextRes`、各社の手順を短く）と「ブラウザでキー発行ページを開く」ボタン。`Intent(ACTION_VIEW, Uri.parse(url))` で標準ブラウザを開く（追加の依存なし。`ActivityNotFoundException` なら「ブラウザが見つかりません」を表示）。**アプリ側ではキーを取得できない**（8.4 の理由）ので、利用者がブラウザでキーをコピーしてアプリに戻る。
4. **キーの貼り付け**：伏せ字の入力欄と「貼り付け」ボタン。アプリが前面に戻ったとき（`ON_RESUME`）、クリップボードに「キーらしい文字列」（4〜400 文字、空白を含まない）があれば「クリップボードのキーを貼り付けますか？」を 1 回だけ出す（Android 12 以降でクリップボードを読むと表示される通知は仕様。利用者の操作の直後だけ読む）。貼り付け後、クリップボードは消去を**勧める**表示だけ出し、アプリからは消去しない（他アプリの内容を勝手に消さない）。
5. **接続テスト**：「接続を確認」ボタン → 7.2 の方式で確認 → 成功なら「接続できました」、失敗なら 7.1 の分類の警告文。
6. **使うモデルの選択**：一覧（取得できなければ手入力）から 1〜10 件。
7. **保存**：`CloudKeyStore.put` → Room の `cloud_integration` に挿入 → アカウントストアへ `upsert`。完了画面に「モデル選択に追加されました」を出し、チャットに戻るボタン。

### 8.3 `AppRoot` の変更

`AppRoot` は今、`EXTENSIONS_ROUTE = "settings/extensions"` のとき専用の分岐（タイトル・戻る矢印・下部ナビの「設定」強調）を持つ。これを次の形に一般化する（既存の動作は変えない）。

- 「設定の下位ルート」の定義：`settings/extensions`（タイトル `settings_extensions`）、`settings/api`（`settings_api`）、`settings/api/add`（`api_add_title`）、`settings/api/{integrationId}`（`api_detail_title`）。
- `onSub = route in subRoutes または route?.startsWith("settings/api/") == true`。タイトルはルートからのリソース id の対応表で決める。戻る矢印は `popBackStack`。下部ナビの「設定」は `onSub` のとき選択状態にし、押すと `popBackStack`（既存の拡張機能画面と同じ）。
- `ApiAddScreen` と `ApiSettingsScreen` の `ViewModel` は `AppRoot` の `viewModel()` 流儀（アクティビティ単位）ではなく、画面ごと（`viewModel()` の既定）で作る。

### 8.4 ブラウザでの連携の可否（R4）

結論：**3 社とも「ブラウザでキー発行ページを開き、利用者がキーをコピーして貼り付ける」方式**が現実的。ブラウザでの承認の後にアプリへ自動で戻り、キーが渡る方式（OAuth のリダイレクト）は、一般の利用者向けには使えない。

| プロバイダー | 利用者が使う認証 | アプリ向け OAuth（ブラウザで承認 → アプリへ戻る） | 判断 |
|---|---|---|---|
| Anthropic | API キー（`x-api-key`）。確認済み：公式の概要ページに、認証は API キー（Console で取得）と、クラウド基盤向けの資格情報（Workload Identity Federation など）が書かれている | 第三者アプリが利用者の API 権限を OAuth で得る仕組みは、確認したページに記載がなく**未確認**（なので使わない）。Claude のサブスクのログインは API 用ではない（C7） | キー発行ページを開いて貼り付け |
| OpenAI | API キー（`Authorization: Bearer`）。確認済み | 同じく記載を確認できず**未確認** | 同上 |
| Google Gemini | API キー（AI Studio で発行）。確認済み。2026-05-28 以降、AI Studio で新規発行するキーは「認可キー（auth key）」で、サービスアカウントに紐づく。**制限なしの標準キーは API 側で拒否される**（公式。作成時の既定設定のままなら問題ない） | **OAuth は公式に存在する**が、公式のクイックスタートは「テスト環境向けの簡略な方法」で、Google Cloud プロジェクトの作成、API の有効化、同意画面の設定、テストユーザー登録、クライアント ID の作成が必要（確認済み）。本番では同意画面の検証が必要になる。一般の利用者が自分の Cloud プロジェクトを作るのは現実的でなく、アプリ側で運営の OAuth クライアントを使う場合は、課金が運営ではなく利用者のプロジェクトに付く構造にできるか未確認 | キー発行ページを開いて貼り付け。OAuth は将来の検討（11 章 U5） |

したがって、R4 の「ブラウザへの移動」は、(1) 同意の確認（アプリ内）、(2) ブラウザでキー発行ページを開く、(3) 戻って貼り付け、(4) 接続テスト、の 4 段にする。ブラウザからアプリへ自動で戻る仕組み（カスタムスキーム／App Links）は作らない。

## 9. 生成設定の扱い（R9）

既存の `GenerationParams`（温度 0.7、top_p 0.9、最大トークン 1024、コンテキスト長 4096、スレッド数）をクラウドでも**共通の設定画面の値**として使い、クラウド専用の設定画面は作らない。クラウドへの送り方は次のとおり。

| 設定 | クラウドへの扱い |
|---|---|
| 温度 | OpenAI 互換：`temperature` に送る。Anthropic：`temperature`（値の範囲に収める：0.0〜1.0。2.0 まで選べる設定値は 1.0 に丸める）。 |
| top_p | 送る。**Anthropic では温度と top_p を両方指定できないモデルがある可能性があり未確認**。そのため、Anthropic では `temperature` だけ送り `top_p` は送らない（安全側）。 |
| 最大トークン | OpenAI 互換：`max_tokens`（新しいモデルは `max_completion_tokens` を要求する場合があり**未確認**。400 で拒否されたらもう一方の名前で 1 回だけ再送する）。Anthropic：`max_tokens`（必須項目）。既定の 1024 は短い場合があるので、`stop_reason == max_tokens` のときの注記（6.4）で案内する。 |
| コンテキスト長・スレッド数 | **送らない**（ローカル専用。クラウドは 6.2 の文字数による絞りを使う） |
| 拒否への対応 | 提供側が `temperature` `top_p` を拒否または無視するモデルがある（確認済み：Gemini 4 と 3.6 以降は指定を無視し、将来 400 にする。公式）。`400 InvalidRequest` で本文に `temperature` か `top_p` の語を含むときは、**それらを除いて 1 回だけ再送**し、成功したらその連携・モデルを「サンプリング設定を送らない」としてメモリ上に覚える（`App` の `Set<String>`。永続化しない）。 |
| システム指示（System ロール） | OpenAI 互換では、システムプロンプトを `role: "system"` のメッセージで送る。**システム（developer）指示を受け付けないモデルがある**：実機で Gemini の OpenAI 互換エンドポイントに Gemma 系のモデルを選んだとき、`400 Developer instruction is not enabled for this model` が返った（利用者の報告。モデル名は未確認）。確認できた公式の事実は次のとおり（2026-10-24）。(1) Gemini の OpenAI 互換のページ（`ai.google.dev/gemini-api/docs/openai`）の例は、すべて Gemini のモデル（`gemini-3.8-flash`）に `role: "system"` を送っている。Gemma への言及はなく、**互換エンドポイントで Gemma が System ロールを受け付けるかは公式から確認できなかった**。(2) Gemma のページ（`ai.google.dev/gemma/docs/core/gemma_on_gemini_api`）には、ネイティブの `generateContent` では `system_instruction` を渡せる例（`gemma-4-26b-a4b-it`）がある。対応：400 で本文に「developer / system instruction」と「not enabled / not supported 等」の語があれば、System を取り除いて最初の User メッセージの前置きに畳み込み（`foldSystemIntoUser`）、**1 回だけ**自動で再送する。成功したら、そのモデルは（アプリのプロセスの間だけ）最初から畳み込む。再送しても失敗したら、既存のエラー表示の「詳細」に提供側の原文が出る。Anthropic は `system` を別項目で送るので対象外。 |
| 設定画面の表示 | 生成設定の見出しに、「温度・top_p・最大トークンはクラウドにも使われます。コンテキスト長・スレッド数は端末内モデルだけです」を足す（`settings_gen_note`） |

## 10. 会話履歴・JS 実行機能との整合（R7, R8）

### 10.1 会話履歴

- 保存先は既存の `conversation` / `message`。クラウドの返答も同じ会話に保存する（ローカルとクラウドを混ぜて 1 つの会話で使える）。
- `message.origin` で、どちらが生成したかを残す（表示の目印と、R8 の判定に使う）。`ChatViewModel.open` で履歴を読み込むとき、`ChatMessage.origin` に反映する（現在は role と content だけ）。
- 2 秒ごとの途中保存・最終保存・再試行（`retry()`）は既存のまま動く。`retry()` の事前判定のうち、モデル読み込みの判定は `activeEngine.isLoaded` に、熱の判定は `Cloud` のとき飛ばす。
- 履歴画面の一覧の見た目は変えない。会話を開くと、メッセージごとの雲アイコンが出る（10.3）。
- クラウドの返答にも「AI 生成」の表示と「報告」ボタン（`設計書.md` 11 章）を維持する。報告メールの本文のモデル名は、クラウドのときは `<表示名> / <モデル名>`。

### 10.2 JS 実行

- `js-runtime.md` の P7（外部 AI の返答には実行ボタンを出さない）を維持する。`ChatScreen.runButtonState` の先頭で、そのメッセージの `origin == Cloud` なら `RunButtonState.Hidden` を返す。表示の判定は**メッセージごと**（会話単位ではない）。ローカルとクラウドが混ざった会話では、ローカルの返答にだけ実行ボタンが出る。
- 実行結果を「AI に渡す」（入力欄に入れる）は、実行できたローカルの返答に対してだけ存在するので、変更しない。利用者が結果を編集して、クラウド選択のまま送信することは可能（送る内容は利用者が確認できる）。
- `Prompts.JS_ADDENDUM` はクラウド選択のとき付けない（3.2）。

### 10.3 利用者への表示（R10）

- モデルバーに、クラウド選択中は雲アイコン ＋ `<表示名> / <モデル名>` を表示する。
- クラウドの返答の吹き出しにも小さな雲アイコン（`origin == Cloud`）。会話の途中で選択を変えたとき、どちらが答えたか分かる。
- 入力欄の上に、クラウド選択中だけ、1 行の注意「入力は <提供元名> に送信されます」を常時表示する。

## 11. 設計書.md への反映（実装前に更新する箇所）

**2026-10-24 に `設計書.md` の 1・3.2・11・14・19.1〜19.3・19.5・19.7・19.8 章へ反映済み**（確定事項：Google サインインは API連携を使うときだけ、Firestore はメタデータのみ、キーは Keystore のみ、接続先は https のみ、同意は種別ごと／カスタムはホストごと）。6 章の図と 8.1・8.2 章の Room／DataStore の表は未反映。下の表は反映の元の一覧で、14・19.3 の行は確定事項に合わせて書き直してある。

| 章 | 更新内容 |
|---|---|
| 1 | 「会話内容は端末の外へ出さない」を、「ローカルモデルの会話は端末の外へ出さない。クラウドを連携した場合に限り、利用者の同意のうえで提供元に送る」に |
| 3.2 / 19.8 | 実装状態の表に、本書の機能を追加（プロバイダー抽象化、Google サインイン、アカウント別の記録、エラー分類） |
| 11 | 「サーバーへ送らない」「データ収集なし」の記述を、5.5 の内容に合わせて書き換える。アカウント削除の導線を追記 |
| 14 | 決定事項に「API連携を使うときだけ Google サインイン（ローカルモデルの利用には不要）。キーは端末の Keystore のみ。Firestore にはキーを含まないメタデータのみ。連携の接続先は https のみ（LAN の http は不可）。同意はプロバイダー種別ごと（カスタムはホストごと）」を追加 |
| 19.1 | 「特定の事業者をおすすめ・既定にしない」を維持しつつ、プリセット（4 社）を選択肢として出す旨を明記 |
| 19.2 | 「OpenAI互換が必須、独自形式は v1.2 以降」を、「OpenAI互換と Anthropic を最初に実装、Gemini はまず OpenAI 互換のプリセット」に更新 |
| 19.3 | 「接続先を手入力」を、プロバイダー選択式に更新（Custom は残す）。「`http://` は同じネットワーク内に限り警告つきで許可」は**廃止**し、「接続先 URL は `https://` のみ」にする（LAN の PC のローカル AI への接続は将来課題）。「複数登録」「会話ごとの切り替え」はモデル選択（モデルバー）で行う旨に更新 |
| 19.6 | エラー処理の表を、7.1 の分類に置き換える |
| 8.1 / 8.2 | Room の表に `origin` と `cloud_integration` を追加。DataStore に同意のキーを追加 |
| 6 | 全体構成図の `RemoteApiEngine` を「v1.1」から実装対象に |

## 12. プロバイダーの比較と推奨（R12）

### 12.1 比較

| 項目 | OpenAI 互換（OpenAI・Gemini互換・カスタム） | Anthropic（Messages） | Gemini（ネイティブ） |
|---|---|---|---|
| 認証 | `Authorization: Bearer` | `x-api-key` ＋ `anthropic-version` | キー（互換エンドポイントでは Bearer） |
| ストリーミング | `data: {json}` ＋ `data: [DONE]` | 名前つきイベント（`message_start`…`message_stop`） | ネイティブは別形式（未確認、フェーズ 2 で調査） |
| 課金・上限エラー | 429 ＋ `error.code`（`credit_balance_exhausted`、`*_spend_limit_exceeded`、`organization_usage_limit_exceeded`）。確認済み | 429 ＋ `details.error_code = enforced_spend_limit_reached`（階層の上限）、設定した上限は 400。402 `billing_error`。確認済み | 402 `payment_required`、400 `failed_precondition`、429 `quota_exceeded`。確認済み |
| 利用者の入手難度 | キーは簡単に発行できる。前払いクレジット制 | キーは簡単に発行できる。Console で上限設定が可能 | AI Studio で発行。無料枠あり（無料枠の有無・条件は未確認。Troubleshooting のページに「無料枠のクォータ」の記述があるが条件は確認していない） |
| 実装の追加量 | 最初の 1 つ。他の互換サービスも同じコードで繋がる | 別アダプター 1 つ | 別アダプター 1 つ（互換エンドポイントで当面は不要） |

### 12.2 推奨

1. **フェーズ 1：OpenAI 互換アダプターと、Anthropic アダプター**。OpenAI 互換は 1 つの実装で、OpenAI・Gemini（互換エンドポイント）・カスタム（`https://` で公開されている OpenAI 互換サービス）に繋がり、`設計書.md` 19.2 の方針とも合う。Anthropic はユーザー要望にあり、かつ形式が違うためアダプター抽象化の検証になる（2 形式あるとインターフェースの妥当性が確かめられる）。
2. **フェーズ 1 の画面のプリセット**：Anthropic・OpenAI・Gemini（互換エンドポイント）・カスタム。
3. **フェーズ 2：Gemini ネイティブ**（互換エンドポイントの制約や、エラー形式が合わないと分かった場合のみ）。
4. 実装順は「OpenAI 互換 → Anthropic」。理由は、OpenAI 互換のほうが SSE の解釈が単純で、先に全体（画面・保存・エラー）を通せるため。

## 13. 実装者向けの小タスク分割（順序つき）

各タスクは単独で「ビルドが通り、単体テストが通る」状態で終えられる大きさ。担当の目安は、根幹（Claude）と周辺（Strata）で、`設計書.md` 15 章の分担に従う。

| # | タスク | 主な要件 | 担当の目安 | 完了の確認 |
|---|---|---|---|---|
| T1 | `domain/CloudModels.kt`（型）、`domain/SseParser.kt`、`ModelSelection` のエンコード／デコード、`SseParserTest`、`ModelSelectionTest` | R1, R6 | Strata | JVM テストが通る |
| T2 | `domain/ApiErrorClassifier.kt` と `OpenAiCompatProvider.classify`、`AnthropicProvider.classify`。7.1 の表をテストの期待値にする（`ApiErrorClassifierTest`） | R3 | 根幹（Claude） | 表の全行のテストが通る |
| T3 | `OpenAiCompatProvider` の `buildStreamRequest` / `parseEvent` / モデル一覧。固定の SSE テキストでのテスト | R6, R9 | Strata | テストが通る |
| T4 | `AnthropicProvider` の同上（`thinking_delta` を捨てる、`error` イベント、`stop_reason`） | R6, R3 | Strata（レビューを Claude） | テストが通る |
| T5 | `data/CloudHttp.kt`（接続・読み取り・中止で `disconnect`）と `RemoteApiEngine`（再送ルール 6.2-9）。偽サーバー（ローカルの `ServerSocket`）を使った JVM テスト | R6 | 根幹（Claude） | 中止で接続が切れる／再送の回数が表どおり |
| T6 | `data/CloudKeyStore.kt`（5.4）。実機（またはエミュレーター）で保存・復号・復号失敗時の削除を確認 | R11 | 根幹（Claude） | 実機で確認、ログにキーが出ないこと |
| T7 | Room：`message.origin`、`cloud_integration`、`Migration(1,2)`。旧バージョンのアプリを入れた端末に更新インストールして会話が残ることを確認 | R7, R14 | 根幹（Claude） | 更新インストールで会話が残る |
| T8 | `App` の変更（`activeEngine`、`selectCloudModel`、復元）と `ChatViewModel` の変更（6.1〜6.3）。この時点では連携の追加画面がないため、デバッグビルド限定の入力口（既存の `debug/` 配下のレシーバー）で連携を 1 件入れて確認 | R2, R6, R7 | 根幹（Claude） | デバッグで 1 往復でき、中止でき、履歴に残る |
| T9 | `CloudErrorText` ＋ 4 言語の文字列（エラー分類 16 種、同意本文、画面ラベル）。`ChatScreen` の警告表示とボタン | R3, R15 | Strata（翻訳）、確認は Claude | 分類ごとの文言を目視 |
| T10 | `ui/ApiSettingsScreen` / `ApiAddScreen`（サインインなしの状態で動く。`uid` は固定の仮値 `"local"`）、`AppRoot` の一般化、`SettingsScreen` の行、同意ダイアログ、ブラウザを開く、貼り付け、接続テスト、モデル選択 | R1, R4, R10 | Strata（画面）、確認は Claude | 追加から送信まで通る |
| T11 | `ChatScreen` のモデルバー（クラウド区画、雲アイコン、注意の 1 行）、`ModelsScreen` の「クラウド」区画、`runButtonState` の `origin` 判定、`Prompts` のクラウド時の分岐、会話途中の切り替え確認（6.2-2） | R2, R8, R10 | Strata | クラウドの返答に実行ボタンが出ない |
| T12 | `AccountRepository`（Credential Manager）と Firebase Auth。`uid` を仮値から実値に切り替え。Google Cloud／Firebase 側の用意（11 章 U2・U3）が前提 | R5 | 根幹（Claude） | 実機でサインイン／サインアウト |
| T13 | `FirestoreAccountStore`（5.3）、セキュリティルールの登録、同期（5.3 の競合解決）、アカウント削除（5.5） | R5 | 根幹（Claude） | 別端末（またはエミュレーター）で連携メタデータが復元され、キー未設定と出る |
| T14 | プライバシーポリシー 4 言語、`store/listing.md`、`設計書.md`（11 章の表）、Play のデータセーフティの下書き | R10 | Claude（文面）、Strata（翻訳の下書き） | 差分レビュー |
| T15 | 総合確認：上限エラーの再現（可能な範囲）、機内モード、途中切断、プロセス死、アカウント切り替え、アップデート、容量（release arm64 APK のサイズが 50MB 以下） | 全部 | Claude | 14 章の表の全項目 |

T1〜T11 はサインイン（T12 以降）なしで進められる。T10 までで「キーを入れて使える」状態にでき、T12・T13 を後から差し込める（`CloudAccountStore` の差し替え）。

## 14. テスト方針

JVM の単体テスト（`app/src/test/...`）は既存の流儀（`*Test.kt`）に合わせる。「実機」は Android 端末またはエミュレーター。

| 要件 | テストケース |
|---|---|
| R1 | (1) `CloudProviders` に `ProviderType` の全値の実装が登録されている。(2) ダミーのプロバイダーを足してもモデルバー・追加画面のコードを変えずに一覧に出る（レジストリ経由であることの確認） |
| R2 | (1) `ModelSelection.encode/decode` の往復（ローカル id、クラウド id、モデル名に `/` や `:` を含む場合、空文字、null、不正な `cloud:` だけの文字列）。(2) 実機：クラウド選択後、アプリを再起動して選択が復元される。連携を削除してから再起動すると未選択に戻る。(3) 実機：ローカル → クラウド → ローカルの切り替えで、ローカルのモデルが読み込み直される |
| R3 | `ApiErrorClassifierTest`：7.1 の各行について、(status, headers, body) → `ApiErrorKind` の表どおりになる。特に、Anthropic の 429 で `enforced_spend_limit_reached` あり／なし（`retry-after` 付き）、OpenAI の 4 種の課金系コード、`insufficient_quota` と `rate_limit_exceeded` の区別、Gemini の 402 と 400 `failed_precondition`、未知のコード、本文が JSON でない場合（`Unknown` で落ちない）、本文が空。実機：不正なキーで `AuthInvalid` の警告とボタンが出る。上限エラーは各社のダッシュボードで上限を最小にして再現できれば確認（できなければ T2 のテストで代替し、未確認と記録） |
| R4 | (1) 実機：「ブラウザで開く」で標準ブラウザが起動する。ブラウザが無い端末（またはエミュレーターで無効化）で `ActivityNotFoundException` の表示。(2) 戻ったときのクリップボード提案は 1 回だけ。(3) 貼り付けたキーの前後の空白・改行が除かれる |
| R5 | (1) `LocalOnlyAccountStore`：`uid` が違うと互いに見えない。(2) 実機（T12 以降）：アカウント A で連携 → サインアウト → アカウント B では一覧が空 → A に戻ると復元。(3) Firestore ルール：本人以外の読み書きが拒否され、許可項目以外を含む書き込みが拒否される（Firebase のルールのシミュレーターで確認）。(4) 別端末で「キー未設定」と出て、キーの入力で使える。(5) `http://` の URL（LAN・`localhost` を含む）は `checkBaseUrl` が `HttpNotAllowed` を返して保存できない。クエリ・ユーザー情報・フラグメントつきの URL は `Invalid` で保存できない（`CloudInputs` の単体テスト） |
| R6 | `SseParserTest`：(1) 複数イベント、(2) `data:` が複数行、(3) コメント行、(4) 空行だけ、(5) チャンク境界が行の途中で切れる、(6) CRLF、(7) 日本語（UTF-8 のマルチバイトが境界で分断されない：バイト列ではなく UTF-8 のデコード後の行で扱う）。`OpenAiCompatProviderTest`／`AnthropicProviderTest`：固定の応答列から期待どおりの `Text` 列と `Done` が得られる。`thinking_delta`・`ping`・未知イベントは無視。`error` イベントが `Failure`。`[DONE]` なしの EOF が `StreamCut`。T5：偽サーバーで、(a) 途中で `stop()` すると接続が閉じられる（サーバー側で切断を検知）。(b) 最初の文字の前の 429 は 2 回まで再送し、3 回目は失敗として返す。(c) 文字を受け取った後の切断は再送しない。実機：長い回答の途中で停止でき、そこまでの文章が残る |
| R7 | (1) 実機：クラウドで 1 往復 → アプリを閉じて開き直しても会話が残る。(2) 実機：応答の途中で機内モードにすると、そこまでの文章が残り、`StreamCut` の警告が出て、再試行（再生成）できる。(3) 履歴から開いたメッセージの `origin` が復元される。(4) 会話途中でローカル → クラウドに切り替えると、6.2-2 の確認が出る |
| R8 | (1) `runButtonState` 相当の判定をテストできる関数に切り出して、`origin == Cloud` で常に `Hidden`（JS 有効・js コードブロック・閉じ済みでも）、`origin == Local` では従来どおり。(2) 実機：ローカルとクラウドが混ざった会話で、ローカルの返答にだけ「実行」が出る。(3) クラウド選択のときのシステムプロンプトに `JS_ADDENDUM` が含まれない |
| R9 | `OpenAiCompatProviderTest`／`AnthropicProviderTest`：(1) リクエスト本文に `temperature` `max_tokens` が入り、`context_length` `threads` が入らない。(2) Anthropic の本文に `top_p` が入らない。(3) `CloudParams.temperature == null` のとき項目自体が出ない。(4) Anthropic で温度 1.5 のとき 1.0 に丸められる。(5) 400（本文に `temperature`）→ 項目を除いて 1 回だけ再送（T5 の偽サーバーで） |
| R10 | (1) 実機：同意なしでは追加を進められない。(2) 同意の版を上げると再度出る。(3) クラウド選択中はモデルバーに雲アイコンと提供元名、入力欄の上に注意の 1 行が常に出る。(4) 4 言語の文字列に不足がない（`values*/strings.xml` のキー集合が一致するか確認するチェックを手動またはスクリプトで）。(5) ストア関係：リリース前チェック（5.5 の表）を満たしたか。ポリシー 4 言語が更新済み |
| R11 | (1) 実機：保存したキーを `get` で取得でき、`filesDir/cloud-keys/` のファイルを文字列検索してもキーが見つからない。(2) 別の `integrationId` のファイルに入れ替えると復号に失敗し null。(3) アプリのデータを `adb backup` / `run-as` で抜いてもキーの平文が出ない。(4) `CloudRequest.toString()` にキーが出ない。(5) Logcat に、通信を行う一連の操作中、キーの文字列が出ない（ビルドの `Log` 呼び出しを grep でも確認）。(6) Keystore の鍵を削除（デバッグ用の操作）してから `get` すると null で、アプリは落ちない |
| R12 | プロバイダー別のテスト（R6・R3 の行）が通る。比較表（12.1）が公式ドキュメントと食い違っていないか、実装時に再確認（特に未確認の項目：Anthropic の `/models`、Gemini のエラー形式、Anthropic の使用上限の 400 の文言） |
| R14 | 実機：旧版（現行リリース相当のビルド）で会話を数件作る → 新版で更新インストール → 会話・メッセージがすべて残り、`origin` が `local`。新規のクラウド返答は `cloud`。Room のスキーマ検証エラーが出ない |
| R15 | 4 言語で、同意本文・エラー文・画面ラベルが欠けず、はみ出さない（スクリーンショットの目視） |

## 15. 未決事項

判断がつかなかった点と、その理由。★は 16 章のユーザー判断にも載せるもの。

| # | 内容 | 理由 | 暫定の扱い |
|---|---|---|---|
| O1 | （解決済み）Google サインインとの方針整合 | **確定**: API連携を使うときだけ Google サインインを求める。ローカルモデルの利用には求めない。`設計書.md` の「アカウント登録を求めない」は、ローカル利用についてのみ維持する。ポリシー・Play 申告は 5.5 のとおり更新 | 確定済み（反映は 11 章） |
| O2 | Anthropic のモデル一覧 API（`GET /v1/models`）の可否・形式 | 確認したページに記載が無く、確認できていない | 失敗したら手入力にフォールバック。接続テストは課金が発生しうる方式になるため、注意書きを出す |
| O3 | 履歴の送信上限 `CLOUD_MAX_INPUT_CHARS = 60000` の妥当性 | モデルごとの上限・料金を固定できない | 暫定値。実機で調整。サーバーが `ContextTooLong` を返したら半分で 1 回再送 |
| O4 | Anthropic で温度と top_p を同時に送れるか | 確認したページでは判断できなかった | 安全側として温度だけ送る |
| O5 | OpenAI 互換の `max_tokens` と `max_completion_tokens` の使い分け | モデルによって異なる可能性（未確認） | 400 のとき名前を替えて 1 回だけ再送 |
| O6 | Anthropic の「ユーザーが設定した使用上限」の 400 エラーの本文 | 公式は「400 を返す」とだけ書き、文言を載せていない | 本文に limit／spend／usage の語があれば `SpendLimit`。実機で上限を設定して確認 |
| O7 | Gemini の `generateContent`・OpenAI 互換エンドポイントのエラー本文の形 | 確認できたのは Interactions API の形式のみ | HTTP 状態を主に、`code` と `status` の両方を見る。実機で確認 |
| O8 | Play のデータセーフティで、クラウド事業者への送信が「共有」にあたるか、アカウント削除要件が適用されるか | Play の定義と例外は更新されるため、リリース前に公式で再確認が必要（推測で断定しない） | 5.5 の表のとおり「該当しうる」前提で準備 |
| O9 | Anthropic のキー発行ページの正確な URL | Console が `platform.claude.com` に移っていることは確認したが、キー画面のパスは確認できていない | 実装時に実際に開いて確認。確認できなければトップを開く |
| O10 | Firebase SDK 追加による APK の増加量 | 実測していない | T12 の後に release arm64 APK を測る。50MB 目標を超えるなら案 E（Drive appData）を再検討 |
| O11 | フェーズ 1 の仮 `uid`（`"local"`）から実 `uid` への移行時の、既存の連携の引き継ぎ | 公開前なので引き継ぎ不要と見るが、内部テストの端末ではキーを再入力することになる | 引き継がない（フェーズ 1 は公開しない） |
| O12 | 生体認証で API キーの使用を保護するオプション | バックグラウンド生成と両立しにくい | 将来の検討。今回は入れない |
| O13 | クラウド選択中の `InferenceService` 通知の文言（「生成中」） | 現在は端末内生成の文言 | 文言を中立（「応答を受信中」）にするかは実装時に決める。Play の用途説明は更新必須（5.5） |

## 16. ユーザー判断が必要な点

推奨案を併記する。先に決まらないと止まるものを上に置く。

| # | 判断事項 | 選択肢 | 推奨 |
|---|---|---|---|
| U1 | どのプロバイダーを先にするか | (a) OpenAI 互換 → Anthropic、(b) Anthropic → OpenAI 互換、(c) Gemini ネイティブも最初から | (a)。12.2 の理由（1 実装で複数に繋がる／2 形式で抽象化を検証できる） |
| U2 | アカウント別の保存先 | 案 A（端末のみ）／**案 B（Firestore、キーは端末のみ）**／案 C／案 D（非推奨）／案 E（Drive appData） | 案 B。運営のサーバーにデータを置きたくなければ案 E。Firebase の用意が負担なら、先に案 A で公開し、後から案 B に移す（ただし移行時に既存利用者は連携を入れ直し） |
| U3 | Google Cloud／Firebase 側で用意するもの（実装者ではなく**あなたが**行う作業） | 下記の一覧 | 実装開始（T12）の前までに用意 |
| U4 | （決定済み）Google サインインの扱い | **決定**: API連携を使うときだけ必要で、アプリ全体では必須ではない。サインインしない場合の端末内だけの連携は作らない（保存先を 2 系統にしない） | 決定どおり |
| U5 | Gemini の OAuth（ブラウザで承認）に将来対応するか | 対応する／しない | しない。理由は 8.4。利用者が自分の Cloud プロジェクトを作る必要があり、検証も重い |
| U6 | クラウド選択中の「会話途中の切り替え確認」（6.2-2）を入れるか | 入れる／毎回出す／出さない | 入れる（過去のローカル会話が送られることに気付けるため） |
| U7 | 履歴の切り詰めの上限（O3）の初期値 | 60000 文字／もっと大きく／設定項目にする | 60000 文字で始め、設定項目にはしない |
| U8 | 自動再送（6.2-9）の回数・待ち時間 | 最大 2 回／なし | 最大 2 回（最初の文字の前のみ） |
| U9 | プライバシーポリシーの変更とストア申告の作業（5.5）を、いつ・誰が行うか | リリース前に実施 | 公開前の必須作業。T14 で下書きを用意し、最終確認はあなた |
| U10 | アカウント削除の導線を設けること（5.5） | 設ける | 設ける（Play の要件に該当しうるため） |

### Google Cloud／Firebase 側で用意が必要なもの（U3、案 B の場合）

1. Firebase プロジェクトを作る（Google Cloud プロジェクトが同時にできる）。Analytics は有効にしない。
2. Firebase にこのアプリを登録する：パッケージ名（アプリの `applicationId`）と、**SHA-1 証明書フィンガープリント**（デバッグ用、リリースの署名鍵用、Google Play アプリ署名の鍵用の 3 つ。Play アプリ署名の SHA-1 は Play Console の「アプリの完全性」で確認する）。
3. Authentication で Google プロバイダーを有効にし、サポートのメールアドレスを設定する。
4. OAuth 同意画面（Google Auth platform）を構成する：アプリ名、サポートメール、プライバシーポリシーの URL（公開 URL が必要）、公開状況（テスト → 本番）。基本的なスコープ（メール・プロフィール）だけなら追加の審査は通常不要だが、本番化の手順は公式で確認する（未確認）。
5. **ウェブ アプリケーション種別の OAuth クライアント ID** を用意する（Credential Manager の Google サインインが ID トークンを得るために使う `serverClientId`）。Android 用のクライアント ID（パッケージ名＋ SHA-1）も自動で作られる。
6. Firestore データベースを作り（ロケーションは後から変更できないので、利用者の多い地域を選ぶ）、5.3 のセキュリティルールを登録する。
7. `google-services.json` をダウンロードして、実装者に渡す（アプリのモジュールに置く。リポジトリへ入れてよいかは方針で決める。含まれるのは秘密鍵ではなく識別子だが、API キーの項目は Firebase のコンソールで利用制限〔パッケージ名と SHA-1〕をかけておく）。
8. 費用：Firestore の無料枠（Spark プラン）で足りる規模の見込み。上限を超えたとき課金が必要になる（Blaze プラン）かは、Firebase の最新の料金ページで確認する。予算アラートの設定を推奨。
9. Play Console 側（5.5）：データセーフティの更新、アカウント削除の Web 用 URL の用意、プライバシーポリシーの URL の更新。

案 E（Drive appData）にする場合は、2・3・5・6 の代わりに、Google Cloud プロジェクトで Drive API の有効化と、Android 用 OAuth クライアント（パッケージ名＋SHA-1）、`drive.appdata` スコープの同意画面への登録が必要。

### 参照した公式ドキュメント（2026-10-24 に確認）

- Anthropic：Claude API errors（`docs.anthropic.com/en/api/errors`）、Rate limits（同 `/rate-limits`）、Streaming（同 `/streaming`）、API overview（同 `/overview`）。
- OpenAI：Error codes（`platform.openai.com/docs/guides/error-codes`）。
- Google：API errors（`ai.google.dev/gemini-api/docs/api-errors`）、API troubleshooting（同 `/troubleshooting`）、OpenAI compatibility（同 `/openai`）、Using Gemini API keys（同 `/api-key`）、Authentication with OAuth quickstart（同 `/oauth`）。

上記のページの記述が、実装時にも同じか（特に未確認として書いた項目）を、実装者が各ページで再確認すること。
