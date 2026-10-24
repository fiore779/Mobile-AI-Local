package com.mobileailocal.data

import android.content.Context
import com.mobileailocal.R
import com.mobileailocal.domain.ErrorDetailText
import com.mobileailocal.domain.LoadCause
import com.mobileailocal.domain.LoadCauseGuess
import com.mobileailocal.domain.NativeError

/**
 * ネイティブのエラー文字列(コード + 詳細)を、選んでいる言語の文言にする。不明なものは原文を添える。
 * 戻り値は ErrorDetailText の形(要約 + 空行 + 詳細)。詳細は llama.cpp のエラー行の原文(パスは伏せ済み)。
 */
fun Context.nativeErrorText(message: String?): String {
    val kind = NativeError.parse(message)
    val id = when (kind) {
        NativeError.LoadFile -> R.string.model_error_load_file
        NativeError.Context -> R.string.model_error_context
        NativeError.NoModel -> R.string.model_error_no_model
        NativeError.Empty -> R.string.model_error_empty
        NativeError.Template -> R.string.model_error_template
        NativeError.Tokenize -> R.string.model_error_tokenize
        NativeError.TooLong -> R.string.model_error_too_long
        NativeError.DecodePrompt, NativeError.DecodeGen -> R.string.model_error_decode
        null -> return localizedString(R.string.model_error_unknown, message ?: "")
    }
    val detail = NativeError.detail(message)
    var summary = localizedString(id)
    if (kind == NativeError.LoadFile || kind == NativeError.Context) {
        val hint = when (LoadCauseGuess.cause(detail)) {
            LoadCause.UnknownArchitecture ->
                localizedString(R.string.model_error_hint_arch, LoadCauseGuess.architecture(detail) ?: "?")
            LoadCause.OutOfMemory -> localizedString(R.string.model_error_hint_memory)
            LoadCause.Corrupt -> localizedString(R.string.model_error_hint_corrupt)
            LoadCause.Unsupported -> localizedString(R.string.model_error_hint_unsupported)
            null -> if (kind == NativeError.Context) null else localizedString(R.string.model_error_hint_unknown)
        }
        if (hint != null) summary += " " + hint
    }
    return ErrorDetailText.join(summary, detail)
}
