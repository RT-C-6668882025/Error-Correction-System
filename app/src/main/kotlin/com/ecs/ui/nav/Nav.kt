package com.ecs.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
 * 只有三个：录入把题存进来，原题是唯一数据源，复习把分析自下而上汇总成方向。
 * 设置是一次性配置，放右上角，不占底部。
 */
private val tabs = listOf(
    Tab(Routes.ENTRY, "录入", Icons.Outlined.Add),
    Tab(Routes.SOURCE, "原题", Icons.Outlined.Description),
    Tab(Routes.REVIEW, "复习", Icons.Outlined.School),
)

/**
 * 只有这两页藏底部导航：确认录入与导出是有始有终的流程，中途切板块会丢掉手上的活。
 * 设置与提示词不是流程，是随时进出的旋钮，藏了导航栏等于把另外两个板块挡住。
 */
private val flowRoutes = setOf(Routes.CONFIRM, Routes.EXPORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcsNavHost(vm: AppViewModel, nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val onTab = tabs.any { it.route == current }

    // 设置与提示词互跳、连点，都不该往回退栈里叠层：进得去也要回得来
    fun openPanel(route: String) {
        if (current == route) return
        nav.navigate(route) {
            if (current == Routes.SETTINGS || current == Routes.PROMPTS) popUpTo(current!!) { inclusive = true }
            launchSingleTop = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabs.firstOrNull { it.route == current }?.label ?: title(current)) },
                navigationIcon = {
                    // 非板块页没有底部入口可依赖，必须给一个看得见的返回
                    if (!onTab) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    // 三级提示词是主要旋钮之一，不该埋在设置页第五张卡里
                    IconButton(onClick = { openPanel(Routes.PROMPTS) }) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = "提示词",
                            tint = if (current == Routes.PROMPTS) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = { openPanel(Routes.SETTINGS) }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = if (current == Routes.SETTINGS) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current,
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 设置与提示词照样留着底部导航，一下就能回到原题、复习
            if (current !in flowRoutes) {
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
            composable(Routes.SOURCE) {
                SourceScreen(vm, onEditPrompt = { openPanel(Routes.PROMPTS) })
            }
            composable(Routes.REVIEW) {
                ReviewScreen(vm, onEditPrompt = { openPanel(Routes.PROMPTS) })
            }
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
