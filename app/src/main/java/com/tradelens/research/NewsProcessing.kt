package com.tradelens.research

/**
 * Deterministic news processing: credibility, category, sentiment, horizon, duplicates and ₹ figures.
 *
 * These are rule-based classifiers, not a language model. They can misread a headline. Each article keeps the rule
 * words that drove its label in [Article.significance], so a wrong call can be seen and checked against the source.
 */
object NewsProcessing {

    private const val DUPLICATE_SIMILARITY = 0.6
    private const val DUPLICATE_WINDOW_MS = 3 * 86_400_000L

    private val OFFICIAL_PUBLISHERS = listOf("bse", "nse", "sebi", "rbi", "exchange", "company announcement")
    private val PRESS = listOf(
        "economic times", "economictimes", "moneycontrol", "livemint", "mint", "business standard", "reuters", "bloomberg",
        "financial express", "financialexpress", "ndtv profit", "cnbc", "hindu businessline", "business line", "hindustan times",
        "the hindu", "barron", "financial times", "cnbctv18", "zee business", "news18",
    )

    fun credibility(publisher: String?): Credibility {
        val p = publisher?.lowercase().orEmpty()
        return when {
            OFFICIAL_PUBLISHERS.any { p.contains(it) } -> Credibility.OFFICIAL
            PRESS.any { p.contains(it) } -> Credibility.ESTABLISHED_PRESS
            else -> Credibility.UNVERIFIED
        }
    }

    // ------------------------------------------------------------------ category

    /** Checked in order; the first match wins, so specific events (litigation, rating) come before general ones. */
    private val CATEGORY_RULES: List<Pair<NewsCategory, Regex>> = listOf(
        NewsCategory.EXCHANGE_ANNOUNCEMENT to rx("exchange filing|disclosure to (the )?(bse|nse|exchanges?)|filed with (the )?(bse|nse)|regulation 30"),
        NewsCategory.LITIGATION to rx("lawsuit|litigation|court|tribunal|arbitration|petition|\\bsued\\b|\\bnclt\\b|\\bsues\\b"),
        NewsCategory.REGULATORY to rx("\\bsebi\\b|\\brbi\\b|regulator|show.cause|penalt|\\bfined\\b|\\bprobe\\b|investigat|raid|\\bban\\b|income tax department"),
        NewsCategory.CREDIT_RATING to rx("credit rating|rating (upgrade|downgrade|reaffirm|assign)|\\bcrisil\\b|\\bicra\\b|care ratings|india ratings|moody|fitch|s&p global"),
        NewsCategory.MERGER_ACQUISITION to rx("acqui(re|res|red|sition)|merger|takeover|buyout|demerger|stake (sale|purchase)|joint venture|divest"),
        NewsCategory.ORDERS_CONTRACTS to rx("\\border(s)?\\b|\\bcontract\\b|letter of (award|intent)|\\bbags?\\b.*\\border"),
        NewsCategory.RESULTS to rx("quarter(ly)? results|\\bq[1-4]\\b|net profit|profit (rises|falls|jumps|declines|slumps|surges|up|down)|revenue (rises|falls|grows|declines)|quarterly (profit|revenue)|annual results"),
        NewsCategory.EARNINGS to rx("earnings|\\beps\\b|guidance|outlook for fy|earnings call"),
        NewsCategory.MANAGEMENT to rx("\\b(ceo|cfo|md|managing director|chairman|appoint\\w*|resign\\w*|steps down|succession)\\b"),
        NewsCategory.POLICY to rx("government|\\bpolicy\\b|budget|\\bscheme\\b|tariff|\\bduty\\b|cabinet|ministry|subsidy|\\bpli\\b"),
        NewsCategory.INDUSTRY to rx("\\bindustry\\b|\\bsector\\b|demand (for|in)|market size"),
        NewsCategory.COMPETITOR to rx("\\brival\\b|competitor|\\bpeers?\\b"),
    )

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    fun category(headline: String): NewsCategory = CATEGORY_RULES.firstOrNull { it.second.containsMatchIn(headline) }?.first ?: NewsCategory.COMPANY

    // ------------------------------------------------------------------ horizon

    /** Which categories move the price in days (short-term) and which change the business over years (long-term). */
    fun horizon(c: NewsCategory): Horizon = when (c) {
        NewsCategory.RESULTS, NewsCategory.ORDERS_CONTRACTS, NewsCategory.CREDIT_RATING, NewsCategory.REGULATORY,
        NewsCategory.LITIGATION, NewsCategory.EXCHANGE_ANNOUNCEMENT, NewsCategory.MANAGEMENT -> Horizon.SHORT_TERM
        NewsCategory.EARNINGS -> Horizon.BOTH
        NewsCategory.MERGER_ACQUISITION, NewsCategory.POLICY, NewsCategory.INDUSTRY, NewsCategory.COMPETITOR, NewsCategory.COMPANY -> Horizon.LONG_TERM
    }

    // ------------------------------------------------------------------ sentiment

    private val POSITIVE_WORDS = mapOf(
        "rises" to 1.0, "rise" to 1.0, "jumps" to 1.5, "surges" to 1.5, "beats" to 1.5, "growth" to 1.0, "grows" to 1.0, "record" to 1.0,
        "wins" to 1.0, "win" to 1.0, "bags" to 1.0, "upgrade" to 1.5, "upgrades" to 1.5, "strong" to 1.0, "robust" to 1.0,
        "expands" to 1.0, "approval" to 1.0, "approved" to 1.0, "gains" to 1.0, "rally" to 1.0, "improves" to 1.0, "clears" to 1.0,
        "raises" to 1.0, "higher" to 0.5, "dividend" to 0.5, "secures" to 1.0, "launches" to 0.5,
    )
    private val NEGATIVE_WORDS = mapOf(
        "falls" to -1.0, "fall" to -1.0, "slumps" to -1.5, "plunges" to -2.0, "misses" to -1.5, "downgrade" to -1.5, "downgrades" to -1.5,
        "penalty" to -2.0, "fined" to -2.0, "probe" to -1.5, "lawsuit" to -1.5, "default" to -2.0, "loss" to -1.5, "losses" to -1.5,
        "declines" to -1.0, "decline" to -1.0, "weak" to -1.0, "cuts" to -1.0, "slashes" to -1.5, "delay" to -1.0, "delays" to -1.0,
        "halt" to -1.5, "ban" to -2.0, "fraud" to -2.5, "resigns" to -1.5, "raid" to -1.5, "concern" to -1.0, "concerns" to -1.0,
        "miss" to -1.5, "drops" to -1.0, "shut" to -1.5, "strike" to -1.0, "slowdown" to -1.5, "shortfall" to -1.5,
    )
    private val UNCERTAINTY = listOf("may", "might", "could", "likely", "expected to", "plans to", "considering", "seeks", "proposes",
        "to consider", "set to", "reportedly", "sources said", "talks")
    private val NEGATIONS = setOf("not", "no", "without", "fails", "failed", "unable", "denies", "denied")

    private fun tokens(s: String): List<String> = s.lowercase().split(Regex("[^a-z0-9&']+")).filter { it.isNotEmpty() }

    /**
     * Sentiment from weighted words, with a negation within three words flipping a word's sign. Uncertainty markers
     * ("may", "plans to", "talks") turn a weak call into UNCERTAIN rather than forcing a direction.
     */
    data class Sent(val label: Sentiment, val score: Double, val triggers: List<String>)

    fun sentiment(headline: String): Sent {
        val t = tokens(headline)
        var sum = 0.0
        val triggers = mutableListOf<String>()
        var lastHit = -1
        t.forEachIndexed { i, w ->
            val weight = POSITIVE_WORDS[w] ?: NEGATIVE_WORDS[w] ?: return@forEachIndexed
            // A negator only reaches the words after the previous sentiment word, so "not weak; growth" keeps "growth" positive.
            val negated = (max0(maxOf(i - 3, lastHit + 1))..i - 1).any { t[it] in NEGATIONS }
            lastHit = i
            val signed = if (negated) -weight else weight
            sum += signed
            triggers += (if (negated) "not " else "") + w
        }
        val lower = headline.lowercase()
        val uncertain = UNCERTAINTY.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(lower) }
        val s = (sum / 2.0).coerceIn(-1.0, 1.0)
        return when {
            uncertain && kotlin.math.abs(s) < 0.6 -> Sent(Sentiment.UNCERTAIN, 0.0, triggers + "uncertain wording")
            kotlin.math.abs(s) < 0.2 -> Sent(Sentiment.NEUTRAL, 0.0, triggers)
            s > 0 -> Sent(Sentiment.POSITIVE, s, triggers)
            else -> Sent(Sentiment.NEGATIVE, s, triggers)
        }
    }

    private fun max0(x: Int) = if (x < 0) 0 else x

    // ------------------------------------------------------------------ figures

    private val FIGURE = Regex("(?i)(?:₹|rs\\.?|inr)\\s?([\\d,]+(?:\\.\\d+)?)\\s?(lakh crore|crore|cr\\b)")

    /** ₹ amounts in crore or lakh crore, with up to 40 characters of the words before them as context. */
    fun figures(headline: String): List<ExtractedFigure> = FIGURE.findAll(headline).mapNotNull { m ->
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
        val unit = m.groupValues[2].lowercase()
        val crore = if (unit.startsWith("lakh")) value * 100_000 else value
        val context = headline.substring(0, m.range.first).takeLast(40).trim()
        ExtractedFigure(context.ifBlank { headline.take(40) }, crore)
    }.toList()

    // ------------------------------------------------------------------ duplicates

    private val STOP = setOf("the", "a", "an", "of", "to", "in", "on", "for", "and", "as", "by", "at", "from", "with", "its", "is", "after", "over")

    private fun words(s: String): Set<String> = tokens(s).filter { it !in STOP && it.length > 1 }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    /**
     * Builds the processed articles, newest first. Items that repeat an earlier headline (word overlap ≥ 60% within
     * three days) are kept but marked [Article.duplicateOf] and left out of the sentiment counts.
     */
    fun process(raw: List<RawItem>, companyAliases: List<String>): List<Article> {
        val aliases = companyAliases.map { it.lowercase() }.filter { it.length >= 3 }
        val relevant = raw.filter { item ->
            val lower = item.title.lowercase()
            aliases.isEmpty() || aliases.any { lower.contains(it) } || category(item.title) in setOf(NewsCategory.INDUSTRY, NewsCategory.POLICY, NewsCategory.COMPETITOR)
        }
        val sorted = relevant.sortedByDescending { it.published ?: 0L }
        val kept = mutableListOf<Pair<Article, Set<String>>>()
        for (item in sorted) {
            val w = words(item.title)
            val dup = kept.firstOrNull { (a, aw) ->
                jaccard(w, aw) >= DUPLICATE_SIMILARITY &&
                    (a.published == null || item.published == null || kotlin.math.abs(a.published - item.published) <= DUPLICATE_WINDOW_MS)
            }
            val c = category(item.title)
            val s = sentiment(item.title)
            val article = Article(
                id = "a" + item.link.hashCode().toUInt().toString(16),
                headline = item.title,
                url = item.link,
                published = item.published,
                publisher = item.publisher ?: "Unknown publisher",
                credibility = credibility(item.publisher),
                category = c,
                sentiment = s.label,
                sentimentScore = s.score,
                horizon = horizon(c),
                significance = significance(c, s, item.publisher),
                duplicateOf = dup?.first?.id,
                figures = figures(item.title),
            )
            kept += article to w
        }
        return kept.map { it.first }
    }

    private fun significance(c: NewsCategory, s: Sent, publisher: String?): String {
        val why = if (s.triggers.isEmpty()) "no sentiment words found" else "words: ${s.triggers.joinToString(", ")}"
        val horizonText = when (horizon(c)) {
            Horizon.SHORT_TERM -> "Likely to matter for the share price over days to weeks."
            Horizon.LONG_TERM -> "Matters more for the business over years than for the next few weeks."
            Horizon.BOTH -> "Can move the price now and also changes the business over time."
        }
        return "${c.label} item, read as ${s.label.label.lowercase()} ($why). $horizonText Source: ${publisher ?: "unknown"}."
    }
}
