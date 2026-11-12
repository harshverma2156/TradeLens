package com.tradelens.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradelens.data.model.Candle
import com.tradelens.data.model.Quote
import com.tradelens.flow.*
import com.tradelens.marketdata.Freshness
import com.tradelens.ta.Bias
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import java.text.SimpleDateFormat
import java.util.Locale

/** Volume, delivery, order book and reported institutional activity. Every missing item says why it is missing. */
@Composable
internal fun FlowSection(f: FlowReport, daily: List<Candle>, q: Quote) {
    val a = f.assessment
    val mc = LocalMarketColors.current
    val biasCol = when (a.bias) { Bias.BULLISH -> mc.gain; Bias.BEARISH -> mc.loss; Bias.NEUTRAL -> mc.neutral }
    Section("Volume & institutional activity", trailing = {
        Surface(color = biasCol.copy(alpha = 0.14f), contentColor = biasCol, shape = RoundedCornerShape(50)) {
            Text(a.label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }) {
        Text("Inferred pressure score ${if (a.score > 0) "+" else ""}${a.score}", style = MaterialTheme.typography.titleSmall)
        Text(a.caveat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- volume
        Heading("Volume")
        when (val v = f.volume) {
            is DataPoint.Available -> {
                val s = v.value
                KeyValue("Current volume", s.current?.let { Fmt.volume(it) + if (s.intradayPartial) " (so far)" else "" } ?: unavailable("no volume"))
                KeyValue("Average (20 bars)", s.average20?.let { Fmt.volume(it.toLong()) } ?: unavailable("needs 21 bars"))
                KeyValue("Relative volume", s.relativeVolume?.let { String.format(Locale.US, "%.2f×", it) + if (s.intradayPartial) " (time-adjusted)" else "" } ?: unavailable("no average"),
                    emphasize = (s.relativeVolume ?: 0.0) >= 2.0)
                KeyValue("Volume spikes (last 60 bars, ≥ 2×)", s.spikes.size.toString())
                KeyValue("Up-day ÷ down-day volume (20 bars)", s.upDownVolumeRatio?.let { String.format(Locale.US, "%.2f×", it) } ?: unavailable("no down days"))
                KeyValue("OBV change (20 bars)", s.obvChange20?.let { Fmt.volume(it.toLong()) } ?: unavailable("n/a"))
                KeyValue("Accumulation/distribution change (20 bars)", s.adChange20?.let { Fmt.volume(it.toLong()) } ?: unavailable("n/a"))
                KeyValue("Money Flow Index (14)", s.mfi?.let { Fmt.num(it, 1) } ?: unavailable("n/a"))
                KeyValue("Point of control (126 bars)", s.poc?.let { Fmt.num(it) } ?: unavailable("n/a"))
                KeyValue("Value area (70%)", if (s.valueAreaLow != null && s.valueAreaHigh != null) "${Fmt.num(s.valueAreaLow)} – ${Fmt.num(s.valueAreaHigh)}" else unavailable("n/a"))
                KeyValue("Anchored VWAP" + (s.anchoredFrom?.let { " from ${Fmt.date(it)}" } ?: ""), s.anchoredVwap?.let { Fmt.num(it) } ?: unavailable("n/a"))
                VolumeBars(daily)
                ProvenanceLine(v.source)
            }
            is DataPoint.Unavailable -> UnavailableRow(v.reason)
        }

        // ---- delivery
        Heading("Delivery")
        when (val d = f.delivery) {
            is DataPoint.Available -> {
                val s = d.value
                KeyValue("Latest delivery", s.latestPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: unavailable("n/a"))
                KeyValue("20-day average delivery", s.avgPct20?.let { String.format(Locale.US, "%.1f%%", it) } ?: unavailable("n/a"))
                KeyValue("5-day vs 20-day delivery", s.trendPts?.let { String.format(Locale.US, "%+.1f pts", it) } ?: unavailable("n/a"))
                KeyValue("Deliverable quantity trend (5 ÷ 20 days)", s.deliveryVolumeTrend?.let { String.format(Locale.US, "%.2f×", it) } ?: unavailable("n/a"))
                ProvenanceLine(d.source)
            }
            is DataPoint.Unavailable -> UnavailableRow(d.reason)
        }

        // ---- market depth
        Heading("Market depth (order book snapshot)")
        when (val dp = f.depthAnalytics) {
            is DataPoint.Available -> {
                val x = dp.value
                BidAskBar(x.bidQtyTopN, x.askQtyTopN)
                KeyValue("Best bid / ask", "${Fmt.num(x.bestBid.price)} (${Fmt.volume(x.bestBid.quantity)}) / ${Fmt.num(x.bestAsk.price)} (${Fmt.volume(x.bestAsk.quantity)})")
                KeyValue("Spread", "${Fmt.num(x.spread)}  (${String.format(Locale.US, "%.3f%%", x.spreadPct)})")
                KeyValue("Bid/ask ratio (top ${x.comparableLevels})", String.format(Locale.US, "%.2f", x.bidAskRatio))
                KeyValue("Order-book imbalance (top ${x.comparableLevels})", String.format(Locale.US, "%+.2f", x.imbalance))
                KeyValue("Largest level's share (bid / ask)", "${String.format(Locale.US, "%.0f%%", x.bidConcentration * 100)} / ${String.format(Locale.US, "%.0f%%", x.askConcentration * 100)}")
                Text(x.method, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                f.depth.let { if (it is DataPoint.Available) ProvenanceLine(it.source) }
            }
            is DataPoint.Unavailable -> UnavailableRow(dp.reason)
        }

        // ---- reported institutional activity (never blended into the score)
        Heading("Reported institutional activity")
        Text("Listed with their dates. Reports show who traded or holds shares; they don't show intent.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val fd = f.fiiDii) {
            is DataPoint.Available -> fd.value.takeLast(5).reversed().forEach { day ->
                KeyValue("FII/FPI net · ${Fmt.date(day.date)}", day.fiiNet?.let { String.format(Locale.US, "%+,.0f cr", it) } ?: unavailable("n/a"))
                KeyValue("DII net · ${Fmt.date(day.date)}", day.diiNet?.let { String.format(Locale.US, "%+,.0f cr", it) } ?: unavailable("n/a"))
            }
            is DataPoint.Unavailable -> UnavailableRow("FII/DII flows: ${fd.reason}")
        }
        when (val sh = f.shareholding) {
            is DataPoint.Available -> sh.value.lastOrNull()?.let { s ->
                KeyValue("Promoter holding · ${Fmt.date(s.quarterEnd)}", s.promoterPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: unavailable("n/a"))
                KeyValue("Promoter pledged", s.promoterPledgedPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: unavailable("n/a"))
                KeyValue("FII / DII holding", "${s.fiiPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—"} / ${s.diiPct?.let { String.format(Locale.US, "%.2f%%", it) } ?: "—"}")
            }
            is DataPoint.Unavailable -> UnavailableRow("Shareholding: ${sh.reason}")
        }
        when (val dl = f.deals) {
            is DataPoint.Available -> if (dl.value.isEmpty()) Text("No bulk or block deals in the period.", style = MaterialTheme.typography.bodySmall)
                else dl.value.take(8).forEach { d -> Bullet("${Fmt.date(d.date)} · ${d.kind.name.lowercase()} · ${d.client} ${d.side.name.lowercase()} ${Fmt.volume(d.quantity)} @ ${Fmt.num(d.price)}") }
            is DataPoint.Unavailable -> UnavailableRow("Bulk and block deals: ${dl.reason}")
        }
        when (val dc = f.disclosures) {
            is DataPoint.Available -> if (dc.value.isEmpty()) Text("No insider or promoter disclosures in the period.", style = MaterialTheme.typography.bodySmall)
                else dc.value.take(8).forEach { d -> Bullet("${Fmt.date(d.date)} · ${d.person} (${d.category}) ${d.side.name.lowercase()} ${Fmt.volume(d.quantity)}") }
            is DataPoint.Unavailable -> UnavailableRow("Disclosures: ${dc.reason}")
        }

        // ---- signals
        if (a.signals.isNotEmpty()) {
            Heading("Signals")
            a.signals.forEach { s ->
                val c = when (s.bias) { Bias.BULLISH -> mc.gain; Bias.BEARISH -> mc.loss; Bias.NEUTRAL -> mc.neutral }
                Bullet("${s.kind.label} (${s.strength}): ${s.headline}", c)
            }
        } else Text("No volume signal today.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        a.context.forEach { Bullet(it) }
        a.depthNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        a.reportedNotes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun unavailable(why: String) = "Unavailable ($why)"

@Composable
private fun UnavailableRow(reason: String) {
    Text("Unavailable", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProvenanceLine(p: Provenance) {
    val when_ = p.asOf?.let { SimpleDateFormat("dd MMM yyyy", Locale.US).format(it) } ?: "—"
    Text(
        "${p.provider} · as of $when_ · ${p.freshness.label}${if (p.official) " · exchange-published" else ""}" + (p.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.padding(top = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodySmall, color = color)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Last 60 daily volumes; bars above 2× the trailing 20-bar average are highlighted. */
@Composable
private fun VolumeBars(daily: List<Candle>) {
    val bars = daily.takeLast(60)
    if (bars.size < 21) return
    val mc = LocalMarketColors.current
    val vols = bars.map { it.volume.toDouble() }
    val max = vols.maxOrNull()?.takeIf { it > 0 } ?: return
    Spacer(Modifier.height(8.dp))
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val step = size.width / bars.size
        val w = step * 0.7f
        bars.forEachIndexed { i, b ->
            val avg = if (i >= 20) (i - 20 until i).map { vols[it] }.average() else null
            val spike = avg != null && avg > 0 && vols[i] / avg >= 2.0
            val col = when {
                spike && b.close >= b.open -> mc.gain
                spike -> mc.loss
                b.close >= b.open -> mc.gain.copy(alpha = 0.35f)
                else -> mc.loss.copy(alpha = 0.35f)
            }
            val h = (size.height * vols[i] / max).toFloat()
            drawRect(col, Offset(step * i + (step - w) / 2, size.height - h), Size(w, h))
        }
    }
    Text("Daily volume, last ${bars.size} bars (highlighted: ≥ 2× the 20-bar average)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Bid vs ask quantity over the comparable levels. */
@Composable
private fun BidAskBar(bid: Long, ask: Long) {
    val mc = LocalMarketColors.current
    val total = (bid + ask).coerceAtLeast(1).toFloat()
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().height(12.dp)) {
        Box(Modifier.weight(bid / total).fillMaxHeight().background(mc.gain))
        Box(Modifier.weight(ask / total).fillMaxHeight().background(mc.loss))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("Bid ${Fmt.volume(bid)}", style = MaterialTheme.typography.labelSmall, color = mc.gain, modifier = Modifier.weight(1f))
        Text("Ask ${Fmt.volume(ask)}", style = MaterialTheme.typography.labelSmall, color = mc.loss)
    }
}
