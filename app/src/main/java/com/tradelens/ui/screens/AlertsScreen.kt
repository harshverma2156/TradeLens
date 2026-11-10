package com.tradelens.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tradelens.AppContainer
import com.tradelens.data.local.AlertEntity
import com.tradelens.data.repository.UserRepository
import com.tradelens.ui.components.*
import com.tradelens.work.AlertScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlertsViewModel(private val user: UserRepository) : ViewModel() {
    val alerts: StateFlow<List<AlertEntity>?> = user.alerts.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun delete(id: Long) = viewModelScope.launch { user.deleteAlert(id) }
    fun setActive(id: Long, active: Boolean) = viewModelScope.launch { user.setAlertActive(id, active) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(container: AppContainer, onOpenStock: (String) -> Unit) {
    val vm = rememberVm { AlertsViewModel(container.user) }
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Price alerts", fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = { AlertScheduler.checkNow(context) }) { Text("Check now") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val list = alerts
        when {
            list == null -> Loading(Modifier.padding(padding))
            list.isEmpty() -> CenterMessage("No alerts yet. Open a stock and tap the bell icon to get notified at your entry, stop-loss or target.", Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding)) {
                item {
                    Text("Alerts are checked in the background about every 15 minutes while you're online. A triggered alert turns off automatically.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(list, key = { it.id }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenStock(a.symbol) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.ticker(a.symbol), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${if (a.above) "Rises above" else "Falls below"} ${Fmt.price(a.targetPrice)}" + if (!a.active) " · triggered or paused" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = a.active, onCheckedChange = { vm.setActive(a.id, it) })
                        IconButton(onClick = { vm.delete(a.id) }) { Icon(Icons.Filled.Delete, "Delete alert", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
