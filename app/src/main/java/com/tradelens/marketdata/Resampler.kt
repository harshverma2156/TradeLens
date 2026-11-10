package com.tradelens.marketdata

import com.tradelens.data.model.Candle

/**
 * Builds larger bars from smaller ones when a provider doesn't serve a timeframe natively
 * (e.g. 30m from 15m, weekly from daily). Buckets follow the exchange session via [MarketCalendar].
 */
object Resampler {

    /** Can [to] be built exactly from [from]? */
    fun canBuild(from: Timeframe, to: Timeframe): Boolean = when {
        from == to -> true
        // Buckets are aligned to 09:15, so the last bar of the day may be short (e.g. 15:15–15:30 for 30m/1h),
        // exactly as exchanges and brokers publish them.
        from.intraday && to.intraday -> to.minutes > from.minutes && to.minutes % from.minutes == 0
        from.intraday && !to.intraday -> true
        from == Timeframe.D1 -> to == Timeframe.W1 || to == Timeframe.MN1
        else -> false
    }

    /** The provider-native timeframe to fetch for [want], preferring the largest that can build it. */
    fun sourceFor(want: Timeframe, native: Set<Timeframe>): Timeframe? =
        if (want in native) want
        else native.filter { canBuild(it, want) && it.ordinal < want.ordinal }.maxByOrNull { it.ordinal }

    fun aggregate(candles: List<Candle>, to: Timeframe, calendar: MarketCalendar): List<Candle> {
        val out = ArrayList<Candle>()
        var key = Long.MIN_VALUE
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0L
        for (k in candles.sortedBy { it.time }) {
            val b = calendar.bucketStart(k.time, to) ?: continue
            if (b != key) {
                if (key != Long.MIN_VALUE) out += Candle(key, o, h, l, c, v)
                key = b; o = k.open; h = k.high; l = k.low; c = k.close; v = k.volume
            } else {
                if (k.high > h) h = k.high
                if (k.low < l) l = k.low
                c = k.close
                v += k.volume
            }
        }
        if (key != Long.MIN_VALUE) out += Candle(key, o, h, l, c, v)
        return out
    }
}
