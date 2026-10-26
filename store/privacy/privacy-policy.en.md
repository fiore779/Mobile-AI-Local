# Mobile AI Local Privacy Policy

Effective date: {{effective date}}　Developer: {{developer name}}　Contact: {{contact email}}

Mobile AI Local ("the app") is an AI chat app that runs on your device. Conversations with on-device models (local models) never leave your device. Only if you choose to set up "API integration" and select a cloud AI model, the content of your conversation is sent to the provider you chose (for example Anthropic, OpenAI, or Google) (Section 2). In either case, the content of your conversations is not sent to the developer's servers.

## 1. Information stored on your device
The following is stored only in the app's private storage (not visible to other apps). The developer does not receive it.
- Conversations (your questions, the AI's answers, conversation titles, system prompts)
- AI model files you download
- Settings (theme, language, generation settings, the Wi-Fi-only option, and so on)
- If you set up API integration: connection details (provider type, display name, connection URL, model names to use) and the API key you entered. The API key is encrypted with the device's Android Keystore, is not sent anywhere except to the provider when the app connects to it, and is excluded from backups.
- If you set a work folder: identifying information about the folder (its URI and name). The contents of the folder are stored in your device's storage; the app does not send them to the developer
- Conversation summaries (when a conversation gets long, older parts are summarized and one summary is stored per conversation)

## 2. Internet connections
The app connects to the internet only when you do one of the following.
- **Searching for models:** the search words you type are sent to Hugging Face (huggingface.co), the model distribution service.
- **Downloading models:** the files of the model you choose are fetched from Hugging Face.

In these connections, Hugging Face receives your IP address and basic connection information. Hugging Face handles it under its own privacy policy. Your conversations are never part of these connections. Answers from local models are generated entirely on your device and need no connection.

**Connecting to cloud AI (API integration, only if you set it up):**
- Only when you add a connection under "API integration" in Settings, select one of its models, and send a message, the app sends the content of the conversation (the text you typed, the earlier messages in the same conversation, the system prompt, and generation settings) over HTTPS to the provider you chose. Before you add a connection, a confirmation screen names the destination and asks for your consent (once per provider type; for custom connections, once per host). You can withdraw consent.
- Connection URLs must use HTTPS. Unencrypted HTTP connections are not supported.
- How the provider handles what you send is governed by the provider's terms and privacy policy. The developer does not receive it. Any usage fees are governed by your agreement with the provider; the app does not charge you.
- While a local model is selected, nothing is sent to the cloud. Conversations using a cloud AI show a cloud icon and the connection name on screen.

**Signing in with a Google account (only when you use API integration):**
- Setting up API integration requires signing in with a Google account. If you only use local models, no sign-in is required or requested.
- When you sign in, the cloud database managed by the developer (Google's Firebase Authentication and Cloud Firestore) stores your Google account email address and user ID, plus API-integration "metadata" (provider type, display name, connection URL, model names to use, and update time). The purpose is to record your integrations per account and carry the "connected" status over to other devices.
- **API keys and conversation content are not stored in this database.** Keys exist only on each device, so you must re-enter the key on another device.
- Access to this data is restricted to your own account.

**Work folder (only if you set one) and conversation summaries:**
- The contents of files in the work folder are given only to the on-device model and are not transmitted. When you use a cloud AI, the work folder cannot be used and file contents are not sent (if you switch to a cloud AI in a conversation where the on-device model read files earlier, the read results are omitted).
- When a conversation gets long, older parts are summarized; with a cloud AI this adds an extra request to the provider (the content sent is the same as in a normal chat request).
- When you use a cloud AI, the contents of work-folder files themselves are not sent, but the AI's replies or the conversation summary may contain parts of them.
- If you choose a folder that is synced with a cloud storage service, the files are saved to that service.

## 3. What we do not collect
The app does not ask you to create an account to use local models; it asks you to sign in with Google only when you use API integration (Section 2). It contains no advertising, no usage analytics, no crash reporting, and nothing that tracks you. It does not access your location, contacts, photos, microphone, or camera. The app does not access any files other than the work folder you choose.

## 4. Permissions
- Internet: to search for and download models, and to communicate with the cloud AI you set up
- Network state: to apply the "download only on Wi-Fi" option
- Foreground service: to keep an answer being generated (or, for cloud AI, received) when you leave the app (on Android 13 and later no notification is shown; on Android 12 and earlier a minimal, unobtrusive notification appears)
- Storage permissions: not used. The work folder is only the single folder you pick in Android's folder picker

## 5. Reporting inappropriate output
If an answer is a problem, you can contact the developer with the app's report function. It creates a draft email, containing the answer you are reporting, in your email app. **It reaches the developer only if you send it.**

## 6. Deleting information
- You can delete conversations at any time on the History screen.
- You can delete downloaded models on the Models screen.
- You can delete an API-integration connection at any time under "API integration" in Settings. Its API key is then removed from your device.
- Information tied to your Google account (email address, user ID, API-integration metadata) can be deleted at any time with "Delete account and cloud data" in Settings. The steps, what is and is not deleted, and how to request deletion if you cannot use the app are described on the deletion page: {{deletion page URL}} (email for requests: kzt.app.support@gmail.com)
- Uninstalling the app removes everything it stored on your device (delete the information tied to your Google account separately, as described above).
- Deleting content already sent to a cloud provider is subject to that provider's own procedures.
- Files created in the work folder are in your own storage and are not removed when you uninstall the app. You can remove the folder setting from Settings > Work folder.
- Deleting a conversation also deletes its summary.

## 7. About AI output
AI answers can be wrong or inappropriate. Check anything important yourself. The app does not bundle AI models; you install them yourself, and you are responsible for complying with the license (terms of use) of the models you install.

## 8. Children
The app is not primarily directed at children under 13.

## 9. Changes
If we change this policy, we will announce it on this page. If we change what is sent to cloud AI providers or where it is sent, we will show a confirmation screen beforehand and ask for your consent again.

## 10. Contact
{{contact email}}
