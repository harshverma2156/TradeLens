package com.tradelens.marketdata

import com.tradelens.marketdata.TestTime.ist
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MarketCalendarTest {
    private val holiday = LocalDate.of(2026, 11, 11)       // pretend Wednesday holiday
    private val cal = MarketCalendar(setOf(holiday), "test", LocalDate.of(2026, 12, 31))

    @Test fun sessionStates() {
        assertEquals(SessionState.CLOSED, cal.sessionState(ist(2026, 11, 10, 8, 59)))
        assertEquals(SessionState.PRE_OPEN, cal.sessionState(ist(2026, 11, 10, 9, 5)))
        assertEquals(SessionState.OPEN, cal.sessionState(ist(2026, 11, 10, 9, 15)))
        assertEquals(SessionState.OPEN, cal.sessionState(ist(2026, 11, 10, 15, 29)))
        assertEquals(SessionState.CLOSED, cal.sessionState(ist(2026, 11, 10, 15, 30)))
        assertEquals(SessionState.CLOSED, cal.sessionState(ist(2026, 11, 11, 11, 0)))   // holiday
        assertEquals(SessionState.CLOSED, cal.sessionState(ist(2026, 11, 14, 11, 0)))   // Saturday
    }

    @Test fun lastCloseSkipsHolidaysAndWeekends() {
        // Thursday morning, after a Wednesday holiday → Tuesday's close
        assertEquals(ist(2026, 11, 10, 15, 30), cal.lastSessionClose(ist(2026, 11, 12, 10, 0)))
        // Monday pre-open → Friday's close
        assertEquals(ist(2026, 11, 13, 15, 30), cal.lastSessionClose(ist(2026, 11, 16, 9, 0)))
        // Same day after the close → today's close
        assertEquals(ist(2026, 11, 13, 15, 30), cal.lastSessionClose(ist(2026, 11, 13, 18, 0)))
        assertEquals(ist(2026, 11, 12, 9, 15), cal.nextOpen(ist(2026, 11, 10, 16, 0)))
    }

    @Test fun bucketsAlignToSessionOpen() {
        assertEquals(ist(2026, 11, 10, 10, 15), cal.bucketStart(ist(2026, 11, 10, 11, 14), Timeframe.H1))
        assertEquals(ist(2026, 11, 10, 9, 45), cal.bucketStart(ist(2026, 11, 10, 9, 59), Timeframe.M30))
        assertNull("before open", cal.bucketStart(ist(2026, 11, 10, 9, 0), Timeframe.M5))
        assertNull("after close", cal.bucketStart(ist(2026, 11, 10, 15, 30), Timeframe.M1))
        assertEquals(ist(2026, 11, 9), cal.bucketStart(ist(2026, 11, 13, 14, 0), Timeframe.W1))   // Monday
        assertEquals(ist(2026, 11, 1), cal.bucketStart(ist(2026, 11, 13, 14, 0), Timeframe.MN1))
        assertEquals(ist(2026, 11, 13), cal.bucketStart(ist(2026, 11, 13, 0, 0), Timeframe.D1))   // provider stamps at midnight
    }

    @Test fun expectedBarsPerSession() {
        val d0 = ist(2026, 11, 10, 9, 15)
        val d1 = ist(2026, 11, 10, 15, 29)
        assertEquals(375, cal.expectedBuckets(d0, d1, Timeframe.M1).size)
        assertEquals(25, cal.expectedBuckets(d0, d1, Timeframe.M15).size)
        assertEquals(13, cal.expectedBuckets(d0, d1, Timeframe.M30).size)   // last bar 15:15–15:30
        assertEquals(7, cal.expectedBuckets(d0, d1, Timeframe.H1).size)     // 09:15 … 15:15
        // Mon 9 → Fri 13 Nov with Wed holiday = 4 trading days
        assertEquals(4, cal.expectedBuckets(ist(2026, 11, 9), ist(2026, 11, 13), Timeframe.D1).size)
    }

    @Test fun holidaysInferredFromIndexBars() {
        val real = MarketCalendar()
        val bars = TestTime.dailyBars(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 20)).filter { MarketCalendar.date(it.time) != holiday }
        assertEquals(setOf(holiday), MarketCalendar.inferHolidays(bars))
        assertTrue(real.holidaysUnknown(LocalDate.of(2026, 11, 2)))
        assertFalse(cal.holidaysUnknown(LocalDate.of(2026, 12, 1)))
    }

    @Test fun specialSessionCountsAsTradingDay() {
        val sunday = LocalDate.of(2026, 11, 8)
        assertTrue(MarketCalendar(specialSessions = setOf(sunday)).isTradingDay(sunday))
        assertFalse(MarketCalendar().isTradingDay(sunday))
    }
}
