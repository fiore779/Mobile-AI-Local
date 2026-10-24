package com.mobileailocal.domain

/** ネイティブ(mal_llama.cpp)が返すエラーコード。表示用の文言は画面側で文字列リソースにする。 */
enum class NativeError(val code: String) {
    LoadFile("E_LOAD_FILE"),
    Context("E_CONTEXT"),
    NoModel("E_NO_MODEL"),
    Empty("E_EMPTY"),
    Template("E_TEMPLATE"),
    Tokenize("E_TOKENIZE"),
    TooLong("E_TOO_LONG"),
    DecodePrompt("E_DECODE_PROMPT"),
    DecodeGen("E_DECODE_GEN");

    companion object {
        /** 先頭のコードから種類を決める("E_XXX" または "E_XXX:<詳細>")。コードでなければ null(不明なエラー)。 */
        fun parse(message: String?): NativeError? {
            val m = message?.trim() ?: return null
            val head = m.substringBefore(':')
            return entries.firstOrNull { head == it.code }
        }

        private const val MAX_LINE = 300
        private const val MAX_LINES = 10
        private val pathRegex = Regex("""[/\\][^\s'"]+""")

        /**
         * "E_XXX:<詳細>" の詳細部分(llama.cpp のエラー行の原文)を、画面に出せる形にして返す。無ければ null。
         * ファイルのパスは "<path>" に置き換え、1 行 300 文字・10 行までにする。
         */
        fun detail(message: String?): String? {
            val m = message?.trim() ?: return null
            if (':' !in m || parse(m) == null) return null
            val lines = m.substringAfter(':').lines()
                .map { it.trim().replace(pathRegex, "<path>") }
                .filter { it.isNotEmpty() }
                .map { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
                .takeLast(MAX_LINES)
            return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }
}

/** 読み込み失敗の原因の目安(詳細の文面から推定)。 */
enum class LoadCause { UnknownArchitecture, OutOfMemory, Corrupt, Unsupported }

object LoadCauseGuess {
    private val archRegex = Regex("""architecture:\s*'([^']*)'""")

    /** 詳細から原因を推定する。分からなければ null。 */
    fun cause(detail: String?): LoadCause? {
        val d = detail?.lowercase() ?: return null
        return when {
            "unknown model architecture" in d -> LoadCause.UnknownArchitecture
            "out of memory" in d || "failed to allocate" in d || "bad_alloc" in d || "unable to allocate" in d ->
                LoadCause.OutOfMemory
            "invalid magic" in d || "failed to read" in d || "end of file" in d ||
                "not within the file" in d || "file size" in d || "not a gguf" in d || "bad magic" in d ->
                LoadCause.Corrupt
            "missing tensor" in d || "unsupported" in d || "unknown pre-tokenizer" in d ||
                "key not found" in d || "wrong shape" in d -> LoadCause.Unsupported
            else -> null
        }
    }

    /** "unknown model architecture: 'xxx'" からアーキテクチャ名を取り出す。 */
    fun architecture(detail: String?): String? =
        detail?.let { archRegex.find(it)?.groupValues?.get(1) }?.takeIf { it.isNotEmpty() }
}

/** 画面のエラー文は「要約」+ 空行 + 「詳細」の 1 つの文字列で持つ(詳細は折りたたんで見せる)。 */
object ErrorDetailText {
    private const val SEP = "\n\n"
    fun join(summary: String, detail: String?): String = if (detail.isNullOrEmpty()) summary else summary + SEP + detail
    fun split(text: String): Pair<String, String?> {
        val i = text.indexOf(SEP)
        return if (i < 0) text to null else text.substring(0, i) to text.substring(i + SEP.length)
    }
}
