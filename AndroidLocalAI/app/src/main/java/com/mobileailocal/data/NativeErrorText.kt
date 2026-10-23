package com.mobileailocal.data

import android.content.Context
import com.mobileailocal.R
import com.mobileailocal.domain.NativeError

/** ネイティブのエラー文字列(コード)を、選んでいる言語の文言にする。不明なものは原文を添える。 */
fun Context.nativeErrorText(message: String?): String {
    val id = when (NativeError.parse(message)) {
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
    return localizedString(id)
}
