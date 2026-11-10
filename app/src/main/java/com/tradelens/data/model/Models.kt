package com.tradelens.data.model

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
) {
    val change: Double get() = price - previousClose
    val changePct: Double get() = if (previousClose == 0.0) 0.0 else change / previousClose * 100
    val isIndex: Boolean get() = symbol.startsWith("^")
}

data class StockData(val quote: Quote, val candles: List<Candle>)

data class SearchItem(val symbol: String, val name: String, val exchange: String, val type: String)

enum class ChartRange(val label: String, val range: String, val interval: String) {
    D1("1D", "1d", "5m"),
    W1("1W", "5d", "15m"),
    M1("1M", "1mo", "1d"),
    M6("6M", "6mo", "1d"),
    Y1("1Y", "1y", "1d"),
    Y5("5Y", "5y", "1wk");

    val intraday: Boolean get() = interval.endsWith("m")
}
