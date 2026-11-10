package com.tradelens.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tradelens.AppContainer
import com.tradelens.data.local.HoldingEntity
import com.tradelens.data.local.TradeEntity
import com.tradelens.data.repository.MarketRepository
import com.tradelens.data.repository.UserRepository
import com.tradelens.ui.components.*
import com.tradelens.ui.theme.Num
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PortfolioViewModel(user: UserRepository, private val market: MarketRepository) : ViewModel() {
    val holdings: StateFlow<List<HoldingEntity>?> = user.holdings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val trades: StateFlow<List<TradeEntity>> = user.trades.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val realized: StateFlow<Double> = user.realizedPnl.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)
    private val _prices = MutableStateFlow<Map<String, Double>>(emptyMap())
    val prices = _prices.asStateFlow()

    init {
        viewModelScope.launch {
            user.holdings.map { h -> h.map { it.symbol } }.distinctUntilChanged().collect { refresh(it) }
        }
    }

    fun refresh(symbols: List<String> = holdings.value.orEmpty().map { it.symbol }) {
        if (symbols.isEmpty()) return
        viewModelScope.launch {
            val quotes = market.quotes(symbols)
            _prices.update { old -> old + quotes.associate { it.symbol to it.price } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(container: AppContainer, onOpenStock: (String) -> Unit) {
    val vm = rememberVm { PortfolioViewModel(container.user, container.market) }
    val holdings by vm.holdings.collectAsStateWithLifecycle()
    val trades by vm.trades.collectAsStateWithLifecycle()
    val prices by vm.prices.collectAsStateWithLifecycle()
    val realized by vm.realized.collectAsStateWithLifecycle()
    val dateFmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Paper portfolio", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh prices") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val list = holdings
        if (list == null) Loading(Modifier.padding(padding))
        else LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            val invested = list.sumOf { it.quantity * it.avgPrice }
            val current = list.sumOf { it.quantity * (prices[it.symbol] ?: it.avgPrice) }
            val pnl = current - invested
            item {
                Section("Summary") {
                    KeyValue("Invested", Fmt.price(invested))
                    KeyValue("Current value", Fmt.price(current), emphasize = true)
                    KeyValue("Unrealised P&L", "${Fmt.signed(pnl)}  (${Fmt.pct(if (invested > 0) pnl / invested * 100 else 0.0)})", changeColor(pnl), emphasize = true)
                    KeyValue("Realised P&L", Fmt.signed(realized), changeColor(realized))
                    Spacer(Modifier.height(6.dp))
                    Text("Practice with virtual money: use Buy and Sell on any stock page to test the suggested trade plans.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (list.isEmpty()) {
                item { CenterMessage("No holdings yet. Open a stock and tap Buy to place a paper trade.", Modifier.height(180.dp)) }
            } else {
                item { Text("Holdings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)) }
                items(list, key = { it.symbol }) { h ->
                    val ltp = prices[h.symbol] ?: h.avgPrice
                    val hp = (ltp - h.avgPrice) * h.quantity
                    QuoteRow(
                        title = Fmt.ticker(h.symbol),
                        subtitle = "${h.quantity} shares · avg ${Fmt.price(h.avgPrice)}",
                        price = Fmt.price(ltp * h.quantity),
                        change = hp,
                        pct = if (h.avgPrice > 0) (ltp - h.avgPrice) / h.avgPrice * 100 else 0.0,
                        onClick = { onOpenStock(h.symbol) },
                    )
                }
            }
            if (trades.isNotEmpty()) {
                item { Text("Trade history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)) }
                items(trades, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${if (t.side == "BUY") "Bought" else "Sold"} ${t.quantity} ${Fmt.ticker(t.symbol)}", style = MaterialTheme.typography.bodyMedium)
                            Text(dateFmt.format(Date(t.time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("@ ${Fmt.price(t.price)}", style = MaterialTheme.typography.bodyMedium.merge(Num))
                            t.realizedPnl?.let { Text(Fmt.signed(it), style = MaterialTheme.typography.bodySmall.merge(Num), color = changeColor(it)) }
                        }
                    }
                }
            }
        }
    }
}
