package com.tradelens.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradelens.patterns.*
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section
import com.tradelens.ui.theme.LocalMarketColors
import com.tradelens.ui.theme.Num
import java.util.Locale

/** Header of the chart-pattern list: counts and filters. */
@Composable
internal fun PatternsHeader(pr: PatternReport, s: TechnicalViewModel.State, vm: TechnicalViewModel) {
    val live = pr.patterns.filter { !it.closed && it.type.kind != PatternKind.CANDLESTICK }
    Section("Chart patterns · ${pr.timeframe.label}") {
        Text(
            "${live.count { it.status == PatternStatus.FORMING }} forming · ${live.count { it.status == PatternStatus.CONFIRMED }} confirmed and still tracked · " +
                "${pr.patterns.size} found in ${pr.candleCount} bars" + (pr.higherTimeframe?.let { " · trend checked on ${it.label}" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternFilter.entries.forEach { f -> FilterChip(selected = s.patternFilter == f, onClick = { vm.setPatternFilter(f) }, label = { Text(f.label) }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternKind.entries.forEach { k -> FilterChip(selected = k in s.patternKinds, onClick = { vm.togglePatternKind(k) }, label = { Text(k.label) }) }
        }
        Text(
            "Found by fixed geometric rules on closed bars only: a swing point counts once the bars after it confirm it, and a breakout needs a candle " +
                "CLOSE beyond the level. Many patterns fail; use them alongside the indicators, not alone.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun StatusPill(st: PatternStatus) {
    val mc = LocalMarketColors.current
    val c = when (st) {
        PatternStatus.FORMING -> MaterialTheme.colorScheme.primary
        PatternStatus.CONFIRMED -> mc.gain
        PatternStatus.FAILED -> mc.loss
        PatternStatus.INVALIDATED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = c.copy(alpha = 0.12f), contentColor = c, shape = RoundedCornerShape(50)) {
        Text(st.label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** One detected pattern. Collapsed: name, direction, status, confidence. Expanded: every level, the plan, the score and the timeline. */
@Composable
internal fun PatternCard(p: DetectedPattern, expanded: Boolean, onToggle: () -> Unit) {
    val dirColor = biasColor(p.direction)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onToggle).padding(14.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${p.type.kind.label} · ${p.timeframe.label} · detected ${Fmt.date(p.detectedAt)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${p.confidence}", style = MaterialTheme.typography.titleMedium.merge(Num), fontWeight = FontWeight.Bold, color = dirColor)
                Text("confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BiasPill(p.direction)
            StatusPill(p.status)
            Text(
                when (p.status) {
                    PatternStatus.FORMING -> "Breakout at ${Fmt.num(p.breakoutLevel)}"
                    PatternStatus.CONFIRMED -> if (p.targetsHit > 0) "${p.targetsHit}/${p.targets.size} targets hit" else "Broke ${Fmt.num(p.breakoutLevel)}"
                    else -> "on ${Fmt.date(p.events.last().knownAt)}"
                },
                style = MaterialTheme.typography.labelMedium.merge(Num), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!expanded) {
            Text("Tap to draw it on the chart and see the plan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            return@Column
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(p.explanation, style = MaterialTheme.typography.bodySmall)

        Heading("Breakout status")
        KeyValue("Status", p.status.label)
        KeyValue(if (p.status == PatternStatus.FORMING) "Breakout level (now)" else "Breakout level", Fmt.num(p.breakoutLevel))
        p.events.firstOrNull { it.type == EventType.CONFIRMED }?.let { KeyValue("Confirmed by the close of", Fmt.date(it.knownAt)) }
        KeyValue("Invalidation level", Fmt.num(p.invalidationLevel))
        KeyValue("Volume", when (p.volume.confirmed) { true -> "Confirmed"; false -> "Not confirmed"; null -> if (p.volume.available) "Pending" else "n/a" } +
            (p.volume.breakoutRelativeVolume?.let { " (${String.format(Locale.US, "%.1f", it)}× avg)" } ?: ""))
        KeyValue("Higher-timeframe trend", p.higherTimeframe?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "not enough history")

        Heading(if (p.status == PatternStatus.FORMING) "Potential plan (only if it confirms)" else "Plan at confirmation")
        KeyValue("Entry", Fmt.num(p.entry))
        KeyValue("Stop-loss", Fmt.num(p.stopLoss))
        p.targets.forEach { KeyValue(it.label, Fmt.num(it.price)) }

        Heading("Key levels")
        p.keyLevels.forEach { KeyValue(it.label, Fmt.num(it.price)) }
        if (p.type.kind != PatternKind.CANDLESTICK) p.anchors.filter { it.label.isNotBlank() }.forEach { KeyValue(it.label, "${Fmt.num(it.price)} · ${Fmt.date(it.time)}") }

        Heading("Confidence ${p.confidence}/100")
        val b = p.breakdown
        KeyValue("Geometry fit", "${b.geometry}/35")
        KeyValue("Size vs ATR", "${b.size}/10")
        KeyValue("Close-confirmed breakout", "${b.breakout}/20")
        KeyValue("Volume", "${b.volume}/20")
        KeyValue("Higher-timeframe agreement", "${b.higherTimeframe}/15")
        if (p.status == PatternStatus.FAILED || p.status == PatternStatus.INVALIDATED)
            Text("Capped at 30 because the pattern did not work out.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Heading("Timeline")
        p.events.forEach { ev ->
            Text("${Fmt.date(ev.knownAt)} · ${ev.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}: ${ev.detail}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
