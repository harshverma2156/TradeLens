package com.tradelens.data.remote

import com.tradelens.data.model.Candle
import com.tradelens.data.model.SearchItem
import com.tradelens.data.repository.DataException
import com.tradelens.marketdata.CandleSeries
import com.tradelens.marketdata.CorporateAction
import com.tradelens.marketdata.CorporateActionType
import com.tradelens.marketdata.DataMode
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.ProviderCapabilities
import com.tradelens.marketdata.ProviderInfo
import com.tradelens.marketdata.ProviderQuote
import com.tradelens.marketdata.RateLimit
import com.tradelens.marketdata.Timeframe

/**
 * Yahoo Finance's public chart/spark/search endpoints. No API key, but unofficial: no SLA, may be
 * delayed, can rate-limit or change without notice, and Yahoo's terms don't grant a licence to
 * redistribute exchange data. Fine for personal use and development; replace for a public release.
 */
class YahooFinanceProvider(private val api: YahooApi) : MarketDataProvider {

    private val day = 86_400_000L

    override val info = ProviderInfo(
        id = "yahoo",
        name = "Yahoo Finance",
        mode = DataMode.DELAYED,
        licensedForDisplay = false,
        capabilities = ProviderCapabilities(
            // Limits measured against the live API; requests beyond them return HTTP 422.
            timeframes = mapOf(
                Timeframe.M1 to 7 * day,
                Timeframe.M5 to 59 * day,
                Timeframe.M15 to 59 * day,
                Timeframe.M30 to 59 * day,
                Timeframe.H1 to 729 * day,
                Timeframe.D1 to null,
                Timeframe.W1 to null,
                Timeframe.MN1 to null,
            ),
            batchQuotes = 20,
            adjustedClose = true,         // daily/weekly/monthly only
            splitAdjustedOhlc = true,     // verified: HDFCBANK 2:1 (Aug 2025) shows no gap
            corporateActions = true,      // splits (incl. bonuses, reported as splits) and dividends
            indices = true,
            sectorIndices = true,
            search = true,
            marketDepth = false,
        ),
        rateLimit = RateLimit(permitsPerSecond = 4.0, burst = 8),  // Yahoo publishes no limit; kept conservative
        note = "Unofficial source; prices may be delayed. Not licensed for redistribution.",
    )

    private fun interval(tf: Timeframe) = when (tf) {
        Timeframe.M1 -> "1m"; Timeframe.M5 -> "5m"; Timeframe.M15 -> "15m"; Timeframe.M30 -> "30m"
        Timeframe.H1 -> "60m"; Timeframe.D1 -> "1d"; Timeframe.W1 -> "1wk"; Timeframe.MN1 -> "1mo"
    }

    override suspend fun quotes(symbols: List<String>): List<ProviderQuote> {
        if (symbols.isEmpty()) return emptyList()
        require(symbols.size <= info.capabilities.batchQuotes) { "At most ${info.capabilities.batchQuotes} symbols per call" }
        val res = api.spark(symbols.joinToString(","))
        res.spark.error?.let { throw DataException(it.description ?: "Quote request failed") }
        return res.spark.result.orEmpty().mapNotNull { r -> r.response?.firstOrNull()?.let { quoteFrom(it) } }
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe, fromMs: Long, toMs: Long): CandleSeries {
        if (!info.supports(timeframe)) throw com.tradelens.marketdata.UnsupportedCapabilityException("${info.name} has no ${timeframe.label} bars")
        val r = chart(symbol, interval(timeframe), fromMs, toMs, events = null)
        val (candles, adj) = parse(r, withAdj = !timeframe.intraday)
        return CandleSeries(symbol, timeframe, candles, adj, splitAdjusted = true, meta = quoteFrom(r))
    }

    override suspend fun corporateActions(symbol: String, fromMs: Long, toMs: Long): List<CorporateAction> {
        val r = chart(symbol, "1d", fromMs, toMs, events = "div,split")
        val ev = r.events ?: return emptyList()
        val splits = ev.splits.orEmpty().values.mapNotNull { s ->
            val n = s.numerator ?: return@mapNotNull null
            val d = s.denominator?.takeIf { it > 0 } ?: return@mapNotNull null
            CorporateAction(symbol, CorporateActionType.SPLIT, s.date * 1000, ratio = n / d, source = info.name)
        }
        val divs = ev.dividends.orEmpty().values.map { CorporateAction(symbol, CorporateActionType.DIVIDEND, it.date * 1000, amount = it.amount, source = info.name) }
        return (splits + divs).sortedBy { it.exDate }
    }

    override suspend fun search(query: String): List<SearchItem> =
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

    private suspend fun chart(symbol: String, interval: String, fromMs: Long, toMs: Long, events: String?): ChartResult {
        val response = api.chart(symbol, interval, period1 = fromMs / 1000, period2 = toMs / 1000, events = events)
        return response.chart.result?.firstOrNull()
            ?: throw DataException(response.chart.error?.description ?: "No data for $symbol")
    }

    /**
     * Previous close = last daily close before the session of the latest price.
     * Uses the close series only: spark responses carry closes but no open/high/low (verified), and
     * meta.chartPreviousClose is the close before the *range*, not the previous session.
     */
    private fun quoteFrom(r: ChartResult): ProviderQuote {
        val m = r.meta
        val asOf = m.regularMarketTime?.let { it * 1000 }
        val ts = r.timestamp.orEmpty()
        val closes = r.indicators.quote.firstOrNull()?.close.orEmpty()
        val daily = ts.indices.mapNotNull { i -> closes.getOrNull(i)?.let { ts[i] * 1000 to it } }
        val price = m.regularMarketPrice ?: daily.lastOrNull()?.second ?: throw DataException("No price for ${m.symbol}")
        val prev = asOf?.let { t ->
            val d = MarketCalendar.date(t)
            daily.lastOrNull { MarketCalendar.date(it.first).isBefore(d) }?.second
        } ?: m.previousClose
        return ProviderQuote(
            symbol = m.symbol, name = m.longName ?: m.shortName, currency = m.currency,
            price = price, previousClose = prev,
            dayHigh = m.regularMarketDayHigh, dayLow = m.regularMarketDayLow, volume = m.regularMarketVolume,
            high52 = m.fiftyTwoWeekHigh, low52 = m.fiftyTwoWeekLow, asOf = asOf,
        )
    }

    /** Drops bars with a missing OHLC value rather than guessing it; keeps adjclose aligned. */
    private fun parse(r: ChartResult, withAdj: Boolean): Pair<List<Candle>, List<Double?>?> {
        val ts = r.timestamp ?: return emptyList<Candle>() to null
        val q = r.indicators.quote.firstOrNull() ?: return emptyList<Candle>() to null
        val adjSrc = if (withAdj) r.indicators.adjclose.firstOrNull()?.adjclose else null
        val out = ArrayList<Candle>(ts.size)
        val adj = if (adjSrc != null) ArrayList<Double?>(ts.size) else null
        for (i in ts.indices) {
            val o = q.open.getOrNull(i)
            val h = q.high.getOrNull(i)
            val l = q.low.getOrNull(i)
            val c = q.close.getOrNull(i)
            if (o == null || h == null || l == null || c == null) continue
            out += Candle(ts[i] * 1000, o, h, l, c, q.volume.getOrNull(i) ?: 0L)
            adj?.add(adjSrc?.getOrNull(i))
        }
        return out to adj
    }
}
