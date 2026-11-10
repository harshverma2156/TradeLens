package com.tradelens.analysis

import com.tradelens.data.model.Candle
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AnalysisEngineTest {
    private val day = 86_400_000L
    private val monday = 1_704_067_200_000L  // 2024-01-01 00:00 UTC, a Monday

    /** Weekday-only daily candles with a constant drift plus noise. */
    private fun series(drift: Double, seed: Int, n: Int = 500, start: Double = 1000.0): List<Candle> {
        val r = Random(seed)
        var p = start
        val out = mutableListOf<Candle>()
        var t = monday
        while (out.size < n) {
            val dow = ((t - monday) / day) % 7
            if (dow < 5) {
                val o = p
                val c = p * (1 + drift + r.nextDouble(-0.015, 0.015))
                p = c
                val vol = (1_000_000 * (if (c > o) 1.3 else 0.8)).toLong()
                out += Candle(t, o, maxOf(o, c) * 1.005, minOf(o, c) * 0.995, c, vol)
            }
            t += day
        }
        return out
    }

    private fun analyze(c: List<Candle>, bench: List<Candle>? = null, capital: Double = 100_000.0) =
        AnalysisEngine.analyze(c, c.last().close, null, null, capital, 1.0, benchmark = bench)!!

    @Test fun rsiOfRisingSeriesIs100() = assertEquals(100.0, Ta.rsi((1..30).map { it.toDouble() }).last()!!, 1e-9)

    @Test fun weeklyResampleGroupsFiveDays() {
        val c = series(0.0, 3, n = 20)
        val w = Ta.weekly(c)
        assertEquals(4, w.candles.size)
        assertEquals(c[4].close, w.candles[0].close, 1e-9)
        assertEquals(c.subList(0, 5).maxOf { it.high }, w.candles[0].high, 1e-9)
        assertEquals(3, w.weekOfDay.last())
    }

    @Test fun supertrendFollowsTrend() {
        assertEquals(true, Ta.supertrend(series(0.004, 1)).up.last())
        assertEquals(false, Ta.supertrend(series(-0.004, 1)).up.last())
    }

    @Test fun volumeProfilePocInsideRange() {
        val c = series(0.0, 5, n = 120)
        val vp = Ta.volumeProfile(c)!!
        assertTrue(vp.poc in c.minOf { it.low }..c.maxOf { it.high })
        assertTrue(vp.valueLow <= vp.poc && vp.poc <= vp.valueHigh)
    }

    @Test fun uptrendIsBuyWithEntryAtOrBelowPrice() {
        val c = series(0.003, 7)
        val a = analyze(c, bench = series(0.0005, 99))
        assertTrue("score ${a.score}", a.score >= AnalysisEngine.BUY_AT)
        assertTrue(a.signal.isBuy)
        assertEquals(Direction.LONG, a.plan.direction)
        assertTrue(a.entry.price <= c.last().close + 0.05)
        assertTrue(a.plan.stopLoss < a.plan.entryLow)
        assertTrue(a.plan.targets.all { it.price > a.plan.entry })
        assertTrue(a.plan.maxLoss <= 1_000.0 + 1e-6)
        assertTrue(a.pillars.any { it.pillar == Pillar.RELATIVE && it.score > 0 })
        assertTrue(a.confidence in 0..100)
    }

    @Test fun downtrendIsSellWithShortPlan() {
        val c = series(-0.003, 7)
        val a = analyze(c, bench = series(0.0005, 99))
        assertTrue(a.signal.isSell)
        assertEquals(EntryType.AVOID, a.entry.type)
        assertTrue(a.plan.stopLoss > a.plan.entryHigh)
    }

    @Test fun backtestRunsOnTwoYears() {
        val bt = analyze(series(0.002, 11)).backtest!!
        assertTrue(bt.trades > 0)
        assertTrue(bt.winRate in 0.0..1.0)
        assertTrue(bt.baseWinRate in 0.0..1.0)
    }

    @Test fun worksWithoutBenchmark() {
        val a = analyze(series(0.003, 7))
        assertTrue(a.pillars.none { it.pillar == Pillar.RELATIVE || it.pillar == Pillar.MARKET })
        assertTrue(a.signal.isBuy)
    }

    @Test fun bounceInsideWeeklyDowntrendIsNotBuy() {
        // Long decline, then a sharp 3-week rally: daily turns up but the weekly trend is still down.
        val down = series(-0.004, 21, n = 400)
        val last = down.last().close
        val up = series(0.012, 22, n = 15, start = last).mapIndexed { k, cd -> cd.copy(time = down.last().time + (k + 1) * day * 7 / 5) }
        val a = analyze(down + up)
        assertFalse("signal ${a.signal}", a.signal.isBuy)
    }

    @Test fun shortHistoryReturnsNull() = assertNull(AnalysisEngine.analyze(series(0.0, 1, 40), 1000.0, null, null, 1e5, 1.0))
}
