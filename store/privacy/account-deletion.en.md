# Mobile AI Local: Deleting your account and integration data

Last updated: {{effective date}}　Developer: {{developer name}}　Contact: {{contact email}}

This page explains how to delete the **account created by signing in with Google** in Mobile AI Local ("the app"), and the information tied to it. Sign-in is required only when you use "API integration" (connecting to cloud AI) in Settings. If you only use local models, you are not signed in and there is no account to delete.

## 1. What is deleted
When you delete your account, the following is deleted:
- **API-integration "metadata" stored in the cloud (Firebase Cloud Firestore):** provider type, display name, connection URL, model names to use, and creation/update times
- **The app's account (the Firebase Authentication user):** your Google account email address and user ID
- **On the device where you perform the deletion, this account's API integrations:** the list of connections, the stored API keys, and the records of your consent to cloud transmission

## 2. What is not deleted
- **Your Google account itself.** The app cannot delete your Google account.
- **Conversation history on your device.** You can delete conversations one by one on the History screen; uninstalling the app also removes them.
- **Downloaded models and settings.**
- **Your accounts, API keys, and already-sent content on the cloud providers' side (Anthropic, OpenAI, Google, and others).** To revoke an API key or delete content sent to a provider, use that provider's own screens and procedures. The app's developer cannot operate on these.
- **API keys stored on other devices.** Keys exist only on each device. On other devices, delete the connection in the app or uninstall it.

## 3. How to delete inside the app
1. Open the app and open "Settings" at the bottom.
2. Open "API connections".
3. While signed in, scroll down to the bottom of the screen, below the list of connections, "Add a connection" and the "Gemini reasoning effort" setting, and tap **"Delete account and cloud data"**. (This button is shown only while you are signed in. Labels follow the app's language.)
4. Review the confirmation and proceed. For security, you are **always** asked to confirm your Google account again (sign in again). If you cancel that step, nothing is deleted.
5. When it finishes, the information in section 1 is deleted and you are signed out.

Deletion may fail where there is no connection. Connect to a network and try again.

## 4. If you cannot use the app
If you cannot operate the app (for example, you lost the device or uninstalled the app), you can request deletion:
- **Email: kzt.app.support@gmail.com**
- Use the subject "Account deletion request".
- **To verify it is you, please send the email from the Google account (registered email address) you used to sign in to the app.** We may be unable to delete the data if the request comes from a different address, because we cannot verify your identity.
- Once verified, we will delete the cloud-side information in section 1 (the metadata and the Firebase account) within a reasonable period and reply to let you know.

Even by request, the items in section 2 ("What is not deleted") cannot be deleted by us.

## 5. Related
- Privacy Policy: {{privacy policy URL}}
- Questions about deletion or this page: kzt.app.support@gmail.com
