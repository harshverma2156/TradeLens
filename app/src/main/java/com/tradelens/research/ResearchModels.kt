package com.tradelens.research

import com.tradelens.flow.DataPoint

/**
 * Every statement in a research report carries a [ClaimKind] and, where one exists, the link and date of its source.
 * Nothing here is invented: an empty source list means the report says "unavailable", never a guessed figure.
 */

enum class NewsCategory(val label: String) {
    EXCHANGE_ANNOUNCEMENT("Exchange announcement"),
    RESULTS("Quarterly results"),
    EARNINGS("Earnings"),
    MANAGEMENT("Management"),
    ORDERS_CONTRACTS("New orders / contracts"),
    MERGER_ACQUISITION("Merger / acquisition"),
    POLICY("Government policy"),
    LITIGATION("Litigation"),
    REGULATORY("Regulatory"),
    CREDIT_RATING("Credit rating"),
    INDUSTRY("Industry"),
    COMPETITOR("Competitor"),
    COMPANY("Company news"),
}

enum class Sentiment(val label: String) { POSITIVE("Positive"), NEGATIVE("Negative"), NEUTRAL("Neutral"), UNCERTAIN("Uncertain") }

/** Whether the item is more likely to move the share price now, or to change the business over years. */
enum class Horizon(val label: String) {
    SHORT_TERM("Short-term price impact"),
    LONG_TERM("Long-term business impact"),
    BOTH("Short-term and long-term"),
}

enum class ClaimKind(val label: String) {
    REPORTED_FACT("Reported fact"),
    GUIDANCE("Company guidance"),
    THIRD_PARTY_ESTIMATE("Third-party estimate"),
    MODEL_ESTIMATE("Model estimate (computed here)"),
    AI_ASSUMPTION("Assumption (generated)"),
}

/** How much a source can be relied on for the fact it reports. */
enum class Credibility(val label: String) {
    OFFICIAL("Official (exchange, regulator or company)"),
    ESTABLISHED_PRESS("Established financial press"),
    UNVERIFIED("Other publisher, not verified"),
}

/** A number found in an article's headline, e.g. an order value. Only ₹ crore and ₹ lakh crore are read. */
data class ExtractedFigure(val context: String, val amountCrore: Double)

data class Article(
    val id: String,
    val headline: String,
    val url: String,
    val published: Long?,
    val publisher: String,
    val credibility: Credibility,
    val category: NewsCategory,
    val sentiment: Sentiment,
    /** −1 (negative) … +1 (positive). Zero for neutral and uncertain items. */
    val sentimentScore: Double,
    val horizon: Horizon,
    val significance: String,
    /** Id of the earlier article this repeats, or null for the first report of a story. */
    val duplicateOf: String?,
    val figures: List<ExtractedFigure>,
)

data class Claim(val text: String, val kind: ClaimKind, val sourceUrl: String? = null, val sourceName: String? = null, val date: Long? = null)

data class GrowthItem(val label: String, val claim: Claim?, val unavailableReason: String?)

data class SentimentOverview(
    val positive: Int,
    val negative: Int,
    val neutral: Int,
    val uncertain: Int,
    val shortTermBias: Sentiment,
    val longTermBias: Sentiment,
    val total: Int,
)

data class ResearchReport(
    val symbol: String,
    val company: String,
    val generatedAt: Long,
    val news: DataPoint<List<Article>>,
    val exchangeAnnouncements: DataPoint<List<Article>>,
    val overview: SentimentOverview?,
    val growth: List<GrowthItem>,
    val positives: List<Claim>,
    val negatives: List<Claim>,
    val opportunities: List<Claim>,
    val risks: List<Claim>,
    val bull: List<Claim>,
    val bear: List<Claim>,
    val earningsOutlook: List<Claim>,
    val unavailable: List<String>,
    val writerNote: String,
)
