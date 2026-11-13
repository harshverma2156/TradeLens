package com.tradelens.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradelens.recommend.*
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section
import com.tradelens.ui.theme.LocalMarketColors
import java.util.Locale

/**
 * AI decision dashboard. One view per horizon. Every score is shown on its own, unavailable components say why, and
 * conflicting evidence is listed next to the supporting evidence. This is an educational summary, not investment advice.
 */
@Composable
internal fun DecisionDashboard(
    decisions: List<HorizonDecision>?,
    horizon: InvestmentHorizon,
    savedNote: String?,
    history: List<RecommendationRecord>,
    onHorizon: (InvestmentHorizon) -> Unit,
    onSave: () -> Unit,
) {
    val mc = LocalMarketColors.current
    Section("AI decision", trailing = {
        Text("model ${ModelConfig.DEFAULT.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }) {
        Text("A summary of the engines in this app, not a guarantee. Read the reasons, and size any position so a wrong call is affordable.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InvestmentHorizon.values().forEach { h ->
                FilterChip(selected = h == horizon, onClick = { onHorizon(h) }, label = { Text("${h.label} · ${h.window}") })
            }
        }
        val d = decisions?.firstOrNull { it.horizon == horizon }
        if (d == null) {
            Text("Computing the decision…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            return@Section
        }
        ActionCard(d, mc)
        ScoreRow(d)
        Heading("How each engine scored")
        d.components.forEach { ComponentRow(it) }

        Heading("Price plan")
        PlanView(d)

        Heading("Evidence for")
        if (d.supporting.isEmpty()) Bullet("No component scored strongly positive.")
        d.supporting.forEach { Bullet(it, mc.gain) }
        Heading("Evidence against (including conflicts)")
        if (d.opposing.isEmpty()) Bullet("No component scored strongly negative.")
        d.opposing.forEach { Bullet(it, mc.loss) }
        if (d.conflicts.isNotEmpty()) {
            Heading("Conflicts between engines")
            d.conflicts.forEach { c ->
                val col = if (c.severity == ConflictSeverity.HIGH) mc.loss else mc.neutral
                Text(c.title + " (${c.severity.label.lowercase(Locale.US)})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = col)
                Text(c.evidence, style = MaterialTheme.typography.bodySmall)
            }
        }

        Heading("Risks")
        d.risks.forEach { Bullet(it) }
        Heading("Conditions to wait for")
        if (d.plan.waitFor.isEmpty()) Text("None: nothing is waiting on a condition for this horizon.", style = MaterialTheme.typography.bodySmall)
        d.plan.waitFor.forEach { Bullet(it) }
        Heading("Conditions to exit")
        d.plan.exitWhen.forEach { Bullet(it) }

        Heading("What this means for you")
        Text(d.explanation, style = MaterialTheme.typography.bodyMedium)
        Text(d.confidenceNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text("Model ${d.modelVersion} · data as of ${d.inputAsOf?.let { Fmt.dateTime(it) } ?: "unknown"} · computed ${Fmt.dateTime(d.computedAt)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = onSave, enabled = d.action != DecisionAction.NO_TRADE) { Text("Save this decision") }
            savedNote?.let { Text("  $it", style = MaterialTheme.typography.labelSmall) }
        }
        if (history.isNotEmpty()) {
            Heading("Saved decisions for this stock")
            history.take(5).forEach { r ->
                KeyValue("${Fmt.dateTime(r.createdAt)} · ${r.horizon.lowercase(Locale.US)}", "${r.action.replace('_', ' ').lowercase(Locale.US)} · model ${r.modelVersion}")
            }
        }
    }
}

@Composable
private fun ActionCard(d: HorizonDecision, mc: com.tradelens.ui.theme.MarketColors) {
    val col = when (d.action) {
        DecisionAction.STRONG_BUY_SETUP, DecisionAction.BUY -> mc.gain
        DecisionAction.SELL, DecisionAction.AVOID -> mc.loss
        DecisionAction.NO_TRADE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> mc.neutral
    }
    Surface(color = col.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(d.action.label.uppercase(Locale.US), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = col)
            Text(d.action.meaning, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ScoreRow(d: HorizonDecision) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Overall", d.overall?.let { "${it.toInt()}/100" } ?: "—")
        Stat("Confidence", d.confidence?.let { "${(it * 100).toInt()}%" } ?: "not calibrated")
        Stat("Risk", d.riskLevel.label)
        Stat("Data", "${(d.coverage * 100).toInt()}%")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ComponentRow(c: ComponentScore) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(c.component.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(c.score?.let { "${it.toInt()}" } ?: "Unavailable", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = if (c.score == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        c.score?.let { LinearProgressIndicator(progress = (it / 100).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
        Text(c.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanView(d: HorizonDecision) {
    val p = d.plan
    KeyValue("Current price", p.currentPrice?.let { "₹" + Fmt.num(it) } ?: "unavailable")
    KeyValue("Preferred entry", p.preferredEntry?.let { band(it.low, it.high) } ?: "unavailable")
    p.preferredEntry?.let { Text(it.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    KeyValue("Alternative entry", p.alternativeEntry?.let { band(it.low, it.high) } ?: "unavailable")
    p.alternativeEntry?.let { Text(it.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    KeyValue("Stop-loss or invalidation", p.stop?.let { "₹" + Fmt.num(it) } ?: "none set")
    Text(p.stopNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    p.targets.forEachIndexed { i, t ->
        KeyValue("Target ${i + 1}" + if (t.label.isNotBlank() && !t.label.startsWith("Target")) " (${t.label})" else "",
            t.price?.let { "₹" + Fmt.num(it) } ?: "unavailable")
    }
    KeyValue("Expected holding period", d.horizon.holding)
    KeyValue("Risk / reward to target 1", p.riskReward?.let { String.format(Locale.US, "%.2f : 1", it) } ?: "not computable")
}

private fun band(lo: Double, hi: Double): String =
    if (lo == hi) "₹" + Fmt.num(lo) else "₹${Fmt.num(lo)} – ₹${Fmt.num(hi)}"

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
