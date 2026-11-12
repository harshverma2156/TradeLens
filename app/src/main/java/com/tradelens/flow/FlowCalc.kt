package com.tradelens.flow

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.DataMode
import com.tradelens.ta.Bias
import com.tradelens.ta.LevelsCalc
import com.tradelens.ta.VolumeCalc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Volume and order-book analytics. Pure functions: every figure is computed from the inputs given, and a missing
 * input yields null (rendered as "unavailable"), never 0.
 */
object FlowCalc {

    const val SPIKE_RVOL = 2.0
    const val BREAKOUT_RVOL = 1.3
    const val PRESSURE_RATIO = 1.5
    const val DEPTH_LEVELS = 5

    /**
     * Volume statistics. [sessionFraction] is set when the last bar is still forming (the share of the session that has
     * elapsed); then the last bar's volume is partial and the relative volume is time-adjusted.
     * Null when there are fewer than 21 bars or the series carries no volume (e.g. an index).
     */
    fun volume(c: List<Candle>, sessionFraction: Double? = null): VolumeStats? {
        val n = c.size
        if (n < 21 || c.takeLast(30).all { it.volume == 0L }) return null
        val i = n - 1
        val partial = sessionFraction != null
        val vols = c.map { it.volume.toDouble() }
        val avg20 = vols.subList(i - 20, i).average().takeIf { it > 0 }
        val cur = vols[i]
        val rel = avg20?.let { a -> if (partial) cur / (a * sessionFraction!!.coerceAtLeast(1e-3)) else cur / a }

        val closedEnd = if (partial) i else n
        val spikes = (max(20, n - 60) until closedEnd).mapNotNull { k ->
            val a = vols.subList(k - 20, k).average()
            if (a <= 0 || vols[k] / a < SPIKE_RVOL) null
            else VolumeSpike(k, c[k].time, vols[k] / a, (c[k].close / c[k - 1].close - 1) * 100)
        }

        var up = 0.0; var down = 0.0
        for (k in i - 19..i) {
            if (c[k].close > c[k - 1].close) up += vols[k] else if (c[k].close < c[k - 1].close) down += vols[k]
        }
        val ratio = if (down > 0) up / down else null

        val obv = VolumeCalc.obv(c)
        val ad = VolumeCalc.accumulationDistribution(c)
        val prof = VolumeCalc.volumeProfile(c.takeLast(min(n, 126)))
        val swing = LevelsCalc.dominantSwing(c, 126)
        val anchor = swing?.let { if (it.up) it.lowIndex else it.highIndex }
        val avwap = anchor?.let { VolumeCalc.anchoredVwap(c, it).last() }

        return VolumeStats(
            current = c[i].volume, intradayPartial = partial, sessionFraction = sessionFraction,
            average20 = avg20, average50 = if (n >= 51) vols.subList(i - 50, i).average() else null,
            relativeVolume = rel, spikes = spikes, upDownVolumeRatio = ratio,
            obvChange20 = obv[i] - obv[i - 20], adChange20 = ad[i] - ad[i - 20],
            mfi = VolumeCalc.mfi(c).last(),
            poc = prof?.poc, valueAreaHigh = prof?.valueAreaHigh, valueAreaLow = prof?.valueAreaLow,
            vwap = null, anchoredVwap = avwap, anchoredFrom = anchor?.let { c[it].time },
        )
    }

    /** Delivery statistics from exchange-published daily delivery data (already validated by the source). */
    fun delivery(days: List<DeliveryDay>): DeliveryStats? {
        val d = days.filter { it.tradedQty > 0 }.sortedBy { it.date }
        if (d.size < 20) return null
        fun pct(xs: List<DeliveryDay>) = xs.sumOf { it.deliverableQty.toDouble() }.let { dq -> xs.sumOf { it.tradedQty.toDouble() }.let { t -> dq * 100 / t } }
        val last5 = d.takeLast(5); val last20 = d.takeLast(20)
        val avgDq20 = last20.map { it.deliverableQty.toDouble() }.average()
        val avgDq5 = last5.map { it.deliverableQty.toDouble() }.average()
        return DeliveryStats(
            latest = d.last(), latestPct = d.last().deliveryPct, avgPct20 = pct(last20),
            trendPts = pct(last5) - pct(last20), deliveryVolumeTrend = if (avgDq20 > 0) avgDq5 / avgDq20 else null,
            days = d.takeLast(60),
        )
    }

    /**
     * Order-book analytics for the top [levels] on each side (the same count on both sides, so the figures are comparable).
     *  imbalance  = (Σ bid qty − Σ ask qty) ÷ (Σ bid qty + Σ ask qty), from −1 (all on the ask) to +1 (all on the bid);
     *  bid/ask ratio = Σ bid qty ÷ Σ ask qty;
     *  concentration = largest level's share of its side;  HHI = Σ(share²) per side (1/N = evenly spread, 1 = one level).
     * Null if either side is empty, quantities are not positive, or the book is crossed (bid ≥ ask).
     */
    fun depth(s: DepthSnapshot, levels: Int = DEPTH_LEVELS): DepthAnalytics? {
        val bids = s.bids.filter { it.quantity > 0 }.sortedByDescending { it.price }
        val asks = s.asks.filter { it.quantity > 0 }.sortedBy { it.price }
        if (bids.isEmpty() || asks.isEmpty()) return null
        val bb = bids.first(); val ba = asks.first()
        if (bb.price >= ba.price) return null
        val n = min(levels, min(bids.size, asks.size))
        val bq = bids.take(n).map { it.quantity.toDouble() }; val aq = asks.take(n).map { it.quantity.toDouble() }
        val bSum = bq.sum(); val aSum = aq.sum()
        val mid = (bb.price + ba.price) / 2
        val spread = ba.price - bb.price
        fun share(xs: List<Double>, sum: Double) = xs.map { it / sum }
        val bShare = share(bq, bSum); val aShare = share(aq, aSum)
        return DepthAnalytics(
            bestBid = bb, bestAsk = ba, spread = spread, spreadPct = spread / mid * 100, spreadBps = spread / mid * 10_000, mid = mid,
            comparableLevels = n, bidQtyTopN = bSum.toLong(), askQtyTopN = aSum.toLong(),
            imbalance = (bSum - aSum) / (bSum + aSum), imbalanceTop1 = (bb.quantity - ba.quantity).toDouble() / (bb.quantity + ba.quantity),
            bidAskRatio = bSum / aSum,
            bidConcentration = bShare.max(), askConcentration = aShare.max(),
            bidHhi = bShare.sumOf { it * it }, askHhi = aShare.sumOf { it * it },
            method = "Top $n levels per side. Imbalance = (bid qty − ask qty) ÷ (bid qty + ask qty). Bid/ask ratio = bid qty ÷ ask qty. " +
                "Concentration = largest level's share of its side. HHI = Σ share² per side. A snapshot taken at ${s.timestamp}.",
        )
    }

    /**
     * Inferred trading-pressure assessment from price, volume and delivery. Reported institutional data and the order
     * book are NOT scored here: they are shown separately, and a book snapshot is momentary.
     */
    fun assess(c: List<Candle>, v: VolumeStats?, d: DeliveryStats?, depth: DepthAnalytics?, depthAt: Long?): FlowAssessment {
        val n = c.size; val i = n - 1
        val signals = mutableListOf<FlowSignal>()
        val context = mutableListOf<String>()
        if (v == null) context += "Volume is unavailable for this instrument, so volume signals are not scored."
        else {
            val rel = v.relativeVolume
            context += "Relative volume ${rel?.let { fmt(it, 2) + "×" } ?: "—"}" +
                (if (v.intradayPartial) " (time-adjusted, session in progress)" else "") + " against the 20-bar average."
            v.upDownVolumeRatio?.let { context += "Up-day to down-day volume (20 bars): ${fmt(it, 2)}×." }

            // Accumulation / distribution: the cumulative flow lines agree with each other.
            // Both lines must move clearly the same way: a zero change (flat closes) is no signal, not a fall.
            val obvC = v.obvChange20 ?: 0.0; val adC = v.adChange20 ?: 0.0
            if (obvC * adC > 0) {
                signals += if (obvC > 0) FlowSignal(FlowSignalKind.ACCUMULATION, Bias.BULLISH, 60,
                    "OBV and the accumulation/distribution line both rose over 20 bars.",
                    listOf("OBV change ${fmt(obvC, 0)}", "A/D change ${fmt(adC, 0)}"), ACC_METHOD)
                else FlowSignal(FlowSignalKind.DISTRIBUTION, Bias.BEARISH, 60,
                    "OBV and the accumulation/distribution line both fell over 20 bars.",
                    listOf("OBV change ${fmt(obvC, 0)}", "A/D change ${fmt(adC, 0)}"), ACC_METHOD)
            }

            // Buying / selling pressure from up-day vs down-day volume.
            v.upDownVolumeRatio?.let { r ->
                if (r >= PRESSURE_RATIO) signals += FlowSignal(FlowSignalKind.BUYING_PRESSURE, Bias.BULLISH, min(90, (50 + (r - 1) * 30).toInt()),
                    "Most volume on up days: ${fmt(r, 2)}× the volume on down days.", listOf("Up/down volume ${fmt(r, 2)}×"), PRESSURE_METHOD)
                else if (r <= 1 / PRESSURE_RATIO) signals += FlowSignal(FlowSignalKind.SELLING_PRESSURE, Bias.BEARISH, min(90, (50 + (1 / r - 1) * 30).toInt()),
                    "Most volume on down days: ${fmt(1 / r, 2)}× the volume on up days.", listOf("Down/up volume ${fmt(1 / r, 2)}×"), PRESSURE_METHOD)
            }

            // Unusual volume today (or the current partial session).
            if (rel != null && rel >= SPIKE_RVOL) {
                val ret = (c[i].close / c[i - 1].close - 1) * 100
                signals += FlowSignal(FlowSignalKind.UNUSUAL_VOLUME, if (ret >= 0) Bias.BULLISH else Bias.BEARISH, min(90, (40 + 15 * rel).toInt()),
                    "Volume is ${fmt(rel, 1)}× normal on a ${if (ret >= 0) "rising" else "falling"} day (${fmt(ret, 1)}%).",
                    listOf("Relative volume ${fmt(rel, 2)}×", "Price change ${fmt(ret, 2)}%"), "Relative volume ≥ $SPIKE_RVOL× the 20-bar average.")
            }

            // Breakout volume: a close above the prior 20-bar high on heavy volume.
            val prior20High = (i - 20 until i).maxOf { c[it].high }
            if (c[i].close > prior20High && rel != null && rel >= BREAKOUT_RVOL) signals += FlowSignal(FlowSignalKind.BREAKOUT_VOLUME, Bias.BULLISH, min(85, (55 + 10 * (rel - BREAKOUT_RVOL)).toInt()),
                "Close above the prior 20-bar high (${fmt(prior20High, 2)}) on ${fmt(rel, 2)}× normal volume.", listOf("Breakout level ${fmt(prior20High, 2)}"),
                "Close above the prior 20-bar high with relative volume ≥ $BREAKOUT_RVOL×.")

            // Price–volume divergence: a new 20-bar high that On-Balance Volume doesn't confirm (and the mirror image).
            val obv = VolumeCalc.obv(c)
            val priorClose = (i - 20 until i).maxOf { c[it].close }
            val priorObv = (i - 20 until i).maxOf { obv[it] }
            val priorLowClose = (i - 20 until i).minOf { c[it].close }
            val priorLowObv = (i - 20 until i).minOf { obv[it] }
            if (c[i].close > priorClose && obv[i] < priorObv) signals += FlowSignal(FlowSignalKind.PRICE_VOLUME_DIVERGENCE, Bias.BEARISH, 60,
                "Price made a new 20-bar high, but OBV is below its own 20-bar high: the rally lacks volume.", listOf("OBV ${fmt(obv[i], 0)} vs prior high ${fmt(priorObv, 0)}"), DIVERGENCE_METHOD)
            if (c[i].close < priorLowClose && obv[i] > priorLowObv) signals += FlowSignal(FlowSignalKind.PRICE_VOLUME_DIVERGENCE, Bias.BULLISH, 55,
                "Price made a new 20-bar low, but OBV is above its own 20-bar low: selling is drying up.", listOf("OBV ${fmt(obv[i], 0)} vs prior low ${fmt(priorLowObv, 0)}"), DIVERGENCE_METHOD)

            // Volume exhaustion: a 5-bar move on volume well below normal, so it lacks participation.
            val ret5 = (c[i].close / c[i - 5].close - 1) * 100
            val vol5 = (i - 4..i).map { c[it].volume.toDouble() }.average()
            val vol20 = (i - 24..i - 5).map { c[it].volume.toDouble() }.average()
            if (vol20 > 0 && vol5 / vol20 < 0.8 && abs(ret5) >= 2) {
                val up = ret5 > 0
                signals += FlowSignal(FlowSignalKind.VOLUME_EXHAUSTION, if (up) Bias.BEARISH else Bias.BULLISH, 45,
                    "The last 5 bars moved ${fmt(ret5, 1)}% on ${fmt(vol5 / vol20, 2)}× the prior volume: the ${if (up) "rally" else "fall"} is losing participation.",
                    listOf("5-bar volume ${fmt(vol5 / vol20, 2)}× prior"), "5-bar move ≥ 2% on 5-bar average volume < 0.8× the prior 20 bars.")
            }
        }

        if (d != null) {
            context += "Delivery ${d.latestPct?.let { fmt(it, 1) + "%" } ?: "—"} on the latest day (20-day average ${d.avgPct20?.let { fmt(it, 1) + "%" } ?: "—"})."
            val ret = (c[i].close / c[i - 1].close - 1) * 100
            val lp = d.latestPct; val avg = d.avgPct20
            if (lp != null && avg != null && lp - avg >= 10) {
                if (ret >= 0) signals += FlowSignal(FlowSignalKind.ACCUMULATION, Bias.BULLISH, 65,
                    "Delivery ${fmt(lp, 1)}% vs ${fmt(avg, 1)}% average on a rising day: shares are being taken for delivery, not just traded intraday.",
                    listOf("Delivery ${fmt(lp, 1)}%"), "Latest delivery % ≥ 20-day average + 10 points, on a non-falling day.")
                else signals += FlowSignal(FlowSignalKind.DISTRIBUTION, Bias.BEARISH, 60,
                    "Delivery ${fmt(lp, 1)}% vs ${fmt(avg, 1)}% average on a falling day: holders are selling into the fall.",
                    listOf("Delivery ${fmt(lp, 1)}%"), "Latest delivery % ≥ 20-day average + 10 points, on a falling day.")
            }
        } else context += "Delivery data is unavailable from this source, so delivery is not scored."

        depth?.let { context += "Order book at ${depthAt?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(it) } ?: "?"}: imbalance ${fmt(it.imbalance, 2)} (snapshot only, not scored)." }

        // Score: average of the signed strengths. No signals = 0 (neutral), not "no data".
        val scored = signals.filter { it.bias != Bias.NEUTRAL }
        val score = if (scored.isEmpty()) 0 else scored.sumOf { (if (it.bias == Bias.BULLISH) 1 else -1) * it.strength }.let { it / scored.size }
        val bias = when { score >= 20 -> Bias.BULLISH; score <= -20 -> Bias.BEARISH; else -> Bias.NEUTRAL }
        val label = when {
            scored.isEmpty() -> "No clear volume signal"
            bias == Bias.BULLISH -> "Net buying pressure"
            bias == Bias.BEARISH -> "Net selling pressure"
            else -> "Mixed volume signals"
        }
        return FlowAssessment(
            bias, label, score, signals, context, depthNote = depth?.let { "A momentary order-book snapshot. It shows resting orders at one moment and can be pulled instantly; it does not prove institutional buying." },
            reportedNotes = emptyList(),
            caveat = "Inferred from price and volume only. Reported institutional activity is listed separately with its dates. Not investment advice.",
        )
    }

    const val ACC_METHOD = "OBV and A/D each compared with their value 20 bars earlier; both must move the same way."
    const val PRESSURE_METHOD = "Σ volume on up-close days ÷ Σ volume on down-close days over 20 bars; ≥ 1.5× bullish, ≤ 0.67× bearish."
    const val DIVERGENCE_METHOD = "New 20-bar close extreme compared with OBV's 20-bar extreme (OBV from Granville's definition)."

    internal fun fmt(v: Double, d: Int) = String.format(java.util.Locale.US, "%,.${d}f", v)
}
