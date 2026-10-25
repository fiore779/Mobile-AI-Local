package com.mobileailocal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobileailocal.R
import com.mobileailocal.domain.PolicyText
import java.util.Locale

/**
 * アプリに同梱したプライバシーポリシーの全文(assets/privacy/)。公開 URL(privacy_policy_url)が未設定の間の代わり。
 * ストア掲載のポリシーと同じファイルから作るので、内容は同じになる。
 */
@Composable
fun PrivacyPolicyScreen() {
    val context = LocalContext.current
    val text = remember {
        val loc = Locale.getDefault()
        val name = PolicyText.fileName(loc.language, loc.country, loc.script)
        try {
            val raw = context.assets.open("privacy/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
            PolicyText.fill(raw, context.getString(R.string.report_email))
        } catch (_: Exception) {
            null
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (text == null) {
            Text(stringResource(R.string.privacy_policy_unavailable), color = MaterialTheme.colorScheme.error)
        } else {
            SelectionContainer { MarkdownText(text) }
        }
    }
}
