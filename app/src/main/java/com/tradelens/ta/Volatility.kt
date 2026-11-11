package com.tradelens.ta

import com.tradelens.data.model.Candle
import kotlin.math.abs

/** Volatility indicators. */
object VolatilityCalc {

    /** TR_i = max(H − L, |H − C_{i−1}|, |L − C_{i−1}|); TR_0 = H − L. */
    fun trueRange(c: List<Candle>): List<Double> = c.indices.map { i ->
        if (i == 0) c[0].high - c[0].low
        else maxOf(c[i].high - c[i].low, abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close))
    }

    /** ATR(n) = Wilder-smoothed TR, seeded with the mean of the first n TRs (Wilder 1978). */
    fun atr(c: List<Candle>, n: Int = 14): List<Double?> = Rolling.rma(trueRange(c), n)

    data class Bands(val mid: List<Double?>, val upper: List<Double?>, val lower: List<Double?>) {
        /** %B = (close − lower) / (upper − lower); null when the band has zero width. */
        fun percentB(close: List<Double>) = close.indices.map { i ->
            val u = upper[i]; val l = lower[i]
            if (u == null || l == null || u == l) null else (close[i] - l) / (u - l)
        }
        /** Width = (upper − lower) / mid. */
        fun width() = mid.indices.map { i -> val m = mid[i]; val u = upper[i]; val l = lower[i]; if (m == null || u == null || l == null || m == 0.0) null else (u - l) / m }
    }

    /** Bollinger Bands: SMA(n) ± k × population σ(n). Defaults 20, 2. */
    fun bollinger(close: List<Double>, n: Int = 20, k: Double = 2.0): Bands {
        val mid = Rolling.sma(close, n)
        val sd = Rolling.stdev(close, n)
        return Bands(mid, Rolling.zip(mid, sd) { m, s -> m + k * s }, Rolling.zip(mid, sd) { m, s -> m - k * s })
    }

    /**
     * Keltner Channels (Chester Keltner, modern Linda Raschke form): EMA(close, n) ± k × ATR(atrN).
     * Defaults 20, 10, 2. Variant: Keltner's 1960 original uses SMA of typical price ± SMA of (H − L).
     */
    fun keltner(c: List<Candle>, n: Int = 20, atrN: Int = 10, k: Double = 2.0): Bands {
        val mid = Rolling.ema(c.map { it.close }, n)
        val a = atr(c, atrN)
        return Bands(mid, Rolling.zip(mid, a) { m, x -> m + k * x }, Rolling.zip(mid, a) { m, x -> m - k * x })
    }

    /** Donchian Channels: highest high and lowest low of the last n bars (inclusive), mid = average. */
    fun donchian(c: List<Candle>, n: Int = 20): Bands {
        val up = Rolling.highest(c.map { it.high }, n)
        val lo = Rolling.lowest(c.map { it.low }, n)
        return Bands(Rolling.zip(up, lo) { u, l -> (u + l) / 2 }, up, lo)
    }
}
