package com.tradelens.patterns

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.TestTime.ist
import com.tradelens.marketdata.Timeframe
import com.tradelens.ta.Bias
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.random.Random

/**
 * "Only information available at the signal timestamp is used."
 *
 * The engine stamps every state change with the bar at whose close it became knowable. If no step ever read a
 * later bar, then running on bars 0..t must reproduce exactly the full run's events known by t: same patterns,
 * same bars, same levels in the text, nothing more and nothing less.
 */
class NoLookAheadTest {

    companion object {
        /** Seeded random walk with trending, ranging and volatile regimes: plenty of patterns of every kind. */
        fun randomWalk(n: Int, seed: Int): List<Candle> {
            val rnd = Random(seed)
            val t = Shapes.days(n)
            var px = 500.0; var drift = 0.0; var vol = 0.012
            return (0 until n).map { i ->
                if (i % 120 == 0) { drift = rnd.nextDouble(-0.002, 0.002); vol = rnd.nextDouble(0.006, 0.02) }
                val o = px * (1 + rnd.nextDouble(-0.3, 0.3) * vol)
                val c = o * exp(drift + vol * (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5))
                val h = maxOf(o, c) * (1 + rnd.nextDouble() * vol * 0.6)
                val l = minOf(o, c) * (1 - rnd.nextDouble() * vol * 0.6)
                px = c
                Candle(t[i], o, h, l, c, (1_000_000 * exp(rnd.nextDouble(-0.5, 0.5)) * if (rnd.nextInt(25) == 0) 3 else 1).toLong())
            }
        }

        fun events(r: PatternReport) = r.patterns.flatMap { p -> p.events.map { p.id to it } }.toSet()
        fun eventsUpTo(r: PatternReport, t: Int) = r.patterns.flatMap { p -> p.events.filter { it.knownIndex <= t }.map { p.id to it } }.toSet()

        fun assertPrefixStable(c: List<Candle>, cuts: List<Int>, tag: String) {
            val full = PatternEngine.detect(tag, Timeframe.D1, c, Long.MAX_VALUE)
            for (t in cuts) {
                val pre = PatternEngine.detect(tag, Timeframe.D1, c.take(t + 1), Long.MAX_VALUE)
                val a = eventsUpTo(full, t); val b = events(pre)
                assertEquals("$tag: run on bars 0..$t differs from the full run's events known by $t.\n only in full: ${(a - b).take(3)}\n only in prefix: ${(b - a).take(3)}", a, b)
            }
        }
    }

    @Test fun prefixRunsReproduceTheFullRunSynthetic() {
        val c = randomWalk(1600, seed = 7)
        val full = PatternEngine.detect("RW", Timeframe.D1, c, Long.MAX_VALUE)
        assertTrue("fixture should be rich in patterns", full.patterns.map { it.type }.toSet().size >= 15)
        assertPrefixStable(c, (60 until c.size step 23).toList(), "RW")
    }

    @Test fun rewritingTheFutureNeverChangesPastEvents() {
        val a = randomWalk(1200, seed = 11)
        for (t in listOf(300, 650, 999)) {
            // Same history up to t, then a completely different future.
            val other = a.take(t + 1) + randomWalk(1200, seed = 99 + t).drop(t + 1).let { tail ->
                val k = a[t].close / tail.first().open
                tail.map { it.copy(open = it.open * k, high = it.high * k, low = it.low * k, close = it.close * k) }
            }
            assertEquals("future bars leaked into events known by $t",
                eventsUpTo(PatternEngine.detect("A", Timeframe.D1, a, Long.MAX_VALUE), t),
                eventsUpTo(PatternEngine.detect("A", Timeframe.D1, other, Long.MAX_VALUE), t))
        }
    }

    @Test fun swingPointsAreOnlyKnownAfterTheirConfirmationWindow() {
        val c = randomWalk(1500, seed = 3)
        val w = PatternConfig().pivotWindow
        Swings.pivots(c, w).forEach { s ->
            assertEquals(s.index + w, s.confirmedAt)
            // and it really is the extreme of the window, which needs the w bars after it
            val win = (s.index - w..s.index + w).map { if (s.high) c[it].high else c[it].low }
            assertEquals(if (s.high) win.max() else win.min(), s.price, 0.0)
        }
        for (p in PatternEngine.detect("RW", Timeframe.D1, c, Long.MAX_VALUE).patterns) {
            val det = p.events.first()
            when {
                p.type.kind == PatternKind.CANDLESTICK -> assertEquals("${p.type} known at the close of its last bar", p.endIndex, p.detectedIndex)
                p.type == PatternType.BULL_FLAG || p.type == PatternType.BEAR_FLAG -> assertTrue(p.detectedIndex >= p.anchors[1].index + w)
                p.type == PatternType.CUP_AND_HANDLE -> assertTrue(p.detectedIndex >= p.anchors[2].index + w)
                else -> assertTrue("${p.type}: detected at ${det.knownIndex} but its last swing at ${det.index} needs $w more bars", det.knownIndex >= det.index + w)
            }
            assertTrue(p.events.all { it.knownIndex >= it.index })
            assertTrue(p.events.zipWithNext().all { (x, y) -> y.knownIndex >= x.knownIndex })
        }
    }

    @Test fun higherTimeframeUsesOnlyCompletedHigherBars() {
        val c = randomWalk(700, seed = 5)
        val full = Context.higherTimeframeTrend(c, Timeframe.D1)
        // Truncating the series anywhere leaves every earlier value unchanged…
        for (t in listOf(120, 333, 500)) assertEquals(full.take(t), Context.higherTimeframeTrend(c.take(t), Timeframe.D1))
        // …and every day of a week sees the same weekly trend (it can't include its own, unfinished week).
        val cal = MarketCalendar()
        c.indices.groupBy { cal.bucketStart(c[it].time, Timeframe.W1) }.values.forEach { days -> assertEquals(1, days.map { full[it] }.toSet().size) }
        assertEquals(Timeframe.W1, Context.higherOf(Timeframe.D1))
        assertEquals(Timeframe.D1, Context.higherOf(Timeframe.M15))
        assertNull(Context.higherOf(Timeframe.MN1))
    }

    @Test fun higherTimeframeTrendReadsTheDirection() {
        fun trend(step: Double) = Shapes.path(0 to 100.0, 500 to 100.0 + 500 * step)
        assertEquals(Bias.BULLISH, Context.higherTimeframeTrend(trend(0.2), Timeframe.D1).last())
        assertEquals(Bias.BEARISH, Context.higherTimeframeTrend(trend(-0.1), Timeframe.D1).last())
    }

    @Test fun formingBarIsExcluded() {
        val bars = com.tradelens.marketdata.TestTime.dailyBars(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 10))
        val during = PatternEngine.analyze("ABC.NS", Timeframe.D1, bars, ist(2026, 11, 10, 11, 0))
        assertEquals(bars.size - 1, during.candleCount)
        assertEquals(bars[bars.size - 2].time, during.lastBarTime)
        assertTrue(during.patterns.all { p -> p.events.all { it.knownIndex <= bars.size - 2 } })
        assertEquals(bars.size, PatternEngine.analyze("ABC.NS", Timeframe.D1, bars, ist(2026, 11, 10, 16, 0)).candleCount)
    }

    @Test fun zigzagFiltersNoiseAndKeepsTheExtreme() {
        val atr = List(100) { 1.0 }
        val z = Swings.ZigZag(atr, 1.5)
        assertTrue(z.add(Swing(10, 100.0, true, 15)))
        assertFalse("smaller high ignored", z.add(Swing(12, 99.0, true, 17)))
        assertTrue("higher high replaces", z.add(Swing(14, 101.0, true, 19)))
        assertFalse("a 1-ATR dip is noise", z.add(Swing(20, 100.0, false, 25)))
        assertTrue(z.add(Swing(25, 98.0, false, 30)))
        assertEquals(listOf(14, 25), z.points.map { it.index })
    }

    @Test fun realHistoryPrefixRunsReproduceTheFullRun() {
        for (s in History.symbols) {
            val c = History.load(s)
            assertPrefixStable(c, listOf(400, 1111, 1800, 2400, c.size - 7), s)
        }
    }
}
