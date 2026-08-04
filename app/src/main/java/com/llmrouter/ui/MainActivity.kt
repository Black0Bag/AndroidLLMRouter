package com.llmrouter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.llmrouter.LlmRouterApp
import com.llmrouter.R
import com.llmrouter.ui.screens.ChannelEditScreen
import com.llmrouter.ui.screens.ChannelListScreen
import com.llmrouter.ui.screens.HomeScreen
import com.llmrouter.ui.screens.SettingsScreen
import com.llmrouter.ui.theme.LlmRouterTheme

sealed class Screen(val route: String, val title: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Home : Screen("home", R.string.home_title, Icons.Filled.Dashboard)
    data object Channels : Screen("channels", R.string.channels, Icons.Filled.Storage)
    data object Settings : Screen("settings", R.string.settings, Icons.Filled.Settings)
    data object ChannelEdit : Screen("channel_edit", R.string.edit_channel, Icons.Filled.Storage)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeAutoStartService()
        setContent {
            LlmRouterTheme {
                MainApp()
            }
        }
    }

    /**
     * v0.7.0: autoStart 语义变更——开机自启在 Android 15+ 被系统禁止
     * （BOOT_COMPLETED 不得启动 dataSync 前台服务），因此改为：
     * 用户打开 App 时（前台时机，合法）若开启"自动启动"且服务未运行，则自动启动。
     */
    private fun maybeAutoStartService() {
        if (com.llmrouter.service.RouterService.isServerRunning) return
        val app = application as LlmRouterApp
        try {
            val autoStart = kotlinx.coroutines.runBlocking { app.settingsRepository.getSnapshot().autoStart }
            if (autoStart) {
                com.llmrouter.service.RouterService.start(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "autoStart 检查失败", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    // 自定义 ViewModel Factory：用 LlmRouterApp 构造 MainViewModel
    val app = LocalContext.current.applicationContext as LlmRouterApp
    val viewModel: MainViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(app) as T
            }
        }
    )

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavItems = listOf(Screen.Home, Screen.Channels, Screen.Settings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            if (currentRoute in listOf(Screen.Home.route, Screen.Channels.route, Screen.Settings.route)) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(stringResource(screen.title)) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToChannels = { navController.navigate(Screen.Channels.route) }
                )
            }
            composable(Screen.Channels.route) {
                ChannelListScreen(
                    viewModel = viewModel,
                    onAddChannel = { navController.navigate("${Screen.ChannelEdit.route}?id=-1") },
                    onEditChannel = { id -> navController.navigate("${Screen.ChannelEdit.route}?id=$id") }
                )
            }
            composable("${Screen.ChannelEdit.route}?id={id}",
                arguments = listOf(androidx.navigation.navArgument("id") { defaultValue = "-1" })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: -1L
                ChannelEditScreen(
                    viewModel = viewModel,
                    channelId = if (id > 0) id else null,
                    onSaved = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
