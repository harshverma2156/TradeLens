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
import com.tradelens.fundamentals.*
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section
import com.tradelens.ui.theme.LocalMarketColors
import java.util.Locale

/**
 * Fundamental report on the stock page: quality score, strongest and weakest measures, red flags, industry position,
 * the reported statements, ratios, shareholding and governance. Anything that is missing says why, and is never zero.
 */
@Composable
internal fun FundamentalSection(report: FundamentalReport) {
    val mc = LocalMarketColors.current
    val a = report.analysis
    val snap = (report.snapshot as? DataPoint.Available)?.value
    if (a == null || snap == null) {
        val why = (report.snapshot as? DataPoint.Unavailable)?.reason ?: "No fundamental data."
        Section("Fundamentals") { KeyValue("Status", "Unavailable", valueColor = MaterialTheme.colorScheme.onSurfaceVariant); Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val score = a.quality.score
    val colour = when {
        score == null -> MaterialTheme.colorScheme.onSurfaceVariant
        score >= 55 -> mc.gain
        score >= 35 -> mc.neutral
        else -> mc.loss
    }
    Section("Fundamentals", trailing = {
        Surface(color = colour.copy(alpha = 0.14f), contentColor = colour, shape = RoundedCornerShape(50)) {
            Text(a.quality.band ?: "Not scored", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }) {
        Text(score?.let { "Quality score $it / 100" } ?: "Quality score: not given", style = MaterialTheme.typography.titleSmall)
        Text(a.summary, style = MaterialTheme.typography.bodyMedium)
        Text(a.quality.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        a.staleNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = mc.neutral) }
        Text(a.caveat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SourceLine(report, snap)

        Heading("Sector and profile")
        Text("${a.sector.label}${if (a.sectorKnown) "" else " (sector not supplied)"}", style = MaterialTheme.typography.bodyMedium)
        Text(a.profileRationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val na = a.readings.filter { !it.applicable }
        if (na.isNotEmpty()) Text("Not used for this sector: " + na.joinToString { it.metric.label }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Heading("Area scores")
        a.pillars.forEach { p ->
            KeyValue(p.pillar.label, p.score?.let { "${it.toInt()} / 100 · ${p.scored} of ${p.applicable} measures" } ?: unavailable("not enough reported data"))
        }

        Heading("Strongest indicators")
        if (a.strongest.isEmpty()) Bullet("None at or above 65 / 100 on the reported figures.")
        a.strongest.forEach { Bullet("${it.reading.metric.label}: ${fmtMetric(it.reading)} (${it.score.toInt()} / 100)", mc.gain) }
        Heading("Weakest indicators")
        if (a.weakest.isEmpty()) Bullet("None below 45 / 100 on the reported figures.")
        a.weakest.forEach { Bullet("${it.reading.metric.label}: ${fmtMetric(it.reading)} (${it.score.toInt()} / 100)", mc.loss) }

        Heading("Improving (vs prior year)")
        if (a.improving.isEmpty()) Bullet("No measure moved by more than 5% in the right direction.")
        a.improving.take(5).forEach { Bullet("${it.metric.label}: ${fmtNum(it.metric, it.previous)} → ${fmtNum(it.metric, it.current)}", mc.gain) }
        Heading("Deteriorating (vs prior year)")
        if (a.deteriorating.isEmpty()) Bullet("No measure moved by more than 5% in the wrong direction.")
        a.deteriorating.take(5).forEach { Bullet("${it.metric.label}: ${fmtNum(it.metric, it.previous)} → ${fmtNum(it.metric, it.current)}", mc.loss) }

        Heading("Red flags")
        if (a.redFlags.isEmpty()) {
            Text("None raised on the ${a.governanceChecks} of 11 checks that could be run. A missing check is not a clean result.", style = MaterialTheme.typography.bodySmall)
        }
        a.redFlags.forEach { f ->
            val c = when (f.severity) { Severity.HIGH -> mc.loss; Severity.MEDIUM -> mc.neutral; Severity.LOW -> MaterialTheme.colorScheme.onSurfaceVariant }
            Bullet("${f.title} (${f.severity.label.lowercase(Locale.US)}): ${f.evidence}", c)
        }

        Heading("Industry position")
        when (val ind = a.industry) {
            is DataPoint.Unavailable -> Text(ind.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DataPoint.Available -> ind.value.forEach { p ->
                KeyValue(p.metric.label, "${fmtNum(p.metric, p.value)} · median ${fmtNum(p.metric, p.peerMedian)}")
                Text("Better than ${p.betterThanPct}% of ${p.peerCount} peers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Heading("Reported statements (latest year, ${snap.basis.label.lowercase(Locale.US)}, ₹ crore)")
        val y = snap.annual.lastOrNull()
        if (y == null) Text("No annual statements reported.", style = MaterialTheme.typography.bodySmall)
        else {
            Text("Year ending ${Fmt.date(y.periodEnd)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeyValue("Revenue", crore(y.revenue)); KeyValue("EBITDA", crore(y.ebitda)); KeyValue("Net profit", crore(y.netProfit))
            KeyValue("EPS (₹)", y.eps?.let { Fmt.num(it) } ?: unavailable("not reported"))
            KeyValue("Total debt", crore(y.totalDebt)); KeyValue("Cash and equivalents", crore(y.cash)); KeyValue("Equity", crore(y.equity))
            KeyValue("Operating cash flow", crore(y.operatingCashFlow)); KeyValue("Investing cash flow", crore(y.investingCashFlow))
            KeyValue("Financing cash flow", crore(y.financingCashFlow))
            KeyValue("Free cash flow (operating − capex)", if (y.operatingCashFlow != null && y.capex != null) crore(y.operatingCashFlow - y.capex) else unavailable("needs capex"))
            KeyValue("Working-capital change", crore(y.workingCapitalChange))
        }

        Heading("Ratios and valuation")
        a.readings.filter { it.applicable }.groupBy { it.metric.pillar }.forEach { (pillar, list) ->
            Text(pillar.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            list.forEach { r -> KeyValue(r.metric.label, r.value?.let { fmtMetric(r) } ?: unavailable(r.reason.ifBlank { "not reported" })) }
        }

        Heading("Shareholding")
        when (val sh = snap.shareholding) {
            is DataPoint.Unavailable -> Text(unavailable(sh.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DataPoint.Available -> {
                val qs = sh.value.sortedBy { it.quarterEnd }
                val latest = qs.lastOrNull()
                if (latest == null) Text("No quarters reported.", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Quarter ending ${Fmt.date(latest.quarterEnd)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    KeyValue("Promoter", pct(latest.promoterPct)); KeyValue("Promoter pledged (of promoter holding)", pct(latest.promoterPledgedPct))
                    KeyValue("FII / FPI", pct(latest.fiiPct)); KeyValue("DII", pct(latest.diiPct)); KeyValue("Public", pct(latest.publicPct))
                    val base = qs.getOrNull(qs.size - 5)
                    val change = if (base?.promoterPct != null && latest.promoterPct != null) latest.promoterPct - base.promoterPct else null
                    KeyValue("Promoter change over four quarters", change?.let { String.format(Locale.US, "%+.2f pts", it) } ?: unavailable("needs five quarters"))
                }
            }
        }

        Heading("Governance")
        when (val g = snap.governance) {
            is DataPoint.Unavailable -> Text(unavailable(g.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DataPoint.Available -> {
                val r = g.value
                KeyValue("Auditor qualifications", when (val q = r.auditorQualifications) {
                    null -> unavailable("not disclosed"); emptyList<String>() -> "None reported"; else -> q.joinToString("; ")
                })
                KeyValue("Related-party transactions (₹ crore)", crore(r.relatedPartyValue))
                KeyValue("Contingent liabilities (₹ crore)", crore(r.contingentLiabilities))
                r.managementChanges?.let { list -> if (list.isEmpty()) KeyValue("Management changes", "None reported") else list.forEach { KeyValue("${Fmt.date(it.date)} · ${it.role}", it.change) } }
                    ?: KeyValue("Management changes", unavailable("not disclosed"))
                r.regulatoryActions?.let { list -> if (list.isEmpty()) KeyValue("Regulatory actions", "None reported") else list.forEach { Bullet("${Fmt.date(it.date)} · ${it.authority}: ${it.summary}") } }
                    ?: KeyValue("Regulatory actions", unavailable("not disclosed"))
            }
        }

        Heading("Data accuracy")
        Text("Reporting basis: ${snap.basis.label}. Currency: ${snap.currency}; amounts in ₹ crore, per-share in ₹.", style = MaterialTheme.typography.bodySmall)
        Text("Annual periods: ${snap.annual.size}" + (snap.annual.firstOrNull()?.let { f -> snap.annual.lastOrNull()?.let { l -> " (${Fmt.date(f.periodEnd)} – ${Fmt.date(l.periodEnd)})" } } ?: ""),
            style = MaterialTheme.typography.bodySmall)
        Text("Quarterly periods: ${snap.quarterly.size}" + (snap.quarterly.lastOrNull()?.let { " · latest ${Fmt.date(it.periodEnd)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
        if (a.missingFields.isEmpty()) Text("No missing fields.", style = MaterialTheme.typography.bodySmall)
        else {
            Text("Missing (shown as unavailable, never as zero):", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            a.missingFields.take(12).forEach { Bullet(it) }
            if (a.missingFields.size > 12) Text("…and ${a.missingFields.size - 12} more.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SourceLine(report: FundamentalReport, snap: FundamentalSnapshot) {
    val p = report.provenance
    val parts = buildList {
        add("Source: ${p?.provider ?: "unknown"}")
        add("Basis: ${snap.basis.label}")
        add("Currency: ${snap.currency} (₹ crore)")
        snap.annual.lastOrNull()?.let { add("Latest year ending ${Fmt.date(it.periodEnd)}") }
        (p?.fetchedAt ?: snap.updatedAt)?.let { add("Updated ${Fmt.dateTime(it)}") }
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun unavailable(why: String) = "Unavailable ($why)"

private fun pct(v: Double?) = v?.let { Fmt.num(it, 2) + "%" } ?: unavailable("not reported")

private fun crore(v: Double?) = v?.let { "₹" + Fmt.num(it, 0) + " Cr" } ?: unavailable("not reported")

private fun fmtNum(m: Metric, v: Double): String = when (m.unit) {
    "%" -> Fmt.num(v, 1) + "%"
    "x" -> Fmt.num(v, 2) + "×"
    else -> Fmt.num(v)
}

private fun fmtMetric(r: Reading): String = r.value?.let { fmtNum(r.metric, it) } ?: "n/a"

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
    }
}
