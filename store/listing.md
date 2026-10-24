# Mobile AI Local ストア掲載文(下書き)

Google Play の上限: アプリ名 30 文字、簡単な説明 80 文字、詳細な説明 4000 文字。
**公開前に確認すること**: Google Play で「Mobile AI Local」が他のアプリと重複しないか。下の「対応モデルの例」は、公開時点で実際に動くことを確かめてから載せる。端末の目安(RAM)は、実機での確認後に数字を見直す。

**書き方の方針**(最終更新 2026-10-24。Play のストア掲載情報の要件〔簡単な説明は 80 文字まで、"Best" "#1" などの順位・受賞の表現、「今すぐダウンロード」のような行動の呼びかけ、絵文字・記号の装飾は使わない。公式: Add preview assets to showcase your app〕を確認済み):
- 「ローカルモデル(端末内)の会話は端末の外へ出ない」と、「クラウド連携を使ったときだけ、選んだ提供元へ送られる(同意あり)」を、両方正確に書く。「会話は一切外に出ない」「アカウント登録なし」とは書かない。
- サインインは、クラウド連携を使うときだけ(Google アカウント)。ローカルモデルだけなら不要。
- 提供元の名前(Anthropic・OpenAI・Google)は、接続できる先として事実を書くだけにする。各社との提携・公認を示す書き方はしない(下の「ご利用にあたって」に非提携の一文を入れる)。
- 最高・最速・完全などの誇張表現は使わない。

素材: `icon-512.png`(アプリアイコン 512×512)、`feature-graphic-en.png` / `feature-graphic-ja.png`(フィーチャーグラフィック 1024×500。中国語版は英語版を使うか、後で作る)、`screenshots/`(ja / en 各 4 枚)。
**素材の見直し**: フィーチャーグラフィックの文言が古い記述(「会話は端末の外に出ません」など)になっていないか確認する。スクリーンショットは現在 1080×2400 で、Play の縦横比の要件(長辺は短辺の 2 倍以下)を超えるため撮り直しが必要(`docs/release-checklist.md` 3 章)。

連絡先(サポート): kzt.app.support@gmail.com(Play Console の「開発者のメールアドレス」にも使う)。

---

## 日本語(ja)

**アプリ名**: Mobile AI Local

**簡単な説明**(80文字以内)
スマホの中で動くAIチャット。端末内モデルのほか、任意でクラウドAIにも接続できます。

**詳細な説明**
Mobile AI Local は、AI モデルを自分のスマホの中で動かして使うチャットアプリです。モデルをダウンロードしたあとは、端末内のモデルなら通信なしで使えます。端末内のモデルとの会話は、端末の外へ送られません。

さらに、設定した場合に限り、クラウドの AI サービス(Anthropic、OpenAI、Google Gemini の API など)に接続して、同じチャット画面で使うこともできます。

■ 特長
・端末内のモデルでの会話は、端末の中だけで処理されます。広告、利用状況の解析はありません。
・好きなモデルを選べます。Hugging Face の GGUF 形式のモデルを、アプリの中で検索して、そのままダウンロードできます。
・チャットの画面から、使うモデルをすぐに切り替えられます。
・会話の履歴を、端末の中に保存します。名前の変更、検索、削除ができます。
・会話ごとに、AI の役割(システムプロンプト)を設定できます。
・考えてから答えるモデルの「思考過程」は、回答のあとで折りたたまれ、押すと見られます。
・生成の速度、メモリ、端末の熱を、画面で確認できます。熱いときは警告します。
・温度、Top-p、最大トークン、コンテキスト長、スレッド数を調整できます。それぞれ短い説明つきです。
・生成中はバックグラウンドでも動き続けるので、アプリを閉じても回答が途切れにくくなっています。
・日本語、English、简体中文、繁體中文に対応しています。

■ クラウドAIへの接続(任意)
・設定の「API連携」で、ご自身の API キーを入れて接続します。API キーは、端末の暗号化機能で保存され、他の場所には保存されません。
・クラウドのモデルを選んで送信したときだけ、その会話の内容(これまでのやり取りを含む)が、選んだ提供元のサーバーへ送られます。接続の前に、送信先を示す確認画面が出ます。
・クラウドへの接続には、Google アカウントでのサインインが必要です(接続の状況を、アカウントごとに記録するためです)。サインインでは、メールアドレスとユーザー ID を扱います。API キーと会話の内容は保存しません。端末内のモデルだけを使う場合は、サインインは不要です。
・クラウドの利用料金は、ご自身と提供元との契約に基づきます。アプリは請求しません。使用量の上限に達したときなどは、原因を分けて画面でお知らせします。

■ こんな方に
・端末内で動かすAIを、いろいろ試したい方
・オフラインの場所でも、AI を使いたい方
・端末内のモデルと、クラウドの AI を、同じ画面で使い分けたい方

■ ご利用にあたって
・モデルは、アプリに同梱されていません。Hugging Face からダウンロードします(1つあたり、数百MB〜数GB)。大きなモデルは、Wi-Fi でのダウンロードをおすすめします(「Wi-Fi のときだけ」の設定が、初期状態でオンです)。
・動かせるモデルの大きさは、端末の性能(特にメモリ)によります。小さなモデルから試してください。
・AI の回答には、誤りや不適切な内容が含まれることがあります。重要なことは、ご自身でご確認ください。問題のある回答は、アプリ内の「報告」から知らせられます。
・各モデルには、それぞれの利用条件(ライセンス)があります。使う前に、Hugging Face のモデルのページで確認してください。導入したモデルの利用条件を守る責任は、利用者にあります。
・クラウド AI を使う場合、送った内容の扱いは、各提供元の利用規約とプライバシーポリシーに従います。
・本アプリは、Anthropic、OpenAI、Google との提携や、それらの公認を受けたものではありません。各名称は、各社の商標です。
・サポート: kzt.app.support@gmail.com

---

## English (en)

**App name**: Mobile AI Local

**Short description** (80 characters max)
AI chat that runs on your phone, with optional connections to cloud AI services.

**Full description**
Mobile AI Local is a chat app that runs AI models on your own phone. After you download a model, on-device models work without a connection. Conversations with on-device models are not sent off your device.

If you choose to set it up, you can also connect to cloud AI services (such as the APIs of Anthropic, OpenAI, and Google Gemini) and use them from the same chat screen.

FEATURES
• Conversations with on-device models are processed on your device. No ads, no usage analytics.
• Choose any model you like. Search for GGUF models on Hugging Face inside the app and download them directly.
• Switch models right from the chat screen.
• Conversation history is saved on your device. Rename, search, and delete.
• Set the AI's role (system prompt) for each conversation.
• For models that think before answering, the thinking is folded away once the answer arrives. Tap to read it.
• See generation speed, memory use, and device heat on screen, with a warning when the device is hot.
• Adjust temperature, top-p, max tokens, context length, and threads. Each comes with a short explanation.
• Generation keeps running in the background, so an answer is less likely to be interrupted when you leave the app.
• Available in English, 日本語, 简体中文, and 繁體中文.

CONNECTING TO CLOUD AI (OPTIONAL)
• In Settings, under "API integration", you enter your own API key to connect. The key is stored encrypted on your device and is not stored anywhere else.
• Only when you select a cloud model and send a message, the content of that conversation (including earlier messages) is sent to the provider you chose. A confirmation screen naming the destination appears before you connect.
• Connecting to cloud AI requires signing in with a Google account, so the app can record your connections per account. Sign-in involves your email address and user ID. API keys and conversation content are not stored. If you only use on-device models, no sign-in is needed.
• Cloud usage fees are governed by your agreement with the provider; the app does not charge you. When you hit a usage limit or a similar problem, the app tells you which kind of problem it is.

WHO IT IS FOR
• People who want to try many AI models running on their own device
• People who want AI in places without a connection
• People who want to use on-device models and cloud AI from the same screen

GOOD TO KNOW
• Models are not bundled with the app. You download them from Hugging Face (hundreds of MB to several GB each). For large models, Wi-Fi is recommended; the "Wi-Fi only" option is on by default.
• The size of model your phone can run depends on its performance, especially memory. Start with a small model.
• AI answers can be wrong or inappropriate. Check anything important yourself. You can report a problematic answer with the in-app "Report" button.
• Each model has its own license. Check the model's page on Hugging Face before you use it. You are responsible for complying with the terms of the models you install.
• When you use cloud AI, how the provider handles what you send is governed by that provider's terms and privacy policy.
• This app is not affiliated with or endorsed by Anthropic, OpenAI, or Google. All names are trademarks of their respective owners.
• Support: kzt.app.support@gmail.com

---

## 简体中文(zh-CN)

**应用名称**: Mobile AI Local

**简短说明**(80 字以内)
在手机上运行的 AI 聊天,也可选择连接云端 AI 服务。

**详细说明**
Mobile AI Local 是一款让 AI 模型在您自己的手机上运行的聊天应用。下载模型之后,使用设备上的模型无需联网。与设备上的模型的对话不会被发送到设备之外。

如果您选择设置,还可以连接云端 AI 服务(例如 Anthropic、OpenAI、Google Gemini 的 API),在同一个聊天界面中使用。

■ 特点
• 与设备上的模型的对话在设备上处理。没有广告,也没有使用情况分析。
• 自由选择模型。可以在应用内搜索 Hugging Face 上的 GGUF 模型并直接下载。
• 在聊天界面就能快速切换模型。
• 对话历史保存在设备上,可重命名、搜索和删除。
• 可以为每个对话设置 AI 的角色(系统提示词)。
• 对于会先思考再回答的模型,回答出现后会折叠"思考过程",点击即可查看。
• 在屏幕上查看生成速度、内存和设备温度,过热时会提醒。
• 可调整温度、Top-p、最大令牌数、上下文长度和线程数,并附有简短说明。
• 生成会在后台继续运行,离开应用时回答不易被中断。
• 支持 English、日本語、简体中文、繁體中文。

■ 连接云端 AI(可选)
• 在设置的"API 连接"中输入您自己的 API 密钥进行连接。密钥经加密保存在设备上,不会保存在其他地方。
• 只有当您选择云端模型并发送消息时,该对话的内容(包括此前的内容)才会发送到您所选服务商的服务器。连接之前会显示注明发送目的地的确认界面。
• 连接云端 AI 需要使用 Google 账号登录(用于按账号记录连接状况)。登录会涉及您的邮箱地址和用户 ID。API 密钥和对话内容不会被保存。如果只使用设备上的模型,则无需登录。
• 云端的使用费用取决于您与服务商之间的合同,应用不收费。遇到用量上限等问题时,应用会区分原因并在界面上提示。

■ 适合这些人
• 想在自己的设备上试用各种 AI 模型的人
• 想在没有网络的地方使用 AI 的人
• 想在同一界面中使用设备上的模型和云端 AI 的人

■ 使用须知
• 应用不自带模型。模型需要从 Hugging Face 下载(每个几百 MB 到几 GB)。较大的模型建议在 Wi-Fi 下下载("仅在 Wi-Fi 下载"默认开启)。
• 手机能运行多大的模型,取决于性能(尤其是内存)。请先从小模型开始。
• AI 的回答可能有错误或不当之处。重要的事情请自行核实。有问题的回答可通过应用内的"举报"反馈。
• 每个模型都有各自的使用条款(许可证)。使用前请在 Hugging Face 的模型页面确认。遵守所安装模型的使用条款是用户自己的责任。
• 使用云端 AI 时,所发送内容的处理方式遵循各服务商的服务条款和隐私政策。
• 本应用与 Anthropic、OpenAI、Google 没有合作关系,也未获得其认可。各名称为其各自所有者的商标。
• 支持: kzt.app.support@gmail.com

---

## 繁體中文(zh-TW)

**應用程式名稱**: Mobile AI Local

**簡短說明**(80 字以內)
在手機上執行的 AI 聊天,也可選擇連接雲端 AI 服務。

**詳細說明**
Mobile AI Local 是一款讓 AI 模型在您自己的手機上執行的聊天應用程式。下載模型之後,使用裝置上的模型不需要連線。與裝置上的模型的對話不會被傳送到裝置之外。

如果您選擇設定,還可以連接雲端 AI 服務(例如 Anthropic、OpenAI、Google Gemini 的 API),在同一個聊天畫面中使用。

■ 特色
• 與裝置上的模型的對話在裝置上處理。沒有廣告,也沒有使用情況分析。
• 自由選擇模型。可以在應用程式內搜尋 Hugging Face 上的 GGUF 模型並直接下載。
• 在聊天畫面就能快速切換模型。
• 對話歷史記錄儲存在裝置上,可重新命名、搜尋和刪除。
• 可以為每個對話設定 AI 的角色(系統提示詞)。
• 對於會先思考再回答的模型,回答出現後會摺疊「思考過程」,點一下即可查看。
• 在畫面上查看產生速度、記憶體和裝置溫度,過熱時會提醒。
• 可調整溫度、Top-p、最大權杖數、上下文長度和執行緒數,並附有簡短說明。
• 產生會在背景繼續執行,離開應用程式時回答較不易被中斷。
• 支援 English、日本語、简体中文、繁體中文。

■ 連接雲端 AI(選用)
• 在設定的「API 連接」中輸入您自己的 API 金鑰進行連接。金鑰經加密儲存在裝置上,不會儲存在其他地方。
• 只有當您選擇雲端模型並傳送訊息時,該對話的內容(包括先前的內容)才會傳送到您所選服務商的伺服器。連接之前會顯示註明傳送目的地的確認畫面。
• 連接雲端 AI 需要使用 Google 帳號登入(用於依帳號記錄連接狀況)。登入會涉及您的電子郵件地址與使用者 ID。API 金鑰與對話內容不會被儲存。如果只使用裝置上的模型,則不需要登入。
• 雲端的使用費用取決於您與服務商之間的合約,應用程式不收費。遇到用量上限等問題時,應用程式會區分原因並在畫面上提示。

■ 適合這些人
• 想在自己的裝置上試用各種 AI 模型的人
• 想在沒有網路的地方使用 AI 的人
• 想在同一畫面中使用裝置上的模型和雲端 AI 的人

■ 使用須知
• 應用程式不內建模型。模型需要從 Hugging Face 下載(每個幾百 MB 到幾 GB)。較大的模型建議在 Wi-Fi 下下載(「僅在 Wi-Fi 下載」預設開啟)。
• 手機能執行多大的模型,取決於效能(尤其是記憶體)。請先從小模型開始。
• AI 的回答可能有錯誤或不當之處。重要的事情請自行確認。有問題的回答可透過應用程式內的「檢舉」回報。
• 每個模型都有各自的使用條款(授權)。使用前請在 Hugging Face 的模型頁面確認。遵守所安裝模型的使用條款是使用者自己的責任。
• 使用雲端 AI 時,所傳送內容的處理方式依各服務商的服務條款與隱私權政策。
• 本應用程式與 Anthropic、OpenAI、Google 沒有合作關係,也未獲得其認可。各名稱為其各自所有者的商標。
• 支援: kzt.app.support@gmail.com
