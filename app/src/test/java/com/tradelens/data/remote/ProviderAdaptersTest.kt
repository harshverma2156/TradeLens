package com.tradelens.data.remote

import com.tradelens.marketdata.CorporateActionType
import com.tradelens.marketdata.DataMode
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.TestTime.ist
import com.tradelens.marketdata.Timeframe
import com.tradelens.marketdata.UnsupportedCapabilityException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Adapters tested against canned HTTP responses shaped like the real APIs. No live network. */
class ProviderAdaptersTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private fun <T> api(cls: Class<T>): T = Retrofit.Builder().baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(cls)

    @Before fun up() { server = MockWebServer().also { it.start() } }
    @After fun down() = server.shutdown()

    private fun s(t: Long) = t / 1000

    @Test fun yahooDailyParsesAdjCloseAndDropsNullBars() = runBlocking {
        val t0 = s(ist(2026, 11, 5)); val t1 = s(ist(2026, 11, 6)); val t2 = s(ist(2026, 11, 9))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"symbol":"ABC.NS","currency":"INR","regularMarketPrice":103.0,"regularMarketTime":${t2 + 22000}},
             "timestamp":[$t0,$t1,$t2],
             "indicators":{"quote":[{"open":[100,null,102],"high":[101,null,104],"low":[99,null,101],"close":[100.5,null,103],"volume":[10,0,30]}],
                           "adjclose":[{"adjclose":[99.5,null,102.0]}]}}],"error":null}}
        """.trimIndent()))
        val y = YahooFinanceProvider(api(YahooApi::class.java))
        val r = y.candles("ABC.NS", Timeframe.D1, ist(2026, 11, 1), ist(2026, 11, 10))
        assertEquals(2, r.candles.size)                    // null bar dropped, not guessed
        assertEquals(listOf(99.5, 102.0), r.adjClose)      // still aligned after the drop
        assertTrue(r.splitAdjusted)
        assertEquals(100.5, r.meta!!.previousClose!!, 1e-9) // last close before the quote's session
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("interval=1d"))
        assertTrue(req.path!!.contains("period1=") && req.path!!.contains("period2="))
    }

    @Test fun yahooIntervalMappingAndNoAdjCloseIntraday() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"chart":{"result":[{"meta":{"symbol":"ABC.NS","regularMarketPrice":1},"timestamp":[],"indicators":{"quote":[{}]}}]}}"""))
        val y = YahooFinanceProvider(api(YahooApi::class.java))
        val r = y.candles("ABC.NS", Timeframe.H1, ist(2026, 11, 1), ist(2026, 11, 10))
        assertNull(r.adjClose)
        assertTrue(server.takeRequest().path!!.contains("interval=60m"))
    }

    @Test fun yahooCorporateActions() = runBlocking {
        val ex = s(ist(2025, 8, 26))
        server.enqueue(MockResponse().setBody("""
            {"chart":{"result":[{"meta":{"symbol":"HDFCBANK.NS"},"timestamp":[],"indicators":{"quote":[{}]},
             "events":{"splits":{"$ex":{"date":$ex,"numerator":2.0,"denominator":1.0,"splitRatio":"2:1"}},
                       "dividends":{"$ex":{"date":$ex,"amount":5.5}}}}]}}
        """.trimIndent()))
        val acts = YahooFinanceProvider(api(YahooApi::class.java)).corporateActions("HDFCBANK.NS", 0, ist(2026, 11, 10))
        assertEquals(2.0, acts.first { it.type == CorporateActionType.SPLIT }.ratio!!, 1e-9)
        assertEquals(5.5, acts.first { it.type == CorporateActionType.DIVIDEND }.amount!!, 1e-9)
        assertTrue(server.takeRequest().path!!.contains("events=div%2Csplit"))
    }

    @Test fun yahooBatchQuotesUseSparkAndRespectLimit() = runBlocking {
        val t = s(ist(2026, 11, 9)); val tp = s(ist(2026, 11, 6))
        // Real spark shape (verified): closes only, and chartPreviousClose = close BEFORE the range (a trap).
        val t0 = s(ist(2026, 11, 3))
        server.enqueue(MockResponse().setBody("""
            {"spark":{"result":[
              {"symbol":"A.NS","response":[{"meta":{"symbol":"A.NS","regularMarketPrice":11,"chartPreviousClose":7,"regularMarketTime":${t + 20000}},"timestamp":[$t0,$tp,$t],"indicators":{"quote":[{"close":[8,10,11]}]}}]},
              {"symbol":"B.NS","response":[{"meta":{"symbol":"B.NS","regularMarketPrice":21,"chartPreviousClose":17,"regularMarketTime":${t + 20000}},"timestamp":[$t0,$tp,$t],"indicators":{"quote":[{"close":[18,20,21]}]}}]}
            ]}}
        """.trimIndent()))
        val y = YahooFinanceProvider(api(YahooApi::class.java))
        val q = y.quotes(listOf("A.NS", "B.NS", "BAD.NS"))
        assertEquals(listOf("A.NS", "B.NS"), q.map { it.symbol })   // unknown symbol omitted, not faked
        assertEquals(10.0, q[0].previousClose!!, 1e-9)          // previous session, not chartPreviousClose (7)
        assertEquals(20.0, q[1].previousClose!!, 1e-9)
        assertTrue(server.takeRequest().path!!.startsWith("/v7/finance/spark?symbols=A.NS%2CB.NS%2CBAD.NS"))
        try { y.quotes((1..21).map { "S$it.NS" }); fail("over batch limit") } catch (e: IllegalArgumentException) { }
    }

    @Test fun yahooHttpErrorsSurface() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"chart":{"result":null,"error":{"code":"Unprocessable","description":"1m data not available"}}}"""))
        try {
            YahooFinanceProvider(api(YahooApi::class.java)).candles("ABC.NS", Timeframe.M1, 0, ist(2026, 11, 10))
            fail()
        } catch (e: HttpException) { assertEquals(422, e.code()) }
    }

    @Test fun backendStartsConservativeThenLoadsCapabilities() = runBlocking {
        val b = BackendMarketDataProvider(api(BackendApi::class.java))
        assertEquals(setOf(Timeframe.D1), b.info.capabilities.timeframes.keys)
        try { b.candles("A.NS", Timeframe.M5, 0, 1); fail() } catch (e: UnsupportedCapabilityException) { }
        server.enqueue(MockResponse().setBody("""
            {"id":"kite","name":"Zerodha Kite Connect (via TradeLens server)","mode":"REALTIME","licensedForDisplay":true,
             "timeframes":{"1m":5184000000,"5m":null,"1d":null,"1w":null,"bogus":1},
             "batchQuotes":500,"adjustedClose":false,"splitAdjustedOhlc":false,"corporateActions":false,
             "indices":true,"sectorIndices":true,"search":true,"marketDepth":true,"permitsPerSecond":3.0,"burst":3}
        """.trimIndent()))
        val info = b.refreshInfo()
        assertEquals("backend:kite", info.id)
        assertEquals(DataMode.REALTIME, info.mode)
        assertEquals(setOf(Timeframe.M1, Timeframe.M5, Timeframe.D1, Timeframe.W1), info.capabilities.timeframes.keys)
        assertEquals(5_184_000_000L, info.lookbackLimit(Timeframe.M1))
        assertFalse(info.supports(Timeframe.M30))
        server.enqueue(MockResponse().setBody("""{"symbol":"A.NS","timeframe":"5m","splitAdjusted":false,"bars":[{"t":1,"o":1,"h":2,"l":0.5,"c":1.5,"v":9}]}"""))
        val c = b.candles("A.NS", Timeframe.M5, 0, 10)
        assertFalse(c.splitAdjusted)
        assertNull(c.adjClose)
        assertEquals(1.5, c.candles.single().close, 1e-9)
        server.takeRequest()
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("timeframe=5m"))
        assertNull("app sends no provider secrets", req.getHeader("X-Kite-Secret"))
    }

    @Test fun demoDataIsLabelledDeterministicAndNeverInTheFuture() = runBlocking {
        val now = ist(2026, 11, 10, 11, 0)        // mid-session
        val demo = DemoMarketDataProvider(now = { now })
        assertEquals(DataMode.DEMO, demo.info.mode)
        val q = demo.quotes(listOf("RELIANCE.NS")).single()
        assertTrue(q.name!!.endsWith("(demo)"))
        assertTrue(q.asOf!! <= now)
        val a = demo.candles("RELIANCE.NS", Timeframe.M5, ist(2026, 11, 10, 9, 15), now)
        val b = demo.candles("RELIANCE.NS", Timeframe.M5, ist(2026, 11, 10, 9, 15), now)
        assertEquals(a.candles, b.candles)
        assertTrue(a.candles.all { it.time < now })
        assertTrue(a.candles.all { MarketCalendar().bucketStart(it.time, Timeframe.M5) == it.time })
        assertEquals(21, a.candles.size)          // 09:15 … 10:55
        val d = demo.candles("RELIANCE.NS", Timeframe.D1, ist(2026, 10, 1), now)
        assertEquals(q.price, d.candles.last().close, 1e-9)   // today's partial daily bar matches the quote
    }
}
