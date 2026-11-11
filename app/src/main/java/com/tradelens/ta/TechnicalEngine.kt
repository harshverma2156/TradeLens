package com.tradelens.ta

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.Timeframe
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Computes every indicator for one timeframe and interprets it.
 *
 *  - Only CLOSED bars feed the signals. A bar that is still forming is returned separately for the chart.
 *  - All series are causal (bar i uses bars ≤ i), so adding later bars never changes earlier values.
 *  - Each timeframe is computed independently from its own bars; nothing is mixed across timeframes.
 *  - The summary votes by indicator family, so correlated indicators can't outvote independent evidence,
 *    and Buy/Sell needs at least [MIN_AGREEING_GROUPS] families agreeing.
 */
object TechnicalEngine {
    val EMA_PERIODS = listOf(9, 20, 21, 50, 100, 200)
    val SMA_PERIODS = listOf(50, 100, 200)
    const val MIN_AGREEING_GROUPS = 3
    const val SUMMARY_THRESHOLD = 20

    /** When does the bar opened at [t] finish? Used to tell closed bars from the one still forming. */
    fun barEnd(t: Long, tf: Timeframe): Long {
        val d = MarketCalendar.date(t)
        val close = MarketCalendar.at(d, MarketCalendar.CLOSE)
        return when (tf) {
            Timeframe.D1 -> close
            Timeframe.W1 -> MarketCalendar.at(d.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)), MarketCalendar.CLOSE)
            Timeframe.MN1 -> MarketCalendar.at(d.with(TemporalAdjusters.lastDayOfMonth()), MarketCalendar.CLOSE)
            else -> min(t + tf.millis, close)
        }
    }

    fun analyze(symbol: String, tf: Timeframe, all: List<Candle>, nowMs: Long, isIndex: Boolean = symbol.startsWith("^")): TechnicalReport {
        val sorted = all.sortedBy { it.time }
        val forming = sorted.lastOrNull()?.takeIf { nowMs < barEnd(it.time, tf) }
        val c = if (forming != null) sorted.dropLast(1) else sorted
        return compute(symbol, tf, c, forming, nowMs, isIndex)
    }

    private fun compute(symbol: String, tf: Timeframe, c: List<Candle>, forming: Candle?, now: Long, isIndex: Boolean): TechnicalReport {
        val n = c.size
        val i = n - 1
        val close = c.map { it.close }
        val hasVolume = !isIndex && c.takeLast(30).count { it.volume > 0 } >= 15

        // ---- series
        val ema = EMA_PERIODS.associateWith { Rolling.ema(close, it) }
        val sma = SMA_PERIODS.associateWith { Rolling.sma(close, it) }
        val atr = VolatilityCalc.atr(c, 14)
        val st = TrendCalc.supertrend(c, 10, 3.0)
        val dmi = TrendCalc.dmi(c, 14)
        val ich = TrendCalc.ichimoku(c)
        val psar = TrendCalc.psar(c)
        val rsi = MomentumCalc.rsi(close, 14)
        val macd = MomentumCalc.macd(close)
        val stoch = MomentumCalc.stochastic(c)
        val srsi = MomentumCalc.stochRsi(close)
        val cci = MomentumCalc.cci(c)
        val wr = MomentumCalc.williamsR(c)
        val bb = VolatilityCalc.bollinger(close)
        val kc = VolatilityCalc.keltner(c)
        val dc = VolatilityCalc.donchian(c)
        val obv = VolumeCalc.obv(c)
        val adl = VolumeCalc.accumulationDistribution(c)
        val mfi = VolumeCalc.mfi(c)
        val vwap = VolumeCalc.sessionVwap(c, tf.intraday)
        val swing = LevelsCalc.dominantSwing(c, 126)
        val anchor = swing?.let { if (it.up) it.lowIndex else it.highIndex }
        val avwap = anchor?.let { VolumeCalc.anchoredVwap(c, it) } ?: List(n) { null }
        val profile = if (hasVolume) VolumeCalc.volumeProfile(c.takeLast(min(n, 126))) else null
        val pivots = LevelsCalc.pivotsFor(c, tf)
        val sr = LevelsCalc.supportResistance(c.takeLast(min(n, 120)))
        val px = close.lastOrNull() ?: 0.0
        val supports = sr.filter { it.price < px * 0.995 }.sortedByDescending { it.price }.map { it.price }.take(3)
        val resistances = sr.filter { it.price > px * 1.005 }.sortedBy { it.price }.map { it.price }.take(3)
        val cloudA = List(n) { ich.cloudA(it) }
        val cloudB = List(n) { ich.cloudB(it) }

        val overlays = Overlays(ema, sma, bb, kc, dc, st, psar, ich.tenkan, ich.kijun, cloudA, cloudB, vwap, avwap, anchor, pivots,
            swing?.let { LevelsCalc.fibRetracement(it) }.orEmpty(), swing?.let { LevelsCalc.fibExtension(it) }.orEmpty(), profile, supports, resistances)
        val osc = Oscillators(rsi, macd, stoch, srsi, cci, wr, dmi, mfi, obv, adl, atr)

        // ---- readings
        val r = Readings(tf, now, c.lastOrNull()?.time, n)
        val a = atr.getOrNull(i) ?: c.lastOrNull()?.let { (it.high - it.low).takeIf { x -> x > 0 } } ?: max(px * 0.01, 1e-9)
        if (n >= 1) {
            for (p in EMA_PERIODS) r.ma("ema$p", "EMA $p", ema[p]!!, i, px, a, p, ema = true)
            for (p in SMA_PERIODS) r.ma("sma$p", "SMA $p", sma[p]!!, i, px, a, p, ema = false)
            r.supertrend(st, i, px, a)
            r.psar(psar, i, px, a)
            r.ichimoku(ich, c, i, px)
            r.adx(dmi, i)
            r.rsi(rsi, i)
            r.macd(macd, i, a)
            r.stoch(stoch, i)
            r.stochRsi(srsi, i)
            r.cci(cci, i)
            r.williams(wr, i)
            r.atr(atr, i, px)
            r.bollinger(bb, close, i)
            r.keltner(kc, i, px)
            r.donchian(dc, i, px)
            r.obv(obv, close, i, hasVolume)
            r.adLine(adl, i, hasVolume)
            r.mfi(mfi, i, hasVolume)
            r.vwap(vwap, i, px, a, tf, hasVolume)
            r.avwap(avwap, i, px, a, swing, c, hasVolume)
            r.profile(profile, px, hasVolume)
            r.pivots(pivots, px)
            r.fib(swing, px, a)
            r.sr(supports, resistances, px, a)
        }
        val readings = r.out

        // ---- family votes and summary
        val groups = SignalGroup.entries.filter { it.votes }.map { g ->
            val members = readings.filter { it.group == g }
            val ready = members.filter { it.ready }
            val score = if (ready.isEmpty()) 0 else ready.map { it.signedScore }.average().roundToInt()
            GroupVote(g, score, biasOf(score, 15), members.map { it.name }, ready.size)
        }
        val summary = summarize(groups)
        return TechnicalReport(symbol, tf, now, c, forming, readings, groups, summary, overlays, osc)
    }

    /**
     * Family vote → summary. Weighted average of family scores (families with no ready member drop out and
     * the weights re-normalise). Buy needs: score ≥ [SUMMARY_THRESHOLD], at least [MIN_AGREEING_GROUPS] bullish
     * families, and bullish families outnumbering bearish ones by 2+. Sell mirrored. Otherwise Neutral.
     */
    fun summarize(groups: List<GroupVote>): TechnicalSummary {
        val voting = groups.filter { it.group.votes && it.ready > 0 }
        val wSum = voting.sumOf { it.group.weight }
        val total = if (wSum == 0.0) 0 else (voting.sumOf { it.score * it.group.weight } / wSum).roundToInt()
        val bulls = voting.count { it.bias == Bias.BULLISH }
        val bears = voting.count { it.bias == Bias.BEARISH }
        val bias = when {
            total >= SUMMARY_THRESHOLD && bulls >= MIN_AGREEING_GROUPS && bulls > bears + 1 -> Bias.BULLISH
            total <= -SUMMARY_THRESHOLD && bears >= MIN_AGREEING_GROUPS && bears > bulls + 1 -> Bias.BEARISH
            else -> Bias.NEUTRAL
        }
        val label = when (bias) { Bias.BULLISH -> "Buy"; Bias.BEARISH -> "Sell"; Bias.NEUTRAL -> "Neutral" }
        val all = groups.count { it.group.votes }
        val explanation = buildString {
            append("$bulls of ${voting.size} independent indicator families lean bullish, $bears bearish. ")
            append("Correlated indicators share one vote per family, and a Buy/Sell needs at least $MIN_AGREEING_GROUPS families agreeing, so no single indicator decides the result. ")
            if (bias == Bias.NEUTRAL && abs(total) >= SUMMARY_THRESHOLD) append("The weighted score leans ${if (total > 0) "bullish" else "bearish"}, but not enough families agree. ")
            if (voting.size < all) append("${all - voting.size} families lack enough history on this timeframe. ")
            append("Technical signals only; not investment advice.")
        }
        return TechnicalSummary(bias, label, total, bulls, bears, voting.size, explanation)
    }

    fun biasOf(score: Int, threshold: Int) = when { score >= threshold -> Bias.BULLISH; score <= -threshold -> Bias.BEARISH; else -> Bias.NEUTRAL }

    // ------------------------------------------------------------------ interpretation

    private class Readings(val tf: Timeframe, val now: Long, val barTime: Long?, val bars: Int) {
        val out = mutableListOf<IndicatorReading>()

        fun add(
            id: String, name: String, cat: IndicatorCategory, group: SignalGroup, value: Double?, display: String,
            bias: Bias, strength: Int, explanation: String, formula: String, params: String, warmup: Int,
            components: List<Pair<String, Double?>> = emptyList(), ready: Boolean = value != null,
        ) {
            out += if (!ready) IndicatorReading(id, name, cat, group, tf, null, "—", components, Bias.NEUTRAL, 0,
                "Not enough history: needs about $warmup ${tf.label} bars, has $bars.", formula, params, warmup, false, barTime, now)
            else IndicatorReading(id, name, cat, group, tf, value, display, components, bias, strength.coerceIn(0, 100), explanation, formula, params, warmup, true, barTime, now)
        }

        fun ma(id: String, name: String, s: List<Double?>, i: Int, px: Double, atr: Double, p: Int, ema: Boolean) {
            val v = s.getOrNull(i)
            val prev = s.getOrNull(i - 5)
            val slope = if (v != null && prev != null) v - prev else 0.0
            val dist = if (v != null) (px - v) / atr else 0.0
            val bias = when { v == null -> Bias.NEUTRAL; abs(dist) < 0.1 -> Bias.NEUTRAL; dist > 0 -> Bias.BULLISH; else -> Bias.BEARISH }
            val agrees = (bias == Bias.BULLISH && slope > 0) || (bias == Bias.BEARISH && slope < 0)
            add(id, name, IndicatorCategory.TREND, SignalGroup.MOVING_AVERAGES, v, f(v),
                bias, (min(abs(dist), 3.0) * 20 + if (agrees) 30 else 0).toInt(),
                if (v == null) "" else "Close ${f(px)} is ${f(abs(dist), 1)} ATR ${if (dist >= 0) "above" else "below"} the $name (${f(v)}), which is ${if (slope > 0) "rising" else if (slope < 0) "falling" else "flat"}.",
                if (ema) "EMAₜ = α·Cₜ + (1 − α)·EMAₜ₋₁, α = 2/(n+1). Seeded with the SMA of the first n closes (TA-Lib convention). " +
                    "pandas ewm(adjust=False) seeds with the first close instead; the difference decays as (1 − α)^k."
                else "SMAₜ = (Cₜ + Cₜ₋₁ + … + Cₜ₋ₙ₊₁) / n.",
                "n = $p", p)
        }

        fun supertrend(s: TrendCalc.Supertrend, i: Int, px: Double, atr: Double) {
            val v = s.line.getOrNull(i); val up = s.up.getOrNull(i)
            val flipped = (max(1, i - 4)..i).any { k -> s.up.getOrNull(k) != null && s.up.getOrNull(k - 1) != null && s.up[k] != s.up[k - 1] }
            add("supertrend", "Supertrend (10, 3)", IndicatorCategory.TREND, SignalGroup.TREND_SYSTEMS, v, f(v),
                if (up == true) Bias.BULLISH else Bias.BEARISH, (40 + min(abs(px - (v ?: px)) / atr, 3.0) * 20).toInt(),
                "Trend is ${if (up == true) "up; the line at ${f(v)} is a trailing stop below price" else "down; price must close above ${f(v)} to flip it"}${if (flipped) ". It flipped within the last 5 bars." else "."}",
                "Basic bands = (H+L)/2 ± k·ATR(n). The final upper band only steps down (unless the prior close broke above it); the final lower band only steps up. " +
                    "The trend flips when the close crosses the opposite final band. ATR uses Wilder smoothing.",
                "ATR n = 10, k = 3", 11, ready = v != null && up != null)
        }

        fun psar(s: TrendCalc.Psar, i: Int, px: Double, atr: Double) {
            val v = s.value.getOrNull(i); val up = s.up.getOrNull(i)
            add("psar", "Parabolic SAR", IndicatorCategory.TREND, SignalGroup.TREND_SYSTEMS, v, f(v),
                if (up == true) Bias.BULLISH else Bias.BEARISH, (35 + min(abs(px - (v ?: px)) / atr, 3.0) * 20).toInt(),
                if (up == true) "SAR ${f(v)} is below price: uptrend. A low under it would reverse the trend." else "SAR ${f(v)} is above price: downtrend. A high over it would reverse the trend.",
                "SARₜ = SARₜ₋₁ + AF·(EP − SARₜ₋₁). EP = the extreme point of the current trend. AF starts at 0.02 and rises 0.02 with each new EP, capped at 0.2. " +
                    "In an uptrend SAR may not exceed the prior two lows (mirrored in a downtrend). A cross of SAR reverses the trend, SAR resets to EP and AF to 0.02. " +
                    "Initialisation is path-dependent across libraries: here the trend starts long at bar 2, as in the `ta` reference.",
                "step 0.02, max 0.2", 3, ready = v != null && up != null)
        }

        fun ichimoku(ich: TrendCalc.Ichimoku, c: List<Candle>, i: Int, px: Double) {
            val t = ich.tenkan.getOrNull(i); val k = ich.kijun.getOrNull(i)
            val ca = ich.cloudA(i); val cb = ich.cloudB(i)
            val ready = t != null && k != null && ca != null && cb != null && i >= 26
            var score = 0
            val notes = mutableListOf<String>()
            if (ready) {
                val top = max(ca!!, cb!!); val bot = min(ca, cb)
                when { px > top -> { score++; notes += "price above the cloud" }; px < bot -> { score--; notes += "price below the cloud" }; else -> notes += "price inside the cloud (no trend)" }
                if (t!! > k!!) { score++; notes += "Tenkan above Kijun" } else if (t < k) { score--; notes += "Tenkan below Kijun" }
                val lag = c[i - 26].close
                if (px > lag) { score++; notes += "Chikou above price 26 bars ago" } else if (px < lag) { score--; notes += "Chikou below price 26 bars ago" }
                val fa = ich.spanA[i]; val fb = ich.spanB[i]
                if (fa != null && fb != null) { if (fa > fb) { score++; notes += "future cloud bullish" } else if (fa < fb) { score--; notes += "future cloud bearish" } }
            }
            add("ichimoku", "Ichimoku Cloud", IndicatorCategory.TREND, SignalGroup.TREND_SYSTEMS, t, "Tenkan ${f(t)} · Kijun ${f(k)}",
                biasOf(score, 1), abs(score) * 25, notes.joinToString("; ").replaceFirstChar { it.uppercase() } + ".",
                "Tenkan = (HH₉ + LL₉)/2; Kijun = (HH₂₆ + LL₂₆)/2; Senkou A = (Tenkan + Kijun)/2 and Senkou B = (HH₅₂ + LL₅₂)/2, both plotted 26 bars ahead; " +
                    "Chikou = close plotted 26 bars back. The cloud for bar t uses spans computed at t − 26, so no future data is used. " +
                    "Score = +1 / −1 for each of: price vs cloud, Tenkan vs Kijun, Chikou vs price, colour of the projected cloud.",
                "9 / 26 / 52, displacement 26", 78, listOf("Kijun" to k, "Cloud A" to ca, "Cloud B" to cb), ready)
        }

        fun adx(d: TrendCalc.Dmi, i: Int) {
            val v = d.adx.getOrNull(i); val p = d.plusDi.getOrNull(i); val m = d.minusDi.getOrNull(i)
            val dir = if ((p ?: 0.0) >= (m ?: 0.0)) Bias.BULLISH else Bias.BEARISH
            val trending = (v ?: 0.0) >= 20
            add("adx", "ADX / DMI (14)", IndicatorCategory.TREND, SignalGroup.TREND_STRENGTH, v, f(v, 1),
                if (trending) dir else Bias.NEUTRAL, if (trending) min(100.0, (v ?: 0.0) * 2).toInt() else (v ?: 0.0).toInt(),
                when {
                    !trending -> "ADX ${f(v, 1)} < 20: no meaningful trend; directional signals are unreliable."
                    dir == Bias.BULLISH -> "ADX ${f(v, 1)} shows a ${if (v!! >= 40) "very strong" else if (v >= 25) "strong" else "developing"} trend with buyers in control (+DI ${f(p, 1)} > −DI ${f(m, 1)})."
                    else -> "ADX ${f(v, 1)} shows a ${if (v!! >= 40) "very strong" else if (v >= 25) "strong" else "developing"} trend with sellers in control (−DI ${f(m, 1)} > +DI ${f(p, 1)})."
                },
                "+DM = Hₜ − Hₜ₋₁ if it exceeds Lₜ₋₁ − Lₜ and is > 0 (else 0); −DM mirrored. +DI = 100·RMA(+DM)/RMA(TR); −DI likewise. " +
                    "DX = 100·|+DI − −DI|/(+DI + −DI); ADX = RMA(DX). RMA = Wilder smoothing (α = 1/n), SMA-seeded, hence ≈ 2n bars of warm-up. " +
                    "ADX measures trend strength only; direction comes from +DI vs −DI.",
                "n = 14", 28, listOf("+DI" to p, "−DI" to m), ready = v != null && p != null && m != null)
        }

        fun rsi(s: List<Double?>, i: Int) {
            val v = s.getOrNull(i)
            val (b, st, txt) = when {
                v == null -> Triple(Bias.NEUTRAL, 0, "")
                v >= 70 -> Triple(Bias.NEUTRAL, 30, "Overbought (≥ 70): momentum is strong but stretched; a pause or pullback is common.")
                v >= 55 -> Triple(Bias.BULLISH, ((v - 50) * 4).toInt(), "Bullish momentum zone (55–70).")
                v > 45 -> Triple(Bias.NEUTRAL, 0, "Neutral (45–55): no momentum edge.")
                v > 30 -> Triple(Bias.BEARISH, ((50 - v) * 4).toInt(), "Bearish momentum zone (30–45).")
                else -> Triple(Bias.NEUTRAL, 30, "Oversold (≤ 30): selling is stretched; bounces are common but not guaranteed in a downtrend.")
            }
            add("rsi", "RSI (14)", IndicatorCategory.MOMENTUM, SignalGroup.MOMENTUM_OSCILLATORS, v, f(v, 1), b, st, txt,
                "RS = RMA(gains, n) / RMA(losses, n); RSI = 100 − 100/(1 + RS). Wilder smoothing seeded with the simple average of the first n changes. " +
                    "No losses → 100; no movement at all → 50. Variant: pandas/`ta` seed via ewm from the first change; values converge after warm-up.",
                "n = 14; zones 30 / 45 / 55 / 70", 15)
        }

        fun macd(m: MomentumCalc.Macd, i: Int, atr: Double) {
            val l = m.line.getOrNull(i); val s = m.signal.getOrNull(i); val h = m.hist.getOrNull(i); val hp = m.hist.getOrNull(i - 1)
            val bias = if (l != null && s != null) (if (l > s) Bias.BULLISH else if (l < s) Bias.BEARISH else Bias.NEUTRAL) else Bias.NEUTRAL
            val rising = h != null && hp != null && h > hp
            val zeroAgrees = l != null && ((bias == Bias.BULLISH && l > 0) || (bias == Bias.BEARISH && l < 0))
            val strength = 30 + (if (zeroAgrees) 30 else 0) + (if ((bias == Bias.BULLISH) == rising) 20 else 0) + min(20.0, abs(h ?: 0.0) / atr * 100).toInt()
            add("macd", "MACD (12, 26, 9)", IndicatorCategory.MOMENTUM, SignalGroup.MACD, l, f(l), bias, strength,
                "MACD ${if (bias == Bias.BULLISH) "above" else "below"} its signal line and ${if ((l ?: 0.0) > 0) "above" else "below"} zero; histogram ${if (rising) "rising" else "falling"}.",
                "MACD = EMA₁₂(C) − EMA₂₆(C); signal = EMA₉(MACD); histogram = MACD − signal. EMAs are SMA-seeded.",
                "12 / 26 / 9", 34, listOf("Signal" to s, "Histogram" to h), ready = l != null && s != null)
        }

        fun stoch(s: MomentumCalc.Stoch, i: Int) {
            val k = s.k.getOrNull(i); val d = s.d.getOrNull(i)
            val (b, st, txt) = zone(k, d, 80.0, 20.0, "%K")
            add("stoch", "Stochastic (14, 3, 3)", IndicatorCategory.MOMENTUM, SignalGroup.MOMENTUM_OSCILLATORS, k, "%K ${f(k, 1)} · %D ${f(d, 1)}", b, st, txt,
                "Fast %K = 100·(C − LL₁₄)/(HH₁₄ − LL₁₄); slow %K = SMA₃(fast %K); %D = SMA₃(slow %K). A flat range (HH = LL) is defined as 50.",
                "14 / 3 / 3; zones 20 / 80", 18, listOf("%D" to d), ready = k != null && d != null)
        }

        fun stochRsi(s: MomentumCalc.Stoch, i: Int) {
            val k = s.k.getOrNull(i); val d = s.d.getOrNull(i)
            val (b, st, txt) = zone(k, d, 80.0, 20.0, "%K")
            add("stochrsi", "Stochastic RSI (14, 14, 3, 3)", IndicatorCategory.MOMENTUM, SignalGroup.MOMENTUM_OSCILLATORS, k, "%K ${f(k, 1)} · %D ${f(d, 1)}", b, st, txt,
                "Raw = 100·(RSI − min RSI₁₄)/(max RSI₁₄ − min RSI₁₄); %K = SMA₃(raw); %D = SMA₃(%K). Scaled 0–100 (`ta` uses 0–1). A flat RSI window is defined as 50.",
                "RSI 14, window 14, 3 / 3", 33, listOf("%D" to d), ready = k != null && d != null)
        }

        private fun zone(k: Double?, d: Double?, ob: Double, os: Double, name: String): Triple<Bias, Int, String> = when {
            k == null || d == null -> Triple(Bias.NEUTRAL, 0, "")
            k >= ob -> Triple(Bias.NEUTRAL, 25, "Overbought: $name ${f(k, 1)} ≥ ${ob.toInt()}. Momentum is strong but stretched.")
            k <= os -> Triple(Bias.NEUTRAL, 25, "Oversold: $name ${f(k, 1)} ≤ ${os.toInt()}. Selling is stretched.")
            k > d -> Triple(Bias.BULLISH, (30 + min(40.0, k - d) + max(0.0, k - 50) / 2).toInt(), "$name is above %D and rising out of the middle: bullish momentum.")
            k < d -> Triple(Bias.BEARISH, (30 + min(40.0, d - k) + max(0.0, 50 - k) / 2).toInt(), "$name is below %D: bearish momentum.")
            else -> Triple(Bias.NEUTRAL, 0, "$name equals %D: no edge.")
        }

        fun cci(s: List<Double?>, i: Int) {
            val v = s.getOrNull(i)
            val b = when { v == null -> Bias.NEUTRAL; v >= 100 -> Bias.BULLISH; v <= -100 -> Bias.BEARISH; v > 20 -> Bias.BULLISH; v < -20 -> Bias.BEARISH; else -> Bias.NEUTRAL }
            add("cci", "CCI (20)", IndicatorCategory.MOMENTUM, SignalGroup.MOMENTUM_OSCILLATORS, v, f(v, 0), b, min(100.0, abs(v ?: 0.0) / 2).toInt(),
                when { v == null -> ""; v >= 100 -> "Above +100: unusually strong upward momentum versus the 20-bar average."; v <= -100 -> "Below −100: unusually strong downward momentum."; else -> "Within ±100: normal range." },
                "TP = (H+L+C)/3; CCI = (TP − SMA₂₀(TP)) / (0.015·MeanDev₂₀(TP)), MeanDev = mean |TP − SMA|. Lambert's 0.015 puts ~70–80% of values within ±100. Zero deviation (flat) → 0.",
                "n = 20, constant 0.015", 20)
        }

        fun williams(s: List<Double?>, i: Int) {
            val v = s.getOrNull(i)
            val (b, st, txt) = when {
                v == null -> Triple(Bias.NEUTRAL, 0, "")
                v >= -20 -> Triple(Bias.NEUTRAL, 25, "Overbought (≥ −20): the close is near the top of its 14-bar range.")
                v <= -80 -> Triple(Bias.NEUTRAL, 25, "Oversold (≤ −80): the close is near the bottom of its 14-bar range.")
                v > -50 -> Triple(Bias.BULLISH, ((v + 50) * 2).toInt(), "Upper half of the range: buyers have the edge.")
                else -> Triple(Bias.BEARISH, ((-50 - v) * 2).toInt(), "Lower half of the range: sellers have the edge.")
            }
            add("williams", "Williams %R (14)", IndicatorCategory.MOMENTUM, SignalGroup.MOMENTUM_OSCILLATORS, v, f(v, 1), b, st, txt,
                "%R = −100·(HH₁₄ − C)/(HH₁₄ − LL₁₄), range −100 to 0 (= fast stochastic − 100). Flat range → −50.", "n = 14; zones −80 / −20", 14)
        }

        fun atr(s: List<Double?>, i: Int, px: Double) {
            val v = s.getOrNull(i)
            val pct = if (v != null && px > 0) v / px * 100 else null
            val hist = s.filterNotNull().takeLast(120)
            val rank = if (v != null && hist.size > 20) hist.count { it <= v } * 100 / hist.size else null
            add("atr", "ATR (14)", IndicatorCategory.VOLATILITY, SignalGroup.CONTEXT, v, "${f(v)} (${f(pct, 2)}%)", Bias.NEUTRAL, rank ?: 0,
                "Average bar range is ${f(pct, 2)}% of price${rank?.let { ", higher than $it% of the last 120 bars" } ?: ""}. Used to size stops and to normalise other readings; it has no direction.",
                "TRₜ = max(H − L, |H − Cₜ₋₁|, |L − Cₜ₋₁|); ATR = RMA₁₄(TR), seeded with the mean of the first 14 TRs (Wilder). Matches `ta` exactly.",
                "n = 14", 14)
        }

        fun bollinger(b: VolatilityCalc.Bands, close: List<Double>, i: Int) {
            val pb = b.percentB(close).getOrNull(i)
            val width = b.width()
            val w = width.getOrNull(i)
            val recent = width.filterNotNull().takeLast(120)
            val squeeze = w != null && recent.size > 40 && w <= recent.sorted()[recent.size / 10]
            val bias = when { pb == null -> Bias.NEUTRAL; pb > 0.55 -> Bias.BULLISH; pb < 0.45 -> Bias.BEARISH; else -> Bias.NEUTRAL }
            add("bollinger", "Bollinger Bands (20, 2)", IndicatorCategory.VOLATILITY, SignalGroup.VOLATILITY_CHANNELS, pb?.let { it * 100 }, "%B ${f(pb?.let { it * 100 }, 0)}",
                bias, min(100.0, abs((pb ?: 0.5) - 0.5) * 120).toInt(),
                when {
                    pb == null -> "Bands have zero width (flat prices)."
                    pb > 1 -> "Closed above the upper band: strong upside momentum, but statistically stretched."
                    pb < 0 -> "Closed below the lower band: strong downside momentum, but statistically stretched."
                    pb > 0.55 -> "Upper half of the bands."
                    pb < 0.45 -> "Lower half of the bands."
                    else -> "Near the middle band."
                } + if (squeeze) " Bandwidth is in its lowest 10% of the last 120 bars (a squeeze): a larger move often follows." else "",
                "Middle = SMA₂₀(C); bands = middle ± 2·σ₂₀ (population σ, ddof = 0). %B = (C − lower)/(upper − lower); width = (upper − lower)/middle.",
                "n = 20, k = 2", 20, listOf("Upper" to b.upper.getOrNull(i), "Middle" to b.mid.getOrNull(i), "Lower" to b.lower.getOrNull(i), "Width %" to w?.let { it * 100 }),
                ready = b.mid.getOrNull(i) != null)
        }

        fun keltner(k: VolatilityCalc.Bands, i: Int, px: Double) {
            val u = k.upper.getOrNull(i); val l = k.lower.getOrNull(i); val m = k.mid.getOrNull(i)
            val bias = when { m == null -> Bias.NEUTRAL; px > (u ?: Double.MAX_VALUE) -> Bias.BULLISH; px < (l ?: -1.0) -> Bias.BEARISH; px > m -> Bias.BULLISH; px < m -> Bias.BEARISH; else -> Bias.NEUTRAL }
            val outside = u != null && l != null && (px > u || px < l)
            add("keltner", "Keltner Channels (20, 10, 2)", IndicatorCategory.VOLATILITY, SignalGroup.VOLATILITY_CHANNELS, m, f(m), bias, if (outside) 80 else 30,
                when { u == null || l == null -> ""; px > u -> "Closed above the upper channel: a volatility breakout to the upside."; px < l -> "Closed below the lower channel: a volatility breakdown."; else -> "Inside the channel, ${if (px > (m ?: px)) "above" else "below"} the middle." },
                "Middle = EMA₂₀(C); channels = middle ± 2·ATR₁₀ (modern Raschke form). Keltner's 1960 original used SMA of typical price ± SMA(H − L).",
                "EMA 20, ATR 10, ×2", 20, listOf("Upper" to u, "Lower" to l), ready = m != null && u != null && l != null)
        }

        fun donchian(d: VolatilityCalc.Bands, i: Int, px: Double) {
            val u = d.upper.getOrNull(i); val l = d.lower.getOrNull(i)
            val pu = d.upper.getOrNull(i - 1); val pl = d.lower.getOrNull(i - 1)
            val pos = if (u != null && l != null && u > l) (px - l) / (u - l) else null
            val breakout = pu != null && px > pu
            val breakdown = pl != null && px < pl
            val bias = when { breakout -> Bias.BULLISH; breakdown -> Bias.BEARISH; pos == null -> Bias.NEUTRAL; pos > 0.6 -> Bias.BULLISH; pos < 0.4 -> Bias.BEARISH; else -> Bias.NEUTRAL }
            add("donchian", "Donchian Channels (20)", IndicatorCategory.VOLATILITY, SignalGroup.VOLATILITY_CHANNELS, pos?.let { it * 100 }, "${f(pos?.let { it * 100 }, 0)}% of channel",
                bias, if (breakout || breakdown) 80 else min(100.0, abs((pos ?: 0.5) - 0.5) * 100).toInt(),
                when { breakout -> "Closed above the prior 20-bar high (${f(pu)}): a breakout (Turtle-style entry signal)."; breakdown -> "Closed below the prior 20-bar low (${f(pl)}): a breakdown."; else -> "Between the 20-bar high ${f(u)} and low ${f(l)}." },
                "Upper = highest high of the last 20 bars; lower = lowest low; middle = (upper + lower)/2. The breakout test compares the close with the PRIOR bar's channel.",
                "n = 20", 21, listOf("Upper" to u, "Lower" to l), ready = u != null && l != null && pu != null)
        }

        fun obv(o: List<Double>, close: List<Double>, i: Int, hasVolume: Boolean) {
            val e = Rolling.ema(o, 20)
            val now = e.getOrNull(i); val then = e.getOrNull(i - 10)
            val up = now != null && then != null && now > then
            val priceUp = i >= 10 && close[i] > close[i - 10]
            val ready = hasVolume && now != null && then != null
            add("obv", "On-Balance Volume", IndicatorCategory.VOLUME, SignalGroup.VOLUME_FLOW, o.getOrNull(i), compact(o.getOrNull(i)),
                if (up) Bias.BULLISH else Bias.BEARISH, if (up == priceUp) 60 else 40,
                when { up && priceUp -> "OBV trend rising with price: volume confirms the advance."; up -> "OBV rising while price falls: possible accumulation (bullish divergence)."; !priceUp -> "OBV falling with price: volume confirms the decline."; else -> "Price rising while OBV falls: the rally lacks volume (bearish divergence)." },
                "OBVₜ = OBVₜ₋₁ + Vₜ if Cₜ > Cₜ₋₁, − Vₜ if Cₜ < Cₜ₋₁, else unchanged; OBV₀ = 0. Only its direction matters: the level depends on the start date. " +
                    "Signal = 10-bar change of EMA₂₀(OBV).",
                "EMA 20, 10-bar slope", 30, ready = ready)
        }

        fun adLine(a: List<Double>, i: Int, hasVolume: Boolean) {
            val now = a.getOrNull(i); val then = a.getOrNull(i - 20)
            val up = now != null && then != null && now > then
            add("adl", "Accumulation/Distribution", IndicatorCategory.VOLUME, SignalGroup.VOLUME_FLOW, now, compact(now),
                if (up) Bias.BULLISH else Bias.BEARISH, 50,
                if (up) "A/D line higher than 20 bars ago: closes tend to land near the highs on volume (accumulation)." else "A/D line lower than 20 bars ago: closes tend to land near the lows on volume (distribution).",
                "CLV = ((C − L) − (H − C))/(H − L) (0 if H = L); ADₜ = ADₜ₋₁ + CLV·Vₜ (Chaikin). Signal = 20-bar change.",
                "20-bar slope", 21, ready = hasVolume && then != null)
        }

        fun mfi(s: List<Double?>, i: Int, hasVolume: Boolean) {
            val v = s.getOrNull(i)
            val (b, st, txt) = when {
                v == null -> Triple(Bias.NEUTRAL, 0, "")
                v >= 80 -> Triple(Bias.NEUTRAL, 30, "Above 80: money flow is overheated.")
                v >= 55 -> Triple(Bias.BULLISH, ((v - 50) * 3).toInt(), "More money flowing in than out.")
                v > 45 -> Triple(Bias.NEUTRAL, 0, "Balanced money flow.")
                v > 20 -> Triple(Bias.BEARISH, ((50 - v) * 3).toInt(), "More money flowing out than in.")
                else -> Triple(Bias.NEUTRAL, 30, "Below 20: selling may be exhausted.")
            }
            add("mfi", "Money Flow Index (14)", IndicatorCategory.VOLUME, SignalGroup.VOLUME_FLOW, v, f(v, 1), b, st, txt,
                "TP = (H+L+C)/3; raw flow = TP·V, positive when TP rose, negative when it fell (ignored if unchanged). MFI = 100 − 100/(1 + ΣPos₁₄/ΣNeg₁₄). " +
                    "No negative flow → 100; no flow at all → 50.",
                "n = 14; zones 20 / 80", 15, ready = hasVolume && v != null)
        }

        fun vwap(s: List<Double?>, i: Int, px: Double, atr: Double, tf: Timeframe, hasVolume: Boolean) {
            val v = s.getOrNull(i)
            val dist = if (v != null) (px - v) / atr else 0.0
            add("vwap", "VWAP (session)", IndicatorCategory.VOLUME, SignalGroup.VWAP, v, f(v),
                if (dist > 0.05) Bias.BULLISH else if (dist < -0.05) Bias.BEARISH else Bias.NEUTRAL, (30 + min(abs(dist), 2.0) * 35).toInt(),
                "Price is ${f(abs(dist), 1)} ATR ${if (dist >= 0) "above" else "below"} today's VWAP: intraday ${if (dist >= 0) "buyers" else "sellers"} are in control.",
                "VWAP = Σ(TP·V)/ΣV from the session open (09:15 IST), TP = (H+L+C)/3. Resets daily, so it only applies to intraday bars.",
                "session-anchored", 1, ready = tf.intraday && hasVolume && v != null)
            if (!tf.intraday) out[out.size - 1] = out.last().copy(explanation = "Session VWAP applies to intraday bars only; see Anchored VWAP for ${tf.label} bars.")
        }

        fun avwap(s: List<Double?>, i: Int, px: Double, atr: Double, swing: LevelsCalc.Swing?, c: List<Candle>, hasVolume: Boolean) {
            val v = s.getOrNull(i)
            val dist = if (v != null) (px - v) / atr else 0.0
            val from = swing?.let { if (it.up) "swing low on ${MarketCalendar.date(c[it.lowIndex].time)}" else "swing high on ${MarketCalendar.date(c[it.highIndex].time)}" } ?: "—"
            add("avwap", "Anchored VWAP", IndicatorCategory.VOLUME, SignalGroup.VWAP, v, f(v),
                if (dist > 0.05) Bias.BULLISH else if (dist < -0.05) Bias.BEARISH else Bias.NEUTRAL, (30 + min(abs(dist), 2.0) * 35).toInt(),
                "Anchored at the $from: the average price paid since then is ${f(v)}. Price is ${if (dist >= 0) "above it (holders since the anchor are in profit)" else "below it (holders since the anchor are under water)"}.",
                "AVWAP = Σ(TP·V)/ΣV from the anchor bar to now. Anchor = start of the dominant swing in the last 126 bars (the low of an up-leg, the high of a down-leg).",
                "anchor: dominant swing, 126 bars", 10, ready = hasVolume && v != null)
        }

        fun profile(p: VolumeCalc.Profile?, px: Double, hasVolume: Boolean) {
            val bias = when { p == null -> Bias.NEUTRAL; px > p.valueAreaHigh -> Bias.BULLISH; px < p.valueAreaLow -> Bias.BEARISH; else -> Bias.NEUTRAL }
            add("vprofile", "Volume Profile (POC, VAH, VAL)", IndicatorCategory.VOLUME, SignalGroup.CONTEXT, p?.poc, "POC ${f(p?.poc)}", bias, if (bias == Bias.NEUTRAL) 20 else 60,
                when { p == null -> ""; px > p.valueAreaHigh -> "Above the value area (${f(p.valueAreaLow)}–${f(p.valueAreaHigh)}): price has been accepted higher; the VAH often acts as support."
                    px < p.valueAreaLow -> "Below the value area: the VAL often acts as resistance."
                    else -> "Inside the value area, where 70% of volume traded; the POC ${f(p.poc)} is a magnet." },
                "Volume of each bar is spread evenly across the 40 price bins its high–low range touches (last 126 bars). POC = busiest bin. " +
                    "Value area = POC plus neighbouring bins, adding the larger side first, until 70% of volume is covered (CBOT convention).",
                "126 bars, 40 bins, 70%", 20, listOf("VAH" to p?.valueAreaHigh, "VAL" to p?.valueAreaLow), ready = hasVolume && p != null)
        }

        fun pivots(p: LevelsCalc.Pivots?, px: Double) {
            add("pivots", "Pivot points (classic)", IndicatorCategory.LEVELS, SignalGroup.CONTEXT, p?.pivot, "P ${f(p?.pivot)}",
                if (p == null) Bias.NEUTRAL else if (px > p.pivot) Bias.BULLISH else Bias.BEARISH, 30,
                if (p == null) "" else "From the ${p.basis}'s high, low and close. Price is ${if (px > p.pivot) "above" else "below"} the pivot; next levels R1 ${f(p.resistances[0])} / S1 ${f(p.supports[0])}.",
                "P = (H+L+C)/3; R1 = 2P − L; S1 = 2P − H; R2 = P + (H − L); S2 = P − (H − L); R3 = H + 2(P − L); S3 = L − 2(H − P), from the previous completed period. " +
                    "Fibonacci variant: P ± {0.382, 0.618, 1}·(H − L); Camarilla: C ± {1.1/12, 1.1/6, 1.1/4}·(H − L).",
                "previous period (intraday→day, daily→week, weekly→month)", 2,
                p?.let { listOf("R1" to it.resistances[0], "R2" to it.resistances[1], "S1" to it.supports[0], "S2" to it.supports[1]) }.orEmpty(), ready = p != null)
        }

        fun fib(s: LevelsCalc.Swing?, px: Double, atr: Double) {
            val ret = s?.let { LevelsCalc.fibRetracement(it) }.orEmpty()
            val near = ret.minByOrNull { abs(it.price - px) }
            val ext = s?.let { LevelsCalc.fibExtension(it) }.orEmpty()
            val nextExt = if (s?.up == true) ext.firstOrNull { it.price > px } else ext.firstOrNull { it.price < px }
            add("fib", "Fibonacci retracement & extension", IndicatorCategory.LEVELS, SignalGroup.CONTEXT, near?.price, "Nearest ${near?.label ?: "—"} ${f(near?.price)}",
                Bias.NEUTRAL, if (near != null && abs(near.price - px) <= 0.5 * atr) 50 else 20,
                if (s == null) "" else "Swing ${if (s.up) "up" else "down"} from ${f(if (s.up) s.low else s.high)} to ${f(if (s.up) s.high else s.low)}. Price is nearest the ${near?.label} retracement (${f(near?.price)})" +
                    (nextExt?.let { "; the next ${it.label} extension is ${f(it.price)}." } ?: "."),
                "Retracement (up-leg) = High − r·(High − Low), r ∈ {0, 23.6, 38.2, 50, 61.8, 78.6, 100}%. Two-point extension = Low + r·(High − Low), r ∈ {127.2, 161.8, 200, 261.8}% " +
                    "(mirrored for a down-leg). The swing is the highest high and lowest low of the last 126 bars. A three-point (ABC) extension variant is not used.",
                "126-bar swing", 10, ret.map { it.label to it.price } + ext.map { it.label to it.price }, ready = s != null)
        }

        fun sr(sup: List<Double>, res: List<Double>, px: Double, atr: Double) {
            val s = sup.firstOrNull(); val r = res.firstOrNull()
            val nearS = s != null && px - s <= atr
            val nearR = r != null && r - px <= atr
            add("sr", "Support & resistance", IndicatorCategory.LEVELS, SignalGroup.CONTEXT, s ?: r, "S ${f(s)} · R ${f(r)}",
                when { nearS && !nearR -> Bias.BULLISH; nearR && !nearS -> Bias.BEARISH; else -> Bias.NEUTRAL }, if (nearS || nearR) 50 else 20,
                when { nearS -> "Within 1 ATR of support ${f(s)}: a tight stop is possible."; nearR -> "Within 1 ATR of resistance ${f(r)}: upside may be capped."; else -> "Nearest support ${f(s)}, resistance ${f(r)}." },
                "Swing highs/lows (extreme of ±3 bars) from the last 120 bars, clustered within 1.5%; strength = number of touches. " +
                    "A swing is confirmed 3 bars later, so the newest bars can't create levels (no look-ahead).",
                "window ±3, 1.5% clusters, 120 bars", 7, sup.mapIndexed { k, v -> "S${k + 1}" to v } + res.mapIndexed { k, v -> "R${k + 1}" to v },
                ready = s != null || r != null)
        }
    }

    // ------------------------------------------------------------------ formatting

    private fun f(v: Double?, d: Int = 2): String = if (v == null) "—" else String.format(java.util.Locale.US, "%,.${d}f", v)

    private fun compact(v: Double?): String = when {
        v == null -> "—"
        abs(v) >= 1e7 -> String.format(java.util.Locale.US, "%.2f Cr", v / 1e7)
        abs(v) >= 1e5 -> String.format(java.util.Locale.US, "%.2f L", v / 1e5)
        else -> String.format(java.util.Locale.US, "%,.0f", v)
    }
}
