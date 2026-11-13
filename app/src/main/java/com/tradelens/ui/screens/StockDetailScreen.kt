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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.lifecycle.repeatOnLifecycle
import com.tradelens.AppContainer
import com.tradelens.analysis.*
import com.tradelens.data.model.Candle
import com.tradelens.data.model.ChartRange
import com.tradelens.marketdata.CorporateAction
import com.tradelens.marketdata.CorporateActionType
import com.tradelens.marketdata.DataStatus
import com.tradelens.marketdata.Resampler
import com.tradelens.marketdata.Timeframe
import com.tradelens.marketdata.DataMode
import com.tradelens.marketdata.Freshness
import com.tradelens.marketdata.SessionState
import com.tradelens.patterns.PatternCache
import com.tradelens.ta.TechnicalCache
import com.tradelens.trade.*
import kotlinx.coroutines.delay
import com.tradelens.data.model.Quote
import com.tradelens.data.repository.*
import com.tradelens.ui.components.*
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import kotlinx.coroutines.flow.*
import com.tradelens.recommend.toRecord
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
    private val technicalCache: TechnicalCache,
    private val patternCache: PatternCache,
    private val stabilizer: SignalStabilizer,
    private val flowSource: com.tradelens.flow.FlowDataSource,
    private val fundamentalSource: com.tradelens.fundamentals.FundamentalDataSource,
    private val newsSource: com.tradelens.research.NewsSource,
    private val exchangeSource: com.tradelens.research.ExchangeAnnouncementSource,
    private val recommendations: com.tradelens.recommend.RecommendationHistory,
) : ViewModel() {

    companion object {
        /** Live quote polling interval while the market is open (a real-time provider is polled faster). */
        fun livePollMs(realtime: Boolean) = if (realtime) 2_000L else 5_000L
        /** How often the daily bars behind the indicators are refreshed during the session. */
        const val SERIES_REFRESH_MS = 5 * 60_000L
    }

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val quote: Quote? = null,
        val daily: List<Candle> = emptyList(),      // 2 years of daily candles, used for analysis
        val benchmark: List<Candle>? = null,        // NIFTY 50, for relative strength and market backdrop
        val analyzing: Boolean = false,
        val analysis: Analysis? = null,
        val dataStatus: DataStatus? = null,         // status of the daily series behind the analysis
        val actions: List<CorporateAction> = emptyList(),
        val range: ChartRange = ChartRange.M6,
        val interval: Timeframe = ChartRange.M6.default,
        val chartCandles: List<Candle> = emptyList(),
        val chartStatus: DataStatus? = null,
        val chartError: String? = null,
        val chartLoading: Boolean = false,
        val refreshing: Boolean = false,
        val candleMode: Boolean = true,
        val showLevels: Boolean = true,
        val message: String? = null,
        /** Phase 5: smart entry/exit recommendation (null for indices or too little data). */
        val trade: Recommendation? = null,
        /** Paper-portfolio holding of this stock, which switches the engine to HOLD / PARTIAL / EXIT advice. */
        val holding: Position? = null,
        /** Direction of the last live price change: +1 up, −1 down, 0 unchanged (drives the price flash). */
        val tick: Int = 0,
        /** Wall-clock time of the last live quote. */
        val liveAt: Long? = null,
        /** Phase 6: volume analytics and institutional-data status (each item unavailable when its source lacks it). */
        val flow: com.tradelens.flow.FlowReport? = null,
        /** Phase 7: fundamentals (statements, ratios, governance, quality score), or the reason it is unavailable. */
        val fundamentals: com.tradelens.fundamentals.FundamentalReport? = null,
        /** Phase 8: fair value, DCF, zones and interpretation, or the reason none is available. */
        val valuation: com.tradelens.valuation.ValuationOutcome? = null,
        /** Phase 9: news, announcements and the research report. Refreshed on demand. */
        val research: com.tradelens.research.ResearchReport? = null,
        val researchLoading: Boolean = false,
        /** Phase 10: one decision per horizon, the horizon the user is viewing, and the saved history for this stock. */
        val decisions: List<com.tradelens.recommend.HorizonDecision>? = null,
        val horizon: com.tradelens.recommend.InvestmentHorizon = com.tradelens.recommend.InvestmentHorizon.SHORT,
        val savedNote: String? = null,
        val history: List<com.tradelens.recommend.RecommendationRecord> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    val watched = user.isWatched(symbol).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val userSettings = settings.settings

    init {
        load()
        // Re-size the trade plan when capital, risk % or max position changes (sizing isn't subject to signal stability).
        viewModelScope.launch { settings.settings.drop(1).collect { stabilizer.reset(symbol); reanalyze() } }
        // Holding this stock (paper portfolio) turns entry advice into HOLD / PARTIAL PROFIT BOOKING / EXIT advice.
        viewModelScope.launch {
            user.holdings.map { l -> l.firstOrNull { it.symbol == symbol }?.let { Position(it.quantity, it.avgPrice) } }
                .distinctUntilChanged().collect { h -> _state.update { it.copy(holding = h) }; evaluateTrade() }
        }
    }

    private var lastSeriesRefresh = System.currentTimeMillis()

    /**
     * Live prices while the screen is visible (call from a lifecycle-aware effect; it suspends forever).
     * During the session the quote is re-fetched every [livePollMs]; each change updates the price and re-runs the
     * entry/exit engine on the new price (cheap: indicator and pattern reports are cached by closed bars, and the
     * stabilizer stops tiny moves from flipping the recommendation). The daily bars refresh every 5 minutes.
     * Outside market hours it only checks once a minute.
     */
    suspend fun liveQuotes() {
        while (true) {
            val now = System.currentTimeMillis()
            val open = market.calendar.sessionState(now) == SessionState.OPEN || market.source.mode == DataMode.DEMO
            if (open && _state.value.quote != null) {
                runCatchingCancellable { market.quote(symbol, forceRefresh = true) }.onSuccess(::onLiveQuote)
                if (now - lastSeriesRefresh >= SERIES_REFRESH_MS) { lastSeriesRefresh = now; load() }
            }
            delay(if (open) livePollMs(market.source.realtime) else 60_000L)
        }
    }

    private fun onLiveQuote(q: Quote) {
        val old = _state.value.quote
        val changed = old == null || q.price != old.price || q.asOf != old.asOf
        _state.update { it.copy(quote = q, liveAt = System.currentTimeMillis(), tick = if (old == null) 0 else q.price.compareTo(old.price).coerceIn(-1, 1)) }
        if (changed) evaluateTrade()
    }

    private var tradeJob: Job? = null

    /** Phase 5 engine on the latest closed daily bars + live price, through the signal stabilizer. */
    private fun evaluateTrade() {
        val s = _state.value
        val q = s.quote ?: return
        if (q.isIndex || s.daily.size < 30) { _state.update { it.copy(trade = null) }; return }
        val cfg = settings.settings.value
        tradeJob?.cancel()
        tradeJob = viewModelScope.launch {
            val rec = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val tech = technicalCache.get(market.source.id, symbol, Timeframe.D1, s.daily, now, q.isIndex)
                val pat = patternCache.get(market.source.id, symbol, Timeframe.D1, s.daily, now)
                // The worse of the price's and the daily series' freshness gates new signals.
                val status = listOfNotNull(q.status, s.dataStatus).firstOrNull { it.freshness == Freshness.STALE || it.freshness == Freshness.OFFLINE } ?: s.dataStatus
                val r = SmartEntryEngine.evaluate(symbol, tech, pat, q.price, status, s.actions, s.holding,
                    RiskSettings(cfg.capital, cfg.riskPercent, cfg.maxPositionPercent), now)
                stabilizer.publish(r, now)
            }
            _state.update { it.copy(trade = rec) }
            decide()
        }
    }

    val source get() = market.source

    /** [force] = pull-to-refresh / Retry: bypass the cache and go to the provider. */
    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { if (it.quote == null) it.copy(loading = true, error = null) else it.copy(refreshing = true) }
            val bench = async { market.benchmark() }
            runCatchingCancellable { market.analysisHistory(symbol, force) }
                .onSuccess { data ->
                    val actions = runCatchingCancellable { market.candles(symbol, Timeframe.D1, System.currentTimeMillis() - MarketRepository.ANALYSIS_LOOKBACK_MS).actions }.getOrDefault(emptyList())
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = null, quote = data.quote, daily = data.candles,
                            dataStatus = data.status, actions = actions, benchmark = bench.await())
                    }
                    reanalyze()
                    loadChart(force)
                }
                .onFailure { e -> _state.update { it.copy(loading = false, refreshing = false, error = if (it.quote == null) e.friendlyMessage() else null,
                    message = if (it.quote != null) e.friendlyMessage() else null) } }
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
            evaluateTrade()
        }
        flowJob?.cancel()
        flowJob = viewModelScope.launch {
            val f = com.tradelens.flow.FlowService.report(symbol, s.daily, flowSource, market.source.name,
                s.dataStatus?.freshness ?: com.tradelens.marketdata.Freshness.MARKET_CLOSED, s.dataStatus?.fetchedAt, System.currentTimeMillis())
            _state.update { it.copy(flow = f) }
        }
        fundamentalJob?.cancel()
        fundamentalJob = viewModelScope.launch {
            val f = com.tradelens.fundamentals.FundamentalService.report(symbol, fundamentalSource, q.price,
                s.dataStatus?.freshness ?: com.tradelens.marketdata.Freshness.MARKET_CLOSED, s.dataStatus?.fetchedAt, System.currentTimeMillis())
            // Valuation runs on the same price and closed daily bars; the margin of safety comes from settings.
            val vcfg = com.tradelens.valuation.ValuationConfig(marginOfSafety = (cfg.marginOfSafetyPercent / 100).coerceIn(0.0, 0.6))
            val v = com.tradelens.valuation.ValuationService.report(symbol, fundamentalSource, q.price, s.daily, vcfg,
                System.currentTimeMillis(), f.analysis?.quality?.score)
            _state.update { it.copy(fundamentals = f, valuation = v) }
            refreshResearch()
        }
    }

    private var flowJob: Job? = null
    private var fundamentalJob: Job? = null
    private var researchJob: Job? = null
    private var decisionJob: Job? = null

    /**
     * Phase 10: combines every engine's latest output into one decision per horizon. Missing engines stay unavailable.
     * Runs after the trade plan and the research are updated, and again whenever either changes.
     */
    fun decide() {
        val s = _state.value
        val q = s.quote ?: return
        decisionJob?.cancel()
        decisionJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val (tech, pat, bench) = withContext(Dispatchers.Default) {
                Triple(
                    if (s.daily.size >= 30) technicalCache.get(market.source.id, symbol, Timeframe.D1, s.daily, now, q.isIndex) else null,
                    if (s.daily.size >= 30) patternCache.get(market.source.id, symbol, Timeframe.D1, s.daily, now) else null,
                    s.benchmark?.takeIf { it.size >= 30 }?.let { technicalCache.get(market.source.id, "^NSEI", Timeframe.D1, it, now, true) },
                )
            }
            val inputs = com.tradelens.recommend.DecisionInputs(
                symbol = symbol, price = q.price, now = now, technical = tech, benchmark = bench, patterns = pat,
                trade = s.trade, flow = s.flow, fundamentals = s.fundamentals, valuation = s.valuation?.report, research = s.research,
                holding = s.holding != null, inputAsOf = s.trade?.dataAsOf ?: s.daily.lastOrNull()?.time,
            )
            // No calibration table is shipped yet, so confidence is reported as uncalibrated.
            val decisions = com.tradelens.recommend.DecisionEngine.decideAll(inputs, com.tradelens.recommend.ModelConfig.DEFAULT, calibration = null)
            _state.update { it.copy(decisions = decisions, history = recommendations.forSymbol(symbol)) }
        }
    }

    fun setHorizon(h: com.tradelens.recommend.InvestmentHorizon) = _state.update { it.copy(horizon = h, savedNote = null) }

    /** Saves the current decision for the viewed horizon. Saved records are never overwritten. */
    fun saveDecision() {
        val s = _state.value
        val d = s.decisions?.firstOrNull { it.horizon == s.horizon } ?: return
        val rec = d.toRecord(symbol, s.quote?.price)
        val saved = recommendations.append(rec)
        _state.update { it.copy(savedNote = if (saved) "Saved to history with model ${rec.modelVersion}." else "This exact recommendation is already saved.",
            history = recommendations.forSymbol(symbol)) }
    }

    fun loadHistory() = _state.update { it.copy(history = recommendations.forSymbol(symbol)) }

    /**
     * Fetches the latest company news and builds the research report from it, the fundamentals and the valuation.
     * Called on each page load and from the Refresh button. Only retrieved items and computed figures are used.
     */
    fun refreshResearch() {
        val s = _state.value
        val name = s.quote?.name ?: symbol
        researchJob?.cancel()
        _state.update { it.copy(researchLoading = true) }
        researchJob = viewModelScope.launch {
            val news = newsSource.search(name)
            val exchange = exchangeSource.announcements(symbol)
            val report = com.tradelens.research.ResearchService.build(symbol, name, news, exchange,
                s.fundamentals, _state.value.valuation?.report, System.currentTimeMillis())
            _state.update { it.copy(research = report, researchLoading = false) }
            decide()
        }
    }

    fun setRange(range: ChartRange) {
        val cur = _state.value.interval
        _state.update { it.copy(range = range, interval = if (cur in range.intervals && isAvailable(cur)) cur else range.default) }
        loadChart()
    }

    fun setInterval(tf: Timeframe) {
        _state.update { it.copy(interval = tf) }
        loadChart()
    }

    /** Whether the current provider can serve [tf], natively or by building it from smaller bars. */
    fun isAvailable(tf: Timeframe) = Resampler.sourceFor(tf, market.source.capabilities.timeframes.keys) != null

    private var chartJob: Job? = null

    private fun loadChart(force: Boolean = false) {
        val range = _state.value.range
        val tf = _state.value.interval
        chartJob?.cancel()
        _state.update { it.copy(chartLoading = true, chartError = null) }
        chartJob = viewModelScope.launch {
            runCatchingCancellable { market.chart(symbol, range, tf, force) }
                .onSuccess { r -> _state.update { it.copy(chartCandles = r.candles, chartStatus = r.status, chartLoading = false) } }
                .onFailure { e -> _state.update { it.copy(chartLoading = false, chartCandles = emptyList(), chartStatus = null, chartError = e.friendlyMessage()) } }
        }
    }

    fun retryChart() = loadChart(force = true)

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
fun StockDetailScreen(container: AppContainer, symbol: String, onBack: () -> Unit, onOpenTechnical: (String) -> Unit = {}) {
    val vm = rememberVm(key = symbol) {
        StockDetailViewModel(symbol, container.market, container.user, container.settings, container.technicalCache, container.patternCache, container.signalStabilizer, container.flowSource, container.fundamentalSource, container.newsSource, container.exchangeSource, container.recommendations)
    }
    // Live prices only while this screen is visible: polling stops in the background and resumes on return.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { vm.liveQuotes() } }
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
                    IconButton(onClick = { vm.load(force = true) }, enabled = !state.refreshing) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Refresh, "Refresh data")
                    }
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
                item { PriceHeader(q, state.tick, state.liveAt, onRetry = { vm.load(force = true) }) }
                // Phase 10: the combined decision sits directly under the price, one view per horizon.
                item { DecisionDashboard(state.decisions, state.horizon, state.savedNote, state.history, vm::setHorizon, vm::saveDecision) }
                item { ChartSection(state, vm) }
                item {
                    FilledTonalButton(onClick = { onOpenTechnical(symbol) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Advanced technical analysis: 30+ indicators, any timeframe")
                    }
                }
                item { KeyStats(q, state.analysis) }
                state.flow?.let { f -> item { FlowSection(f, state.daily, q) } }
                state.fundamentals?.let { f -> item { FundamentalSection(f) } }
                state.valuation?.let { v -> item { ValuationSection(v) } }
                item { ResearchSection(state.research, state.researchLoading, vm::refreshResearch) }
                // Phase 5: smart entry / exit plan, then the long-term view kept deliberately separate from it.
                state.trade?.let { t ->
                    item { SmartEntrySection(t, q) }
                    item { LongTermSection(t.longTerm) }
                }
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
                    a.backtest?.let { bt -> item { BacktestSection(bt) } }
                    item { FactorsSection(a) }
                    item { LevelsSection(a, q) }
                    if (a.warnings.isNotEmpty()) item { WarningsSection(a.warnings) }
                }
                item {
                    DataDetailsSection(vm.source, state.dataStatus, state.actions.takeLast(6).reversed().map { a ->
                        val d = Fmt.date(a.exDate)
                        when (a.type) {
                            CorporateActionType.DIVIDEND -> "Dividend ${a.amount?.let { Fmt.price(it, q.currency) } ?: ""} · ex-date $d"
                            else -> "${a.type.name.lowercase().replaceFirstChar { it.uppercase() }} ${a.ratio?.let { r -> if (r >= 1) "${Fmt.num(r, 0)}:1" else "1:${Fmt.num(1 / r, 0)}" } ?: ""} · ex-date $d"
                        }
                    })
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
                initialQty = state.trade?.primaryPlan?.risk?.quantity?.takeIf { it > 0 } ?: 1,
                confirmLabel = if (side == "BUY") "Buy" else "Sell",
                onConfirm = { qty -> if (side == "BUY") vm.buy(qty) else vm.sell(qty); tradeSide = null },
                onDismiss = { tradeSide = null },
            )
        }
    }
    if (showAlertDialog && q != null) {
        PriceAlertDialog(
            quote = q,
            suggested = state.trade?.primaryPlan?.exit?.targets?.firstOrNull()?.price ?: q.price,
            onConfirm = { price, above -> vm.addAlert(price, above); showAlertDialog = false },
            onDismiss = { showAlertDialog = false },
        )
    }
}

@Composable
private fun PriceHeader(q: Quote, tick: Int, liveAt: Long?, onRetry: () -> Unit) {
    val mc = LocalMarketColors.current
    val base = MaterialTheme.colorScheme.onSurface
    // Flash green/red on each live price change, then fade back.
    var flash by remember { mutableIntStateOf(0) }
    LaunchedEffect(liveAt) { if (liveAt != null && tick != 0) { flash = tick; delay(900); flash = 0 } }
    val color by androidx.compose.animation.animateColorAsState(when (flash) { 1 -> mc.gain; -1 -> mc.loss; else -> base }, label = "price-flash")
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(q.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.price(q.price, q.currency, q.isIndex), style = MaterialTheme.typography.displaySmall.merge(Num), fontWeight = FontWeight.SemiBold, color = color)
        ChangeText(q.change, q.changePct, style = MaterialTheme.typography.titleMedium)
        if (liveAt != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(7.dp).background(mc.gain, CircleShape))
                Text("  Auto-updating · last check ${Fmt.dateTime(liveAt).substringAfterLast(' ')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Always say how fresh the price is and where it came from; stale or demo data is never shown as live.
        DataStatusBar(q.status, Modifier.padding(top = 4.dp), onRetry = onRetry)
    }
}

@Composable
private fun ChartSection(state: StockDetailViewModel.State, vm: StockDetailViewModel) {
    val mc = LocalMarketColors.current
    val primary = MaterialTheme.colorScheme.primary
    val plan = state.trade?.primaryPlan?.takeIf { it.exit != null }
    val levels = if (state.showLevels && plan != null) buildList {
        val x = plan.exit!!
        add(LevelLine(plan.entry, "Entry", primary))
        add(LevelLine(x.stopLoss, "SL", mc.loss))
        x.targets.take(2).forEach { add(LevelLine(it.price, it.label, mc.gain)) }
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
            val err = state.chartError
            if (err != null && state.chartCandles.isEmpty()) {
                CenterMessage(err, Modifier.height(260.dp), "Retry", vm::retryChart)
            } else {
                PriceChart(state.chartCandles, state.candleMode, state.interval.intraday, levels)
            }
            if (state.chartLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        DataStatusBar(state.chartStatus, Modifier.padding(top = 6.dp), onRetry = vm::retryChart)
        state.chartStatus?.availability?.filter { it.contains("bars") }?.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChartRange.entries.forEach { r ->
                FilterChip(selected = state.range == r, onClick = { vm.setRange(r) }, label = { Text(r.label) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Interval", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.range.intervals.forEach { tf ->
                val ok = vm.isAvailable(tf)
                FilterChip(selected = state.interval == tf, enabled = ok, onClick = { vm.setInterval(tf) },
                    label = { Text(if (ok) tf.label else "${tf.label} n/a") })
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
