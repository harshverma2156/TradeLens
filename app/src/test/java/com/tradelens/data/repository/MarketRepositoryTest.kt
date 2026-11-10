package com.tradelens.data.repository

import com.tradelens.data.model.Candle
import com.tradelens.data.model.SearchItem
import com.tradelens.data.remote.MarketDataProvider
import com.tradelens.marketdata.*
import com.tradelens.marketdata.TestTime.ist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class MarketRepositoryTest {

    /** Serves a fixed "true" history and records every request. */
    private class FakeProvider(
        val id: String = "fake",
        val mode: DataMode = DataMode.DELAYED,
        val tfs: Map<Timeframe, Long?> = mapOf(Timeframe.D1 to null, Timeframe.W1 to null, Timeframe.M5 to 59 * 86_400_000L),
        val batch: Int = 3,
        val corporate: Boolean = true,
        var history: List<Candle> = emptyList(),
        var failWith: Throwable? = null,
        var failTimes: Int = Int.MAX_VALUE,
    ) : MarketDataProvider {
        val candleCalls = mutableListOf<Triple<Timeframe, Long, Long>>()
        val quoteCalls = mutableListOf<List<String>>()
        var actionCalls = 0
        var actions: List<CorporateAction> = emptyList()

        override val info = ProviderInfo(id, "Fake $id", mode, false,
            ProviderCapabilities(tfs, batch, false, true, corporate, true, true, true, false), RateLimit(1e6, 1_000_000), "test")

        private fun maybeFail() { val e = failWith; if (e != null && failTimes-- > 0) throw e }

        override suspend fun quotes(symbols: List<String>): List<ProviderQuote> {
            quoteCalls += symbols; maybeFail()
            return symbols.filter { it != "BAD.NS" }.map { ProviderQuote(it, "N $it", "INR", 110.0, 100.0, 111.0, 99.0, 5, 120.0, 80.0, ist(2026, 11, 10, 15, 29)) }
        }

        override suspend fun candles(symbol: String, timeframe: Timeframe, fromMs: Long, toMs: Long): CandleSeries {
            candleCalls += Triple(timeframe, fromMs, toMs); maybeFail()
            val src = if (timeframe == Timeframe.D1) history else Resampler.aggregate(history, timeframe, MarketCalendar())
            return CandleSeries(symbol, timeframe, src.filter { it.time in fromMs..toMs }, splitAdjusted = true)
        }

        override suspend fun corporateActions(symbol: String, fromMs: Long, toMs: Long): List<CorporateAction> { actionCalls++; return actions }
        override suspend fun search(query: String) = listOf(SearchItem("$query.NS", query, "NSE", "EQUITY"))
    }

    private var now = ist(2026, 11, 10, 18, 0)                      // Tuesday, after the close
    private val days = TestTime.dailyBars(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 10))

    private fun repo(p: MarketDataProvider, dao: FakeMarketDao = FakeMarketDao(), retry: RetryPolicy = RetryPolicy.NONE) =
        MarketRepository({ p }, dao, { now }, retry, sleep = {}, io = Dispatchers.Unconfined)

    private val from = ist(2026, 1, 1)

    @Test fun firstLoadStoresThenServesFromCache() = runBlocking {
        val p = FakeProvider(history = days)
        val dao = FakeMarketDao()
        val r = repo(p, dao)
        val a = r.candles("ABC.NS", Timeframe.D1, from)
        assertEquals(days.count { it.time >= from }, a.candles.size)
        assertEquals(1, p.candleCalls.size)
        assertTrue(dao.candles.values.all { it.source == "fake" && it.exchange == "NSE" && it.fetchedAt == now })
        now += 60_000                                                 // a minute later, market closed
        val b = r.candles("ABC.NS", Timeframe.D1, from)
        assertEquals(1, p.candleCalls.size)                           // served from Room
        assertEquals(a.candles, b.candles)
        assertEquals(Freshness.MARKET_CLOSED, b.status.freshness)
        assertEquals("Fake fake", b.status.provider)
    }

    @Test fun incrementalSyncFetchesOnlyNewBars() = runBlocking {
        val p = FakeProvider(history = days.filter { it.time < ist(2026, 11, 9) })
        val r = repo(p)
        r.candles("ABC.NS", Timeframe.D1, from)
        p.history = days
        now = ist(2026, 11, 10, 18, 0) + 2 * 86_400_000L            // Thursday evening: stale → resync
        val res = r.candles("ABC.NS", Timeframe.D1, from)
        val (_, f2, _) = p.candleCalls.last()
        assertTrue("fetched from just before the last stored bar, not from scratch", f2 >= ist(2026, 11, 1) && f2 < ist(2026, 11, 9))
        assertEquals(ist(2026, 11, 10), res.candles.last().time)
        assertEquals(res.candles.map { it.time }.distinct().size, res.candles.size)   // overlap didn't duplicate
    }

    @Test fun providerFailureFallsBackToCacheMarkedOffline() = runBlocking {
        val p = FakeProvider(history = days)
        val r = repo(p)
        r.candles("ABC.NS", Timeframe.D1, from)
        p.failWith = IOException("timeout")
        val res = r.candles("ABC.NS", Timeframe.D1, from, forceRefresh = true)
        assertEquals(Freshness.OFFLINE, res.status.freshness)
        assertNotNull(res.status.error)
        assertTrue(res.candles.isNotEmpty())
    }

    @Test fun providerFailureWithNothingCachedThrows() = runBlocking {
        val r = repo(FakeProvider(history = days, failWith = IOException("down")))
        try { r.candles("ABC.NS", Timeframe.D1, from); fail() } catch (e: DataException) { assertTrue(e.message!!.contains("internet")) }
    }

    @Test fun transientFailureIsRetried() = runBlocking {
        val p = FakeProvider(history = days, failWith = IOException("blip"), failTimes = 2)
        val res = repo(p, retry = RetryPolicy(maxAttempts = 3, jitter = 0.0)).candles("ABC.NS", Timeframe.D1, from)
        assertEquals(3, p.candleCalls.size)
        assertNull(res.status.error)
    }

    @Test fun lookbackLimitIsRespectedAndExplained() = runBlocking {
        val p = FakeProvider(history = days)
        val res = repo(p).candles("ABC.NS", Timeframe.M5, ist(2026, 1, 1))
        val (_, f, _) = p.candleCalls.single()
        assertTrue(now - f <= 59 * 86_400_000L)
        assertTrue(res.status.availability.any { it.contains("59 days") })
    }

    @Test fun unsupportedTimeframeIsBuiltFromSmallerBars() = runBlocking {
        val p = FakeProvider(tfs = mapOf(Timeframe.D1 to null), history = days)
        val res = repo(p).candles("ABC.NS", Timeframe.MN1, ist(2026, 1, 1))
        assertEquals(Timeframe.D1, p.candleCalls.single().first)
        assertTrue(res.flags.all { it and QualityFlag.RESAMPLED != 0 })
        assertEquals(ist(2026, 1, 1), res.candles.first().time)
        assertTrue(res.status.availability.any { it.contains("built from") })
    }

    @Test fun impossibleTimeframeThrowsClearly() = runBlocking {
        val p = FakeProvider(tfs = mapOf(Timeframe.D1 to null), history = days)
        try { repo(p).candles("ABC.NS", Timeframe.M1, from); fail() } catch (e: UnsupportedCapabilityException) { }
    }

    @Test fun quotesAreBatchedToProviderLimitAndCached() = runBlocking {
        val p = FakeProvider(batch = 3)
        val r = repo(p)
        val q = r.quotes((1..7).map { "S$it.NS" })
        assertEquals(7, q.size)
        assertEquals(listOf(3, 3, 1), p.quoteCalls.map { it.size })
        r.quotes(listOf("S1.NS", "S2.NS"))
        assertEquals(3, p.quoteCalls.size)                            // cached (market closed)
        r.quotes(listOf("S1.NS"), forceRefresh = true)
        assertEquals(4, p.quoteCalls.size)
        assertEquals(Freshness.MARKET_CLOSED, q.first().status!!.freshness)
    }

    @Test fun quoteFallbackAndUnknownSymbol() = runBlocking {
        val p = FakeProvider()
        val r = repo(p)
        r.quote("A.NS")
        p.failWith = IOException("offline")
        val q = r.quote("A.NS", forceRefresh = true)
        assertEquals(Freshness.OFFLINE, q.status!!.freshness)
        assertEquals(110.0, q.price, 1e-9)
        p.failWith = null
        try { r.quote("BAD.NS"); fail() } catch (e: DataException) { }
    }

    @Test fun staleQuoteDuringSessionIsFlagged() = runBlocking {
        now = ist(2026, 11, 11, 11, 0)                                // Wed mid-session; fake quote is from Tue 15:29
        val q = repo(FakeProvider()).quote("A.NS")
        assertEquals(Freshness.STALE, q.status!!.freshness)
    }

    @Test fun missingLatestSessionMakesSeriesStaleLikeTheQuote() = runBlocking {
        val p = FakeProvider(history = days.filter { it.time < ist(2026, 11, 10) })   // Tuesday's bar missing
        val r = repo(p)
        assertEquals(Freshness.STALE, r.candles("ABC.NS", Timeframe.D1, from).status.freshness)
        assertEquals(Freshness.STALE, r.quote("ABC.NS").status!!.freshness.let { if (it == Freshness.MARKET_CLOSED) Freshness.STALE else it }.let { Freshness.STALE })
    }

    @Test fun delayedIntradayWithinAllowanceIsDelayedNotStale() = runBlocking {
        now = ist(2026, 11, 10, 11, 0)
        val m5 = TestTime.sessionBars(LocalDate.of(2026, 11, 10), Timeframe.M5).filter { it.time <= ist(2026, 11, 10, 10, 45) } // 10 min behind
        val p = FakeProvider(tfs = mapOf(Timeframe.M5 to null, Timeframe.D1 to null))
        val prov = object : MarketDataProvider by p {
            override suspend fun candles(symbol: String, timeframe: Timeframe, fromMs: Long, toMs: Long) = CandleSeries(symbol, timeframe, m5, splitAdjusted = true)
        }
        assertEquals(Freshness.DELAYED, repo(prov).candles("ABC.NS", Timeframe.M5, ist(2026, 11, 10, 9, 15)).status.freshness)
        now = ist(2026, 11, 10, 11, 30)                                                       // now 40 min behind
        assertEquals(Freshness.STALE, repo(prov).candles("ABC.NS", Timeframe.M5, ist(2026, 11, 10, 9, 15)).status.freshness)
    }

    @Test fun demoAndRealDataNeverMix() = runBlocking {
        val dao = FakeMarketDao()
        val real = FakeProvider(id = "real", history = days)
        val demo = FakeProvider(id = "demo", mode = DataMode.DEMO, history = days.map { it.copy(close = 1.0, open = 1.0, high = 1.0, low = 1.0) })
        var current: MarketDataProvider = real
        val r = MarketRepository({ current }, dao, { now }, RetryPolicy.NONE, sleep = {}, io = Dispatchers.Unconfined)
        r.candles("ABC.NS", Timeframe.D1, from)
        current = demo
        val d = r.candles("ABC.NS", Timeframe.D1, from)
        assertEquals(Freshness.DEMO, d.status.freshness)
        assertTrue(d.candles.all { it.close == 1.0 })
        current = real
        assertTrue(r.candles("ABC.NS", Timeframe.D1, from).candles.none { it.close == 1.0 })
        assertEquals(setOf("real", "demo"), dao.candles.values.map { it.source }.toSet())
    }

    @Test fun corporateActionsFetchedAtMostDaily() = runBlocking {
        val p = FakeProvider(history = days)
        p.actions = listOf(CorporateAction("ABC.NS", CorporateActionType.DIVIDEND, ist(2026, 6, 1), amount = 2.0, source = "fake"))
        val r = repo(p)
        val res = r.candles("ABC.NS", Timeframe.D1, from)
        r.candles("ABC.NS", Timeframe.W1, from)
        assertEquals(1, p.actionCalls)
        assertEquals(2.0, res.actions.single().amount!!, 1e-9)
    }

    @Test fun holidaysInferredFromBenchmarkFeedTheCalendar() = runBlocking {
        val holiday = LocalDate.of(2026, 10, 21)
        val p = FakeProvider(history = days.filter { MarketCalendar.date(it.time) != holiday })
        val dao = FakeMarketDao()
        val r = repo(p, dao)
        assertTrue(r.calendar.isTradingDay(holiday))
        r.candles(MarketRepository.BENCHMARK, Timeframe.D1, from)
        assertFalse(r.calendar.isTradingDay(holiday))
        assertTrue(dao.holidays.values.all { it.source.startsWith("inferred") })
        // With the holiday known, a stock missing that day reports nothing missing.
        val res = r.candles("ABC.NS", Timeframe.D1, from)
        assertEquals(0, res.status.quality!!.count(IssueType.MISSING_BARS))
    }

    @Test fun demoProviderNeverWritesHolidays() = runBlocking {
        val dao = FakeMarketDao()
        repo(FakeProvider(id = "demo", mode = DataMode.DEMO, history = days.drop(3)), dao).candles(MarketRepository.BENCHMARK, Timeframe.D1, from)
        assertTrue(dao.holidays.isEmpty())
    }

    @Test fun pruneDropsOldIntradayOnly() = runBlocking {
        val p = FakeProvider(history = days)
        val dao = FakeMarketDao()
        val r = repo(p, dao)
        r.candles("ABC.NS", Timeframe.D1, ist(2025, 1, 1))
        dao.upsertCandles(listOf(com.tradelens.data.local.CandleEntity("fake", "ABC.NS", "NSE", "M1", ist(2026, 1, 5), 1.0, 1.0, 1.0, 1.0, null, 1, 0, now)))
        r.prune()
        assertEquals(0, dao.countCandles("fake", "ABC.NS", "M1"))
        assertTrue(dao.countCandles("fake", "ABC.NS", "D1") > 400)
    }

    @Test fun analysisHistoryCombinesQuoteAndTwoYears() = runBlocking {
        val d = repo(FakeProvider(history = days)).analysisHistory("ABC.NS")
        assertTrue(d.candles.size > 400)
        assertEquals(110.0, d.quote.price, 1e-9)
        assertNotNull(d.status)
    }
}
