package com.tradelens.ta

import com.tradelens.data.model.Candle
import kotlin.math.max

/** Momentum oscillators. */
object MomentumCalc {

    /**
     * RSI (Wilder 1978): RS = RMA(gains, n) / RMA(losses, n); RSI = 100 − 100 / (1 + RS).
     * Seeded with the simple average of the first n changes (Wilder). Flat-market rules: losses = 0 and
     * gains > 0 → 100; both 0 (no movement at all) → 50 (undefined, treated as neutral).
     * Variant: pandas/`ta` seed the averages from the first change via ewm; values converge after warm-up.
     */
    fun rsi(close: List<Double>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(close.size) { null }
        if (close.size <= n) return out
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) { val d = close[i] - close[i - 1]; if (d > 0) gain += d else loss -= d }
        gain /= n; loss /= n
        out[n] = rsiOf(gain, loss)
        for (i in n + 1 until close.size) {
            val d = close[i] - close[i - 1]
            gain = (gain * (n - 1) + max(d, 0.0)) / n
            loss = (loss * (n - 1) + max(-d, 0.0)) / n
            out[i] = rsiOf(gain, loss)
        }
        return out
    }

    private fun rsiOf(g: Double, l: Double) = when {
        l == 0.0 && g == 0.0 -> 50.0
        l == 0.0 -> 100.0
        else -> 100 - 100 / (1 + g / l)
    }

    data class Macd(val line: List<Double?>, val signal: List<Double?>, val hist: List<Double?>)

    /** MACD (Gerald Appel): line = EMA12 − EMA26, signal = EMA9(line), histogram = line − signal. */
    fun macd(close: List<Double>, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val line = Rolling.zip(Rolling.ema(close, fast), Rolling.ema(close, slow)) { a, b -> a - b }
        val signal = Rolling.ema(line, sig)
        return Macd(line, signal, Rolling.zip(line, signal) { a, b -> a - b })
    }

    data class Stoch(val fastK: List<Double?>, val k: List<Double?>, val d: List<Double?>)

    /**
     * Slow Stochastic (George Lane) 14, 3, 3:
     *   fast %K = 100 × (C − LL_n) / (HH_n − LL_n);  %K = SMA(fast %K, 3);  %D = SMA(%K, 3).
     * Flat range (HH = LL) → 50 (undefined, neutral).
     */
    fun stochastic(c: List<Candle>, n: Int = 14, smoothK: Int = 3, d: Int = 3): Stoch {
        val hh = Rolling.highest(c.map { it.high }, n)
        val ll = Rolling.lowest(c.map { it.low }, n)
        val fast = c.indices.map { i ->
            val h = hh[i]; val l = ll[i]
            if (h == null || l == null) null else if (h == l) 50.0 else 100 * (c[i].close - l) / (h - l)
        }
        val k = if (smoothK <= 1) fast else Rolling.sma(fast, smoothK)
        return Stoch(fast, k, Rolling.sma(k, d))
    }

    /**
     * Stochastic RSI (Chande & Kroll), 14, 14, 3, 3, scaled 0–100:
     *   raw = 100 × (RSI − min RSI_n) / (max RSI_n − min RSI_n);  %K = SMA(raw, 3);  %D = SMA(%K, 3).
     * Flat RSI over the window → 50. (`ta` outputs 0–1 and returns NaN there.)
     */
    fun stochRsi(close: List<Double>, rsiN: Int = 14, n: Int = 14, smoothK: Int = 3, d: Int = 3): Stoch {
        val r = rsi(close, rsiN)
        val start = r.indexOfFirst { it != null }
        val raw = MutableList<Double?>(close.size) { null }
        if (start >= 0) {
            val vals = r.subList(start, r.size).map { it!! }
            val hi = Rolling.highest(vals, n)
            val lo = Rolling.lowest(vals, n)
            for (k in vals.indices) {
                val h = hi[k] ?: continue
                val l = lo[k] ?: continue
                raw[start + k] = if (h == l) 50.0 else 100 * (vals[k] - l) / (h - l)
            }
        }
        val k = Rolling.sma(raw, smoothK)
        return Stoch(raw, k, Rolling.sma(k, d))
    }

    /**
     * Commodity Channel Index (Donald Lambert): CCI = (TP − SMA(TP, n)) / (0.015 × MeanDev(TP, n)),
     * TP = (H + L + C)/3, n = 20. Zero mean deviation (flat) → 0.
     */
    fun cci(c: List<Candle>, n: Int = 20, constant: Double = 0.015): List<Double?> {
        val tp = c.map { (it.high + it.low + it.close) / 3 }
        val m = Rolling.sma(tp, n)
        val md = Rolling.meanAbsDev(tp, n)
        return tp.indices.map { i ->
            val a = m[i]; val b = md[i]
            if (a == null || b == null) null else if (b == 0.0) 0.0 else (tp[i] - a) / (constant * b)
        }
    }

    /** Williams %R (Larry Williams): −100 × (HH_n − C) / (HH_n − LL_n), n = 14; range −100..0. Flat → −50. */
    fun williamsR(c: List<Candle>, n: Int = 14): List<Double?> {
        val hh = Rolling.highest(c.map { it.high }, n)
        val ll = Rolling.lowest(c.map { it.low }, n)
        return c.indices.map { i ->
            val h = hh[i]; val l = ll[i]
            if (h == null || l == null) null else if (h == l) -50.0 else -100 * (h - c[i].close) / (h - l)
        }
    }
}
