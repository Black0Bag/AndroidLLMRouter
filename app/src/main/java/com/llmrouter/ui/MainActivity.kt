package com.llmrouter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Category
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
import com.llmrouter.AppLogger
import com.llmrouter.LlmRouterApp
import com.llmrouter.R
import com.llmrouter.ui.screens.ChannelEditScreen
import com.llmrouter.ui.screens.ChannelListScreen
import com.llmrouter.ui.screens.HomeScreen
import com.llmrouter.ui.screens.ModelGroupEditScreen
import com.llmrouter.ui.screens.ModelGroupListScreen
import com.llmrouter.ui.screens.SettingsScreen
import com.llmrouter.ui.theme.LlmRouterTheme

sealed class Screen(val route: String, val title: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Home : Screen("home", R.string.home_title, Icons.Filled.Dashboard)
    data object Channels : Screen("channels", R.string.channels, Icons.Filled.Storage)
    data object Settings : Screen("settings", R.string.settings, Icons.Filled.Settings)
    data object ModelGroups : Screen("model_groups", R.string.model_groups, Icons.Filled.Category)
    data object ChannelEdit : Screen("channel_edit", R.string.edit_channel, Icons.Filled.Storage)
    data object ModelGroupEdit : Screen("model_group_edit", R.string.edit_model_group, Icons.Filled.Category)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i("MainActivity", "onCreate 开始")
        setContent {
            LlmRouterTheme {
                MainApp()
            }
        }
        AppLogger.i("MainActivity", "UI 渲染完毕")
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

    val bottomNavItems = listOf(Screen.Home, Screen.Channels, Screen.ModelGroups, Screen.Settings)

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
            if (currentRoute in listOf(Screen.Home.route, Screen.Channels.route, Screen.ModelGroups.route, Screen.Settings.route)) {
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
            composable(Screen.ModelGroups.route) {
                ModelGroupListScreen(
                    modelGroups = viewModel.modelGroups.collectAsState().value,
                    onAddGroup = { navController.navigate("${Screen.ModelGroupEdit.route}?id=-1") },
                    onEditGroup = { id -> navController.navigate("${Screen.ModelGroupEdit.route}?id=$id") },
                    onDeleteGroup = { group -> viewModel.deleteModelGroup(group) }
                )
            }
            composable(
                "${Screen.ModelGroupEdit.route}?id={id}",
                arguments = listOf(androidx.navigation.navArgument("id") { defaultValue = "-1" })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: -1L
                val group = if (id > 0) viewModel.modelGroups.collectAsState().value.find { it.id == id } else null
                val channels = viewModel.channels.collectAsState().value
                ModelGroupEditScreen(
                    group = group,
                    channels = channels,
                    onSave = { name, displayName, members ->
                        viewModel.saveModelGroup(id, name, displayName, members)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
