package com.tradelens.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tradelens.AppContainer
import com.tradelens.analysis.Analysis
import com.tradelens.analysis.AnalysisEngine
import com.tradelens.analysis.Direction
import com.tradelens.data.model.ChartRange
import com.tradelens.data.model.Quote
import com.tradelens.data.model.Universe
import com.tradelens.data.repository.*
import com.tradelens.ui.components.*
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ScannerViewModel(
    private val market: MarketRepository,
    private val user: UserRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    enum class Source { NIFTY50, WATCHLIST }
    enum class Filter { ALL, BUY, SELL }
    data class ScanRow(val quote: Quote, val analysis: Analysis)
    data class State(
        val source: Source = Source.NIFTY50,
        val filter: Filter = Filter.BUY,
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val rows: List<ScanRow> = emptyList(),
        val failed: Int = 0,
        val hasRun: Boolean = false,
    ) {
        val visible: List<ScanRow> get() = rows.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.BUY -> it.analysis.signal.isBuy
                Filter.SELL -> it.analysis.signal.isSell
            }
        }.let { list ->
            // Rank by score, then by confidence, so the cleanest setups come first.
            if (filter == Filter.SELL) list.sortedWith(compareBy({ it.analysis.score }, { -it.analysis.confidence }))
            else list.sortedWith(compareByDescending<ScanRow> { it.analysis.score }.thenByDescending { it.analysis.confidence })
        }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setSource(s: Source) = _state.update { it.copy(source = s) }
    fun setFilter(f: Filter) = _state.update { it.copy(filter = f) }

    fun run() {
        job?.cancel()
        job = viewModelScope.launch {
            val symbols = when (_state.value.source) {
                Source.NIFTY50 -> Universe.NIFTY_50
                Source.WATCHLIST -> user.watchlist.first().map { it.symbol }
            }
            _state.update { it.copy(running = true, done = 0, total = symbols.size, rows = emptyList(), failed = 0, hasRun = true) }
            val cfg = settings.settings.value
            val bench = market.benchmark()
            val gate = Semaphore(5)
            symbols.map { s ->
                async {
                    gate.withPermit {
                        val result = runCatchingCancellable {
                            val d = market.analysisHistory(s)
                            withContext(Dispatchers.Default) {
                                AnalysisEngine.analyze(d.candles, d.quote.price, d.quote.high52, d.quote.low52,
                                    cfg.capital, cfg.riskPercent, d.quote.isIndex, bench)
                            }?.let { ScanRow(d.quote, it) }
                        }.getOrNull()
                        _state.update {
                            it.copy(
                                done = it.done + 1,
                                rows = if (result != null) it.rows + result else it.rows,
                                failed = if (result == null) it.failed + 1 else it.failed,
                            )
                        }
                    }
                }
            }.awaitAll()
            _state.update { it.copy(running = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(container: AppContainer, onOpenStock: (String) -> Unit) {
    val vm = rememberVm { ScannerViewModel(container.market, container.user, container.settings) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Scanner", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Runs the full analysis on every stock in the list and ranks the best setups.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(state.source == ScannerViewModel.Source.NIFTY50, { vm.setSource(ScannerViewModel.Source.NIFTY50) }, { Text("Nifty 50") })
                        FilterChip(state.source == ScannerViewModel.Source.WATCHLIST, { vm.setSource(ScannerViewModel.Source.WATCHLIST) }, { Text("My watchlist") })
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = vm::run, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.running) "Scanning ${state.done} of ${state.total}…" else if (state.hasRun) "Scan again" else "Run scan")
                    }
                    if (state.running && state.total > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { state.done / state.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    if (state.hasRun) {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScannerViewModel.Filter.entries.forEach { f ->
                                FilterChip(state.filter == f, { vm.setFilter(f) }, {
                                    Text(when (f) {
                                        ScannerViewModel.Filter.ALL -> "All (${state.rows.size})"
                                        ScannerViewModel.Filter.BUY -> "Buy ratings (${state.rows.count { it.analysis.signal.isBuy }})"
                                        ScannerViewModel.Filter.SELL -> "Sell ratings (${state.rows.count { it.analysis.signal.isSell }})"
                                    })
                                })
                            }
                        }
                    }
                }
            }
            if (!state.hasRun) {
                item { CenterMessage("Tap Run scan to find stocks with the strongest buy and sell signals right now.", Modifier.height(240.dp)) }
            } else if (!state.running && state.visible.isEmpty()) {
                item {
                    CenterMessage(
                        if (state.rows.isEmpty()) "No stocks could be analysed. Check your connection, or add stocks to your watchlist first."
                        else "No stocks match this filter right now.",
                        Modifier.height(200.dp),
                    )
                }
            }
            items(state.visible, key = { it.quote.symbol }) { row -> ScanCard(row) { onOpenStock(row.quote.symbol) } }
            if (state.hasRun && !state.running && state.failed > 0) {
                item {
                    Text("${state.failed} symbols couldn't be loaded and were skipped.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ScanCard(row: ScannerViewModel.ScanRow, onClick: () -> Unit) {
    val q = row.quote
    val a = row.analysis
    val p = a.plan
    val mc = LocalMarketColors.current
    Section(Fmt.ticker(q.symbol), Modifier.clickable(onClick = onClick), trailing = { SignalPill(a.signal) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.price(q.price, q.currency), style = MaterialTheme.typography.titleMedium.merge(Num), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            ChangeText(q.change, q.changePct, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text("Score ${if (a.score > 0) "+" else ""}${a.score} · ${a.confidence}%", style = MaterialTheme.typography.labelLarge.merge(Num), color = signalColor(a.signal))
        }
        Spacer(Modifier.height(4.dp))
        Text(a.entry.headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Mini(if (p.direction == Direction.LONG) "Best entry" else "Short at", Fmt.num(p.entry), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Mini("Stop-loss", Fmt.num(p.stopLoss), mc.loss, Modifier.weight(1f))
            Mini("Target 2", Fmt.num(p.targets[1].price), mc.gain, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Mini(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Num), color = color, fontWeight = FontWeight.Medium)
    }
}
