package com.mobileailocal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileailocal.App
import com.mobileailocal.data.AppSettings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mobileailocal.R

enum class Dest(val route: String, val label: Int, val icon: Int) {
    Chat("chat", R.string.nav_chat, R.drawable.ic_nav_chat),
    History("history", R.string.nav_history, R.drawable.ic_nav_history),
    Models("models", R.string.nav_models, R.drawable.ic_nav_models),
    Settings("settings", R.string.nav_settings, R.drawable.ic_nav_settings),
}

// 設定画面の下に開く拡張機能画面(下部ナビには出さない)
private const val EXTENSIONS_ROUTE = "settings/extensions"
private const val WORKSPACE_ROUTE = "settings/workspace"
private const val PRIVACY_ROUTE = "settings/privacy"
private const val API_ROUTE = "settings/api"
private const val API_DETAIL_ROUTE = "settings/api/{integrationId}" // "new" で追加、連携 ID で編集

/** 設定の下位ルートか(下部ナビの「設定」を強調し、戻る矢印を出す)。 */
internal fun isSettingsSubRoute(route: String?): Boolean =
    route == EXTENSIONS_ROUTE || route == WORKSPACE_ROUTE || route == PRIVACY_ROUTE || route == API_ROUTE || route?.startsWith("$API_ROUTE/") == true

/** 設定の下位ルートのタイトル(文字列リソース id)。 */
internal fun settingsSubTitle(route: String?): Int = when {
    route == EXTENSIONS_ROUTE -> R.string.settings_extensions
    route == WORKSPACE_ROUTE -> R.string.workspace_title
    route == PRIVACY_ROUTE -> R.string.settings_privacy_policy
    route == API_ROUTE -> R.string.settings_api
    route == "$API_ROUTE/new" -> R.string.api_add_title
    else -> R.string.api_detail_title
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppRoot() {
    val settings by (LocalContext.current.applicationContext as App).settings.settings
        .collectAsStateWithLifecycle(AppSettings())
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val nav = rememberNavController()
        val current by nav.currentBackStackEntryAsState()
        val chatVm: ChatViewModel = viewModel() // アクティビティ単位で共有し、履歴から開けるようにする
        val route = current?.destination?.route
        val onExtensions = isSettingsSubRoute(route)
        val ctx = LocalContext.current
        val app = ctx.applicationContext as App
        // プライバシーポリシー: 公開 URL があればブラウザで、無ければ同梱の全文を画面で開く
        val openPrivacy: () -> Unit = {
            val url = ctx.getString(R.string.privacy_policy_url)
            if (url.isNotBlank()) openUrl(ctx, url) else nav.navigate(PRIVACY_ROUTE) { launchSingleTop = true }
        }
        val pendingConsent by app.pendingConsent.collectAsStateWithLifecycle()
        val dest = Dest.entries.firstOrNull { it.route == route }
            ?: if (onExtensions) Dest.Settings else Dest.Chat
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (onExtensions) settingsSubTitle(route)
                            else if (dest == Dest.Chat) R.string.app_name
                            else dest.label
                        )
                    )
                },
                navigationIcon = {
                    // material-icons の依存が無いので矢印は文字で出す
                    if (onExtensions) {
                        IconButton(onClick = { nav.popBackStack() }) { Text("←") }
                    }
                },
                actions = {
                    if (dest == Dest.Chat) {
                        IconButton(onClick = { chatVm.newConversation() }) {
                            Icon(painterResource(R.drawable.ic_new_chat), contentDescription = stringResource(R.string.chat_new))
                        }
                    }
                },
            )
        }, bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    val selected = route == d.route || (d == Dest.Settings && onExtensions)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // 拡張機能画面で「設定」を押したら、設定画面へ戻る(ナビし直さない)
                            if (d == Dest.Settings && onExtensions) nav.popBackStack()
                            else nav.navigate(d.route) { launchSingleTop = true }
                        },
                        icon = { Icon(painterResource(d.icon), contentDescription = null) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        }) { pad ->
            // モデル選択で同意がまだのクラウドを選んだとき、どの画面からでも確認を出す
            pendingConsent?.let { p ->
                CloudConsentDialog(p.providerType, p.scope.ifEmpty { null }, onAccept = app::acceptPendingConsent, onDecline = app::declinePendingConsent)
            }
            NavHost(
                nav,
                startDestination = Dest.Chat.route,
                modifier = Modifier.padding(pad).consumeWindowInsets(pad).imePadding(),
            ) {
                composable(Dest.Chat.route) {
                    ChatScreen(chatVm, onOpenApiSettings = { id ->
                        nav.navigate(if (id == null) API_ROUTE else "$API_ROUTE/$id") { launchSingleTop = true }
                    })
                }
                composable(Dest.History.route) {
                    HistoryScreen(onOpen = { id ->
                        chatVm.open(id)
                        nav.navigate(Dest.Chat.route) { launchSingleTop = true }
                    })
                }
                composable(Dest.Models.route) { ModelsScreen() }
                composable(Dest.Settings.route) {
                    SettingsScreen(
                        onOpenExtensions = { nav.navigate(EXTENSIONS_ROUTE) { launchSingleTop = true } },
                        onOpenApi = { nav.navigate(API_ROUTE) { launchSingleTop = true } },
                        onOpenWorkspace = { nav.navigate(WORKSPACE_ROUTE) { launchSingleTop = true } },
                        onOpenPrivacy = openPrivacy,
                    )
                }
                composable(EXTENSIONS_ROUTE) { ExtensionsScreen() }
                composable(WORKSPACE_ROUTE) { WorkspaceScreen() }
                composable(PRIVACY_ROUTE) { PrivacyPolicyScreen() }
                composable(API_ROUTE) {
                    ApiSettingsScreen(
                        onOpenPrivacy = openPrivacy,
                        onAdd = { nav.navigate("$API_ROUTE/new") { launchSingleTop = true } },
                        onOpen = { id -> nav.navigate("$API_ROUTE/$id") { launchSingleTop = true } },
                    )
                }
                composable(API_DETAIL_ROUTE) { ApiAddScreen(onDone = { nav.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(name) }
}
