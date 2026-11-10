package com.tradelens.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradelens.ui.components.DemoBanner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tradelens.AppContainer
import com.tradelens.ui.screens.*

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("markets", "Markets", Icons.AutoMirrored.Filled.ShowChart),
    Tab("watchlist", "Watchlist", Icons.Filled.Star),
    Tab("scanner", "Scanner", Icons.Filled.Insights),
    Tab("portfolio", "Portfolio", Icons.Filled.AccountBalanceWallet),
    Tab("alerts", "Alerts", Icons.Filled.Notifications),
)

@Composable
fun TradeLensNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val openStock: (String) -> Unit = { nav.navigate("stock/${Uri.encode(it)}") }

    val settings by container.settings.settings.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { if (settings.demoMode) DemoBanner(Modifier.statusBarsPadding()) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "markets", modifier = Modifier.padding(padding)) {
            composable("markets") {
                MarketsScreen(container, openStock, onSearch = { nav.navigate("search") }, onSettings = { nav.navigate("settings") })
            }
            composable("watchlist") { WatchlistScreen(container, openStock, onSearch = { nav.navigate("search") }) }
            composable("scanner") { ScannerScreen(container, openStock) }
            composable("portfolio") { PortfolioScreen(container, openStock) }
            composable("alerts") { AlertsScreen(container, openStock) }
            composable("search") { SearchScreen(container, openStock, onBack = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
            composable("stock/{symbol}", arguments = listOf(navArgument("symbol") { type = NavType.StringType })) { e ->
                val symbol = e.arguments?.getString("symbol").orEmpty()
                StockDetailScreen(container, symbol, onBack = { nav.popBackStack() })
            }
        }
    }
}
