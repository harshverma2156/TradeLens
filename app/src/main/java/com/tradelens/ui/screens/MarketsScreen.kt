package com.tradelens.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.repeatOnLifecycle
import com.tradelens.data.repository.pollWhileOpen
import com.tradelens.AppContainer
import com.tradelens.data.model.Quote
import com.tradelens.data.model.Universe
import com.tradelens.data.repository.MarketRepository
import com.tradelens.ui.components.*
import com.tradelens.ui.theme.Num
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MarketsViewModel(private val market: MarketRepository) : ViewModel() {
    enum class Sort { POPULAR, GAINERS, LOSERS }
    data class State(
        val loading: Boolean = true,
        val indices: List<Quote> = emptyList(),
        val sectors: List<Quote> = emptyList(),
        val stocks: List<Quote> = emptyList(),
        val sort: Sort = Sort.POPULAR,
        val error: String? = null,
    ) {
        val sorted: List<Quote> get() = when (sort) {
            Sort.POPULAR -> stocks
            Sort.GAINERS -> stocks.sortedByDescending { it.changePct }
            Sort.LOSERS -> stocks.sortedBy { it.changePct }
        }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            fetch(force = false)
        }
    }

    /** Live prices while the screen is visible: one quiet batched refresh per poll, no spinner. */
    suspend fun live() = market.pollWhileOpen { fetch(force = true) }

    private suspend fun fetch(force: Boolean) {
        // Three batched requests (provider batch size permitting) instead of one call per symbol.
        val indices = market.quotes(Universe.INDICES.keys, force)
        val sectors = market.quotes(Universe.SECTORS.keys - Universe.INDICES.keys, force)
        val stocks = market.quotes(Universe.POPULAR, force)
        _state.update {
            // A failed live poll keeps the prices already on screen.
            if (force && indices.isEmpty() && stocks.isEmpty()) it
            else it.copy(
                loading = false, indices = indices.ifEmpty { it.indices }, sectors = sectors.ifEmpty { it.sectors }, stocks = stocks.ifEmpty { it.stocks },
                error = if (indices.isEmpty() && stocks.isEmpty() && it.stocks.isEmpty()) "Couldn't load prices. Check your connection and tap refresh." else null,
            )
        }
    }

    fun setSort(sort: Sort) = _state.update { it.copy(sort = sort) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsScreen(container: AppContainer, onOpenStock: (String) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val vm = rememberVm { MarketsViewModel(container.market) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { vm.live() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Markets", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search stocks") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            state.loading && state.stocks.isEmpty() -> Loading(Modifier.padding(padding))
            state.error != null -> CenterMessage(state.error!!, Modifier.padding(padding), "Try again", vm::refresh)
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    DataStatusBar((state.indices + state.stocks).firstOrNull()?.status, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), onRetry = vm::refresh)
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.indices, key = { it.symbol }) { q -> IndexCard(q) { onOpenStock(q.symbol) } }
                    }
                }
                if (state.sectors.isNotEmpty()) {
                    item {
                        Text("Sectors", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.sectors.sortedByDescending { it.changePct }, key = { it.symbol }) { q -> SectorChip(q) { onOpenStock(q.symbol) } }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarketsViewModel.Sort.entries.forEach { s ->
                            FilterChip(
                                selected = state.sort == s,
                                onClick = { vm.setSort(s) },
                                label = { Text(when (s) { MarketsViewModel.Sort.POPULAR -> "Popular"; MarketsViewModel.Sort.GAINERS -> "Top gainers"; MarketsViewModel.Sort.LOSERS -> "Top losers" }) },
                            )
                        }
                    }
                }
                items(state.sorted, key = { it.symbol }) { q ->
                    QuoteRow(Fmt.ticker(q.symbol), q.name, Fmt.price(q.price, q.currency), q.change, q.changePct, onClick = { onOpenStock(q.symbol) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
                item {
                    Text(
                        "Prices may be delayed. Tap any stock for a full technical analysis and trade plan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectorChip(q: Quote, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(q.name.removePrefix("NIFTY "), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        ChangeText(q.change, q.changePct, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun IndexCard(q: Quote, onClick: () -> Unit) {
    Column(
        Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(q.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(Fmt.num(q.price), style = MaterialTheme.typography.titleLarge.merge(Num), fontWeight = FontWeight.SemiBold)
        ChangeText(q.change, q.changePct, style = MaterialTheme.typography.bodySmall)
    }
}
