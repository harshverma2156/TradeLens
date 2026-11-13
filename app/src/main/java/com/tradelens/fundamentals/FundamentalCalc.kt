package com.tradelens.fundamentals

import com.tradelens.flow.DataPoint
import com.tradelens.flow.ShareholdingQuarter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fundamental ratios, growth, cash flow, valuation, red flags and the quality score. Pure functions over the reported
 * inputs: a value that cannot be computed is null with the reason, and an absent input is never treated as zero.
 */
object FundamentalCalc {

    private const val CRORE = 10_000_000.0
    private const val DAY = 86_400_000L
    private const val YEAR = 365 * DAY
    /** Annual results older than this are flagged as stale (a year plus the 3-month filing window). */
    private const val STALE_AFTER = 460 * DAY
    private const val GOVERNANCE_CHECKS = 11

    private class Calc(val value: Double?, val reason: String = "")
    private fun v(x: Double) = Calc(x)
    private fun miss(why: String) = Calc(null, why)

    // ------------------------------------------------------------------ readings

    /**
     * Every metric for the annual period at index [i] (oldest first). Valuation needs a [price]; without one those
     * readings are null. Trend and peer comparisons call this with a null price, which leaves valuation out.
     */
    fun readings(s: FundamentalSnapshot, profile: IndustryProfile, price: Double?, i: Int = s.annual.lastIndex): List<Reading> {
        val a = s.annual
        if (i !in a.indices) return emptyList()
        val p = a[i]
        val prev = a.getOrNull(i - 1)
        val out = LinkedHashMap<Metric, Reading>()
        fun put(m: Metric, c: Calc) {
            val na = profile.notApplicable[m]
            out[m] = if (na != null) Reading(m, null, false, na) else Reading(m, c.value, true, c.reason)
        }

        val np = p.netProfit
        val rev = p.revenue
        val ebit = p.ebit
        val eqAvg = avg(p.equity, prev?.equity)
        val taAvg = avg(p.totalAssets, prev?.totalAssets)

        put(Metric.ROE, when {
            np == null -> miss("Net profit not reported")
            eqAvg == null -> miss("Equity not reported")
            eqAvg <= 0 -> miss("Equity is zero or negative: return on equity is not meaningful")
            else -> v(np / eqAvg * 100)
        })
        val capEmployed = if (p.totalAssets != null && p.currentLiabilities != null) p.totalAssets - p.currentLiabilities else null
        put(Metric.ROCE, when {
            ebit == null -> miss("EBIT not reported")
            capEmployed == null -> miss("Total assets or current liabilities not reported")
            capEmployed <= 0 -> miss("Capital employed is zero or negative")
            else -> v(ebit / capEmployed * 100)
        })
        put(Metric.ROA, when {
            np == null -> miss("Net profit not reported")
            taAvg == null -> miss("Total assets not reported")
            taAvg <= 0 -> miss("Total assets are zero or negative")
            else -> v(np / taAvg * 100)
        })
        put(Metric.GROSS_MARGIN, margin(p.grossProfit, rev, "Gross profit"))
        put(Metric.OPERATING_MARGIN, margin(ebit, rev, "EBIT"))
        put(Metric.NET_MARGIN, margin(np, rev, "Net profit"))

        // Growth
        val cagrRev = cagr(a, i) { it.revenue }
        val cagrEbitda = cagr(a, i) { it.ebitda }
        val cagrEps = cagr(a, i) { it.eps }
        val cagrNp = cagr(a, i) { it.netProfit }
        put(Metric.REVENUE_CAGR, cagrRev)
        put(Metric.EBITDA_CAGR, cagrEbitda)
        put(Metric.EPS_CAGR, cagrEps)
        put(Metric.NET_PROFIT_CAGR, cagrNp)
        put(Metric.QUARTERLY_REVENUE_GROWTH, quarterlyGrowth(s, i))
        val prevRev = prev?.revenue
        put(Metric.ANNUAL_REVENUE_GROWTH, when {
            rev == null || prevRev == null -> miss("Revenue not reported for both years")
            prevRev <= 0 -> miss("Prior-year revenue is zero or negative")
            else -> v((rev / prevRev - 1) * 100)
        })

        // Financial strength
        val debt = p.totalDebt
        val eq = p.equity
        put(Metric.DEBT_TO_EQUITY, when {
            debt == null -> miss("Total debt not reported")
            eq == null -> miss("Equity not reported")
            eq <= 0 -> miss("Negative equity: leverage ratio is not meaningful")
            else -> v(debt / eq)
        })
        val intr = p.interestExpense
        put(Metric.INTEREST_COVERAGE, when {
            ebit == null -> miss("EBIT not reported")
            intr == null -> miss("Interest expense not reported")
            intr <= 0 -> miss("No interest expense reported: coverage is not meaningful")
            else -> v(ebit / intr)
        })
        val ca = p.currentAssets
        val cl = p.currentLiabilities
        put(Metric.CURRENT_RATIO, when {
            ca == null -> miss("Current assets not reported")
            cl == null -> miss("Current liabilities not reported")
            cl <= 0 -> miss("Current liabilities are zero or negative")
            else -> v(ca / cl)
        })
        val inv = p.inventory
        put(Metric.QUICK_RATIO, when {
            ca == null || cl == null -> miss("Current assets or liabilities not reported")
            inv == null -> miss("Inventory not reported")
            cl <= 0 -> miss("Current liabilities are zero or negative")
            else -> v((ca - inv) / cl)
        })

        // Cash flow
        val ocf = p.operatingCashFlow
        val fcf = if (ocf != null && p.capex != null) ocf - p.capex else null
        put(Metric.CASH_CONVERSION, when {
            ocf == null -> miss("Operating cash flow not reported")
            np == null -> miss("Net profit not reported")
            np <= 0 -> miss("Net profit is zero or negative: cash conversion is not meaningful")
            else -> v(ocf / np)
        })
        put(Metric.FCF_MARGIN, when {
            fcf == null -> miss("Operating cash flow or capex not reported")
            rev == null -> miss("Revenue not reported")
            rev <= 0 -> miss("Revenue is zero or negative")
            else -> v(fcf / rev * 100)
        })

        // Valuation (needs a price; per-share values use the period's share count)
        val shares = p.shares
        val mcap = if (price != null && shares != null) price * shares / CRORE else null
        val pe: Calc = when {
            price == null -> miss("Share price not available")
            p.eps == null -> miss("EPS not reported")
            p.eps <= 0 -> miss("Loss-making: P/E is not meaningful")
            else -> v(price / p.eps)
        }
        put(Metric.PE, pe)
        put(Metric.FORWARD_PE, when {
            price == null -> miss("Share price not available")
            s.forwardEps == null -> miss("Forward EPS not reported")
            s.forwardEps <= 0 -> miss("Forward EPS is zero or negative")
            else -> v(price / s.forwardEps)
        })
        put(Metric.PB, when {
            price == null -> miss("Share price not available")
            shares == null -> miss("Share count not reported")
            eq == null -> miss("Equity not reported")
            eq <= 0 -> miss("Negative equity: P/B is not meaningful")
            else -> v(price / (eq * CRORE / shares))
        })
        put(Metric.PEG, when {
            pe.value == null -> miss("P/E not available")
            cagrEps.value == null -> miss("EPS growth not available: ${cagrEps.reason}")
            cagrEps.value <= 0 -> miss("EPS growth is not positive: PEG is not meaningful")
            else -> v(pe.value / cagrEps.value)
        })
        val ev = if (mcap != null && debt != null && p.cash != null) mcap + debt - p.cash else null
        put(Metric.EV_EBITDA, when {
            mcap == null -> miss("Market cap needs a price and share count")
            ev == null -> miss("Total debt or cash not reported")
            p.ebitda == null -> miss("EBITDA not reported")
            p.ebitda <= 0 -> miss("EBITDA is zero or negative")
            else -> v(ev / p.ebitda)
        })
        put(Metric.PRICE_TO_SALES, when {
            mcap == null -> miss("Market cap needs a price and share count")
            rev == null -> miss("Revenue not reported")
            rev <= 0 -> miss("Revenue is zero or negative")
            else -> v(mcap / rev)
        })
        put(Metric.DIVIDEND_YIELD, when {
            price == null -> miss("Share price not available")
            s.dividendPerShare == null -> miss("Dividend per share not reported")
            else -> v(s.dividendPerShare / price * 100)
        })
        put(Metric.FCF_YIELD, when {
            fcf == null -> miss("Operating cash flow or capex not reported")
            mcap == null -> miss("Market cap needs a price and share count")
            mcap <= 0 -> miss("Market cap is zero or negative")
            else -> v(fcf / mcap * 100)
        })
        return out.values.toList()
    }

    private fun avg(a: Double?, b: Double?): Double? = if (a != null && b != null) (a + b) / 2 else a

    private fun margin(num: Double?, rev: Double?, what: String): Calc = when {
        num == null -> miss("$what not reported")
        rev == null -> miss("Revenue not reported")
        rev <= 0 -> miss("Revenue is zero or negative")
        else -> v(num / rev * 100)
    }

    /** Compound annual growth over up to five years ending at index [i]. Needs a positive value at both ends. */
    private fun cagr(a: List<FinancialPeriod>, i: Int, f: (FinancialPeriod) -> Double?): Calc {
        val k = min(5, i)
        if (k < 2) return miss("Needs at least three annual periods")
        val start = f(a[i - k]) ?: return miss("Value missing for the start year")
        val end = f(a[i]) ?: return miss("Value missing for the latest year")
        if (start <= 0.0 || end <= 0.0) return miss("Zero or negative value at an end point: CAGR is not meaningful")
        return v(((end / start).pow(1.0 / k) - 1.0) * 100.0)
    }

    /** Latest quarter's revenue against the same quarter a year earlier. Only defined for the latest annual index. */
    private fun quarterlyGrowth(s: FundamentalSnapshot, i: Int): Calc {
        if (i != s.annual.lastIndex) return miss("Shown for the latest year only")
        val q = s.quarterly
        if (q.size < 5) return miss("Needs five quarters of revenue")
        val cur = q.last().revenue
        val base = q[q.size - 5].revenue
        return when {
            cur == null || base == null -> miss("Revenue not reported for the latest quarter or the same quarter last year")
            base <= 0 -> miss("Base-quarter revenue is zero or negative")
            else -> v((cur / base - 1) * 100)
        }
    }

    // ------------------------------------------------------------------ scoring

    /** 0–100 score for one applicable, computed reading, or null when there is nothing to score. */
    fun scoreOf(r: Reading, profile: IndustryProfile): Double? {
        val value = r.value ?: return null
        if (!r.applicable) return null
        val scale = profile.scales[r.metric] ?: return null
        return ((value - scale.bad) / (scale.good - scale.bad)).coerceIn(0.0, 1.0) * 100.0
    }

    /** Business consistency: years with profit and positive operating cash flow, plus stability of the net margin. */
    fun consistency(a: List<FinancialPeriod>): Double? {
        val years = a.takeLast(5).mapNotNull { p -> val np = p.netProfit; val ocf = p.operatingCashFlow; if (np != null && ocf != null) np to ocf else null }
        if (years.size < 3) return null
        val positive = years.count { (np, ocf) -> np > 0 && ocf > 0 }
        val part1 = 100.0 * positive / years.size
        val margins = a.takeLast(5).mapNotNull { p -> val np = p.netProfit; val rev = p.revenue; if (np != null && rev != null && rev > 0) np / rev * 100 else null }
        val parts = mutableListOf(part1)
        if (margins.size >= 3) {
            val mean = margins.average()
            val sd = sqrt(margins.sumOf { (it - mean) * (it - mean) } / margins.size)
            parts += (100.0 - sd * 5.0).coerceIn(0.0, 100.0)
        }
        return parts.average()
    }

    class Governance(val flags: List<RedFlag>, val checks: Int)

    /**
     * Red flags from filings, shares and the annual accounts. A flag is raised only when its inputs are reported; each
     * check that could be run is counted in [Governance.checks], so a thin record is visible rather than scored as clean.
     */
    fun governance(s: FundamentalSnapshot, now: Long): Governance {
        val a = s.annual
        val last = a.lastOrNull()
        val prev = a.getOrNull(a.size - 2)
        val rec = s.governance.valueOrNull
        val flags = mutableListOf<RedFlag>()
        var checks = 0

        rec?.auditorQualifications?.let { q ->
            checks++
            if (q.isNotEmpty()) flags += RedFlag("Auditor qualification", Severity.HIGH, q.joinToString("; "))
        }

        val rpt = rec?.relatedPartyValue
        val rev = last?.revenue
        if (rpt != null && rev != null && rev > 0) {
            checks++
            val pct = rpt / rev * 100
            if (pct > 10) flags += RedFlag("Large related-party transactions", if (pct > 25) Severity.HIGH else Severity.MEDIUM,
                "₹${fmt0(rpt)} crore, ${fmt1(pct)}% of revenue")
        }

        val ce = rec?.contingentLiabilities
        val eq = last?.equity
        if (ce != null && eq != null && eq > 0) {
            checks++
            val ratio = ce / eq
            if (ratio > 0.25) flags += RedFlag("Large contingent liabilities", if (ratio > 0.5) Severity.HIGH else Severity.MEDIUM,
                "₹${fmt0(ce)} crore, ${fmt1(ratio * 100)}% of net worth")
        }

        val rdNow = receivableDays(last)
        val rdPrev = receivableDays(prev)
        if (rdNow != null && rdPrev != null) {
            checks++
            if (rdNow - rdPrev >= 15) flags += RedFlag("Receivables building up", Severity.MEDIUM,
                "Receivable days ${rdPrev.roundToInt()} → ${rdNow.roundToInt()}")
        }

        val dNow = last?.totalDebt
        val dPrev = prev?.totalDebt
        if (dNow != null && dPrev != null && dPrev > 0) {
            checks++
            val growth = dNow / dPrev - 1
            if (growth > 0.20) {
                val ocfNow = last?.operatingCashFlow
                val ocfPrev = prev?.operatingCashFlow
                val falling = ocfNow != null && ocfPrev != null && ocfNow < ocfPrev
                flags += RedFlag("Debt rising quickly", if (falling) Severity.HIGH else Severity.MEDIUM,
                    "Total debt up ${fmt0(growth * 100)}% in a year" + if (falling) ", while operating cash flow fell" else "")
            }
        }

        val o1 = prev?.operatingCashFlow
        val o2 = last?.operatingCashFlow
        if (o1 != null && o2 != null) {
            checks++
            val n1 = prev?.netProfit
            val n2 = last?.netProfit
            if (o1 < 0 && o2 < 0 && n1 != null && n2 != null && n1 > 0 && n2 > 0) {
                flags += RedFlag("Negative operating cash flow two years running", Severity.HIGH, "while both years reported a profit")
            } else {
                val o0 = a.getOrNull(a.size - 3)?.operatingCashFlow
                if (o0 != null && o0 > 0 && o2 < 0.7 * o0) flags += RedFlag("Operating cash flow falling", Severity.MEDIUM,
                    "down ${fmt0((1 - o2 / o0) * 100)}% over three years")
            }
        }

        val shares = a.takeLast(4).mapNotNull { it.shares }
        if (shares.size == 4) {
            checks++
            val rises = (1..3).count { k -> shares[k] > shares[k - 1] * 1.02 }
            if (rises >= 2) flags += RedFlag("Repeated equity dilution", Severity.MEDIUM, "share count rose by more than 2% in $rises of the last three years")
        }

        val quarters = s.shareholding.valueOrNull?.sortedBy { it.quarterEnd }.orEmpty()
        val pledged = quarters.lastOrNull()?.promoterPledgedPct
        if (pledged != null) {
            checks++
            if (pledged > 10) flags += RedFlag("Promoter pledging", if (pledged > 20) Severity.HIGH else Severity.MEDIUM, "${fmt1(pledged)}% of promoter holding pledged")
        }
        val promoter = quarters.mapNotNull { q -> q.promoterPct?.let { q to it } }
        if (promoter.size >= 5) {
            checks++
            val drop = promoter[promoter.size - 5].second - promoter.last().second
            if (drop >= 3) flags += RedFlag("Promoter holding falling", Severity.MEDIUM, "down ${fmt1(drop)} points over four quarters")
        }

        rec?.managementChanges?.let { list ->
            checks++
            val n = list.count { now - it.date <= YEAR && it.role.uppercase(Locale.US) in SENIOR_ROLES }
            when {
                n >= 2 -> flags += RedFlag("Repeated senior management changes", Severity.HIGH, "$n CEO / MD / CFO changes in the last 12 months")
                n == 1 -> flags += RedFlag("Senior management change", Severity.LOW, "one CEO / MD / CFO change in the last 12 months")
            }
        }

        rec?.regulatoryActions?.let { list ->
            checks++
            val n = list.count { now - it.date <= 2 * YEAR }
            when {
                n >= 2 -> flags += RedFlag("Repeated regulatory actions", Severity.HIGH, "$n actions in the last 24 months")
                n == 1 -> flags += RedFlag("Regulatory action", Severity.MEDIUM, "one action in the last 24 months")
            }
        }
        return Governance(flags, checks)
    }

    private val SENIOR_ROLES = setOf("CEO", "MD", "CFO")

    private fun receivableDays(p: FinancialPeriod?): Double? {
        val r = p?.receivables ?: return null
        val rv = p.revenue ?: return null
        return if (rv > 0) r / rv * 365 else null
    }

    // ------------------------------------------------------------------ pillars and quality

    fun pillars(cur: List<Reading>, profile: IndustryProfile, gov: Governance, consistency: Double?): List<PillarScore> =
        Pillar.values().filter { it.scored }.map { pillar ->
            when (pillar) {
                Pillar.GOVERNANCE -> {
                    val score = if (gov.checks >= 3) max(0.0, 100.0 - gov.flags.sumOf { it.severity.penalty }) else null
                    PillarScore(pillar, score, gov.checks, GOVERNANCE_CHECKS)
                }
                Pillar.CONSISTENCY -> PillarScore(pillar, consistency, if (consistency != null) 1 else 0, 1)
                else -> {
                    val applicable = cur.filter { it.metric.pillar == pillar && it.applicable }
                    val scores = applicable.mapNotNull { scoreOf(it, profile) }
                    val need = min(2, applicable.size)
                    val score = if (scores.isNotEmpty() && scores.size >= need) scores.average() else null
                    PillarScore(pillar, score, scores.size, applicable.size)
                }
            }
        }

    fun band(score: Int): String = when {
        score >= 75 -> "High quality"
        score >= 55 -> "Good quality"
        score >= 35 -> "Average quality"
        else -> "Weak quality"
    }

    fun quality(pillars: List<PillarScore>, cfg: FundamentalConfig): QualityScore {
        val total = Pillar.values().count { it.scored }
        val usable = pillars.filter { it.pillar.scored && it.score != null }
        val scoredCount = usable.size
        if (scoredCount < cfg.minPillars) {
            return QualityScore(null, null, scoredCount, total,
                "Only $scoredCount of $total areas have enough reported data; an overall score needs ${cfg.minPillars}.")
        }
        val pairs = usable.mapNotNull { p -> p.score?.let { it to (cfg.weights[p.pillar] ?: 0.0) } }
        val wsum = pairs.sumOf { it.second }
        if (wsum <= 0.0) return QualityScore(null, null, scoredCount, total, "The configured weights leave out every area with data.")
        val score = (pairs.sumOf { it.first * it.second } / wsum).roundToInt()
        val note = if (scoredCount < total) "Re-weighted over $scoredCount of $total areas; ${total - scoredCount} lacked data." else "All $total areas scored."
        return QualityScore(score, band(score), scoredCount, total, note)
    }

    // ------------------------------------------------------------------ trends and peers

    fun trends(cur: List<Reading>, prev: List<Reading>): List<Trend> {
        val prevBy = prev.associateBy { it.metric }
        return cur.mapNotNull { r ->
            if (!r.metric.pillar.scored || !r.applicable) return@mapNotNull null
            val c = r.value ?: return@mapNotNull null
            val p = prevBy[r.metric]?.value ?: return@mapNotNull null
            val tolerance = 0.05 * max(abs(p), 1.0)
            if (abs(c - p) <= tolerance) null else Trend(r.metric, p, c, improved = (c > p) == r.metric.higherIsBetter)
        }
    }

    /** Where the company ranks on each scored metric among peers that report it. */
    fun industry(cur: List<Reading>, peers: List<List<Reading>>, minPeers: Int): List<IndustryPosition> =
        cur.mapNotNull { r ->
            val c = r.value ?: return@mapNotNull null
            if (!r.metric.pillar.scored || !r.applicable) return@mapNotNull null
            val pv = peers.mapNotNull { pr -> pr.firstOrNull { it.metric == r.metric }?.value }
            if (pv.size < minPeers) return@mapNotNull null
            val better = if (r.metric.higherIsBetter) pv.count { it < c } else pv.count { it > c }
            IndustryPosition(r.metric, c, median(pv), (100.0 * better / pv.size).roundToInt(), pv.size)
        }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    // ------------------------------------------------------------------ assembly

    fun analyze(
        s: FundamentalSnapshot,
        price: Double?,
        now: Long,
        cfg: FundamentalConfig = FundamentalConfig(),
        peers: DataPoint<List<FundamentalSnapshot>>,
    ): FundamentalAnalysis {
        val profile = IndustryProfiles.of(s.sector)
        val cur = readings(s, profile, price)
        val prev = readings(s, profile, null, s.annual.lastIndex - 1)
        val gov = governance(s, now)
        val consistency = consistency(s.annual)
        val pillars = pillars(cur, profile, gov, consistency)
        val quality = quality(pillars, cfg)

        val scored = cur.filter { it.applicable && it.metric.pillar.scored }
            .mapNotNull { r -> scoreOf(r, profile)?.let { MetricScore(r, it) } }
        val strongest = scored.filter { it.score >= 65 }.sortedByDescending { it.score }.take(3)
        val weakest = scored.filter { it.score < 45 }.sortedBy { it.score }.take(3)
        val trends = trends(cur, prev)

        val industry: DataPoint<List<IndustryPosition>> = when (peers) {
            is DataPoint.Unavailable -> DataPoint.Unavailable(peers.reason)
            is DataPoint.Available -> {
                val list = if (peers.value.size >= cfg.minPeers) {
                    industry(cur, peers.value.map { readings(it, profile, null) }, cfg.minPeers)
                } else emptyList()
                if (list.isEmpty()) DataPoint.Unavailable("Needs at least ${cfg.minPeers} peers reporting the same metric; ${peers.value.size} supplied.")
                else DataPoint.Available(list, peers.source)
            }
        }

        val missing = buildList {
            cur.filter { it.applicable && it.value == null }.forEach { add("${it.metric.label}: ${it.reason}") }
            (s.governance as? DataPoint.Unavailable)?.let { add("Governance and filings: ${it.reason}") }
            (s.shareholding as? DataPoint.Unavailable)?.let { add("Shareholding pattern: ${it.reason}") }
            if (s.shares() == null) add("Shares outstanding: not reported")
            if (s.forwardEps == null) add("Forward EPS: not reported")
            if (s.dividendPerShare == null) add("Dividend per share: not reported")
        }
        val latest = s.annual.lastOrNull()?.periodEnd
        val staleNote = when {
            latest == null -> "No annual results reported."
            now - latest > STALE_AFTER -> "Latest annual results are more than 15 months old."
            else -> null
        }

        return FundamentalAnalysis(
            sector = profile.sector,
            sectorKnown = s.sector != null,
            profileRationale = profile.rationale,
            basis = s.basis,
            currency = s.currency,
            latestPeriodEnd = latest,
            updatedAt = s.updatedAt,
            readings = cur,
            pillars = pillars,
            quality = quality,
            summary = summaryText(quality, profile, strongest, weakest, gov),
            strongest = strongest,
            weakest = weakest,
            improving = trends.filter { it.improved },
            deteriorating = trends.filter { !it.improved },
            redFlags = gov.flags,
            governanceChecks = gov.checks,
            industry = industry,
            missingFields = missing,
            staleNote = staleNote,
            caveat = "Rule-based screen on reported financials in ₹ crore (${s.basis.label.lowercase(Locale.US)} unless noted). " +
                "It describes the business, not whether to buy the shares.",
        )
    }

    private fun FundamentalSnapshot.shares(): Double? = annual.lastOrNull()?.shares

    private fun summaryText(q: QualityScore, profile: IndustryProfile, strongest: List<MetricScore>, weakest: List<MetricScore>, gov: Governance): String {
        val score = q.score ?: return "Not enough reported data for an overall quality score."
        return buildString {
            append("${q.band} ($score/100) for ${profile.sector.label.lowercase(Locale.US)}")
            strongest.firstOrNull()?.let { append(". Strongest: ${it.reading.metric.label}") }
            weakest.firstOrNull()?.let { append(". Weakest: ${it.reading.metric.label}") }
            val high = gov.flags.count { it.severity == Severity.HIGH }
            if (high > 0) append(". $high high-severity red flag${if (high > 1) "s" else ""}")
        }
    }

    private fun fmt0(x: Double) = String.format(Locale.US, "%,.0f", x)
    private fun fmt1(x: Double) = String.format(Locale.US, "%.1f", x)
}
