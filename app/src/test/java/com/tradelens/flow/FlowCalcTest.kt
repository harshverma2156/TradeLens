package com.tradelens.flow

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.Freshness
import com.tradelens.patterns.Shapes
import com.tradelens.ta.Bias
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Volume, delivery and order-book analytics, with hand-worked fixtures. Missing inputs must come out as null, never 0. */
class FlowCalcTest {
    private val e = 1e-9

    private fun bars(closes: List<Double>, vols: List<Long>): List<Candle> {
        val t = Shapes.days(closes.size)
        return closes.indices.map { k -> Candle(t[k], closes[k], closes[k] + 0.5, closes[k] - 0.5, closes[k], vols[k]) }
    }

    private fun flat(n: Int, vol: Long = 1_000, lastVol: Long = vol): List<Candle> =
        bars(List(n) { 100.0 }, List(n) { if (it == n - 1) lastVol else vol })

    // ------------------------------------------------------------------ volume

    @Test fun relativeVolumeAndSpikeByHand() {
        // Last bar 3,000 against a 20-bar average of 1,000 → RVOL 3.0. It is also a spike (≥ 2×).
        val v = FlowCalc.volume(flat(31, lastVol = 3_000))!!
        assertEquals(1_000.0, v.average20!!, e)
        assertEquals(3.0, v.relativeVolume!!, e)
        assertEquals(3_000L, v.current)
        assertEquals(1, v.spikes.size)
        assertEquals(3.0, v.spikes[0].relativeVolume, e)
    }

    @Test fun intradayRelativeVolumeIsTimeAdjusted() {
        // Half the session gone and 3,000 traded: expected by now = 1,000 × 0.5 → RVOL 6.0, not 3.0.
        val v = FlowCalc.volume(flat(31, lastVol = 3_000), sessionFraction = 0.5)!!
        assertTrue(v.intradayPartial)
        assertEquals(6.0, v.relativeVolume!!, e)
    }

    @Test fun noVolumeMeansNullNotZero() {
        assertNull(FlowCalc.volume(flat(31, vol = 0)))                 // index-style series
        assertNull(FlowCalc.volume(flat(10)))                          // too short
    }

    @Test fun upDownVolumeRatioByHand() {
        // Closes alternate 100 (down) and 101 (up); volume 1,000 on down bars and 2,000 on up bars.
        // The 20-bar window ending at the last bar (index 30) has 10 up and 10 down bars: 10×2,000 ÷ 10×1,000 = 2.0.
        val closes = List(31) { k -> if (k % 2 == 1) 101.0 else 100.0 }
        val vols = List(31) { k -> if (k % 2 == 1) 2_000L else 1_000L }
        assertEquals(2.0, FlowCalc.volume(bars(closes, vols))!!.upDownVolumeRatio!!, e)
    }

    @Test fun obvAndAdChangesAreReported() {
        val v = FlowCalc.volume(flat(31))!!
        assertEquals(0.0, v.obvChange20!!, e)                          // unchanged closes move OBV by 0
        assertTrue(v.poc != null && v.valueAreaHigh != null && v.valueAreaLow != null)
    }

    // ------------------------------------------------------------------ delivery

    @Test fun deliveryStatsByHand() {
        // 15 days at 50% delivery (500 of 1,000), then 5 days at 80% (800 of 1,000).
        val days = (0 until 20).map { k -> DeliveryDay(Shapes.days(20)[k], 1_000, if (k < 15) 500 else 800) }
        val d = FlowCalc.delivery(days)!!
        assertEquals(80.0, d.latestPct!!, e)
        assertEquals(57.5, d.avgPct20!!, e)                            // (15×500 + 5×800) ÷ (20×1,000) = 11,500 ÷ 20,000
        assertEquals(22.5, d.trendPts!!, e)                            // 80 − 57.5
        assertEquals(800.0 / 575.0, d.deliveryVolumeTrend!!, e)        // 5-day avg deliverable ÷ 20-day avg
    }

    @Test fun deliveryNeedsTwentyDays() = assertNull(FlowCalc.delivery(List(19) { DeliveryDay(it.toLong(), 1_000, 500) }))

    // ------------------------------------------------------------------ order book

    private val book = DepthSnapshot(
        "T.NS",
        bids = listOf(DepthLevel(100.00, 300), DepthLevel(99.95, 200)),
        asks = listOf(DepthLevel(100.05, 100), DepthLevel(100.10, 100)),
        timestamp = 1_000L,
    )

    @Test fun depthAnalyticsByHand() {
        val d = FlowCalc.depth(book)!!
        assertEquals(2, d.comparableLevels)
        assertEquals(500L, d.bidQtyTopN)
        assertEquals(200L, d.askQtyTopN)
        assertEquals(0.05, d.spread, e)
        assertEquals(100.025, d.mid, e)
        assertEquals(0.05 / 100.025 * 10_000, d.spreadBps, 1e-9)
        assertEquals(300.0 / 700.0, d.imbalance, 1e-9)                 // (500 − 200) ÷ 700
        assertEquals(0.5, d.imbalanceTop1, e)                          // (300 − 100) ÷ 400
        assertEquals(2.5, d.bidAskRatio, e)
        assertEquals(0.6, d.bidConcentration, e)                       // 300 ÷ 500
        assertEquals(0.5, d.askConcentration, e)                       // 100 ÷ 200
        assertEquals(0.36 + 0.16, d.bidHhi, e)                         // 0.6² + 0.4²
        assertEquals(0.5, d.askHhi, e)                                 // 0.5² + 0.5²
    }

    @Test fun comparableLevelsUseTheSameCountOnBothSides() {
        // Asks have three levels, bids one: only the top level of each side is compared.
        val s = book.copy(bids = listOf(DepthLevel(100.0, 300)), asks = listOf(DepthLevel(100.05, 100), DepthLevel(100.1, 500), DepthLevel(100.2, 900)))
        val d = FlowCalc.depth(s)!!
        assertEquals(1, d.comparableLevels)
        assertEquals(0.5, d.imbalance, e)                              // (300 − 100) ÷ 400, the asks' 500 and 900 are ignored
    }

    @Test fun unusableBooksGiveNullNotZeroImbalance() {
        assertNull(FlowCalc.depth(book.copy(bids = emptyList())))
        assertNull(FlowCalc.depth(book.copy(asks = emptyList())))
        assertNull(FlowCalc.depth(book.copy(bids = listOf(DepthLevel(100.10, 300)))))   // crossed: bid ≥ ask
        assertNull(FlowCalc.depth(book.copy(bids = listOf(DepthLevel(100.0, 0)))))      // zero quantities
    }

    // ------------------------------------------------------------------ assessment

    @Test fun breakoutOnHeavyVolumeIsConfirmed() {
        // 25 flat bars at 100 (high 100.5), then a close at 103 on double volume: above the prior 20-bar high.
        val c = bars(List(26) { if (it < 25) 100.0 else 103.0 }, List(26) { if (it < 25) 1_000L else 2_000L })
        val a = FlowCalc.assess(c, FlowCalc.volume(c), null, null, null)
        val b = a.signals.single { it.kind == FlowSignalKind.BREAKOUT_VOLUME }
        assertEquals(Bias.BULLISH, b.bias)
        assertTrue(b.headline.contains("100.50"))
        assertEquals(Bias.BULLISH, a.bias)
    }

    @Test fun priceVolumeDivergenceIsFlagged() {
        // Rally to 109 on heavy volume, a fall back to 99 on heavy volume (OBV peaks near 40,000 then drops to 0),
        // then a slow recovery on light volume to a new 20-bar closing high (110) with OBV still far below its peak.
        val closes = (0..9).map { 100.0 + it } + (10..19).map { 109.0 - (it - 9) } + (20..29).map { 99.0 + (it - 19) * 0.8 } + listOf(110.0)
        val vols = List(20) { 5_000L } + List(11) { 100L }
        val a = FlowCalc.assess(bars(closes, vols), FlowCalc.volume(bars(closes, vols)), null, null, null)
        val d = a.signals.single { it.kind == FlowSignalKind.PRICE_VOLUME_DIVERGENCE }
        assertEquals(Bias.BEARISH, d.bias)
        assertTrue(a.bias != Bias.BULLISH)
    }

    @Test fun flatMarketHasNoSignalsAndNeutralScore() {
        val c = flat(40)
        val a = FlowCalc.assess(c, FlowCalc.volume(c), null, null, null)
        assertEquals(0, a.score)
        assertEquals(Bias.NEUTRAL, a.bias)
        assertEquals("No clear volume signal", a.label)
    }

    @Test fun missingDataIsSaidNotScored() {
        val c = flat(40, vol = 0)
        val a = FlowCalc.assess(c, null, null, null, null)
        assertTrue(a.context.any { it.contains("Volume is unavailable") })
        assertTrue(a.context.any { it.contains("Delivery data is unavailable") })
        assertEquals(0, a.score)
    }

    // ------------------------------------------------------------------ unavailable is never zero

    @Test fun reportMarksEveryUnsuppliedItemUnavailable() = runBlocking {
        val r = FlowService.report("T.NS", flat(40), UnavailableFlowSource, "Yahoo Finance", Freshness.MARKET_CLOSED, null, 0L)
        assertTrue(r.volume is DataPoint.Available)
        for (p in listOf(r.delivery, r.depth, r.depthAnalytics, r.fiiDii, r.shareholding, r.deals, r.disclosures)) {
            assertTrue("expected Unavailable, got $p", p is DataPoint.Unavailable)
            assertTrue((p as DataPoint.Unavailable).reason.isNotBlank())
        }
        assertTrue(r.assessment.reportedNotes.single().contains("unavailable"))
    }

    @Test fun reportKeepsSuppliedDepthAndProvenance() = runBlocking {
        val src = object : FlowDataSource by UnavailableFlowSource {
            override val name = "Test feed"
            override suspend fun depth(symbol: String) = DataPoint.Available(book, Provenance("Test feed", book.timestamp, 5L, Freshness.LIVE, false))
        }
        val r = FlowService.report("T.NS", flat(40), src, "Test feed", Freshness.LIVE, 5L, 9L)
        val dp = r.depth as DataPoint.Available
        assertEquals("Test feed", dp.source.provider)
        assertFalse("a snapshot is not reported as official", dp.source.official)
        assertNotNull(r.depthAnalytics.valueOrNull)
        assertTrue(r.assessment.depthNote!!.contains("momentary"))
    }
}
