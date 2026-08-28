package com.ecs.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ecs.ui.AppViewModel
import com.ecs.ui.screen.ConfirmScreen
import com.ecs.ui.screen.EntryScreen
import com.ecs.ui.screen.ExportScreen
import com.ecs.ui.screen.HomeScreen
import com.ecs.ui.screen.ListScreen
import com.ecs.ui.screen.MacroScreen
import com.ecs.ui.screen.MaintenanceScreen
import com.ecs.ui.screen.MicroScreen
import com.ecs.ui.screen.PromptScreen
import com.ecs.ui.screen.ReverseTableScreen
import com.ecs.ui.screen.SettingsScreen

object Routes {
    const val HOME = "home"
    const val ENTRY = "entry"
    const val CONFIRM = "confirm"
    const val LIST = "list"
    const val REVERSE = "reverse"
    const val MICRO = "micro"
    const val MACRO = "macro"
    const val MAINTENANCE = "maintenance"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
    const val PROMPTS = "prompts"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "首页", Icons.Outlined.Home),
    Tab(Routes.ENTRY, "录入", Icons.Outlined.Add),
    Tab(Routes.REVERSE, "倒推表", Icons.Outlined.Style),
    Tab(Routes.LIST, "列表", Icons.Outlined.List),
    Tab(Routes.MACRO, "大方向", Icons.Outlined.Insights),
)

@Composable
fun EcsNavHost(vm: AppViewModel, nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(Routes.HOME)
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(vm, nav) }
            composable(Routes.ENTRY) { EntryScreen(vm, nav) }
            composable(Routes.CONFIRM) { ConfirmScreen(vm, nav) }
            composable(Routes.LIST) { ListScreen(vm) }
            composable(Routes.REVERSE) { ReverseTableScreen(vm) }
            composable(Routes.MICRO) { MicroScreen(vm) }
            composable(Routes.MACRO) { MacroScreen(vm) }
            composable(Routes.MAINTENANCE) { MaintenanceScreen(vm) }
            composable(Routes.EXPORT) { ExportScreen(vm) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
            composable(Routes.PROMPTS) { PromptScreen(vm) }
        }
    }
}
