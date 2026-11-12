package com.tradelens.patterns

import com.tradelens.patterns.Shapes.mirror
import com.tradelens.patterns.Shapes.one
import com.tradelens.patterns.Shapes.only
import com.tradelens.patterns.Shapes.path
import com.tradelens.ta.Bias
import org.junit.Assert.*
import org.junit.Test

/**
 * Textbook geometry for every chart pattern, drawn from waypoints (see [Shapes]). Each bearish case is the exact
 * price-mirror of its bullish twin, so both directions are tested with identical geometry.
 */
class ChartPatternTest {
    private val e = 1e-6

    private fun confirmed(r: PatternReport, t: PatternType): DetectedPattern {
        val hits = r.only(t).filter { it.status == PatternStatus.CONFIRMED }
        assertEquals("one confirmed $t; found ${r.patterns.filter { it.type.kind != PatternKind.CANDLESTICK }.map { "${it.type}/${it.status}@${it.detectedIndex}: ${it.events.last().detail}" }}", 1, hits.size)
        return hits.single()
    }

    /** Invariants every confirmed pattern must satisfy. */
    private fun assertTradeable(p: DetectedPattern, c: List<com.tradelens.data.model.Candle>) {
        val s = if (p.direction == Bias.BULLISH) 1.0 else -1.0
        val ci = p.confirmedIndex!!
        assertTrue("${p.type}: confirmation close beyond the level", s * (c[ci].close - p.breakoutLevel) > 0)
        assertEquals(c[ci].close, p.entry, e)
        assertTrue("${p.type}: stop on the far side of the entry", s * (p.entry - p.stopLoss) > 0)
        assertTrue("${p.type}: stop at least 0.5 ATR beyond the level", s * (p.breakoutLevel - p.stopLoss) > 0)
        assertEquals(3, p.targets.size)
        p.targets.forEach { assertTrue("${p.type}: target beyond the level", s * (it.price - p.breakoutLevel) > 0) }
        assertTrue("${p.type}: targets ordered", p.targets.zipWithNext().all { (a, b) -> s * (b.price - a.price) > 0 })
        assertTrue("${p.type}: breakout after the formation started", ci > p.startIndex)
        assertEquals(listOf(EventType.DETECTED), p.events.take(1).map { it.type })
        assertEquals(p.detectedIndex, p.events.first().knownIndex)
        assertTrue("${p.type}: no event known before it happened", p.events.all { it.knownIndex >= it.index })
        assertTrue(p.events.any { it.type == EventType.CONFIRMED && it.index == ci })
        assertTrue(p.explanation.contains(p.type.label))
        assertTrue(p.confidence in 1..100)
    }

    // ------------------------------------------------------------------ double top / bottom

    private val dtLead = arrayOf(0 to 85.0, 10 to 80.0, 40 to 100.0, 55 to 90.0, 70 to 100.2)
    private fun doubleTop(vararg tail: Pair<Int, Double>, boost: Map<IntRange, Double> = emptyMap()) = path(*dtLead, *tail, volumeBoost = boost)

    @Test fun doubleTopConfirmedOnNecklineBreakWithVolume() {
        val c = doubleTop(85 to 85.0, boost = mapOf(76..90 to 2.5))
        val p = confirmed(Shapes.detect(c), PatternType.DOUBLE_TOP)
        assertEquals(Bias.BEARISH, p.direction)
        assertEquals(75, p.detectedIndex)                                                    // top 2 (bar 70) + 5-bar pivot window
        assertEquals(c[55].low, p.breakoutLevel, e)                                          // neckline = trough low
        assertEquals(c[55].low, p.keyLevels.first { it.label == "Neckline" }.price, e)
        assertEquals(c[70].high, p.invalidationLevel, e)                                     // the higher top
        assertTrue(p.stopLoss > c[70].high)
        val height = (c[40].high + c[70].high) / 2 - c[55].low
        assertEquals(height, p.patternHeight, e)
        assertEquals(p.breakoutLevel - height, p.targets[1].price, e)                        // measured move
        assertEquals(true, p.volume.confirmed)
        assertTrue(p.volume.breakoutRelativeVolume!! >= 1.3)
        assertEquals(listOf("Top 1", "Neckline", "Top 2"), p.anchors.drop(1).map { it.label })
        assertTradeable(p, c)
    }

    @Test fun doubleBottomIsTheMirror() {
        val c = mirror(doubleTop(85 to 85.0))
        val p = confirmed(Shapes.detect(c), PatternType.DOUBLE_BOTTOM)
        assertEquals(Bias.BULLISH, p.direction)
        assertEquals(c[55].high, p.breakoutLevel, e)
        assertEquals(false, p.volume.confirmed)                                              // flat volume: breakout not volume-confirmed
        assertTradeable(p, c)
    }

    @Test fun doubleTopFormingBeforeTheBreak() {
        val c = doubleTop(78 to 92.0)
        val p = Shapes.detect(c).one(PatternType.DOUBLE_TOP)
        assertEquals(PatternStatus.FORMING, p.status)
        assertNull(p.confirmedIndex); assertNull(p.volume.confirmed)
        assertEquals(c[55].low, p.breakoutLevel, e)
        assertTrue("potential entry is a sell-stop just below the neckline", p.entry < p.breakoutLevel)
        assertFalse(p.closed)
    }

    @Test fun doubleTopInvalidatedByCloseAboveTheTops() {
        val p = Shapes.detect(doubleTop(76 to 94.0, 90 to 104.0)).one(PatternType.DOUBLE_TOP)
        assertEquals(PatternStatus.INVALIDATED, p.status)
        assertTrue(p.events.last().detail.contains("above the invalidation level"))
        assertTrue(p.closed); assertTrue(p.confidence <= 30)
    }

    @Test fun doubleTopFailsWhenPriceClosesBackThroughTheStop() {
        val p = Shapes.detect(doubleTop(82 to 87.0, 95 to 103.0)).one(PatternType.DOUBLE_TOP)
        assertEquals(PatternStatus.FAILED, p.status)
        assertEquals(listOf(EventType.DETECTED, EventType.CONFIRMED, EventType.FAILED), p.events.map { it.type })
        assertEquals(0, p.targetsHit)
    }

    @Test fun doubleTopTargetsAreTracked() {
        val p = Shapes.detect(doubleTop(85 to 85.0, 110 to 70.0)).one(PatternType.DOUBLE_TOP)
        assertEquals(PatternStatus.CONFIRMED, p.status)
        assertEquals(3, p.targetsHit)
        assertTrue(p.closed)
        assertEquals(EventType.CLOSED, p.events.last().type)
    }

    @Test fun unequalTopsAreNotADoubleTop() {
        val c = path(0 to 85.0, 10 to 80.0, 40 to 100.0, 55 to 90.0, 70 to 104.0, 85 to 85.0)   // 4 points apart ≫ 1 ATR
        assertTrue(Shapes.detect(c).only(PatternType.DOUBLE_TOP).isEmpty())
    }

    @Test fun oneBarSpikeLegsAreRejected() {
        // Trough to second top in 2 bars (< minLegBars = 3): a spike, not a formation.
        val c = path(0 to 85.0, 10 to 80.0, 40 to 100.0, 53 to 90.0, 55 to 100.2, 70 to 85.0)
        assertTrue(Shapes.detect(c).only(PatternType.DOUBLE_TOP).isEmpty())
    }

    // ------------------------------------------------------------------ triple top / bottom

    // The second trough (92.5) holds above the first (92): undercutting it would confirm the tops-1-2 double top first.
    // Third top at bar 74 is confirmed at 79, inside the double top's 20-bar breakout window (else it expires first).
    private fun tripleTop() = path(0 to 85.0, 10 to 80.0, 40 to 100.0, 50 to 92.0, 60 to 100.3, 66 to 92.5, 74 to 99.8, 90 to 82.0)

    @Test fun tripleTopConfirmedAndSupersedesTheEarlierDoubleTop() {
        val c = tripleTop()
        val r = Shapes.detect(c)
        val p = confirmed(r, PatternType.TRIPLE_TOP)
        assertEquals(minOf(c[50].low, c[70].low), p.breakoutLevel, e)                       // support = lower trough
        assertEquals(listOf("Top 1", "Trough", "Top 2", "Trough", "Top 3"), p.anchors.drop(1).map { it.label })
        assertTradeable(p, c)
        val dt = r.only(PatternType.DOUBLE_TOP).single()                                     // tops 1–2 were a double top first …
        assertEquals(PatternStatus.INVALIDATED, dt.status)
        assertTrue(dt.events.toString(), dt.events.last().detail.contains("evolved into a Triple Top"))   // … until the third top appeared
    }

    @Test fun tripleBottomIsTheMirror() {
        val c = mirror(tripleTop())
        val p = confirmed(Shapes.detect(c), PatternType.TRIPLE_BOTTOM)
        assertEquals(Bias.BULLISH, p.direction)
        assertTradeable(p, c)
    }

    // ------------------------------------------------------------------ head and shoulders

    private fun headAndShoulders(vararg tail: Pair<Int, Double>) =
        path(0 to 85.0, 10 to 78.0, 35 to 95.0, 45 to 88.0, 60 to 104.0, 75 to 88.5, 90 to 95.5, *tail)

    @Test fun headAndShouldersConfirmedOnSlopingNeckline() {
        val c = headAndShoulders(110 to 75.0)
        val p = confirmed(Shapes.detect(c), PatternType.HEAD_AND_SHOULDERS)
        assertEquals(95, p.detectedIndex)
        assertEquals(listOf("Left shoulder", "Neckline 1", "Head", "Neckline 2", "Right shoulder"), p.anchors.drop(1).map { it.label })
        // Neckline through the two trough lows, projected to the breakout bar.
        val n1 = c[45].low; val n2 = c[75].low
        val ci = p.confirmedIndex!!
        assertEquals(n1 + (n2 - n1) * (ci - 45) / 30.0, p.breakoutLevel, 1e-9)
        assertEquals(c[60].high - (n1 + (n2 - n1) * 15 / 30.0), p.patternHeight, 1e-9)     // head minus neckline under the head
        assertTrue(p.stopLoss > c[90].high)                                                  // above the right shoulder
        assertTrue(p.segments.any { it.role == SegmentRole.NECKLINE })
        assertEquals(5, p.segments.count { it.role == SegmentRole.OUTLINE })
        assertTradeable(p, c)
    }

    @Test fun inverseHeadAndShouldersIsTheMirror() {
        val c = mirror(headAndShoulders(110 to 75.0))
        val p = confirmed(Shapes.detect(c), PatternType.INVERSE_HEAD_AND_SHOULDERS)
        assertEquals(Bias.BULLISH, p.direction)
        assertTradeable(p, c)
    }

    @Test fun headAndShouldersInvalidatedAboveTheHead() {
        val p = Shapes.detect(headAndShoulders(96 to 92.0, 115 to 108.0)).one(PatternType.HEAD_AND_SHOULDERS)
        assertEquals(PatternStatus.INVALIDATED, p.status)
    }

    @Test fun headThatDoesNotStandOutIsNotHeadAndShoulders() {
        val c = path(0 to 85.0, 10 to 78.0, 35 to 95.0, 45 to 88.0, 60 to 95.4, 75 to 88.5, 90 to 95.5, 110 to 75.0)
        val r = Shapes.detect(c)
        assertTrue(r.only(PatternType.HEAD_AND_SHOULDERS).isEmpty())
        assertTrue("three equal peaks are a triple top instead", r.only(PatternType.TRIPLE_TOP).isNotEmpty())
    }

    // ------------------------------------------------------------------ triangles

    // After the last high the pullback must stay above the rising support (≈ 98 by bar 77), or it's invalidated.
    private fun ascendingTriangle() = path(0 to 85.0, 10 to 80.0, 25 to 100.0, 35 to 87.0, 50 to 100.2, 60 to 94.0, 72 to 100.1, 77 to 99.0, 90 to 110.0)

    @Test fun ascendingTriangleInvalidatedWhenPullbackBreaksRisingSupport() {
        val c = path(0 to 85.0, 10 to 80.0, 25 to 100.0, 35 to 87.0, 50 to 100.2, 60 to 94.0, 72 to 100.1, 78 to 97.0, 92 to 110.0)
        val r = Shapes.detect(c)
        assertTrue(r.only(PatternType.ASCENDING_TRIANGLE).none { it.status == PatternStatus.CONFIRMED })
        assertTrue(r.only(PatternType.ASCENDING_TRIANGLE).any { it.events.last().detail.contains("below the invalidation level") })
    }

    @Test fun ascendingTriangleBreaksOutAboveFlatResistance() {
        val c = ascendingTriangle()
        val r = Shapes.detect(c)
        val p = confirmed(r, PatternType.ASCENDING_TRIANGLE)
        assertEquals(Bias.BULLISH, p.direction)
        assertTrue(p.segments.any { it.role == SegmentRole.UPPER } && p.segments.any { it.role == SegmentRole.LOWER })
        assertEquals(6, p.anchors.size)                                                      // grew from 4 to 6 swing points
        assertTrue(p.events.any { it.type == EventType.UPDATED })
        assertTrue(kotlin.math.abs(p.breakoutLevel - 100.3) < 0.5)                          // flat resistance ≈ the highs
        assertTradeable(p, c)
    }

    @Test fun descendingTriangleIsTheMirror() {
        val c = mirror(ascendingTriangle())
        val p = confirmed(Shapes.detect(c), PatternType.DESCENDING_TRIANGLE)
        assertEquals(Bias.BEARISH, p.direction)
        assertTradeable(p, c)
    }

    private fun symmetrical() = path(0 to 95.0, 10 to 110.0, 25 to 90.0, 40 to 106.0, 55 to 94.0, 70 to 102.0, 85 to 98.0, 100 to 112.0)

    @Test fun symmetricalTriangleTakesTheBreakoutDirection() {
        val up = symmetrical()
        val p = confirmed(Shapes.detect(up), PatternType.SYMMETRICAL_TRIANGLE)
        assertEquals(Bias.BULLISH, p.direction)
        assertTradeable(p, up)
        val down = mirror(up)
        val q = confirmed(Shapes.detect(down), PatternType.SYMMETRICAL_TRIANGLE)
        assertEquals(Bias.BEARISH, q.direction)
        assertTradeable(q, down)
    }

    @Test fun symmetricalTriangleWhileFormingShowsBothTriggers() {
        val c = path(0 to 95.0, 10 to 110.0, 25 to 90.0, 40 to 106.0, 55 to 94.0, 70 to 102.0, 85 to 98.0, 88 to 99.0)
        val p = Shapes.detect(c).only(PatternType.SYMMETRICAL_TRIANGLE).single { it.status == PatternStatus.FORMING }
        assertEquals(Bias.NEUTRAL, p.direction)                                              // no prior trend: either side counts
        assertTrue(p.keyLevels.any { it.label == "Bullish trigger" } && p.keyLevels.any { it.label == "Bearish trigger" })
    }

    // ------------------------------------------------------------------ rectangle, wedges, channels

    @Test fun rectangleBreakdown() {
        val c = path(0 to 95.0, 10 to 100.0, 25 to 90.0, 40 to 100.1, 55 to 90.2, 70 to 100.0, 85 to 90.1, 100 to 80.0)
        val p = confirmed(Shapes.detect(c), PatternType.RECTANGLE)
        assertEquals(Bias.BEARISH, p.direction)
        assertTradeable(p, c)
        val q = confirmed(Shapes.detect(mirror(c)), PatternType.RECTANGLE)
        assertEquals(Bias.BULLISH, q.direction)
    }

    private fun risingWedge() = path(0 to 85.0, 10 to 80.0, 25 to 95.0, 40 to 87.0, 55 to 98.5, 70 to 94.0, 85 to 102.0, 100 to 88.0)

    @Test fun risingWedgeBreaksDown() {
        val c = risingWedge()
        val p = confirmed(Shapes.detect(c), PatternType.RISING_WEDGE)
        assertEquals(Bias.BEARISH, p.direction)
        assertTradeable(p, c)
    }

    @Test fun fallingWedgeIsTheMirror() {
        val c = mirror(risingWedge())
        val p = confirmed(Shapes.detect(c), PatternType.FALLING_WEDGE)
        assertEquals(Bias.BULLISH, p.direction)
        assertTradeable(p, c)
    }

    private fun ascendingChannel(vararg tail: Pair<Int, Double>) =
        path(0 to 85.0, 10 to 80.0, 25 to 92.0, 40 to 86.0, 55 to 98.0, 70 to 92.0, 85 to 104.0, 88 to 102.5, *tail)

    @Test fun ascendingChannelConfirmedByCloseAboveTheUpperLine() {
        val c = ascendingChannel(103 to 116.0)
        val p = confirmed(Shapes.detect(c), PatternType.ASCENDING_CHANNEL)
        assertEquals(Bias.BULLISH, p.direction)
        assertTradeable(p, c)
    }

    @Test fun ascendingChannelInvalidatedByBreakdown() {
        val p = Shapes.detect(ascendingChannel(103 to 88.0)).only(PatternType.ASCENDING_CHANNEL).single()
        assertEquals(PatternStatus.INVALIDATED, p.status)
        assertTrue(p.events.last().detail.contains("below the invalidation level"))
    }

    @Test fun descendingChannelIsTheMirror() {
        val c = mirror(ascendingChannel(103 to 116.0))
        val p = confirmed(Shapes.detect(c), PatternType.DESCENDING_CHANNEL)
        assertEquals(Bias.BEARISH, p.direction)
        assertTradeable(p, c)
    }

    // ------------------------------------------------------------------ flags

    private fun bullFlag(vararg tail: Pair<Int, Double>, boost: Map<IntRange, Double> = emptyMap()) =
        path(0 to 85.0, 10 to 80.0, 25 to 100.0, 37 to 96.0, *tail, volumeBoost = boost)

    @Test fun bullFlagBreakoutAndMeasuredMove() {
        val c = bullFlag(45 to 106.0, boost = mapOf(38..40 to 2.0))
        val p = confirmed(Shapes.detect(c), PatternType.BULL_FLAG)
        assertEquals(30, p.detectedIndex)                                                    // pole top 25 + 5 bars (pivot and minimum flag length)
        val pole = c[25].high - c[10].low
        assertEquals(pole, p.patternHeight, e)
        assertEquals(p.breakoutLevel + pole, p.targets[1].price, e)                          // flagpole projected from the breakout
        assertTrue(p.stopLoss < (26..37).minOf { c[it].low })                                // below the flag low
        assertEquals(c[25].high - 0.5 * pole, p.invalidationLevel, e)                        // 50% retracement
        assertTrue(p.segments.any { it.role == SegmentRole.POLE })
        assertEquals(true, p.volume.confirmed)
        assertTradeable(p, c)
    }

    @Test fun bearFlagIsTheMirror() {
        val c = mirror(bullFlag(45 to 106.0))
        val p = confirmed(Shapes.detect(c), PatternType.BEAR_FLAG)
        assertEquals(Bias.BEARISH, p.direction)
        assertTradeable(p, c)
    }

    @Test fun deepPullbackInvalidatesTheFlag() {
        val p = Shapes.detect(bullFlag(45 to 85.0)).one(PatternType.BULL_FLAG)
        assertEquals(PatternStatus.INVALIDATED, p.status)
    }

    @Test fun flagThatDragsOnExpires() {
        // The orderly pullback just keeps going for 30 bars (max flag length 25) without breaking its upper line
        // or retracing 50% of the pole.
        val p = Shapes.detect(path(0 to 85.0, 10 to 80.0, 25 to 100.0, 55 to 90.5)).one(PatternType.BULL_FLAG)
        assertEquals(PatternStatus.INVALIDATED, p.status)
        assertTrue(p.events.last().detail.contains("No breakout"))
    }

    // ------------------------------------------------------------------ cup and handle

    private fun cupAndHandle(vararg tail: Pair<Int, Double>) =
        path(0 to 70.0, 45 to 100.0, 55 to 90.0, 65 to 84.0, 75 to 82.0, 85 to 84.0, 95 to 90.0, 105 to 99.6, 112 to 95.0, *tail)

    @Test fun cupAndHandleBreaksOutOverTheRim() {
        val c = cupAndHandle(125 to 106.0)
        val p = confirmed(Shapes.detect(c), PatternType.CUP_AND_HANDLE)
        assertEquals(listOf("Left rim", "Cup bottom", "Right rim"), p.anchors.map { it.label })
        assertEquals(c[105].high, p.breakoutLevel, e)                                        // the right rim
        assertEquals(c[75].low, p.anchors[1].price, e)                                       // cup bottom
        assertEquals(c[105].high - c[75].low, p.patternHeight, 1e-9)                         // depth (lower rim − bottom)
        assertTrue(p.stopLoss < (106..112).minOf { c[it].low })                              // below the handle
        assertTradeable(p, c)
    }

    @Test fun handleThatFallsIntoTheLowerHalfInvalidates() {
        val p = Shapes.detect(cupAndHandle(125 to 86.0)).one(PatternType.CUP_AND_HANDLE)
        assertEquals(PatternStatus.INVALIDATED, p.status)
    }

    @Test fun vShapedBottomIsNotACup() {
        // Same rims and depth but a sharp V: almost no time spent in the lower half.
        val c = path(0 to 70.0, 45 to 100.0, 73 to 99.0, 75 to 82.0, 77 to 99.0, 105 to 99.6, 112 to 95.0, 125 to 106.0)
        assertTrue(Shapes.detect(c).only(PatternType.CUP_AND_HANDLE).isEmpty())
    }

    // ------------------------------------------------------------------ configuration

    @Test fun pivotWindowIsConfigurable() {
        // A 3-bar window confirms the second top 2 bars sooner than the default 5.
        val c = doubleTop(85 to 85.0)
        assertEquals(75, Shapes.detect(c).one(PatternType.DOUBLE_TOP).detectedIndex)
        assertEquals(73, Shapes.detect(c, PatternConfig(pivotWindow = 3)).one(PatternType.DOUBLE_TOP).detectedIndex)
    }

    @Test fun sizeThresholdsAreInAtrSoScaleDoesNotMatter() {
        // The same shape at 1/100th and 100× the price finds the same patterns at the same bars.
        fun scaled(k: Double) = doubleTop(85 to 85.0).map { it.copy(open = it.open * k, high = it.high * k, low = it.low * k, close = it.close * k) }
        val base = Shapes.detect(doubleTop(85 to 85.0)).patterns.map { Triple(it.type, it.status, it.confirmedIndex) }
        assertEquals(base, Shapes.detect(scaled(0.01)).patterns.map { Triple(it.type, it.status, it.confirmedIndex) })
        assertEquals(base, Shapes.detect(scaled(100.0)).patterns.map { Triple(it.type, it.status, it.confirmedIndex) })
    }
}
