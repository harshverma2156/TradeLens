package com.tradelens.marketdata

import com.tradelens.data.model.Candle
import java.time.LocalDate
import java.time.LocalTime

/** Helpers for building IST timestamps and realistic sessions in tests. */
object TestTime {
    fun ist(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 15): Long =
        MarketCalendar.at(LocalDate.of(y, m, d), LocalTime.of(h, min))

    fun bar(t: Long, close: Double, vol: Long = 1_000, open: Double = close) =
        Candle(t, open, maxOf(open, close) * 1.002, minOf(open, close) * 0.998, close, vol)

    /** Daily bars at 09:15 IST for every trading day in [from, to] (per [cal]), close = 100 + index. */
    fun dailyBars(from: LocalDate, to: LocalDate, cal: MarketCalendar = MarketCalendar(), start: Double = 100.0): List<Candle> {
        val out = mutableListOf<Candle>()
        var d = from
        var px = start
        while (!d.isAfter(to)) {
            if (cal.isTradingDay(d)) { out += bar(MarketCalendar.at(d, MarketCalendar.OPEN), px); px += 1 }
            d = d.plusDays(1)
        }
        return out
    }

    /** Full 09:15–15:30 session of [tf] bars on [date]. */
    fun sessionBars(date: LocalDate, tf: Timeframe, start: Double = 100.0): List<Candle> {
        val open = MarketCalendar.at(date, MarketCalendar.OPEN)
        val close = MarketCalendar.at(date, MarketCalendar.CLOSE)
        val out = mutableListOf<Candle>()
        var t = open
        var px = start
        while (t < close) { out += bar(t, px); px += 0.1; t += tf.millis }
        return out
    }
}
