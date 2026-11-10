package com.tradelens.marketdata

import com.tradelens.data.model.Candle

/** Pure-Kotlin market data domain. No Android imports: everything here is unit-testable on the JVM. */

enum class Exchange(val suffix: String) {
    NSE(".NS"), BSE(".BO");

    companion object {
        /** Indices ("^NSEI") and NSE-listed index tickers trade on NSE hours. */
        fun of(symbol: String): Exchange = if (symbol.endsWith(".BO") || symbol == "^BSESN") BSE else NSE
    }
}

/** Bar sizes the app supports. [minutes] is 0 for calendar-based bars. */
enum class Timeframe(val label: String, val minutes: Int, val intraday: Boolean) {
    M1("1m", 1, true),
    M5("5m", 5, true),
    M15("15m", 15, true),
    M30("30m", 30, true),
    H1("1h", 60, true),
    D1("1D", 0, false),
    W1("1W", 0, false),
    MN1("1M", 0, false);

    val millis: Long get() = minutes * 60_000L
}

enum class DataMode { REALTIME, DELAYED, END_OF_DAY, DEMO }

/** What a provider can actually serve. Nothing is assumed: an absent entry means "not supported". */
data class ProviderCapabilities(
    /** Native bar sizes, each with the furthest it can look back (null = no stated limit). */
    val timeframes: Map<Timeframe, Long?>,
    val batchQuotes: Int,                 // max symbols per quote request; 1 = no batching
    val adjustedClose: Boolean,           // dividend + split adjusted close series
    val splitAdjustedOhlc: Boolean,       // OHLC already adjusted for splits/bonuses
    val corporateActions: Boolean,
    val indices: Boolean,
    val sectorIndices: Boolean,
    val search: Boolean,
    val marketDepth: Boolean,
)

data class RateLimit(val permitsPerSecond: Double, val burst: Int)

data class ProviderInfo(
    val id: String,
    val name: String,
    val mode: DataMode,
    /** Whether the terms allow showing this data to end users of a distributed app. */
    val licensedForDisplay: Boolean,
    val capabilities: ProviderCapabilities,
    val rateLimit: RateLimit,
    val note: String,
) {
    val realtime: Boolean get() = mode == DataMode.REALTIME
    fun supports(tf: Timeframe) = tf in capabilities.timeframes
    fun lookbackLimit(tf: Timeframe): Long? = capabilities.timeframes[tf]
}

class UnsupportedCapabilityException(message: String) : Exception(message)

data class ProviderQuote(
    val symbol: String,
    val name: String?,
    val currency: String?,
    val price: Double,
    val previousClose: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Long?,
    val high52: Double?,
    val low52: Double?,
    val asOf: Long?,          // exchange time of [price], epoch millis
)

/** Candles exactly as the provider returned them, before validation. */
data class CandleSeries(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    /** Aligned to [candles]; null when the provider has no adjusted series. */
    val adjClose: List<Double?>? = null,
    val splitAdjusted: Boolean,
    val meta: ProviderQuote? = null,
)

enum class CorporateActionType { SPLIT, BONUS, DIVIDEND }

data class CorporateAction(
    val symbol: String,
    val type: CorporateActionType,
    val exDate: Long,             // epoch millis
    /** For splits/bonuses: new shares per old share (2:1 split → 2.0). Null for dividends. */
    val ratio: Double? = null,
    /** Cash per share for dividends. */
    val amount: Double? = null,
    val source: String,
)

/** How trustworthy and recent a piece of data is, as shown to the user. */
enum class Freshness(val label: String) {
    LIVE("Live"),
    DELAYED("Delayed"),
    MARKET_CLOSED("Market closed"),
    STALE("Stale"),
    OFFLINE("Offline · cached"),
    DEMO("DEMO DATA"),
}

data class DataStatus(
    val provider: String,
    val mode: DataMode,
    val freshness: Freshness,
    val asOf: Long?,               // when the price/bar was valid at the exchange
    val fetchedAt: Long?,          // when we last got it from the provider
    val quality: ValidationReport? = null,
    val availability: List<String> = emptyList(),
    val error: String? = null,
) {
    val isDemo get() = mode == DataMode.DEMO
}
