package com.tradelens.ta

import com.tradelens.data.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Trend indicators. */
object TrendCalc {

    fun sma(close: List<Double>, n: Int) = Rolling.sma(close, n)
    fun ema(close: List<Double>, n: Int) = Rolling.ema(close, n)

    data class Dmi(val adx: List<Double?>, val plusDi: List<Double?>, val minusDi: List<Double?>)

    /**
     * Wilder's Directional Movement System (1978).
     *   +DM = H_i − H_{i−1} if it exceeds L_{i−1} − L_i and is > 0, else 0; −DM symmetric.
     *   +DI = 100 × RMA(+DM, n) / RMA(TR, n);  −DI likewise.
     *   DX  = 100 × |+DI − −DI| / (+DI + −DI);  ADX = RMA(DX, n).
     * Each RMA is seeded with the SMA of its first n values, so ADX needs ≈ 2n bars of warm-up.
     */
    fun dmi(c: List<Candle>, n: Int = 14): Dmi {
        val size = c.size
        val pdm = MutableList<Double?>(size) { null }
        val mdm = MutableList<Double?>(size) { null }
        val trs = MutableList<Double?>(size) { null }
        val tr = VolatilityCalc.trueRange(c)
        for (i in 1 until size) {
            val up = c[i].high - c[i - 1].high
            val down = c[i - 1].low - c[i].low
            pdm[i] = if (up > down && up > 0) up else 0.0
            mdm[i] = if (down > up && down > 0) down else 0.0
            trs[i] = tr[i]
        }
        val trS = Rolling.rma(trs, n)
        val pS = Rolling.rma(pdm, n)
        val mS = Rolling.rma(mdm, n)
        val plus = MutableList<Double?>(size) { null }
        val minus = MutableList<Double?>(size) { null }
        val dx = MutableList<Double?>(size) { null }
        for (i in 0 until size) {
            val t = trS[i] ?: continue
            val p = pS[i] ?: continue
            val m = mS[i] ?: continue
            val pdi = if (t == 0.0) 0.0 else 100 * p / t
            val mdi = if (t == 0.0) 0.0 else 100 * m / t
            plus[i] = pdi
            minus[i] = mdi
            dx[i] = if (pdi + mdi == 0.0) 0.0 else 100 * abs(pdi - mdi) / (pdi + mdi)
        }
        return Dmi(Rolling.rma(dx, n), plus, minus)
    }

    data class Supertrend(val line: List<Double?>, val up: List<Boolean?>)

    /**
     * Supertrend (Olivier Seban). basic bands = (H + L)/2 ± k × ATR(n).
     * Final upper band only moves down (or resets when the prior close broke above it); final lower band only
     * moves up. Trend flips when the close crosses the opposite final band. Defaults 10, 3.
     */
    fun supertrend(c: List<Candle>, n: Int = 10, k: Double = 3.0): Supertrend {
        val atr = VolatilityCalc.atr(c, n)
        val line = MutableList<Double?>(c.size) { null }
        val up = MutableList<Boolean?>(c.size) { null }
        var fu = 0.0; var fl = 0.0; var trendUp = true; var started = false
        for (i in c.indices) {
            val a = atr[i] ?: continue
            val hl2 = (c[i].high + c[i].low) / 2
            val bu = hl2 + k * a
            val bl = hl2 - k * a
            if (!started) {
                fu = bu; fl = bl; trendUp = c[i].close >= hl2; started = true
            } else {
                val pc = c[i - 1].close
                fu = if (bu < fu || pc > fu) bu else fu
                fl = if (bl > fl || pc < fl) bl else fl
                trendUp = if (trendUp) c[i].close >= fl else c[i].close > fu
            }
            up[i] = trendUp
            line[i] = if (trendUp) fl else fu
        }
        return Supertrend(line, up)
    }

    /**
     * Ichimoku Kinko Hyo (Goichi Hosoda), defaults 9 / 26 / 52.
     *   Tenkan = (HH9 + LL9)/2, Kijun = (HH26 + LL26)/2,
     *   Senkou A = (Tenkan + Kijun)/2 and Senkou B = (HH52 + LL52)/2, both computed at bar i and PLOTTED at i + 26.
     *   Chikou = close_i plotted at i − 26.
     * [spanA]/[spanB] here are unshifted ("raw") so nothing ever reads the future; the cloud that applies to
     * bar i is [cloudA] / [cloudB] = raw spans from bar i − 26.
     */
    data class Ichimoku(
        val tenkan: List<Double?>, val kijun: List<Double?>,
        val spanA: List<Double?>, val spanB: List<Double?>, val displacement: Int,
    ) {
        fun cloudA(i: Int) = spanA.getOrNull(i - displacement)
        fun cloudB(i: Int) = spanB.getOrNull(i - displacement)
    }

    fun ichimoku(c: List<Candle>, conv: Int = 9, base: Int = 26, spanBN: Int = 52, displacement: Int = 26): Ichimoku {
        val h = c.map { it.high }; val l = c.map { it.low }
        fun mid(n: Int) = Rolling.zip(Rolling.highest(h, n), Rolling.lowest(l, n)) { a, b -> (a + b) / 2 }
        val t = mid(conv)
        val k = mid(base)
        return Ichimoku(t, k, Rolling.zip(t, k) { a, b -> (a + b) / 2 }, mid(spanBN), displacement)
    }

    data class Psar(val value: List<Double?>, val up: List<Boolean?>)

    /**
     * Parabolic SAR (Wilder 1978), step 0.02, max 0.2.
     *   SAR_{i} = SAR_{i−1} + AF × (EP − SAR_{i−1}); AF += step on each new extreme (capped at max);
     *   in an uptrend SAR may not exceed the prior two lows (mirror in a downtrend); reversal when price
     *   crosses SAR, SAR resets to the prior EP and AF to step.
     * Initialisation (path-dependent, implementations differ): start long at bar 2 with SAR = close_1 and
     * EP = H_0 / L_0, matching the `ta` reference library so values can be cross-checked.
     */
    fun psar(c: List<Candle>, step: Double = 0.02, maxStep: Double = 0.2): Psar {
        val n = c.size
        val sar = MutableList<Double?>(n) { null }
        val upL = MutableList<Boolean?>(n) { null }
        if (n < 3) return Psar(sar, upL)
        var up = true
        var af = step
        var hiEp = c[0].high
        var loEp = c[0].low
        var prev = c[1].close
        for (i in 2 until n) {
            var reversal = false
            var s: Double
            if (up) {
                s = prev + af * (hiEp - prev)
                if (c[i].low < s) {
                    reversal = true; s = hiEp; loEp = c[i].low; af = step
                } else {
                    if (c[i].high > hiEp) { hiEp = c[i].high; af = min(af + step, maxStep) }
                    if (c[i - 2].low < s) s = c[i - 2].low else if (c[i - 1].low < s) s = c[i - 1].low
                }
            } else {
                s = prev - af * (prev - loEp)
                if (c[i].high > s) {
                    reversal = true; s = loEp; hiEp = c[i].high; af = step
                } else {
                    if (c[i].low < loEp) { loEp = c[i].low; af = min(af + step, maxStep) }
                    if (c[i - 2].high > s) s = c[i - 2].high else if (c[i - 1].high > s) s = c[i - 1].high
                }
            }
            if (reversal) up = !up
            sar[i] = s
            upL[i] = up
            prev = s
        }
        return Psar(sar, upL)
    }
}
