# コンテキスト自動圧縮と作業フォルダ（ファイル操作）設計書（v0.1）

2026-10-26 のユーザー要望「コンテキストが最大になったら自動で圧縮する機能と、作業フォルダにはローカルAIがアクセスできるようにしてファイルを作成できるようにして」の設計書です。書式と用語は `設計書.md`（v0.2）、`docs/design/js-runtime.md`、`docs/design/cloud-api.md` に合わせます。実装コードは含みません（型・シグネチャ・規則だけ）。

この文書は 2 つの機能を扱います。

- **A. コンテキスト自動圧縮**（R1〜R9）
- **B. 作業フォルダ（ファイル操作ツール）**（R10〜R19）

調べた現状（2026-10-26 時点のソースで確認）:

- ローカル: `mal_llama.cpp` の `nativeGenerate` が、プロンプトのトークン数が `n_ctx - reserve`（`reserve = max(64, min(maxTokens, n_ctx/2))`）を超えると、**黙って古い発言から落とす**（先頭の system と最新の発言は残す）。要約はしない（`設計書.md` 9 章 4）。`n_ctx` は設定の `params.contextLength`（既定 4096、512 刻み）で、モデルを読み込むときに決まる。`ModelEntity.contextLength` は取り込み時の固定値 4096 で、実際の `n_ctx` ではない。
- クラウド: `CloudHistory.kt` の `trimForCloud` が 60,000 文字（`CLOUD_MAX_INPUT_CHARS`）を超えると古い発言を落とし、`ChatUiState.trimmedNotice` を立てる。`ContextTooLong` のときは上限を半分にして 1 回だけ自動で送り直す。トークン換算はしない（cloud-api.md の O3）。
- Kotlin 側にはトークナイザーが無い（トークン化は JNI の中だけ）。
- Room は version 2（`MIGRATION_1_2` あり、`exportSchema = true`、`App.kt` で `addMigrations(MIGRATION_1_2)`、`fallbackToDestructiveMigration` は使わない）。
- 再試行（`ChatViewModel.retry`）は `dao.lastUserMessageId` の**後ろの行を削除**して作り直す（`ChatRetry.historyForRetry`）。
- クラウド同意は `App.cloudSendAllowed()`（送信直前）と `needsCloudHistoryConfirm()`（会話の途中でクラウドへ切り替えた最初の送信の確認）の 2 段。

## 1. 概要

### 1.1 目的

**A.** 会話がモデルのコンテキスト上限に近づいたら、古い部分を要約に置き換えて、利用者が何もしなくても会話を続けられるようにする。元のメッセージは消さず、画面の表示も変えない。要約に失敗しても、今までどおり（古い発言を落とす）で続く。

**B.** 利用者が選んだ 1 つのフォルダ（Android の Storage Access Framework）に、端末内モデルがファイルを一覧・読み取り・作成（上書き）できるようにする。モデルには「タグ形式のツール呼び出し」をさせる。書き込みは毎回、利用者の承認が要る。フォルダの外へは出られない。クラウドモデルでは使えない。

### 1.2 要件一覧

「出典」が「指示」のものは 2026-10-26 のユーザー要望、「設計」は設計上の必要から足したもの（ユーザー未確認）。ID はこの文書の中での番号（cloud-api.md の R1〜R15 とは別）。

| ID | 要件 | 出典 |
|---|---|---|
| R1 | 入力（履歴 + システム文 + 生成枠）が上限に近づいたら、古い部分を要約して置き換え、自動で続行する。ローカルとクラウドの両方 | 指示 |
| R2 | 上限の判定に使うトークン数（クラウドは文字数）の見積もり方を決める | 指示 |
| R3 | 要約の保存方法：元メッセージは残し、画面の表示は維持する。Room を v3 へ移行（既存の会話を失わない） | 指示 |
| R4 | 再試行（`ChatRetry`）・会話の切り替え・履歴からの再開と矛盾しない | 指示 |
| R5 | 要約に失敗・中断したときの挙動を決める | 指示 |
| R6 | クラウドへ要約を送る場合の同意（既存の 2 段の確認）との整合 | 指示 |
| R7 | 圧縮したことを利用者に表示する（実行中の状態、区切り、要約の確認） | 指示 |
| R8 | 設定で自動圧縮の有効・無効を切り替える（既定は有効） | 設計 |
| R9 | 既存の安全網（ネイティブの古い発言の削除、`trimForCloud`、`ModelBudget` の前提）を壊さない | 設計 |
| R10 | 利用者が選んだフォルダ 1 つを「作業フォルダ」として保存し、アプリの再起動後も使える（`ACTION_OPEN_DOCUMENT_TREE` と永続 URI 権限） | 指示 |
| R11 | モデルに「ツール呼び出し」をさせる方式を決める。小さなローカルモデルでも安定する形式 | 指示 |
| R12 | ツールは一覧・読み取り・書き込み（新規作成、同名は上書き）。削除は作らない | 指示 |
| R13 | フォルダの外へ出ない（パス検証、`..`・絶対パスの拒否） | 指示 |
| R14 | 書き込みの前に毎回、確認ダイアログ（新規か上書きかを明示）。承認なしの書き込み経路を作らない | 指示 |
| R15 | 上限（1 ファイルの大きさ、1 回の返答での操作回数、1 回の送信での往復回数、時間）を決める | 指示 |
| R16 | クラウドモデル選択中の扱い：ファイル操作は使えない。過去のファイル内容をクラウドへ送らない | 指示 |
| R17 | 設定画面で作業フォルダの選択・解除・有効／無効（既定は無効） | 設計 |
| R18 | JS 実行機能（js-runtime.md）の内部作業フォルダとは別物として共存させる | 設計 |
| R19 | プライバシーポリシー（4 言語）・Data safety・リリースチェックリストへの影響を整理し、4 言語の文字列を用意する | 指示 |

## 2. 前提・スコープ外

### 2.1 前提

| 番号 | 前提 | 関連 |
|---|---|---|
| P1 | 圧縮の入口は `ChatViewModel.generate` だけ（`send` と `retry` の両方が通る）。ユーザーが手動で「今すぐ要約」を押す操作は作らない | R1 |
| P2 | 要約は**会話ごとに 1 つ**（ローリング要約。再要約のときは「前の要約 + 新しく古くなった部分」を入力にする） | R3 |
| P3 | 要約は選択中のモデル自身が作る（ローカルならそのローカルモデル、クラウドなら選択中のクラウドモデル）。別の要約専用モデルは使わない | R1, R6 |
| P4 | 要約を作っている間は、生成中（`generating = true`）として扱う。停止ボタンで中断できる | R5, R7 |
| P5 | 作業フォルダは全体で 1 つ（会話ごとには分けない）。URI は DataStore に文字列で保存する | R10 |
| P6 | ツールを使えるのは、**端末内モデルの返答**だけ（js-runtime.md の P7 と同じ考え方）。クラウドの返答にツールのタグがあっても実行しない | R16 |
| P7 | ツールの 1 回の返答での実行は**最大 1 回**。最初の完成したタグで生成を止め、その後ろの文章は捨てる（小型モデルが結果を作り話で続けるのを防ぎ、端末の生成時間も節約する） | R11, R15 |
| P8 | 読み取り・一覧は承認なしで実行し、書き込みは毎回承認する（U8） | R14 |
| P9 | 作業フォルダの中身を扱うのは UTF-8 のテキストだけ（バイナリは読まない・作らない） | R12 |
| P10 | JS 実行機能の内部作業フォルダ（`filesDir/js-workspace/`）は変更しない。2 つの機能はお互いのフォルダに触れない（U5） | R18 |

### 2.2 スコープ外

- 手動の「今すぐ要約」ボタン、要約の編集、複数の要約の保持、要約専用モデル。
- コンテキスト使用率のメーター表示。
- ローカルで古い発言がネイティブ層に黙って落とされたことの利用者への通知（今までどおり通知しない。圧縮を有効にしていれば、落ちる前に要約されるのが通常）。
- ファイルの削除・移動・名前変更・バイナリ・画像・フォルダの作成だけの操作（フォルダは書き込みの親として自動で作る）。
- クラウドモデルでのファイル操作（U6 で推奨しないとした場合）。
- 複数の作業フォルダ、会話ごとの作業フォルダ、フォルダの内容の閲覧画面（利用者は OS のファイルアプリで見る）。
- ネイティブ関数呼び出し（チャットテンプレートの `tools`）：アプリは `llama_chat_apply_template`（旧 C API）で組み立てており、`tools` を渡せない。ここでは採らない（U9）。
- JS 実行機能から作業フォルダを読み書きすること（U5）。

## 3. 変更・追加するファイル

パッケージは `com.mobileailocal`（`AndroidLocalAI/app/src/main/java/com/mobileailocal/` 以下）。

### 3.1 追加

| パス | 役割 | 要件 |
|---|---|---|
| `domain/TokenEstimator.kt` | 文字列・メッセージのトークン数の見積もり（純 Kotlin） | R2 |
| `domain/ContextCompression.kt` | `ContextBudget`（上限・閾値の計算）、`planCompression`（どこまで要約するかの計画。純 Kotlin） | R1, R2, R4, R9 |
| `domain/ToolCallParser.kt` | 返答から `<file_list>` `<file_read>` `<file_write>` を取り出す（純 Kotlin） | R11, R12 |
| `domain/ToolModels.kt` | `ToolCall`、`ToolFind`、`ToolOutcome`、`WorkspaceLimits`、`WorkspaceFiles` インターフェース、`WorkspaceException` | R12, R13, R15 |
| `domain/ToolResultFormat.kt` | ツール結果メッセージの整形・判定（`ToolResult.PREFIX`）、クラウド送信用の省略 | R11, R16 |
| `data/SafWorkspace.kt` | `WorkspaceFiles` の SAF 実装（`DocumentFile`）。パス解決、読み書き、タイムアウト | R10, R12, R13 |
| `data/WorkspaceToolRunner.kt` | `ToolCall` を実行して `ToolOutcome` を返す（上限の適用、結果の組み立て）。`WorkspaceFiles` を受け取る | R12, R15 |
| `ui/WorkspaceScreen.kt`, `ui/WorkspaceViewModel.kt` | 設定内の「作業フォルダ」画面（選択・解除・有効化・状態表示） | R10, R17 |
| `ui/ToolCallCard.kt` | 返答内のツール呼び出しの表示、ツール結果メッセージの折りたたみ表示 | R11, R14 |
| `ui/ToolApprovalDialog.kt` | 書き込み前の確認ダイアログ | R14 |
| `ui/SummaryDivider.kt` | 要約済みの区切り線と、要約の確認・リセットのダイアログ | R7 |
| `src/test/java/com/mobileailocal/` の `TokenEstimatorTest.kt`、`ContextCompressionTest.kt`、`ToolCallParserTest.kt`、`ToolResultFormatTest.kt`、`WorkspaceToolRunnerTest.kt`（偽の `WorkspaceFiles` を使う） | 単体テスト（8 章） | 全般 |
| `app/schemas/.../3.json` | Room の `exportSchema` で自動生成される v3 のスキーマ（既存の 1.json・2.json と同じ置き場所） | R3 |

### 3.2 変更

| パス | 変更内容 | 要件 |
|---|---|---|
| `data/Entities.kt` | `ConversationEntity` に `summaryText: String`（既定 ""）、`summaryCount: Int`（既定 0）を追加（`@ColumnInfo(defaultValue = ...)` を付ける。`MessageEntity.origin` と同じ流儀）。`ChatDao` に `updateSummary(id, text, count)`、`clearSummary(id)` を追加。`@Database(version = 3)` | R3, R4 |
| `data/Migrations.kt` | `MIGRATION_2_3_STATEMENTS`（4.4 の SQL）と `MIGRATION_2_3` を追加 | R3 |
| `App.kt` | `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`。`workspaceFiles`（`SafWorkspace`）を保持 | R3, R10 |
| `data/SettingsRepository.kt` | `AppSettings` に `autoCompress: Boolean = true`（キー `auto_compress`）、`workspaceEnabled: Boolean = false`（`workspace_enabled`）、`workspaceTreeUri: String = ""`（`workspace_tree_uri`）、`workspaceName: String = ""`（`workspace_name`）と、それぞれの setter | R8, R10, R17 |
| `domain/Prompts.kt` | `SUMMARIZE_SYSTEM`、`summarizeMessages(...)`、`summaryBlock(...)`、`WORKSPACE_ADDENDUM` を追加。`systemFor` に引数 `workspaceEnabled`、`summary` を追加（4.5） | R1, R11 |
| `ui/ChatViewModel.kt` | (a) `generate` に圧縮ステップ（6.1）、(b) 往復ループ・承認待ち・早期停止（6.4）、(c) `open` で `summaryText`/`summaryCount` を読む、(d) クラウド送信時のツール結果の省略（6.5）。`ChatUiState` に項目を追加（5.2） | R1〜R7, R11〜R16 |
| `ui/ChatScreen.kt` | `Bubble` を `ToolCallParser` で描き分け、`SummaryDivider` の挿入、状態表示（要約中、失敗の注記）、承認ダイアログの呼び出し | R7, R11, R14 |
| `ui/SettingsScreen.kt`, `ui/AppRoot.kt` | 「会話の自動圧縮」のスイッチ、「作業フォルダ」への入口、経路 `settings/workspace` | R8, R17 |
| `AndroidManifest.xml` | 変更なし（SAF は権限不要）。`MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` は宣言しない | R19 |
| `build.gradle.kts`（app） | `implementation("androidx.documentfile:documentfile:1.0.1")`（未導入なら。バージョンは実装時に最新の安定版を確認。U10） | R10 |
| `res/values*/strings.xml`（4 言語） | 7.4 の文字列 | R19 |
| `store/privacy/privacy-policy.{ja,en,zh-CN,zh-TW}.md`、`docs/release-checklist.md` | 8.5 | R19 |
| `InferenceEngine.kt`, `LlamaCppEngine.kt`, `mal_llama.cpp`（任意） | `suspend fun countTokens(messages): Int? = null` を追加し、ローカルだけ JNI でトークナイザーを呼ぶ（T7。U3） | R2 |

## 4. インターフェース

### 4.1 トークンの見積もり（`domain/TokenEstimator.kt`、R2）

```kotlin
object TokenEstimator {
    const val PER_MESSAGE_OVERHEAD = 8   // チャットテンプレートの役割タグ分の目安
    /** 文字列のトークン数の見積もり。ASCII は 3.5 文字 = 1 トークン(切り上げ)、非 ASCII は 1 文字 = 1 トークン。空は 0 */
    fun estimate(text: String): Int
    fun estimate(m: ChatMessage): Int = estimate(m.content) + PER_MESSAGE_OVERHEAD
}
```

- 少なめに見積もると、ネイティブ層の黙った削除が先に起きる。そのため**多めに出る式**にしてある（日本語・中国語は 1 文字 1 トークンで、Qwen 系の実測より多い側）。係数は実機で見直す（U15）。
- クラウドは `estimate` を使わず、既存の方針どおり**文字数**（`content.length` の合計）で判定する。

### 4.2 上限と閾値（`domain/ContextCompression.kt`、R1, R2, R9）

```kotlin
object ContextBudget {
    /** ローカルの入力側の上限(トークン)。mal_llama.cpp の `n_ctx - reserve` と同じ式。reserve = max(64, min(maxTokens, contextLength/2)) */
    fun localLimit(contextLength: Int, maxTokens: Int): Int
    const val TRIGGER_RATIO = 0.70       // 入力が limit のこの割合を超えたら圧縮する
    const val KEEP_RATIO = 0.35          // 圧縮後も原文で残す直近の分の上限(limit の割合)
    const val MIN_LOCAL_LIMIT = 768      // これ未満の limit では圧縮しない(要約が入る余地がない)
    fun localTrigger(limit: Int): Int = (limit * TRIGGER_RATIO).toInt()
    fun localKeep(limit: Int): Int = (limit * KEEP_RATIO).toInt()
    /** 要約の出力の最大トークン数。(limit * 0.15).coerceIn(96, 512) */
    fun localSummaryMaxTokens(limit: Int): Int

    const val CLOUD_TRIGGER_CHARS = 45_000   // CLOUD_MAX_INPUT_CHARS(60,000)の 75%
    const val CLOUD_KEEP_CHARS = 15_000
    const val CLOUD_SUMMARY_MAX_TOKENS = 800
    const val CLOUD_LINE_CAP_CHARS = 8_000   // 要約の入力に入れる 1 発言の最大文字数
    const val LOCAL_LINE_CAP_CHARS = 2_000
}
```

数値の例（既定の `maxTokens = 1024`）:

| contextLength | reserve | limit | trigger | keep | 要約の最大 |
|---|---|---|---|---|---|
| 4096 | 1024 | 3072 | 2150 | 1075 | 460 |
| 2048 | 1024 | 1024 | 716 | 358 | 153 |
| 8192 | 1024 | 7168 | 5017 | 2508 | 512 |
| 1024（`< MIN_LOCAL_LIMIT` で圧縮しない） | 512 | 512 | - | - | - |

```kotlin
/** 圧縮の計画。null なら圧縮しない */
data class CompressionPlan(
    val newSummaryCount: Int,                 // 圧縮後に要約が覆う先頭からの発言数(history の添字。System を含まない)
    val toSummarize: List<ChatMessage>,       // 今回新しく要約に入れる発言(原文。1 発言は lineCap 文字で切る前の状態)
)

/**
 * @param history 表示順の全発言(System を除く。最後は今回の User 発言。ThinkingParser.answerOf 済み)
 * @param summaryCount 今の要約が覆う発言数
 * @param fixedSize システム文 + 今の要約の大きさ(sizeOf の単位)
 * @param sizeOf 発言の大きさ(ローカルはトークン、クラウドは文字数)
 * @param trigger 超えたら圧縮する入力の大きさ
 * @param keep 圧縮後に原文で残す直近の大きさの上限
 * @param inputBudget 今回の要約の入力(前の要約 + 要約する発言)に使える大きさ
 */
fun planCompression(
    history: List<ChatMessage>, summaryCount: Int, fixedSize: Int,
    sizeOf: (ChatMessage) -> Int, trigger: Int, keep: Int, inputBudget: Int,
): CompressionPlan?
```

`planCompression` の規則（実装者が迷わないように固定）:

1. `active = history.drop(summaryCount)`。`total = fixedSize + active.sumOf(sizeOf)`。`total <= trigger` なら null。
2. 後ろから発言を足して、合計が `keep` 以下で最大になる位置 `s` を求める（`active` の添字。**最新の 1 件は `keep` を超えても必ず原文で残す**）。
3. `active[s]` が Assistant なら `s` を 1 つ後ろへ進める（要約する側に回す）。最新の User 発言（最後の添字）を越えて進めない。
4. 要約する範囲は `active[0 until s]`。空なら null（要約できるものが無い。最新の発言だけで上限を超える場合もここ）。
5. 範囲の合計が `inputBudget` を超えるときは、古い側から入るだけ入れる（`inputBudget` に収まる最大の添字 `c`）。`c` を、`active[c]` が User になる位置まで手前へ戻す。`c == 0` なら、`active[0]` 1 件だけを（長さ上限で切って）入れる。
6. 戻り値は `CompressionPlan(summaryCount + c, active[0 until c])`。

### 4.3 プロンプト（`domain/Prompts.kt`、R1, R11）

```kotlin
const val SUMMARIZE_SYSTEM: String   // 英語の固定文(下記)
/** 要約用の入力。[System(SUMMARIZE_SYSTEM), User(本文)] の 2 件。lineCap で各発言を切る */
fun summarizeMessages(previousSummary: String, transcript: List<ChatMessage>, lineCap: Int): List<ChatMessage>
/** システム文の後ろに足す要約ブロック。summary が空なら "" */
fun summaryBlock(summary: String): String
const val WORKSPACE_ADDENDUM: String // 4.6
fun systemFor(base: String, jsEnabled: Boolean, workspaceEnabled: Boolean, summary: String, cloud: Boolean): String
```

`SUMMARIZE_SYSTEM`（英語固定。小型モデルでも読み取りやすいように言語で変えない。約 100 トークン）:

```
You compress a chat for later use by the same assistant. Write a concise summary of the conversation below.
Keep: the user's goals, decisions, facts, names, numbers, file names, code identifiers, preferences, and unfinished tasks.
Drop: greetings and repetition. Write in the language the user used. Output only the summary, no preface.
```

`summarizeMessages` の User 本文の形:

```
[Previous summary]
<previousSummary>        // 空ならこの 2 行ごと省く

[Conversation to add]
User: <text>
Assistant: <text>
...
```

- 各発言は `lineCap` 文字で切り、切ったら末尾に ` …[cut]` を付ける。
- ツール結果の発言（`ToolResult.PREFIX` で始まるもの）は、本文を最大 400 文字に切って入れる（ファイルの全文を要約に写さない。ファイル名と成否が分かればよい）。

`summaryBlock`:

```
[Summary of the earlier conversation; those messages were removed to save space]
<summary>
```

`systemFor` の組み立て順: `base` + JS の追記（有効かつローカル）+ 作業フォルダの追記（有効かつローカル）+ `summaryBlock(summary)`。各ブロックの間は空行。**要約は常にシステム文の中に入れる**（Gemma 等、2 つ目の System を受け付けないテンプレートがあるため。クラウドは既存の `foldSystemIntoUser` がそのまま効く）。

### 4.4 Room の移行（R3）

```kotlin
val MIGRATION_2_3_STATEMENTS = listOf(
    "ALTER TABLE conversation ADD COLUMN summaryText TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE conversation ADD COLUMN summaryCount INTEGER NOT NULL DEFAULT 0",
)
val MIGRATION_2_3: Migration  // Migration(2, 3) で上の文を順に実行
```

`ChatDao`:

```kotlin
@Query("UPDATE conversation SET summaryText = :text, summaryCount = :count WHERE id = :id")
fun updateSummary(id: Long, text: String, count: Int)
@Query("UPDATE conversation SET summaryText = '', summaryCount = 0 WHERE id = :id")
fun clearSummary(id: Long)
```

### 4.5 ツール呼び出し（`domain/ToolModels.kt`、`ToolCallParser.kt`、R11, R12）

```kotlin
sealed interface ToolCall {
    data class List(val path: String) : ToolCall                       // path は "" か "." でルート
    data class Read(val path: String, val start: Int = 0) : ToolCall   // start は文字の位置(0 以上)
    data class Write(val path: String, val text: String) : ToolCall
}

sealed interface ToolFind {
    data object NoCall : ToolFind                                       // ツールのタグが無い
    data class Call(val call: ToolCall, val endIndex: Int) : ToolFind   // 最初の完成したタグ。endIndex は answer 中でタグの終わりの次の位置
    data class Incomplete(val tag: String) : ToolFind                   // 開きはあるが閉じが無い(途中で切れた)
    data class Malformed(val reason: String) : ToolFind                 // 未知のタグ・属性が無い・パス不正など
}

object ToolCallParser {
    /** ThinkingParser.answerOf 済みの文字列を渡す(思考過程の中のタグは対象外) */
    fun find(answer: String): ToolFind
    /** 生成の途中で呼ぶ軽い判定。新しい断片に '>' を含み、かつ answer に "<file_" を含むときだけ find を呼ぶ */
    fun mayComplete(answer: String, lastPiece: String): Boolean
}
```

タグの文法（5.1 に表）。

```kotlin
/** 書き込みや読み取りの上限。固定(U で変えない) */
object WorkspaceLimits {
    const val MAX_WRITE_BYTES = 200_000          // 1 回の書き込み(UTF-8)
    const val MAX_READ_FILE_BYTES = 1_000_000L   // これより大きいファイルは読まない(先頭の分だけ返す処理もしない)
    const val MAX_LIST_ENTRIES = 100
    const val MAX_TOOL_TURNS = 6                 // 1 回のユーザー送信につきツールで往復できる回数
    const val MAX_FORMAT_ERRORS = 2              // 連続した形式の誤りの許容
    const val MAX_WRITE_BYTES_PER_SEND = 500_000 // 1 回のユーザー送信での書き込みの合計
    const val OP_TIMEOUT_MS = 10_000L            // 1 操作の時間(SAF 提供元が固まる場合の保険)
    const val APPROVAL_TIMEOUT_MS = 300_000L     // 承認待ち(5 分)。超えたら拒否と同じ
    const val MAX_DEPTH = 8; const val MAX_PATH_CHARS = 512; const val MAX_NAME_CHARS = 100  // JsLimits と同じ値
    /** 読み取りで返す文字数: (contextLength / 3).coerceIn(1000, 8000) */
    fun readBudgetChars(contextLength: Int): Int
}

class WorkspaceException(val code: String, message: String) : RuntimeException("$code: $message")
// code: EPERM / EINVAL / ENAMETOOLONG / ENOENT / EISDIR / ENOTDIR / EFBIG / EACCES(権限を失った) / ETIMEDOUT / EIO / EBINARY(テキストでない)

data class WorkspaceEntry(val name: String, val isDir: Boolean, val size: Long)
data class WorkspaceStat(val isDir: Boolean, val size: Long)
data class ReadChunk(val text: String, val totalChars: Int, val start: Int, val hasMore: Boolean)

/** 作業フォルダの実体。実装は SAF(SafWorkspace)。テストでは偽物に差し替える。パスは相対で、実装の入口で JsPathPolicy.normalize を通す */
interface WorkspaceFiles {
    suspend fun list(path: String): List<WorkspaceEntry>                  // 名前順、ディレクトリ先。MAX_LIST_ENTRIES 件まで
    suspend fun stat(path: String): WorkspaceStat?                        // 無ければ null
    suspend fun read(path: String, start: Int, maxChars: Int): ReadChunk
    suspend fun write(path: String, text: String): WriteResult            // 親ディレクトリは自動で作る
}
data class WriteResult(val created: Boolean, val bytes: Int)

sealed interface ToolOutcome {
    data class Ok(val text: String) : ToolOutcome                          // モデルへ返す本文(整形済み)
    data class Failed(val code: String, val message: String) : ToolOutcome
    data object Denied : ToolOutcome                                       // 利用者が拒否(または承認の時間切れ)
}
```

`WorkspaceToolRunner`（`data/`）:

```kotlin
class WorkspaceToolRunner(private val files: WorkspaceFiles) {
    /** 書き込みの承認ダイアログに出す事前情報。stat で新規か上書きか、旧サイズを調べる。例外は投げず Failed 相当は null を返す */
    suspend fun preview(call: ToolCall.Write): WritePreview?
    /** 承認済み(または承認不要)の呼び出しを実行する。例外は投げない。CancellationException は再送出 */
    suspend fun execute(call: ToolCall, contextLength: Int): ToolOutcome
}
data class WritePreview(val path: String, val bytes: Int, val overwrite: Boolean, val oldBytes: Long?, val head: String)
```

### 4.6 システムプロンプトへの追記（`Prompts.WORKSPACE_ADDENDUM`、R11）

`workspaceEnabled` かつ作業フォルダが使える状態（権限あり）かつローカルのとき、足す。英語の固定文（約 130 トークン）:

```
You can use files in the user's work folder with ONE of these tags per reply (relative paths only):
<file_list path="."/>
<file_read path="notes/a.txt"/>   (optional start="2000" to continue a long file)
<file_write path="notes/a.txt">full text of the file</file_write>
After the tag, stop and wait: the result comes back as the next message. Writes need the user's approval. Read before you edit; never guess file contents. Text files only.
```

メモ: JS の追記（約 110 トークン）と両方有効だと約 240 トークンになり、`contextLength` 2048 の端末では limit（1024）の約 4 分の 1 を占める。両方有効で `limit < 1536` のときは、設定画面の説明に「コンテキスト長を大きくすることをおすすめします」と書く（U 化はしない）。システム文が変わると KV キャッシュの再利用が 1 回だけ効かなくなる（設計書 9 章 5）。

## 5. データ構造・仕様

### 5.1 ツールのタグの文法（R11, R12, R15）

小型モデルで安定させるための選択（U9）: JSON ではなく、**本文を生のまま書ける XML 風のタグ**にする。JSON は改行・引用符のエスケープが必要で、小型モデルが最も壊しやすい。Qwen 系などが学習で見慣れている `<tag>` 形式にし、`<file_write>` の本文は一切エスケープしない。

| タグ | 形 | 動作 |
|---|---|---|
| 一覧 | `<file_list path="dir"/>`（`path` 省略可＝ルート） | 直下の名前を返す。ディレクトリは末尾 `/`。最大 100 件 |
| 読み取り | `<file_read path="a/b.txt"/>`、続きは `start="2000"` | UTF-8 のテキストを `start` 文字目から `readBudgetChars` 文字まで返す。続きがあれば末尾に `[more: start=<次の位置>]` |
| 書き込み | `<file_write path="a/b.txt">本文</file_write>` | 新規作成、同名なら上書き（承認ダイアログで明示）。親ディレクトリは自動で作る |

パース規則（`ToolCallParser.find`。実装者が迷わないように固定）:

1. 対象は `<file_list` `<file_read` `<file_write` で始まるタグの**最初の 1 つ**。それより前の文章は残す。コードブロック（```）の中のタグは**実行対象にしない**（説明のための例示と区別する。フェンス内の位置は `CodeBlockParser` と同じ規則で判定）。
2. 属性は `名前="値"` または `名前='値'`。許す名前は `path`、`start`（`file_read` のみ）。未知の属性は `Malformed("unknown attribute")`。`path` の無い `file_read` / `file_write` は `Malformed("path required")`。
3. `file_list`・`file_read` は `/>` または `></file_x>` で閉じる。`>` だけで閉じ括弧が無いまま文末になれば `Incomplete`。
4. `file_write` は `</file_write>` までが本文。文末までに無ければ `Incomplete`（**途中で切れた書き込みは実行しない**。最大トークン数で打ち切られた場合を含む）。
5. 本文の整形：先頭の改行 1 つと末尾の改行 1 つを取り除く。本文全体が ```` ``` ````（言語名つき可）で囲まれていたら、囲みを外す（小型モデルが付けがちなため）。`\r\n` は `\n` に統一。
6. `<file_` で始まる未知のタグ（`<file_delete` など）は `Malformed("unknown tool")`。`<tool_call>` など `<file_` で始まらないものは対象外（`NoCall`）。
7. `path` の検証は実行側（`SafWorkspace` の入口で `JsPathPolicy.normalize`）。パーサーは文字列をそのまま保持する。

### 5.2 状態（`ChatUiState` への追加）

```kotlin
val summaryCount: Int = 0                    // 要約が覆う先頭からの発言数(区切り線の位置)
val summaryText: String = ""                 // 要約の本文(確認ダイアログ用)
val compressing: Boolean = false             // 要約の生成中
val compressNotice: CompressNotice? = null   // Done(覆った発言数) / Failed(理由の種類)。次の送信で消える
val pendingToolApproval: ToolApproval? = null // 書き込みの承認待ち
val toolStatus: ToolStatus? = null           // 実行中の操作の表示("notes/a.txt を読んでいます" など)
val toolStopNotice: ToolStopNotice? = null   // 往復の上限、形式の誤りが続いた、など止まった理由

data class ToolApproval(val preview: WritePreview, val call: ToolCall.Write)
```

### 5.3 保存形式（ツールの往復）

- ツール呼び出しを含む返答は、通常どおり `assistant` として保存する（本文にタグがそのまま入る。**最初の完成したタグの後ろは捨てた状態**で保存）。
- ツールの結果は **`role = "user"` の行**として保存する。本文は `ToolResult.PREFIX`（`"[File tool result]\n"`）で始める。スキーマ変更なし（U12）。形式（英語固定、JsHandoff と同じ流儀）:

```
[File tool result]
op: read
path: notes/a.txt
status: ok
<本文>        // read: 取得した本文 + 続きがあれば "[more: start=N]" / list: 1 行 1 名前 / write: "created 120 bytes" か "overwritten 120 bytes (was 98)"
```

失敗は `status: error` と `code: ENOENT` + 1 行の説明、拒否は `status: denied by user`。形式の誤りは `status: error` + `code: FORMAT` + 直し方の 1 行（例 `use exactly <file_write path="..."> ... </file_write>`）。
- 履歴を開いたとき、`assistant` のタグに続く行が `ToolResult.PREFIX` で始まる `user` 行でなければ、そのタグは「実行されませんでした」と表示する（**再開時に自動実行はしない**）。

### 5.4 設定値（DataStore）

| キー | 型 | 既定 | 備考 |
|---|---|---|---|
| `auto_compress` | Boolean | true | R8 |
| `workspace_enabled` | Boolean | false | R17 |
| `workspace_tree_uri` | String | "" | `Uri.toString()`。永続 URI 権限は OS 側が持つ |
| `workspace_name` | String | "" | 表示用（`DocumentFile.name`） |

作業フォルダが使える状態 = `workspaceEnabled && workspaceTreeUri.isNotBlank() && 永続権限（読み書き）が `contentResolver.persistedUriPermissions` に残っている`。1 つでも欠ければ、システム文に追記せず、タグがあっても実行しない。

## 6. 処理フロー

### 6.1 A. 圧縮つきの送信（`ChatViewModel.generate` の変更。R1, R4〜R7, R9）

既存の流れ（同意の確認 → DB に行を作る → `generating = true` → 送るメッセージを組み立て → サービス開始 → 発熱監視 → 生成ループ）に、次を差し込む。

1. 既存どおり、`cloud && !cloudSendAllowed()` なら送らずに戻る（**圧縮の要約リクエストもここを通った後でしか動かない**。R6）。
2. `history`（今回の User 発言まで）を既存どおり作る。
3. 送るメッセージの組み立てを 2 段にする。
   1. `mapped = history.map { Assistant なら ThinkingParser.answerOf(content) }`（既存のマッピング）。
   2. `active = mapped.drop(summaryCount)`。システム文 = `Prompts.systemFor(base, jsEnabled, workspaceEnabled, summaryText, cloud)`。
4. `InferenceService.start` と発熱の監視を始めた**後**（生成ループの `try` の先頭）で、`maybeCompress` を呼ぶ（6.2）。圧縮したら 3 をやり直す（`summaryCount`・`summaryText` が変わるため）。
5. 以降は既存どおり。ローカルは `engine.generate(forModel)`、クラウドは `trimForCloud` と `ContextTooLong` の自動再送を**そのまま残す**（R9。圧縮の後でも安全網として働く）。

### 6.2 `maybeCompress`

前提: `settings.autoCompress` が true。偽なら何もしない。

1. 上限の計算:
   - ローカル: `limit = ContextBudget.localLimit(params.contextLength, params.maxTokens)`。`limit < MIN_LOCAL_LIMIT` なら何もしない。`sizeOf = TokenEstimator.estimate`、`fixedSize = estimate(システム文)`、`trigger = localTrigger(limit)`、`keep = localKeep(limit)`、`summaryMax = localSummaryMaxTokens(limit)`、`inputBudget = params.contextLength - summaryMax - estimate(SUMMARIZE_SYSTEM) - 100`。
   - クラウド: `sizeOf = { it.content.length }`、`fixedSize = システム文の文字数`、`trigger = CLOUD_TRIGGER_CHARS`、`keep = CLOUD_KEEP_CHARS`、`summaryMax = CLOUD_SUMMARY_MAX_TOKENS`、`inputBudget = CLOUD_TRIGGER_CHARS`。
   - クラウドの場合、`active` には先に `normalizeForCloud` と 6.5 の省略をかけた内容を使う（実際に送る内容で測る）。
2. 直近の失敗の抑止: この会話で要約に失敗していて、その後に発言が 4 件（`BACKOFF_MESSAGES`）増えていなければ何もしない（毎回の送信を遅くしないため。メモリ上だけ、会話を切り替えるとリセット）。
3. `plan = planCompression(...)`。null なら何もしない。
4. 回数: 1 回の送信での圧縮は、ローカル最大 2 回、クラウド最大 1 回（`MAX_PASSES`）。ローカルで 2 回目が必要なのは、クラウドで長くなった会話をローカルへ切り替えたときなど、1 回で `inputBudget` に収まらない場合。
5. `compressing = true`。`engine.generate(summarizeMessages(...), params.copy(maxTokens = summaryMax, temperature = 0.2f))` を collect して文字列にする（途中経過は画面に出さない）。
6. 検証: `ThinkingParser.answerOf` で思考過程を除く → `trim()`。次のどれかなら**失敗**（6.3）。空／`TokenEstimator.estimate(要約) > limit * 0.30`（クラウドは 6,000 文字超）／要約が置き換える原文の大きさ以上。
7. 保存: `withContext(Dispatchers.IO) { dao.updateSummary(cid, summary, plan.newSummaryCount) }`。続けて `_state` の `summaryCount`・`summaryText` を更新し、`compressNotice = Done(plan.newSummaryCount)`、`compressing = false`。
8. 2 回目が必要なら（まだ trigger を超える）、手順 3 に戻る。

### 6.3 要約の失敗・中断（R5）

| 状況 | 動作 |
|---|---|
| 停止ボタン・会話の切り替え・発熱停止（`CancellationException`） | 何も保存しない。再送出して、通常の停止の流れ（既存の `finally`）に任せる。失敗の注記は出さない |
| 要約の生成が例外（`ApiError`・ローカルの `IllegalStateException` など） | 保存しない。`compressNotice = Failed`、`compressing = false`、手順 6.2-2 の抑止をかける。**主要な生成は続ける**（ローカルはネイティブ層が古い発言を落とす、クラウドは `trimForCloud`）。要約の `ApiError` は `cloudError`・`markCloudWarning` に**しない**（主要な生成が同じエラーを出すので、そちらで一度だけ報告される） |
| 検証で不合格（空、長すぎる） | 上と同じ扱い |
| 要約中にアプリが落ちる | 保存前なら何も残らない（行は消えない）。次の送信でやり直す |
| 要約の保存の直後〜主要な生成の前に落ちる | 要約は残っている。次の送信は要約つきで始まる（問題なし） |

### 6.4 B. ツールの往復（`generate` の変更。R11〜R15）

条件: `!cloud` かつ「作業フォルダが使える状態」（5.4）。それ以外は従来どおり（タグがあっても実行しない）。

1. 生成の collect の中で、新しい断片のたびに `answer = ThinkingParser.answerOf(sb)` を作り、`ToolCallParser.mayComplete(answer, piece)` が true のときだけ `find` を呼ぶ。`ToolFind.Call` が出たら、**生成を打ち切る**（`transformWhile` で collect を止める。`engine.generate` はフローを止めると生成も止まる）。保存・表示する本文は `answer` の `endIndex` まで（後ろは捨てる。思考過程がある場合は、思考過程 + 回答の `endIndex` まで）。
2. 生成が最後まで終わったとき（打ち切りなし）にも `find` を呼ぶ。`Call` → 以降へ。`NoCall` → 通常終了。`Incomplete` / `Malformed` → 形式の誤りとして 6 へ。
3. 回数の確認：`toolTurns >= MAX_TOOL_TURNS` なら、実行せず `toolStopNotice = TurnLimit` で終了（タグを含む返答は保存済み。「実行されませんでした」と表示）。
4. `Write` の確認：`MAX_WRITE_BYTES`（UTF-8）を超える、または送信中の書き込みの合計が `MAX_WRITE_BYTES_PER_SEND` を超えるなら、承認ダイアログを出さず `status: error` / `EFBIG` の結果にする。
5. 承認（`Write` のみ。P8）：`runner.preview(call)` で新規／上書きと旧サイズを得て、`pendingToolApproval` をセットし、`CompletableDeferred<Boolean>` を `APPROVAL_TIMEOUT_MS` まで待つ。許可 → 実行、拒否・時間切れ・停止ボタン → `Denied`（停止ボタンは往復全体を終了）。待っている間も `generating = true`（送信不可）。アプリが背面でも待つ（戻ると同じダイアログが出る）。待ちが 5 分を超えたら拒否として扱い、通知は出さない。
6. 実行：`toolStatus` をセットし、`withTimeout(OP_TIMEOUT_MS) { runner.execute(call, params.contextLength) }`。結果（`Ok` / `Failed` / `Denied`）を 5.3 の形式にして、`user` 行として DB に保存する。続けて空の `assistant` 行を作り、**同じ `generate` の手順 3 以降（6.1）をもう一度**行う（`toolTurns++`）。形式の誤りの結果の場合は `formatErrors++` し、`MAX_FORMAT_ERRORS` に達したら往復を止める（`toolStopNotice = FormatErrors`）。
7. 往復の途中でも 6.1 の圧縮が働く（ツール結果が長くなって trigger を超える場合）。圧縮の境界は、今回の User 行（= ツール結果の行）より手前に限られる（4.2 の規則 2〜3）。
8. 生成の再開は、画面の `messages` に `user` のツール結果行と空の `assistant` 行を足して続ける（既存の `history + ChatMessage(...)` と同じ作り方）。

注意（実装上の落とし穴）: 既存の `finally` は 1 回の生成ごとに `finalSave`・`InferenceService.stop`・`generating = false` を行う。往復の途中では `generating = false` にならないよう、ループ全体を 1 つの `try`/`finally` で囲み、各往復の最終保存だけを往復ごとに行う（実装者が `generate` を「1 往復」と「全体」の 2 関数に分ける）。

### 6.5 クラウドとの境界（R6, R16）

- `selectedModel` がクラウドのとき：`workspaceEnabled` でもシステム文に `WORKSPACE_ADDENDUM` を足さない。返答にタグがあっても実行しない（本文は文章として表示）。設定画面に「クラウドのモデルでは使えません」と常に書く。
- 会話の途中でクラウドへ切り替えたとき、送る履歴のうち `ToolResult.PREFIX` で始まる `user` 行は、本文を `"[File tool result omitted: not sent to the cloud]"` の 1 行に置き換えて送る（`ToolResultFormat.forCloud(messages)`。`normalizeForCloud` の前にかける）。DB と画面の内容は変えない。
- `assistant` が `file_write` のタグで書いた本文（モデルが作った内容）は、そのまま送る（利用者のファイルの内容そのものではない。ただし読み取った内容を写した可能性はある。→ 切り替えの確認ダイアログに 1 行足す）。
- 切り替えの確認ダイアログ（既存の `pendingCloudConfirm`）は、会話に `ToolResult.PREFIX` の行または要約があるとき、本文に次を 1 行足す：「作業フォルダのファイルの内容は送りません。ただし、AI の返答や要約に、その一部が含まれている場合があります」。
- 圧縮：クラウドの会話で作る要約のリクエストは、通常の送信と同じ会話内容を同じ提供元へ送るだけで、新しい種類の送信ではない（既存の同意の範囲内）。ただし**追加のリクエストになり、利用者の API の料金がかかる**（U2）。

### 6.6 B. 作業フォルダの選択（`WorkspaceViewModel`。R10, R17）

1. 「フォルダを選ぶ」→ `ActivityResultContracts.OpenDocumentTree()`。初期位置は指定しない。
2. 戻りの URI が null（取り消し）なら何もしない。
3. `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`。`SecurityException` なら `workspace_error_permission` を表示して終了。
4. 以前のフォルダがあれば `releasePersistableUriPermission` で手放す（永続権限の上限は端末ごとに 128〜512 個）。
5. `workspaceTreeUri`・`workspaceName` を保存する。`workspaceEnabled` は変えない（利用者が別途スイッチを入れる）。
6. 「解除」→ 権限を手放し、URI と名前を消し、`workspaceEnabled = false`。実行中の往復があれば中断する（`ChatViewModel` が設定の変化を見て `stop()` 相当）。
7. 画面を開くたびに `persistedUriPermissions` を確認し、権限が無ければ「フォルダへのアクセスが無くなりました。選び直してください」と表示してスイッチを無効に見せる（保存値は消さない）。

### 6.7 `SafWorkspace` の動作（R12, R13）

- すべてのメソッドは `withContext(Dispatchers.IO)` で実行する。入口で `JsPathPolicy.normalize(path, allowEmpty = true/false)` を通す（`..`・絶対パス・`\`・制御文字・長さ・階層を拒否。`JsFsException` を `WorkspaceException` に写す。コードは同じ）。
- パスは `DocumentFile.fromTreeUri` のルートから**名前 1 要素ずつ `findFile`** でたどる。SAF では `..` で親へ出る手段がそもそも無く、OS が URI の権限範囲外を拒否するので、字句の検査と合わせて二重に守られる。
- 書き込み：既存なら `contentResolver.openOutputStream(uri, "wt")`（切り詰めて書く）。新規なら親を `createDirectory` でたどり、`createFile(mime, name)`。**`createFile` の前に必ず `findFile` で同名を確認する**（提供元によっては同名で `name (1)` が作られるため）。mime は拡張子から `MimeTypeMap` で決め、不明なら `application/octet-stream`（`text/plain` を渡すと、提供元が名前の末尾に `.txt` を足すことがある）。書いた後に `length()` が UTF-8 のバイト数と一致するか確認し、違えば `EIO`。
- 読み取り：サイズが `MAX_READ_FILE_BYTES` 超なら `EFBIG`。NUL バイトを含む、または UTF-8 として不正なら `EBINARY`（置換しない）。`start` が長さ以上なら空文字と `hasMore = false`。
- 一覧：`listFiles()` を名前順（ディレクトリ先）に並べて 100 件まで。超えた分は結果の末尾に `[+N more]`。
- 権限を失っている（`SecurityException`、`persistedUriPermissions` に無い）→ `EACCES`。
- 上書きの原子性はない（SAF には一時ファイル + 置き換えの手段が無い）。書き込みは最大 200KB で、途中で落ちたときに内容が中途半端になる可能性があることを、仕様として受け入れる（U にしない。承認ダイアログで旧サイズを見せる）。
- SAF の提供元がクラウドストレージ（Google ドライブ等）のときは、そのサービスに保存される。アプリは検出できないので、フォルダ選択前の説明文（7.3）で注意する。

## 7. 画面仕様・文字列

### 7.1 チャット画面（A）

- 要約の生成中：返答の吹き出しの位置に「会話が長くなったので、古い部分を要約しています…」（`chat_compressing`）。ローカルは数十秒かかることがあるので、停止ボタンで中断できることが分かるよう、通常の「考え中」表示と同じ位置・同じ停止ボタンを使う。
- 要約済みの区切り：`messages[summaryCount]` の直前に細い区切り線と「ここより前は要約して AI に渡しています」（`chat_summary_divider`）。表示される発言は消えない。区切り線をタップすると `SummaryDialog`（要約の全文、「閉じる」、「要約をやめる」）。「要約をやめる」は `clearSummary`（`summaryCount = 0`）。以後は要約なしで送られ、必要ならまた自動で作られる。
- 圧縮した直後の 1 回だけ、返答の上に小さな注記「会話が長くなったので、古い部分を要約しました」（`chat_compress_done`）。失敗したときは「要約できなかったので、古い発言を省いて続けました」（`chat_compress_failed`）。どちらも次の送信で消える（`trimmedNotice` と同じ扱い）。
- 要約に使った発言数が 0 の会話（`summaryCount == 0`）では区切り線を出さない。

### 7.2 チャット画面（B）

- 返答の中のツールのタグは、文章の一部として生のタグを見せず、`ToolCallCard` にする：「作業フォルダ: `notes/a.txt` に書き込み」「…を読み取り」「…の一覧」。状態は 実行中／完了／拒否／失敗／実行されませんでした。
- ツール結果の `user` 行（`ToolResult.PREFIX` で始まる）は、利用者の吹き出しとしては出さず、小さな灰色のカード「ファイル操作の結果（タップで表示）」に折りたたむ。タップで本文を展開。
- 書き込みの承認ダイアログ（`ToolApprovalDialog`）：
  - タイトル「ファイルを書き込みますか？」
  - 本文：作業フォルダ名、パス、「新しいファイルを作成します」または「**既存のファイルを置き換えます**（現在 N バイト → 新しい M バイト）」、書き込む内容の先頭 1,500 文字（等幅、スクロール可。超えた分は「…あと N 文字」）。
  - ボタン：「書き込む」「書き込まない」。外側タップ・戻るは「書き込まない」。上書きのときは「書き込む」を赤系のテキストボタンにする。「今後は確認しない」は作らない。
- 往復の途中の表示：`toolStatus`（「notes/a.txt を読んでいます…」）を、生成中の表示の位置に出す。往復の上限などで止まったときは `toolStopNotice` の文言を注記として出す。

### 7.3 設定画面

- 「会話の自動圧縮」スイッチ（`SettingsScreen` の生成設定の近く）。説明「会話が長くなったとき、古い部分を要約して続けます。クラウドのモデルでは、要約のための送信が 1 回増え、API の料金がかかります」。
- 「作業フォルダ」画面（`WorkspaceScreen`、`settings/workspace`）：
  1. 説明（常時表示）：できること（端末内モデルが、選んだフォルダの中のテキストファイルを読み、新しく作る・置き換える）、できないこと（フォルダの外、削除、クラウドのモデル、バイナリ）、書き込みは毎回確認すること、読み取りは確認なしで行うこと、クラウドストレージ上のフォルダを選ぶとそのサービスに保存されること。
  2. 現在のフォルダ名（または「未選択」）と「フォルダを選ぶ」「解除」。権限を失っているときの警告。
  3. 「AI にファイル操作を許可」スイッチ（既定オフ。フォルダ未選択のときは無効）。
  4. 初めてオンにするとき 1 回だけ、確認ダイアログ「AI がこのフォルダのファイルを読めるようになります。クラウドのモデルには使われません。続けますか？」（`workspace_first_enable_*`。確認済みフラグは持たず、毎回オンにするたびに出す。回数が少ないため）。

### 7.4 文字列キー（4 言語。R19）

`chat_compressing`、`chat_summary_divider`、`chat_compress_done`、`chat_compress_failed`、`summary_dialog_title`、`summary_dialog_reset`、`settings_auto_compress`、`settings_auto_compress_desc`、`settings_workspace_entry`、`workspace_title`、`workspace_desc`、`workspace_pick`、`workspace_clear`、`workspace_none`、`workspace_enable`、`workspace_no_permission`、`workspace_error_permission`、`workspace_cloud_unavailable`、`workspace_first_enable_title`、`workspace_first_enable_body`、`tool_card_list`、`tool_card_read`、`tool_card_write`、`tool_state_running`、`tool_state_done`、`tool_state_denied`、`tool_state_failed`、`tool_state_not_run`、`tool_result_card`、`tool_status_reading`、`tool_status_writing`、`tool_status_listing`、`tool_approve_title`、`tool_approve_new`、`tool_approve_overwrite`、`tool_approve_yes`、`tool_approve_no`、`tool_stop_turn_limit`、`tool_stop_format`、`cloud_confirm_file_note`。
ツールのタグ、`ToolResult` の形式、`SUMMARIZE_SYSTEM`、`WORKSPACE_ADDENDUM` は英語固定（`strings.xml` に入れない。JsHandoff と同じ例外）。

## 8. セキュリティとプライバシー（R10〜R19）

### 8.1 作業フォルダの安全（R13, R14, R15）

| 項目 | 対処 |
|---|---|
| フォルダの外 | 二重に守る。(1) 字句：`JsPathPolicy.normalize` で絶対パス・`..`・`\`・`:`・制御文字を拒否（`sub/../x` も拒否）。(2) 実体：SAF のルートから名前 1 要素ずつたどる。URI 権限の範囲外に OS が出さない |
| 書き込みの承認 | 書き込みは必ず `pendingToolApproval` を経由する。`WorkspaceToolRunner.execute(Write)` を、承認の確認を持たない経路から呼ばない（ViewModel のどこにも「承認なしの書き込み」の入口を作らない。承認済みの値は `CompletableDeferred` の結果だけ）。再開した履歴のタグは自動実行しない |
| 上書き | 承認ダイアログで「置き換え」と明示。旧サイズを表示。バックアップは作らない（U にしない。上書きされたくないファイルは、承認しなければよい） |
| 削除 | ツールが存在しない（`<file_delete>` は `Malformed` で実行されない）。モデルがファイルを空にして実質削除することは、上書きの承認で防ぐ |
| 件数・大きさ | 1 返答 1 操作、1 送信 6 往復、書き込み 200KB、1 送信の書き込み合計 500KB、読み取りはコンテキスト長に応じ 1,000〜8,000 文字、一覧 100 件、1 操作 10 秒 |
| プロンプト注入 | 読んだファイルの中に「別のファイルを書け」といった指示があっても、書き込みは毎回の承認が要る。端末内モデルにはネットワーク手段が無い（JS 実行機能のネットワークは無く、クラウドでは使えない）ので、読んだ内容が外へ出る経路が無い |
| テキスト以外 | NUL を含む・UTF-8 でないファイルは読まない（`EBINARY`） |
| 権限の範囲 | `ACTION_OPEN_DOCUMENT_TREE` で選ばれたフォルダの配下だけ。`MANAGE_EXTERNAL_STORAGE`・`READ_EXTERNAL_STORAGE` は宣言しない。OS が、ストレージのルート・`Download`・`Android/data` 等を選ばせない（API 30 以降。選んだ画面で OS が拒否する） |
| JS 実行機能との独立 | JS の `fs` は `filesDir/js-workspace/` だけ。作業フォルダの URI を JS の runner に渡さない（runner プロセスは URI 権限の共有もしない） |

### 8.2 クラウドの扱い（R16）

6.5 のとおり。要点：クラウドではツールを使えない、過去のツール結果の本文は送らない、切り替えの確認に注記を足す。**クラウドでもファイル操作を許す案（U6 の別案）を採る場合は、別途「ファイルの内容をクラウドへ送る」ことの同意（提供元ごと）、Data safety の回答（「ファイルとドキュメント」を収集・共有）、プライバシーポリシーの変更が必要になる**。この設計はそれを含まない。

### 8.3 要約のプライバシー（R6）

- 要約は端末内（Room）にだけ保存される。バックアップ対象外（`allowBackup=false` のまま）。
- ローカルで作った要約を、あとでクラウドへ切り替えた会話で送る場合：会話に `origin = "local"` の発言が必ずあるので、既存の `needsCloudHistoryConfirm` の確認が先に出る。要約はその「これまでのやり取り」の一部として扱う。ダイアログには 6.5 の注記（ファイルの内容の一部が返答や要約に含まれる場合がある）を、ツール結果または要約がある会話でだけ足す。
- 会話を削除すると要約も消える（`conversation` の列のため）。

### 8.4 Google Play

- SAF のフォルダ選択は権限不要で、「すべてのファイルへのアクセス」の審査対象にならない（`MANAGE_EXTERNAL_STORAGE` を使わないため。Play の公式文書は今回は未確認。U16）。
- 権限一覧（release-checklist 4.10）は変わらない。
- AI 生成コンテンツのポリシー：書き込まれたファイルは AI の出力。既存の「AI 生成」表示と「報告」ボタンの対象は返答なので、変更なし。

### 8.5 プライバシーポリシー・Data safety への影響（R19）

追記案（実際の反映は実装完了後。4 言語。ユーザー確認後）:

| 場所 | 追記・変更 |
|---|---|
| `store/privacy/privacy-policy.*.md` 1 節（端末の中に保存される情報） | 「作業フォルダを設定した場合: 選んだフォルダの識別情報（場所を示す URI とフォルダ名）。フォルダの中身は利用者の端末の保存領域にあり、アプリは受け取りません」「会話の要約（会話ごとに 1 つ）」 |
| 同 2 節（インターネット通信） | 「作業フォルダのファイルの内容は、端末内のモデルにだけ渡され、通信しません。クラウドの AI を使っているときは、作業フォルダは使えず、ファイルの内容は送りません（以前に端末内モデルがファイルを読んだ会話をクラウドに切り替えた場合も、読み取り結果は省いて送ります）」「会話が長くなったとき、古い部分を要約するために、クラウドの AI には要約用の送信が増えます（送る内容は通常の会話の送信と同じです）」 |
| 同 3 節（収集しないもの） | 「利用者が選んだ作業フォルダ以外のファイルには、アクセスしません」 |
| 同 4 節（使う権限） | 追記なし（SAF は権限が不要である旨を 1 文足してもよい） |
| 同 6 節（情報の削除） | 「作業フォルダに作られたファイルは、利用者の保存領域にあり、アプリを削除しても消えません。フォルダの設定は設定画面から解除できます」 |
| `docs/release-checklist.md` 4.4 | 「作業フォルダ機能の追加後も、回答案（5 章）を変えない。ただし、クラウドでファイル操作を許す案に変更した場合は 5.3 に『ファイルとドキュメント』を追加」 |
| 同 5.3 の「収集しないもの」 | 「ファイル（利用者が選んだ作業フォルダの中身。端末内モデルが処理し、端末の外へ送らない）」を理由つきで追記 |
| 同 5.2 / D2 | 「メッセージ」の共有の回答に変更なし（要約リクエストも同じ種類の送信）。送信量が増えることだけ補足 |
| 同 4.10 | 「ストレージの権限を宣言しない」を明記 |
| `設計書.md` 8.3・11 章 | 13 章の追記案 |

## 9. エラーハンドリングと境界条件

| 状況 | 動作 |
|---|---|
| `autoCompress` が false | 圧縮しない。今までどおり（ネイティブが古い発言を落とす／`trimForCloud`） |
| `limit < MIN_LOCAL_LIMIT` | 圧縮しない |
| 最新の User 発言 1 件だけで上限を超える（貼り付けが巨大） | `planCompression` が null。ネイティブ層の `E_TOO_LONG` など既存の挙動 |
| 要約が覆う発言より後ろだけが残る（先頭が Assistant になる） | 4.2 の規則 3・5 で User から始める |
| 再試行（`retry`） | 要約は最後の User 発言より前だけを覆うので有効のまま。`deleteMessagesAfter` は要約が覆う行に触れない。再試行時も `maybeCompress` を通る（既に trigger 以下なら何もしない） |
| 履歴からの再開（`open`） | `conv.summaryText`・`summaryCount` を状態に読む。`rows.size < summaryCount`（不整合）なら `clearSummary` して 0 にする |
| 新しい会話 | `summaryCount = 0`、`summaryText = ""` |
| 会話の途中でモデル（`n_ctx`）が変わる | 送信のたびに `limit`・`trigger` を計算し直す。既存の要約は有効のまま使う |
| ツール：パス不正 | `status: error` / `EPERM` / `EINVAL` / `ENAMETOOLONG` の結果をモデルに返す |
| ツール：ファイルが無い／ディレクトリを読もうとした | `ENOENT` / `EISDIR` |
| ツール：書き込み先の途中がファイル | `ENOTDIR` |
| ツール：権限を失った・フォルダが消えた | `EACCES`。往復を止め、`toolStopNotice` に「フォルダにアクセスできません。設定で選び直してください」 |
| ツール：SAF 提供元が固まる | `OP_TIMEOUT_MS` で `ETIMEDOUT` |
| ツール：最大トークン数で書き込みが途中で切れた | `Incomplete` で実行しない。モデルへ `FORMAT`（`file too long; write a shorter file or split it`）を返す。`maxTokensNotice` は既存どおり出す |
| ツール：書き込みの 1 回あたり 200KB 超 | `EFBIG`（承認前に断る） |
| ツール：同じ返答に 2 つ目のタグ | 1 つ目の完成の時点で生成を止めているので出ない。止める前に出た分は捨てる |
| 承認待ち中に会話を切り替える／新しい会話 | 拒否として扱い、往復を終了（`open`/`newConversation` が `job.cancel` するのでキャンセルされる。承認ダイアログも消す） |
| 承認待ち中にアプリのプロセスが死ぬ | 何も実行されない。再開時はタグが「実行されませんでした」 |
| ツールの往復中に設定でスイッチをオフ／フォルダ解除 | 往復を中断（実行中の操作は完了を待たず取り消す） |
| 作業フォルダの有効化の直後に既存の会話で送信 | その送信からシステム文に追記される。KV キャッシュの再利用が 1 回だけ効かない |
| ローカルモデルがタグの形式を守らない | `FORMAT` エラーを 2 回まで返して再試行させ、それでも守らなければ止める。ツールの成功率はモデル依存（U14） |

## 10. テスト方針

### 10.1 単体テスト（JVM、JUnit4。`src/test/java/com/mobileailocal/`）

| 要件 | テスト |
|---|---|
| R2 | `TokenEstimatorTest`：空→0、ASCII 35 文字→10、日本語 10 文字→10、混在、メッセージごとの +8、改行・絵文字で例外なし。`localLimit` が `mal_llama.cpp` の式と一致する表（4.2 の例の 4 行）。`localTrigger`/`localKeep`/`localSummaryMaxTokens` の境界（`coerceIn` の下限 96・上限 512） |
| R1, R4, R9 | `ContextCompressionTest`：(1) `total <= trigger` で null。(2) 超えると `newSummaryCount` が進み、残りが `keep` 以下。(3) 最新の User 1 件だけで `keep` を超えても残す。(4) 残る先頭が Assistant にならない。(5) 要約する範囲が空（1 件だけ）で null。(6) `inputBudget` が小さいとき、古い側から入るだけで `c` が User の手前になる。(7) `summaryCount > 0` のとき `active` が `drop(summaryCount)` から始まる。(8) 2 回続けて呼んだとき `newSummaryCount` が単調に増える。(9) 再試行を想定：最後の User までの `history` で `newSummaryCount <= history.lastIndex` |
| R1 | 同：クラウド（`sizeOf` = 文字数、trigger 45,000）で同じ性質 |
| R3 | `MigrationSchemaTest` に `MIGRATION_2_3` を追加：`startVersion=2`/`endVersion=3`、2 文の SQL が `NOT NULL DEFAULT` 付き、`3.json` の `conversation` の列定義と一致（既存の 1→2 のテストと同じ流儀） |
| R3 | `DataLogicTest` 流の DAO 相当：`updateSummary` / `clearSummary` で列が変わり、メッセージ行は変わらない |
| R5 | 偽のエンジンで `maybeCompress` を呼ぶ試験（ViewModel から切り出した純粋な関数にするか、`FakeEngine` を使う）：例外→保存なし・失敗の注記・抑止（発言 4 件増えるまで再試行しない）、空の要約→失敗、長すぎる要約→失敗、`CancellationException`→再送出で保存なし |
| R1 | `Prompts.summarizeMessages`：前の要約が空なら見出しごと省く、各発言が `lineCap` で切られる、ツール結果は 400 文字で切れる。`summaryBlock("")` が空 |
| R1 | `Prompts.systemFor`：JS・作業フォルダ・要約の組み合わせ（有効／無効、クラウドは JS と作業フォルダを足さないが要約は足す）の順序と空行 |
| R11, R12 | `ToolCallParserTest`：list/read/write の正常、属性の引用符（`"`/`'`）、`start`、本文の前後の改行の除去、本文全体の ``` の囲みの除去、`\r\n` 統一、`Incomplete`（閉じなし）、`Malformed`（未知タグ `file_delete`、未知属性、`path` 無し）、コードブロック内のタグは `NoCall`、文章 + タグ + 後ろの文章で `endIndex` が正しい、`<tool_call>` は `NoCall`、思考過程（`<think>`）内のタグは対象外（`answerOf` 済みを前提にした呼び出しのテスト）、`mayComplete` の条件 |
| R11 | `ToolResultFormatTest`：各 op の形式、`PREFIX` の判定、`forCloud` が本文を省略し他の行を変えない、失敗・拒否・`FORMAT` の形式 |
| R13 | `JsPathPolicyTest` の既存ケースを、`SafWorkspace` の入口（偽の `DocumentFile` 層ではなく、パス正規化を通す純関数）に対しても確認：`../x`、`/etc/hosts`、`a/../b`、`C:x`、`\`、NUL、513 文字、階層 9、1 要素 101 文字がすべて拒否 |
| R12, R15 | `WorkspaceToolRunnerTest`（偽の `WorkspaceFiles`）：list の並び（ディレクトリ先）と 100 件の切り詰め、read の `start` と `[more: start=N]`、`readBudgetChars` の境界、write の新規／上書きの結果文、200KB 超で `EFBIG`、`WorkspaceException` が `Failed` に変換される、`CancellationException` は再送出、`preview` が新規／上書き／旧サイズを返す |
| R14 | 承認の経路：`Write` は承認なしでは `execute` に到達しない（ViewModel から切り出した承認の状態機械のテスト）。拒否・時間切れ・停止で `Denied`、許可で実行 |

### 10.2 実機・エミュレーターでの確認（手動）

A. 圧縮（ローカル、`contextLength` 2048 と 4096、arm64 の実機で）

1. 長い会話（trigger を超えるまで）を続けて、要約が自動で作られ、区切り線が出て、表示された発言が消えないこと（R1, R3, R7）。
2. 要約中に停止ボタン→要約は保存されず、通常の停止になる。続けて送信できる（R5）。
3. 要約済みの会話で「再試行」→ 最後の返答だけが作り直され、区切り線の位置が変わらない（R4）。
4. アプリを終了して履歴から開き直す→ 区切り線と要約が復元される。「要約をやめる」で消える（R3, R4）。
5. 設定で自動圧縮をオフ→ 圧縮せず、今までの挙動（R8, R9）。
6. 要約の生成時間と、要約後の最初の返答の遅さ（KV キャッシュが無効になる分）を記録（U15）。
7. クラウド：45,000 文字を超える会話で、要約が 1 回作られ、`trimmedNotice` が出なくなる。要約の失敗（キーを無効にして試す）で、通常の `ApiError` の表示が 1 回だけ出る（R5, R6）。
8. ローカルで作った要約つきの会話をクラウドに切り替え→ 既存の確認が出てから送られる（R6）。
9. Room v2 の既存データがあるビルドから更新→ 会話が残り、要約は空（R3）。

B. 作業フォルダ（ローカル、実機で小型モデル 2〜3 種：Qwen 系、Gemma 系、Llama 系）

1. 設定で「フォルダを選ぶ」→ 選択→ アプリを再起動しても使える（永続権限）。ストレージのルートや `Download` を選ぶと OS が拒否する（R10）。
2. 「現在のフォルダの一覧を見て」→ `<file_list>` が出て、結果が返り、続きの文章が出る（R11, R12）。
3. 「`memo.txt` を作って、…と書いて」→ 承認ダイアログ（新規）→ 許可→ ファイルが OS のファイルアプリで見える（R12, R14）。
4. 同じ名前でもう一度→ ダイアログが「置き換え」になり、旧サイズが出る。拒否→ ファイルが変わらず、モデルに拒否が返る（R14）。
5. 「`../x.txt` に書いて」「`/sdcard/x.txt` に書いて」→ エラーの結果が返り、何も作られない（R13）。
6. 長いファイル（20,000 文字）を読ませる→ 途中までと `[more: start=N]`、続きを読める（R15）。
7. 最大トークン数で書き込みが切れる（長い本文を頼む）→ 実行されず `FORMAT` が返る（R15）。
8. 承認ダイアログを出したままホームへ→ 戻るとダイアログが残っている。5 分放置→ 拒否になる（R14）。
9. 往復の上限：「ファイルを 10 個作って」→ 6 往復で止まり、注記が出る（R15）。
10. 小型モデルでの成功率（タグの形式を守る割合）を 10 回ずつ記録し、`WORKSPACE_ADDENDUM` を調整する（U14）。
11. クラウドを選ぶ→ ファイル操作の説明が追記されず、タグが実行されない。ツール結果のある会話をクラウドへ切り替え→ 結果が省かれた内容で送られる（確認ダイアログの注記も出る）（R16）。
12. 設定で作業フォルダを解除→ 実行中の往復が止まる。OS 側で権限を取り消した場合→ `EACCES` と設定の警告（R10, R17）。
13. JS 実行機能が有効でも、JS の `fs` が作業フォルダに触れない（R18）。
14. 4 言語の文字列（R19）。
15. 履歴を開き直す→ 承認待ちのまま終わったタグが「実行されませんでした」で、自動実行されない（R14）。

## 11. タスク分割

設計書.md 15 章の方針（根幹は Claude 側の implementer、細かいコードは Strata）と js-runtime.md の 14 章の書き方に合わせる。T の番号は依存順。

| 番号 | 内容 | 担当 | 完了条件 |
|---|---|---|---|
| T0 | **事前検証**（B）：実機の小型モデル（Qwen 系・Gemma 系・Llama 系）で、`WORKSPACE_ADDENDUM` を付けて「ファイルを作って」と頼み、タグの形式を守る割合を測る。守れない場合は形式の再検討を U9 でユーザーへ報告 | implementer + ユーザー（実機） | 成功率の数字が出る。極端に低い（例：10 回中 5 回未満）ならタグの形式を見直す |
| T1 | `TokenEstimator`、`ContextBudget`、`planCompression`、`Prompts` の追加（要約・システム文の組み立て）+ 単体テスト | implementer（`planCompression`・`ContextBudget`）+ Strata（`TokenEstimator`・`Prompts`・テスト） | 10.1 の R1/R2/R4/R9 の行が通る |
| T2 | Room v3：`Entities.kt`、`Migrations.kt`、`App.kt`、`MigrationSchemaTest`、`3.json` | implementer（移行）+ Strata（テスト） | 10.1 の R3 の行。v2 の DB から更新して会話が残る（10.2 A-9） |
| T3 | `SettingsRepository` に `autoCompress` / `workspace*`。設定の「自動圧縮」スイッチ | Strata（見本：`jsEnabled`、`wifiOnly`） | 設定が保存・復元される |
| T4 | `ChatViewModel` の圧縮の組み込み（`maybeCompress`、状態、保存、`open`/`newConversation`/`retry` との整合）。可能なら `maybeCompress` の中核を純粋な関数・クラスに切り出してテスト | implementer | 10.1 の R5。10.2 A-1〜5 |
| T5 | チャット画面の圧縮の表示：`SummaryDivider`、要約中の表示、注記、`SummaryDialog` + 文字列（A 分） | Strata（見本：`trimmedNotice` の表示、システムプロンプトの `AlertDialog`） | 10.2 A-1〜4 |
| T6 | （任意）`InferenceEngine.countTokens` + JNI `nativeCountTokens`。入ったら `ContextBudget` のローカル判定に使い、見積もりは JNI が使えないときの代替にする | implementer（JNI） | 見積もりとの誤差を記録。U3 の判断材料 |
| T7 | `ToolModels`、`ToolCallParser`、`ToolResultFormat` + テスト | implementer（`ToolCallParser`）+ Strata（`ToolResultFormat`・テスト。規則は 5.1 を貼る） | 10.1 の R11 の行 |
| T8 | `WorkspaceToolRunner` + 偽の `WorkspaceFiles` + テスト | implementer（上限の適用）+ Strata（テスト） | 10.1 の R12/R15 の行 |
| T9 | `SafWorkspace`（`DocumentFile`、パス解決、`createFile` の注意点、タイムアウト、権限確認）、依存の追加 | implementer | 10.2 B-1〜3、5、6 |
| T10 | 作業フォルダ画面：`WorkspaceViewModel`/`WorkspaceScreen`、`OpenDocumentTree`、永続権限、`AppRoot` の経路 + 文字列 | Strata（見本：`ExtensionsScreen`/`ExtensionsViewModel`）。権限の取得・解除は implementer が指示書に手順を書く | 10.2 B-1、12 |
| T11 | `ChatViewModel` のツール往復：早期停止、承認の待ち（`CompletableDeferred`）、往復の上限、保存、`generate` の 2 関数化、`Prompts.systemFor` の接続、設定変化での中断 | implementer | 10.1 の R14（承認の状態機械）。10.2 B-2〜9、15 |
| T12 | チャット画面のツール表示：`ToolCallCard`、ツール結果カード、`ToolApprovalDialog`、`Bubble` の描き分け + 文字列 | Strata（見本：`CodeBlockView`、`JsConfirmDialog`） | 10.2 B-2〜4、8 |
| T13 | クラウドとの境界：`ToolResultFormat.forCloud` の適用、切り替え確認ダイアログの注記、クラウド選択時の無効化 | implementer | 10.2 B-11、A-8 |
| T14 | 文書：プライバシーポリシー 4 言語、`release-checklist.md`、`設計書.md` の更新（13 章）。ストア掲載文の更新 | Claude（leader の判断で）/ implementer（ユーザー確認後） | 8.5 の表が反映される |
| T15 | 総合確認：4 言語、release ビルド、実機（arm64）、10.2 の全項目、容量（`documentfile` の増分） | implementer + ユーザー | 10.2 が通る |

実装順の目安：A は T1 → T2 → T3 → T4 → T5（T6 は任意）。B は T0 → T7 → T8 → T9 → T10 → T11 → T12 → T13。A と B は独立に進められるが、`ChatViewModel.generate` を両方が触るので、**T4 の後に T11** の順にする（衝突を避ける）。T14・T15 は最後。

Strata に任せる指示書には、4〜5 章の型・規則と該当の見本を貼り、設計書の書き換えは禁止とする。

## 12. 未決事項（ユーザー判断が要るもの。推奨案つき）

| 番号 | 事項 | 選択肢 | 推奨案と理由 |
|---|---|---|---|
| U1 | 自動圧縮の既定 | (a) 既定オン、設定で切れる (b) 既定オフ | **(a)**。要望が「自動で」なので。圧縮の失敗時は今までの挙動に戻るので、害が小さい |
| U2 | クラウドでも要約を作るか（要約のたびにクラウドへの追加リクエストと、利用者の API の料金が発生する） | (a) クラウドも同じスイッチで要約 (b) クラウドは要約せず今までの `trimForCloud` のまま (c) クラウド用の別スイッチ | **(a)**。クラウドの上限は 60,000 文字と大きく、要約が走るのは稀で、1 回の追加が小さい。料金の注意は設定の説明文に書く。料金に敏感な人は (c) を望むかもしれない |
| U3 | トークン数の見積もり | (a) 文字からの推定式（`TokenEstimator`）だけ (b) ローカルは JNI で実測（T6）+ 推定式を代替 | **(a) で始め、実機で誤差が大きければ (b)**。(a) は多めに出る式で、外れても既存のネイティブ側の削除が受け止める。(b) は C++ と `InferenceEngine` の変更が要る |
| U4 | 要約の表示 | (a) 区切り線 + タップで全文・「要約をやめる」 (b) 注記だけ (c) 要約を吹き出しとして履歴に出す | **(a)**。何が AI に渡っているか分かる（クラウドへ送られる内容の確認にもなる）。(c) は表示が崩れ、`Role` の追加も要る |
| U5 | 「作業フォルダ」と JS 実行機能の内部作業フォルダの関係 | (a) 別物のまま（JS は `filesDir/js-workspace/`、新機能は利用者が選んだ SAF フォルダ）。名称で区別する (b) JS の `fs` も新しいフォルダを指す (c) 新機能が内部の `js-workspace` を使う | **(a)**。(b) は runner プロセスに SAF の権限を持たせる設計変更が要り、JS の安全設計（js-runtime.md 5.3）を作り直す。js-runtime.md 2.2 の「作業フォルダの中身を取り出す手段なし」と、設計書.md 8.3 の「SAF は行わない」は、新機能のために書き換える（13 章）。UI の呼び名は新機能を「作業フォルダ」、JS 側は説明文で「実行用の内部フォルダ」と区別する |
| U6 | クラウドモデルでのファイル操作 | (a) 使えない（ローカル専用） (b) 提供元ごとの追加の同意つきで使える | **(a)**。要望も「ローカルAI」。(b) は Data safety に「ファイルとドキュメント」の収集・共有が増え、ポリシー・審査の負担が大きい。過去のツール結果をクラウドへ送らない処理（6.5）を併せて入れる |
| U7 | 削除のツール | (a) 作らない (b) 承認つきで作る | **(a)**。「作成」が要望。上書きは承認で守る。必要になったら別の設計にする |
| U8 | 承認の範囲 | (a) 書き込みだけ毎回承認、読み取り・一覧は承認なし (b) 読み取りも毎回承認 | **(a)**。読み取りは端末内モデルにだけ渡り、外へ出ない（クラウド不可のため）。(b) は小型モデルの探索（一覧→読み取り）のたびにダイアログが出て実用にならない |
| U9 | ツール呼び出しの形式 | (a) 本文を生で書ける XML 風タグ + 1 返答 1 操作 (b) JSON の関数呼び出し (c) チャットテンプレートの `tools`（ネイティブ） | **(a)**。JSON は小型モデルが改行・引用符のエスケープを壊しやすい。(c) は現在の `llama_chat_apply_template` では渡せない。1 返答 1 操作にするのは、結果の作り話を防ぎ、生成時間を減らすため。**T0 で実機の成功率を測って最終確認する**（未検証） |
| U10 | `androidx.documentfile` の依存追加 | (a) 追加する（小さい AAR） (b) `DocumentsContract` を直接使う（依存なし、コードが増える） | **(a)** |
| U11 | クラウドへの切り替えで過去のファイル操作の結果を送るか | (a) 本文を省いて送る（確認ダイアログに注記） (b) そのまま送る（確認のみ） | **(a)**。ファイルの内容は利用者が想定する「会話」より機微が高い可能性がある |
| U12 | ツール結果の保存形式 | (a) `role = "user"` + `[File tool result]` の接頭辞（スキーマ変更なし） (b) `message.kind` 列を足す（v3 に含める） | **(a)**。移行が増えない。接頭辞の偽装（利用者が同じ文字で書き始める）はカードの表示が変わるだけで害がない |
| U13 | 承認待ちの上限時間 | 5 分で拒否と同じ | **5 分**（固定。生成を止めたままフォアグラウンドサービスを長く残さないため） |
| U14 | 小型モデルがタグの形式を守る成功率 | 実機で測る（T0） | 測定前なので推奨なし。極端に低い場合は、形式（属性の簡略化、固定の例を足す）を調整。調整で足りなければ機能を「対応モデルを限る」として出す |
| U15 | 見積もりの係数・閾値（70%・35%・ASCII 3.5 文字／日本語 1 文字 1 トークン・要約の最大トークン）と、要約の生成時間 | 推測値 | 実機で調整する（10.2 A-6）。当面は設計書の値で始める |
| U16 | Play の「すべてのファイルへのアクセス」の審査と SAF の扱い | `MANAGE_EXTERNAL_STORAGE` を使わないので対象外と考えるが、公式文書は今回の調査で確認していない | リリース前に公式の User Data / Permissions ポリシーを確認する（release-checklist に項目を足す） |
| U17 | 往復の上限（6 回）・書き込みの大きさ（200KB／送信ごと 500KB）・読み取りの文字数（1,000〜8,000） | 推測値 | 固定（js-runtime.md の P2 と同様、利用者は変更できない）。実機で調整 |
| U18 | 作業フォルダの有効化の確認ダイアログを毎回出す（7.3-4） | 毎回／初回のみ | **毎回**（スイッチをオンにする操作は稀で、毎回出しても邪魔にならない） |

## 13. `設計書.md` 等への追記が必要な箇所（この文書では更新しない）

ユーザー確認後に反映する。

| 文書 | 箇所 | 追記・変更 |
|---|---|---|
| `設計書.md` | 3.1 機能一覧 | 「会話の自動圧縮」「作業フォルダ（ファイル操作）」を追加し、実装状態の列を追加 |
| 同 | 8.1 Room | `conversation` に `summaryText`、`summaryCount` を追加（v3、`MIGRATION_2_3`）。あわせて、現行の表に無い `message.origin`（v2 で追加済み）と `cloud_integration` テーブルも反映する |
| 同 | 8.2 DataStore | `auto_compress`、`workspace_enabled`、`workspace_tree_uri`、`workspace_name` |
| 同 | 8.3 ファイル配置 | 「端末内ファイルの取り込み（Storage Access Framework）は行わない」を「利用者が選んだ 1 つの作業フォルダだけ SAF でアクセスする（読み書き。削除なし）。それ以外のファイルの取り込みは行わない」に変更。JS の内部作業フォルダは別物である旨を足す |
| 同 | 9 章 4（コンテキスト管理） | 「要約はしない」を、「収まらなくなる前に、古い発言を要約して置き換える（設定でオフにできる）。要約が使えない・失敗したときは従来どおり古い発言から落とす」に変更 |
| 同 | 10 章 非機能 | 要約の生成時間の目標（実測後）、ツールの 1 操作の時間 10 秒 |
| 同 | 11 章 プライバシー | 作業フォルダの扱い（端末内のみ、クラウドへ送らない、権限なし）と、要約の送信（クラウドでは追加の送信）を追記。権限の一覧は変わらない |
| 同 | 14 章 決定事項 | U1〜U13 の決定を記録 |
| 同 | 19.5・19.7 | クラウドでは作業フォルダが使えないこと、要約のための送信が増えること、Data safety の回答は変わらないこと |
| 同 | 20.4 | 実装状態に反映 |
| `docs/design/js-runtime.md` | 2.2 | 「作業フォルダの中身を取り出す手段なし」は JS の内部作業フォルダについてであり、新機能の作業フォルダとは別物である旨の注記（P1・P10 は変更しない） |
| `docs/design/cloud-api.md` | R8 の近く | クラウドの返答にはツールのタグがあっても実行しない（js-runtime.md の P7 と同じ） |
| `docs/release-checklist.md` | 4.4、4.10、5.3、D2 | 8.5 の表のとおり |
| `store/privacy/privacy-policy.{ja,en,zh-CN,zh-TW}.md` | 1・2・3・6 節 | 8.5 の表のとおり |
