package com.tradelens.analysis

import com.tradelens.data.model.Candle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign

enum class Verdict { BULLISH, BEARISH, NEUTRAL }

enum class Signal(val label: String) {
    STRONG_BUY("Strong buy"), BUY("Buy"), HOLD("Hold"), SELL("Sell"), STRONG_SELL("Strong sell");

    val isBuy get() = this == STRONG_BUY || this == BUY
    val isSell get() = this == STRONG_SELL || this == SELL
}

enum class Direction { LONG, SHORT }

/** The six groups the rating is built from. Weights sum to 1; missing pillars are re-normalised away. */
enum class Pillar(val label: String, val weight: Double) {
    TREND("Trend", 0.30),
    MOMENTUM("Momentum", 0.20),
    RELATIVE("Relative strength vs NIFTY", 0.15),
    FLOW("Volume & money flow", 0.15),
    LOCATION("Price location", 0.10),
    MARKET("Market backdrop", 0.10),
}

data class Factor(
    val name: String,
    val value: String,
    val verdict: Verdict,
    val points: Int,
    val max: Int,
    val detail: String,
    val pillar: Pillar,
)

data class PillarScore(val pillar: Pillar, val score: Int, val factors: List<Factor>)

data class Target(val label: String, val price: Double, val pct: Double)

data class TradePlan(
    val direction: Direction,
    val entryLow: Double,
    val entryHigh: Double,
    val entry: Double,
    val stopLoss: Double,
    val stopPct: Double,
    val targets: List<Target>,
    val riskPerShare: Double,
    val rewardRiskT2: Double,
    val nearestLevel: Double?,          // real resistance (long) / support (short); null at fresh highs/lows
    val rewardRiskToLevel: Double?,
    val quantity: Int,
    val capitalRequired: Double,
    val maxLoss: Double,
    val profitAtT2: Double,
    val holding: String,
    val trailing: String,
    val invalidation: String,
)

enum class EntryType(val label: String) {
    BUY_NOW("Buy now"),
    PULLBACK("Wait for pullback"),
    BREAKOUT("Buy the breakout"),
    WAIT("Wait for confirmation"),
    AVOID("Avoid buying"),
}

data class EntryLevel(val name: String, val price: Double)

data class EntryAdvice(
    val type: EntryType,
    val price: Double,                 // the suggested entry price
    val zoneLow: Double,
    val zoneHigh: Double,
    val headline: String,
    val detail: String,
    val confluence: List<EntryLevel>,  // levels that agree on this zone
    val trigger: String?,              // what must happen before entering (for WAIT/PULLBACK)
)

data class Backtest(
    val trades: Int,
    val wins: Int,
    val winRate: Double,               // 0..1
    val baseWinRate: Double,           // same stop/target rule entered on every day, for comparison
    val avgReturnPct: Double,
    val profitFactor: Double?,
    val avgHoldDays: Double,
    val totalReturnPct: Double,        // compounded, one position at a time
    val buyHoldPct: Double,
    val periodDays: Int,
)

data class Analysis(
    val score: Int,                 // -100 (very bearish) .. +100 (very bullish)
    val signal: Signal,
    val confidence: Int,            // 0..100
    val summary: String,
    val pillars: List<PillarScore>,
    val plan: TradePlan,
    val entry: EntryAdvice,
    val backtest: Backtest?,
    val supports: List<Double>,
    val resistances: List<Double>,
    val atr: Double,
    val atrPct: Double,
    val rsi: Double,
    val ema20: Double,
    val ema50: Double,
    val ema200: Double?,
    val weeklyTrend: String,
    val warnings: List<String>,
) {
    val factors: List<Factor> get() = pillars.flatMap { it.factors }
    val confidenceLabel: String get() = when {
        confidence >= 70 -> "High"
        confidence >= 50 -> "Medium"
        else -> "Low"
    }
}

/**
 * Multi-pillar, multi-timeframe technical engine.
 *
 * 1. Every pillar (trend, momentum, relative strength, money flow, location, market) scores −100..+100
 *    from its factors; the weighted average is the overall score.
 * 2. Guardrails downgrade ratings that fight the weekly trend, chase extended moves or ignore a weak market.
 * 3. The entry price is the strongest confluence of support-type levels (EMAs, Supertrend, swing levels,
 *    volume POC, Fibonacci, anchored VWAP) near the current price.
 * 4. The same rules are replayed over the stock's own history to measure how well Buy ratings worked.
 */
object AnalysisEngine {
    const val MIN_CANDLES = 60

    const val STRONG_BUY_AT = 45
    const val BUY_AT = 20
    const val SELL_AT = -20
    const val STRONG_SELL_AT = -45

    // Backtest exit rule, in multiples of ATR at the signal bar.
    private const val BT_STOP_ATR = 1.5
    private const val BT_TARGET_ATR = 3.0
    private const val BT_MAX_BARS = 30

    /**
     * @param candles daily candles, oldest first; ~2 years gives the best results (200 EMA + backtest).
     * @param benchmark daily NIFTY 50 candles for relative strength and market backdrop; optional.
     */
    fun analyze(
        candles: List<Candle>,
        price: Double,
        high52: Double?,
        low52: Double?,
        capital: Double,
        riskPercent: Double,
        isIndex: Boolean = false,
        benchmark: List<Candle>? = null,
        runBacktest: Boolean = true,
    ): Analysis? {
        if (candles.size < MIN_CANDLES || price <= 0) return null
        val ctx = Ctx(candles, benchmark?.takeIf { !isIndex && it.size >= MIN_CANDLES }, isIndex)
        val i = ctx.n - 1
        val atr = ctx.atr[i] ?: return null
        val ema20 = ctx.ema20[i] ?: return null
        val ema50 = ctx.ema50[i] ?: return null
        val rsi = ctx.rsi[i] ?: return null

        val r = score(ctx, i, price, high52, low52)
        val bt = if (runBacktest) backtest(ctx) else null

        val levels = Ta.levels(candles.takeLast(120))
        val supports = levels.filter { it.price < price * 0.995 }.sortedByDescending { it.price }.map { it.price }.take(3)
        val resistances = levels.filter { it.price > price * 1.005 }.sortedBy { it.price }.map { it.price }.take(3)
        val prior20 = candles.subList(ctx.n - 21, ctx.n - 1)
        val high20 = prior20.maxOf { it.high }
        val low20 = prior20.minOf { it.low }

        val direction = if (r.signal.isSell) Direction.SHORT else Direction.LONG
        val entry = entryAdvice(ctx, r, direction, price, atr, levels, high20, low20)
        val realLevel = if (direction == Direction.LONG) resistances.firstOrNull { it > entry.price * 1.005 } ?: high20.takeIf { it > price * 1.005 }
        else supports.firstOrNull { it < entry.price * 0.995 } ?: low20.takeIf { it < price * 0.995 }
        val plan = buildPlan(direction, entry, atr, realLevel, ema20, r, capital, riskPercent)

        val confidence = confidence(r, bt)
        val atrPct = atr / price * 100
        val warnings = buildList {
            addAll(r.notes)
            if (atrPct > 4) add("High volatility: the stock moves about ${fmt(atrPct, 1)}% a day. Use a smaller position.")
            if (r.extension > 2.5 && direction == Direction.LONG)
                add("Price is ${fmt(r.extension, 1)} ATR above its 20 EMA. Chasing here gives poor risk-reward; let it come to the entry zone.")
            val lvl = plan.nearestLevel
            if (direction == Direction.LONG && lvl != null && lvl < plan.targets[0].price)
                add("Resistance at ${fmt(lvl)} sits before Target 1. Expect selling there; consider booking part profit near it.")
            if (bt != null && bt.trades >= 5 && bt.winRate < bt.baseWinRate)
                add("On this stock, past Buy ratings did worse than random entries (${pct(bt.winRate)} vs ${pct(bt.baseWinRate)} win rate). Treat signals here with extra caution.")
            if (bt != null && bt.trades < 5 && runBacktest)
                add("Only ${bt.trades} past Buy signals on this stock, too few to judge how reliable the rating is here.")
            if (direction == Direction.SHORT)
                add("In the Indian cash market you can't hold a short overnight. If you own it, the Sell rating means exit or tighten your stop; the short plan is only for intraday or F&O.")
            if (plan.quantity == 0) add("Your risk budget is smaller than the risk on one share. Raise capital/risk % in Settings or skip this trade.")
            add("Technical signals ignore results, news and corporate actions. Check upcoming results dates before trading.")
        }

        return Analysis(
            score = r.score, signal = r.signal, confidence = confidence,
            summary = summary(r, entry, confidence),
            pillars = r.pillars, plan = plan, entry = entry, backtest = bt,
            supports = supports, resistances = resistances,
            atr = atr, atrPct = atrPct, rsi = rsi, ema20 = ema20, ema50 = ema50, ema200 = ctx.ema200[i],
            weeklyTrend = r.weeklyLabel, warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- precomputed series

    private class Ctx(val c: List<Candle>, bench: List<Candle>?, val isIndex: Boolean) {
        val n = c.size
        val close = c.map { it.close }
        val ema20 = Ta.ema(close, 20)
        val ema50 = Ta.ema(close, 50)
        val ema200 = Ta.ema(close, 200)
        val rsi = Ta.rsi(close)
        val macd = Ta.macd(close)
        val atr = Ta.atr(c)
        val bb = Ta.bollinger(close)
        val adx = Ta.adx(c)
        val st = Ta.supertrend(c)
        val obvEma = Ta.ema(Ta.obv(c), 10)
        val mfi = Ta.mfi(c)
        val roc63 = Ta.roc(close, 63)
        val roc126 = Ta.roc(close, 126)
        val weekly = Ta.weekly(c)
        private val wClose = weekly.candles.map { it.close }
        val wEma10 = Ta.ema(wClose, 10)
        val wEma30 = Ta.ema(wClose, 30)
        val wCloseAt = wClose
        val hasVolume = !isIndex && c.takeLast(30).count { it.volume > 0 } >= 20

        // Benchmark aligned to this stock's days (carried forward over holidays that differ).
        val bClose: List<Double?>
        val bEma50: List<Double?>
        val bEma200: List<Double?>
        val rsRatio: List<Double?>
        val rsSma: List<Double?>

        init {
            if (bench == null) {
                bClose = List(n) { null }; bEma50 = bClose; bEma200 = bClose; rsRatio = bClose; rsSma = bClose
            } else {
                val bc = bench.map { it.close }
                val be50 = Ta.ema(bc, 50)
                val be200 = Ta.ema(bc, 200)
                val idx = IntArray(n) { -1 }
                var j = 0
                for (k in 0 until n) {
                    val d = Ta.dayKey(c[k].time)
                    while (j + 1 < bench.size && Ta.dayKey(bench[j + 1].time) <= d) j++
                    if (Ta.dayKey(bench[j].time) <= d) idx[k] = j
                }
                bClose = idx.map { if (it >= 0) bc[it] else null }
                bEma50 = idx.map { if (it >= 0) be50[it] else null }
                bEma200 = idx.map { if (it >= 0) be200[it] else null }
                rsRatio = (0 until n).map { k -> bClose[k]?.takeIf { it > 0 }?.let { close[k] / it } }
                rsSma = smaNullable(rsRatio, 100)
            }
        }

        fun bRoc(i: Int, p: Int): Double? {
            val a = bClose.getOrNull(i) ?: return null
            val b = bClose.getOrNull(i - p) ?: return null
            return if (b > 0) a / b - 1 else null
        }

        /** Weekly bar to judge day i by: the live week for the last bar, else the last completed week (no look-ahead). */
        fun weekFor(i: Int): Int = if (i == n - 1) weekly.weekOfDay[i] else weekly.weekOfDay[i] - 1

        private fun smaNullable(v: List<Double?>, p: Int): List<Double?> = v.indices.map { k ->
            if (k < p - 1) null else {
                var s = 0.0
                for (q in k - p + 1..k) s += v[q] ?: return@map null
                s / p
            }
        }
    }

    // ---------------------------------------------------------------- scoring

    private class Scored(
        val score: Int,
        val signal: Signal,
        val pillars: List<PillarScore>,
        val notes: List<String>,
        val extension: Double,          // (price − EMA20) / ATR
        val weeklyLabel: String,
        val adx: Double,
        val weeklyUp: Boolean,
    )

    private fun score(ctx: Ctx, i: Int, livePrice: Double? = null, high52In: Double? = null, low52In: Double? = null): Scored {
        val c = ctx.c
        val price = livePrice ?: c[i].close
        val atr = ctx.atr[i] ?: (c[i].high - c[i].low).coerceAtLeast(price * 0.01)
        val e20 = ctx.ema20[i]
        val e50 = ctx.ema50[i]
        val e200 = ctx.ema200[i]
        val all = mutableListOf<Factor>()
        fun add(p: Pillar, name: String, value: String, pts: Int, max: Int, detail: String) {
            all += Factor(name, value, when { pts > 0 -> Verdict.BULLISH; pts < 0 -> Verdict.BEARISH; else -> Verdict.NEUTRAL }, pts, max, detail, p)
        }

        // ---------- TREND
        if (e20 != null && e50 != null) {
            when {
                price > e20 && e20 > e50 -> add(Pillar.TREND, "20/50 EMA", "Up", 3, 3, "Price above the 20 and 50 EMA with the 20 EMA leading: buyers in control.")
                price < e20 && e20 < e50 -> add(Pillar.TREND, "20/50 EMA", "Down", -3, 3, "Price below the 20 and 50 EMA: sellers in control, rallies tend to get sold.")
                price > e50 -> add(Pillar.TREND, "20/50 EMA", "Mixed, above 50", 1, 3, "Holding above the 50 EMA but short-term momentum is mixed.")
                else -> add(Pillar.TREND, "20/50 EMA", "Mixed, below 50", -1, 3, "Below the 50 EMA; the medium-term trend hasn't turned up.")
            }
        }
        if (e200 != null) {
            val prev = ctx.ema200.getOrNull(i - 20)
            val rising = prev != null && e200 > prev
            val pts = (if (price > e200) 2 else -2) + if (prev == null) 0 else if (rising) 1 else -1
            add(Pillar.TREND, "200 EMA", "${fmt(e200)} ${if (rising) "rising" else "falling"}", pts, 3,
                if (price > e200) "Above a ${if (rising) "rising" else "flat/falling"} 200-day EMA: the long-term trend ${if (rising) "is up" else "is turning"}. Dips tend to get bought."
                else "Below the ${if (rising) "rising" else "falling"} 200-day EMA: long-term trend is down, long trades carry extra risk.")
        }
        val w = ctx.weekFor(i)
        val wc = ctx.wCloseAt.getOrNull(w)
        val w10 = ctx.wEma10.getOrNull(w)
        val w30 = ctx.wEma30.getOrNull(w)
        var weeklyUp = false
        var weeklyDown = false
        var weeklyLabel = "Not enough weekly data"
        if (wc != null && w10 != null && w30 != null) {
            when {
                wc > w10 && w10 > w30 -> { weeklyUp = true; weeklyLabel = "Up"
                    add(Pillar.TREND, "Weekly trend", "Up", 3, 3, "On the weekly chart price is above its 10 and 30-week EMAs: the bigger trend supports buying.") }
                wc < w10 && w10 < w30 -> { weeklyDown = true; weeklyLabel = "Down"
                    add(Pillar.TREND, "Weekly trend", "Down", -3, 3, "Weekly chart is in a downtrend (below the 10 and 30-week EMAs). Daily rallies often fail.") }
                wc > w30 -> { weeklyLabel = "Sideways, above 30-week"
                    add(Pillar.TREND, "Weekly trend", "Sideways, above 30W", 1, 3, "Weekly trend is sideways but holding above the 30-week EMA.") }
                else -> { weeklyLabel = "Sideways, below 30-week"
                    add(Pillar.TREND, "Weekly trend", "Sideways, below 30W", -1, 3, "Weekly trend is sideways and below the 30-week EMA.") }
            }
        }
        val stUp = ctx.st.up[i]
        val stLine = ctx.st.line[i]
        if (stUp != null && stLine != null) {
            val flipped = (max(1, i - 4)..i).any { k -> ctx.st.up[k] != null && ctx.st.up[k - 1] != null && ctx.st.up[k] != ctx.st.up[k - 1] }
            add(Pillar.TREND, "Supertrend (10,3)", if (stUp) "Up, stop ${fmt(stLine)}" else "Down, cap ${fmt(stLine)}", if (stUp) 2 else -2, 2,
                if (stUp) "Supertrend is bullish${if (flipped) " and flipped up in the last week" else ""}; its line at ${fmt(stLine)} acts as a trailing support."
                else "Supertrend is bearish${if (flipped) " and flipped down in the last week" else ""}; ${fmt(stLine)} is the level to reclaim.")
        }
        val adxV = ctx.adx.adx[i] ?: 0.0
        val pdi = ctx.adx.plusDi[i] ?: 0.0
        val mdi = ctx.adx.minusDi[i] ?: 0.0
        val dirSign = if (pdi >= mdi) 1 else -1
        when {
            adxV >= 25 -> add(Pillar.TREND, "ADX (14)", fmt(adxV, 1), 2 * dirSign, 2,
                if (dirSign > 0) "Strong trend with buyers dominant (+DI > −DI)." else "Strong trend with sellers dominant (−DI > +DI).")
            adxV >= 20 -> add(Pillar.TREND, "ADX (14)", fmt(adxV, 1), dirSign, 2, "A trend is forming in favour of ${if (dirSign > 0) "buyers" else "sellers"}.")
            else -> add(Pillar.TREND, "ADX (14)", fmt(adxV, 1), 0, 2, "Weak trend: range-bound. Buy near support, sell near resistance; breakouts may fail.")
        }

        // ---------- MOMENTUM
        val rsi = ctx.rsi[i]
        if (rsi != null) {
            when {
                rsi > 80 -> add(Pillar.MOMENTUM, "RSI (14)", fmt(rsi, 1), -1, 2, "Extremely overbought. Strong stocks can stay here, but fresh entries usually get a better price later.")
                rsi >= 70 -> add(Pillar.MOMENTUM, "RSI (14)", fmt(rsi, 1), 1, 2, "Overbought but in a strong-momentum zone. Don't chase; buy dips.")
                rsi >= 55 -> add(Pillar.MOMENTUM, "RSI (14)", fmt(rsi, 1), 2, 2, "Bullish momentum zone (55–70) without being overbought: the sweet spot.")
                rsi >= 45 -> add(Pillar.MOMENTUM, "RSI (14)", fmt(rsi, 1), 0, 2, "Neutral momentum.")
                rsi >= 30 -> add(Pillar.MOMENTUM, "RSI (14)", fmt(rsi, 1), -2, 2, "Bearish momentum zone (30–45).")
                else -> add(Pillar.MOMENTUM, "RSI (14)", fmt(rsi, 1), 0, 2, "Oversold. A bounce is possible, but oversold stocks can stay oversold in a downtrend.")
            }
        }
        val ml = ctx.macd.line[i]
        val ms = ctx.macd.signal[i]
        if (ml != null && ms != null) {
            fun crossed(up: Boolean) = (max(1, i - 2)..i).any { k ->
                val a = ctx.macd.line[k]; val b = ctx.macd.signal[k]; val pa = ctx.macd.line[k - 1]; val pb = ctx.macd.signal[k - 1]
                a != null && b != null && pa != null && pb != null && if (up) a > b && pa <= pb else a < b && pa >= pb
            }
            if (ml > ms) {
                val fresh = crossed(true)
                val pts = (if (ml > 0) 2 else 1) + if (fresh) 1 else 0
                add(Pillar.MOMENTUM, "MACD (12,26,9)", if (fresh) "Fresh bullish cross" else "Above signal", pts, 3,
                    (if (fresh) "MACD just crossed above its signal line" else "MACD is above its signal line") +
                        if (ml > 0) " and above zero: momentum confirms the uptrend." else ", still below zero: an early recovery.")
            } else {
                val fresh = crossed(false)
                val pts = -(if (ml < 0) 2 else 1) - if (fresh) 1 else 0
                add(Pillar.MOMENTUM, "MACD (12,26,9)", if (fresh) "Fresh bearish cross" else "Below signal", pts, 3,
                    (if (fresh) "MACD just crossed below its signal line" else "MACD is below its signal line") +
                        if (ml < 0) " and below zero: momentum confirms the downtrend." else ", still above zero: momentum is cooling.")
            }
        }
        fun rocFactor(name: String, v: Double?, max: Int) {
            v ?: return
            val p = v * 100
            val pts = when { p > 10 -> max; p > 0 -> 1; p < -10 -> -max; p < 0 -> -1; else -> 0 }
            add(Pillar.MOMENTUM, name, signedPct(p), pts, max,
                if (p > 0) "Up ${fmt(p, 1)}% over the period. Stocks with positive medium-term momentum tend to keep outperforming."
                else "Down ${fmt(-p, 1)}% over the period: negative medium-term momentum.")
        }
        rocFactor("3-month return", ctx.roc63[i], 2)
        rocFactor("6-month return", ctx.roc126[i], 2)

        // ---------- RELATIVE STRENGTH
        fun rsFactor(name: String, p: Int, max: Int) {
            val s = ctx.roc63.let { if (p == 63) it[i] else ctx.roc126[i] } ?: return
            val b = ctx.bRoc(i, p) ?: return
            val diff = (s - b) * 100
            val pts = when { diff > 5 -> max; diff > 0 -> 1; diff < -5 -> -max; diff < 0 -> -1; else -> 0 }
            add(Pillar.RELATIVE, name, signedPct(diff), pts, max,
                if (diff >= 0) "Beat NIFTY 50 by ${fmt(diff, 1)} percentage points. Market leaders tend to lead the next leg up."
                else "Lagged NIFTY 50 by ${fmt(-diff, 1)} percentage points. Laggards often keep lagging.")
        }
        rsFactor("vs NIFTY, 3 months", 63, 3)
        rsFactor("vs NIFTY, 6 months", 126, 2)
        val rr = ctx.rsRatio[i]
        val rsm = ctx.rsSma[i]
        if (rr != null && rsm != null) {
            val up = rr > rsm
            add(Pillar.RELATIVE, "RS line", if (up) "Above its average" else "Below its average", if (up) 2 else -2, 2,
                if (up) "The stock/NIFTY ratio is above its 100-day average: it is gaining strength relative to the market."
                else "The stock/NIFTY ratio is below its 100-day average: it is losing ground to the market.")
        }

        // ---------- FLOW
        if (ctx.hasVolume && i >= 21) {
            val o = ctx.obvEma[i]
            val o10 = ctx.obvEma.getOrNull(i - 10)
            if (o != null && o10 != null) {
                val up = o > o10
                val priceUp = c[i].close > c[i - 10].close
                val pts = when { up && priceUp -> 2; up -> 1; !up && !priceUp -> -2; else -> -1 }
                add(Pillar.FLOW, "On-balance volume", if (up) "Rising" else "Falling", pts, 2, when {
                    up && priceUp -> "Volume is flowing in as price rises: accumulation confirms the move."
                    up -> "Volume is flowing in while price dips: quiet accumulation (bullish divergence)."
                    !priceUp -> "Volume is flowing out as price falls: distribution confirms the decline."
                    else -> "Price is rising but volume is flowing out: the rally lacks conviction (bearish divergence)."
                })
            }
            val mfi = ctx.mfi[i]
            if (mfi != null) {
                when {
                    mfi > 80 -> add(Pillar.FLOW, "Money Flow Index", fmt(mfi, 0), -1, 1, "Money flow is overheated (>80); buying pressure may be exhausted.")
                    mfi >= 50 -> add(Pillar.FLOW, "Money Flow Index", fmt(mfi, 0), 1, 1, "More money flowing in than out.")
                    mfi >= 20 -> add(Pillar.FLOW, "Money Flow Index", fmt(mfi, 0), -1, 1, "More money flowing out than in.")
                    else -> add(Pillar.FLOW, "Money Flow Index", fmt(mfi, 0), 0, 1, "Money flow is washed out (<20); selling may be exhausted.")
                }
            }
            var upV = 0.0
            var dnV = 0.0
            for (k in i - 19..i) if (c[k].close >= c[k - 1].close) upV += c[k].volume else dnV += c[k].volume
            if (dnV > 0) {
                val ratio = upV / dnV
                val pts = when { ratio > 1.3 -> 2; ratio > 1.0 -> 1; ratio < 0.77 -> -2; else -> -1 }
                add(Pillar.FLOW, "Up/down volume (20d)", "${fmt(ratio, 2)}×", pts, 2,
                    if (ratio >= 1) "Up days carry ${fmt(ratio, 1)}× the volume of down days: buyers are more aggressive."
                    else "Down days carry more volume than up days: sellers are more aggressive.")
            }
            val avg = (i - 20 until i).map { c[it].volume.toDouble() }.average()
            if (avg > 0) {
                val ratio = c[i].volume / avg
                if (ratio >= 1.5) {
                    val upDay = c[i].close >= c[i - 1].close
                    add(Pillar.FLOW, "Today's volume", "${fmt(ratio, 1)}× avg", if (upDay) 1 else -1, 1,
                        if (upDay) "Heavy volume on an up day: institutions may be buying." else "Heavy volume on a down day: distribution pressure.")
                } else add(Pillar.FLOW, "Today's volume", "${fmt(ratio, 1)}× avg", 0, 1, if (ratio < 0.7) "Light volume; today's move isn't confirmed." else "Normal volume.")
            }
        }

        // ---------- LOCATION
        val win = c.subList(max(0, i - 119), i + 1)
        val lv = Ta.levels(win)
        val sup = lv.filter { it.price < price * 0.995 }.maxByOrNull { it.price }?.price
        val res = lv.filter { it.price > price * 1.005 }.minByOrNull { it.price }?.price
        if (i >= 21) {
            val prior = c.subList(i - 20, i)
            val h20 = prior.maxOf { it.high }
            val l20 = prior.minOf { it.low }
            when {
                c[i].close > h20 -> add(Pillar.LOCATION, "Price structure", "20-day breakout", 2, 2, "Closed above the previous 20-day high (${fmt(h20)}). Breakouts with volume often run further.")
                c[i].close < l20 -> add(Pillar.LOCATION, "Price structure", "20-day breakdown", -2, 2, "Closed below the previous 20-day low (${fmt(l20)}).")
                sup != null && price - sup <= atr -> add(Pillar.LOCATION, "Price structure", "Near support", 2, 2, "Just above support at ${fmt(sup)}: a tight stop is possible, so risk-reward is good.")
                res != null && res - price <= atr -> add(Pillar.LOCATION, "Price structure", "Near resistance", -2, 2, "Just below resistance at ${fmt(res)}: upside is capped until it breaks out.")
                else -> add(Pillar.LOCATION, "Price structure", "Mid-range", 0, 2, "Between support and resistance; no structural edge.")
            }
        }
        val yr = c.subList(max(0, i - 251), i + 1)
        val h52 = max(high52In ?: 0.0, yr.maxOf { it.high })
        val l52 = min(low52In ?: Double.MAX_VALUE, yr.minOf { it.low })
        if (h52 > l52) {
            val fromHigh = (h52 - price) / h52 * 100
            val pos = (price - l52) / (h52 - l52)
            when {
                fromHigh <= 5 -> add(Pillar.LOCATION, "52-week range", "${fmt(fromHigh, 1)}% from high", 2, 2, "Within 5% of its 52-week high: leaders make new highs, laggards don't.")
                pos >= 0.75 -> add(Pillar.LOCATION, "52-week range", "Upper quarter", 1, 2, "In the top quarter of its yearly range.")
                pos <= 0.10 -> add(Pillar.LOCATION, "52-week range", "Near low", -2, 2, "Near its 52-week low: weak relative strength. Avoid catching falling knives.")
                pos <= 0.25 -> add(Pillar.LOCATION, "52-week range", "Lower quarter", -1, 2, "In the bottom quarter of its yearly range.")
                else -> add(Pillar.LOCATION, "52-week range", "${fmt(pos * 100, 0)}% of range", 0, 2, "Mid-way through its yearly range.")
            }
        }
        val ext = if (e20 != null && atr > 0) (price - e20) / atr else 0.0
        when {
            ext > 3 -> add(Pillar.LOCATION, "Stretch from 20 EMA", "${fmt(ext, 1)} ATR", -2, 2, "Very extended above the 20 EMA. Pullbacks from here are common; wait for a better price.")
            ext > 2 -> add(Pillar.LOCATION, "Stretch from 20 EMA", "${fmt(ext, 1)} ATR", -1, 2, "Somewhat extended above the 20 EMA.")
            ext < -3 -> add(Pillar.LOCATION, "Stretch from 20 EMA", "${fmt(ext, 1)} ATR", 1, 2, "Very stretched below the 20 EMA: a relief bounce is likely even in a downtrend.")
            else -> add(Pillar.LOCATION, "Stretch from 20 EMA", "${fmt(ext, 1)} ATR", 0, 2, "Not stretched; price is near its short-term mean.")
        }
        val bu = ctx.bb.upper[i]
        val bl = ctx.bb.lower[i]
        val bm = ctx.bb.mid[i]
        if (bu != null && bl != null && bm != null && bu > bl) {
            val width = (bu - bl) / bm * 100
            val pb = (price - bl) / (bu - bl)
            when {
                width < 6 -> add(Pillar.LOCATION, "Bollinger Bands", "Squeeze (${fmt(width, 1)}%)", 0, 1, "Bands are very tight: a big move is building. Trade the direction of the breakout.")
                pb > 1 -> add(Pillar.LOCATION, "Bollinger Bands", "Above upper", -1, 1, "Above the upper band: stretched, expect a cool-off.")
                pb < 0 -> add(Pillar.LOCATION, "Bollinger Bands", "Below lower", 1, 1, "Below the lower band: stretched down, mean-reversion bounce possible.")
                else -> add(Pillar.LOCATION, "Bollinger Bands", "${fmt(pb * 100, 0)}% of band", 0, 1, "Inside the bands.")
            }
        }

        // ---------- MARKET
        val nb = ctx.bClose[i]
        val nb50 = ctx.bEma50[i]
        val nb200 = ctx.bEma200[i]
        if (nb != null && nb50 != null) {
            val pts = when {
                nb200 != null && nb > nb50 && nb > nb200 -> 2
                nb200 != null && nb < nb50 && nb < nb200 -> -2
                nb > nb50 -> 1
                else -> -1
            }
            add(Pillar.MARKET, "NIFTY 50 trend", when (pts) { 2 -> "Up"; -2 -> "Down"; 1 -> "Above 50 EMA"; else -> "Below 50 EMA" }, pts, 2, when (pts) {
                2 -> "NIFTY is above its 50 and 200-day EMAs: a healthy market lifts most stocks."
                -2 -> "NIFTY is below its 50 and 200-day EMAs: in weak markets most breakouts fail."
                1 -> "NIFTY is above its 50-day EMA; the broader trend is mixed."
                else -> "NIFTY is below its 50-day EMA; the market is under pressure."
            })
            ctx.bRoc(i, 20)?.let { r ->
                add(Pillar.MARKET, "NIFTY last month", signedPct(r * 100), if (r > 0) 1 else -1, 1,
                    if (r > 0) "The market rose over the last month." else "The market fell over the last month.")
            }
        }

        // ---------- combine
        val pillars = Pillar.entries.mapNotNull { p ->
            val fs = all.filter { it.pillar == p }
            val mx = fs.sumOf { it.max }
            if (fs.isEmpty() || mx == 0) null else PillarScore(p, (fs.sumOf { it.points } * 100.0 / mx).roundToInt().coerceIn(-100, 100), fs)
        }
        val wSum = pillars.sumOf { it.pillar.weight }
        val score = if (wSum == 0.0) 0 else (pillars.sumOf { it.score * it.pillar.weight } / wSum).roundToInt().coerceIn(-100, 100)

        var signal = when {
            score >= STRONG_BUY_AT -> Signal.STRONG_BUY
            score >= BUY_AT -> Signal.BUY
            score > SELL_AT -> Signal.HOLD
            score > STRONG_SELL_AT -> Signal.SELL
            else -> Signal.STRONG_SELL
        }
        val notes = mutableListOf<String>()
        val market = pillars.firstOrNull { it.pillar == Pillar.MARKET }?.score
        if (signal.isBuy && weeklyDown) {
            signal = Signal.HOLD
            notes += "Downgraded to Hold: daily signals are positive, but the weekly trend is still down. Wait for the weekly chart to turn."
        }
        if (signal == Signal.STRONG_BUY && ext > 3) {
            signal = Signal.BUY
            notes += "Downgraded from Strong buy: the stock is overextended. Buy on a pullback, not at this price."
        }
        if (signal == Signal.STRONG_BUY && market != null && market <= -50) {
            signal = Signal.BUY
            notes += "Downgraded from Strong buy: the overall market is weak. Use a smaller position."
        }
        if (signal == Signal.STRONG_SELL && weeklyUp) {
            signal = Signal.SELL
            notes += "Softened from Strong sell: the weekly trend is still up, so this may be a pullback rather than a reversal."
        }
        return Scored(score, signal, pillars, notes, ext, weeklyLabel, adxV, weeklyUp)
    }

    // ---------------------------------------------------------------- entry

    private class Cand(val name: String, val price: Double, val weight: Double)

    private fun entryAdvice(
        ctx: Ctx, r: Scored, dir: Direction, price: Double, atr: Double,
        levels: List<Ta.Level>, high20: Double, low20: Double,
    ): EntryAdvice {
        val c = ctx.c
        val i = ctx.n - 1
        val long = dir == Direction.LONG
        val s = if (long) 1.0 else -1.0
        val cands = mutableListOf<Cand>()
        fun cand(name: String, p: Double?, w: Double) {
            if (p == null || p <= 0) return
            val d = s * (price - p)               // distance "behind" price, in the direction of a pullback
            if (d >= -0.25 * atr && d <= 3 * atr) cands += Cand(name, p, w)
        }
        cand("20 EMA", ctx.ema20[i], 2.0)
        cand("50 EMA", ctx.ema50[i], 2.5)
        cand("200 EMA", ctx.ema200[i], 2.5)
        if (ctx.st.up[i] == long) cand("Supertrend", ctx.st.line[i], 2.0)
        levels.forEach { lv -> cand(if (long) "Swing support" else "Swing resistance", lv.price, 1.5 + 0.5 * min(lv.touches, 3)) }
        val six = c.takeLast(126)
        if (ctx.hasVolume) Ta.volumeProfile(six)?.let { vp ->
            cand("Volume POC (most traded price)", vp.poc, 2.0)
            cand(if (long) "Value-area low" else "Value-area high", if (long) vp.valueLow else vp.valueHigh, 1.0)
        }
        Ta.lastSwing(six, up = long)?.let { sw ->
            val range = sw.high - sw.low
            listOf(0.382 to 1.0, 0.5 to 1.2, 0.618 to 1.5).forEach { (f, w) ->
                cand("Fibonacci ${fmt(f * 100, 1)}%", if (long) sw.high - range * f else sw.low + range * f, w)
            }
            if (ctx.hasVolume) {
                val anchor = c.size - six.size + if (long) sw.lowIndex else sw.highIndex
                cand("Anchored VWAP from swing ${if (long) "low" else "high"}", Ta.anchoredVwap(c, anchor), 2.0)
            }
        }
        val brokeOut = if (long) c[i].close > high20 else c[i].close < low20
        if (brokeOut) cand(if (long) "Breakout retest (prior 20-day high)" else "Breakdown retest (prior 20-day low)", if (long) high20 else low20, 2.0)

        // Cluster nearby levels and pick the zone with the most agreement, preferring closer zones.
        val sorted = cands.sortedByDescending { s * it.price }
        val clusters = mutableListOf<MutableList<Cand>>()
        for (cd in sorted) {
            val g = clusters.lastOrNull()
            if (g != null && abs(g.first().price - cd.price) <= 0.6 * atr) g += cd else clusters += mutableListOf(cd)
        }
        fun mean(g: List<Cand>) = g.sumOf { it.price * it.weight } / g.sumOf { it.weight }
        fun strength(g: List<Cand>) = g.sumOf { it.weight } * (1 - 0.12 * max(0.0, s * (price - mean(g))) / atr)
        val best = clusters.maxByOrNull { strength(it) }?.takeIf { strength(it) >= 2.0 }

        val volSurge = ctx.hasVolume && run {
            val avg = (i - 20 until i).map { c[it].volume.toDouble() }.average()
            avg > 0 && c[i].volume / avg >= 1.3
        }

        val zoneLow: Double
        val zoneHigh: Double
        val level: Double
        val conf: List<EntryLevel>
        if (best != null) {
            var lo = best.minOf { it.price }
            var hi = best.maxOf { it.price }
            if (hi - lo < 0.2 * atr) { lo -= 0.1 * atr; hi += 0.1 * atr }
            zoneLow = tick(lo); zoneHigh = tick(hi); level = tick(mean(best))
            conf = best.distinctBy { it.name }.map { EntryLevel(it.name, tick(it.price)) }
        } else {
            // No confluence: fall back to a shallow ATR pullback.
            level = tick(price - s * 0.5 * atr)
            zoneLow = tick(min(price, price - s * 0.75 * atr)); zoneHigh = tick(max(price, price - s * 0.75 * atr))
            conf = emptyList()
        }
        val dist = s * (price - level) / atr      // ATRs price must travel to reach the level
        val confText = if (conf.isEmpty()) "no strong level nearby" else conf.joinToString { it.name }

        if (!long) {
            val inZone = price >= zoneLow - 0.35 * atr
            return EntryAdvice(
                EntryType.AVOID, if (inZone) tick(price) else level, zoneLow, zoneHigh,
                "Avoid fresh buying",
                "Sell rating. If you hold it, consider exiting or tightening your stop. For intraday/F&O shorts, the best zone to sell is ₹${fmt(zoneLow)}–₹${fmt(zoneHigh)} ($confText).",
                conf,
                "The bearish view is invalid if price reclaims ₹${fmt(max(zoneHigh, ctx.ema50[i] ?: zoneHigh))} on a daily close.",
            )
        }

        return when {
            r.signal == Signal.HOLD -> {
                val trigger = max(high20, levels.filter { it.price > price * 1.005 }.minByOrNull { it.price }?.price ?: high20)
                EntryAdvice(
                    EntryType.WAIT, level, zoneLow, zoneHigh,
                    "Wait: no clear edge yet",
                    "Signals are mixed. The best area to buy, if the setup improves, is ₹${fmt(zoneLow)}–₹${fmt(zoneHigh)} ($confText).",
                    conf,
                    "Buy only after a daily close above ₹${fmt(trigger)} with good volume, or a bounce from ₹${fmt(zoneLow)}–₹${fmt(zoneHigh)} with a bullish candle.",
                )
            }
            brokeOut && volSurge && dist > 1.5 -> EntryAdvice(
                EntryType.BREAKOUT, tick(price), tick(high20), tick(price),
                "Buy the breakout near ₹${fmt(price)}",
                "Fresh 20-day breakout on heavy volume. Strong breakouts often don't pull back far. If you'd rather wait, the retest level is ₹${fmt(high20)}.",
                conf + EntryLevel("Breakout level", tick(high20)),
                "If price falls back below ₹${fmt(high20)} the breakout has failed.",
            )
            dist <= 0.35 -> EntryAdvice(
                EntryType.BUY_NOW, tick(min(price, zoneHigh.coerceAtLeast(price - 0.1 * atr))), zoneLow, zoneHigh,
                "Buy now: price is in the entry zone",
                "Price is sitting on the best support cluster: ${confText}. Entering here keeps the stop-loss tight.",
                conf, null,
            )
            else -> EntryAdvice(
                EntryType.PULLBACK, level, zoneLow, zoneHigh,
                "Wait for a pullback to ₹${fmt(level)}",
                "Price is ${fmt(dist, 1)} ATR above the best entry zone ₹${fmt(zoneLow)}–₹${fmt(zoneHigh)} ($confText). A limit order there gives a much better risk-reward than buying now.",
                conf,
                "Place a limit buy near ₹${fmt(level)}. Cancel it if the stock closes below ₹${fmt(zoneLow - 0.5 * atr)} before filling.",
            )
        }
    }

    // ---------------------------------------------------------------- plan

    private fun buildPlan(
        dir: Direction, e: EntryAdvice, atr: Double, realLevel: Double?, ema20: Double, r: Scored,
        capital: Double, riskPercent: Double,
    ): TradePlan {
        val long = dir == Direction.LONG
        val entry = tick(e.price)
        val stop = if (long) {
            val base = e.zoneLow - 0.5 * atr
            max(min(base, entry - 1.5 * atr), entry - 3 * atr)
        } else {
            val base = e.zoneHigh + 0.5 * atr
            min(max(base, entry + 1.5 * atr), entry + 3 * atr)
        }
        val sl = tick(stop)
        val risk = abs(entry - sl).coerceAtLeast(0.05)
        val sign = if (long) 1 else -1
        val targets = listOf(1.5 to "Target 1", 2.5 to "Target 2", 4.0 to "Target 3").map { (m, label) ->
            val t = tick(max(entry + sign * m * risk, 0.05))
            Target(label, t, (t - entry) / entry * 100)
        }
        val rrLevel = realLevel?.let { abs(it - entry) / risk }
        val qty = min(floor(capital * riskPercent / 100 / risk), floor(capital / entry)).toInt().coerceAtLeast(0)
        val holding = when {
            r.adx >= 25 && r.weeklyUp -> "Positional swing, roughly 2–6 weeks"
            r.adx >= 25 -> "Swing trade, roughly 1–4 weeks"
            else -> "Short-term, roughly 3–10 trading days"
        }
        return TradePlan(
            direction = dir,
            entryLow = e.zoneLow, entryHigh = e.zoneHigh, entry = entry,
            stopLoss = sl, stopPct = (sl - entry) / entry * 100,
            targets = targets,
            riskPerShare = risk,
            rewardRiskT2 = 2.5,
            nearestLevel = realLevel?.let { tick(it) },
            rewardRiskToLevel = rrLevel,
            quantity = qty,
            capitalRequired = qty * entry,
            maxLoss = qty * risk,
            profitAtT2 = qty * 2.5 * risk,
            holding = holding,
            trailing = if (long)
                "Book about a third at Target 1 and move the stop-loss to entry. Trail the rest below the 20 EMA (now ₹${fmt(ema20)}) or the Supertrend line."
            else "Cover about a third at Target 1 and move the stop-loss to entry. Trail the rest above the 20 EMA (now ₹${fmt(ema20)}).",
            invalidation = if (long) "The setup is invalid if the stock closes below ₹${fmt(sl)}. Exit without waiting."
            else "The setup is invalid if the stock closes above ₹${fmt(sl)}. Exit without waiting.",
        )
    }

    // ---------------------------------------------------------------- backtest & confidence

    private fun backtest(ctx: Ctx): Backtest? {
        val c = ctx.c
        val start = 150
        val end = ctx.n - 2
        if (end - start < 40) return null

        fun simulate(i: Int): Pair<Double, Int>? {   // return fraction and bars held, null if still open
            val a = ctx.atr[i] ?: return null
            val entry = c[i + 1].open
            val stop = entry - BT_STOP_ATR * a
            val target = entry + BT_TARGET_ATR * a
            for (j in i + 1 until ctx.n) {
                val k = c[j]
                if (k.low <= stop) return (min(k.open, stop) / entry - 1) to (j - i)
                if (k.high >= target) return (max(k.open, target) / entry - 1) to (j - i)
                if (j - i >= BT_MAX_BARS) return (k.close / entry - 1) to (j - i)
            }
            return null
        }

        var baseWins = 0
        var baseN = 0
        for (i in start..end) simulate(i)?.let { (ret, _) -> baseN++; if (ret > 0) baseWins++ }

        val rets = mutableListOf<Double>()
        var held = 0
        var i = start
        while (i <= end) {
            val sig = score(ctx, i).signal
            if (sig.isBuy) {
                val res = simulate(i) ?: break
                rets += res.first
                held += res.second
                i += res.second + 1
            } else i++
        }
        val wins = rets.count { it > 0 }
        val gains = rets.filter { it > 0 }.sum()
        val losses = -rets.filter { it < 0 }.sum()
        var equity = 1.0
        rets.forEach { equity *= 1 + it }
        return Backtest(
            trades = rets.size,
            wins = wins,
            winRate = if (rets.isEmpty()) 0.0 else wins.toDouble() / rets.size,
            baseWinRate = if (baseN == 0) 0.0 else baseWins.toDouble() / baseN,
            avgReturnPct = if (rets.isEmpty()) 0.0 else rets.average() * 100,
            profitFactor = if (losses > 0) gains / losses else null,
            avgHoldDays = if (rets.isEmpty()) 0.0 else held.toDouble() / rets.size,
            totalReturnPct = (equity - 1) * 100,
            buyHoldPct = (c[ctx.n - 1].close / c[start].close - 1) * 100,
            periodDays = ctx.n - 1 - start,
        )
    }

    private fun confidence(r: Scored, bt: Backtest?): Int {
        val dir = sign(r.score.toDouble())
        val totalW = r.pillars.sumOf { it.pillar.weight }
        val agree = if (dir == 0.0 || totalW == 0.0) 0.0
        else r.pillars.filter { sign(it.score.toDouble()) == dir && abs(it.score) >= 10 }.sumOf { it.pillar.weight } / totalW
        val strength = min(abs(r.score) / 60.0, 1.0)
        val history = if (bt != null && bt.trades >= 5) (0.5 + (bt.winRate - bt.baseWinRate) * 2.5).coerceIn(0.0, 1.0) else 0.5
        return (100 * (0.40 * agree + 0.35 * strength + 0.25 * history)).roundToInt().coerceIn(0, 100)
    }

    private fun summary(r: Scored, e: EntryAdvice, confidence: Int): String {
        val ranked = r.pillars.sortedByDescending { it.score }
        val best = ranked.firstOrNull()
        val worst = ranked.lastOrNull()
        val lead = when (r.signal) {
            Signal.STRONG_BUY -> "Strong buy: trend, momentum and money flow line up."
            Signal.BUY -> "Buy: the evidence leans bullish."
            Signal.HOLD -> "Hold: signals are mixed, there's no clear edge right now."
            Signal.SELL -> "Sell: the evidence leans bearish."
            Signal.STRONG_SELL -> "Strong sell: trend and momentum are firmly down."
        }
        val parts = buildString {
            append(lead)
            if (best != null && worst != null && best != worst)
                append(" Strongest: ${best.pillar.label} (${signed(best.score)}). Weakest: ${worst.pillar.label} (${signed(worst.score)}).")
            append(" Confidence $confidence%. ")
            append(e.headline).append('.')
        }
        return parts
    }

    // ---------------------------------------------------------------- utils

    /** NSE tick size is ₹0.05. */
    private fun tick(x: Double) = (x / 0.05).roundToLong() * 0.05

    private fun fmt(v: Double, decimals: Int = 2) = String.format(java.util.Locale.US, "%,.${decimals}f", v)
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
    private fun signedPct(v: Double) = (if (v >= 0) "+" else "") + fmt(v, 1) + "%"
    private fun pct(v: Double) = fmt(v * 100, 0) + "%"
}
