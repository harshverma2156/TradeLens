package com.tradelens.ta

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Window primitives shared by every indicator.
 *
 * Conventions (apply to the whole `ta` package):
 *  - Outputs are aligned to the input: `out[i]` describes bar i and uses only bars 0..i (no look-ahead).
 *  - `null` means "not enough data yet" (warm-up) or "undefined" (documented per function). Never 0-filled.
 */
object Rolling {

    /** Simple moving average. Null until [n] values are available, or if any value in the window is null. */
    fun sma(v: List<Double?>, n: Int): List<Double?> {
        require(n > 0)
        val out = MutableList<Double?>(v.size) { null }
        var sum = 0.0
        var nulls = 0
        for (i in v.indices) {
            val x = v[i]
            if (x == null) nulls++ else sum += x
            if (i >= n) { val y = v[i - n]; if (y == null) nulls-- else sum -= y }
            if (i >= n - 1 && nulls == 0) out[i] = sum / n
        }
        return out
    }

    /**
     * Exponential moving average, alpha = 2 / (n + 1), seeded with the SMA of the first [n] values
     * (TA-Lib / Wilder convention). Leading nulls are skipped, so it can run on derived series (e.g. MACD).
     * Variant: pandas `ewm(adjust=False)` seeds with the first value instead; the two converge, the seed's
     * influence decaying as (1 − alpha)^k.
     */
    fun ema(v: List<Double?>, n: Int): List<Double?> = smoothed(v, n, 2.0 / (n + 1))

    /** Wilder's smoothing (RMA), alpha = 1 / n, SMA-seeded. Used by RSI, ATR and ADX. */
    fun rma(v: List<Double?>, n: Int): List<Double?> = smoothed(v, n, 1.0 / n)

    private fun smoothed(v: List<Double?>, n: Int, alpha: Double): List<Double?> {
        require(n > 0)
        val out = MutableList<Double?>(v.size) { null }
        val start = v.indexOfFirst { it != null }
        if (start < 0 || v.size - start < n) return out
        var seed = 0.0
        for (i in start until start + n) seed += v[i] ?: return out
        var prev = seed / n
        out[start + n - 1] = prev
        for (i in start + n until v.size) {
            val x = v[i] ?: continue
            prev = alpha * x + (1 - alpha) * prev
            out[i] = prev
        }
        return out
    }

    /** Population standard deviation (ddof = 0, Bollinger's definition) over [n] values. */
    fun stdev(v: List<Double>, n: Int): List<Double?> {
        val mean = sma(v, n)
        return v.indices.map { i ->
            val m = mean[i] ?: return@map null
            var ss = 0.0
            for (j in i - n + 1..i) { val d = v[j] - m; ss += d * d }
            sqrt(ss / n)
        }
    }

    /** Mean absolute deviation around the window mean (used by CCI). */
    fun meanAbsDev(v: List<Double>, n: Int): List<Double?> {
        val mean = sma(v, n)
        return v.indices.map { i ->
            val m = mean[i] ?: return@map null
            var s = 0.0
            for (j in i - n + 1..i) s += abs(v[j] - m)
            s / n
        }
    }

    /** Highest value of the last [n] (inclusive of i). O(len) with a monotonic deque. */
    fun highest(v: List<Double>, n: Int): List<Double?> = extreme(v, n, max = true)

    fun lowest(v: List<Double>, n: Int): List<Double?> = extreme(v, n, max = false)

    private fun extreme(v: List<Double>, n: Int, max: Boolean): List<Double?> {
        require(n > 0)
        val out = MutableList<Double?>(v.size) { null }
        val dq = ArrayDeque<Int>()
        for (i in v.indices) {
            while (dq.isNotEmpty() && dq.first() <= i - n) dq.removeFirst()
            while (dq.isNotEmpty() && if (max) v[dq.last()] <= v[i] else v[dq.last()] >= v[i]) dq.removeLast()
            dq.addLast(i)
            if (i >= n - 1) out[i] = v[dq.first()]
        }
        return out
    }

    /** a[i] op b[i] when both present. */
    inline fun zip(a: List<Double?>, b: List<Double?>, f: (Double, Double) -> Double?): List<Double?> =
        a.indices.map { i -> val x = a[i]; val y = b.getOrNull(i); if (x != null && y != null) f(x, y) else null }
}
