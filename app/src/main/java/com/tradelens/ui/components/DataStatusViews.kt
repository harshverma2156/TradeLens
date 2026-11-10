package com.tradelens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradelens.marketdata.DataIssue
import com.tradelens.marketdata.DataStatus
import com.tradelens.marketdata.Freshness
import com.tradelens.marketdata.ProviderInfo
import com.tradelens.marketdata.Severity
import com.tradelens.ui.theme.LocalMarketColors

val DemoColor = Color(0xFF8E44AD)

@Composable
fun freshnessColor(f: Freshness): Color {
    val mc = LocalMarketColors.current
    return when (f) {
        Freshness.LIVE -> mc.gain
        Freshness.DELAYED -> mc.neutral
        Freshness.MARKET_CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
        Freshness.STALE, Freshness.OFFLINE -> mc.loss
        Freshness.DEMO -> DemoColor
    }
}

@Composable
fun FreshnessBadge(f: Freshness, modifier: Modifier = Modifier) {
    val c = freshnessColor(f)
    Surface(color = c.copy(alpha = 0.15f), contentColor = c, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(f.label.uppercase(), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** One line: [BADGE] Updated 15:28 IST · Yahoo Finance, plus an error with Retry when the last refresh failed. */
@Composable
fun DataStatusBar(status: DataStatus?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    if (status == null) return
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FreshnessBadge(status.freshness)
            Spacer(Modifier.width(8.dp))
            val time = status.asOf?.let { "As of ${Fmt.dateTime(it)} IST" } ?: "Time unknown"
            Text("$time · ${status.provider}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
        }
        if (status.freshness == Freshness.STALE && status.error == null) {
            Text("Newer data was expected by now. The provider may be lagging, or the market may have been closed (holiday not yet in the calendar).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        status.error?.let { err ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    "Couldn't refresh: $err" + (status.fetchedAt?.let { " Showing data saved ${Fmt.dateTime(it)}." } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = LocalMarketColors.current.loss, modifier = Modifier.weight(1f),
                )
                if (onRetry != null) TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Retry") }
            }
        }
    }
}

/** Full-width banner shown on every screen while demo data is active. */
@Composable
fun DemoBanner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(DemoColor).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("DEMO DATA: synthetic prices, not real market data. Turn off in Settings.",
            style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

/** Provider, availability, data checks and corporate actions for the series behind the analysis. */
@Composable
fun DataDetailsSection(info: ProviderInfo, status: DataStatus?, actions: List<String>) {
    Section("Data source & quality", trailing = { status?.let { FreshnessBadge(it.freshness) } }) {
        KeyValue("Provider", info.name)
        KeyValue("Quotes", when (info.mode) {
            com.tradelens.marketdata.DataMode.REALTIME -> "Real-time"
            com.tradelens.marketdata.DataMode.DELAYED -> "Delayed"
            com.tradelens.marketdata.DataMode.END_OF_DAY -> "End of day"
            com.tradelens.marketdata.DataMode.DEMO -> "Synthetic (demo)"
        })
        status?.fetchedAt?.let { KeyValue("Last synced", "${Fmt.dateTime(it)} IST") }
        status?.asOf?.let { KeyValue("Latest bar", "${Fmt.dateTime(it)} IST") }
        val q = status?.quality
        if (q != null) {
            KeyValue("Data checks", "${q.score}/100 · ${q.kept} bars", if (q.score >= 90) LocalMarketColors.current.gain else if (q.score >= 70) LocalMarketColors.current.neutral else LocalMarketColors.current.loss)
            q.issues.forEach { IssueRow(it) }
        }
        if (actions.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Text("Corporate actions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }
        }
        val notes = (status?.availability.orEmpty() + info.note).filter { it.isNotBlank() }.distinct()
        if (notes.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            notes.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun IssueRow(i: DataIssue) {
    val mc = LocalMarketColors.current
    val c = when (i.severity) { Severity.ERROR -> mc.loss; Severity.WARNING -> mc.neutral; Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•  ", color = c, style = MaterialTheme.typography.bodySmall)
        Text("${i.type.label}: ${i.message}", style = MaterialTheme.typography.bodySmall)
    }
}
