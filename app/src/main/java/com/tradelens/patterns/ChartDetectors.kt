package com.tradelens.patterns

import com.tradelens.ta.Bias
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * What a detector found, as of the bar it was evaluated on. The engine turns it into a tracked pattern.
 *
 * Breakout rules: a close above [up] (+ buffer) is a bullish break, a close below [down] (− buffer) a bearish one.
 * For a directional pattern the break against [direction] is an invalidation; when [bidirectional] either side
 * confirms and sets the direction.
 */
internal class Geometry(
    val type: PatternType,
    val direction: Bias,
    val bidirectional: Boolean,
    val start: Int,
    /** Last bar that belongs to the formation (last anchor, or the last flag/handle bar). */
    val end: Int,
    val anchors: List<Swing>,
    val anchorLabels: List<String>,
    /** Lines to draw: (line, from bar, role). Lines run to the breakout bar (or the latest bar). */
    val lines: List<Triple<TrendLine, Int, SegmentRole>>,
    val levels: List<PriceLevel>,
    val up: (Int) -> Double,
    val down: (Int) -> Double,
    val stopBull: Double,
    val stopBear: Double,
    val height: Double,
    val expiresAt: Int,
    /** 0..1: how cleanly the textbook definition is met. */
    val fit: Double,
    val note: String,
    /** Re-fit each bar while forming (flags, handles). */
    val evolving: Boolean = false,
    /** Candlesticks: targets in R multiples from the entry instead of a measured move. */
    val rTargets: Boolean = false,
) {
    val anchorKey get() = anchors.joinToString(",") { "${it.index}" }
}

internal object ChartDetectors {

    private fun f(v: Double) = String.format(java.util.Locale.US, "%,.2f", v)
    private fun r1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    // ------------------------------------------------------------------ reversal patterns (zigzag tail)

    private fun legsOk(cfg: PatternConfig, pts: List<Swing>) = pts.zipWithNext().all { (a, b) -> b.index - a.index >= cfg.minLegBars }

    /** Reversal patterns whose LAST swing is the newest zigzag point. Most complex first; simpler ones it contains are skipped. */
    fun reversals(x: Context, zz: List<Swing>): List<Geometry> {
        if (zz.size < 4) return emptyList()
        if (zz.last().index - zz[zz.lastIndex - 1].index < x.cfg.minLegBars) return emptyList()   // newest leg is a spike
        headAndShoulders(x, zz)?.let { return listOf(it) }
        triple(x, zz)?.let { return listOf(it) }
        return listOfNotNull(double(x, zz))
    }

    private fun double(x: Context, zz: List<Swing>): Geometry? {
        val k = zz.lastIndex
        val p2 = zz[k]; val nk = zz[k - 1]; val p1 = zz[k - 2]; val before = zz[k - 3]
        val top = p2.high
        val a = x.atrAt(p2.index)
        val cfg = x.cfg
        if (!legsOk(cfg, listOf(p1, nk, p2))) return null
        val diff = abs(p1.price - p2.price)
        if (diff > cfg.equalityAtr * a) return null
        val extreme = if (top) max(p1.price, p2.price) else min(p1.price, p2.price)
        val height = abs((p1.price + p2.price) / 2 - nk.price)
        if (height < cfg.minHeightAtr * a) return null
        val width = p2.index - p1.index
        if (width < cfg.minPatternBars || width > cfg.maxPatternBars) return null
        if (abs(p1.price - before.price) < 0.75 * height) return null                       // needs a prior trend into the first peak
        val neck = nk.price
        val type = if (top) PatternType.DOUBLE_TOP else PatternType.DOUBLE_BOTTOM
        val word = if (top) "peaks" else "troughs"
        return Geometry(
            type, type.expected, false, p1.index, p2.index, listOf(before, p1, nk, p2),
            listOf("Start", if (top) "Top 1" else "Bottom 1", "Neckline", if (top) "Top 2" else "Bottom 2"),
            listOf(Triple(TrendLine(nk.index, neck, 0.0), p1.index, SegmentRole.NECKLINE)),
            listOf(PriceLevel(if (top) "Top 1" else "Bottom 1", p1.price), PriceLevel(if (top) "Top 2" else "Bottom 2", p2.price), PriceLevel("Neckline", neck)),
            up = { if (top) extreme else neck }, down = { if (top) neck else extreme },
            stopBull = extreme - cfg.stopBufferAtr * a, stopBear = extreme + cfg.stopBufferAtr * a,
            height = height, expiresAt = p2.index + max(10, width), fit = 1 - diff / (cfg.equalityAtr * a),
            note = "Two $word at ${f(p1.price)} and ${f(p2.price)} (${r1(diff / a)} ATR apart), $width bars apart, with the neckline at ${f(neck)}. " +
                "Pattern height ${f(height)} (${r1(height / a)} ATR).",
        )
    }

    private fun triple(x: Context, zz: List<Swing>): Geometry? {
        if (zz.size < 6) return null
        val k = zz.lastIndex
        val p = listOf(zz[k - 4], zz[k - 2], zz[k]); val n1 = zz[k - 3]; val n2 = zz[k - 1]; val before = zz[k - 5]
        val top = zz[k].high
        val a = x.atrAt(zz[k].index)
        val cfg = x.cfg
        if (!legsOk(cfg, zz.subList(k - 4, k + 1))) return null
        val spread = p.maxOf { it.price } - p.minOf { it.price }
        if (spread > cfg.equalityAtr * a) return null
        val neck = if (top) min(n1.price, n2.price) else max(n1.price, n2.price)
        val mean = p.sumOf { it.price } / 3
        val height = abs(mean - neck)
        if (height < cfg.minHeightAtr * a) return null
        val width = p[2].index - p[0].index
        if (width < cfg.minPatternBars || width > cfg.maxPatternBars) return null
        if (p[1].index - p[0].index < 3 || p[2].index - p[1].index < 3) return null
        if (abs(p[0].price - before.price) < 0.75 * height) return null
        val extreme = if (top) p.maxOf { it.price } else p.minOf { it.price }
        val type = if (top) PatternType.TRIPLE_TOP else PatternType.TRIPLE_BOTTOM
        val w = if (top) "Top" else "Bottom"
        return Geometry(
            type, type.expected, false, p[0].index, p[2].index, listOf(before, p[0], n1, p[1], n2, p[2]),
            listOf("Start", "$w 1", "Trough", "$w 2", "Trough", "$w 3"),
            listOf(Triple(TrendLine(p[0].index, neck, 0.0), p[0].index, SegmentRole.NECKLINE)),
            listOf(PriceLevel("$w 1", p[0].price), PriceLevel("$w 2", p[1].price), PriceLevel("$w 3", p[2].price), PriceLevel(if (top) "Support" else "Resistance", neck)),
            up = { if (top) extreme else neck }, down = { if (top) neck else extreme },
            stopBull = extreme - cfg.stopBufferAtr * a, stopBear = extreme + cfg.stopBufferAtr * a,
            height = height, expiresAt = p[2].index + max(10, width), fit = 1 - spread / (cfg.equalityAtr * a),
            note = "Three ${if (top) "tops" else "bottoms"} within ${r1(spread / a)} ATR of each other over $width bars, " +
                "${if (top) "support" else "resistance"} at ${f(neck)}. Height ${f(height)} (${r1(height / a)} ATR).",
        )
    }

    private fun headAndShoulders(x: Context, zz: List<Swing>): Geometry? {
        if (zz.size < 6) return null
        val k = zz.lastIndex
        val before = zz[k - 5]; val ls = zz[k - 4]; val n1 = zz[k - 3]; val hd = zz[k - 2]; val n2 = zz[k - 1]; val rs = zz[k]
        val top = rs.high
        val s = if (top) 1.0 else -1.0                      // mirror everything for the inverse pattern
        val a = x.atrAt(rs.index)
        val cfg = x.cfg
        if (!legsOk(cfg, listOf(ls, n1, hd, n2, rs))) return null
        val neck = TrendLine.through(n1, n2)
        val height = s * (hd.price - neck.at(hd.index))
        if (height < cfg.minHeightAtr * a) return null
        if (s * (hd.price - ls.price) < cfg.equalityAtr * a || s * (hd.price - rs.price) < cfg.equalityAtr * a) return null   // head stands out
        val shoulderDiff = abs(ls.price - rs.price)
        if (shoulderDiff > 2 * cfg.equalityAtr * a) return null
        if (s * (ls.price - neck.at(ls.index)) < 0.4 * height || s * (rs.price - neck.at(rs.index)) < 0.4 * height) return null
        if (abs(n2.price - n1.price) > max(1.5 * a, 0.35 * height)) return null              // neckline not too steep
        val left = hd.index - ls.index; val right = rs.index - hd.index
        val ratio = left.toDouble() / right.coerceAtLeast(1)
        if (ratio !in 0.4..2.5) return null
        val width = rs.index - ls.index
        if (width < cfg.minPatternBars || width > cfg.maxPatternBars) return null
        // Prior trend: price rose into the left shoulder from below the neckline (fell into it from above, inverse).
        val cameFromBeyondNeck = if (top) before.price < min(n1.price, n2.price) else before.price > max(n1.price, n2.price)
        if (s * (ls.price - before.price) < 0.5 * height || !cameFromBeyondNeck) return null
        val type = if (top) PatternType.HEAD_AND_SHOULDERS else PatternType.INVERSE_HEAD_AND_SHOULDERS
        val fit = 0.5 * (1 - shoulderDiff / (2 * cfg.equalityAtr * a)) + 0.5 * (1 - abs(ln(ratio)) / ln(2.5))
        return Geometry(
            type, type.expected, false, ls.index, rs.index, listOf(before, ls, n1, hd, n2, rs),
            listOf("Start", "Left shoulder", "Neckline 1", "Head", "Neckline 2", "Right shoulder"),
            listOf(Triple(neck, n1.index, SegmentRole.NECKLINE)),
            listOf(PriceLevel("Left shoulder", ls.price), PriceLevel("Head", hd.price), PriceLevel("Right shoulder", rs.price), PriceLevel("Neckline (at head)", neck.at(hd.index))),
            up = { i -> if (top) hd.price else neck.at(i) }, down = { i -> if (top) neck.at(i) else hd.price },
            stopBull = rs.price - cfg.stopBufferAtr * a, stopBear = rs.price + cfg.stopBufferAtr * a,
            height = height, expiresAt = rs.index + max(10, width), fit = fit.coerceIn(0.0, 1.0),
            note = "Head ${f(hd.price)} ${if (top) "above" else "below"} shoulders ${f(ls.price)} and ${f(rs.price)} (${r1(shoulderDiff / a)} ATR apart), " +
                "left/right spans $left/$right bars, neckline ${f(n1.price)} → ${f(n2.price)}. Height ${f(height)} (${r1(height / a)} ATR).",
        )
    }

    // ------------------------------------------------------------------ trendline patterns

    /**
     * Fits upper and lower trendlines to the last 7, 6, 5 or 4 zigzag points (longest that fits) and classifies by
     * slope (in ATR over the pattern) and by whether the lines converge or run parallel.
     */
    fun trendlines(x: Context, zz: List<Swing>): Geometry? {
        val cfg = x.cfg
        for (m in minOf(7, zz.size) downTo 4) {
            val pts = zz.subList(zz.size - m, zz.size)
            val hs = pts.filter { it.high }; val ls = pts.filter { !it.high }
            if (hs.size < 2 || ls.size < 2) continue
            val s = pts.first().index; val e = pts.last().index; val w = e - s
            if (w < cfg.minPatternBars || w > cfg.maxPatternBars) continue
            val a = x.atrAt(e)
            val u = TrendLine.fit(hs.map { it.index to it.price })
            val l = TrendLine.fit(ls.map { it.index to it.price })
            val res = max(hs.maxOf { abs(it.price - u.at(it.index)) }, ls.maxOf { abs(it.price - l.at(it.index)) })
            val tol = cfg.trendlineFitAtr * a
            if (res > tol) continue
            val w0 = u.at(s) - l.at(s); val w1 = u.at(e) - l.at(e)
            if (w0 <= 0 || w1 <= 0) continue
            if ((s..e).any { i -> x.c[i].close > u.at(i) + tol || x.c[i].close < l.at(i) - tol }) continue   // no close outside during formation
            val du = u.slope * w / a; val dl = l.slope * w / a
            val flat = cfg.flatAtr
            val converging = w0 - w1 > flat * a && w1 <= 0.75 * w0
            val parallel = abs(w1 - w0) <= flat * a
            val type = when {
                abs(du) <= flat && abs(dl) <= flat && parallel -> PatternType.RECTANGLE
                abs(du) <= flat && dl > flat && converging -> PatternType.ASCENDING_TRIANGLE
                abs(dl) <= flat && du < -flat && converging -> PatternType.DESCENDING_TRIANGLE
                du < -flat && dl > flat -> PatternType.SYMMETRICAL_TRIANGLE
                du > flat && dl > flat && converging -> PatternType.RISING_WEDGE
                du < -flat && dl < -flat && converging -> PatternType.FALLING_WEDGE
                du > flat && dl > flat && parallel -> PatternType.ASCENDING_CHANNEL
                du < -flat && dl < -flat && parallel -> PatternType.DESCENDING_CHANNEL
                else -> null
            } ?: continue
            val height = if (converging || type == PatternType.SYMMETRICAL_TRIANGLE) w0 else (w0 + w1) / 2
            if (height < cfg.minHeightAtr * a) continue
            val bidir = type.expected == Bias.NEUTRAL
            val dir = if (bidir) x.trendBefore(s, 30, 2.0) else type.expected
            val apex = if (u.slope != l.slope) s + (w0 / (l.slope - u.slope)).toInt() else Int.MAX_VALUE
            val expires = if (apex > e) min(apex, e + w) else e + w
            val lastLow = ls.last(); val lastHigh = hs.last()
            val fit = 0.7 * (1 - res / tol) + 0.3 * (m - 4) / 3.0
            return Geometry(
                type, dir, bidir, s, e, pts, pts.map { if (it.high) "High" else "Low" },
                listOf(Triple(u, s, SegmentRole.UPPER), Triple(l, s, SegmentRole.LOWER)),
                listOf(PriceLevel("Upper line (now)", u.at(e)), PriceLevel("Lower line (now)", l.at(e))),
                up = { i -> u.at(i) }, down = { i -> l.at(i) },
                stopBull = lastLow.price - cfg.stopBufferAtr * a, stopBear = lastHigh.price + cfg.stopBufferAtr * a,
                height = height, expiresAt = max(expires, e + 5), fit = fit.coerceIn(0.0, 1.0),
                note = "${hs.size} swing highs and ${ls.size} swing lows over $w bars. Upper line moves ${r1(du)} ATR, lower line ${r1(dl)} ATR " +
                    "across the pattern; width ${f(w0)} → ${f(w1)}. Every swing sits within ${r1(res / a)} ATR of its line." +
                    if (bidir) " Breakouts on either side count; ${if (dir == Bias.NEUTRAL) "there is no clear prior trend" else "the prior trend is ${dir.name.lowercase()}"}." else "",
            )
        }
        return null
    }

    // ------------------------------------------------------------------ flags (pole from the zigzag, flag from bars)

    /** The most recent zigzag leg that could be a flag pole as of bar [t]: (start, end, bullish). */
    fun latestPole(x: Context, zz: List<Swing>, t: Int): Triple<Swing, Swing, Boolean>? {
        for (k in zz.lastIndex downTo 1) {
            val end = zz[k]
            if (t - end.index > x.cfg.flagMaxBars) return null
            val start = zz[k - 1]
            if (end.index - start.index > x.cfg.poleMaxBars) continue
            if (abs(end.price - start.price) < x.cfg.poleMinAtr * x.atrAt(end.index)) continue
            return Triple(start, end, end.high)
        }
        return null
    }

    /**
     * Flag after the pole [p0]→[p1], made of bars p1+1..t. Bull flag: highs stay ≤ the pole top (+0.25 ATR), the
     * pullback retraces ≤ 50% of the pole, both envelope lines are flat or drift against the pole, and the flag is
     * at most half as tall as the pole. Bear flag mirrored.
     */
    fun flag(x: Context, p0: Swing, p1: Swing, t: Int, bull: Boolean): Geometry? {
        val cfg = x.cfg
        val len = t - p1.index
        if (len < cfg.flagMinBars || len > cfg.flagMaxBars) return null
        val a = x.atrAt(p1.index)
        val pole = abs(p1.price - p0.price)
        val bars = (p1.index + 1..t)
        val hi = bars.maxOf { x.c[it].high }; val lo = bars.minOf { x.c[it].low }
        val s = if (bull) 1.0 else -1.0
        if (bull && hi > p1.price + 0.25 * a) return null
        if (!bull && lo < p1.price - 0.25 * a) return null
        val retrace = if (bull) p1.price - lo else hi - p1.price
        if (retrace > 0.5 * pole) return null
        val upper = TrendLine.envelope(bars.map { it to x.c[it].high }, true)
        val lower = TrendLine.envelope(bars.map { it to x.c[it].low }, false)
        val drift = max(s * upper.slope, s * lower.slope) * len / a                  // steepest movement in the pole's direction, ATR
        if (drift > 0.5) return null
        val width = bars.map { upper.at(it) - lower.at(it) }.average()
        if (width > 0.5 * pole) return null
        val type = if (bull) PatternType.BULL_FLAG else PatternType.BEAR_FLAG
        val invalid = p1.price - s * 0.5 * pole
        val fit = 0.5 * (1 - width / (0.5 * pole)) + 0.5 * (1 - retrace / (0.5 * pole))
        return Geometry(
            type, type.expected, false, p0.index, t, listOf(p0, p1), listOf("Pole start", "Pole end"),
            listOf(Triple(TrendLine.through(p0, p1), p0.index, SegmentRole.POLE), Triple(upper, p1.index + 1, SegmentRole.UPPER), Triple(lower, p1.index + 1, SegmentRole.LOWER)),
            listOf(PriceLevel("Pole start", p0.price), PriceLevel("Pole end", p1.price), PriceLevel("50% of pole", invalid)),
            up = { i -> if (bull) upper.at(i) else invalid }, down = { i -> if (bull) invalid else lower.at(i) },
            stopBull = lo - cfg.stopBufferAtr * a, stopBear = hi + cfg.stopBufferAtr * a,
            height = pole, expiresAt = p1.index + cfg.flagMaxBars, fit = fit.coerceIn(0.0, 1.0), evolving = true,
            note = "Pole of ${f(pole)} (${r1(pole / a)} ATR) in ${p1.index - p0.index} bars, then a $len-bar flag that retraced ${(retrace / pole * 100).toInt()}% of it " +
                "and drifts ${if (s * upper.slope < 0) "against" else "sideways to"} the pole.",
        )
    }

    // ------------------------------------------------------------------ cup and handle

    /**
     * Cup between two zigzag highs [left] and [right] (rims within 1.5 × equality ATR, no higher high in between),
     * depth ≥ max(minHeight, 3) ATR and ≤ 50% of price, rounded (≥ 30% of the cup's closes in its lower half and
     * the low in the middle 60% of the cup), after an uptrend. Null if it isn't a cup.
     */
    fun cup(x: Context, left: Swing, right: Swing): Triple<Int, Double, Double>? {
        val cfg = x.cfg
        val len = right.index - left.index
        if (len < cfg.cupMinBars || len > cfg.maxPatternBars) return null
        val a = x.atrAt(right.index)
        if (abs(left.price - right.price) > 1.5 * cfg.equalityAtr * a) return null
        val rim = max(left.price, right.price)
        val inside = (left.index + 1 until right.index)
        if (inside.any { x.c[it].high > rim + 0.25 * a }) return null
        val bottomIdx = inside.minBy { x.c[it].low }
        val bottom = x.c[bottomIdx].low
        val depth = min(left.price, right.price) - bottom
        if (depth < max(cfg.minHeightAtr, 3.0) * a || depth > 0.5 * rim) return null
        val pos = (bottomIdx - left.index).toDouble() / len
        if (pos !in 0.2..0.8) return null
        val lowerHalf = inside.count { x.c[it].close <= bottom + depth / 2 }.toDouble() / inside.count()
        if (lowerHalf < 0.3) return null
        if (x.trendBefore(left.index, 40, 2.0) != Bias.BULLISH) return null
        return Triple(bottomIdx, depth, lowerHalf)
    }

    /** Cup plus a handle of bars right+1..t: stays in the upper half of the cup, below the rim, pulls back ≥ 1 ATR. */
    fun cupWithHandle(x: Context, left: Swing, right: Swing, cup: Triple<Int, Double, Double>, t: Int): Geometry? {
        val cfg = x.cfg
        val (bottomIdx, depth, lowerHalf) = cup
        val len = right.index - left.index
        val hLen = t - right.index
        val maxHandle = max(cfg.handleMinBars + 5, len / 3)
        if (hLen < cfg.handleMinBars || hLen > maxHandle) return null
        val a = x.atrAt(right.index)
        val bars = (right.index + 1..t)
        val hHigh = bars.maxOf { x.c[it].high }; val hLow = bars.minOf { x.c[it].low }
        val bottom = x.c[bottomIdx].low
        val floor = bottom + depth / 2
        if (hHigh > right.price + 0.25 * a || hLow < floor || right.price - hLow < 1.0 * a) return null
        val rimTol = 1.5 * cfg.equalityAtr * a
        val fit = 0.4 * (1 - abs(left.price - right.price) / rimTol) + 0.3 * min(1.0, lowerHalf / 0.5) + 0.3 * (1 - (right.price - hLow) / (depth / 2))
        val bot = Swing(bottomIdx, bottom, false, bottomIdx)
        return Geometry(
            PatternType.CUP_AND_HANDLE, Bias.BULLISH, false, left.index, t, listOf(left, bot, right), listOf("Left rim", "Cup bottom", "Right rim"),
            listOf(Triple(TrendLine(left.index, right.price, 0.0), left.index, SegmentRole.RIM),
                Triple(TrendLine.envelope(bars.map { it to x.c[it].high }, true), right.index + 1, SegmentRole.UPPER)),
            listOf(PriceLevel("Left rim", left.price), PriceLevel("Right rim", right.price), PriceLevel("Cup bottom", bottom), PriceLevel("Handle low", hLow)),
            up = { right.price }, down = { floor },
            stopBull = hLow - cfg.stopBufferAtr * a, stopBear = right.price,
            height = depth, expiresAt = right.index + maxHandle, fit = fit.coerceIn(0.0, 1.0), evolving = true,
            note = "A rounded $len-bar cup ${f(depth)} deep (${(depth / right.price * 100).toInt()}% of price; ${(lowerHalf * 100).toInt()}% of its closes in the lower half), " +
                "rims ${f(left.price)} and ${f(right.price)}, then a $hLen-bar handle that held above the cup's midpoint ${f(floor)}.",
        )
    }
}
