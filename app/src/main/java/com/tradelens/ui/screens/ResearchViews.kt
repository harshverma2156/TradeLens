package com.tradelens.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tradelens.flow.DataPoint
import com.tradelens.research.*
import com.tradelens.ui.components.Fmt
import com.tradelens.ui.components.KeyValue
import com.tradelens.ui.components.Section
import com.tradelens.ui.theme.LocalMarketColors

/**
 * AI research on the stock page: news feed, sentiment, exchange announcements, growth outlook, bull and bear thesis.
 * Every line shows what kind of claim it is and where it came from. Missing items say why they are missing.
 */
@Composable
internal fun ResearchSection(report: ResearchReport?, loading: Boolean, onRefresh: () -> Unit) {
    val mc = LocalMarketColors.current
    val uri = LocalUriHandler.current
    Section("News & AI research", trailing = {
        TextButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "Updating…" else "Refresh") }
    }) {
        if (report == null) {
            Text(if (loading) "Fetching the latest news…" else "No research yet. Tap Refresh.", style = MaterialTheme.typography.bodySmall)
            return@Section
        }
        Text(report.writerNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ---- sentiment overview
        Heading("Sentiment overview")
        val o = report.overview
        if (o == null) Text("No news items found for this company, so there is no sentiment to show.", style = MaterialTheme.typography.bodySmall)
        else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Positive", o.positive, mc.gain); Stat("Negative", o.negative, mc.loss)
                Stat("Neutral", o.neutral, MaterialTheme.colorScheme.onSurfaceVariant); Stat("Uncertain", o.uncertain, mc.neutral)
            }
            KeyValue("Short-term price impact (days–weeks)", o.shortTermBias.label)
            KeyValue("Long-term business impact", o.longTermBias.label)
            Text("Based on ${o.total} unique items. Repeats of one story are counted once. Sentiment is read from words in headlines, not from full articles.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- latest news with explanations
        Heading("Latest news")
        when (val news = report.news) {
            is DataPoint.Unavailable -> Text(news.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DataPoint.Available -> {
                val unique = news.value.filter { it.duplicateOf == null }.take(10)
                if (unique.isEmpty()) Text("No relevant items in the feed.", style = MaterialTheme.typography.bodySmall)
                unique.forEach { a -> ArticleCard(a, uri::openUri, mc) }
                val repeats = news.value.count { it.duplicateOf != null }
                if (repeats > 0) Text("$repeats repeated item(s) hidden.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Heading("Company announcements (exchange filings)")
        when (val ex = report.exchangeAnnouncements) {
            is DataPoint.Unavailable -> Text(ex.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is DataPoint.Available -> if (ex.value.isEmpty()) Text("No announcements found.", style = MaterialTheme.typography.bodySmall)
                else ex.value.take(10).forEach { a -> ArticleCard(a, uri::openUri, mc) }
        }

        // ---- growth outlook
        Heading("Growth outlook")
        report.growth.forEach { g ->
            Text(g.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            val c = g.claim
            if (c != null) ClaimLine(c, uri::openUri)
            else Text("Unavailable: ${g.unavailableReason ?: "not reported"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Heading("Earnings outlook")
        report.earningsOutlook.forEach { ClaimLine(it, uri::openUri) }

        // ---- research report
        Heading("Positive developments")
        ClaimList(report.positives, "No positive items in the feed.", uri::openUri)
        Heading("Negative developments")
        ClaimList(report.negatives, "No negative items in the feed.", uri::openUri)
        Heading("Opportunities")
        ClaimList(report.opportunities, "No opportunity is supported by the retrieved items.", uri::openUri)
        Heading("Risks")
        ClaimList(report.risks, "No risk is supported by the retrieved items.", uri::openUri)

        // ---- bull and bear
        Heading("Bull case")
        ClaimList(report.bull, "No supported bull point.", uri::openUri, mc.gain)
        Heading("Bear case")
        ClaimList(report.bear, "No supported bear point.", uri::openUri, mc.loss)

        if (report.unavailable.isNotEmpty()) {
            Heading("Not available")
            report.unavailable.forEach { Bullet(it) }
        }
    }
}

@Composable
private fun Stat(label: String, n: Int, color: Color) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(n.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ArticleCard(a: Article, open: (String) -> Unit, mc: com.tradelens.ui.theme.MarketColors) {
    val sentColour = when (a.sentiment) {
        Sentiment.POSITIVE -> mc.gain; Sentiment.NEGATIVE -> mc.loss; else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(a.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { open(a.url) })
        Text("${a.publisher} · ${a.credibility.label}" + (a.published?.let { " · ${Fmt.date(it)}" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Chip(a.category.label, MaterialTheme.colorScheme.onSurfaceVariant)
            Chip(a.sentiment.label, sentColour)
            Chip(a.horizon.label, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(a.significance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = RoundedCornerShape(50), modifier = Modifier.padding(end = 6.dp, top = 2.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ClaimList(list: List<Claim>, empty: String, open: (String) -> Unit, color: Color? = null) {
    if (list.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    list.forEach { c -> Row(Modifier.padding(vertical = 2.dp)) { Text("•  ", color = color ?: MaterialTheme.colorScheme.onSurface); ClaimLine(c, open) } }
}

@Composable
private fun ClaimLine(c: Claim, open: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = if (c.sourceUrl != null) Modifier.clickable { open(c.sourceUrl) } else Modifier)
        val meta = listOfNotNull(c.kind.label, c.sourceName, c.date?.let { Fmt.date(it) }).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun unavailable(why: String) = "Unavailable ($why)"

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
