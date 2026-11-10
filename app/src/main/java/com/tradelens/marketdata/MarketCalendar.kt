package com.tradelens.marketdata

import com.tradelens.data.model.Candle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class SessionState { PRE_OPEN, OPEN, CLOSED }

/**
 * NSE/BSE equity cash-market sessions (IST, no DST): pre-open 09:00–09:15, normal 09:15–15:30, Mon–Fri.
 *
 * Holidays are never hard-coded here. They come from an official list (when a provider or the backend
 * supplies one) or are inferred from days the benchmark index didn't trade. [holidaySource] says which,
 * and [knownThrough] marks the last date the list covers; later weekdays are assumed to be trading days.
 * Special sessions (e.g. Muhurat trading on a weekend) are allowed via [specialSessions].
 */
class MarketCalendar(
    holidays: Collection<LocalDate> = emptySet(),
    val holidaySource: String? = null,
    val knownThrough: LocalDate? = null,
    specialSessions: Collection<LocalDate> = emptySet(),
) {
    private val holidaySet = holidays.toHashSet()
    private val specialSet = specialSessions.toHashSet()
    val holidays: Set<LocalDate> get() = holidaySet

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        val PRE_OPEN: LocalTime = LocalTime.of(9, 0)
        val OPEN: LocalTime = LocalTime.of(9, 15)
        val CLOSE: LocalTime = LocalTime.of(15, 30)
        const val SESSION_MINUTES = 375

        fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(IST).toLocalDate()
        fun at(date: LocalDate, time: LocalTime): Long = date.atTime(time).atZone(IST).toInstant().toEpochMilli()

        /**
         * Weekdays between the first and last daily bar of a broad index that have no bar are market
         * holidays (or unscheduled closures). Only meaningful for an index that trades every session.
         */
        fun inferHolidays(indexDaily: List<Candle>): Set<LocalDate> {
            if (indexDaily.size < 2) return emptySet()
            val traded = indexDaily.map { date(it.time) }.toHashSet()
            val out = HashSet<LocalDate>()
            var d = traded.min()
            val end = traded.max()
            while (!d.isAfter(end)) {
                if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d !in traded) out += d
                d = d.plusDays(1)
            }
            return out
        }
    }

    fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    fun isTradingDay(d: LocalDate): Boolean = d in specialSet || (!isWeekend(d) && d !in holidaySet)

    /** True if the date is outside the range our holiday list covers. */
    fun holidaysUnknown(d: LocalDate): Boolean = knownThrough == null || d.isAfter(knownThrough)

    fun sessionState(nowMs: Long): SessionState {
        val z = Instant.ofEpochMilli(nowMs).atZone(IST)
        if (!isTradingDay(z.toLocalDate())) return SessionState.CLOSED
        val t = z.toLocalTime()
        return when {
            t >= OPEN && t < CLOSE -> SessionState.OPEN
            t >= PRE_OPEN && t < OPEN -> SessionState.PRE_OPEN
            else -> SessionState.CLOSED
        }
    }

    fun previousTradingDay(d: LocalDate): LocalDate {
        var x = d.minusDays(1)
        while (!isTradingDay(x)) x = x.minusDays(1)
        return x
    }

    fun nextTradingDay(d: LocalDate): LocalDate {
        var x = d.plusDays(1)
        while (!isTradingDay(x)) x = x.plusDays(1)
        return x
    }

    /** Close time of the most recent completed session at or before [nowMs]. */
    fun lastSessionClose(nowMs: Long): Long {
        val today = date(nowMs)
        val closeToday = at(today, CLOSE)
        return if (isTradingDay(today) && nowMs >= closeToday) closeToday else at(previousTradingDay(today), CLOSE)
    }

    fun nextOpen(nowMs: Long): Long {
        val today = date(nowMs)
        val openToday = at(today, OPEN)
        return if (isTradingDay(today) && nowMs < openToday) openToday else at(nextTradingDay(today), OPEN)
    }

    /**
     * Start of the bar that contains [ms]. Intraday bars are aligned to the 09:15 session open (so a 1h bar
     * runs 09:15–10:15); daily, weekly and monthly bars are stamped at 09:15 on their first calendar day.
     * Returns null for intraday timestamps outside the session.
     */
    fun bucketStart(ms: Long, tf: Timeframe): Long? {
        val z: ZonedDateTime = Instant.ofEpochMilli(ms).atZone(IST)
        val d = z.toLocalDate()
        return when (tf) {
            Timeframe.D1 -> at(d, OPEN)
            Timeframe.W1 -> at(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), OPEN)
            Timeframe.MN1 -> at(d.withDayOfMonth(1), OPEN)
            else -> {
                val open = at(d, OPEN)
                val close = at(d, CLOSE)
                if (ms < open || ms >= close) null else open + (ms - open) / tf.millis * tf.millis
            }
        }
    }

    /** Every bar start we'd expect between [fromMs] and [toMs] (inclusive), given known holidays. */
    fun expectedBuckets(fromMs: Long, toMs: Long, tf: Timeframe): List<Long> {
        val out = ArrayList<Long>()
        var d = date(fromMs)
        val end = date(toMs)
        val seenPeriods = HashSet<Long>()
        while (!d.isAfter(end)) {
            if (isTradingDay(d)) {
                if (tf.intraday) {
                    var t = at(d, OPEN)
                    val close = at(d, CLOSE)
                    while (t < close) {
                        if (t in fromMs..toMs) out += t
                        t += tf.millis
                    }
                } else {
                    val b = bucketStart(at(d, OPEN), tf)!!
                    if (seenPeriods.add(b) && at(d, OPEN) >= bucketStart(fromMs, Timeframe.D1)!!) out += b
                }
            }
            d = d.plusDays(1)
        }
        return out
    }
}
