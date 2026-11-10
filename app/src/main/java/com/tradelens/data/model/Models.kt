package com.tradelens.data.model

import com.tradelens.marketdata.DataStatus
import com.tradelens.marketdata.Timeframe

data class Candle(
    val time: Long,          // epoch millis
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val previousClose: Double,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Long?,
    val high52: Double?,
    val low52: Double?,
    val currency: String,
    val asOf: Long? = null,      // exchange time of [price], epoch millis
    val status: DataStatus? = null,
) {
    val change: Double get() = price - previousClose
    val changePct: Double get() = if (previousClose == 0.0) 0.0 else change / previousClose * 100
    val isIndex: Boolean get() = symbol.startsWith("^") || symbol in Universe.SECTORS
}

data class StockData(val quote: Quote, val candles: List<Candle>, val status: DataStatus? = null)

data class SearchItem(val symbol: String, val name: String, val exchange: String, val type: String)

/** Chart ranges, each with the bar sizes that make sense for it. The first-listed default matches Phase 1. */
enum class ChartRange(
    val label: String,
    val lookbackDays: Int,
    val intervals: List<Timeframe>,
    val default: Timeframe,
    val lastSessionOnly: Boolean = false,
) {
    D1("1D", 6, listOf(Timeframe.M1, Timeframe.M5, Timeframe.M15), Timeframe.M5, lastSessionOnly = true),
    W1("1W", 7, listOf(Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1), Timeframe.M15),
    M1("1M", 31, listOf(Timeframe.M30, Timeframe.H1, Timeframe.D1), Timeframe.D1),
    M6("6M", 183, listOf(Timeframe.H1, Timeframe.D1, Timeframe.W1), Timeframe.D1),
    Y1("1Y", 366, listOf(Timeframe.D1, Timeframe.W1), Timeframe.D1),
    Y5("5Y", 1827, listOf(Timeframe.D1, Timeframe.W1, Timeframe.MN1), Timeframe.W1),
    MAX("Max", 7300, listOf(Timeframe.W1, Timeframe.MN1), Timeframe.MN1),
}
