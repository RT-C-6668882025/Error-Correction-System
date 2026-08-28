package com.ecs.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.ecs.ui.screen.PromptScreen
import com.ecs.ui.screen.ReviewScreen
import com.ecs.ui.screen.SettingsScreen
import com.ecs.ui.screen.SourceScreen

object Routes {
    const val ENTRY = "entry"
    const val SOURCE = "source"
    const val REVIEW = "review"
    const val CONFIRM = "confirm"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
    const val PROMPTS = "prompts"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * 只有三个：录入把题存进来，原题是唯一数据源，复习按考点层级自下而上看。
 * 设置是一次性配置，放右上角，不占底部。
 */
private val tabs = listOf(
    Tab(Routes.ENTRY, "录入", Icons.Outlined.Add),
    Tab(Routes.SOURCE, "原题", Icons.Outlined.Description),
    Tab(Routes.REVIEW, "复习", Icons.Outlined.School),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcsNavHost(vm: AppViewModel, nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val onTab = tabs.any { it.route == current }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabs.firstOrNull { it.route == current }?.label ?: title(current)) },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(Routes.ENTRY)
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.ENTRY, modifier = Modifier.padding(padding)) {
            composable(Routes.ENTRY) { EntryScreen(vm, nav) }
            composable(Routes.SOURCE) { SourceScreen(vm) }
            composable(Routes.REVIEW) { ReviewScreen(vm) }
            composable(Routes.CONFIRM) { ConfirmScreen(vm, nav) }
            composable(Routes.EXPORT) { ExportScreen(vm) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
            composable(Routes.PROMPTS) { PromptScreen(vm) }
        }
    }
}

private fun title(route: String?): String = when (route) {
    Routes.CONFIRM -> "确认录入"
    Routes.EXPORT -> "导出"
    Routes.SETTINGS -> "设置"
    Routes.PROMPTS -> "提示词"
    else -> "错题元数据"
}
