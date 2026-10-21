package com.mobileailocal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mobileailocal.R

enum class Dest(val route: String, val label: Int) {
    Chat("chat", R.string.nav_chat),
    History("history", R.string.nav_history),
    Models("models", R.string.nav_models),
    Settings("settings", R.string.nav_settings),
}

@Composable
fun AppRoot() {
    MaterialTheme {
        val nav = rememberNavController()
        val current by nav.currentBackStackEntryAsState()
        Scaffold(bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = current?.destination?.route == d.route,
                        onClick = { nav.navigate(d.route) { launchSingleTop = true } },
                        icon = { Text(stringResource(d.label).take(1)) }, // TODO(Strata): アイコンに置き換え
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        }) { pad ->
            NavHost(nav, startDestination = Dest.Chat.route, modifier = Modifier.padding(pad)) {
                composable(Dest.Chat.route) { ChatScreen() }
                composable(Dest.History.route) { Placeholder("History") }
                composable(Dest.Models.route) { Placeholder("Models") }
                composable(Dest.Settings.route) { Placeholder("Settings") }
            }
        }
    }
}

@Composable
private fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(name) }
}
