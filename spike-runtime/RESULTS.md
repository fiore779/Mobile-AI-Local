# QuickJS 技術検証(スパイク)結果

検証日: 2026-10-23。環境: Android エミュレーター pixel_mal(x86_64、Android 35)。ライブラリ io.github.dokar3:quickjs-kt 1.0.15。
実機・arm64 での実行は未確認(arm64 は .so の構造とサイズのみ確認。実行はエミュレーターの x86_64 版)。
検証コードは spike-runtime/app/src/main/(RunnerService.kt = 実行本体、assets/tests/*.js = テスト)。実行結果の生ログは logcat タグ SPIKE。

## 結果一覧

| # | 項目 | 結果 |
|---|---|---|
| 1 | JS 実行・結果受け取り | OK |
| 2 | ホスト API の不在 | OK |
| 3 | 無限ループの時間切れ停止 | OK(ただし例外あり。下記) |
| 4 | メモリ上限と超過時の挙動 | OK |
| 5 | 作業フォルダ限定のファイル API | OK |
| 6 | 別プロセスの要否 | 推奨: 別プロセスにする(下記) |
| 7 | 16KB ページ対応 | OK |
| 8 | 大きさ | 小さい |
| 9 | バージョン・ライセンス | 1.0.15(最新)・Apache-2.0 |

## 1. JS 実行と結果の受け取り: OK
- `quickJs { evaluate<Any?>(code) }` で実行。最後の式の値は戻り値として取れた(`=> last-expression-value`)。
- console.log は QuickJS に標準では無いので、Kotlin 関数 `__log` を `function("__log")` で公開し、JS 側の prelude で console.log/info/warn/error に割り当てて Kotlin に集めた。オブジェクトは JSON、BigInt は `n` 付きで整形。
- 動いたもの: JSON、Date、Map/Set、class、async/await、トップレベル await、Promise、BigInt、正規表現、日本語文字列。
- 無いもの: Intl、TextEncoder、structuredClone、setTimeout 等のタイマー(下記のとおり自前で用意した)。toLocaleString は区切りなし。
- 初回実行 約 0.5 秒(JIT 無しの初期化 + サービス起動込みで 約 1 秒)、2 回目以降は数 ms〜数十 ms。
- throw した場合は QuickJsException(メッセージとスタック付き)で受け取れる。
- setTimeout / setInterval / sleep は `asyncFunction("__sleep")` + JS の prelude で作り、動作した(未 await のタイマーも、スクリプト終了前なら発火。await されない浮いた非同期は evaluate 終了時に切られる場合あり)。

## 2. ホスト API の不在: OK
globalThis を調べた結果(v2b_host.js):
- 無い(undefined): require, fetch, XMLHttpRequest, WebSocket, process, Buffer, module, exports, std, os, Deno, window, document, navigator, Java, java, android, Android, performance, crypto, localStorage, setImmediate, __dirname。
- 動的 `import("fs")` は `could not load module 'fs'` で失敗(モジュール読み込み元を提供していないため)。`require("fs")` は ReferenceError。
- 見えるのは JS 標準の組み込み + こちらが公開した console / fs / setTimeout 系 / sleep のみ。
- eval と new Function は使えるが、同じサンドボックス内なので問題なし。
- 実行ごとに新しい QuickJs を作るため、前回の globalThis.leak や Array.prototype の書き換えは次回に残らない(v2j、2 回連続実行で確認)。ファイルだけが残る。
- 注意: `__block` `__exit` `__oom` は検証用に私が公開した関数。本番には入れない。

## 3. 無限ループの時間切れ停止: OK
`evaluationTimeoutMillis` を設定すると QuickJS の割り込みハンドラで止まる(QuickJsInterruptedException)。
- `while(true){}`: 3000ms 設定で 3015ms で停止。停止後、同じ runner で次の実行が正常に動いた。
- async 関数内の無限ループ(2000ms 設定 → 2015ms)、破滅的正規表現 `/(a+)+$/`(2015ms、ネイティブの exec 内でも割り込めた)は停止できた。
- マイクロタスクの無限連鎖(Promise.then の再帰)は時間切れでは止まらず、メモリ上限(64MB)に当たって約 1.2 秒でエラー終了した。それでもプロセスは無事。
- 割り込めない例外: **ホスト(Kotlin)関数の中で固まった場合**。検証用 `__block`(Thread.sleep)は割り込めない。fs の関数は短いので現実には起きにくいが、将来ネットワーク等を公開するなら注意が必要。→ 項目 6 の理由の一つ。

## 4. メモリ上限: OK
- `memoryLimit = 64MB` を設定。大量に配列へ push する → 約 1 秒で QuickJsException(メッセージ null)で終了、プロセスは無事。次の実行も成功。
- `new Uint8Array(500MB)` は JS 側で `InternalError: out of memory` を投げ、try/catch で捕まえられた。巨大な文字列連結は `string too long`。
- 再帰は `maxStackSize = 256KB` で `InternalError: stack overflow` になり、catch できた(落ちない)。ただしこの再帰テストは console.log を 10 万回出す部分も含み、約 3.8 秒かかった(出力は 64KB で打ち切り)。
- 出力は 64KB(MAX_OUT)で `[output truncated]` として打ち切れた。
- 上限が効くのは QuickJS のヒープ。**ホスト側(Kotlin)で確保するメモリ(検証用 __oom)は QuickJS の上限と無関係**。この場合 `OutOfMemoryError` は runner のスレッドで捕まって error として返り、プロセスは生存した(v3g)。ただし ART のヒープが尽きた状態で他の部分が巻き込まれる可能性は残る。

## 5. 作業フォルダ内限定のファイル API: OK
Kotlin で `fs.writeFile / readFile / list / exists / remove` を `define("fs")` で公開。`File(root, rel).canonicalFile` で解決し、`root` 配下でなければ SecurityException(v2h、v2p)。
- `../x`、`sub/../../leak.txt` → 拒否。
- シンボリックリンク(作業フォルダ内に `/system/etc` へのディレクトリリンク、`/system/etc/hosts` へのファイルリンクを adb run-as で作成): list / read / write / remove すべて拒否(canonicalFile がリンクを解決するため)。
- 絶対パス `/etc/hosts` は、`File(root, "/etc/hosts")` が root 配下の `root/etc/hosts` として扱われるため、外には出ない(存在しなければ FileNotFound)。設計では「絶対パスは拒否」と明示した方が分かりやすい。
- 上限: 1 ファイル 1MB、100 ファイル、合計 10MB。いずれも超過で例外。
- 注意点(未対策): canonicalFile で判定してから開くまでの間に別プロセスがリンクを差し替える競合(TOCTOU)は考慮していない。作業フォルダに JS 以外が書き込まない前提なら問題なし。
- 注意点: 合計容量の計算は walkTopDown で毎回全走査(100 ファイル程度なら問題なし)。

## 6. 別プロセス(RunnerService)の要否: 推奨は「別プロセスにする」
同一プロセスで処理できるのは QuickJS 内の暴走だけ。
- 同一プロセスで足りる部分: 時間切れ(3)、メモリ超過(4)、スタック超過は、すべて QuickJS の機構で止まり、アプリは生きていた。
- 別プロセスの価値:
  1. ホスト関数でのブロックは割り込めない(上記)。別プロセスなら呼び出し側が kill して復帰できる。検証では、runner 内の監視(watchdog)による自殺 → 次の実行が新プロセスで成功(v3a/b、timeout+1.5 秒で復帰)、watchdog なしでも呼び出し側が kill して復帰(v3c/d)、runner の突然死も検知して次が成功(v3e/f)、ホスト側 OOM でも復帰(v3g/h)。
  2. QuickJS のネイティブ側のバグ(SIGSEGV)でアプリ本体(会話画面)が落ちるのを防げる。同一プロセスだと JNI のクラッシュは全体を道連れにする。
  3. 本体は llama.cpp で大きなメモリを使うので、JS の暴走が本体の OOM kill を招かないよう分離できる。
  4. 本体にネットワーク権限が付く場合でも、runner プロセスから JS が使える API は公開したものだけで、権限の分離を意識しやすい(ただし同じ UID・同じ権限なので OS レベルの隔離ではない。isolatedProcess を使えば権限を完全に落とせるが、作業フォルダへの直接アクセスができなくなるため今回の構成には向かない)。
- 費用: 初回の runner 起動 約 0.1〜0.4 秒(connectMs)。kill 後の再接続はすぐ rebind が必要(自動再接続はバックオフで 5 秒以上かかる場合があり、検証で一度「5 秒待ってから rebind」が発生した)。1 度に 1 件だけ実行。runner の Application.onCreate は別プロセスでも走るので、本体アプリの初期化(モデルのロード等)を Application に置かないこと。
- 結論: 時間とメモリだけなら同一プロセスで足りるが、将来の拡張(ホスト関数の追加)と本体の保護のため、別プロセス + 二重の時間監視(QuickJS 側の割り込み + 外側の watchdog)を推奨する。同一プロセスで始めるなら、必ず別スレッド実行 + 外側のタイムアウトを持つこと(ただし固まったスレッドは殺せない点が弱い)。

## 7. 16KB ページ対応: OK
llvm-readelf で libquickjs.so の LOAD セグメントのアライメントを確認。arm64-v8a、x86_64 とも 3 つの LOAD すべてが 0x4000(=16KB)。
注意: debug APK では .so が無圧縮(Stored)で入る。圧縮しない場合の zip 内 16KB アライメントは AGP 8.5 以降が自動で行う(AGP 8.7.3 で確認済みのビルド)。実機の 16KB 端末では未確認。

## 8. 大きさ
- libquickjs.so: arm64-v8a 924,936 バイト(約 0.9MB)、x86_64 911,248 バイト。
- release APK(arm64 のみ、R8 有効、コルーチン込み、空のアクティビティ付き): 1,149,678 バイト(約 1.1MB)。アプリ本体を除いた追加分は、.so(圧縮前 約 0.9MB)+ ライブラリの Kotlin コード(数百 KB 以内)の見込み。本体 APK との差分は未計測。
- 実行時メモリ: runner プロセスの PSS 約 20〜25MB(待機〜小さな実行)。64MB 上限まで使うテスト後は約 92〜97MB まで増えた。

## 9. バージョンとライセンス
- quickjs-kt 1.0.15(Maven Central の maven-metadata で latest/release とも 1.0.15、lastUpdated 2026-09-03)。ライセンスは POM に Apache License 2.0 と記載。
- 内蔵する QuickJS エンジン本体のライセンスは MIT と認識しているが、同梱ファイル等では確認できていない(未確認)。配布時に NOTICE を付ける際は、上流リポジトリで要確認。

## ビルド上の注意(設計時に重要)
- quickjs-kt 1.0.1〜1.0.15 の AAR は minCompileSdk=36 を宣言している。compileSdk 35 + AGP 8.7.3 では checkAarMetadata が失敗する。このスパイクでは `check*AarMetadata` タスクを無効化して回避した(build.gradle.kts)。本体で採用するなら、compileSdk 36 + 対応する AGP に上げるか、同様に回避するかを決める必要がある。
- Kotlin 2.4.10、AGP 8.7.3、Gradle 8.14.5、JDK 22 で `assembleDebug` / `assembleRelease` が通った。

## 設計への推奨
1. 別プロセス(:runner)+ Messenger。1 件ずつ実行。二重の時間監視: QuickJS の `evaluationTimeoutMillis`(例 5 秒)と、外側の watchdog(+1.5 秒で runner を kill)。クライアント側にも最後の保険(+2.5 秒で kill)。
2. 上限の案: 時間 5 秒(設定で変更可でも上限 30 秒)、QuickJS メモリ 64MB、スタック 256KB、出力 64KB、ファイル 1 件 1MB、ファイル数 100、作業フォルダ合計 10MB、実行するコード自体の長さの上限(例 100KB)。
3. API の形: 実行ごとに新しい QuickJs を作る(状態を持ち越さない)。`console.log/info/warn/error`、`fs.writeFile(path, text) / readFile(path) / list(dir) / exists(path) / remove(path)`(文字列のみ・同期風)、`setTimeout / setInterval / sleep`。パスは作業フォルダからの相対のみ、絶対パスと `..` は拒否と文書化。最後の式の値と console 出力を結果として返す。
4. 結果の状態: ok / error / timeout / crashed / killed をユーザーに区別して見せる。
5. 確認ダイアログでは実行する JS のコードをそのまま見せる(AI が書いた内容を人が読める形で)。
6. Application.onCreate は :runner でも動くので、重い初期化は避ける、またはプロセス名で分岐。
7. バイナリは扱わず文字列のみ(readFile / writeFile は UTF-8 テキスト)にすると単純。

## わからなかったこと・未確認
- 実機(特に arm64、16KB ページ端末)での動作と速度。エミュレーター x86_64 のみ。BlueStacks での確認もしていない。
- 本体 APK に追加した場合の実際の増分。
- QuickJS エンジン本体のライセンス表記の原文。
- マイクロタスク無限連鎖は時間切れでなくメモリ上限で止まった。停止時間は短いが、メモリ上限を大きくすると遅くなる。
- ホスト関数が固まった場合の watchdog は runner 側自殺と呼び出し側 kill の両方で復帰を確認したが、発生頻度の高い連続実行(負荷試験)は未実施。
- 絶対パスの扱い、TOCTOU 競合は上記のとおり未対策。
