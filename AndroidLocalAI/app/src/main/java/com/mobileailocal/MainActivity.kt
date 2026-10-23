package com.mobileailocal

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.mobileailocal.data.SettingsRepository
import com.mobileailocal.ui.AppRoot
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import android.content.res.Resources
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 起動時に保存済みの言語を読み、リソースの言語に反映する("system" は端末の言語のまま)
        val lang = runBlocking { SettingsRepository(newBase).settings.first().language }
        super.attachBaseContext(withLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 設定で言語が変わったら作り直して反映する
        lifecycleScope.launch {
            (application as App).settings.settings.map { it.language }.drop(1).collect { recreate() }
        }
        setContent { AppRoot() }
    }

    private fun withLanguage(base: Context, lang: String): Context {
        // "system" のときも、前に選んだ言語の Locale.setDefault が残らないよう、端末の言語に戻す
        val locale = if (lang == "system") Resources.getSystem().configuration.locales[0]
        else Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (lang == "system") return base
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
