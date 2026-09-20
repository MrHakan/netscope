package com.netscope.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.netscope.feature.access.FtpBrowserScreen
import com.netscope.feature.access.ServiceExplorerScreen
import com.netscope.feature.dashboard.DashboardScreen
import com.netscope.feature.devices.DeviceDetailScreen
import com.netscope.feature.devices.DevicesScreen
import com.netscope.feature.history.HistoryScreen
import com.netscope.feature.more.MoreScreen
import com.netscope.feature.networks.NetworksScreen
import com.netscope.feature.permissions.PermissionGate
import com.netscope.feature.settings.SettingsScreen
import com.netscope.feature.subnets.SubnetsScreen
import com.netscope.feature.tools.ToolsScreen
import com.netscope.feature.wifi.WifiScreen

/** Every route in the app. Keeping them in one place makes deep links trivial to add. */
object Routes {
    const val DASHBOARD = "dashboard"
    const val DEVICES = "devices"
    const val DEVICE_DETAIL = "device/{deviceKey}"
    const val WIFI = "wifi"
    const val TOOLS = "tools"
    const val MORE = "more"
    const val NETWORKS = "networks"
    const val SUBNETS = "subnets"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SERVICES = "services?target={target}"
    const val FTP_BROWSER = "ftp?host={host}&port={port}"

    fun deviceDetail(deviceKey: String) = "device/$deviceKey"
    fun serviceExplorer(target: String) = "services?target=" + Uri.encode(target)
    fun ftpBrowser(host: String, port: Int) = "ftp?host=" + Uri.encode(host) + "&port=" + port
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.DASHBOARD, "Dashboard", Icons.Default.Dashboard),
    BottomDestination(Routes.DEVICES, "Devices", Icons.Default.Devices),
    BottomDestination(Routes.WIFI, "Wi-Fi", Icons.Default.Wifi),
    BottomDestination(Routes.TOOLS, "Tools", Icons.Default.Build),
    BottomDestination(Routes.MORE, "More", Icons.Default.MoreHoriz),
)

@Composable
fun NetScopeApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = currentDestination?.route in bottomDestinations.map { it.route }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // Keep a single copy of each tab and restore its state,
                                    // so switching tabs never re-runs a scan.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Shown over the first screen on first launch, then never again.
            PermissionGate()

            NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onOpenDevices = { navController.navigate(Routes.DEVICES) },
                        onOpenSubnets = { navController.navigate(Routes.SUBNETS) },
                        onOpenWifi = { navController.navigate(Routes.WIFI) },
                        onOpenTools = { navController.navigate(Routes.TOOLS) },
                        onOpenHistory = { navController.navigate(Routes.HISTORY) },
                        onOpenNetworks = { navController.navigate(Routes.NETWORKS) },
                    )
                }
                composable(Routes.DEVICES) {
                    DevicesScreen(
                        onOpenDevice = { key -> navController.navigate(Routes.deviceDetail(key)) },
                    )
                }
                composable(Routes.DEVICE_DETAIL) { entry ->
                    DeviceDetailScreen(
                        deviceKey = entry.arguments?.getString("deviceKey").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onExploreServices = { host -> navController.navigate(Routes.serviceExplorer(host)) },
                    )
                }
                composable(Routes.WIFI) { WifiScreen() }
                composable(Routes.TOOLS) { ToolsScreen() }
                composable(Routes.MORE) {
                    MoreScreen(
                        onOpenNetworks = { navController.navigate(Routes.NETWORKS) },
                        onOpenSubnets = { navController.navigate(Routes.SUBNETS) },
                        onOpenHistory = { navController.navigate(Routes.HISTORY) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.NETWORKS) { NetworksScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SUBNETS) {
                    SubnetsScreen(
                        onBack = { navController.popBackStack() },
                        onExploreServices = { target -> navController.navigate(Routes.serviceExplorer(target)) },
                    )
                }
                composable(Routes.HISTORY) { HistoryScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SERVICES) {
                    ServiceExplorerScreen(
                        onBack = { navController.popBackStack() },
                        onOpenFtp = { host, port -> navController.navigate(Routes.ftpBrowser(host, port)) },
                    )
                }
                composable(Routes.FTP_BROWSER) {
                    FtpBrowserScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
