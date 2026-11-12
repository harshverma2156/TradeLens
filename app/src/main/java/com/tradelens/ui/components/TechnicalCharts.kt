package com.tradelens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import com.tradelens.data.model.Candle
import com.tradelens.patterns.DetectedPattern
import com.tradelens.patterns.PatternKind
import com.tradelens.patterns.PatternStatus
import com.tradelens.patterns.SegmentRole
import com.tradelens.ta.Bias
import com.tradelens.ta.Overlays
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import kotlin.math.max
import kotlin.math.min

/** Price-chart overlays the user can toggle. */
enum class Overlay(val label: String, val color: Color) {
    EMA20("EMA 20", Color(0xFF2E86DE)),
    EMA50("EMA 50", Color(0xFFE67E22)),
    EMA200("EMA 200", Color(0xFF8E44AD)),
    SMA200("SMA 200", Color(0xFF7F8C8D)),
    BOLLINGER("Bollinger", Color(0xFF95A5A6)),
    KELTNER("Keltner", Color(0xFF16A085)),
    DONCHIAN("Donchian", Color(0xFF5D6D7E)),
    SUPERTREND("Supertrend", Color(0xFF138A55)),
    PSAR("Parabolic SAR", Color(0xFFB57A06)),
    ICHIMOKU("Ichimoku", Color(0xFF2980B9)),
    VWAP("VWAP", Color(0xFFD35400)),
    AVWAP("Anchored VWAP", Color(0xFF1ABC9C)),
    PIVOTS("Pivots", Color(0xFF7F8C8D)),
    FIB("Fibonacci", Color(0xFFC9A227)),
    PROFILE("POC / Value area", Color(0xFFAF7AC5)),
    SR("Support / resistance", Color(0xFF138A55)),
    PATTERNS("Chart patterns", Color(0xFF6C5CE7)),
}

private data class Line(val values: List<Double?>, val color: Color, val width: Dp = 1.5.dp, val dashed: Boolean = false)
private data class HLevel(val price: Double, val label: String, val color: Color)

/**
 * Candles with indicator overlays. [candles] = closed bars (+ the forming bar, if any, last); overlay series are
 * aligned to closed bars, so the forming bar simply has no overlay values. Shows the last [visible] bars.
 *
 * [patterns] (bar indices into the same closed bars) are drawn when [Overlay.PATTERNS] is on. A [highlight]ed pattern
 * is drawn alone with its labelled swing points and its breakout, stop and target levels, and the window moves to
 * show the whole pattern even if it is older than the last [visible] bars.
 */
@Composable
fun TechnicalPriceChart(
    candles: List<Candle>, overlays: Overlays, enabled: Set<Overlay>, visible: Int = 120, modifier: Modifier = Modifier,
    patterns: List<DetectedPattern> = emptyList(), highlight: DetectedPattern? = null,
) {
    if (candles.size < 2) {
        Box(modifier.fillMaxWidth().height(300.dp)) { Text("Not enough bars to chart", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val mc = LocalMarketColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall
    val end = highlight?.let { h -> min(candles.size, maxOf(h.endIndex, h.confirmedIndex ?: 0, h.events.last().index) + 25) } ?: candles.size
    val shown = max(visible, highlight?.let { end - it.startIndex + 10 } ?: 0)
    val start = max(0, end - shown)
    val win = candles.subList(start, end)
    var selected by remember(candles, start, end) { mutableStateOf<Int?>(null) }
    fun slice(v: List<Double?>) = (start until end).map { v.getOrNull(it) }
    val showPatterns = Overlay.PATTERNS in enabled || highlight != null
    val drawn = if (highlight != null) listOf(highlight) else if (showPatterns) patterns else emptyList()

    val lines = buildList {
        if (Overlay.EMA20 in enabled) add(Line(slice(overlays.ema[20].orEmpty()), Overlay.EMA20.color))
        if (Overlay.EMA50 in enabled) add(Line(slice(overlays.ema[50].orEmpty()), Overlay.EMA50.color))
        if (Overlay.EMA200 in enabled) add(Line(slice(overlays.ema[200].orEmpty()), Overlay.EMA200.color, 2.dp))
        if (Overlay.SMA200 in enabled) add(Line(slice(overlays.sma[200].orEmpty()), Overlay.SMA200.color, 2.dp, dashed = true))
        if (Overlay.BOLLINGER in enabled) overlays.bollinger.let { b ->
            add(Line(slice(b.upper), Overlay.BOLLINGER.color, 1.dp)); add(Line(slice(b.mid), Overlay.BOLLINGER.color, 1.dp, true)); add(Line(slice(b.lower), Overlay.BOLLINGER.color, 1.dp))
        }
        if (Overlay.KELTNER in enabled) overlays.keltner.let { k -> add(Line(slice(k.upper), Overlay.KELTNER.color, 1.dp, true)); add(Line(slice(k.lower), Overlay.KELTNER.color, 1.dp, true)) }
        if (Overlay.DONCHIAN in enabled) overlays.donchian.let { d -> add(Line(slice(d.upper), Overlay.DONCHIAN.color, 1.dp)); add(Line(slice(d.lower), Overlay.DONCHIAN.color, 1.dp)) }
        if (Overlay.ICHIMOKU in enabled) { add(Line(slice(overlays.ichimokuTenkan), Color(0xFF2980B9), 1.dp)); add(Line(slice(overlays.ichimokuKijun), Color(0xFFC0392B), 1.dp)) }
        if (Overlay.VWAP in enabled) add(Line(slice(overlays.vwap), Overlay.VWAP.color, 1.5.dp))
        if (Overlay.AVWAP in enabled) add(Line(slice(overlays.anchoredVwap), Overlay.AVWAP.color, 1.5.dp, true))
    }
    val levels = buildList {
        overlays.pivots?.takeIf { Overlay.PIVOTS in enabled }?.let { p ->
            add(HLevel(p.pivot, "P", Overlay.PIVOTS.color))
            p.resistances.take(2).forEachIndexed { k, v -> add(HLevel(v, "R${k + 1}", mc.loss)) }
            p.supports.take(2).forEachIndexed { k, v -> add(HLevel(v, "S${k + 1}", mc.gain)) }
        }
        if (Overlay.FIB in enabled) overlays.fibRetracement.filter { it.label != "0.0%" && it.label != "100.0%" }.forEach { add(HLevel(it.price, it.label, Overlay.FIB.color)) }
        overlays.profile?.takeIf { Overlay.PROFILE in enabled }?.let { p ->
            add(HLevel(p.poc, "POC", Overlay.PROFILE.color)); add(HLevel(p.valueAreaHigh, "VAH", Overlay.PROFILE.color.copy(alpha = 0.7f))); add(HLevel(p.valueAreaLow, "VAL", Overlay.PROFILE.color.copy(alpha = 0.7f)))
        }
        if (Overlay.SR in enabled) { overlays.supports.forEach { add(HLevel(it, "S", mc.gain)) }; overlays.resistances.forEach { add(HLevel(it, "R", mc.loss)) } }
        highlight?.let { h ->
            add(HLevel(h.breakoutLevel, "Breakout", Overlay.PATTERNS.color))
            add(HLevel(h.stopLoss, "Stop", mc.loss.takeIf { h.bullish } ?: mc.gain))
            h.targets.forEach { add(HLevel(it.price, it.label.substringBefore(' '), mc.gain.takeIf { h.bullish } ?: mc.loss)) }
        }
    }
    val st = if (Overlay.SUPERTREND in enabled) slice(overlays.supertrend.line) to (start until end).map { overlays.supertrend.up.getOrNull(it) } else null
    val sar = if (Overlay.PSAR in enabled) slice(overlays.psar.value) to (start until end).map { overlays.psar.up.getOrNull(it) } else null
    val cloud = if (Overlay.ICHIMOKU in enabled) slice(overlays.cloudA) to slice(overlays.cloudB) else null

    // Price range from candles, widened for overlays but never by more than 30% (keeps candles readable).
    val lo0 = win.minOf { it.low }; val hi0 = win.maxOf { it.high }; val span0 = (hi0 - lo0).coerceAtLeast(hi0 * 1e-6)
    val patternPrices = drawn.flatMap { p -> p.segments.flatMap { listOf(it.from, it.to) }.filter { it.index in start until end }.map { it.price } }
    val extra = (lines.flatMap { it.values } + levels.map { it.price } + (st?.first.orEmpty()) + (sar?.first.orEmpty()) + (cloud?.first.orEmpty()) + (cloud?.second.orEmpty()) + patternPrices)
        .filterNotNull().filter { it in (lo0 - span0 * 0.3)..(hi0 + span0 * 0.3) }
    val lo = min(lo0, extra.minOrNull() ?: lo0); val hi = max(hi0, extra.maxOrNull() ?: hi0)
    val pad = (hi - lo) * 0.04

    Column(modifier) {
        val sel = selected
        val c = win[sel ?: win.lastIndex]
        Text(
            "${Fmt.dateTime(c.time)}  O ${Fmt.num(c.open)} H ${Fmt.num(c.high)} L ${Fmt.num(c.low)} C ${Fmt.num(c.close)}" +
                if (sel != null && start + sel >= candles.size - 1 && candles.size > overlays.ema[20].orEmpty().size) "  (forming)" else "",
            style = label.merge(Num), color = muted, modifier = Modifier.padding(bottom = 4.dp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(300.dp).clipToBounds()
                .pointerInput(win) { detectTapGestures { p -> selected = ((p.x / size.width) * win.size).toInt().coerceIn(0, win.lastIndex) } }
                .pointerInput(win) {
                    fun at(x: Float) = ((x / size.width) * win.size).toInt().coerceIn(0, win.lastIndex)
                    detectHorizontalDragGestures(onDragStart = { selected = at(it.x) }, onDragEnd = { selected = null }, onDragCancel = { selected = null }) { ch, _ -> selected = at(ch.position.x) }
                }
        ) {
            val yLo = lo - pad; val yHi = hi + pad
            fun y(p: Double) = (size.height * (1 - (p - yLo) / (yHi - yLo))).toFloat()
            val step = size.width / win.size
            fun x(i: Int) = step * i + step / 2
            for (g in 1..4) drawLine(grid, Offset(0f, size.height * g / 5f), Offset(size.width, size.height * g / 5f), 1f)

            cloud?.let { (a, b) ->
                for (i in 1 until win.size) {
                    val a0 = a[i - 1]; val b0 = b[i - 1]; val a1 = a[i]; val b1 = b[i]
                    if (a0 == null || b0 == null || a1 == null || b1 == null) continue
                    val col = (if (a1 >= b1) mc.gain else mc.loss).copy(alpha = 0.14f)
                    val p = Path().apply { moveTo(x(i - 1), y(a0)); lineTo(x(i), y(a1)); lineTo(x(i), y(b1)); lineTo(x(i - 1), y(b0)); close() }
                    drawPath(p, col)
                }
            }
            lines.forEach { drawSeries(it.values, it.color, it.width.toPx(), it.dashed, ::x, ::y) }
            st?.let { (v, up) ->
                for (i in 1 until win.size) {
                    val p0 = v[i - 1]; val p1 = v[i]; val u0 = up[i - 1]; val u1 = up[i]
                    if (p0 == null || p1 == null || u0 == null || u1 == null || u0 != u1) continue
                    drawLine(if (u1) mc.gain else mc.loss, Offset(x(i - 1), y(p0)), Offset(x(i), y(p1)), 2.dp.toPx())
                }
            }
            sar?.let { (v, up) -> v.forEachIndexed { i, p -> if (p != null) drawCircle(if (up[i] == true) mc.gain else mc.loss, 1.8.dp.toPx(), Offset(x(i), y(p))) } }

            val bodyW = max(1f, step * 0.6f)
            win.forEachIndexed { i, k ->
                val forming = start + i == candles.lastIndex && candles.size > (overlays.ema[20]?.size ?: candles.size)
                val col = (if (k.close >= k.open) mc.gain else mc.loss).let { if (forming) it.copy(alpha = 0.45f) else it }
                drawLine(col, Offset(x(i), y(k.high)), Offset(x(i), y(k.low)), max(1f, step * 0.12f))
                val top = y(max(k.open, k.close)); val bot = y(min(k.open, k.close))
                drawRect(col, Offset(x(i) - bodyW / 2, top), Size(bodyW, max(1f, bot - top)))
            }
            // Patterns: solid once confirmed, dashed while forming, grey when failed or invalidated.
            drawn.forEach { p ->
                val dead = p.status == PatternStatus.FAILED || p.status == PatternStatus.INVALIDATED
                val col = when { dead -> muted.copy(alpha = 0.55f); p.direction == Bias.BULLISH -> mc.gain; p.direction == Bias.BEARISH -> mc.loss; else -> Overlay.PATTERNS.color }
                val dash = if (p.status == PatternStatus.CONFIRMED) null else PathEffect.dashPathEffect(floatArrayOf(9f, 6f))
                fun px(i: Int) = x(i - start)
                p.segments.forEach { s ->
                    if (s.to.index < start || s.from.index >= end) return@forEach
                    val w = if (s.role == SegmentRole.OUTLINE) 1.5.dp.toPx() else 2.dp.toPx()
                    drawLine(col, Offset(px(s.from.index), y(s.from.price)), Offset(px(s.to.index), y(s.to.price)), w, pathEffect = dash)
                }
                if (p.type.kind == PatternKind.CANDLESTICK && p.endIndex in start until end) {
                    val k = candles[p.endIndex]; val cx = px(p.endIndex); val sz = 5.dp.toPx()
                    val tri = if (p.bullish) Path().apply { val ty = y(k.low) + 4.dp.toPx(); moveTo(cx, ty); lineTo(cx - sz, ty + sz * 1.6f); lineTo(cx + sz, ty + sz * 1.6f); close() }
                    else Path().apply { val ty = y(k.high) - 4.dp.toPx(); moveTo(cx, ty); lineTo(cx - sz, ty - sz * 1.6f); lineTo(cx + sz, ty - sz * 1.6f); close() }
                    drawPath(tri, col)
                }
                if (p === highlight) {
                    p.anchors.filter { it.index in start until end }.forEach { a ->
                        drawCircle(col, 3.dp.toPx(), Offset(px(a.index), y(a.price)))
                        if (a.label.isNotBlank() && p.type.kind != PatternKind.CANDLESTICK) {
                            val t = measurer.measure(a.label, label.copy(color = col))
                            val above = a.price >= (candles[a.index].high + candles[a.index].low) / 2
                            drawText(t, topLeft = Offset((px(a.index) - t.size.width / 2).coerceIn(0f, size.width - t.size.width), if (above) y(a.price) - t.size.height - 4f else y(a.price) + 4f))
                        }
                    }
                    p.confirmedIndex?.takeIf { it in start until end }?.let { drawCircle(col, 5.dp.toPx(), Offset(px(it), y(candles[it].close)), style = Stroke(2.dp.toPx())) }
                }
            }
            levels.filter { it.price in yLo..yHi }.forEach { l ->
                val ly = y(l.price)
                drawLine(l.color, Offset(0f, ly), Offset(size.width, ly), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                val t = measurer.measure("${l.label} ${Fmt.num(l.price)}", label.copy(color = l.color))
                drawText(t, topLeft = Offset(size.width - t.size.width - 2f, ly - t.size.height))
            }
            drawText(measurer.measure(Fmt.num(hi), label.copy(color = muted)), topLeft = Offset(2f, 2f))
            val ml = measurer.measure(Fmt.num(lo), label.copy(color = muted))
            drawText(ml, topLeft = Offset(2f, size.height - ml.size.height - 2f))
            selected?.let { drawLine(muted, Offset(x(it), 0f), Offset(x(it), size.height), 1f) }
        }
    }
}

/** One oscillator series to draw. */
data class OscLine(val name: String, val values: List<Double?>, val color: Color, val histogram: Boolean = false)

/**
 * Oscillator panel, aligned bar-for-bar with the price chart (same [visible] window). [refLines] are horizontal
 * reference levels (e.g. 30/70); [range] fixes the y-axis (e.g. 0..100) or null to auto-scale.
 */
@Composable
fun OscillatorChart(lines: List<OscLine>, totalBars: Int, visible: Int = 120, refLines: List<Double> = emptyList(), range: ClosedFloatingPointRange<Double>? = null, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val mc = LocalMarketColors.current
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall
    val start = max(0, totalBars - visible)
    val n = totalBars - start
    val win = lines.map { l -> l.copy(values = (start until totalBars).map { l.values.getOrNull(it) }) }
    val all = win.flatMap { it.values }.filterNotNull() + refLines
    if (n < 2 || all.isEmpty()) { Text("Not enough bars yet", color = muted, modifier = modifier.padding(16.dp)); return }
    val lo = range?.start ?: all.min()
    val hi = range?.endInclusive ?: all.max()
    val pad = if (range == null) (hi - lo).coerceAtLeast(1e-9) * 0.08 else 0.0
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            win.forEach { l -> Text("${l.name} ${Fmt.num(l.values.lastOrNull { it != null } ?: Double.NaN)}", style = label.merge(Num), color = l.color) }
        }
        Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 4.dp)) {
            val yLo = lo - pad; val yHi = hi + pad
            fun y(v: Double) = (size.height * (1 - (v - yLo) / (yHi - yLo))).toFloat()
            val step = size.width / n
            fun x(i: Int) = step * i + step / 2
            refLines.forEach { r ->
                drawLine(grid, Offset(0f, y(r)), Offset(size.width, y(r)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                drawText(measurer.measure(Fmt.num(r, 0), label.copy(color = muted)), topLeft = Offset(2f, y(r) - 14f))
            }
            win.forEach { l ->
                if (l.histogram) {
                    val zero = y(0.0.coerceIn(yLo, yHi))
                    l.values.forEachIndexed { i, v -> if (v != null) drawRect(if (v >= 0) mc.gain.copy(alpha = 0.6f) else mc.loss.copy(alpha = 0.6f), Offset(x(i) - step * 0.35f, min(zero, y(v))), Size(step * 0.7f, kotlin.math.abs(y(v) - zero))) }
                } else drawSeries(l.values, l.color, 1.5.dp.toPx(), false, ::x, ::y)
            }
        }
    }
}

private fun DrawScope.drawSeries(v: List<Double?>, color: Color, width: Float, dashed: Boolean, x: (Int) -> Float, y: (Double) -> Float) {
    var path: Path? = null
    v.forEachIndexed { i, p ->
        if (p == null || !p.isFinite()) { path?.let { drawPath(it, color, style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)) }; path = null }
        else if (path == null) path = Path().apply { moveTo(x(i), y(p)) } else path!!.lineTo(x(i), y(p))
    }
    path?.let { drawPath(it, color, style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)) }
}
