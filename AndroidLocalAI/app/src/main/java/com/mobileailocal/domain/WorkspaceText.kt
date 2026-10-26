package com.mobileailocal.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 作業フォルダのテキストの扱い(純 Kotlin。SafWorkspace から使う)。 */
object WorkspaceText {
    /** UTF-8 として厳密にデコードする。NUL を含む・不正なら EBINARY(置換しない)。 */
    fun decodeStrict(bytes: ByteArray): String {
        if (bytes.any { it == 0.toByte() }) throw WorkspaceException("EBINARY", "not a text file")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw WorkspaceException("EBINARY", "not valid UTF-8 text")
        }
    }

    /** start 文字目から maxChars 文字まで。start が長さ以上なら空と hasMore=false。末尾がサロゲートペアの途中なら 1 文字手前で切る。 */
    fun slice(text: String, start: Int, maxChars: Int): ReadChunk {
        if (start >= text.length) return ReadChunk("", text.length, start, false)
        var end = minOf(text.length, start + maxChars)
        if (end < text.length && end > start + 1 && text[end - 1].isHighSurrogate()) end--
        return ReadChunk(text.substring(start, end), text.length, start, end < text.length)
    }
}
