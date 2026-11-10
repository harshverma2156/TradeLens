package com.tradelens.data.repository

import com.tradelens.data.model.*
import com.tradelens.data.remote.ChartResult
import com.tradelens.data.remote.YahooApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class DataException(message: String) : Exception(message)

class MarketRepository(private val api: YahooApi) {
    companion object { const val BENCHMARK = "^NSEI" }

    suspend fun quote(symbol: String): Quote = withContext(Dispatchers.IO) {
        val result = fetch(symbol, "5d", "1d")
        result.toQuote(parseCandles(result), daily = true)
    }

    /** Loads quotes in parallel; symbols that fail are dropped. */
    suspend fun quotes(symbols: Collection<String>, parallel: Int = 6): List<Quote> = coroutineScope {
        val gate = Semaphore(parallel)
        symbols.map { s ->
            async { gate.withPermit { runCatchingCancellable { quote(s) }.getOrNull() } }
        }.awaitAll().filterNotNull()
    }

    suspend fun history(symbol: String, range: ChartRange): StockData = withContext(Dispatchers.IO) {
        val result = fetch(symbol, range.range, range.interval)
        val candles = parseCandles(result)
        StockData(result.toQuote(candles, daily = range.interval == "1d"), candles)
    }

    /** Two years of daily candles: enough for the 200 EMA, weekly trend and the signal backtest. */
    suspend fun analysisHistory(symbol: String): StockData = withContext(Dispatchers.IO) {
        val result = fetch(symbol, "2y", "1d")
        val candles = parseCandles(result)
        StockData(result.toQuote(candles, daily = true), candles)
    }

    private val benchMutex = Mutex()
    private var benchCache: Pair<Long, List<Candle>>? = null

    /** NIFTY 50 daily candles (2 years), cached for 15 minutes. Null if it can't be loaded. */
    suspend fun benchmark(): List<Candle>? = benchMutex.withLock {
        benchCache?.let { (at, data) -> if (System.currentTimeMillis() - at < 15 * 60_000) return@withLock data }
        runCatchingCancellable { analysisHistory(BENCHMARK).candles }.getOrNull()
            ?.takeIf { it.size > 100 }
            ?.also { benchCache = System.currentTimeMillis() to it }
            ?: benchCache?.second
    }

    suspend fun search(query: String): List<SearchItem> = withContext(Dispatchers.IO) {
        api.search(query).quotes
            .filter { it.symbol != null && it.quoteType in setOf("EQUITY", "ETF", "INDEX") }
            .map {
                SearchItem(
                    symbol = it.symbol!!,
                    name = it.longname ?: it.shortname ?: it.symbol,
                    exchange = it.exchDisp ?: it.exchange.orEmpty(),
                    type = it.quoteType.orEmpty(),
                )
            }
    }

    private suspend fun fetch(symbol: String, range: String, interval: String): ChartResult {
        val response = api.chart(symbol, range, interval)
        return response.chart.result?.firstOrNull()
            ?: throw DataException(response.chart.error?.description ?: "No data for $symbol")
    }

    private fun parseCandles(r: ChartResult): List<Candle> {
        val ts = r.timestamp ?: return emptyList()
        val q = r.indicators.quote.firstOrNull() ?: return emptyList()
        val out = ArrayList<Candle>(ts.size)
        for (i in ts.indices) {
            val o = q.open.getOrNull(i)
            val h = q.high.getOrNull(i)
            val l = q.low.getOrNull(i)
            val c = q.close.getOrNull(i)
            if (o == null || h == null || l == null || c == null) continue
            out += Candle(ts[i] * 1000, o, h, l, c, q.volume.getOrNull(i) ?: 0L)
        }
        return out
    }

    private fun ChartResult.toQuote(candles: List<Candle>, daily: Boolean): Quote {
        val price = meta.regularMarketPrice ?: candles.lastOrNull()?.close ?: 0.0
        val prev = if (daily && candles.size >= 2) candles[candles.size - 2].close
        else meta.previousClose ?: meta.chartPreviousClose ?: price
        return Quote(
            symbol = meta.symbol,
            name = Universe.INDICES[meta.symbol] ?: meta.longName ?: meta.shortName ?: meta.symbol,
            price = price,
            previousClose = prev,
            dayHigh = meta.regularMarketDayHigh ?: candles.lastOrNull()?.high,
            dayLow = meta.regularMarketDayLow ?: candles.lastOrNull()?.low,
            volume = meta.regularMarketVolume ?: candles.lastOrNull()?.volume,
            high52 = meta.fiftyTwoWeekHigh,
            low52 = meta.fiftyTwoWeekLow,
            currency = meta.currency ?: "INR",
        )
    }
}
