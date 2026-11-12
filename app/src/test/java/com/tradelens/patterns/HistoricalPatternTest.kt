package com.tradelens.patterns

import com.tradelens.marketdata.MarketCalendar
import com.tradelens.marketdata.Timeframe
import com.tradelens.ta.Bias
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * Real NSE daily history, 2015–2025 (see [History]; skipped when not fetched).
 *
 * The pinned examples were found by the engine and then checked by hand against the raw bars: swing prices and
 * dates, that no bar inside the formation broke it, and that the confirming close really crossed the projected
 * level while earlier closes did not. Prices are compared within 0.5% so a provider's re-adjustment of old data
 * doesn't break them; dates exactly.
 */
class HistoricalPatternTest {

    private val reports = HashMap<String, PatternReport>()
    private fun report(s: String) = reports.getOrPut(s) { PatternEngine.detect(s, Timeframe.D1, History.load(s), Long.MAX_VALUE) }
    private fun date(ms: Long) = MarketCalendar.date(ms)

    private fun pinned(s: String, type: PatternType, confirmedOn: String): DetectedPattern {
        val r = report(s)
        val c = History.load(s)
        return r.patterns.singleOrNull { it.type == type && it.confirmedIndex?.let { i -> date(c[i].time) } == LocalDate.parse(confirmedOn) }
            ?: throw AssertionError("$s: no $type confirmed on $confirmedOn; nearby: " +
                r.patterns.filter { it.type == type }.map { "${it.status}@${date(it.detectedAt)}" }.take(8))
    }

    private fun near(expected: Double, actual: Double, what: String) =
        assertTrue("$what: expected ≈ $expected, was $actual", abs(actual - expected) <= 0.005 * expected)

    private fun anchor(p: DetectedPattern, label: String) = p.anchors.first { it.label == label }

    // ------------------------------------------------------------------ hand-verified examples

    @Test fun tcsHeadAndShoulders2025() {
        // Shoulders 11 Nov 2024 (4,234.30) and 13 Jan 2025 (4,322.95), head 13 Dec 2024 (4,494.90); neckline through the
        // 18 Nov and 7 Jan lows (3,990.20 → 4,011.55). Closes on 7 and 10 Feb held above the projected neckline; the
        // 11 Feb close (3,963.55) broke it (≈ 4,027.88 that day).
        val p = pinned("TCS.NS", PatternType.HEAD_AND_SHOULDERS, "2025-02-11")
        assertEquals(Bias.BEARISH, p.direction)
        assertEquals(LocalDate.parse("2024-12-13"), date(anchor(p, "Head").time)); near(4494.90, anchor(p, "Head").price, "head")
        assertEquals(LocalDate.parse("2025-01-13"), date(anchor(p, "Right shoulder").time))
        assertEquals(LocalDate.parse("2025-01-20"), date(p.detectedAt))                      // right shoulder + 5 bars
        near(4027.88, p.breakoutLevel, "neckline on the breakout day")
        near(3963.55, p.entry, "entry = breakout close")
        assertTrue(p.stopLoss > anchor(p, "Right shoulder").price)
        assertEquals(Bias.BEARISH, p.higherTimeframe)                                       // weekly trend agreed
    }

    @Test fun relianceCupAndHandle2017() {
        // Rims 25 Apr (335.50) and 22 Jun (330.39), bottom 24 May (296.04); no high in between above the rim. The handle
        // held above the cup's midpoint; 7 Jul closed 340.85 on ≈ 2.6× the previous day's volume.
        val p = pinned("RELIANCE.NS", PatternType.CUP_AND_HANDLE, "2017-07-07")
        near(335.50, anchor(p, "Left rim").price, "left rim"); near(296.04, anchor(p, "Cup bottom").price, "bottom"); near(330.39, anchor(p, "Right rim").price, "right rim")
        assertEquals(LocalDate.parse("2017-05-24"), date(anchor(p, "Cup bottom").time))
        near(330.39, p.breakoutLevel, "breakout over the right rim")
        assertEquals(true, p.volume.confirmed)
        assertTrue(p.targetsHit >= 1)
    }

    @Test fun relianceInverseHeadAndShoulders2018() {
        // Head 18 Dec 2017 (394.37) under shoulders 5 Dec (409.37) and 2 Jan (414.38); the 10 Jan close cleared the
        // neckline but not the 0.1-ATR confirmation buffer, the 12 Jan close (433.85) did.
        val p = pinned("RELIANCE.NS", PatternType.INVERSE_HEAD_AND_SHOULDERS, "2018-01-12")
        assertEquals(Bias.BULLISH, p.direction)
        near(394.37, anchor(p, "Head").price, "head")
        assertEquals(LocalDate.parse("2017-12-18"), date(anchor(p, "Head").time))
        assertEquals(LocalDate.parse("2018-01-09"), date(p.detectedAt))
    }

    @Test fun infosysRectangleBreakdown2015() {
        // Range ≈ 533–570 from March 2015; broke down on 24 Apr 2015 (results day) on ~5× average volume.
        val p = pinned("INFY.NS", PatternType.RECTANGLE, "2015-04-24")
        assertEquals(Bias.BEARISH, p.direction)
        assertEquals(true, p.volume.confirmed)
        assertTrue(p.volume.breakoutRelativeVolume!! > 4.0)
    }

    @Test fun tcsDescendingTriangle2025() {
        val p = pinned("TCS.NS", PatternType.DESCENDING_TRIANGLE, "2025-07-11")
        assertEquals(Bias.BEARISH, p.direction)
        assertTrue(p.volume.breakoutRelativeVolume!! > 3.0)
    }

    @Test fun relianceDoubleBottom2018() {
        val p = pinned("RELIANCE.NS", PatternType.DOUBLE_BOTTOM, "2018-04-24")
        near(398.19, anchor(p, "Bottom 1").price, "bottom 1"); near(402.31, anchor(p, "Bottom 2").price, "bottom 2")
        near(438.83, p.breakoutLevel, "neckline")
    }

    // ------------------------------------------------------------------ invariants over every detection

    @Test fun everyDetectionIsInternallyConsistent() {
        var n = 0
        for (s in History.symbols) {
            val c = History.load(s)
            for (p in report(s).patterns) {
                n++
                val tag = "$s ${p.type} @${date(p.detectedAt)}"
                assertTrue(tag, p.name.isNotBlank() && p.explanation.isNotBlank() && p.keyLevels.isNotEmpty())
                assertEquals(Timeframe.D1, p.timeframe)
                assertTrue(tag, p.confidence in 0..100)
                assertTrue(tag, p.startIndex <= p.endIndex && p.anchors.isNotEmpty())
                listOf(p.breakoutLevel, p.entry, p.stopLoss, p.invalidationLevel, p.patternHeight).forEach { assertTrue(tag, it.isFinite()) }
                val types = p.events.map { it.type }
                assertEquals(tag, EventType.DETECTED, types.first())
                when (p.status) {
                    PatternStatus.FORMING -> { assertFalse(tag, p.closed); assertTrue(tag, types.all { it == EventType.DETECTED || it == EventType.UPDATED }) }
                    PatternStatus.INVALIDATED -> { assertTrue(tag, p.closed); assertFalse(tag, EventType.CONFIRMED in types); assertEquals(tag, EventType.INVALIDATED, types.last()) }
                    PatternStatus.FAILED -> { assertTrue(tag, p.closed); assertTrue(tag, types.indexOf(EventType.CONFIRMED) < types.indexOf(EventType.FAILED)); assertEquals(0, p.targetsHit) }
                    PatternStatus.CONFIRMED -> assertTrue(tag, EventType.CONFIRMED in types && EventType.FAILED !in types)
                }
                val ci = p.confirmedIndex ?: continue
                val sgn = if (p.direction == Bias.BULLISH) 1.0 else -1.0
                assertNotEquals(tag, Bias.NEUTRAL, p.direction)
                assertTrue("$tag: confirming close beyond the level", sgn * (c[ci].close - p.breakoutLevel) > 0)
                assertTrue("$tag: stop beyond the level", sgn * (p.breakoutLevel - p.stopLoss) > 0)
                assertTrue("$tag: targets beyond the level", p.targets.all { sgn * (it.price - p.breakoutLevel) > 0 })
                if (p.status == PatternStatus.FAILED) {
                    val fi = p.events.first { it.type == EventType.FAILED }.index
                    assertTrue("$tag: a close through the stop", sgn * (c[fi].close - p.stopLoss) < 0)
                }
                // The confirming bar is the FIRST close through the level after the formation (none earlier).
                if (p.type.kind != PatternKind.CANDLESTICK && !p.type.trendlineFamily && p.type != PatternType.BULL_FLAG && p.type != PatternType.BEAR_FLAG)
                    assertTrue(tag, p.events.count { it.type == EventType.CONFIRMED } == 1)
            }
        }
        assertTrue("expected thousands of detections over 11 years × 15 symbols, got $n", n > 3000)
    }

    @Test fun everyPatternTypeOccursInRealData() {
        val seen = History.symbols.flatMap { s -> report(s).patterns.filter { it.status != PatternStatus.FORMING }.map { it.type } }.toSet()
        assertEquals(emptySet<PatternType>(), PatternType.entries.toSet() - seen)
    }

    @Test fun indexVolumeIsNeverUsedForConfirmation() {
        // Yahoo publishes an aggregate "volume" for NIFTY; it isn't traded shares, so (as in Phase 3) it's ignored.
        val r = report("^NSEI")
        assertTrue(r.patterns.isNotEmpty())
        assertTrue(r.patterns.all { it.volume.breakoutRelativeVolume == null && it.volume.confirmed == null && !it.volume.available })
    }

    @Test fun elevenYearsAnalyseQuickly() {
        val c = History.load("RELIANCE.NS")
        PatternEngine.detect("W", Timeframe.D1, c, Long.MAX_VALUE)                         // warm-up
        val t0 = System.nanoTime()
        PatternEngine.detect("R", Timeframe.D1, c, Long.MAX_VALUE)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("2,700 bars took $ms ms", ms < 1500)
        val t1 = System.nanoTime()
        PatternEngine.detect("R", Timeframe.D1, c.takeLast(750), Long.MAX_VALUE)            // what the app analyses (3 years)
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 400)
    }
}
