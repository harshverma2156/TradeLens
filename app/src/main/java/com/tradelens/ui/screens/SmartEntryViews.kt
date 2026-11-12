package com.tradelens.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradelens.data.model.Quote
import com.tradelens.trade.*
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import java.util.Locale

@Composable
private fun statusColor(s: EntryStatus): Color {
    val mc = LocalMarketColors.current
    return when (s) {
        EntryStatus.BUY_NOW -> mc.gain
        EntryStatus.EXIT, EntryStatus.AVOID -> mc.loss
        EntryStatus.PARTIAL_PROFIT_BOOKING -> Color(0xFFB57A06)
        EntryStatus.HOLD -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
}

private fun p(v: Double) = Fmt.num(v)
private fun pct(v: Double) = (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f%%", v)
private fun r1(v: Double) = String.format(Locale.US, "%.1f", v)

/** Smart entry, exit and price-target engine (Phase 5): status, three strategies, exits, risk and the reasons. */
@Composable
internal fun SmartEntrySection(rec: Recommendation, q: Quote) {
    var selected by remember(rec.primary) { mutableStateOf(rec.primary ?: StrategyType.AGGRESSIVE) }
    val col = statusColor(rec.status)
    Section("Smart entry & exit", trailing = {
        Surface(color = col.copy(alpha = 0.14f), contentColor = col, shape = RoundedCornerShape(50)) {
            Text(rec.status.label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }) {
        Text(rec.status.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = col)
        Text(rec.headline, style = MaterialTheme.typography.titleSmall)
        rec.stability?.let {
            Text(it.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        Text("Price ${p(rec.price)} · ATR ${p(rec.atr)} · daily bars to ${rec.dataAsOf?.let { Fmt.date(it) } ?: "—"} · recalculated ${Fmt.dateTime(rec.computedAt)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

        if (rec.blockers.isNotEmpty()) {
            Heading("Why there is no buy signal")
            rec.blockers.forEach { b -> Bullet("${b.type.label}: ${b.message}", LocalMarketColors.current.loss) }
        }

        rec.position?.let { pos ->
            Heading("Your position")
            KeyValue("Profit / loss", pct(pos.pnlPct), valueColor = if (pos.pnlPct >= 0) LocalMarketColors.current.gain else LocalMarketColors.current.loss)
            KeyValue("Protective stop (daily close)", p(pos.protectiveStop))
            pos.trailingStop?.let { KeyValue("Trailing stop (after T1)", p(it)) }
            pos.nextTarget?.let { KeyValue("Next target", "${p(it.price)} · ${it.basis.substringBefore(':')}") }
            Text(pos.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        if (rec.strategies.isNotEmpty()) {
            Heading("Entry strategies")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rec.strategies.forEach { s ->
                    FilterChip(selected = selected == s.type, onClick = { selected = s.type },
                        label = { Text(s.type.label + if (s.type == rec.primary) " ★" else "") })
                }
            }
            rec.strategies.firstOrNull { it.type == selected }?.let { StrategyDetail(it, q) }
        }

        if (rec.events.isNotEmpty()) {
            Heading("Events")
            rec.events.forEach { e -> Bullet(e.title + if (e.resolved) " (settled)" else "", if (e.major && !e.resolved) LocalMarketColors.current.loss else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(
            "Computed by fixed rules from closed daily bars, indicators and chart patterns; the live price only places it relative to the levels. " +
                "Not investment advice. Paper-trade first.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun StrategyDetail(s: StrategyPlan, q: Quote) {
    val mc = LocalMarketColors.current
    Spacer(Modifier.height(6.dp))
    Text(s.type.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(s.status.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = statusColor(s.status), modifier = Modifier.weight(1f))
        Text(if (s.viable) "score ${s.score}" else "not valid", style = MaterialTheme.typography.labelMedium.merge(Num), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(s.trigger, style = MaterialTheme.typography.bodySmall)
    s.rejections.forEach { Bullet(it, mc.loss) }

    Heading("Entry")
    KeyValue("Entry", p(s.entry), emphasize = true)
    if (s.entryHigh > s.entryLow) KeyValue("Entry zone", "${p(s.entryLow)} – ${p(s.entryHigh)}")

    val x = s.exit
    val rr = s.risk
    if (x != null) {
        Heading("Exit plan")
        KeyValue("Stop-loss", p(x.stopLoss), valueColor = mc.loss, emphasize = true)
        Text(x.stopBasis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        x.targets.forEach { t ->
            KeyValue("${t.label}  (${r1(t.rewardRisk)}R, ${pct(t.pctFromEntry)})", p(t.price), valueColor = mc.gain)
            Text(t.basis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        KeyValue("Trailing stop", x.trailing.current?.let { "${p(it)} now" } ?: "—")
        Text("${x.trailing.method}. ${x.trailing.rule}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Heading("Partial profit booking")
        x.partials.forEach { Bullet("${it.label} (${p(it.price)}): ${it.action}") }
        Heading("Invalidated setup")
        Bullet("Before entry: below ${p(x.invalidation)}, ${x.invalidationRule}", mc.loss)
        Heading("Exit conditions")
        x.exitConditions.forEach { Bullet(it) }
    }

    if (rr != null) {
        Heading("Risk / reward")
        KeyValue("Risk per share", "${p(rr.riskPerShare)}  (${String.format(Locale.US, "%.2f", rr.stopPct)}% to stop)")
        rr.rewards.forEachIndexed { k, rw -> KeyValue("Reward to T${k + 1}", "${p(rw)}  (${r1(rr.ratios[k])} : 1, ${pct(rr.targetPcts[k])})") }
        KeyValue("Quantity", "${rr.quantity} shares", emphasize = true)
        KeyValue("Capital required", Fmt.price(rr.capitalRequired, q.currency))
        KeyValue("Capital exposure", String.format(Locale.US, "%.1f%% of capital", rr.exposurePct))
        KeyValue("Max loss at stop", Fmt.price(rr.maxLoss, q.currency), valueColor = mc.loss)
        Text("Sized by your ${rr.limitedBy}. Change capital, risk % or max position in Settings.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val why = s.rationale
    Heading("Why this entry")
    Text(why.why, style = MaterialTheme.typography.bodySmall)
    if (why.supporting.isNotEmpty()) {
        Heading("What supports it")
        why.supporting.take(8).forEach { Bullet(it, mc.gain) }
    }
    if (why.levels.isNotEmpty()) {
        Heading("Price levels that matter")
        why.levels.forEach { (k, v) -> KeyValue(k, p(v)) }
    }
    if (why.invalidation.isNotEmpty()) {
        Heading("What would invalidate it")
        why.invalidation.forEach { Bullet(it, mc.loss) }
    }
    if (why.risks.isNotEmpty()) {
        Heading("Risks that remain")
        why.risks.distinct().take(8).forEach { Bullet(it) }
    }
}

/** Long-term view: deliberately separate from the short-term plan, with no stop-loss and no buy signal. */
@Composable
internal fun LongTermSection(lt: LongTermView) {
    Section("Long-term view (separate from the trade plan)") {
        Text(lt.trend, style = MaterialTheme.typography.titleSmall)
        lt.context.forEach { Bullet(it) }
        Spacer(Modifier.height(6.dp))
        Text(lt.valuationNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.padding(top = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodySmall, color = color)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
