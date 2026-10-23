package com.mobileailocal.data

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * アプリで選んだ言語(MainActivity が Locale.setDefault する)で文字列を取り出す。
 * Application の Context は端末の言語のままなので、画面の外(ViewModel・ダウンロード)のエラー文に使う。
 */
fun Context.localizedString(@StringRes id: Int, vararg args: Any): String {
    val cfg = Configuration(resources.configuration)
    cfg.setLocale(Locale.getDefault())
    return createConfigurationContext(cfg).getString(id, *args)
}
