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
        val onExtensions = route == EXTENSIONS_ROUTE
        val dest = Dest.entries.firstOrNull { it.route == route }
            ?: if (onExtensions) Dest.Settings else Dest.Chat
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (onExtensions) R.string.settings_extensions
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
            NavHost(
                nav,
                startDestination = Dest.Chat.route,
                modifier = Modifier.padding(pad).consumeWindowInsets(pad).imePadding(),
            ) {
                composable(Dest.Chat.route) { ChatScreen(chatVm) }
                composable(Dest.History.route) {
                    HistoryScreen(onOpen = { id ->
                        chatVm.open(id)
                        nav.navigate(Dest.Chat.route) { launchSingleTop = true }
                    })
                }
                composable(Dest.Models.route) { ModelsScreen() }
                composable(Dest.Settings.route) {
                    SettingsScreen(onOpenExtensions = { nav.navigate(EXTENSIONS_ROUTE) { launchSingleTop = true } })
                }
                composable(EXTENSIONS_ROUTE) { ExtensionsScreen() }
            }
        }
    }
}

@Composable
private fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(name) }
}
