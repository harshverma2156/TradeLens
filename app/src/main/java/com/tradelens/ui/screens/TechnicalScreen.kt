package com.tradelens.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tradelens.AppContainer
import com.tradelens.data.repository.MarketRepository
import com.tradelens.data.repository.friendlyMessage
import com.tradelens.data.repository.runCatchingCancellable
import com.tradelens.marketdata.DataStatus
import com.tradelens.marketdata.Resampler
import com.tradelens.marketdata.SessionState
import com.tradelens.marketdata.Timeframe
import com.tradelens.patterns.*
import com.tradelens.ta.*
import com.tradelens.ui.components.*
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PatternFilter(val label: String) {
    ACTIVE("Active & recent"), FORMING("Forming"), CONFIRMED("Confirmed"), FAILED("Failed"), INVALIDATED("Invalidated"), ALL("All history");

    fun accepts(p: DetectedPattern, bars: Int): Boolean = when (this) {
        // Still tracked, or resolved within the last 30 bars; candlesticks only from the last 10 bars.
        ACTIVE -> if (p.type.kind == PatternKind.CANDLESTICK) p.endIndex >= bars - 10 else !p.closed || (p.events.last().knownIndex >= bars - 30)
        FORMING -> p.status == PatternStatus.FORMING
        CONFIRMED -> p.status == PatternStatus.CONFIRMED
        FAILED -> p.status == PatternStatus.FAILED
        INVALIDATED -> p.status == PatternStatus.INVALIDATED
        ALL -> true
    }
}

enum class OscKind(val label: String) { RSI("RSI"), MACD("MACD"), STOCH("Stoch"), STOCH_RSI("Stoch RSI"), CCI("CCI"), WILLIAMS("%R"), ADX("ADX/DMI"), MFI("MFI"), OBV("OBV"), AD("A/D") }

class TechnicalViewModel(
    private val symbol: String,
    private val market: MarketRepository,
    private val cache: TechnicalCache,
    private val patternCache: PatternCache,
) : ViewModel() {

    data class State(
        val timeframe: Timeframe = Timeframe.D1,
        val loading: Boolean = true,
        val error: String? = null,
        val report: TechnicalReport? = null,
        val patterns: PatternReport? = null,
        val status: DataStatus? = null,
        val overlays: Set<Overlay> = setOf(Overlay.EMA20, Overlay.EMA50, Overlay.EMA200, Overlay.SUPERTREND, Overlay.PATTERNS),
        val oscillator: OscKind = OscKind.RSI,
        val category: IndicatorCategory? = null,
        val expanded: Set<String> = emptySet(),
        val patternFilter: PatternFilter = PatternFilter.ACTIVE,
        val patternKinds: Set<PatternKind> = PatternKind.entries.toSet(),
        /** Highlighted on the chart and expanded in the list. */
        val selectedPattern: String? = null,
    ) {
        val highlighted get() = patterns?.patterns?.firstOrNull { it.id == selectedPattern }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    val source get() = market.source
    private var job: Job? = null

    init {
        load()
        // Update when new candles arrive: re-poll while the market is open. The repository's cache TTLs
        // decide whether this hits the network; the indicator cache recomputes only if a bar actually changed.
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (market.calendar.sessionState(System.currentTimeMillis()) == SessionState.OPEN) load(quiet = true)
            }
        }
    }

    /** Enough history for the slowest indicator (EMA 200, Ichimoku 52 + 26) on each timeframe, within provider limits. */
    private fun lookbackDays(tf: Timeframe) = when (tf) {
        Timeframe.M1 -> 6; Timeframe.M5 -> 20; Timeframe.M15 -> 45; Timeframe.M30 -> 59
        Timeframe.H1 -> 250; Timeframe.D1 -> 3 * 365; Timeframe.W1 -> 10 * 365; Timeframe.MN1 -> 25 * 365
    }

    fun isAvailable(tf: Timeframe) = Resampler.sourceFor(tf, market.source.capabilities.timeframes.keys) != null

    fun load(force: Boolean = false, quiet: Boolean = false) {
        val tf = _state.value.timeframe
        job?.cancel()
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            runCatchingCancellable {
                val now = System.currentTimeMillis()
                val r = market.candles(symbol, tf, now - lookbackDays(tf) * MarketRepository.DAY, force)
                val (report, patterns) = withContext(Dispatchers.Default) {
                    cache.get(market.source.id, symbol, tf, r.candles, now) to patternCache.get(market.source.id, symbol, tf, r.candles, now)
                }
                Triple(report, patterns, r.status)
            }.onSuccess { (rep, pat, st) -> _state.update { if (it.timeframe == tf) it.copy(loading = false, report = rep, patterns = pat, status = st, error = null) else it } }
                .onFailure { e -> _state.update { if (it.timeframe == tf) it.copy(loading = false, error = e.friendlyMessage()) else it } }
        }
    }

    fun setTimeframe(tf: Timeframe) { _state.update { it.copy(timeframe = tf, report = null, patterns = null, selectedPattern = null) }; load() }
    fun setPatternFilter(f: PatternFilter) = _state.update { it.copy(patternFilter = f) }
    fun togglePatternKind(k: PatternKind) = _state.update { it.copy(patternKinds = if (k in it.patternKinds) it.patternKinds - k else it.patternKinds + k) }
    fun selectPattern(id: String) = _state.update { it.copy(selectedPattern = if (it.selectedPattern == id) null else id) }
    fun toggleOverlay(o: Overlay) = _state.update { it.copy(overlays = if (o in it.overlays) it.overlays - o else it.overlays + o) }
    fun setOscillator(o: OscKind) = _state.update { it.copy(oscillator = o) }
    fun setCategory(c: IndicatorCategory?) = _state.update { it.copy(category = c) }
    fun toggleExpanded(id: String) = _state.update { it.copy(expanded = if (id in it.expanded) it.expanded - id else it.expanded + id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechnicalScreen(container: AppContainer, symbol: String, onBack: () -> Unit) {
    val vm = rememberVm(key = "ta-$symbol") { TechnicalViewModel(symbol, container.market, container.technicalCache, container.patternCache) }
    val s by vm.state.collectAsStateWithLifecycle()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Column { Text(Fmt.ticker(symbol), fontWeight = FontWeight.SemiBold); Text("Technical analysis", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = { IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Timeframe.entries.forEach { tf ->
                        val ok = vm.isAvailable(tf)
                        FilterChip(selected = s.timeframe == tf, enabled = ok, onClick = { vm.setTimeframe(tf) }, label = { Text(if (ok) tf.label else "${tf.label} n/a") })
                    }
                }
                DataStatusBar(s.status, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onRetry = { vm.load(force = true) })
            }
            val rep = s.report
            when {
                s.loading && rep == null -> item { Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                s.error != null && rep == null -> item { CenterMessage(s.error!!, Modifier.height(240.dp), "Retry") { vm.load(force = true) } }
                rep != null -> {
                    item { SummaryCard(rep) }
                    item { ChartCard(rep, s, vm) }
                    item { OscillatorCard(rep, s, vm) }
                    s.patterns?.let { pr ->
                        item { PatternsHeader(pr, s, vm) }
                        val shown = pr.patterns.filter { it.type.kind in s.patternKinds && s.patternFilter.accepts(it, pr.candleCount) }
                        if (shown.isEmpty()) item {
                            Text("No ${s.patternFilter.label.lowercase()} patterns on ${pr.timeframe.label}.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        }
                        items(shown, key = { "pat-" + it.id }) { p ->
                            PatternCard(p, expanded = p.id == s.selectedPattern) {
                                val selecting = s.selectedPattern != p.id
                                vm.selectPattern(p.id)
                                if (selecting) scope.launch { listState.animateScrollToItem(2) }   // bring the chart (item 2) into view
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = s.category == null, onClick = { vm.setCategory(null) }, label = { Text("All") })
                            IndicatorCategory.entries.forEach { c -> FilterChip(selected = s.category == c, onClick = { vm.setCategory(c) }, label = { Text(c.label) }) }
                        }
                    }
                    items(rep.readings.filter { s.category == null || it.category == s.category }, key = { it.id }) { r ->
                        IndicatorCard(r, expanded = r.id in s.expanded) { vm.toggleExpanded(r.id) }
                    }
                    item {
                        Text(
                            "Computed ${Fmt.dateTime(rep.computedAt)} IST on ${rep.candles.size} closed ${rep.timeframe.label} bars (last ${rep.lastClosedBarTime?.let { Fmt.dateTime(it) } ?: "—"}). " +
                                "Every indicator uses only bars up to the last closed one. Technical signals alone are not investment advice.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun biasColor(b: Bias): Color {
    val mc = LocalMarketColors.current
    return when (b) { Bias.BULLISH -> mc.gain; Bias.BEARISH -> mc.loss; Bias.NEUTRAL -> mc.neutral }
}

@Composable
internal fun BiasPill(b: Bias, text: String = b.name.lowercase().replaceFirstChar { it.uppercase() }) {
    val c = biasColor(b)
    Surface(color = c.copy(alpha = 0.14f), contentColor = c, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StrengthBar(score: Int, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((score.coerceIn(0, 100)) / 100f).background(color))
    }
}

@Composable
private fun SummaryCard(rep: TechnicalReport) {
    val sm = rep.summary
    Section("Technical summary · ${rep.timeframe.label}", trailing = { BiasPill(sm.bias, sm.label) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(sm.label.uppercase(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = biasColor(sm.bias), modifier = Modifier.weight(1f))
            Text("${if (sm.score > 0) "+" else ""}${sm.score}", style = MaterialTheme.typography.titleLarge.merge(Num), fontWeight = FontWeight.SemiBold)
        }
        Text("${sm.bullishGroups} bullish · ${sm.bearishGroups} bearish · ${sm.votingGroups - sm.bullishGroups - sm.bearishGroups} neutral families",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ScoreGauge(sm.score)
        Spacer(Modifier.height(10.dp))
        rep.groups.forEach { g ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.group.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(if (g.ready == 0) "no data" else "${if (g.score > 0) "+" else ""}${g.score}", style = MaterialTheme.typography.labelMedium.merge(Num), color = biasColor(g.bias))
                    Text("  ·  ${(g.group.weight * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StrengthBar(kotlin.math.abs(g.score), biasColor(g.bias), Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(sm.explanation, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChartCard(rep: TechnicalReport, s: TechnicalViewModel.State, vm: TechnicalViewModel) {
    Section("Chart & overlays") {
        val bars = rep.candles + listOfNotNull(rep.formingBar)
        // Pattern indices refer to the same closed bars as the indicators (both drop the forming bar the same way).
        val pr = s.patterns?.takeIf { it.candleCount == rep.candles.size }
        val onChart = pr?.patterns.orEmpty().filter { it.type.kind in s.patternKinds && (!it.closed || it.events.last().knownIndex >= pr!!.candleCount - 30) }
            .filter { it.type.kind != PatternKind.CANDLESTICK || it.endIndex >= pr!!.candleCount - 10 }
        TechnicalPriceChart(bars, rep.overlays, s.overlays, patterns = onChart, highlight = s.highlighted?.takeIf { pr != null })
        s.highlighted?.let { h ->
            Text("Showing ${h.name} (${h.status.label.lowercase()}): breakout, stop and targets drawn. Tap the pattern again to clear.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }
        rep.formingBar?.let {
            Text("The last bar (opened ${Fmt.dateTime(it.time)}) is still forming: drawn faded and excluded from every signal.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Overlay.entries.forEach { o ->
                val unavailable = (o == Overlay.VWAP && !rep.timeframe.intraday) || (o == Overlay.PROFILE && rep.overlays.profile == null)
                FilterChip(selected = o in s.overlays, enabled = !unavailable, onClick = { vm.toggleOverlay(o) },
                    label = { Text(o.label) }, leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(o.color)) })
            }
        }
    }
}

@Composable
private fun OscillatorCard(rep: TechnicalReport, s: TechnicalViewModel.State, vm: TechnicalViewModel) {
    val o = rep.oscillators
    val n = rep.candles.size
    Section("Oscillators") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OscKind.entries.forEach { k -> FilterChip(selected = s.oscillator == k, onClick = { vm.setOscillator(k) }, label = { Text(k.label) }) }
        }
        Spacer(Modifier.height(6.dp))
        val blue = Color(0xFF2E86DE); val orange = Color(0xFFE67E22); val mc = LocalMarketColors.current
        when (s.oscillator) {
            OscKind.RSI -> OscillatorChart(listOf(OscLine("RSI", o.rsi, blue)), n, refLines = listOf(30.0, 50.0, 70.0), range = 0.0..100.0)
            OscKind.MACD -> OscillatorChart(listOf(OscLine("Hist", o.macd.hist, mc.gain, histogram = true), OscLine("MACD", o.macd.line, blue), OscLine("Signal", o.macd.signal, orange)), n, refLines = listOf(0.0))
            OscKind.STOCH -> OscillatorChart(listOf(OscLine("%K", o.stoch.k, blue), OscLine("%D", o.stoch.d, orange)), n, refLines = listOf(20.0, 80.0), range = 0.0..100.0)
            OscKind.STOCH_RSI -> OscillatorChart(listOf(OscLine("%K", o.stochRsi.k, blue), OscLine("%D", o.stochRsi.d, orange)), n, refLines = listOf(20.0, 80.0), range = 0.0..100.0)
            OscKind.CCI -> OscillatorChart(listOf(OscLine("CCI", o.cci, blue)), n, refLines = listOf(-100.0, 0.0, 100.0))
            OscKind.WILLIAMS -> OscillatorChart(listOf(OscLine("%R", o.williamsR, blue)), n, refLines = listOf(-80.0, -20.0), range = -100.0..0.0)
            OscKind.ADX -> OscillatorChart(listOf(OscLine("ADX", o.dmi.adx, MaterialTheme.colorScheme.onSurface), OscLine("+DI", o.dmi.plusDi, mc.gain), OscLine("−DI", o.dmi.minusDi, mc.loss)), n, refLines = listOf(20.0, 25.0))
            OscKind.MFI -> OscillatorChart(listOf(OscLine("MFI", o.mfi, blue)), n, refLines = listOf(20.0, 80.0), range = 0.0..100.0)
            OscKind.OBV -> OscillatorChart(listOf(OscLine("OBV", o.obv, blue)), n)
            OscKind.AD -> OscillatorChart(listOf(OscLine("A/D", o.adLine, blue)), n)
        }
    }
}

@Composable
private fun IndicatorCard(r: IndicatorReading, expanded: Boolean, onToggle: () -> Unit) {
    val c = biasColor(r.bias)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onToggle).padding(14.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${r.category.label} · ${r.timeframe.label}${if (r.group == SignalGroup.CONTEXT) " · context only" else ""}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(r.display, style = MaterialTheme.typography.bodyMedium.merge(Num), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        if (r.ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BiasPill(r.bias)
                Spacer(Modifier.width(10.dp))
                StrengthBar(r.strength, c, Modifier.weight(1f))
                Text("  ${r.strength}", style = MaterialTheme.typography.labelSmall.merge(Num), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(r.explanation, style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Text("How it's calculated", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(r.formula, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            KeyValue("Parameters", r.params)
            KeyValue("Warm-up", "≈ ${r.warmupBars} bars")
            r.components.filter { it.second != null }.forEach { (k, v) -> KeyValue(k, Fmt.num(v!!)) }
            KeyValue("Bar used", r.barTime?.let { "${Fmt.dateTime(it)} IST" } ?: "—")
            KeyValue("Calculated", "${Fmt.dateTime(r.computedAt)} IST")
        } else {
            Text("Tap for the maths", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
