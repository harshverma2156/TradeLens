package com.tradelens.ta

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.TestTime.ist
import com.tradelens.marketdata.Timeframe
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Hand-calculated fixtures: small inputs whose answers are worked out on paper (shown in comments). */
class IndicatorFixturesTest {
    private val e = 1e-9
    private fun bar(h: Double, l: Double, c: Double, v: Long = 100, o: Double = c, t: Long = 0) = Candle(t, o, h, l, c, v)

    // ---------------- primitives

    @Test fun smaByHand() = assertEquals(listOf(null, null, 2.0, 3.0, 4.0), Rolling.sma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3))

    @Test fun emaByHand() {
        // seed SMA(1,2,3) = 2 at i=2; α = 2/(3+1) = 0.5 → i3: 0.5·4 + 0.5·2 = 3; i4: 0.5·5 + 0.5·3 = 4
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), Rolling.ema(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3))
    }

    @Test fun wilderByHand() {
        // seed 2; α = 1/3 → i3: (2·2 + 4)/3 = 8/3; i4: (8/3·2 + 5)/3 = 31/9
        val r = Rolling.rma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertEquals(8.0 / 3, r[3]!!, e); assertEquals(31.0 / 9, r[4]!!, e)
    }

    @Test fun rollingExtremes() {
        val v = listOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0)
        assertEquals(listOf(null, null, 4.0, 4.0, 5.0, 9.0, 9.0), Rolling.highest(v, 3))
        assertEquals(listOf(null, null, 1.0, 1.0, 1.0, 1.0, 2.0), Rolling.lowest(v, 3))
    }

    // ---------------- momentum

    @Test fun rsiByHand() {
        // n = 2. Changes +1, −1 → avg gain .5, loss .5 → RSI 50 at i2.
        // i3 change +1: gain = (.5·1 + 1)/2 = .75, loss = (.5·1)/2 = .25 → RS 3 → RSI 75.
        val r = MomentumCalc.rsi(listOf(10.0, 11.0, 10.0, 11.0), 2)
        assertNull(r[1]); assertEquals(50.0, r[2]!!, e); assertEquals(75.0, r[3]!!, e)
        assertEquals(100.0, MomentumCalc.rsi((1..30).map { it.toDouble() }).last()!!, e)
        assertEquals(50.0, MomentumCalc.rsi(List(30) { 7.0 }).last()!!, e)                // flat: defined as neutral
    }

    @Test fun stochasticAndWilliamsByHand() {
        // HH = 12, LL = 7, C = 9 → fast %K = 100·(9−7)/(12−7) = 40; %R = −100·(12−9)/5 = −60
        val c = listOf(bar(10.0, 8.0, 9.0), bar(12.0, 9.0, 11.0), bar(11.0, 7.0, 9.0))
        assertEquals(40.0, MomentumCalc.stochastic(c, 3, 1, 1).fastK[2]!!, e)
        assertEquals(-60.0, MomentumCalc.williamsR(c, 3)[2]!!, e)
        val flat = List(5) { bar(5.0, 5.0, 5.0) }
        assertEquals(50.0, MomentumCalc.stochastic(flat, 3, 1, 1).fastK[4]!!, e)
        assertEquals(-50.0, MomentumCalc.williamsR(flat, 3)[4]!!, e)
    }

    @Test fun cciByHand() {
        // TP = 1, 2, 3; SMA = 2; MeanDev = (1+0+1)/3 = 2/3 → CCI = (3 − 2)/(0.015 · 2/3) = 100
        val c = listOf(bar(1.0, 1.0, 1.0), bar(2.0, 2.0, 2.0), bar(3.0, 3.0, 3.0))
        assertEquals(100.0, MomentumCalc.cci(c, 3)[2]!!, 1e-9)
        assertEquals(0.0, MomentumCalc.cci(List(5) { bar(4.0, 4.0, 4.0) }, 3)[4]!!, e)   // flat → 0, not NaN
    }

    @Test fun macdOfConstantSeriesIsZero() {
        val m = MomentumCalc.macd(List(60) { 100.0 })
        assertEquals(0.0, m.line.last()!!, e); assertEquals(0.0, m.signal.last()!!, e); assertEquals(0.0, m.hist.last()!!, e)
        assertNull(m.signal[32]); assertNotNull(m.signal[33])                              // 26 + 9 − 2 = bar 33
    }

    @Test fun stochRsiFlatIsNeutral() {
        val s = MomentumCalc.stochRsi(List(60) { 50.0 })
        assertEquals(50.0, s.d.last()!!, e)
    }

    // ---------------- volatility

    @Test fun atrAndTrueRangeIncludeGaps() {
        // Range 2 every bar → ATR 2. A gap-up bar (prev close 101, H 112, L 110) has TR = |112 − 101| = 11.
        val base = (0 until 20).map { bar(102.0, 100.0, 101.0) }
        assertEquals(2.0, VolatilityCalc.atr(base, 14).last()!!, e)
        val gap = base + bar(112.0, 110.0, 111.0)
        assertEquals(11.0, VolatilityCalc.trueRange(gap).last(), e)
        assertEquals((2.0 * 13 + 11) / 14, VolatilityCalc.atr(gap, 14).last()!!, e)
    }

    @Test fun bollingerOfConstantHasZeroWidth() {
        val b = VolatilityCalc.bollinger(List(25) { 50.0 })
        assertEquals(50.0, b.upper.last()!!, e); assertEquals(50.0, b.lower.last()!!, e)
        assertNull(b.percentB(List(25) { 50.0 }).last())                                    // undefined, not NaN
        // closes 1..20 → mean 10.5, population σ = √((20²−1)/12) = 5.766…
        val b2 = VolatilityCalc.bollinger((1..20).map { it.toDouble() })
        assertEquals(10.5 + 2 * Math.sqrt(399.0 / 12), b2.upper.last()!!, 1e-9)
    }

    @Test fun keltnerAndDonchianByHand() {
        val c = (0 until 30).map { bar(102.0, 98.0, 100.0) }                              // TR 4 every bar
        val k = VolatilityCalc.keltner(c)
        assertEquals(100.0, k.mid.last()!!, e); assertEquals(108.0, k.upper.last()!!, e); assertEquals(92.0, k.lower.last()!!, e)
        val d = VolatilityCalc.donchian(listOf(bar(5.0, 1.0, 3.0), bar(7.0, 2.0, 4.0), bar(6.0, 3.0, 5.0)), 3)
        assertEquals(7.0, d.upper[2]!!, e); assertEquals(1.0, d.lower[2]!!, e); assertEquals(4.0, d.mid[2]!!, e)
    }

    // ---------------- trend

    @Test fun dmiOnPerfectUptrend() {
        // Each bar: L = k, H = k + 2, C = k + 1. +DM = 1, −DM = 0, TR = 2 → +DI = 50, −DI = 0, DX = 100 → ADX = 100.
        val c = (0 until 40).map { k -> bar(k + 2.0, k.toDouble(), k + 1.0) }
        val d = TrendCalc.dmi(c, 14)
        assertNull(d.adx[26]); assertEquals(100.0, d.adx[27]!!, e)                         // ADX ready at 2n − 1
        assertEquals(50.0, d.plusDi.last()!!, e); assertEquals(0.0, d.minusDi.last()!!, e)
        val flat = TrendCalc.dmi(List(40) { bar(5.0, 5.0, 5.0) }, 14)
        assertEquals(0.0, flat.adx.last()!!, e)                                            // no range: 0, not NaN
    }

    @Test fun psarFirstStepsByHand() {
        // prev SAR = C1 = 10.5, EP = H0 = 10. i2: 10.5 + .02·(10 − 10.5) = 10.49; new high 12 → EP 12, AF .04;
        // clamp to the prior-two lows: L0 = 9 < 10.49 → SAR 9. i3: 9 + .04·(12 − 9) = 9.12.
        val c = listOf(bar(10.0, 9.0, 9.5), bar(11.0, 10.0, 10.5), bar(12.0, 11.0, 11.5), bar(13.0, 12.0, 12.5))
        val p = TrendCalc.psar(c)
        assertEquals(9.0, p.value[2]!!, e); assertEquals(9.12, p.value[3]!!, 1e-12); assertEquals(true, p.up[3])
        val rev = c + bar(9.5, 8.0, 8.5)                                                   // low 8 < SAR → reverse to EP 13
        assertEquals(13.0, TrendCalc.psar(rev).value[4]!!, e); assertEquals(false, TrendCalc.psar(rev).up[4])
    }

    @Test fun ichimokuByHand() {
        val c = (0 until 80).map { k -> bar(k + 2.0, k.toDouble(), k + 1.0) }
        val ich = TrendCalc.ichimoku(c)
        // Tenkan at bar k = (HH₉ + LL₉)/2 = ((k+2) + (k−8))/2 = k − 3
        assertEquals(79.0 - 3, ich.tenkan[79]!!, e)
        assertNull(ich.tenkan[7]); assertEquals(8.0 - 3, ich.tenkan[8]!!, e)             // ready after 9 bars
        assertEquals(ich.spanA[79 - 26], ich.cloudA(79))                                  // cloud for bar t = spans from t − 26
        assertNull(ich.spanB[50]); assertNotNull(ich.spanB[51])
    }

    @Test fun supertrendByHand() {
        // n = 2, k = 1. TR: 2, 2, 2, 4, 2, 3.5 → ATR (Wilder, seed (2+2)/2 at i1): 2, 2, 3, 2.5, 3.
        // i1: hl2 10 → bands 12 / 8; close 10 ≥ hl2 → up, line 8.
        // i2: hl2 11 → basic 13 / 9; upper keeps 12 (13 > 12); lower ratchets up to 9. Close 11 ≥ 9 → up, line 9.
        // i3: hl2 9, ATR 3 → basic 12 / 6; upper 12; lower keeps 9 (6 < 9). Close 7.5 < 9 → flips DOWN, line = upper 12.
        // i4: hl2 7 → basic 9.5 / 4.5; upper steps down to 9.5; lower resets to 4.5 (prior close 7.5 < 9). Close 6.5 ≤ 9.5 → down, 9.5.
        // i5: hl2 8.5, ATR 3 → basic 11.5 / 5.5; upper keeps 9.5; lower 5.5. Close 9.8 > 9.5 → flips UP, line = lower 5.5.
        val c = listOf(bar(10.0, 8.0, 9.0), bar(11.0, 9.0, 10.0), bar(12.0, 10.0, 11.0), bar(11.0, 7.0, 7.5), bar(8.0, 6.0, 6.5), bar(10.0, 7.0, 9.8))
        val st = TrendCalc.supertrend(c, 2, 1.0)
        assertEquals(listOf(null, 8.0, 9.0, 12.0, 9.5, 5.5), st.line)
        assertEquals(listOf(null, true, true, false, false, true), st.up)
    }

    @Test fun supertrendFollowsDirection() {
        val up = (0 until 40).map { k -> bar(k + 2.0, k.toDouble(), k + 1.5) }
        assertEquals(true, TrendCalc.supertrend(up).up.last())
        val down = (0 until 40).map { k -> bar(100 - k + 2.0, 100 - k.toDouble(), 100 - k + 0.5) }
        assertEquals(false, TrendCalc.supertrend(down).up.last())
    }

    // ---------------- volume

    @Test fun obvAndAdByHand() {
        val c = listOf(bar(10.0, 10.0, 10.0, 100), bar(11.0, 11.0, 11.0, 200), bar(11.0, 11.0, 11.0, 300), bar(9.0, 9.0, 9.0, 400))
        assertEquals(listOf(0.0, 200.0, 200.0, -200.0), VolumeCalc.obv(c))
        // CLV = ((10−0) − (10−10))/10 = 1 → +100; then H = L → CLV 0
        assertEquals(listOf(100.0, 100.0), VolumeCalc.accumulationDistribution(listOf(bar(10.0, 0.0, 10.0, 100), bar(5.0, 5.0, 5.0, 999))))
    }

    @Test fun mfiByHand() {
        val up = (0 until 20).map { k -> bar(k + 1.0, k.toDouble(), k + 0.5) }
        assertEquals(100.0, VolumeCalc.mfi(up).last()!!, e)                               // only positive flow
        assertEquals(50.0, VolumeCalc.mfi(List(20) { bar(5.0, 5.0, 5.0) }).last()!!, e)    // no flow at all
    }

    @Test fun sessionVwapResetsDaily() {
        val d1 = ist(2026, 11, 9, 9, 15); val d2 = ist(2026, 11, 10, 9, 15)
        val c = listOf(bar(10.0, 10.0, 10.0, 100, t = d1), bar(20.0, 20.0, 20.0, 300, t = d1 + 300_000), bar(30.0, 30.0, 30.0, 50, t = d2))
        val v = VolumeCalc.sessionVwap(c, intraday = true)
        assertEquals(10.0, v[0]!!, e); assertEquals((10.0 * 100 + 20.0 * 300) / 400, v[1]!!, e); assertEquals(30.0, v[2]!!, e)
        assertTrue(VolumeCalc.sessionVwap(c, intraday = false).all { it == null })
        assertEquals(listOf(null, 20.0, (20.0 * 300 + 30.0 * 50) / 350), VolumeCalc.anchoredVwap(c, 1))
    }

    @Test fun volumeProfileByHand() {
        // 9 bars traded at 100–101, one bar at 110–111: POC must sit at 100–101 and the value area must exclude 110.
        val c = List(9) { bar(101.0, 100.0, 100.5, 1_000) } + bar(111.0, 110.0, 110.5, 100)
        val p = VolumeCalc.volumeProfile(c, bins = 11)!!
        assertTrue(p.poc in 100.0..101.0)
        assertTrue(p.valueAreaHigh < 110.0)
        assertNull(VolumeCalc.volumeProfile(List(5) { bar(5.0, 5.0, 5.0) }))                // no range
    }

    @Test fun valueAreaByHand() {
        // Range 0–5 in 5 bins of 1. Each bar sits inside one bin: volumes 10, 20, 50, 15, 5 (total 100).
        // POC = bin 2 → 2.5. Value area starts at 50; neighbours 20 (below) vs 15 (above) → add below → 70 = 70% → stop.
        // VAL = bottom of bin 1 = 1.0, VAH = top of bin 2 = 3.0. (Adding the smaller side first would give VAH 4.)
        val c = listOf(bar(0.5, 0.0, 0.25, 10), bar(1.8, 1.2, 1.5, 20), bar(2.8, 2.2, 2.5, 50), bar(3.8, 3.2, 3.5, 15), bar(5.0, 4.5, 4.75, 5))
        val p = VolumeCalc.volumeProfile(c, bins = 5)!!
        assertEquals(2.5, p.poc, e); assertEquals(1.0, p.valueAreaLow, e); assertEquals(3.0, p.valueAreaHigh, e)
        assertArrayEquals(doubleArrayOf(10.0, 20.0, 50.0, 15.0, 5.0), p.volumes, e)
    }

    // ---------------- levels

    @Test fun pivotsByHand() {
        val p = LevelsCalc.pivots(110.0, 90.0, 100.0)
        assertEquals(100.0, p.pivot, e)
        assertEquals(listOf(110.0, 120.0, 130.0), p.resistances); assertEquals(listOf(90.0, 80.0, 70.0), p.supports)
        assertEquals(107.64, LevelsCalc.pivots(110.0, 90.0, 100.0, LevelsCalc.PivotType.FIBONACCI).resistances[0], 1e-9)
        assertEquals(100 + 1.1 / 12 * 20, LevelsCalc.pivots(110.0, 90.0, 100.0, LevelsCalc.PivotType.CAMARILLA).resistances[0], 1e-9)
    }

    @Test fun dailyPivotsUseThePreviousWeekOnly() {
        val wk1 = (9..13).map { d -> bar(100.0 + d, 90.0, 95.0 + d, t = ist(2026, 11, d)) }  // Mon 9 – Fri 13
        val mon = bar(500.0, 400.0, 450.0, t = ist(2026, 11, 16))
        val p = LevelsCalc.pivotsFor(wk1 + mon, Timeframe.D1)!!
        assertEquals((113.0 + 90.0 + 108.0) / 3, p.pivot, e)                               // H 113, L 90, C 108; current week ignored
        assertEquals("previous week", p.basis)
    }

    @Test fun fibonacciByHand() {
        val s = LevelsCalc.Swing(0, 100.0, 5, 200.0)
        val r = LevelsCalc.fibRetracement(s).associate { it.label to it.price }
        assertEquals(138.2, r["61.8%"]!!, 1e-9); assertEquals(150.0, r["50.0%"]!!, e); assertEquals(200.0, r["0.0%"]!!, e)
        assertEquals(261.8, LevelsCalc.fibExtension(s).first { it.label == "161.8%" }.price, 1e-9)
        val down = LevelsCalc.Swing(5, 100.0, 0, 200.0)
        assertEquals(161.8, LevelsCalc.fibRetracement(down).first { it.label == "61.8%" }.price, 1e-9)
    }

    @Test fun supportResistanceIgnoresUnconfirmedSwings() {
        val c = (0 until 20).map { k -> bar(100.0 + if (k == 10) 10 else 0, 95.0, 98.0) } + bar(200.0, 95.0, 150.0)
        val lv = LevelsCalc.supportResistance(c)
        assertTrue(lv.any { it.price == 110.0 })                                           // confirmed swing high
        assertTrue(lv.none { it.price == 200.0 })                                          // last bar can't be a swing yet
    }

    @Test fun dayBoundariesUseIst() = assertEquals(LocalDate.of(2026, 11, 10), MarketCalendar.date(ist(2026, 11, 10, 0, 5)))
}
