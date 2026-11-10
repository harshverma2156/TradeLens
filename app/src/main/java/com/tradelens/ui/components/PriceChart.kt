package com.tradelens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tradelens.data.model.Candle
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class LevelLine(val price: Double, val label: String, val color: Color)

/** Candlestick or line chart with draggable crosshair and optional horizontal levels (entry, SL, targets). */
@Composable
fun PriceChart(
    candles: List<Candle>,
    candleMode: Boolean,
    intraday: Boolean,
    levels: List<LevelLine>,
    modifier: Modifier = Modifier,
) {
    if (candles.size < 2) {
        Box(modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
            Text("Not enough data for this range", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val mc = LocalMarketColors.current
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = if (candles.last().close >= candles.first().close) mc.gain else mc.loss
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    var selected by remember(candles) { mutableStateOf<Int?>(null) }
    val dateFmt = remember(intraday) { SimpleDateFormat(if (intraday) "dd MMM, HH:mm" else "dd MMM yyyy", Locale.getDefault()) }

    // Keep levels from squashing the chart: only include those within 25% of the visible price range.
    val lowP = candles.minOf { it.low }
    val highP = candles.maxOf { it.high }
    val span = highP - lowP
    val shownLevels = levels.filter { it.price in (lowP - span * 0.25)..(highP + span * 0.25) }

    Column(modifier) {
        val c = candles[selected ?: candles.lastIndex]
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(dateFmt.format(Date(c.time)), style = labelStyle, color = muted, modifier = Modifier.weight(1f))
            Text(
                "O ${Fmt.num(c.open)}  H ${Fmt.num(c.high)}  L ${Fmt.num(c.low)}  C ${Fmt.num(c.close)}",
                style = labelStyle.merge(Num), color = muted,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .pointerInput(candles) {
                    detectTapGestures { pos ->
                        selected = ((pos.x / size.width) * candles.size).toInt().coerceIn(0, candles.lastIndex)
                    }
                }
                .pointerInput(candles) {
                    fun indexAt(x: Float) = ((x / size.width) * candles.size).toInt().coerceIn(0, candles.lastIndex)
                    detectHorizontalDragGestures(
                        onDragStart = { selected = indexAt(it.x) },
                        onDragEnd = { selected = null },
                        onDragCancel = { selected = null },
                    ) { change, _ -> selected = indexAt(change.position.x) }
                }
        ) {
            val minP = min(lowP, shownLevels.minOfOrNull { it.price } ?: lowP)
            val maxP = max(highP, shownLevels.maxOfOrNull { it.price } ?: highP)
            val pad = (maxP - minP).coerceAtLeast(0.01) * 0.06
            val lo = minP - pad
            val hi = maxP + pad
            fun y(p: Double) = (size.height * (1 - (p - lo) / (hi - lo))).toFloat()
            val step = size.width / candles.size

            for (i in 1..4) {
                val gy = size.height * i / 5f
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }

            if (candleMode) {
                val bodyW = max(1f, step * 0.62f)
                candles.forEachIndexed { i, k ->
                    val cx = step * i + step / 2
                    val col = if (k.close >= k.open) mc.gain else mc.loss
                    drawLine(col, Offset(cx, y(k.high)), Offset(cx, y(k.low)), strokeWidth = max(1f, step * 0.12f))
                    val top = y(max(k.open, k.close))
                    val bottom = y(min(k.open, k.close))
                    drawRect(col, topLeft = Offset(cx - bodyW / 2, top), size = Size(bodyW, max(1f, bottom - top)))
                }
            } else {
                val path = Path()
                candles.forEachIndexed { i, k ->
                    val x = step * i + step / 2
                    if (i == 0) path.moveTo(x, y(k.close)) else path.lineTo(x, y(k.close))
                }
                val fill = Path().apply {
                    addPath(path)
                    lineTo(step * candles.lastIndex + step / 2, size.height)
                    lineTo(step / 2, size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.22f), Color.Transparent)))
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
            }

            shownLevels.forEach { l ->
                val ly = y(l.price)
                drawLine(l.color, Offset(0f, ly), Offset(size.width, ly), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                val t = measurer.measure("${l.label} ${Fmt.num(l.price)}", labelStyle.copy(color = l.color))
                drawText(t, topLeft = Offset(size.width - t.size.width - 4f, ly - t.size.height - 2f))
            }

            drawText(measurer.measure(Fmt.num(maxP), labelStyle.copy(color = muted)), topLeft = Offset(4f, 2f))
            val minLabel = measurer.measure(Fmt.num(minP), labelStyle.copy(color = muted))
            drawText(minLabel, topLeft = Offset(4f, size.height - minLabel.size.height - 2f))

            selected?.let { i ->
                val cx = step * i + step / 2
                drawLine(muted, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1f)
                drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(cx, y(candles[i].close)))
            }
        }
    }
}
