package com.tradelens.research

import com.tradelens.flow.DataPoint
import com.tradelens.flow.Provenance
import com.tradelens.fundamentals.Basis
import com.tradelens.fundamentals.FinancialPeriod
import com.tradelens.fundamentals.FundamentalCalc
import com.tradelens.fundamentals.FundamentalConfig
import com.tradelens.fundamentals.FundamentalSnapshot
import com.tradelens.fundamentals.Sector
import com.tradelens.marketdata.Freshness
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Parsing, classification and sentiment are checked on headlines written for the test. Dates come from java.time. */
class ResearchTest {
    private val now = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()
    private val prov = Provenance("test", null, null, Freshness.LIVE, official = false)

    private fun raw(title: String, publisher: String? = "Economic Times", at: String = "2026-11-12T10:00:00Z") =
        RawItem(title, "https://example.test/${title.hashCode()}", Instant.parse(at).toEpochMilli(), publisher)

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Reliance - Google News</title>
        <item>
          <title>Reliance Q2 net profit rises 12% - Economic Times</title>
          <link>https://news.example/a1</link>
          <pubDate>Wed, 12 Nov 2026 10:00:00 GMT</pubDate>
          <source url="https://economictimes.example">Economic Times</source>
        </item>
        <item>
          <title>Reliance bags order from NTPC - Moneycontrol</title>
          <link>https://news.example/a2</link>
          <pubDate>Tue, 11 Nov 2026 08:30:00 GMT</pubDate>
          <source url="https://moneycontrol.example">Moneycontrol</source>
        </item>
        </channel></rss>
    """.trimIndent()

    // ------------------------------------------------------------------ feed parsing

    @Test fun rssItemsParseAndPublisherSuffixIsRemoved() {
        val items = GoogleNewsRss.parseRss(rss)
        assertEquals(2, items.size)
        assertEquals("Reliance Q2 net profit rises 12%", items[0].title)
        assertEquals("Economic Times", items[0].publisher)
        assertEquals(Instant.parse("2026-11-12T10:00:00Z").toEpochMilli(), items[0].published)
        assertEquals("https://news.example/a2", items[1].link)
    }

    @Test fun malformedFeedGivesNoItemsNotAGuess() {
        assertTrue(GoogleNewsRss.parseRss("<rss><channel><item><title>broken").isEmpty())
        assertTrue(GoogleNewsRss.parseRss("not xml at all").isEmpty())
    }

    // ------------------------------------------------------------------ credibility

    @Test fun credibilityFollowsThePublisher() {
        assertEquals(Credibility.ESTABLISHED_PRESS, NewsProcessing.credibility("Economic Times"))
        assertEquals(Credibility.OFFICIAL, NewsProcessing.credibility("BSE India"))
        assertEquals(Credibility.UNVERIFIED, NewsProcessing.credibility("Some Blog"))
        assertEquals(Credibility.UNVERIFIED, NewsProcessing.credibility(null))
    }

    // ------------------------------------------------------------------ category and horizon

    @Test fun categoriesAreAssignedInPriorityOrder() {
        assertEquals(NewsCategory.ORDERS_CONTRACTS, NewsProcessing.category("Reliance bags ₹1,200 crore order from NTPC"))
        assertEquals(NewsCategory.REGULATORY, NewsProcessing.category("SEBI penalises Infosys over a disclosure lapse"))
        assertEquals(NewsCategory.RESULTS, NewsProcessing.category("Tata Motors Q2 results: net profit falls 8%"))
        assertEquals(NewsCategory.CREDIT_RATING, NewsProcessing.category("HDFC Bank gets CRISIL rating upgrade"))
        assertEquals(NewsCategory.MERGER_ACQUISITION, NewsProcessing.category("Company to acquire stake in a startup"))
        assertEquals(NewsCategory.MANAGEMENT, NewsProcessing.category("Infosys announces new CEO"))
        assertEquals(NewsCategory.POLICY, NewsProcessing.category("Government announces PLI scheme for chips"))
        assertEquals(NewsCategory.INDUSTRY, NewsProcessing.category("Auto sector demand rises in festive season"))
        assertEquals(NewsCategory.COMPANY, NewsProcessing.category("Company hosts annual picnic"))
    }

    @Test fun horizonSeparatesPriceMovesFromBusinessChange() {
        assertEquals(Horizon.SHORT_TERM, NewsProcessing.horizon(NewsCategory.RESULTS))
        assertEquals(Horizon.LONG_TERM, NewsProcessing.horizon(NewsCategory.POLICY))
        assertEquals(Horizon.LONG_TERM, NewsProcessing.horizon(NewsCategory.MERGER_ACQUISITION))
        assertEquals(Horizon.BOTH, NewsProcessing.horizon(NewsCategory.EARNINGS))
    }

    // ------------------------------------------------------------------ sentiment

    @Test fun sentimentFromWordsWithNegationAndUncertainty() {
        assertEquals(Sentiment.POSITIVE, NewsProcessing.sentiment("Net profit rises 20% on strong growth").label)
        assertEquals(Sentiment.NEGATIVE, NewsProcessing.sentiment("Company faces SEBI penalty and lawsuit").label)
        assertEquals(Sentiment.NEGATIVE, NewsProcessing.sentiment("Infosys misses estimates, shares fall").label)
        // "not weak" is a positive: the negation flips "weak".
        assertEquals(Sentiment.POSITIVE, NewsProcessing.sentiment("Reliance not weak; profit growth continues").label)
        assertEquals(Sentiment.UNCERTAIN, NewsProcessing.sentiment("Company may consider stake sale").label)
        assertEquals(Sentiment.NEUTRAL, NewsProcessing.sentiment("Company announces board meeting date").label)
    }

    @Test fun strongWordsOverrideMildUncertainty() {
        // "may" does not hide two strong positive words: the call is positive, with the wording listed in the triggers.
        val s = NewsProcessing.sentiment("Stock may rise sharply after strong results")
        assertEquals(Sentiment.POSITIVE, s.label)
        assertTrue(s.triggers.contains("strong"))
    }

    // ------------------------------------------------------------------ figures

    @Test fun rupeeAmountsAreReadInCrore() {
        assertEquals(1_200.0, NewsProcessing.figures("Reliance bags ₹1,200 crore order").single().amountCrore, 1e-9)
        assertEquals(150_000.0, NewsProcessing.figures("Adani wins ₹1.5 lakh crore project").single().amountCrore, 1e-6)
        assertTrue(NewsProcessing.figures("Net profit up 12%").isEmpty())
    }

    // ------------------------------------------------------------------ duplicates and relevance

    @Test fun repeatedStoriesAreMarkedNotCountedTwice() {
        val items = listOf(
            raw("Reliance bags ₹1,200 crore order from NTPC", at = "2026-11-12T10:00:00Z"),
            raw("Reliance bags Rs 1,200 crore order from NTPC", publisher = "Moneycontrol", at = "2026-11-12T12:00:00Z"),
        )
        val out = NewsProcessing.process(items, listOf("Reliance"))
        assertEquals(2, out.size)
        assertEquals(1, out.count { it.duplicateOf != null })
    }

    @Test fun unrelatedStoriesAreDropped() {
        val out = NewsProcessing.process(listOf(raw("Cricket team wins final match")), listOf("Reliance"))
        assertTrue(out.isEmpty())
    }

    // ------------------------------------------------------------------ report

    private fun snapshot(revenues: List<Double>): FundamentalSnapshot = FundamentalSnapshot(
        "T.NS", Sector.MANUFACTURING, Basis.CONSOLIDATED, "INR",
        revenues.mapIndexed { k, r -> FinancialPeriod(now - (revenues.size - 1 - k) * 365 * 86_400_000L, 12, revenue = r,
            netProfit = r * 0.1, ebit = r * 0.15, shares = 1e8, equity = 1_000.0) },
        emptyList(), null, null, DataPoint.Unavailable("none"), DataPoint.Unavailable("none"), now, null,
    )

    @Test fun revenueGrowthComesFromReportedFiguresWithItsSource() {
        // 100 → 121 → 146.41: 21.0% a year, from the reported statements, not from news.
        val snap = snapshot(listOf(100.0, 121.0, 146.41))
        val fr = com.tradelens.fundamentals.FundamentalReport("T.NS", now, DataPoint.Available(snap, prov), null, prov)
        val r = ResearchService.build("T.NS", "Test Co", DataPoint.Unavailable("offline"), DataPoint.Unavailable("none"), fr, null, now)
        val rev = r.growth.single { it.label == "Historical revenue growth" }
        assertTrue(rev.claim!!.text.contains("21.0%"))
        assertEquals(ClaimKind.REPORTED_FACT, rev.claim!!.kind)
    }

    @Test fun missingNewsIsSaidNotInvented() {
        val r = ResearchService.build("T.NS", "Test Co", DataPoint.Unavailable("feed down"), DataPoint.Unavailable("no feed"), null, null, now)
        assertTrue(r.news is DataPoint.Unavailable)
        assertTrue(r.positives.isEmpty() && r.negatives.isEmpty())
        assertNull(r.overview)
        assertTrue(r.unavailable.any { it.startsWith("Company news:") })
        assertTrue(r.unavailable.any { it.startsWith("Fundamentals:") })
        // Every growth item without a source must say why it is missing.
        assertTrue(r.growth.filter { it.claim == null }.all { !it.unavailableReason.isNullOrBlank() })
        assertTrue(r.growth.none { it.claim != null })
    }

    @Test fun noGuidanceClaimWithoutGuidanceInTheFeed() {
        val news = listOf(raw("Reliance Q2 net profit rises 12%"))
        val r = ResearchService.build("T.NS", "Reliance", DataPoint.Available(news, prov), DataPoint.Unavailable("none"), null, null, now)
        assertNull(r.growth.single { it.label == "Management guidance" }.claim)
        assertTrue(r.growth.none { it.claim?.kind == ClaimKind.GUIDANCE })
    }

    @Test fun guidanceIsLabelledAsCompanyGuidance() {
        val news = listOf(raw("Reliance Q2 results: management expects strong growth in FY27"))
        val r = ResearchService.build("T.NS", "Reliance", DataPoint.Available(news, prov), DataPoint.Unavailable("none"), null, null, now)
        assertEquals(ClaimKind.GUIDANCE, r.growth.single { it.label == "Management guidance" }.claim!!.kind)
    }

    @Test fun orderBookIsOnlyAnnouncedOrderValues() {
        val news = listOf(
            raw("Reliance bags ₹1,200 crore order from NTPC", at = "2026-10-01T10:00:00Z"),
            raw("Reliance wins ₹300 crore contract for solar", at = "2026-09-01T10:00:00Z"),
        )
        val r = ResearchService.build("T.NS", "Reliance", DataPoint.Available(news, prov), DataPoint.Unavailable("none"), null, null, now)
        val order = r.growth.single { it.label == "Order book" }
        assertTrue(order.claim!!.text.contains("1,500"))
        assertTrue(order.claim!!.text.contains("not the company's reported order book"))
    }

    @Test fun extrapolationIsLabelledAsAssumptionNotForecast() {
        val snap = snapshot(listOf(100.0, 121.0, 146.41))
        val analysis = FundamentalCalc.analyze(snap, 300.0, now, FundamentalConfig(), DataPoint.Unavailable("none"))
        val fr = com.tradelens.fundamentals.FundamentalReport("T.NS", now, DataPoint.Available(snap, prov), analysis, prov)
        val r = ResearchService.build("T.NS", "Test Co", DataPoint.Unavailable("offline"), DataPoint.Unavailable("none"), fr, null, now)
        val ext = r.earningsOutlook.single { it.text.contains("not a forecast") }
        assertEquals(ClaimKind.AI_ASSUMPTION, ext.kind)
        assertTrue(r.earningsOutlook.first().text.contains("No analyst consensus"))
    }

    @Test fun sentimentOverviewCountsOnlyNonDuplicates() {
        val news = listOf(
            raw("Reliance Q2 net profit rises 12%", at = "2026-11-12T10:00:00Z"),
            raw("Reliance Q2 net profit rises 12 percent", publisher = "Moneycontrol", at = "2026-11-12T11:00:00Z"),
            raw("Reliance faces SEBI penalty", at = "2026-11-10T10:00:00Z"),
        )
        val r = ResearchService.build("T.NS", "Reliance", DataPoint.Available(news, prov), DataPoint.Unavailable("none"), null, null, now)
        val o = r.overview!!
        assertEquals(2, o.total)
        assertEquals(1, o.positive)
        assertEquals(1, o.negative)
    }
}
