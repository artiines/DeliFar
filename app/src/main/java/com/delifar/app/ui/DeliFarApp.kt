package com.delifar.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.delifar.app.R
import com.delifar.app.ui.components.ProvideAppLocale
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.screens.HomeScreen
import com.delifar.app.ui.screens.JobDetailScreen
import com.delifar.app.ui.screens.JobEditorScreen
import com.delifar.app.ui.screens.MapScreen
import com.delifar.app.ui.screens.ScheduleScreen
import com.delifar.app.ui.screens.SettingsScreen
import com.delifar.app.ui.screens.StoreDetailScreen
import com.delifar.app.ui.screens.StoreEditorScreen
import com.delifar.app.ui.screens.StoresScreen
import com.delifar.app.ui.theme.DeliFarTheme

private object Routes {
    const val HOME = "home"
    const val MAP = "map"
    const val SCHEDULE = "schedule"
    const val STORES = "stores"
    const val SETTINGS = "settings"
    const val STORE_EDITOR = "store_editor"
    const val STORE_DETAIL = "store_detail"
    const val JOB_EDITOR = "job_editor"
    const val JOB_DETAIL = "job_detail"
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

@Composable
fun DeliFarApp(viewModel: DeliFarViewModel = viewModel()) {
    val stores by viewModel.stores.collectAsState()
    val jobRows by viewModel.jobRows.collectAsState()
    val prefs by viewModel.preferences.collectAsState()
    val latestDraft by viewModel.latestDraft.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomTabs = remember {
        listOf(
            BottomTab(Routes.HOME, R.string.tab_home, Icons.Default.Home),
            BottomTab(Routes.MAP, R.string.tab_map, Icons.Default.Map),
            BottomTab(Routes.SCHEDULE, R.string.tab_schedule, Icons.Default.CalendarMonth),
            BottomTab(Routes.STORES, R.string.tab_stores, Icons.Default.Store)
        )
    }
    val showBottomBar = currentRoute in setOf(Routes.HOME, Routes.MAP, Routes.SCHEDULE, Routes.STORES)

    DeliFarTheme {
        ProvideAppLocale(languageCode = prefs.language) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar {
                            bottomTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = lStr(tab.labelRes)) },
                                    label = { Text(lStr(tab.labelRes)) }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Routes.HOME,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            stores = stores,
                            jobRows = jobRows,
                            latestDraft = latestDraft,
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onNewJob = { navController.navigate(Routes.JOB_EDITOR) },
                            onResumeDraft = { draft ->
                                navController.navigate("${Routes.JOB_EDITOR}?draftId=${draft.jobId}")
                            },
                            onDiscardDraft = { draftId -> viewModel.discardDraft(draftId) },
                            onOpenJob = { jobId -> navController.navigate("${Routes.JOB_DETAIL}/$jobId") },
                            onAddStore = { navController.navigate(Routes.STORE_EDITOR) }
                        )
                    }

                    composable(Routes.MAP) {
                        MapScreen(
                            stores = stores,
                            jobRows = jobRows,
                            defaultArea = prefs.area,
                            tileUrlTemplate = prefs.tileUrl,
                            tileLoader = viewModel.tileLoader,
                            onOpenStore = { storeId -> navController.navigate("${Routes.STORE_DETAIL}/$storeId") },
                            onOpenJob = { jobId -> navController.navigate("${Routes.JOB_DETAIL}/$jobId") },
                            onNewJobForStore = { storeId ->
                                navController.navigate("${Routes.JOB_EDITOR}?storeId=$storeId")
                            }
                        )
                    }

                    composable(Routes.SCHEDULE) {
                        ScheduleScreen(
                            jobRows = jobRows,
                            hasActiveStores = stores.any { !it.store.archived },
                            onOpenJob = { jobId -> navController.navigate("${Routes.JOB_DETAIL}/$jobId") },
                            onNewJob = { navController.navigate(Routes.JOB_EDITOR) }
                        )
                    }

                    composable(Routes.STORES) {
                        StoresScreen(
                            stores = stores,
                            jobRows = jobRows,
                            onAddStore = { navController.navigate(Routes.STORE_EDITOR) },
                            onOpenStore = { storeId -> navController.navigate("${Routes.STORE_DETAIL}/$storeId") }
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = "${Routes.STORE_EDITOR}?storeId={storeId}",
                        arguments = listOf(
                            navArgument("storeId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStack ->
                        val existingId = backStack.arguments?.getString("storeId")
                        StoreEditorScreen(
                            existingStoreId = existingId,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onSaved = { savedId ->
                                navController.popBackStack()
                                if (existingId == null) {
                                    navController.navigate("${Routes.STORE_DETAIL}/$savedId")
                                }
                            }
                        )
                    }

                    composable(
                        route = "${Routes.STORE_DETAIL}/{storeId}",
                        arguments = listOf(navArgument("storeId") { type = NavType.StringType })
                    ) { backStack ->
                        val storeId = backStack.arguments?.getString("storeId").orEmpty()
                        StoreDetailScreen(
                            storeId = storeId,
                            viewModel = viewModel,
                            allJobRows = jobRows,
                            onBack = { navController.popBackStack() },
                            onEditStore = { id -> navController.navigate("${Routes.STORE_EDITOR}?storeId=$id") },
                            onNewJobForStore = { id -> navController.navigate("${Routes.JOB_EDITOR}?storeId=$id") },
                            onOpenJob = { jobId -> navController.navigate("${Routes.JOB_DETAIL}/$jobId") }
                        )
                    }

                    composable(
                        route = "${Routes.JOB_EDITOR}?jobId={jobId}&storeId={storeId}&draftId={draftId}",
                        arguments = listOf(
                            navArgument("jobId") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("storeId") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("draftId") { type = NavType.StringType; nullable = true; defaultValue = null }
                        )
                    ) { backStack ->
                        val jobId = backStack.arguments?.getString("jobId")
                        val storeId = backStack.arguments?.getString("storeId")
                        val draftId = backStack.arguments?.getString("draftId")
                        JobEditorScreen(
                            jobIdArg = jobId,
                            preselectedStoreId = storeId,
                            resumeDraftId = draftId,
                            stores = stores,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onSaved = { savedJobId ->
                                navController.popBackStack()
                                if (jobId == null) {
                                    navController.navigate("${Routes.JOB_DETAIL}/$savedJobId")
                                }
                            }
                        )
                    }

                    composable(
                        route = "${Routes.JOB_DETAIL}/{jobId}",
                        arguments = listOf(navArgument("jobId") { type = NavType.StringType })
                    ) { backStack ->
                        val jobId = backStack.arguments?.getString("jobId").orEmpty()
                        JobDetailScreen(
                            jobId = jobId,
                            viewModel = viewModel,
                            stores = stores,
                            onBack = { navController.popBackStack() },
                            onEditJob = { id -> navController.navigate("${Routes.JOB_EDITOR}?jobId=$id") },
                            onOpenStore = { storeId -> navController.navigate("${Routes.STORE_DETAIL}/$storeId") }
                        )
                    }
                }
            }
        }
    }
}
