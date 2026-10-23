# Mobile AI Local ストア掲載文(下書き)

Google Play の上限: アプリ名 30 文字、簡単な説明 80 文字、詳細な説明 4000 文字。
**公開前に確認すること**: Google Play で「Mobile AI Local」が他のアプリと重複しないか。下の「対応モデルの例」は、公開時点で実際に動くことを確かめてから載せる。端末の目安(RAM)は、実機での確認後に数字を見直す。

素材: `icon-512.png`(アプリアイコン 512×512)、`feature-graphic-en.png` / `feature-graphic-ja.png`(フィーチャーグラフィック 1024×500。中国語版は英語版を使うか、後で作る)。

---

## 日本語(ja)

**アプリ名**: Mobile AI Local

**簡単な説明**(80文字以内)
スマホの中だけで動く、プライベートなAIチャット。会話は端末の外に出ません。

**詳細な説明**
Mobile AI Local は、AI を「スマホの中だけ」で動かすチャットアプリです。モデルをダウンロードしたあとは、通信なしで使えます。会話の内容は、端末の外へ送られません。

■ 特長
・会話は端末の中だけ。アカウント登録、広告、利用状況の解析はありません。
・好きなモデルを選べます。Hugging Face の GGUF 形式のモデルを、アプリの中で検索して、そのままダウンロードできます。
・チャットの画面から、使うモデルをすぐに切り替えられます。
・会話の履歴を、端末の中に保存します。名前の変更、検索、削除ができます。
・会話ごとに、AI の役割(システムプロンプト)を設定できます。
・考えてから答えるモデルの「思考過程」は、回答のあとで折りたたまれ、押すと見られます。
・生成の速度、メモリ、端末の熱を、画面で確認できます。熱いときは警告します。
・温度、Top-p、最大トークン、コンテキスト長、スレッド数を調整できます。それぞれ短い説明つきです。
・生成中はバックグラウンドでも動き続けるので、アプリを閉じても回答が途切れにくくなっています。
・日本語、English、简体中文、繁體中文に対応しています。

■ こんな方に
・自分の会話を、外部のサーバーに送りたくない方
・オフラインの場所でも、AI を使いたい方
・いろいろなモデルを、手元で試したい方

■ ご利用にあたって
・モデルは、アプリに同梱されていません。Hugging Face からダウンロードします(1つあたり、数百MB〜数GB)。大きなモデルは、Wi-Fi でのダウンロードをおすすめします(「Wi-Fi のときだけ」の設定が、初期状態でオンです)。
・動かせるモデルの大きさは、端末の性能(特にメモリ)によります。小さなモデルから試してください。
・AI の回答には、誤りや不適切な内容が含まれることがあります。重要なことは、ご自身でご確認ください。
・各モデルには、それぞれの利用条件(ライセンス)があります。使う前に、Hugging Face のモデルのページで確認してください。

---

## English (en)

**App name**: Mobile AI Local

**Short description** (80 characters max)
Private AI chat that runs only on your phone. Your chats never leave the device.

**Full description**
Mobile AI Local is a chat app that runs AI entirely on your phone. After you download a model, it works without a connection. Your conversations are never sent off the device.

FEATURES
• Your chats stay on your device. No account, no ads, no usage analytics.
• Choose any model you like. Search for GGUF models on Hugging Face inside the app and download them directly.
• Switch models right from the chat screen.
• Conversation history is saved on your device. Rename, search, and delete.
• Set the AI's role (system prompt) for each conversation.
• For models that think before answering, the thinking is folded away once the answer arrives. Tap to read it.
• See generation speed, memory use, and device heat on screen, with a warning when the device is hot.
• Adjust temperature, top-p, max tokens, context length, and threads. Each comes with a short explanation.
• Generation keeps running in the background, so an answer is less likely to be interrupted when you leave the app.
• Available in English, 日本語, 简体中文, and 繁體中文.

WHO IT IS FOR
• People who do not want their conversations sent to an outside server
• People who want AI in places without a connection
• People who want to try many models locally

GOOD TO KNOW
• Models are not bundled with the app. You download them from Hugging Face (hundreds of MB to several GB each). For large models, Wi-Fi is recommended; the "Wi-Fi only" option is on by default.
• The size of model your phone can run depends on its performance, especially memory. Start with a small model.
• AI answers can be wrong or inappropriate. Check anything important yourself.
• Each model has its own license. Check the model's page on Hugging Face before you use it.

---

## 简体中文(zh-CN)

**应用名称**: Mobile AI Local

**简短说明**(80 字以内)
只在手机上运行的私密 AI 聊天。对话不会离开您的设备。

**详细说明**
Mobile AI Local 是一款让 AI 完全在手机上运行的聊天应用。下载模型之后,无需联网就能使用。您的对话内容不会被发送到设备之外。

■ 特点
• 对话只保存在设备上。无需注册账号,没有广告,也没有使用情况分析。
• 自由选择模型。可以在应用内搜索 Hugging Face 上的 GGUF 模型并直接下载。
• 在聊天界面就能快速切换模型。
• 对话历史保存在设备上,可重命名、搜索和删除。
• 可以为每个对话设置 AI 的角色(系统提示词)。
• 对于会先思考再回答的模型,回答出现后会折叠"思考过程",点击即可查看。
• 在屏幕上查看生成速度、内存和设备温度,过热时会提醒。
• 可调整温度、Top-p、最大令牌数、上下文长度和线程数,并附有简短说明。
• 生成会在后台继续运行,离开应用时回答不易被中断。
• 支持 English、日本語、简体中文、繁體中文。

■ 适合这些人
• 不想把对话发送到外部服务器的人
• 想在没有网络的地方使用 AI 的人
• 想在本地试用各种模型的人

■ 使用须知
• 应用不自带模型。模型需要从 Hugging Face 下载(每个几百 MB 到几 GB)。较大的模型建议在 Wi-Fi 下下载("仅在 Wi-Fi 下载"默认开启)。
• 手机能运行多大的模型,取决于性能(尤其是内存)。请先从小模型开始。
• AI 的回答可能有错误或不当之处。重要的事情请自行核实。
• 每个模型都有各自的使用条款(许可证)。使用前请在 Hugging Face 的模型页面确认。

---

## 繁體中文(zh-TW)

**應用程式名稱**: Mobile AI Local

**簡短說明**(80 字以內)
只在手機上執行的私密 AI 聊天。對話不會離開您的裝置。

**詳細說明**
Mobile AI Local 是一款讓 AI 完全在手機上執行的聊天應用程式。下載模型之後,不需要連線就能使用。您的對話內容不會被傳送到裝置之外。

■ 特色
• 對話只儲存在裝置上。不需註冊帳號,沒有廣告,也沒有使用情況分析。
• 自由選擇模型。可以在應用程式內搜尋 Hugging Face 上的 GGUF 模型並直接下載。
• 在聊天畫面就能快速切換模型。
• 對話歷史記錄儲存在裝置上,可重新命名、搜尋和刪除。
• 可以為每個對話設定 AI 的角色(系統提示詞)。
• 對於會先思考再回答的模型,回答出現後會摺疊「思考過程」,點一下即可查看。
• 在畫面上查看產生速度、記憶體和裝置溫度,過熱時會提醒。
• 可調整溫度、Top-p、最大權杖數、上下文長度和執行緒數,並附有簡短說明。
• 產生會在背景繼續執行,離開應用程式時回答較不易被中斷。
• 支援 English、日本語、简体中文、繁體中文。

■ 適合這些人
• 不想把對話傳送到外部伺服器的人
• 想在沒有網路的地方使用 AI 的人
• 想在本機試用各種模型的人

■ 使用須知
• 應用程式不內建模型。模型需要從 Hugging Face 下載(每個幾百 MB 到幾 GB)。較大的模型建議在 Wi-Fi 下下載(「僅在 Wi-Fi 下載」預設開啟)。
• 手機能執行多大的模型,取決於效能(尤其是記憶體)。請先從小模型開始。
• AI 的回答可能有錯誤或不當之處。重要的事情請自行確認。
• 每個模型都有各自的使用條款(授權)。使用前請在 Hugging Face 的模型頁面確認。
