package com.tradelens.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.repeatOnLifecycle
import com.tradelens.data.repository.pollWhileOpen
import com.tradelens.AppContainer
import com.tradelens.data.local.WatchlistEntity
import com.tradelens.data.model.Quote
import com.tradelens.data.repository.MarketRepository
import com.tradelens.data.repository.UserRepository
import com.tradelens.ui.components.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WatchlistViewModel(private val user: UserRepository, private val market: MarketRepository) : ViewModel() {
    val items: StateFlow<List<WatchlistEntity>?> = user.watchlist.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val quotes = _quotes.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    init {
        viewModelScope.launch {
            user.watchlist.map { list -> list.map { it.symbol } }.distinctUntilChanged().collect { refresh(it) }
        }
    }

    fun refresh(symbols: List<String> = items.value.orEmpty().map { it.symbol }) {
        if (symbols.isEmpty()) return
        viewModelScope.launch {
            _refreshing.value = true
            val loaded = market.quotes(symbols)
            _quotes.update { old -> old + loaded.associateBy { it.symbol } }
            _refreshing.value = false
        }
    }

    /** Live prices while the screen is visible: one quiet batched refresh per poll. */
    suspend fun live() = market.pollWhileOpen {
        val symbols = items.value.orEmpty().map { it.symbol }
        if (symbols.isNotEmpty()) { val loaded = market.quotes(symbols, forceRefresh = true); _quotes.update { old -> old + loaded.associateBy { it.symbol } } }
    }

    fun remove(symbol: String) = viewModelScope.launch { user.removeWatch(symbol) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(container: AppContainer, onOpenStock: (String) -> Unit, onSearch: () -> Unit) {
    val vm = rememberVm { WatchlistViewModel(container.user, container.market) }
    val items by vm.items.collectAsStateWithLifecycle()
    val quotes by vm.quotes.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { vm.live() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Watchlist", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Add stocks") }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val list = items
        Column(Modifier.padding(padding)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                list == null -> Loading()
                list.isEmpty() -> CenterMessage("Your watchlist is empty. Search for a stock and tap the star to add it here.", actionLabel = "Search stocks", onAction = onSearch)
                else -> LazyColumn {
                    items(list, key = { it.symbol }) { w ->
                        val q = quotes[w.symbol]
                        QuoteRow(
                            title = Fmt.ticker(w.symbol),
                            subtitle = w.name,
                            price = q?.let { Fmt.price(it.price, it.currency) } ?: "—",
                            change = q?.change ?: 0.0,
                            pct = q?.changePct ?: 0.0,
                            onClick = { onOpenStock(w.symbol) },
                            trailing = {
                                IconButton(onClick = { vm.remove(w.symbol) }) {
                                    Icon(Icons.Filled.Delete, "Remove ${w.symbol}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}
