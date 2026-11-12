package com.tradelens.flow

import com.tradelens.marketdata.Freshness
import com.tradelens.ta.Bias

/** Where a value came from and how current it is. Every number on the volume/institutional dashboard carries one. */
data class Provenance(
    val provider: String,
    /** When the value was true at the source: snapshot time, trade date, filing date or quarter end. */
    val asOf: Long?,
    val fetchedAt: Long?,
    val freshness: Freshness,
    /** True for exchange / regulator-published data (delivery, bulk/block deals, shareholding, disclosures). */
    val official: Boolean,
    val note: String = "",
)

/**
 * A value that may be missing. Missing data is [Unavailable] with a reason: never a 0, never an empty list that
 * looks like "nothing happened".
 */
sealed interface DataPoint<out T> {
    data class Available<T>(val value: T, val source: Provenance) : DataPoint<T>
    data class Unavailable(val reason: String) : DataPoint<Nothing>

    val valueOrNull: T? get() = (this as? Available<T>)?.value
}

// ---------------------------------------------------------------------------------------------- volume

data class VolumeSpike(val index: Int, val time: Long, val relativeVolume: Double, val priceChangePct: Double)

data class VolumeStats(
    /** Volume of the latest bar (the session so far when [intradayPartial]). Null if the series has no volume. */
    val current: Long?,
    val intradayPartial: Boolean,
    /** Fraction of the session elapsed when [intradayPartial] (for the time-adjusted RVOL). */
    val sessionFraction: Double?,
    val average20: Double?,
    val average50: Double?,
    /**
     * current ÷ average of the 20 bars BEFORE it. During the session it is time-adjusted:
     * volume so far ÷ (average × fraction of the session elapsed), assuming volume accrues evenly through the day.
     * (Indian sessions are heavier at the open and close, so early-session RVOL tends to read high.)
     */
    val relativeVolume: Double?,
    val spikes: List<VolumeSpike>,
    /** Σ volume on up-close days ÷ Σ volume on down-close days over 20 bars. */
    val upDownVolumeRatio: Double?,
    val obvChange20: Double?,
    val adChange20: Double?,
    val mfi: Double?,
    val poc: Double?,
    val valueAreaHigh: Double?,
    val valueAreaLow: Double?,
    val vwap: Double?,
    val anchoredVwap: Double?,
    val anchoredFrom: Long?,
)

data class DeliveryDay(val date: Long, val tradedQty: Long, val deliverableQty: Long) {
    val deliveryPct get() = if (tradedQty > 0) deliverableQty * 100.0 / tradedQty else null
}

data class DeliveryStats(
    val latest: DeliveryDay,
    val latestPct: Double?,
    val avgPct20: Double?,
    /** 5-day average delivery % minus the 20-day average, in percentage points. */
    val trendPts: Double?,
    /** 5-day average deliverable quantity ÷ 20-day average. */
    val deliveryVolumeTrend: Double?,
    val days: List<DeliveryDay>,
)

// ---------------------------------------------------------------------------------------------- depth

data class DepthLevel(val price: Double, val quantity: Long, val orders: Int? = null)

/** One order-book snapshot as delivered by a licensed feed. Bids best (highest) first, asks best (lowest) first. */
data class DepthSnapshot(
    val symbol: String,
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>,
    val timestamp: Long,
    /** Exchange-reported totals across the whole book, if the feed sends them (not just the visible levels). */
    val totalBidQty: Long? = null,
    val totalAskQty: Long? = null,
)

data class DepthAnalytics(
    val bestBid: DepthLevel,
    val bestAsk: DepthLevel,
    val spread: Double,
    val spreadPct: Double,
    val spreadBps: Double,
    val mid: Double,
    /** Levels used on EACH side for the comparable figures below: min(bid levels, ask levels). */
    val comparableLevels: Int,
    val bidQtyTopN: Long,
    val askQtyTopN: Long,
    /** (Σ bid qty − Σ ask qty) ÷ (Σ bid qty + Σ ask qty) over the top [comparableLevels] of each side: −1 … +1. */
    val imbalance: Double,
    /** Same formula with only the best level on each side. */
    val imbalanceTop1: Double,
    /** Σ bid qty ÷ Σ ask qty over the comparable levels. */
    val bidAskRatio: Double,
    /** Largest single level's share of its side's visible quantity. */
    val bidConcentration: Double,
    val askConcentration: Double,
    /** Herfindahl index of each side's visible quantity across levels (1/N = evenly spread, 1 = one level holds everything). */
    val bidHhi: Double,
    val askHhi: Double,
    val method: String,
)

// ---------------------------------------------------------------------------------------------- reported institutional data

/** Market-wide (not stock-specific) FII/FPI and DII cash-market activity for one day, ₹ crore. */
data class InstitutionalFlowDay(val date: Long, val fiiBuy: Double?, val fiiSell: Double?, val diiBuy: Double?, val diiSell: Double?) {
    val fiiNet get() = if (fiiBuy != null && fiiSell != null) fiiBuy - fiiSell else null
    val diiNet get() = if (diiBuy != null && diiSell != null) diiBuy - diiSell else null
}

/** Quarter-end shareholding pattern (% of equity). Fields the filing doesn't give stay null (unavailable). */
data class ShareholdingQuarter(val quarterEnd: Long, val promoterPct: Double?, val promoterPledgedPct: Double?, val fiiPct: Double?, val diiPct: Double?, val publicPct: Double?)

enum class DealKind { BULK, BLOCK }
enum class Side { BUY, SELL }

data class Deal(val date: Long, val kind: DealKind, val client: String, val side: Side, val quantity: Long, val price: Double, val exchange: String) {
    val value get() = quantity * price
}

/** A disclosed transaction (SEBI PIT / SAST): promoter, director or large-holder buying or selling. */
data class Disclosure(val date: Long, val person: String, val category: String, val side: Side, val quantity: Long, val value: Double?, val mode: String)

data class ShareholdingChange(val from: ShareholdingQuarter, val to: ShareholdingQuarter) {
    fun delta(f: (ShareholdingQuarter) -> Double?) = f(to)?.let { b -> f(from)?.let { a -> b - a } }
}

// ---------------------------------------------------------------------------------------------- assessment

enum class FlowSignalKind(val label: String) {
    ACCUMULATION("Accumulation"),
    DISTRIBUTION("Distribution"),
    BUYING_PRESSURE("Buying pressure"),
    SELLING_PRESSURE("Selling pressure"),
    UNUSUAL_VOLUME("Unusual volume"),
    PRICE_VOLUME_DIVERGENCE("Price–volume divergence"),
    BREAKOUT_VOLUME("Breakout volume confirmation"),
    VOLUME_EXHAUSTION("Volume exhaustion"),
}

data class FlowSignal(
    val kind: FlowSignalKind,
    val bias: Bias,
    /** 0–100. */
    val strength: Int,
    val headline: String,
    val evidence: List<String>,
    /** The rule that produced it, shown under "how this is calculated". */
    val method: String,
)

/**
 * Two separate views, never blended:
 *  INFERRED from price, volume and delivery: what the trading looks like (scored);
 *  REPORTED by exchanges/companies: who actually bought or holds (listed with dates, not scored).
 * The order book is a momentary snapshot and contributes neither.
 */
data class FlowAssessment(
    val bias: Bias,
    val label: String,
    /** −100 … +100 from the inferred signals only. */
    val score: Int,
    val signals: List<FlowSignal>,
    val context: List<String>,
    val depthNote: String?,
    val reportedNotes: List<String>,
    val caveat: String,
)

data class FlowReport(
    val symbol: String,
    val computedAt: Long,
    val volume: DataPoint<VolumeStats>,
    val delivery: DataPoint<DeliveryStats>,
    val depth: DataPoint<DepthSnapshot>,
    val depthAnalytics: DataPoint<DepthAnalytics>,
    val fiiDii: DataPoint<List<InstitutionalFlowDay>>,
    val shareholding: DataPoint<List<ShareholdingQuarter>>,
    val deals: DataPoint<List<Deal>>,
    val disclosures: DataPoint<List<Disclosure>>,
    val assessment: FlowAssessment,
)
