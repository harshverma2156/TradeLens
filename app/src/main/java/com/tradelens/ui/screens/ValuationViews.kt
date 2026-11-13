package com.tradelens.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradelens.flow.DataPoint
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.valuation.*
import java.util.Locale

/**
 * Valuation on the stock page. Every figure here is a model estimate from reported statements and the listed
 * assumptions. It is not a market price, and no analyst consensus is included.
 */
@Composable
internal fun ValuationSection(outcome: ValuationOutcome) {
    val mc = LocalMarketColors.current
    val r = outcome.report
    if (r == null) {
        Section("Valuation (model estimates)") {
            KeyValue("Status", "Unavailable", valueColor = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(outcome.unavailableReason ?: "No valuation data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val mos = r.marginOfSafetyPct
    val verdict = when {
        r.fairBase == null || mos == null -> "Not valued" to MaterialTheme.colorScheme.onSurfaceVariant
        mos >= r.marginOfSafetyPreference * 100 -> "Undervalued" to mc.gain
        mos >= 0 -> "Below fair value" to mc.neutral
        else -> "Above fair value" to mc.loss
    }
    Section("Valuation (model estimates)", trailing = {
        Surface(color = verdict.second.copy(alpha = 0.14f), contentColor = verdict.second, shape = RoundedCornerShape(50)) {
            Text(verdict.first, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }) {
        Text(r.dataNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(r.primaryNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Heading("Price and fair value")
        KeyValue("Current market price (quote feed)", r.price?.let { rupee(it) } ?: unavailable("no price"))
        KeyValue("Bear-case fair value (model)", r.fairBear?.let { rupee(it) } ?: unavailable("not available"))
        KeyValue("Base-case fair value (model)", r.fairBase?.let { rupee(it) } ?: unavailable("not available"), emphasize = true)
        KeyValue("Bull-case fair value (model)", r.fairBull?.let { rupee(it) } ?: unavailable("not available"))
        KeyValue("Estimated range (all models used)", if (r.rangeLow != null && r.rangeHigh != null) "${rupee(r.rangeLow)} – ${rupee(r.rangeHigh)}" else unavailable("no estimate"))
        KeyValue("Premium (+) or discount (−) to base fair value", r.premiumPct?.let { signedPct(it) } ?: unavailable("no fair value"))
        KeyValue("Margin of safety today", mos?.let { signedPct(it) } ?: unavailable("no fair value"))
        KeyValue("Your margin-of-safety preference", pct(r.marginOfSafetyPreference * 100))
        KeyValue("Buy below (base fair value less your margin)", r.buyBelow?.let { rupee(it) } ?: unavailable("no fair value"))

        Heading("Accumulation zones (base fair value)")
        if (r.zones.isEmpty()) Text("Zones need a base-case fair value.", style = MaterialTheme.typography.bodySmall)
        else {
            var lower: Double? = null
            r.zones.forEach { z ->
                val range = when {
                    lower == null && z.upTo != null -> "up to ${rupee(z.upTo)}"
                    z.upTo == null -> "above ${rupee(lower ?: 0.0)}"
                    else -> "${rupee(lower!!)} – ${rupee(z.upTo)}"
                }
                val current = z.zone == r.currentZone
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text((if (current) "▶ " else "   ") + z.zone.label, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lower = z.upTo ?: lower
            }
        }

        Heading("Models")
        r.models.forEach { m ->
            val value = m.base
            if (value != null) {
                KeyValue(m.model.label, rupee(value) + (if (m.bear != null && m.bull != null) " (bear ${rupee(m.bear)} · bull ${rupee(m.bull)})" else ""))
                if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                KeyValue(m.model.label, unavailable(m.reason.ifBlank { "not available" }))
            }
        }

        r.dcf?.let { d -> DcfTable(r, d) }

        Heading("Sensitivity of base fair value")
        if (r.sensitivity.isEmpty()) Text("Needs the DCF.", style = MaterialTheme.typography.bodySmall)
        r.sensitivity.forEach { s ->
            val lo = s.fairLow; val hi = s.fairHigh
            KeyValue(s.driver, if (lo != null && hi != null) "${rupee(lo)} – ${rupee(hi)}" else unavailable("not computable"))
            Text("${s.lowInput} / ${s.highInput} on the base case", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Heading("Growth the price implies")
        KeyValue("Implied starting revenue growth (reverse DCF)", r.impliedGrowth?.let { pct(it * 100) } ?: unavailable("outside plausible range"))
        KeyValue("Base-case starting growth", r.baseGrowth?.let { pct(it * 100) } ?: unavailable("no growth history"))

        Heading("Industry comparison")
        when (val p = r.peerComparison) {
            is DataPoint.Unavailable -> Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DataPoint.Available -> p.value.forEach { row ->
                KeyValue(row.metric, "${row.company?.let { Fmt.num(it, 1) + "×" } ?: "n/a"} vs peer median ${row.peerMedian?.let { Fmt.num(it, 1) + "×" } ?: "n/a"}")
                Text("${row.peerCount} peers with data", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Heading("Assumptions (each one is documented)")
        r.assumptions.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${a.name}: ${a.value}", style = MaterialTheme.typography.bodyMedium)
                    Text(a.why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(a.source.label, style = MaterialTheme.typography.labelSmall,
                    color = if (a.source == AssumptionSource.ASSUMED) mc.neutral else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Heading("What this means")
        Text(r.interpretation.valuation, style = MaterialTheme.typography.bodyMedium)
        Text(r.interpretation.growthPricedIn, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(r.interpretation.fundamentals, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text("What could make this wrong", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        r.interpretation.couldBeWrong.forEach { Bullet(it) }
    }
}

@Composable
private fun DcfTable(r: ValuationReport, d: DcfCase) {
    Heading("Discounted cash flow (base case, ₹ crore)")
    KeyValue("Discount rate (WACC)", pct(d.wacc * 100))
    KeyValue("Terminal growth", pct(d.terminalGrowth * 100))
    d.years.forEach { y ->
        KeyValue("Year ${y.year}: growth ${pct(y.growth * 100)}", "FCF ${crore(y.fcf)} · PV ${crore(y.presentValue)}")
    }
    KeyValue("Terminal value (undiscounted)", crore(d.terminalValue))
    KeyValue("Present value of terminal value", crore(d.presentTerminal) + " (${pct(d.terminalShare * 100)} of EV)")
    KeyValue("Enterprise value", crore(d.enterpriseValue))
    KeyValue("Less net debt", crore(d.netDebt))
    KeyValue("Equity value", crore(d.equityValue))
    KeyValue("Intrinsic value per share", rupee(d.perShare), emphasize = true)
    Text("Revenue, margin, capex and depreciation are as in the assumptions list. Growth fades linearly to terminal growth.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun unavailable(why: String) = "Unavailable ($why)"
private fun rupee(v: Double) = "₹" + Fmt.num(v, 2)
private fun crore(v: Double) = "₹" + Fmt.num(v, 0) + " Cr"
private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v)
private fun signedPct(v: Double) = String.format(Locale.US, "%+.1f%%", v)

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
    }
}
