package com.tradelens.research

import com.tradelens.flow.DataPoint
import com.tradelens.fundamentals.FundamentalReport
import com.tradelens.fundamentals.Metric
import com.tradelens.fundamentals.RedFlag
import com.tradelens.valuation.ValuationCalc
import com.tradelens.valuation.ValuationReport
import java.util.Locale
import kotlin.math.abs

/**
 * Builds the research report from retrieved items only. Every bullet is a [Claim] with its kind and source; a missing
 * input becomes an entry in [ResearchReport.unavailable] rather than a guess.
 */
object ResearchService {

    private const val DAY = 86_400_000L

    fun build(
        symbol: String,
        company: String,
        news: DataPoint<List<RawItem>>,
        exchange: DataPoint<List<RawItem>>,
        fundamentals: FundamentalReport?,
        valuation: ValuationReport?,
        now: Long,
    ): ResearchReport {
        val aliases = listOf(company, company.substringBefore(' ')).distinct()
        val newsArticles = (news as? DataPoint.Available)?.value?.let { NewsProcessing.process(it, aliases) }.orEmpty()
        val exchangeArticles = (exchange as? DataPoint.Available)?.value?.let { raw ->
            NewsProcessing.process(raw, listOf(symbol.substringBefore('.'))).map { it.copy(credibility = Credibility.OFFICIAL) }
        }.orEmpty()
        val live = newsArticles.filter { it.duplicateOf == null }
        val unavailable = mutableListOf<String>()
        (news as? DataPoint.Unavailable)?.let { unavailable += "Company news: ${it.reason}" }
        (exchange as? DataPoint.Unavailable)?.let { unavailable += "Exchange announcements: ${it.reason}" }
        if (fundamentals?.analysis == null) unavailable += "Fundamentals: not available, so growth and business-quality items are missing."
        if (valuation == null) unavailable += "Valuation: not available, so the price-based bear and bull points are missing."

        val overview = if (live.isEmpty()) null else overview(live)

        val positives = live.filter { it.sentiment == Sentiment.POSITIVE }.map { articleClaim(it) }.take(8)
        val negatives = live.filter { it.sentiment == Sentiment.NEGATIVE }.map { articleClaim(it) }.take(8)
        val longPositives = live.filter { it.sentiment == Sentiment.POSITIVE && it.horizon != Horizon.SHORT_TERM }
        val longNegatives = live.filter { it.sentiment == Sentiment.NEGATIVE && it.horizon != Horizon.SHORT_TERM }

        val growth = growthItems(fundamentals, live, now)
        val redFlags = fundamentals?.analysis?.redFlags.orEmpty()

        val opportunities = (longPositives.map { articleClaim(it) } + positiveGrowth(growth)).take(8)
        val risks = (negatives + redFlags.map { flagClaim(it) }).take(10)

        val strongest = fundamentals?.analysis?.strongest.orEmpty()
        val weakest = fundamentals?.analysis?.weakest.orEmpty()
        val bull = buildList {
            addAll(longPositives.map { articleClaim(it) }.take(4))
            strongest.take(3).forEach { add(Claim("${it.reading.metric.label}: ${fmtOpt(it.reading.value)} (scores ${it.score.toInt()}/100 on reported figures)", ClaimKind.MODEL_ESTIMATE, sourceName = "Reported financials")) }
            positiveGrowth(growth).take(2).forEach { add(it) }
            valuation?.let { v -> v.premiumPct?.takeIf { it < 0 }?.let { add(Claim("Base-case model value is ${fmt(abs(it))}% above the price (model estimate)", ClaimKind.MODEL_ESTIMATE, sourceName = "Valuation model")) } }
        }
        val bear = buildList {
            addAll(longNegatives.map { articleClaim(it) }.take(4))
            weakest.take(3).forEach { add(Claim("${it.reading.metric.label}: ${fmtOpt(it.reading.value)} (scores ${it.score.toInt()}/100 on reported figures)", ClaimKind.MODEL_ESTIMATE, sourceName = "Reported financials")) }
            redFlags.take(3).forEach { add(flagClaim(it)) }
            valuation?.premiumPct?.takeIf { it > 0 }?.let { add(Claim("Base-case model value is ${fmt(it)}% below the price (model estimate)", ClaimKind.MODEL_ESTIMATE, sourceName = "Valuation model")) }
        }

        val earnings = buildList {
            add(Claim("No analyst consensus or earnings estimates are connected, so none are quoted here.", ClaimKind.AI_ASSUMPTION))
            fundamentals?.analysis?.let { a ->
                val ext = netProfitExtrapolation(fundamentals)
                if (ext != null) add(Claim(ext, ClaimKind.AI_ASSUMPTION, sourceName = "Arithmetic on reported net profit"))
            }
            growth.firstOrNull { it.label == "Management guidance" }?.claim?.let { add(it) }
        }

        return ResearchReport(
            symbol = symbol, company = company, generatedAt = now,
            news = when (news) {
                is DataPoint.Unavailable -> DataPoint.Unavailable(news.reason)
                is DataPoint.Available -> DataPoint.Available(newsArticles, news.source)
            },
            exchangeAnnouncements = when (exchange) {
                is DataPoint.Unavailable -> DataPoint.Unavailable(exchange.reason)
                is DataPoint.Available -> DataPoint.Available(exchangeArticles, exchange.source)
            },
            overview = overview, growth = growth, positives = positives, negatives = negatives,
            opportunities = opportunities, risks = risks, bull = bull, bear = bear, earningsOutlook = earnings,
            unavailable = unavailable + growth.mapNotNull { it.unavailableReason?.let { r -> "${it.label}: $r" } },
            writerNote = "Text here is assembled by fixed rules from the items listed. No language model wrote it, so every bullet can be traced to a " +
                "retrieved item or a computed figure. A model-written narrative would need a backend that holds its API key, and would have to cite the same items.",
        )
    }

    // ------------------------------------------------------------------ sentiment overview

    private fun overview(live: List<Article>): SentimentOverview {
        fun bias(items: List<Article>): Sentiment {
            val scored = items.filter { it.sentiment == Sentiment.POSITIVE || it.sentiment == Sentiment.NEGATIVE }
            if (scored.isEmpty()) return Sentiment.NEUTRAL
            val avg = scored.sumOf { it.sentimentScore } / scored.size
            return when {
                avg > 0.15 -> Sentiment.POSITIVE
                avg < -0.15 -> Sentiment.NEGATIVE
                else -> Sentiment.NEUTRAL
            }
        }
        return SentimentOverview(
            positive = live.count { it.sentiment == Sentiment.POSITIVE },
            negative = live.count { it.sentiment == Sentiment.NEGATIVE },
            neutral = live.count { it.sentiment == Sentiment.NEUTRAL },
            uncertain = live.count { it.sentiment == Sentiment.UNCERTAIN },
            shortTermBias = bias(live.filter { it.horizon != Horizon.LONG_TERM }),
            longTermBias = bias(live.filter { it.horizon != Horizon.SHORT_TERM }),
            total = live.size,
        )
    }

    // ------------------------------------------------------------------ growth

    private fun growthItems(f: FundamentalReport?, live: List<Article>, now: Long): List<GrowthItem> {
        val snap = (f?.snapshot as? DataPoint.Available)?.value
        val annual = snap?.annual.orEmpty()
        val latest = annual.lastOrNull()
        val src = "Reported financials (${snap?.basis?.label ?: ""})"
        val items = mutableListOf<GrowthItem>()

        val rc = ValuationCalc.revenueCagr(annual)
        items += GrowthItem("Historical revenue growth",
            rc?.let { Claim("Revenue grew ${pct(it)} a year over the last ${minOf(5, annual.size - 1)} years (CAGR).", ClaimKind.REPORTED_FACT, sourceName = src, date = latest?.periodEnd) },
            if (rc == null) "needs at least three years of positive revenue" else null)

        val npPrev = annual.getOrNull(annual.size - 2)?.netProfit
        val npNow = latest?.netProfit
        val npGrowth = if (npPrev != null && npNow != null && npPrev > 0 && npNow > 0) (npNow / npPrev - 1) * 100 else null
        items += GrowthItem("Earnings growth (latest year)",
            npGrowth?.let { Claim("Net profit ${if (it >= 0) "+" else ""}${fmt(it)}% in the latest reported year.", ClaimKind.REPORTED_FACT, sourceName = src, date = latest?.periodEnd) },
            if (npGrowth == null) "needs positive net profit in two consecutive years" else null)

        val margins = annual.mapNotNull { p -> val e = p.ebit; val r = p.revenue; if (e != null && r != null && r > 0) e / r * 100 else null }
        val marginTrend = if (margins.size >= 2) margins.last() - margins[margins.size - 2] else null
        items += GrowthItem("Profitability outlook (operating margin trend)",
            marginTrend?.let { Claim("Operating margin ${if (it >= 0) "rose" else "fell"} ${fmt(abs(it))} points to ${fmt(margins.last())}% in the latest year.", ClaimKind.REPORTED_FACT, sourceName = src, date = latest?.periodEnd) },
            if (marginTrend == null) "needs EBIT and revenue for two years" else null)

        val capexRatio = latest?.let { p -> val c = p.capex; val r = p.revenue; if (c != null && r != null && r > 0) c / r * 100 else null }
        items += GrowthItem("Capital expenditure",
            capexRatio?.let { Claim("Capex was ${fmt(it)}% of revenue in the latest reported year.", ClaimKind.REPORTED_FACT, sourceName = src, date = latest?.periodEnd) },
            if (capexRatio == null) "capex not reported" else null)

        val industryRoe = (f?.analysis?.industry as? DataPoint.Available)?.value?.firstOrNull { it.metric == Metric.ROE }
        items += GrowthItem("Competitive position (ROE vs peers)",
            industryRoe?.let { Claim("ROE ${fmt(it.value)}% vs a peer median of ${fmt(it.peerMedian)}%, better than ${it.betterThanPct}% of ${it.peerCount} peers.", ClaimKind.REPORTED_FACT, sourceName = src) },
            "fewer than three peers report ROE, or no peer data is connected")

        val orders = live.filter { it.category == NewsCategory.ORDERS_CONTRACTS && it.figures.isNotEmpty() && now - (it.published ?: 0L) <= 365 * DAY }
        val orderTotal = orders.sumOf { a -> a.figures.sumOf { it.amountCrore } }
        items += GrowthItem("Order book",
            if (orders.isNotEmpty()) Claim("News reports ₹${fmt0(orderTotal)} crore of order wins in the last 12 months across ${orders.size} item(s). These are announcements, not the company's reported order book.",
                ClaimKind.REPORTED_FACT, sourceName = "News items (see list)") else null,
            if (orders.isEmpty()) "no order values in the news feed; the order book is in company filings, which are not connected" else null)

        val capacity = live.filter { Regex("capacity|plant|facility|new product|launch", RegexOption.IGNORE_CASE).containsMatchIn(it.headline) }.take(3)
        items += GrowthItem("Capacity expansion and new products",
            if (capacity.isNotEmpty()) capacity.first().let { Claim("Reported: \"${it.headline}\" (${it.publisher}).", ClaimKind.REPORTED_FACT, it.url, it.publisher, it.published) } else null,
            if (capacity.isEmpty()) "no capacity or product announcements in the news feed" else null)

        val guidance = live.filter { it.category in setOf(NewsCategory.RESULTS, NewsCategory.EARNINGS, NewsCategory.MANAGEMENT) &&
            Regex("guidance|expects|outlook|targets|plans to|aims", RegexOption.IGNORE_CASE).containsMatchIn(it.headline) }
        items += GrowthItem("Management guidance",
            guidance.firstOrNull()?.let { Claim("Press report of management comment: \"${it.headline}\" (${it.publisher}).", ClaimKind.GUIDANCE, it.url, it.publisher, it.published) },
            if (guidance.isEmpty()) "no guidance statements in the news feed; guidance is in earnings-call transcripts, which are not connected" else null)

        val industry = live.filter { it.category == NewsCategory.INDUSTRY }.take(3)
        items += GrowthItem("Industry and sector outlook",
            industry.firstOrNull()?.let { a ->
                val kind = if (Regex("\\d|percent|%|expected|forecast|to grow", RegexOption.IGNORE_CASE).containsMatchIn(a.headline)) ClaimKind.THIRD_PARTY_ESTIMATE else ClaimKind.REPORTED_FACT
                Claim("\"${a.headline}\" (${a.publisher}).", kind, a.url, a.publisher, a.published)
            },
            if (industry.isEmpty()) "no industry items in the news feed" else null)

        items += GrowthItem("Market opportunity", null, "no market-size data is connected; a figure would need a cited third-party research source")
        items += GrowthItem("Sector growth rate", null, "no sector-growth data is connected")
        return items
    }

    /** Reported growth claims that show an increase: revenue, earnings and margin. Decided by the figure's sign. */
    private fun positiveGrowth(growth: List<GrowthItem>): List<Claim> = growth
        .filter { it.label in setOf("Historical revenue growth", "Earnings growth (latest year)", "Profitability outlook (operating margin trend)") }
        .mapNotNull { it.claim }
        .filter { c -> c.kind == ClaimKind.REPORTED_FACT && (c.text.contains("grew") || c.text.contains("rose") || c.text.contains("Net profit +")) }

    /** Arithmetic only: the five-year net-profit CAGR applied once. It is labelled an assumption, not a forecast. */
    private fun netProfitExtrapolation(f: FundamentalReport): String? {
        val annual = (f.snapshot as? DataPoint.Available)?.value?.annual ?: return null
        val i = annual.lastIndex
        val k = minOf(5, i)
        if (k < 2) return null
        val start = annual[i - k].netProfit ?: return null
        val end = annual[i].netProfit ?: return null
        if (start <= 0 || end <= 0) return null
        val g = Math.pow(end / start, 1.0 / k) - 1
        val next = end * (1 + g)
        return "If the ${k}-year net-profit growth rate of ${pct(g)} continued for one year, net profit would be about ₹${fmt0(next)} crore. This is arithmetic on reported figures, not a forecast."
    }

    // ------------------------------------------------------------------ claims

    private fun articleClaim(a: Article): Claim = Claim(
        text = a.headline, kind = ClaimKind.REPORTED_FACT, sourceUrl = a.url, sourceName = "${a.publisher} · ${a.credibility.label}", date = a.published,
    )

    private fun flagClaim(f: RedFlag): Claim = Claim("${f.title} (${f.severity.label.lowercase(Locale.US)}): ${f.evidence}", ClaimKind.MODEL_ESTIMATE, sourceName = "Reported financials, checked by rule")

    private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v * 100)
    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmtOpt(v: Double?) = v?.let { fmt(it) } ?: "n/a"
    private fun fmt0(v: Double) = String.format(Locale.US, "%,.0f", v)
}
