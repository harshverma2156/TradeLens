package com.tradelens.ta

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.TestTime.ist
import com.tradelens.marketdata.Timeframe
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** Look-ahead, warm-up, edge-case and engine-level behaviour. */
class EngineRobustnessTest {
    private val candles get() = ReferenceImplementationTest.candles

    // ---------------- no future-candle leakage

    /** Every series must be causal: computing on a prefix gives identical values for that prefix. */
    @Test fun noIndicatorUsesFutureBars() {
        val full = candles.take(900)
        val series: Map<String, (List<Candle>) -> List<Double?>> = mapOf(
            "ema50" to { c -> Rolling.ema(c.map { it.close }, 50) },
            "sma200" to { c -> Rolling.sma(c.map { it.close }, 200) },
            "rsi" to { c -> MomentumCalc.rsi(c.map { it.close }) },
            "macd" to { c -> MomentumCalc.macd(c.map { it.close }).hist },
            "stochK" to { c -> MomentumCalc.stochastic(c).k },
            "stochRsiD" to { c -> MomentumCalc.stochRsi(c.map { it.close }).d },
            "cci" to { c -> MomentumCalc.cci(c) },
            "williams" to { c -> MomentumCalc.williamsR(c) },
            "atr" to { c -> VolatilityCalc.atr(c) },
            "bbUpper" to { c -> VolatilityCalc.bollinger(c.map { it.close }).upper },
            "kcLower" to { c -> VolatilityCalc.keltner(c).lower },
            "dcUpper" to { c -> VolatilityCalc.donchian(c).upper },
            "adx" to { c -> TrendCalc.dmi(c).adx },
            "supertrend" to { c -> TrendCalc.supertrend(c).line },
            "psar" to { c -> TrendCalc.psar(c).value },
            "kijun" to { c -> TrendCalc.ichimoku(c).kijun },
            "spanB" to { c -> TrendCalc.ichimoku(c).spanB },
            "obv" to { c -> VolumeCalc.obv(c) },
            "adl" to { c -> VolumeCalc.accumulationDistribution(c) },
            "mfi" to { c -> VolumeCalc.mfi(c) },
            "avwap" to { c -> VolumeCalc.anchoredVwap(c, 100) },
        )
        for ((name, f) in series) {
            val all = f(full)
            for (k in listOf(150, 333, 700)) {
                val pre = f(full.take(k))
                for (i in 0 until k) assertEquals("$name leaks future data at bar $i (prefix $k)", all[i], pre[i])
            }
        }
    }

    @Test fun ichimokuCloudForTodayUsesOnlyPastBars() {
        val c = candles.take(400)
        val ich = TrendCalc.ichimoku(c)
        val pre = TrendCalc.ichimoku(c.take(300))
        assertEquals(pre.cloudA(299), ich.cloudA(299))
        assertEquals(ich.spanA[299 - 26], ich.cloudA(299))
    }

    @Test fun formingBarIsExcludedFromSignals() {
        val day = LocalDate.of(2026, 11, 10)
        val bars = com.tradelens.marketdata.TestTime.dailyBars(LocalDate.of(2025, 1, 1), day)
        val during = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, bars, ist(2026, 11, 10, 11, 0))
        assertEquals(bars.last(), during.formingBar)
        assertEquals(bars[bars.size - 2].time, during.lastClosedBarTime)
        assertTrue(during.readings.all { it.barTime == bars[bars.size - 2].time })
        val after = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, bars, ist(2026, 11, 10, 16, 0))
        assertNull(after.formingBar)
        assertEquals(bars.last().time, after.lastClosedBarTime)
    }

    @Test fun barEndPerTimeframe() {
        val t = ist(2026, 11, 10, 15, 15)
        assertEquals(ist(2026, 11, 10, 15, 30), TechnicalEngine.barEnd(t, Timeframe.H1))          // short last hour
        assertEquals(ist(2026, 11, 10, 15, 30), TechnicalEngine.barEnd(t, Timeframe.M15))
        assertEquals(ist(2026, 11, 13, 15, 30), TechnicalEngine.barEnd(ist(2026, 11, 9), Timeframe.W1))   // Friday
        assertEquals(ist(2026, 11, 30, 15, 30), TechnicalEngine.barEnd(ist(2026, 11, 2), Timeframe.MN1))
    }

    // ---------------- warm-up and insufficient history

    @Test fun shortHistoryMarksIndicatorsNotReady() {
        val r = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, candles.take(30), Long.MAX_VALUE)
        val ema200 = r.reading("ema200")!!
        assertFalse(ema200.ready); assertNull(ema200.value); assertEquals(0, ema200.strength)
        assertTrue(ema200.explanation.contains("needs about 200"))
        assertTrue(r.reading("rsi")!!.ready)
        assertFalse(r.reading("ichimoku")!!.ready)
        assertTrue("not-ready readings never vote", r.groups.all { g -> g.ready == r.readings.count { it.group == g.group && it.ready } })
    }

    @Test fun emptyAndTinyInputsDoNotCrash() {
        for (n in 0..3) {
            val r = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, candles.take(n), Long.MAX_VALUE)
            assertEquals(Bias.NEUTRAL, r.summary.bias)
        }
    }

    // ---------------- flat markets, gaps, missing candles, extreme values

    private fun assertAllFinite(r: TechnicalReport) {
        for (x in r.readings) {
            x.value?.let { assertTrue("${x.id} value not finite: $it", it.isFinite()) }
            x.components.forEach { (k, v) -> v?.let { assertTrue("${x.id}.$k not finite", it.isFinite()) } }
            assertTrue("${x.id} strength out of range", x.strength in 0..100)
        }
        assertTrue(r.summary.score in -100..100)
    }

    @Test fun flatMarketIsNeutralAndFinite() {
        val flat = (0 until 300).map { Candle(ist(2025, 1, 1) + it * 86_400_000L, 100.0, 100.0, 100.0, 100.0, 1_000) }
        val r = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, flat, Long.MAX_VALUE)
        assertAllFinite(r)
        assertEquals(50.0, r.reading("rsi")!!.value!!, 1e-9)
        assertEquals(Bias.NEUTRAL, r.summary.bias)
    }

    @Test fun gapsAndExtremeValuesStayFinite() {
        val rnd = Random(3)
        var px = 1e-4
        val tiny = (0 until 400).map { k -> px *= if (k % 50 == 0) 1.5 else 1 + rnd.nextDouble(-0.02, 0.02); Candle(k * 86_400_000L, px, px * 1.01, px * 0.99, px, 1) }
        assertAllFinite(TechnicalEngine.analyze("TINY.NS", Timeframe.D1, tiny, Long.MAX_VALUE))
        px = 1e9
        val huge = (0 until 400).map { k -> px *= 1 + rnd.nextDouble(-0.02, 0.02); Candle(k * 86_400_000L, px, px * 1.01, px * 0.99, px, Long.MAX_VALUE / 4) }
        val r = TechnicalEngine.analyze("HUGE.NS", Timeframe.D1, huge, Long.MAX_VALUE)
        assertAllFinite(r)
        assertTrue(r.oscillators.obv.all { it.isFinite() })                                     // Double sums: no Long overflow
    }

    @Test fun missingCandlesOnlyShiftTheWindow() {
        // Indicators are defined on bars, not on calendar time: a missing day means the window spans one more day.
        // Session VWAP and pivots, which ARE calendar-based, still pick the right day/week.
        val all = com.tradelens.marketdata.TestTime.sessionBars(LocalDate.of(2026, 11, 9), Timeframe.M15) +
            com.tradelens.marketdata.TestTime.sessionBars(LocalDate.of(2026, 11, 11), Timeframe.M15, start = 200.0)   // 10 Nov missing
        val vwap = VolumeCalc.sessionVwap(all, true)
        val firstOfDay3 = all.indexOfFirst { MarketCalendar.date(it.time) == LocalDate.of(2026, 11, 11) }
        assertEquals((all[firstOfDay3].high + all[firstOfDay3].low + all[firstOfDay3].close) / 3, vwap[firstOfDay3]!!, 1e-9)
        val p = LevelsCalc.pivotsFor(all, Timeframe.M15)!!
        assertEquals("previous day", p.basis)
        val day1 = all.filter { MarketCalendar.date(it.time) == LocalDate.of(2026, 11, 9) }
        assertEquals((day1.maxOf { it.high } + day1.minOf { it.low } + day1.last().close) / 3, p.pivot, 1e-9)
    }

    // ---------------- timeframes are independent

    @Test fun eachTimeframeUsesOnlyItsOwnBars() {
        val m15 = com.tradelens.marketdata.TestTime.sessionBars(LocalDate.of(2026, 11, 10), Timeframe.M15)
        val r = TechnicalEngine.analyze("ABC.NS", Timeframe.M15, m15, ist(2026, 11, 10, 18, 0))
        assertTrue(r.readings.all { it.timeframe == Timeframe.M15 })
        assertTrue(r.reading("vwap")!!.ready)
        val d = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, candles.take(300), Long.MAX_VALUE)
        assertFalse("session VWAP doesn't apply to daily bars", d.reading("vwap")!!.ready)
    }

    // ---------------- signal interpretation

    @Test fun everyReadingHasTheRequiredFields() {
        val r = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, candles, Long.MAX_VALUE)
        val ids = r.readings.map { it.id }.toSet()
        val required = listOf("ema9", "ema20", "ema21", "ema50", "ema100", "ema200", "sma50", "sma100", "sma200", "supertrend", "adx", "ichimoku", "psar",
            "rsi", "macd", "stoch", "stochrsi", "cci", "williams", "atr", "bollinger", "keltner", "donchian",
            "obv", "vwap", "avwap", "adl", "mfi", "vprofile", "fib", "pivots", "sr")
        assertEquals(required.toSet() - ids, emptySet<String>())
        for (x in r.readings.filter { it.ready }) {
            assertTrue(x.name.isNotBlank() && x.explanation.isNotBlank() && x.formula.isNotBlank())
            assertEquals(Timeframe.D1, x.timeframe)
            assertEquals(candles.last().time, x.barTime)
            assertTrue(x.computedAt > 0)
        }
    }

    @Test fun trendingMarketsProduceMatchingSummaries() {
        fun trend(drift: Double) = (0 until 400).map { k ->
            val c = 100 * Math.pow(1 + drift, k.toDouble()) * (1 + 0.004 * Math.sin(k / 3.0))
            Candle(k * 86_400_000L, c * (1 - drift / 2), c * 1.006, c * 0.994, c, (1_000_000 * (1 + if (drift > 0) 0.3 * (k % 2) else 0.0)).toLong())
        }
        assertEquals("Buy", TechnicalEngine.analyze("UP.NS", Timeframe.D1, trend(0.004), Long.MAX_VALUE).summary.label)
        assertEquals("Sell", TechnicalEngine.analyze("DN.NS", Timeframe.D1, trend(-0.004), Long.MAX_VALUE).summary.label)
    }

    @Test fun correlatedOscillatorsShareOneVote() {
        val r = TechnicalEngine.analyze("ABC.NS", Timeframe.D1, candles, Long.MAX_VALUE)
        val osc = r.groups.single { it.group == SignalGroup.MOMENTUM_OSCILLATORS }
        assertEquals(5, osc.members.size)
        assertEquals("five oscillators, one family vote", 1, r.groups.count { it.group == SignalGroup.MOMENTUM_OSCILLATORS })
        assertTrue(r.readings.filter { it.group == SignalGroup.CONTEXT }.isNotEmpty())
        assertFalse(SignalGroup.CONTEXT.votes)
    }

    @Test fun oneFamilyCannotProduceABuy() {
        fun g(group: SignalGroup, score: Int) = GroupVote(group, score, TechnicalEngine.biasOf(score, 15), listOf(group.label), 1)
        val voting = SignalGroup.entries.filter { it.votes }
        // One family screaming bullish, every other family neutral → Neutral.
        val one = voting.map { if (it == SignalGroup.MOMENTUM_OSCILLATORS) g(it, 100) else g(it, 0) }
        assertEquals("Neutral", TechnicalEngine.summarize(one).label)
        // Two strongly bullish families still aren't enough.
        val two = voting.map { if (it == SignalGroup.MOMENTUM_OSCILLATORS || it == SignalGroup.MOVING_AVERAGES) g(it, 100) else g(it, 0) }
        assertEquals("Neutral", TechnicalEngine.summarize(two).label)
        // Broad agreement → Buy.
        val broad = voting.map { g(it, 60) }
        assertEquals("Buy", TechnicalEngine.summarize(broad).label)
        // Bulls only marginally outnumbering bears → Neutral.
        val split = voting.mapIndexed { k, it -> g(it, if (k < 4) 80 else -20) }
        assertEquals("Neutral", TechnicalEngine.summarize(split).label)
    }

    // ---------------- caching and updates

    @Test fun cacheRecomputesOnlyWhenClosedBarsChange() {
        val cache = TechnicalCache()
        val day = LocalDate.of(2026, 11, 10)
        val bars = com.tradelens.marketdata.TestTime.dailyBars(LocalDate.of(2025, 6, 1), day)
        val during = ist(2026, 11, 10, 11, 0)
        val a = cache.get("yahoo", "ABC.NS", Timeframe.D1, bars, during)
        // The forming bar ticks: indicators unchanged → cache hit, new forming bar attached.
        val ticked = bars.dropLast(1) + bars.last().copy(close = bars.last().close + 5, high = bars.last().high + 5)
        val b = cache.get("yahoo", "ABC.NS", Timeframe.D1, ticked, during)
        assertEquals(1, cache.misses); assertEquals(1, cache.hits)
        assertEquals(a.readings, b.readings)
        assertEquals(ticked.last(), b.formingBar)
        // The bar closes → new key → recompute including it.
        val c = cache.get("yahoo", "ABC.NS", Timeframe.D1, ticked, ist(2026, 11, 10, 16, 0))
        assertEquals(2, cache.misses)
        assertEquals(ticked.last().time, c.lastClosedBarTime)
        // A different timeframe or source is a different entry.
        cache.get("demo", "ABC.NS", Timeframe.D1, ticked, ist(2026, 11, 10, 16, 0))
        assertEquals(3, cache.misses)
    }

    @Test fun engineIsFastEnoughForInteractiveUse() {
        val t0 = System.nanoTime()
        repeat(5) { TechnicalEngine.analyze("ABC.NS", Timeframe.D1, candles.take(1000), Long.MAX_VALUE) }
        val ms = (System.nanoTime() - t0) / 5 / 1_000_000
        assertTrue("one full analysis of 1,000 bars took $ms ms", ms < 500)
    }
}
