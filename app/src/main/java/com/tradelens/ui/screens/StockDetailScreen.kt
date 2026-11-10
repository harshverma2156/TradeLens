package com.tradelens.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tradelens.AppContainer
import com.tradelens.analysis.*
import com.tradelens.data.model.Candle
import com.tradelens.data.model.ChartRange
import com.tradelens.data.model.Quote
import com.tradelens.data.repository.*
import com.tradelens.ui.components.*
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class StockDetailViewModel(
    private val symbol: String,
    private val market: MarketRepository,
    private val user: UserRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val quote: Quote? = null,
        val daily: List<Candle> = emptyList(),      // 2 years of daily candles, used for analysis
        val benchmark: List<Candle>? = null,        // NIFTY 50, for relative strength and market backdrop
        val analyzing: Boolean = false,
        val analysis: Analysis? = null,
        val range: ChartRange = ChartRange.M6,
        val chartCandles: List<Candle> = emptyList(),
        val chartLoading: Boolean = false,
        val candleMode: Boolean = true,
        val showLevels: Boolean = true,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    val watched = user.isWatched(symbol).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val userSettings = settings.settings

    init {
        load()
        // Re-size the trade plan when capital or risk % changes.
        viewModelScope.launch { settings.settings.drop(1).collect { reanalyze() } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val bench = async { market.benchmark() }
            runCatchingCancellable { market.analysisHistory(symbol) }
                .onSuccess { data ->
                    _state.update { it.copy(loading = false, quote = data.quote, daily = data.candles, benchmark = bench.await()) }
                    reanalyze()
                    setRange(_state.value.range)
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.friendlyMessage()) } }
        }
    }

    private var analysisJob: Job? = null

    private fun reanalyze() {
        val s = _state.value
        val q = s.quote ?: return
        val cfg = settings.settings.value
        analysisJob?.cancel()
        _state.update { it.copy(analyzing = true) }
        analysisJob = viewModelScope.launch {
            // The backtest replays the rules over ~2 years, so keep it off the main thread.
            val a = withContext(Dispatchers.Default) {
                AnalysisEngine.analyze(s.daily, q.price, q.high52, q.low52, cfg.capital, cfg.riskPercent, q.isIndex, s.benchmark)
            }
            _state.update { it.copy(analysis = a, analyzing = false) }
        }
    }

    fun setRange(range: ChartRange) {
        val daily = _state.value.daily
        // 1M / 6M / 1Y are sliced from the daily data we already have; others need a fetch.
        val sliced = when (range) {
            ChartRange.M1 -> daily.takeLast(22)
            ChartRange.M6 -> daily.takeLast(126)
            ChartRange.Y1 -> daily.takeLast(252)
            else -> null
        }
        if (sliced != null) {
            _state.update { it.copy(range = range, chartCandles = sliced, chartLoading = false) }
            return
        }
        _state.update { it.copy(range = range, chartLoading = true) }
        viewModelScope.launch {
            runCatchingCancellable { market.history(symbol, range) }
                .onSuccess { d -> _state.update { if (it.range == range) it.copy(chartCandles = d.candles, chartLoading = false) else it } }
                .onFailure { e -> _state.update { it.copy(chartLoading = false, message = e.friendlyMessage()) } }
        }
    }

    fun toggleCandles() = _state.update { it.copy(candleMode = !it.candleMode) }
    fun toggleLevels() = _state.update { it.copy(showLevels = !it.showLevels) }

    fun toggleWatch() = viewModelScope.launch {
        user.toggleWatch(symbol, _state.value.quote?.name ?: symbol)
    }

    fun buy(qty: Int) = viewModelScope.launch {
        val q = _state.value.quote ?: return@launch
        user.buy(symbol, q.name, qty, q.price)
        _state.update { it.copy(message = "Paper trade: bought $qty ${Fmt.ticker(symbol)} at ${Fmt.price(q.price, q.currency)}") }
    }

    fun sell(qty: Int) = viewModelScope.launch {
        val q = _state.value.quote ?: return@launch
        val ok = user.sell(symbol, qty, q.price)
        _state.update {
            it.copy(message = if (ok) "Paper trade: sold $qty ${Fmt.ticker(symbol)} at ${Fmt.price(q.price, q.currency)}"
            else "You don't hold $qty shares of ${Fmt.ticker(symbol)} in your paper portfolio.")
        }
    }

    fun addAlert(target: Double, above: Boolean) = viewModelScope.launch {
        val q = _state.value.quote ?: return@launch
        user.addAlert(symbol, q.name, target, above)
        _state.update { it.copy(message = "Alert set: ${if (above) "above" else "below"} ${Fmt.price(target, q.currency)}") }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(container: AppContainer, symbol: String, onBack: () -> Unit) {
    val vm = rememberVm(key = symbol) { StockDetailViewModel(symbol, container.market, container.user, container.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val watched by vm.watched.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tradeSide by remember { mutableStateOf<String?>(null) }
    var showAlertDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(Fmt.ticker(symbol), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showAlertDialog = true }, enabled = state.quote != null) {
                        Icon(Icons.Filled.AddAlert, "Set price alert")
                    }
                    IconButton(onClick = { vm.toggleWatch() }) {
                        Icon(if (watched) Icons.Filled.Star else Icons.Filled.StarBorder,
                            if (watched) "Remove from watchlist" else "Add to watchlist",
                            tint = if (watched) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            val q = state.quote
            if (q != null && !q.isIndex) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val mc = LocalMarketColors.current
                        Button(onClick = { tradeSide = "SELL" }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = mc.loss)) { Text("Sell") }
                        Button(onClick = { tradeSide = "BUY" }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = mc.gain)) { Text("Buy") }
                    }
                }
            }
        },
    ) { padding ->
        val q = state.quote
        when {
            state.loading -> Loading(Modifier.padding(padding))
            state.error != null || q == null -> CenterMessage(state.error ?: "No data.", Modifier.padding(padding), "Try again", vm::load)
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { PriceHeader(q) }
                item { ChartSection(state, vm) }
                item { KeyStats(q, state.analysis) }
                val a = state.analysis
                if (a == null && state.analyzing) {
                    item {
                        Section("Analysis") {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("Running the analysis and backtest…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (a == null) {
                    item {
                        Section("Analysis") {
                            Text("Not enough price history to analyse this stock (needs at least ${AnalysisEngine.MIN_CANDLES} trading days).",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    item { VerdictSection(a) }
                    item { BestEntrySection(a, q) }
                    item { TradePlanSection(a, q) }
                    a.backtest?.let { bt -> item { BacktestSection(bt) } }
                    item { FactorsSection(a) }
                    item { LevelsSection(a, q) }
                    if (a.warnings.isNotEmpty()) item { WarningsSection(a.warnings) }
                }
                item {
                    Text(
                        "Ratings are generated automatically from price and volume data using fixed rules, and the backtest uses past data only. They are not investment advice and can be wrong. Buy and Sell here are paper trades with virtual money.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    val q = state.quote
    tradeSide?.let { side ->
        if (q != null) {
            QuantityDialog(
                title = if (side == "BUY") "Buy ${Fmt.ticker(symbol)}" else "Sell ${Fmt.ticker(symbol)}",
                priceText = Fmt.price(q.price, q.currency),
                initialQty = state.analysis?.plan?.quantity?.takeIf { it > 0 } ?: 1,
                confirmLabel = if (side == "BUY") "Buy" else "Sell",
                onConfirm = { qty -> if (side == "BUY") vm.buy(qty) else vm.sell(qty); tradeSide = null },
                onDismiss = { tradeSide = null },
            )
        }
    }
    if (showAlertDialog && q != null) {
        PriceAlertDialog(
            quote = q,
            suggested = state.analysis?.plan?.targets?.firstOrNull()?.price ?: q.price,
            onConfirm = { price, above -> vm.addAlert(price, above); showAlertDialog = false },
            onDismiss = { showAlertDialog = false },
        )
    }
}

@Composable
private fun PriceHeader(q: Quote) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(q.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.price(q.price, q.currency, q.isIndex), style = MaterialTheme.typography.displaySmall.merge(Num), fontWeight = FontWeight.SemiBold)
        ChangeText(q.change, q.changePct, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ChartSection(state: StockDetailViewModel.State, vm: StockDetailViewModel) {
    val mc = LocalMarketColors.current
    val primary = MaterialTheme.colorScheme.primary
    val plan = state.analysis?.plan
    val levels = if (state.showLevels && plan != null) buildList {
        add(LevelLine(plan.entry, "Entry", primary))
        add(LevelLine(plan.stopLoss, "SL", mc.loss))
        add(LevelLine(plan.targets[1].price, "T2", mc.gain))
    } else emptyList()

    Section(
        "Chart",
        trailing = {
            Row {
                IconButton(onClick = vm::toggleCandles) {
                    Icon(if (state.candleMode) Icons.AutoMirrored.Filled.ShowChart else Icons.Filled.BarChart,
                        if (state.candleMode) "Show line chart" else "Show candles")
                }
                if (plan != null) TextButton(onClick = vm::toggleLevels) { Text(if (state.showLevels) "Hide levels" else "Show levels") }
            }
        },
    ) {
        Box {
            PriceChart(state.chartCandles, state.candleMode, state.range.intraday, levels)
            if (state.chartLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChartRange.entries.forEach { r ->
                FilterChip(selected = state.range == r, onClick = { vm.setRange(r) }, label = { Text(r.label) })
            }
        }
    }
}

@Composable
private fun KeyStats(q: Quote, a: Analysis?) {
    Section("Key stats") {
        if (q.dayLow != null && q.dayHigh != null) KeyValue("Day range", "${Fmt.num(q.dayLow)} – ${Fmt.num(q.dayHigh)}")
        if (q.low52 != null && q.high52 != null) KeyValue("52-week range", "${Fmt.num(q.low52)} – ${Fmt.num(q.high52)}")
        KeyValue("Previous close", Fmt.num(q.previousClose))
        if (!q.isIndex && q.volume != null) KeyValue("Volume", Fmt.volume(q.volume))
        if (a != null) {
            KeyValue("Daily volatility (ATR 14)", "${Fmt.num(a.atr)}  (${String.format(Locale.US, "%.1f", a.atrPct)}%)")
            KeyValue("20 / 50 EMA", "${Fmt.num(a.ema20)} / ${Fmt.num(a.ema50)}")
            a.ema200?.let { KeyValue("200 EMA", Fmt.num(it)) }
            KeyValue("Weekly trend", a.weeklyTrend)
        }
    }
}

@Composable
private fun VerdictSection(a: Analysis) {
    Section("Rating", trailing = { SignalPill(a.signal) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(a.signal.label.uppercase(Locale.US), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = signalColor(a.signal), modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${a.confidence}%", style = MaterialTheme.typography.titleLarge.merge(Num), fontWeight = FontWeight.SemiBold)
                Text("${a.confidenceLabel} confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Score ${if (a.score > 0) "+" else ""}${a.score} / 100", style = MaterialTheme.typography.bodyMedium.merge(Num),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ScoreGauge(a.score)
        Spacer(Modifier.height(12.dp))
        Text(a.summary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        a.pillars.forEach { p -> PillarBar(p) }
    }
}

@Composable
private fun PillarBar(p: PillarScore) {
    val mc = LocalMarketColors.current
    val color = when {
        p.score >= 15 -> mc.gain
        p.score <= -15 -> mc.loss
        else -> mc.neutral
    }
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(p.pillar.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${if (p.score > 0) "+" else ""}${p.score}", style = MaterialTheme.typography.labelMedium.merge(Num), color = color)
            Text("  ·  weight ${(p.pillar.weight * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(3.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val mid = size.width / 2
            drawRect(track)
            val w = mid * (abs(p.score) / 100f)
            drawRect(color, topLeft = Offset(if (p.score >= 0) mid else mid - w, 0f), size = Size(w, size.height))
            drawRect(axis, topLeft = Offset(mid - 1f, 0f), size = Size(2f, size.height))
        }
    }
}

@Composable
private fun BestEntrySection(a: Analysis, q: Quote) {
    val e = a.entry
    val mc = LocalMarketColors.current
    val cur = q.currency
    val color = when (e.type) {
        EntryType.BUY_NOW, EntryType.BREAKOUT -> mc.gain
        EntryType.AVOID -> mc.loss
        else -> mc.neutral
    }
    Section("Best entry", trailing = {
        Surface(color = color.copy(alpha = 0.14f), contentColor = color, shape = CircleShape) {
            Text(e.type.label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
        }
    }) {
        Text(e.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
        Spacer(Modifier.height(6.dp))
        KeyValue(if (e.type == EntryType.AVOID) "Level to watch" else "Best entry price", Fmt.price(e.price, cur), emphasize = true)
        KeyValue("Entry zone", "${Fmt.price(e.zoneLow, cur)} – ${Fmt.price(e.zoneHigh, cur)}")
        KeyValue("vs current price", Fmt.pct((e.price - q.price) / q.price * 100))
        Spacer(Modifier.height(6.dp))
        Text(e.detail, style = MaterialTheme.typography.bodySmall)
        e.trigger?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        if (e.confluence.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Text("Why this zone: ${e.confluence.size} level${if (e.confluence.size > 1) "s" else ""} line up here",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.confluence.forEach { KeyValue(it.name, Fmt.price(it.price, cur)) }
        }
    }
}

@Composable
private fun BacktestSection(bt: Backtest) {
    val mc = LocalMarketColors.current
    fun p(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
    Section("How this rating did on this stock") {
        if (bt.trades == 0) {
            Text("The rules gave no Buy signal on this stock in the last ${bt.periodDays} trading days.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val edge = bt.winRate - bt.baseWinRate
            KeyValue("Past Buy signals", "${bt.trades} in ${bt.periodDays} trading days")
            KeyValue("Win rate", "${p(bt.winRate)}  (${bt.wins}/${bt.trades})", if (edge >= 0) mc.gain else mc.loss, emphasize = true)
            KeyValue("Random-entry win rate", p(bt.baseWinRate))
            KeyValue("Average trade", Fmt.pct(bt.avgReturnPct), changeColor(bt.avgReturnPct))
            bt.profitFactor?.let { KeyValue("Profit factor", String.format(Locale.US, "%.2f", it), if (it >= 1) mc.gain else mc.loss) }
            KeyValue("Average holding", String.format(Locale.US, "%.0f days", bt.avgHoldDays))
            KeyValue("Strategy return (compounded)", Fmt.pct(bt.totalReturnPct), changeColor(bt.totalReturnPct))
            KeyValue("Buy & hold, same period", Fmt.pct(bt.buyHoldPct), changeColor(bt.buyHoldPct))
        }
        Spacer(Modifier.height(6.dp))
        Text("Each Buy signal was entered at the next day's open with a 1.5 ATR stop and a 3 ATR target (max 30 days). " +
            "The random-entry win rate uses the same exits on every day, so the gap shows whether the rating adds an edge on this stock. " +
            "No costs or slippage; past results don't guarantee future ones.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TradePlanSection(a: Analysis, q: Quote) {
    val p = a.plan
    val mc = LocalMarketColors.current
    val cur = q.currency
    val isLong = p.direction == Direction.LONG
    Section(if (isLong) "Trade plan: buy setup" else "Trade plan: short setup") {
        if (a.signal == Signal.HOLD) {
            Text("Rating is Hold. Use this plan only after the trigger in Best entry is met.",
                style = MaterialTheme.typography.bodySmall, color = mc.neutral, modifier = Modifier.padding(bottom = 6.dp))
        }
        KeyValue(if (isLong) "Buy zone" else "Sell zone", "${Fmt.price(p.entryLow, cur)} – ${Fmt.price(p.entryHigh, cur)}")
        KeyValue("Suggested entry", Fmt.price(p.entry, cur), emphasize = true)
        KeyValue("Stop-loss", "${Fmt.price(p.stopLoss, cur)}  (${Fmt.pct(p.stopPct)})", mc.loss, emphasize = true)
        p.targets.forEach { t -> KeyValue(t.label, "${Fmt.price(t.price, cur)}  (${Fmt.pct(t.pct)})", mc.gain) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        KeyValue("Risk per share", Fmt.price(p.riskPerShare, cur))
        KeyValue("Reward : risk at Target 2", "${String.format(Locale.US, "%.1f", p.rewardRiskT2)} : 1")
        val lvl = p.nearestLevel
        val rr = p.rewardRiskToLevel
        if (lvl != null && rr != null) {
            KeyValue(if (isLong) "Reward : risk to resistance" else "Reward : risk to support",
                "${String.format(Locale.US, "%.1f", rr)} : 1  (${Fmt.num(lvl)})")
        } else {
            KeyValue(if (isLong) "Overhead resistance" else "Support below", if (isLong) "None, at fresh highs" else "None, at fresh lows")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        KeyValue("Suggested quantity", "${p.quantity} shares", emphasize = true)
        KeyValue("Capital needed", Fmt.price(p.capitalRequired, cur))
        KeyValue("Max loss if stop-loss hits", Fmt.price(p.maxLoss, cur), mc.loss)
        KeyValue("Profit at Target 2", Fmt.price(p.profitAtT2, cur), mc.gain)
        KeyValue("Holding period", p.holding)
        Spacer(Modifier.height(8.dp))
        Text(p.trailing, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(p.invalidation, style = MaterialTheme.typography.bodySmall, color = mc.loss)
        Spacer(Modifier.height(4.dp))
        Text("Quantity is sized so a stop-loss hit costs your chosen risk % of capital. Change it in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FactorsSection(a: Analysis) {
    Section("Indicator breakdown") {
        a.pillars.forEachIndexed { pi, p ->
            if (pi > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.pillar.label.uppercase(Locale.US), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${if (p.score > 0) "+" else ""}${p.score}", style = MaterialTheme.typography.labelMedium.merge(Num),
                    color = changeColor(p.score.toDouble()))
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            p.factors.forEachIndexed { i, f ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(verdictColor(f.verdict), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(f.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(f.value, style = MaterialTheme.typography.bodyMedium.merge(Num), color = verdictColor(f.verdict), fontWeight = FontWeight.Medium)
                    Text("  " + (if (f.points > 0) "+" else "") + f.points + "/" + f.max,
                        style = MaterialTheme.typography.labelMedium.merge(Num), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp))
            }
        }
    }
}

@Composable
private fun LevelsSection(a: Analysis, q: Quote) {
    val mc = LocalMarketColors.current
    Section("Support and resistance") {
        if (a.resistances.isEmpty() && a.supports.isEmpty()) {
            Text("No clear swing levels in the last 6 months.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        a.resistances.reversed().forEachIndexed { i, r -> KeyValue("Resistance ${a.resistances.size - i}", Fmt.price(r, q.currency, q.isIndex), mc.loss) }
        KeyValue("Current price", Fmt.price(q.price, q.currency, q.isIndex), emphasize = true)
        a.supports.forEachIndexed { i, s -> KeyValue("Support ${i + 1}", Fmt.price(s, q.currency, q.isIndex), mc.gain) }
    }
}

@Composable
private fun WarningsSection(warnings: List<String>) {
    Section("Before you trade") {
        warnings.forEach { w ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•  ", color = LocalMarketColors.current.neutral)
                Text(w, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun QuantityDialog(
    title: String,
    priceText: String,
    initialQty: Int,
    confirmLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialQty.toString()) }
    val qty = text.toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Paper trade at the current price, $priceText.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(7) },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(qty) }, enabled = qty > 0) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PriceAlertDialog(quote: Quote, suggested: Double, onConfirm: (Double, Boolean) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", suggested)) }
    val price = text.toDoubleOrNull()
    val above = price != null && price > quote.price
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price alert for ${Fmt.ticker(quote.symbol)}") },
        text = {
            Column {
                Text("Now ${Fmt.price(quote.price, quote.currency)}. You'll get a notification when the price crosses your target (checked about every 15 minutes).",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' }.take(12) },
                    label = { Text("Target price") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { if (price != null) Text(if (above) "Alert when price rises above this" else "Alert when price falls below this") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { price?.let { onConfirm(it, above) } }, enabled = price != null && price > 0) { Text("Set alert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
