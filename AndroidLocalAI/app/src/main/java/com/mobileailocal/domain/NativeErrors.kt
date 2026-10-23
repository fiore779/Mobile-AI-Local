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
        /** 先頭のコードから種類を決める。コードでなければ null(不明なエラー)。 */
        fun parse(message: String?): NativeError? {
            val m = message?.trim() ?: return null
            return entries.firstOrNull { m == it.code }
        }
    }
}
