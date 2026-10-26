package com.mobileailocal.domain

object Prompts {
    /** 会話のシステムプロンプトが空のときに使う既定文(設計書 17.2)。 */
    const val DEFAULT_SYSTEM =
        "You are a helpful assistant running entirely on the user's device. " +
            "Always reply in the language the user writes in. " +
            "When the user writes Chinese, reply in the same script (Simplified or Traditional) unless asked otherwise."

    /** 拡張機能(JavaScript 実行)が有効なとき、システムプロンプトの後ろに足す説明(設計書 4.6)。 */
    const val JS_ADDENDUM =
        "You can write JavaScript in a ```js code block; the user may run it on the device (QuickJS, no network, no npm).\n" +
            "Available: console.log, setTimeout/sleep(ms), and fs.writeFile(path,text), fs.readFile(path), fs.list(dir), fs.exists(path), fs.remove(path) for text files in a private workspace (relative paths only).\n" +
            "Limits: 5 seconds, small memory. The value of the last expression and console output are returned to you."

    /** 会話の要約用の指示(設計書 context-and-workspace 4.3)。英語固定。 */
    const val SUMMARIZE_SYSTEM =
        "You compress a chat for later use by the same assistant. Write a concise summary of the conversation below.\n" +
            "Keep: the user's goals, decisions, facts, names, numbers, file names, code identifiers, preferences, and unfinished tasks.\n" +
            "Drop: greetings and repetition. Write in the language the user used. Output only the summary, no preface."

    /** 作業フォルダが使えるとき(端末内モデルだけ)、システムプロンプトの後ろに足す説明(設計書 4.6)。英語固定。 */
    const val WORKSPACE_ADDENDUM =
        "You can use files in the user's work folder with ONE of these tags per reply (relative paths only):\n" +
            "<file_list path=\".\"/>\n" +
            "<file_read path=\"notes/a.txt\"/>   (optional start=\"2000\" to continue a long file)\n" +
            "<file_write path=\"notes/a.txt\">full text of the file</file_write>\n" +
            "After the tag, stop and wait: the result comes back as the next message. Writes need the user's approval. " +
            "Read before you edit; never guess file contents. Text files only."

    private const val TOOL_RESULT_CAP = 400

    private fun cut(text: String, cap: Int): String =
        if (text.length > cap) text.take(cap) + " …[cut]" else text

    /** 要約用の入力。[System(SUMMARIZE_SYSTEM), User(本文)] の 2 件。lineCap で各発言を切る。 */
    fun summarizeMessages(previousSummary: String, transcript: List<ChatMessage>, lineCap: Int): List<ChatMessage> {
        val body = StringBuilder()
        if (previousSummary.isNotBlank()) {
            body.append("[Previous summary]\n").append(previousSummary.trim()).append("\n\n")
        }
        body.append("[Conversation to add]")
        for (m in transcript) {
            val who = if (m.role == Role.Assistant) "Assistant" else "User"
            val cap = if (m.role == Role.User && ToolResult.isResult(m.content)) minOf(lineCap, TOOL_RESULT_CAP) else lineCap
            body.append('\n').append(who).append(": ").append(cut(m.content, cap))
        }
        return listOf(ChatMessage(Role.System, SUMMARIZE_SYSTEM), ChatMessage(Role.User, body.toString()))
    }

    /** システム文の後ろに足す要約ブロック。summary が空なら ""。 */
    fun summaryBlock(summary: String): String =
        if (summary.isBlank()) ""
        else "[Summary of the earlier conversation; those messages were removed to save space]\n" + summary.trim()

    /**
     * モデルへ渡すシステムプロンプト。拡張機能(JS 実行)は、クラウドの返答には「実行」を出さないので、
     * クラウド選択のときは JS の説明を足さない(設計書 6.2-4、R8)。要約は常にシステム文の中に入れる(クラウドでも足す)。
     * 順序: base + JS + 作業フォルダ(有効かつローカル) + 要約。各ブロックの間は空行。
     * workspaceEnabled は「作業フォルダが使える状態」(設定が有効 + フォルダ選択済み + 権限あり)。
     */
    fun systemFor(base: String, jsEnabled: Boolean, workspaceEnabled: Boolean, summary: String, cloud: Boolean): String {
        val parts = mutableListOf(base)
        if (jsEnabled && !cloud) parts += JS_ADDENDUM
        if (workspaceEnabled && !cloud) parts += WORKSPACE_ADDENDUM
        summaryBlock(summary).let { if (it.isNotEmpty()) parts += it }
        return parts.joinToString("\n\n")
    }
}
