package com.buse.smartfreeze.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.buse.smartfreeze.ui.AlertsViewModel
import com.buse.smartfreeze.ui.HistoryViewModel
import com.buse.smartfreeze.ui.MonitorViewModel
import com.buse.smartfreeze.ui.ResearchViewModel
import com.buse.smartfreeze.ui.SettingsViewModel
import com.buse.smartfreeze.ui.screens.AboutScreen
import com.buse.smartfreeze.ui.screens.AlertsScreen
import com.buse.smartfreeze.ui.screens.DashboardScreen
import com.buse.smartfreeze.ui.screens.HistoryScreen
import com.buse.smartfreeze.ui.screens.InsightsScreen
import com.buse.smartfreeze.ui.screens.LiveScreen
import com.buse.smartfreeze.ui.screens.OnboardingScreen
import com.buse.smartfreeze.ui.screens.ResearchScreen
import com.buse.smartfreeze.ui.screens.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val LIVE = "live"
    const val INSIGHTS = "insights"
    const val HISTORY = "history"
    const val ALERTS = "alerts"
    const val RESEARCH = "research"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Dashboard", Icons.Filled.Dashboard),
    Tab(Routes.LIVE, "Live", Icons.Filled.ShowChart),
    Tab(Routes.INSIGHTS, "Insights", Icons.Filled.Insights),
    Tab(Routes.HISTORY, "History", Icons.Filled.History),
    Tab(Routes.ALERTS, "Alerts", Icons.Filled.Notifications),
)

private val titles = mapOf(
    Routes.DASHBOARD to "SmartFreeze AI", Routes.LIVE to "Real-time monitoring", Routes.INSIGHTS to "AI insights",
    Routes.HISTORY to "Performance history", Routes.ALERTS to "Alerts", Routes.RESEARCH to "Research & testing",
    Routes.SETTINGS to "Settings", Routes.ABOUT to "About",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(factory: ViewModelProvider.Factory, onboardingDone: Boolean, requestedDestination: String?, onDestinationHandled: () -> Unit) {
    val nav = rememberNavController()
    val monitorVm: MonitorViewModel = viewModel(factory = factory)
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val unread by monitorVm.unacknowledgedAlerts.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(requestedDestination) {
        if (requestedDestination == Routes.ALERTS && onboardingDone) {
            nav.navigate(Routes.ALERTS) { launchSingleTop = true }
            onDestinationHandled()
        }
    }

    val isTab = tabs.any { it.route == route }
    fun goTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        topBar = {
            if (route != Routes.ONBOARDING && route != null) {
                TopAppBar(
                    title = { Text(titles[route] ?: "SmartFreeze AI") },
                    navigationIcon = {
                        if (!isTab) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Research & testing") }, onClick = { menu = false; nav.navigate(Routes.RESEARCH) })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; nav.navigate(Routes.SETTINGS) })
                            DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; nav.navigate(Routes.ABOUT) })
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { goTab(t.route) },
                        icon = {
                            if (t.route == Routes.ALERTS && unread > 0) BadgedBox(badge = { Badge { Text("$unread") } }) { Icon(t.icon, null) }
                            else Icon(t.icon, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        // Fixed at first composition so that finishing onboarding does not rebuild the graph.
        val start = remember { if (onboardingDone) Routes.DASHBOARD else Routes.ONBOARDING }
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(settingsVm, monitorVm) {
                    nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            }
            composable(Routes.DASHBOARD) { DashboardScreen(monitorVm) { goTab(Routes.INSIGHTS) } }
            composable(Routes.LIVE) { LiveScreen(monitorVm) }
            composable(Routes.INSIGHTS) { InsightsScreen(monitorVm) }
            composable(Routes.HISTORY) {
                val vm: HistoryViewModel = viewModel(factory = factory)
                HistoryScreen(vm) { nav.navigate(Routes.SETTINGS) }
            }
            composable(Routes.ALERTS) {
                val vm: AlertsViewModel = viewModel(factory = factory)
                AlertsScreen(vm)
            }
            composable(Routes.RESEARCH) {
                val vm: ResearchViewModel = viewModel(factory = factory)
                ResearchScreen(vm)
            }
            composable(Routes.SETTINGS) { SettingsScreen(settingsVm) { nav.navigate(Routes.ABOUT) } }
            composable(Routes.ABOUT) { AboutScreen() }
        }
    }
}
