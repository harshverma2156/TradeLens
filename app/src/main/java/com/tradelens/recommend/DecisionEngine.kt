package com.tradelens.recommend

import com.tradelens.flow.FlowReport
import com.tradelens.fundamentals.FundamentalReport
import com.tradelens.fundamentals.Pillar
import com.tradelens.patterns.PatternReport
import com.tradelens.patterns.PatternStatus
import com.tradelens.research.ResearchReport
import com.tradelens.ta.Bias
import com.tradelens.ta.TechnicalReport
import com.tradelens.trade.EntryStatus
import com.tradelens.trade.Recommendation
import com.tradelens.valuation.ValuationReport
import kotlin.math.abs

/**
 * Combines the engines' outputs into one decision per horizon. Scores are 0–100 (above 50 leans bullish). A component
 * whose input is missing is unavailable and drops out of the weighting; it is never scored as 0 or 50.
 *
 * The weights and thresholds are starting configurations ([ModelConfig.DEFAULT]), not proven optimal values. The
 * confidence is not a probability of profit until calibrated against out-of-sample history (see [Calibration]).
 */

enum class InvestmentHorizon(val label: String, val window: String, val holding: String) {
    SHORT("Short term", "1–30 days", "days to a few weeks"),
    MEDIUM("Medium term", "1–6 months", "one to six months"),
    LONG("Long term", "1–5+ years", "several years"),
}

enum class ScoreComponent(val label: String) {
    TECHNICAL("Technical analysis"),
    VOLUME("Volume & market activity"),
    MARKET("Market conditions"),
    PATTERNS("Chart patterns"),
    NEWS("News & event risk"),
    FUNDAMENTALS("Fundamental quality"),
    EARNINGS_MOMENTUM("Earnings momentum"),
    VALUATION("Valuation"),
    GROWTH("Growth outlook"),
    GOVERNANCE("Governance & financial health"),
    ENTRY_TIMING("Technical entry timing"),
    SECTOR_MACRO("Sector & macro conditions"),
}

enum class DecisionAction(val label: String, val meaning: String) {
    STRONG_BUY_SETUP("Strong buy setup", "Several signals agree and the entry is ready. Still size small and keep the stop."),
    BUY("Buy", "The evidence leans positive. Consider buying in the preferred zone, with a stop."),
    ACCUMULATE("Accumulate", "Positive but not urgent. Buy in parts over time rather than all at once."),
    WAIT_FOR_ENTRY("Wait for entry", "The stock may be worth owning, but the price is not yet at a sensible entry."),
    HOLD("Hold", "Keep what you have. There is no strong reason to add or to sell."),
    PARTIAL_PROFIT_BOOKING("Partial profit booking", "A first target has been reached. Sell part and protect the rest with a stop."),
    SELL("Sell", "The evidence has turned against the position. Exit it."),
    AVOID("Avoid", "The evidence is against buying now. Do not open a new position."),
    NO_TRADE("No trade: insufficient data", "Too little reliable data to recommend anything. This is not a neutral signal."),
}

enum class RiskLevel(val label: String) { LOW("Low"), MODERATE("Moderate"), HIGH("High"), VERY_HIGH("Very high") }

enum class ConflictSeverity(val label: String) { HIGH("High"), MEDIUM("Medium") }

data class ComponentScore(val component: ScoreComponent, val score: Double?, val reason: String)

data class Conflict(val title: String, val severity: ConflictSeverity, val evidence: String)

data class Band(val low: Double, val high: Double, val note: String)

data class PlanTarget(val label: String, val price: Double?, val note: String)

/** Price plan for one horizon. Each field is null (with a note) when the engines did not supply it. */
data class PlanBlock(
    val currentPrice: Double?,
    val preferredEntry: Band?,
    val alternativeEntry: Band?,
    val stop: Double?,
    val stopNote: String,
    val targets: List<PlanTarget>,
    val riskReward: Double?,
    val waitFor: List<String>,
    val exitWhen: List<String>,
)

data class HorizonDecision(
    val horizon: InvestmentHorizon,
    val action: DecisionAction,
    val overall: Double?,
    val coverage: Double,
    val agreement: Double,
    val confidenceRaw: Double,
    val confidence: Double?,
    val confidenceNote: String,
    val riskLevel: RiskLevel,
    val components: List<ComponentScore>,
    val conflicts: List<Conflict>,
    val supporting: List<String>,
    val opposing: List<String>,
    val risks: List<String>,
    val plan: PlanBlock,
    val explanation: String,
    val modelVersion: String,
    val inputAsOf: Long?,
    val computedAt: Long,
)

/** Everything the engine may read. Any field can be null; the engines do not fill gaps. */
data class DecisionInputs(
    val symbol: String,
    val price: Double?,
    val now: Long,
    val technical: TechnicalReport?,
    val benchmark: TechnicalReport?,
    val patterns: PatternReport?,
    val trade: Recommendation?,
    val flow: FlowReport?,
    val fundamentals: FundamentalReport?,
    val valuation: ValuationReport?,
    val research: ResearchReport?,
    val holding: Boolean,
    val inputAsOf: Long?,
)

data class ModelConfig(
    val version: String,
    /** Weights in percent per horizon. Each horizon's weights must total 100. */
    val weights: Map<InvestmentHorizon, Map<ScoreComponent, Double>>,
    /** Share of the weight that must have data. Below this the decision is NO_TRADE / insufficient data. */
    val minCoverage: Double = 0.6,
) {
    init {
        for (h in InvestmentHorizon.values()) {
            val w = weights[h] ?: error("No weights for $h")
            require(abs(w.values.sum() - 100.0) < 0.01) { "Weights for $h must total 100, not ${w.values.sum()}" }
            require(w.values.all { it >= 0 }) { "Weights must be non-negative" }
        }
        require(minCoverage in 0.0..1.0)
    }

    companion object {
        val DEFAULT = ModelConfig(
            version = "1.0.0",
            weights = mapOf(
                InvestmentHorizon.SHORT to mapOf(
                    ScoreComponent.TECHNICAL to 35.0, ScoreComponent.VOLUME to 25.0, ScoreComponent.MARKET to 15.0,
                    ScoreComponent.PATTERNS to 15.0, ScoreComponent.NEWS to 10.0,
                ),
                InvestmentHorizon.MEDIUM to mapOf(
                    ScoreComponent.TECHNICAL to 25.0, ScoreComponent.FUNDAMENTALS to 15.0, ScoreComponent.EARNINGS_MOMENTUM to 10.0,
                    ScoreComponent.VOLUME to 15.0, ScoreComponent.VALUATION to 15.0, ScoreComponent.NEWS to 10.0,
                    ScoreComponent.SECTOR_MACRO to 10.0,
                ),
                InvestmentHorizon.LONG to mapOf(
                    ScoreComponent.FUNDAMENTALS to 30.0, ScoreComponent.VALUATION to 25.0, ScoreComponent.GROWTH to 20.0,
                    ScoreComponent.GOVERNANCE to 15.0, ScoreComponent.ENTRY_TIMING to 5.0, ScoreComponent.SECTOR_MACRO to 5.0,
                ),
            ),
        )
    }
}

object DecisionEngine {

    fun decideAll(i: DecisionInputs, cfg: ModelConfig = ModelConfig.DEFAULT, calibration: CalibrationTable? = null): List<HorizonDecision> =
        InvestmentHorizon.values().map { decide(it, i, cfg, calibration) }

    fun decide(h: InvestmentHorizon, i: DecisionInputs, cfg: ModelConfig = ModelConfig.DEFAULT, calibration: CalibrationTable? = null): HorizonDecision {
        val comps = components(i)
        val weights = cfg.weights.getValue(h)
        val byComp = comps.associateBy { it.component }

        val totalW = weights.values.sum()
        val avail = weights.filter { (c, w) -> w > 0 && byComp[c]?.score != null }
        val availW = avail.values.sum()
        val coverage = if (totalW > 0) availW / totalW else 0.0
        val overall = if (availW > 0) avail.entries.sumOf { (c, w) -> byComp.getValue(c).score!! * w } / availW else null

        val side = overall?.let { if (it >= 50) 1 else -1 }
        val agreement = if (side == null || availW == 0.0) 0.0 else
            avail.filter { (c, _) -> (byComp.getValue(c).score!! >= 50) == (side > 0) }.values.sum() / availW
        val conflicts = conflicts(i, byComp)
        val confRaw = coverage * agreement * (1 - 0.15 * conflicts.count { it.severity == ConflictSeverity.HIGH }.coerceAtMost(2))
            .coerceIn(0.0, 1.0)
        val calibrated = calibration?.predict(confRaw)
        val risk = riskLevel(i, coverage, conflicts)
        val plan = plan(h, i)
        val action = action(h, overall, coverage, byComp, conflicts, i, plan, cfg)

        return HorizonDecision(
            horizon = h, action = action, overall = overall?.let { Math.round(it * 10) / 10.0 }, coverage = coverage, agreement = agreement,
            confidenceRaw = confRaw, confidence = calibrated,
            confidenceNote = if (calibrated != null) "Calibrated on out-of-sample history: about ${(calibrated * 100).toInt()}% of similar past calls were right."
                else "Not calibrated: this is data coverage × agreement between engines, not a probability of profit. No out-of-sample history has been measured for this model yet.",
            riskLevel = risk, components = comps, conflicts = conflicts,
            supporting = comps.filter { (it.score ?: 0.0) >= 60 }.map { "${it.component.label}: ${it.score!!.toInt()} · ${it.reason}" },
            opposing = comps.filter { it.score != null && it.score <= 40 }.map { "${it.component.label}: ${it.score!!.toInt()} · ${it.reason}" } +
                conflicts.map { "Conflict (${it.severity.label.lowercase()}): ${it.title}. ${it.evidence}" },
            risks = risks(i, risk),
            plan = plan, explanation = explain(h, action, overall, coverage, risk, i),
            modelVersion = cfg.version, inputAsOf = i.inputAsOf, computedAt = i.now,
        )
    }

    // ------------------------------------------------------------------ components

    fun components(i: DecisionInputs): List<ComponentScore> {
        val t = i.technical?.summary
        val b = i.benchmark?.summary
        val f = i.flow?.assessment
        val a = i.fundamentals?.analysis
        val v = i.valuation
        val tr = i.trade
        fun pillar(p: Pillar) = a?.pillars?.firstOrNull { it.pillar == p }?.score
        val patternScore: Pair<Double, String>? = i.patterns?.let { pr ->
            val live = pr.active.filter { it.status == PatternStatus.FORMING || it.status == PatternStatus.CONFIRMED }
            if (live.isEmpty()) 50.0 to "No forming or confirmed chart pattern."
            else {
                val net = live.count { it.direction == Bias.BULLISH } - live.count { it.direction == Bias.BEARISH }
                (50 + 50 * (net / 3.0).coerceIn(-1.0, 1.0)) to "${live.size} live pattern(s): net ${if (net >= 0) "+" else ""}$net bullish."
            }
        }
        val news = i.research?.overview?.let { o ->
            val n = o.positive + o.negative
            (50 + 50 * (o.positive - o.negative).toDouble() / maxOf(1, n)) to "${o.positive} positive, ${o.negative} negative of ${o.total} items."
        }
        val momentum = a?.let { x ->
            val n = x.improving.size + x.deteriorating.size
            if (n == 0) null else (50 + 50 * ((x.improving.size - x.deteriorating.size) / 4.0).coerceIn(-1.0, 1.0)) to
                "${x.improving.size} measures improved, ${x.deteriorating.size} worsened year on year."
        }
        val gov = listOfNotNull(pillar(Pillar.GOVERNANCE), pillar(Pillar.FINANCIAL_STRENGTH))
        val entry = tr?.let { entryScore(it.status) to "Smart-entry status: ${it.status.label}." }

        return listOf(
            ComponentScore(ScoreComponent.TECHNICAL, t?.let { (it.score + 100) / 2.0 }, t?.explanation ?: "Technical indicators not computed."),
            ComponentScore(ScoreComponent.VOLUME, f?.let { (it.score + 100) / 2.0 }, f?.let { "${it.label}. ${it.caveat}" } ?: "Volume analytics unavailable."),
            ComponentScore(ScoreComponent.MARKET, b?.let { (it.score + 100) / 2.0 }, b?.let { "Benchmark index: ${it.explanation}" } ?: "Benchmark index not loaded."),
            ComponentScore(ScoreComponent.PATTERNS, patternScore?.first, patternScore?.second ?: "Chart patterns not computed."),
            ComponentScore(ScoreComponent.NEWS, news?.first?.let { s -> adjustForEvents(s, i.trade) }, news?.second ?: "No news items retrieved."),
            ComponentScore(ScoreComponent.FUNDAMENTALS, a?.quality?.score?.toDouble(), a?.summary ?: "Fundamentals unavailable."),
            ComponentScore(ScoreComponent.EARNINGS_MOMENTUM, momentum?.first, momentum?.second ?: "No year-on-year changes measured."),
            ComponentScore(ScoreComponent.VALUATION, v?.marginOfSafetyPct?.let { (50 + it).coerceIn(0.0, 100.0) },
                v?.marginOfSafetyPct?.let { "Margin of safety ${String.format(java.util.Locale.US, "%+.1f", it)}% against the base fair value (model estimate)." } ?: "Valuation unavailable."),
            ComponentScore(ScoreComponent.GROWTH, pillar(Pillar.GROWTH), if (pillar(Pillar.GROWTH) != null) "Growth pillar of the fundamental score." else "Growth figures unavailable."),
            ComponentScore(ScoreComponent.GOVERNANCE, gov.takeIf { it.isNotEmpty() }?.average(),
                if (gov.isNotEmpty()) "Governance and financial-strength pillars." else "Governance checks not run."),
            ComponentScore(ScoreComponent.ENTRY_TIMING, entry?.first, entry?.second ?: "No smart-entry plan."),
            ComponentScore(ScoreComponent.SECTOR_MACRO, null, "No sector index or macro data is connected."),
        )
    }

    /** Major, unresolved event risk lowers the news score by 15 points. */
    private fun adjustForEvents(score: Double, tr: Recommendation?): Double =
        if (tr?.events?.any { it.major && !it.resolved } == true) (score - 15).coerceAtLeast(0.0) else score

    private fun entryScore(s: EntryStatus): Double = when (s) {
        EntryStatus.BUY_NOW -> 90.0
        EntryStatus.WAIT_FOR_CONFIRMATION -> 60.0
        EntryStatus.HOLD -> 60.0
        EntryStatus.WAIT_FOR_BREAKOUT -> 55.0
        EntryStatus.WAIT_FOR_CORRECTION -> 40.0
        EntryStatus.PARTIAL_PROFIT_BOOKING -> 35.0
        EntryStatus.EXIT -> 10.0
        EntryStatus.AVOID -> 5.0
    }

    // ------------------------------------------------------------------ conflicts

    fun conflicts(i: DecisionInputs, c: Map<ScoreComponent, ComponentScore>): List<Conflict> {
        fun s(k: ScoreComponent) = c[k]?.score
        val out = mutableListOf<Conflict>()
        val fund = s(ScoreComponent.FUNDAMENTALS)
        val val_ = s(ScoreComponent.VALUATION)
        val tech = s(ScoreComponent.TECHNICAL)
        val news = s(ScoreComponent.NEWS)
        val vol = s(ScoreComponent.VOLUME)
        val majorEvent = i.trade?.events?.any { it.major && !it.resolved } == true
        if (fund != null && val_ != null && fund >= 65 && val_ <= 40)
            out += Conflict("Strong fundamentals but expensive valuation", ConflictSeverity.MEDIUM,
                "Fundamental quality ${fund.toInt()}, valuation score ${val_.toInt()}: the business is good but the price already reflects it.")
        if (tech != null && fund != null && tech >= 60 && fund <= 40)
            out += Conflict("Bullish chart but weak fundamentals", ConflictSeverity.MEDIUM,
                "Technical ${tech.toInt()} while fundamental quality is ${fund.toInt()}: price momentum is not backed by the reported business.")
        val bearishEntry = i.trade?.status?.let { it == EntryStatus.EXIT || it == EntryStatus.AVOID } == true
        if (news != null && tech != null && news >= 60 && (tech <= 40 || bearishEntry))
            out += Conflict("Positive news but bearish price action", ConflictSeverity.MEDIUM,
                "News is positive (${news.toInt()}) while the price signals are weak (technical ${tech.toInt()}). The market may not agree with the headlines.")
        if (((tech != null && tech >= 65) || i.trade?.status == EntryStatus.BUY_NOW) && majorEvent)
            out += Conflict("Strong technical setup but high event risk", ConflictSeverity.HIGH,
                "The setup looks good, but a major unresolved event is scheduled or pending. Such events can move the price beyond the stop.")
        if (vol != null && tech != null && vol >= 65 && tech <= 40)
            out += Conflict("Volume buying while price falls", ConflictSeverity.MEDIUM,
                "Volume analysis leans to accumulation (${vol.toInt()}) while price signals lean down (${tech.toInt()}).")
        return out
    }

    // ------------------------------------------------------------------ risk

    private fun riskLevel(i: DecisionInputs, coverage: Double, conflicts: List<Conflict>): RiskLevel {
        var points = 0
        val atrPct = if (i.trade != null && i.trade.price > 0) i.trade.atr / i.trade.price * 100 else null
        if (atrPct != null && atrPct > 3) points++
        if (atrPct != null && atrPct > 5) points++
        if (i.trade?.events?.any { it.major && !it.resolved } == true) points++
        if (i.fundamentals?.analysis?.redFlags?.any { it.severity == com.tradelens.fundamentals.Severity.HIGH } == true) points++
        if ((i.valuation?.premiumPct ?: 0.0) > 30) points++
        if (coverage < 0.7) points++
        if (conflicts.any { it.severity == ConflictSeverity.HIGH }) points++
        return when {
            points <= 1 -> RiskLevel.LOW
            points == 2 -> RiskLevel.MODERATE
            points == 3 -> RiskLevel.HIGH
            else -> RiskLevel.VERY_HIGH
        }
    }

    private fun risks(i: DecisionInputs, risk: RiskLevel): List<String> = buildList {
        add("Overall risk level: ${risk.label.lowercase()}, from volatility, event risk, red flags, valuation and data coverage.")
        i.trade?.let { t -> if (t.price > 0) add("Typical daily swing (ATR): ${String.format(java.util.Locale.US, "%.1f", t.atr / t.price * 100)}% of price.") }
        i.trade?.events?.filter { it.major && !it.resolved }?.forEach { add("Event: ${it.title}") }
        i.fundamentals?.analysis?.redFlags?.forEach { add("Red flag: ${it.title} (${it.severity.label.lowercase()}). ${it.evidence}") }
        add("Model estimates (valuation, growth) can be wrong; the market price is what you actually pay.")
    }

    // ------------------------------------------------------------------ plan

    fun plan(h: InvestmentHorizon, i: DecisionInputs): PlanBlock {
        val price = i.price
        val tr = i.trade
        val v = i.valuation
        return when (h) {
            InvestmentHorizon.SHORT -> {
                val p = tr?.primaryPlan
                val primaryType = tr?.primary
                val alt = tr?.strategies?.firstOrNull { it.type != primaryType && it.entryLow != it.entryHigh }
                val targets = p?.exit?.targets?.take(3)?.map { PlanTarget(it.label, it.price, it.basis) }.orEmpty()
                PlanBlock(
                    currentPrice = price,
                    preferredEntry = p?.let { Band(it.entryLow, it.entryHigh, "${p.type.label} entry: ${p.trigger}") },
                    alternativeEntry = alt?.let { Band(it.entryLow, it.entryHigh, "Alternative (${it.type.label})") },
                    stop = p?.exit?.stopLoss,
                    stopNote = p?.exit?.stopBasis ?: "No strategy plan, so no short-term stop.",
                    targets = padTargets(targets, "No strategy target available."),
                    riskReward = riskReward(p?.entry, p?.exit?.stopLoss, targets.firstOrNull()?.price),
                    waitFor = if (p != null && p.status != EntryStatus.BUY_NOW) listOf(p.trigger) else emptyList(),
                    exitWhen = p?.exit?.exitConditions.orEmpty() + listOfNotNull(p?.exit?.invalidationRule),
                )
            }
            InvestmentHorizon.MEDIUM -> {
                val support = tr?.supports?.firstOrNull()
                val resist = tr?.resistances.orEmpty()
                val stop = support?.let { s -> tr?.atr?.let { s.low - it } }
                val targets = resist.take(3).map { PlanTarget("Resistance", it.low, "Technical resistance zone (${it.low.fmt()}–${it.high.fmt()}).") }
                val valTargets = listOfNotNull(v?.fairBase?.let { PlanTarget("Base-case value", it, "Model estimate") })
                PlanBlock(
                    currentPrice = price,
                    preferredEntry = support?.let { Band(it.low, it.high, "Nearest technical support zone") },
                    alternativeEntry = v?.buyBelow?.let { Band(it, it, "Valuation buy-below line (margin of safety)") },
                    stop = stop,
                    stopNote = if (stop != null) "One ATR below the support zone." else "No support zone found, so no medium-term stop.",
                    targets = padTargets((targets + valTargets).take(3), "No resistance or model value available."),
                    riskReward = riskReward(support?.let { (it.low + it.high) / 2 }, stop, (targets + valTargets).firstOrNull()?.price),
                    waitFor = if (support != null && price != null && price > support.high) listOf("A pull-back into ${support.low.fmt()}–${support.high.fmt()}") else emptyList(),
                    exitWhen = listOf("A weekly close below the support zone.", "Earnings or governance surprise that changes the thesis."),
                )
            }
            InvestmentHorizon.LONG -> {
                val buyBelow = v?.buyBelow
                val targets = listOfNotNull(
                    v?.fairBase?.let { PlanTarget("Base-case value", it, "Model estimate") },
                    v?.fairBull?.let { PlanTarget("Bull-case value", it, "Model estimate") },
                    v?.rangeHigh?.let { PlanTarget("High end of model range", it, "Model estimate") },
                )
                PlanBlock(
                    currentPrice = price,
                    preferredEntry = buyBelow?.let { Band(it, it, "Buy at or below the margin-of-safety line") },
                    alternativeEntry = v?.fairBase?.let { Band(it * 0.9, it, "Fair-value zone (model)") },
                    stop = null,
                    stopNote = "No price stop for a long-term view. The thesis is invalidated instead (see exit conditions).",
                    targets = padTargets(targets, "No valuation model available for targets."),
                    // Long term has no price stop, so a reward-to-risk ratio would be invented: it is left unset.
                    riskReward = null,
                    waitFor = if (buyBelow == null) listOf("A valuation estimate is needed before an entry can be set.") else emptyList(),
                    exitWhen = listOf("Governance red flags turn high-severity or the fundamental score falls below 40.",
                        "Revenue or earnings fall for two reported years in a row."),
                )
            }
        }
    }

    private fun padTargets(t: List<PlanTarget>, missing: String): List<PlanTarget> =
        (t + List(3) { PlanTarget("Target ${t.size + it + 1}", null, missing) }).take(3)

    private fun riskReward(entry: Double?, stop: Double?, target: Double?): Double? {
        if (entry == null || stop == null || target == null) return null
        val risk = entry - stop
        val reward = target - entry
        return if (risk > 0 && reward > 0) Math.round(reward / risk * 100) / 100.0 else null
    }

    private fun Double.fmt() = String.format(java.util.Locale.US, "%.2f", this)

    // ------------------------------------------------------------------ action

    fun action(
        h: InvestmentHorizon, overall: Double?, coverage: Double, c: Map<ScoreComponent, ComponentScore>,
        conflicts: List<Conflict>, i: DecisionInputs, plan: PlanBlock, cfg: ModelConfig,
    ): DecisionAction {
        if (overall == null || coverage < cfg.minCoverage) return DecisionAction.NO_TRADE
        val highConflict = conflicts.any { it.severity == ConflictSeverity.HIGH }
        val entryReady = i.trade?.status == EntryStatus.BUY_NOW
        val target1 = plan.targets.firstOrNull()?.price
        val reachedTarget1 = i.price != null && target1 != null && i.price >= target1
        val holding = i.holding
        return when {
            holding && reachedTarget1 && overall < 65 -> DecisionAction.PARTIAL_PROFIT_BOOKING
            overall >= 78 && (h == InvestmentHorizon.LONG || entryReady) && !highConflict ->
                if (holding) DecisionAction.HOLD else DecisionAction.STRONG_BUY_SETUP
            overall >= 65 -> if (holding) DecisionAction.HOLD else if (h == InvestmentHorizon.LONG || entryReady) DecisionAction.BUY else DecisionAction.WAIT_FOR_ENTRY
            overall >= 55 -> if (holding) DecisionAction.HOLD else DecisionAction.ACCUMULATE
            overall >= 45 -> if (holding) DecisionAction.HOLD else DecisionAction.WAIT_FOR_ENTRY
            overall >= 35 -> if (holding) DecisionAction.HOLD else DecisionAction.AVOID
            else -> if (holding) DecisionAction.SELL else DecisionAction.AVOID
        }
    }

    private fun explain(h: InvestmentHorizon, a: DecisionAction, overall: Double?, coverage: Double, risk: RiskLevel, i: DecisionInputs): String {
        if (a == DecisionAction.NO_TRADE) return "Only ${(coverage * 100).toInt()}% of the ${h.label.lowercase()} model has data, below the required minimum. Nothing is recommended until more data is available."
        val lean = if ((overall ?: 50.0) >= 50) "leans positive" else "leans negative"
        val hold = if (i.holding) "You hold this stock." else "You do not hold it."
        return "For ${h.window}, the evidence $lean (overall ${overall?.toInt() ?: "—"}/100, risk ${risk.label.lowercase()}). ${a.meaning} $hold"
    }
}
