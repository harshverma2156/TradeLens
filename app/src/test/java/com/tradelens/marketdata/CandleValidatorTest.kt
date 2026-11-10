package com.tradelens.marketdata

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.TestTime.bar
import com.tradelens.marketdata.TestTime.ist
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CandleValidatorTest {
    private val cal = MarketCalendar(emptySet(), "test", LocalDate.of(2026, 12, 31))
    private val nov = TestTime.dailyBars(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 10), cal)
    private val now = ist(2026, 11, 10, 18, 0)

    private fun run(c: List<Candle>, tf: Timeframe = Timeframe.D1, actions: List<CorporateAction> = emptyList(),
                    adjusted: Boolean = true, calendar: MarketCalendar = cal, at: Long = now, symbol: String = "ABC.NS") =
        CandleValidator.validate(CandleSeries(symbol, tf, c, splitAdjusted = adjusted), calendar, actions, at)

    @Test fun cleanSeriesPassesAllChecks() {
        val r = run(nov)
        assertEquals(nov.size, r.candles.size)
        assertTrue(r.report.summary, r.report.issues.isEmpty())
        assertEquals(100, r.report.score)
    }

    @Test fun missingTradingDaysAreReportedNotFilled() {
        val gap = nov.filterIndexed { i, _ -> i != 10 && i != 11 }
        val r = run(gap)
        assertEquals(gap.size, r.candles.size)                       // nothing invented
        assertEquals(2, r.report.count(IssueType.MISSING_BARS))
        assertEquals(Severity.WARNING, r.report.issues.first { it.type == IssueType.MISSING_BARS }.severity)
    }

    @Test fun missingDaysAreOnlyInfoWhenHolidaysUnknown() {
        val unknown = MarketCalendar()                                // no holiday list
        val r = run(nov.filterIndexed { i, _ -> i != 10 }, calendar = unknown)
        assertEquals(Severity.INFO, r.report.issues.first { it.type == IssueType.MISSING_BARS }.severity)
    }

    @Test fun duplicatePeriodKeepsTheLatestBar() {
        // Real Yahoo behaviour: weekly series ends with the week's bar AND a live partial bar for the same week.
        val wk = ist(2026, 11, 9, 0, 0)
        val c = listOf(bar(ist(2026, 11, 2, 0, 0), 100.0), bar(wk, 101.0), bar(ist(2026, 11, 10, 15, 28), 105.0))
        val r = run(c, Timeframe.W1)
        assertEquals(2, r.candles.size)
        assertEquals(105.0, r.candles.last().close, 1e-9)
        assertEquals(ist(2026, 11, 9), r.candles.last().time)       // normalised to the week start
        assertEquals(1, r.report.count(IssueType.DUPLICATE))
        assertTrue(r.flags.last() and QualityFlag.DUPLICATE_RESOLVED != 0)
    }

    @Test fun impossibleOhlcIsDropped() {
        val t = nov[5].time
        val bad = listOf(
            Candle(t, 100.0, 99.0, 98.0, 100.0, 10),          // high below open/close
            Candle(t + 1, -1.0, 101.0, 99.0, 100.0, 10),      // negative price
            Candle(t + 2, Double.NaN, 101.0, 99.0, 100.0, 10),
            Candle(t + 3, 100.0, 101.0, 102.0, 100.0, 10),    // low above high
            Candle(t + 4, 100.0, 101.0, 99.0, 100.0, -5),     // negative volume
        )
        assertTrue(bad.none { CandleValidator.validOhlc(it) })
        val r = run(nov.take(5) + bad.take(1) + nov.drop(6))
        assertEquals(1, r.report.count(IssueType.INVALID_OHLC))
        assertEquals(nov.size - 1, r.candles.size)
        assertTrue(r.report.errors > 0)
    }

    @Test fun volumeSpikeIsFlaggedButKept() {
        val c = nov.mapIndexed { i, b -> if (i == 25) b.copy(volume = 50_000) else b }
        val r = run(c)
        assertEquals(1, r.report.count(IssueType.UNUSUAL_VOLUME))
        assertTrue(r.flags[25] and QualityFlag.UNUSUAL_VOLUME != 0)
        assertEquals(c.size, r.candles.size)
    }

    @Test fun indicesSkipVolumeChecks() {
        val c = nov.mapIndexed { i, b -> if (i == 25) b.copy(volume = 50_000) else b }
        assertEquals(0, run(c, symbol = "^NSEI").report.count(IssueType.UNUSUAL_VOLUME))
    }

    @Test fun futureAndOutOfSessionTimestampsAreDropped() {
        val day = LocalDate.of(2026, 11, 10)
        val bars = TestTime.sessionBars(day, Timeframe.M15)
        val c = bars + bar(ist(2026, 11, 10, 16, 0), 100.0) + bar(ist(2026, 11, 12, 10, 0), 100.0)
        val r = run(c, Timeframe.M15, at = ist(2026, 11, 10, 18, 0))
        assertEquals(bars.size, r.candles.size)
        assertEquals(2, r.report.count(IssueType.BAD_TIMESTAMP))
    }

    @Test fun unalignedTimestampsAreSnapped() {
        val t = ist(2026, 11, 10, 9, 17)                            // inside the 09:15 5m bar
        val r = run(listOf(bar(t, 100.0)), Timeframe.M5, at = ist(2026, 11, 10, 9, 30))
        assertEquals(ist(2026, 11, 10, 9, 15), r.candles.single().time)
        assertTrue(r.flags[0] and QualityFlag.TIME_NORMALISED != 0)
    }

    @Test fun staleSeriesDetected() {
        val r = run(nov.dropLast(3))                                 // last 3 trading days missing at the end
        val stale = r.report.issues.first { it.type == IssueType.STALE }
        assertEquals(3, stale.count)
        assertEquals(Severity.WARNING, stale.severity)
    }

    @Test fun unadjustedSplitIsBackAdjusted() {
        val ex = nov[20].time
        // Unadjusted feed: prices halve on the ex-date of a 2:1 split.
        val raw = nov.mapIndexed { i, b -> if (i < 20) b.copy(open = b.open * 2, high = b.high * 2, low = b.low * 2, close = b.close * 2) else b }
        val split = CorporateAction("ABC.NS", CorporateActionType.SPLIT, ex, ratio = 2.0, source = "test")
        val r = run(raw, actions = listOf(split), adjusted = false)
        assertEquals(nov[19].close, r.candles[19].close, 1e-9)
        assertTrue(r.flags[0] and QualityFlag.ADJUSTED != 0)
        assertEquals(0, r.report.count(IssueType.UNEXPLAINED_GAP))
        assertTrue(r.report.issues.any { it.type == IssueType.CORPORATE_ACTION && it.message.contains("back-adjusted") })
    }

    @Test fun alreadyAdjustedSplitIsLeftAlone() {
        val split = CorporateAction("ABC.NS", CorporateActionType.SPLIT, nov[20].time, ratio = 2.0, source = "test")
        val r = run(nov, actions = listOf(split), adjusted = true)
        assertEquals(nov.map { it.close }, r.candles.map { it.close })
        assertTrue(r.report.issues.any { it.type == IssueType.CORPORATE_ACTION && it.message.contains("already adjusted") })
    }

    @Test fun providerClaimsAdjustedButGapPresentIsAWarning() {
        val raw = nov.mapIndexed { i, b -> if (i < 20) b.copy(open = b.open * 2, high = b.high * 2, low = b.low * 2, close = b.close * 2) else b }
        val split = CorporateAction("ABC.NS", CorporateActionType.SPLIT, nov[20].time, ratio = 2.0, source = "test")
        val r = run(raw, actions = listOf(split), adjusted = true)
        assertEquals(Severity.WARNING, r.report.issues.first { it.type == IssueType.CORPORATE_ACTION }.severity)
    }

    @Test fun gapWithoutCorporateActionIsFlagged() {
        val raw = nov.mapIndexed { i, b -> if (i < 20) b.copy(open = b.open * 2, high = b.high * 2, low = b.low * 2, close = b.close * 2) else b }
        val r = run(raw)
        assertEquals(1, r.report.count(IssueType.UNEXPLAINED_GAP))
        assertTrue(r.flags[20] and QualityFlag.GAP != 0)
    }

    @Test fun weekendBarIsKeptAsSpecialSession() {
        val sunday = ist(2026, 11, 8, 18, 0)                         // e.g. Muhurat trading
        val r = run(nov + bar(sunday, 140.0))
        assertTrue(r.candles.any { MarketCalendar.date(it.time) == LocalDate.of(2026, 11, 8) })
        assertTrue(r.report.issues.any { it.message.contains("special session") })
    }
}
