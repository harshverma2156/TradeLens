package com.tradelens.data.remote

import com.tradelens.data.model.Candle
import com.tradelens.data.model.SearchItem
import com.tradelens.data.model.Universe
import com.tradelens.marketdata.CandleSeries
import com.tradelens.marketdata.CorporateAction
import com.tradelens.marketdata.DataMode
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.ProviderCapabilities
import com.tradelens.marketdata.ProviderInfo
import com.tradelens.marketdata.ProviderQuote
import com.tradelens.marketdata.RateLimit
import com.tradelens.marketdata.Resampler
import com.tradelens.marketdata.Timeframe
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SYNTHETIC prices for offline demos and UI work. Deterministic per symbol and day, follows NSE session
 * times, and is ALWAYS labelled: mode = DEMO, names end in "(demo)", and the UI shows a DEMO badge.
 * Never use it to make trading decisions. It has nothing to do with the real market.
 */
class DemoMarketDataProvider(
    private val now: () -> Long = System::currentTimeMillis,
    private val calendar: MarketCalendar = MarketCalendar(),
) : MarketDataProvider {

    override val info = ProviderInfo(
        id = "demo",
        name = "Demo data (synthetic)",
        mode = DataMode.DEMO,
        licensedForDisplay = true,
        capabilities = ProviderCapabilities(
            timeframes = Timeframe.entries.associateWith { if (it == Timeframe.M1) 30 * 86_400_000L else null },
            batchQuotes = 50, adjustedClose = false, splitAdjustedOhlc = true, corporateActions = false,
            indices = true, sectorIndices = true, search = true, marketDepth = false,
        ),
        rateLimit = RateLimit(1000.0, 1000),
        note = "Synthetic prices. Not real market data.",
    )

    private val anchor = LocalDate.of(2015, 1, 1)

    private fun seed(symbol: String) = symbol.hashCode().toLong() * 1_000_003L

    private fun basePrice(symbol: String) = 100.0 + (abs31(symbol.hashCode()) % 4900)

    private fun abs31(x: Int) = if (x == Int.MIN_VALUE) 0 else kotlin.math.abs(x)

    /** Daily closes from the anchor date: a seeded geometric random walk on trading days. */
    private fun dailyPath(symbol: String, until: LocalDate): List<Pair<LocalDate, Candle>> {
        val rnd = Random(seed(symbol))
        var close = basePrice(symbol)
        val out = ArrayList<Pair<LocalDate, Candle>>()
        var d = anchor
        val vol = 0.012 + (abs31(symbol.hashCode()) % 10) / 1000.0
        while (!d.isAfter(until)) {
            if (calendar.isTradingDay(d)) {
                val open = close * (1 + rnd.nextDouble(-0.004, 0.004))
                val c = open * exp(rnd.nextDouble(-1.0, 1.0) * vol * sqrt(3.0) + 0.0003)
                val h = max(open, c) * (1 + rnd.nextDouble(0.0, vol))
                val l = min(open, c) * (1 - rnd.nextDouble(0.0, vol))
                val v = if (symbol.startsWith("^")) 0L else (500_000 + rnd.nextInt(2_000_000)).toLong()
                out += d to Candle(MarketCalendar.at(d, MarketCalendar.OPEN), open, h, l, c, v)
                close = c
            }
            d = d.plusDays(1)
        }
        return out
    }

    /** 1-minute path for one session that starts at the day's open and ends exactly at its close. */
    private fun minutePath(symbol: String, day: Candle): List<Candle> {
        val rnd = Random(seed(symbol) xor day.time)
        val n = MarketCalendar.SESSION_MINUTES
        val walk = DoubleArray(n + 1)
        walk[0] = day.open
        for (i in 1..n) walk[i] = walk[i - 1] * (1 + rnd.nextDouble(-0.0012, 0.0012))
        val drift = (day.close - walk[n]) / n
        val out = ArrayList<Candle>(n)
        for (i in 0 until n) {
            val o = walk[i] + drift * i
            val c = walk[i + 1] + drift * (i + 1)
            val h = max(o, c) * (1 + rnd.nextDouble(0.0, 0.0006))
            val l = min(o, c) * (1 - rnd.nextDouble(0.0, 0.0006))
            out += Candle(day.time + i * 60_000L, o, h, l, c, day.volume / n)
        }
        return out
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe, fromMs: Long, toMs: Long): CandleSeries {
        val t = min(toMs, now())
        val days = dailyPath(symbol, MarketCalendar.date(t)).filter { it.second.time + 86_400_000L > fromMs }
        val candles: List<Candle> = when (timeframe) {
            Timeframe.D1, Timeframe.W1, Timeframe.MN1 -> {
                val daily = days.map { (d, c) -> if (d == MarketCalendar.date(t)) partialDay(symbol, c, t) else c }.filterNotNull()
                if (timeframe == Timeframe.D1) daily else Resampler.aggregate(daily, timeframe, calendar)
            }
            else -> {
                val mins = days.flatMap { (_, c) -> minutePath(symbol, c) }.filter { it.time in fromMs..(t - 60_000L) }
                if (timeframe == Timeframe.M1) mins else Resampler.aggregate(mins, timeframe, calendar)
            }
        }.filter { it.time in (fromMs - 31L * 86_400_000L)..t }
        return CandleSeries(symbol, timeframe, candles, adjClose = null, splitAdjusted = true, meta = null)
    }

    /** Today's bar only covers minutes that have already happened. */
    private fun partialDay(symbol: String, day: Candle, t: Long): Candle? {
        if (t >= day.time + MarketCalendar.SESSION_MINUTES * 60_000L) return day
        val done = minutePath(symbol, day).filter { it.time + 60_000L <= t }
        if (done.isEmpty()) return null
        return Candle(day.time, done.first().open, done.maxOf { it.high }, done.minOf { it.low }, done.last().close, done.sumOf { it.volume })
    }

    override suspend fun quotes(symbols: List<String>): List<ProviderQuote> = symbols.map { s ->
        val t = now()
        val today = MarketCalendar.date(t)
        val days = dailyPath(s, today)
        val past = days.filter { it.first.isBefore(today) }.map { it.second }
        val todayFull = days.lastOrNull()?.takeIf { it.first == today }?.second
        val todayBar = todayFull?.let { partialDay(s, it, t) }          // null before 09:16 or on holidays
        val last = todayBar ?: past.last()
        val prevClose = if (todayBar != null) past.lastOrNull()?.close else past.getOrNull(past.size - 2)?.close
        val sessionEnd = last.time + MarketCalendar.SESSION_MINUTES * 60_000L
        val asOf = if (todayBar != null && t < sessionEnd) (t / 60_000L) * 60_000L else sessionEnd
        val yr = (past + listOfNotNull(todayBar)).takeLast(252)
        ProviderQuote(
            symbol = s, name = "${Universe.INDICES[s] ?: s.removeSuffix(".NS").removeSuffix(".BO")} (demo)", currency = "INR",
            price = last.close, previousClose = prevClose,
            dayHigh = last.high, dayLow = last.low, volume = last.volume,
            high52 = yr.maxOfOrNull { it.high }, low52 = yr.minOfOrNull { it.low }, asOf = min(asOf, t),
        )
    }

    override suspend fun corporateActions(symbol: String, fromMs: Long, toMs: Long): List<CorporateAction> = emptyList()

    override suspend fun search(query: String): List<SearchItem> {
        val q = query.trim().uppercase()
        return (Universe.INDICES.keys + Universe.NIFTY_50).filter { it.uppercase().contains(q) }
            .map { SearchItem(it, "${Universe.INDICES[it] ?: it.removeSuffix(".NS")} (demo)", "DEMO", if (it.startsWith("^")) "INDEX" else "EQUITY") }
    }
}
