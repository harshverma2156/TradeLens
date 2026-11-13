package com.tradelens.fundamentals

import com.tradelens.flow.DataPoint
import com.tradelens.flow.Provenance
import com.tradelens.flow.ShareholdingQuarter
import com.tradelens.marketdata.Freshness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Hand-worked fixtures. Money is ₹ crore, shares are absolute counts, so market cap (crore) = price × shares ÷ 10⁷.
 * Missing inputs must come out as null with a reason, never as 0.
 */
class FundamentalCalcTest {
    private val e = 1e-9
    private val now = 1_780_000_000_000L
    private val day = 86_400_000L
    private val prov = Provenance("Test feed", null, null, Freshness.MARKET_CLOSED, official = true)

    private fun period(k: Int, f: FinancialPeriod.() -> FinancialPeriod = { this }) =
        FinancialPeriod(periodEnd = now - (3 - k) * 365 * day, months = 12).f()

    private fun snap(
        annual: List<FinancialPeriod>,
        sector: Sector? = Sector.MANUFACTURING,
        quarterly: List<FinancialPeriod> = emptyList(),
        gov: DataPoint<GovernanceRecord> = DataPoint.Available(GovernanceRecord(auditorQualifications = emptyList()), prov),
        sh: DataPoint<List<ShareholdingQuarter>> = DataPoint.Unavailable("Not supplied in this fixture"),
        forwardEps: Double? = null,
        dps: Double? = null,
    ) = FundamentalSnapshot("T.NS", sector, Basis.CONSOLIDATED, "INR", annual, quarterly, forwardEps, dps, gov, sh, updatedAt = now)

    private fun List<Reading>.of(m: Metric) = first { it.metric == m }
    private fun generic() = IndustryProfiles.of(Sector.MANUFACTURING)

    // ------------------------------------------------------------------ profitability

    @Test fun profitabilityRatiosByHand() {
        // Previous year: equity 400, assets 1,000. Latest: revenue 1,000, gross 400, EBIT 150, net 100, equity 500,
        // assets 1,200, current liabilities 300. Averages use both years' balance sheets.
        val s = snap(listOf(
            period(0) { copy(equity = 400.0, totalAssets = 1_000.0) },
            period(1) { copy(revenue = 1_000.0, grossProfit = 400.0, ebit = 150.0, netProfit = 100.0, equity = 500.0,
                totalAssets = 1_200.0, currentLiabilities = 300.0) },
        ))
        val r = FundamentalCalc.readings(s, generic(), null, 1)
        assertEquals(100.0 / 450.0 * 100, r.of(Metric.ROE).value!!, e)           // 100 ÷ average equity 450
        assertEquals(150.0 / 900.0 * 100, r.of(Metric.ROCE).value!!, e)          // 150 ÷ (1,200 − 300)
        assertEquals(100.0 / 1_100.0 * 100, r.of(Metric.ROA).value!!, e)         // 100 ÷ average assets 1,100
        assertEquals(40.0, r.of(Metric.GROSS_MARGIN).value!!, e)
        assertEquals(15.0, r.of(Metric.OPERATING_MARGIN).value!!, e)
        assertEquals(10.0, r.of(Metric.NET_MARGIN).value!!, e)
    }

    @Test fun negativeEquityGivesNullNotAZeroOrNegativeReturn() {
        val s = snap(listOf(period(0), period(1) { copy(revenue = 1_000.0, netProfit = 50.0, equity = -200.0) }))
        val roe = FundamentalCalc.readings(s, generic(), null, 1).of(Metric.ROE)
        assertNull(roe.value)
        assertTrue(roe.reason.contains("not meaningful"))
    }

    // ------------------------------------------------------------------ growth

    @Test fun cagrByHand() {
        // Revenue 100 → 121 → 146.41: two years at 21% a year (146.41 ÷ 100 = 1.21²).
        val s = snap(listOf(period(0) { copy(revenue = 100.0) }, period(1) { copy(revenue = 121.0) }, period(2) { copy(revenue = 146.41) }))
        assertEquals(21.0, FundamentalCalc.readings(s, generic(), null, 2).of(Metric.REVENUE_CAGR).value!!, 1e-9)
    }

    @Test fun cagrNeedsAPositiveStartAndThreeYears() {
        // EPS goes from a loss (−2) to a profit: the compound rate is meaningless from a negative base.
        val s = snap(listOf(period(0) { copy(eps = -2.0) }, period(1) { copy(eps = 5.0) }, period(2) { copy(eps = 10.0) }))
        val eps = FundamentalCalc.readings(s, generic(), null, 2).of(Metric.EPS_CAGR)
        assertNull(eps.value)
        assertTrue(eps.reason.contains("CAGR is not meaningful"))

        val two = snap(listOf(period(0) { copy(revenue = 100.0) }, period(1) { copy(revenue = 121.0) }))
        assertNull(FundamentalCalc.readings(two, generic(), null, 1).of(Metric.REVENUE_CAGR).value)
    }

    @Test fun quarterlyGrowthIsYearOnYearForTheLatestQuarter() {
        // Latest quarter 130 against the same quarter a year earlier (100): +30%.
        val q = (0 until 5).map { k -> FinancialPeriod(now - (4 - k) * 90 * day, 3, revenue = if (k == 0) 100.0 else if (k == 4) 130.0 else 110.0) }
        val s = snap(listOf(period(0) { copy(revenue = 1.0) }, period(1) { copy(revenue = 1.0) }, period(2) { copy(revenue = 1.0) }), quarterly = q)
        assertEquals(30.0, FundamentalCalc.readings(s, generic(), null, 2).of(Metric.QUARTERLY_REVENUE_GROWTH).value!!, e)
    }

    // ------------------------------------------------------------------ financial strength

    @Test fun zeroInterestGivesNoCoverageRatio() {
        val s = snap(listOf(period(0), period(1) { copy(ebit = 150.0, interestExpense = 0.0) }))
        val ic = FundamentalCalc.readings(s, generic(), null, 1).of(Metric.INTEREST_COVERAGE)
        assertNull(ic.value)
        assertTrue(ic.reason.contains("No interest expense"))
    }

    @Test fun missingInventoryMeansNoQuickRatioNotAZero() {
        val s = snap(listOf(period(0), period(1) { copy(currentAssets = 600.0, currentLiabilities = 300.0, inventory = null) }))
        val r = FundamentalCalc.readings(s, generic(), null, 1)
        assertEquals(2.0, r.of(Metric.CURRENT_RATIO).value!!, e)
        assertNull(r.of(Metric.QUICK_RATIO).value)
        assertEquals("Inventory not reported", r.of(Metric.QUICK_RATIO).reason)
    }

    // ------------------------------------------------------------------ cash flow

    @Test fun cashConversionAndFreeCashFlow() {
        // OCF 150, net profit 100, capex 50, revenue 1,000: conversion 150 ÷ 100 = 1.5x; FCF = 150 − 50 = 100, a 10% margin.
        val s = snap(listOf(period(0), period(1) { copy(revenue = 1_000.0, netProfit = 100.0, operatingCashFlow = 150.0, capex = 50.0) }))
        val r = FundamentalCalc.readings(s, generic(), null, 1)
        assertEquals(1.5, r.of(Metric.CASH_CONVERSION).value!!, e)
        assertEquals(10.0, r.of(Metric.FCF_MARGIN).value!!, e)                  // 100 ÷ 1,000
    }

    @Test fun lossMakingHasNoCashConversion() {
        val s = snap(listOf(period(0), period(1) { copy(netProfit = -40.0, operatingCashFlow = 30.0) }))
        assertNull(FundamentalCalc.readings(s, generic(), null, 1).of(Metric.CASH_CONVERSION).value)
    }

    // ------------------------------------------------------------------ valuation

    @Test fun valuationByHand() {
        // Price ₹500, 10 crore shares → market cap ₹5,000 crore. Equity 1,000 → book value ₹100 a share.
        // EV = 5,000 + debt 500 − cash 300 = 5,200. EBITDA 400 → EV/EBITDA 13. Sales 1,000 → P/S 5.
        // EPS 20 → 22 → 25 gives a CAGR of √1.25 − 1 = 11.80%, so PEG = 20 ÷ 11.80 = 1.694.
        val latest = period(2) { copy(revenue = 1_000.0, ebitda = 400.0, eps = 25.0, shares = 100_000_000.0, equity = 1_000.0,
            totalDebt = 500.0, cash = 300.0, operatingCashFlow = 200.0, capex = 50.0) }
        val s = snap(listOf(period(0) { copy(eps = 20.0) }, period(1) { copy(eps = 22.0) }, latest), forwardEps = 20.0, dps = 10.0)
        val r = FundamentalCalc.readings(s, generic(), 500.0, 2)
        assertEquals(20.0, r.of(Metric.PE).value!!, e)
        assertEquals(25.0, r.of(Metric.FORWARD_PE).value!!, e)
        assertEquals(5.0, r.of(Metric.PB).value!!, e)
        assertEquals(13.0, r.of(Metric.EV_EBITDA).value!!, e)
        assertEquals(5.0, r.of(Metric.PRICE_TO_SALES).value!!, e)
        assertEquals(2.0, r.of(Metric.DIVIDEND_YIELD).value!!, e)               // ₹10 ÷ ₹500
        assertEquals(100.0 * 150.0 / 5_000.0, r.of(Metric.FCF_YIELD).value!!, e) // FCF 150 ÷ market cap 5,000
        assertEquals(20.0 / (100.0 * (Math.sqrt(1.25) - 1)), r.of(Metric.PEG).value!!, 1e-9)
    }

    @Test fun noShareCountMeansNoPerShareOrMarketValuesNotZero() {
        val s = snap(listOf(period(0), period(1) { copy(equity = 1_000.0, ebitda = 400.0, revenue = 1_000.0, totalDebt = 500.0, cash = 300.0) }))
        val r = FundamentalCalc.readings(s, generic(), 500.0, 1)
        assertNull(r.of(Metric.PB).value)
        assertEquals("Share count not reported", r.of(Metric.PB).reason)
        assertNull(r.of(Metric.EV_EBITDA).value)
        assertNull(r.of(Metric.PRICE_TO_SALES).value)
    }

    @Test fun bankProfileLeavesOutLeverageAndMarginRatios() {
        val bank = IndustryProfiles.of(Sector.BANK)
        val s = snap(listOf(period(0), period(1) { copy(revenue = 1_000.0, netProfit = 140.0, equity = 1_000.0, totalAssets = 10_000.0, totalDebt = 5_000.0) }), sector = Sector.BANK)
        val r = FundamentalCalc.readings(s, bank, null, 1)
        val de = r.of(Metric.DEBT_TO_EQUITY)
        assertFalse(de.applicable)
        assertTrue(de.reason.contains("banks"))
        assertFalse(r.of(Metric.GROSS_MARGIN).applicable)
        assertTrue(r.of(Metric.ROE).applicable)
        // ROE 140 ÷ 1,000 = 14% on the bank scale 8 → 20: (14 − 8) ÷ 12 = 50 points.
        assertEquals(50.0, FundamentalCalc.scoreOf(r.of(Metric.ROE), bank)!!, e)
        // Net margin 14% on the bank scale 10 → 30: (14 − 10) ÷ 20 = 20 points.
        assertEquals(20.0, FundamentalCalc.scoreOf(r.of(Metric.NET_MARGIN), bank)!!, e)
    }

    // ------------------------------------------------------------------ scoring

    @Test fun scoreIsLinearBetweenItsBounds() {
        // ROE 12.5% on the generic scale 5 → 20: (12.5 − 5) ÷ 15 = 50 points.
        assertEquals(50.0, FundamentalCalc.scoreOf(Reading(Metric.ROE, 12.5, true), generic())!!, e)
        assertEquals(100.0, FundamentalCalc.scoreOf(Reading(Metric.ROE, 80.0, true), generic())!!, e)   // capped
        assertNull(FundamentalCalc.scoreOf(Reading(Metric.ROE, null, true, "missing"), generic()))         // never 0
        assertNull(FundamentalCalc.scoreOf(Reading(Metric.GROSS_MARGIN, 40.0, false, "n/a"), IndustryProfiles.of(Sector.BANK)))
    }

    @Test fun qualityScoreWeightsAvailablePillarsOnly() {
        // Profitability 80 (w25), growth 60 (w20), strength 40 (w20), cash flow missing, governance 100 (w10), consistency 50 (w10):
        // (80×25 + 60×20 + 40×20 + 100×10 + 50×10) ÷ 85 = 5,500 ÷ 85 = 64.7 → 65, "Good quality".
        val p = listOf(
            PillarScore(Pillar.PROFITABILITY, 80.0, 6, 6), PillarScore(Pillar.GROWTH, 60.0, 6, 6),
            PillarScore(Pillar.FINANCIAL_STRENGTH, 40.0, 4, 4), PillarScore(Pillar.CASH_FLOW, null, 0, 2),
            PillarScore(Pillar.GOVERNANCE, 100.0, 11, 11), PillarScore(Pillar.CONSISTENCY, 50.0, 1, 1),
        )
        val q = FundamentalCalc.quality(p, FundamentalConfig())
        assertEquals(65, q.score)
        assertEquals("Good quality", q.band)
        assertEquals(5, q.pillarsScored)
        assertTrue(q.note.contains("Re-weighted"))
    }

    @Test fun tooFewPillarsGivesNoScoreNotAGuess() {
        val p = listOf(PillarScore(Pillar.PROFITABILITY, 80.0, 6, 6), PillarScore(Pillar.GROWTH, null, 0, 6))
        val q = FundamentalCalc.quality(p, FundamentalConfig())
        assertNull(q.score)
        assertTrue(q.note.contains("Only 1 of 6"))
    }

    @Test fun invalidConfigIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { FundamentalConfig(weights = mapOf(Pillar.GROWTH to 0.0)) }
        assertThrows(IllegalArgumentException::class.java) { Scale(5.0, 5.0) }
    }

    // ------------------------------------------------------------------ governance and red flags

    @Test fun auditorQualificationIsHighSeverityAndLowersGovernance() {
        // Three checks run (auditor, related party at zero, contingent at zero), so the pillar scores: 100 − 35 = 65.
        val s = snap(
            listOf(period(0), period(1) { copy(revenue = 1_000.0, equity = 500.0) }),
            gov = DataPoint.Available(GovernanceRecord(auditorQualifications = listOf("Going concern emphasis"), relatedPartyValue = 0.0, contingentLiabilities = 0.0), prov),
        )
        val g = FundamentalCalc.governance(s, now)
        assertEquals(3, g.checks)
        assertEquals(Severity.HIGH, g.flags.single().severity)
        val pillar = FundamentalCalc.pillars(emptyList(), generic(), g, null).first { it.pillar == Pillar.GOVERNANCE }
        assertEquals(65.0, pillar.score!!, e)
    }

    @Test fun relatedPartyAndContingentLevelsSetSeverity() {
        // Related-party 300 on revenue 1,000 = 30% → HIGH. Contingent 0.3 × net worth 500 = 150 → 30%, MEDIUM.
        val s = snap(
            listOf(period(0), period(1) { copy(revenue = 1_000.0, equity = 500.0) }),
            gov = DataPoint.Available(GovernanceRecord(relatedPartyValue = 300.0, contingentLiabilities = 150.0), prov),
        )
        val flags = FundamentalCalc.governance(s, now).flags.associateBy { it.title }
        assertEquals(Severity.HIGH, flags.getValue("Large related-party transactions").severity)
        assertEquals(Severity.MEDIUM, flags.getValue("Large contingent liabilities").severity)
    }

    @Test fun repeatedDilutionIsFlagged() {
        // Share count up 3% in each of three years (100m → 109.27m): three rises, so MEDIUM.
        val shares = (0..3).map { k -> FinancialPeriod(now - (3 - k) * 365 * day, 12, shares = 100_000_000.0 * Math.pow(1.03, k.toDouble())) }
        val f = FundamentalCalc.governance(snap(shares), now).flags.single { it.title == "Repeated equity dilution" }
        assertEquals(Severity.MEDIUM, f.severity)
    }

    @Test fun promoterPledgingAboveTwentyPercentIsHigh() {
        val sh = DataPoint.Available(listOf(ShareholdingQuarter(now, 55.0, 25.0, 10.0, 5.0, 30.0)), prov)
        val f = FundamentalCalc.governance(snap(listOf(period(0), period(1)), sh = sh), now).flags.single()
        assertEquals("Promoter pledging", f.title)
        assertEquals(Severity.HIGH, f.severity)
    }

    @Test fun operatingCashOutflowWhileProfitableIsHigh() {
        val s = snap(listOf(period(0) { copy(operatingCashFlow = -10.0, netProfit = 20.0) },
            period(1) { copy(operatingCashFlow = -30.0, netProfit = 25.0) }))
        val f = FundamentalCalc.governance(s, now).flags.single { it.title.startsWith("Negative operating cash flow") }
        assertEquals(Severity.HIGH, f.severity)
    }

    @Test fun seniorChangesCountOnlyWithinTwelveMonths() {
        val changes = listOf(
            ManagementChange(now - 30 * day, "CFO", "Resigned"),
            ManagementChange(now - 200 * day, "MD", "Appointed"),
            ManagementChange(now - 500 * day, "CEO", "Resigned"),   // outside 12 months
            ManagementChange(now - 60 * day, "Director", "Appointed"),
        )
        val g = FundamentalCalc.governance(snap(listOf(period(0), period(1)), gov = DataPoint.Available(GovernanceRecord(managementChanges = changes), prov)), now)
        assertEquals(Severity.HIGH, g.flags.single().severity)
        assertTrue(g.flags.single().evidence.startsWith("2 CEO"))
    }

    @Test fun missingFilingsAreNotCountedAsClean() {
        val g = FundamentalCalc.governance(snap(listOf(period(0), period(1)), gov = DataPoint.Unavailable("No filings feed")), now)
        assertEquals(0, g.checks)
        assertTrue(g.flags.isEmpty())
        assertNull(FundamentalCalc.pillars(emptyList(), generic(), g, null).first { it.pillar == Pillar.GOVERNANCE }.score)
    }

    // ------------------------------------------------------------------ consistency, trends, peers

    @Test fun consistencyNeedsThreeYearsAndRewardsSteadyProfits() {
        val steady = (0..4).map { period(0) { copy(revenue = 1_000.0, netProfit = 100.0, operatingCashFlow = 120.0) } }
        assertEquals(100.0, FundamentalCalc.consistency(steady)!!, e)
        assertNull(FundamentalCalc.consistency(steady.take(2)))
    }

    @Test fun trendsReportDirectionAndIgnoreSmallMoves() {
        val cur = listOf(Reading(Metric.ROE, 15.0, true), Reading(Metric.DEBT_TO_EQUITY, 1.0, true), Reading(Metric.ROCE, 10.2, true))
        val prev = listOf(Reading(Metric.ROE, 10.0, true), Reading(Metric.DEBT_TO_EQUITY, 0.5, true), Reading(Metric.ROCE, 10.0, true))
        val t = FundamentalCalc.trends(cur, prev).associateBy { it.metric }
        assertTrue(t.getValue(Metric.ROE).improved)
        assertFalse(t.getValue(Metric.DEBT_TO_EQUITY).improved)                  // lower is better, so a rise is worse
        assertFalse(t.containsKey(Metric.ROCE))                                  // +2% is inside the 5% tolerance
    }

    @Test fun industryPositionUsesPeersThatReportTheMetric() {
        // Company ROE 20 against peers 10, 15, 30: two are below it → 67th percentile, peer median 15.
        val peers = listOf(listOf(Reading(Metric.ROE, 10.0, true)), listOf(Reading(Metric.ROE, 15.0, true)), listOf(Reading(Metric.ROE, 30.0, true)))
        val pos = FundamentalCalc.industry(listOf(Reading(Metric.ROE, 20.0, true)), peers, minPeers = 3).single()
        assertEquals(67, pos.betterThanPct)
        assertEquals(15.0, pos.peerMedian, e)
        assertEquals(3, pos.peerCount)
        assertTrue(FundamentalCalc.industry(listOf(Reading(Metric.ROE, 20.0, true)), peers.take(2), minPeers = 3).isEmpty())
    }

    // ------------------------------------------------------------------ assembly and unavailable data

    @Test fun unknownSectorUsesGenericProfileAndSaysSo() {
        val a = FundamentalCalc.analyze(snap(listOf(period(0), period(1)), sector = null), null, now, peers = DataPoint.Unavailable("none"))
        assertFalse(a.sectorKnown)
        assertEquals(Sector.OTHER, a.sector)
        assertTrue(a.profileRationale.contains("No sector supplied"))
    }

    @Test fun staleAnnualResultsAreFlagged() {
        val old = FinancialPeriod(now - 600 * day, 12)
        val a = FundamentalCalc.analyze(snap(listOf(old, old)), null, now, peers = DataPoint.Unavailable("none"))
        assertNotNull(a.staleNote)
    }

    @Test fun missingGovernanceAndPeersAreListedAsMissing() {
        val a = FundamentalCalc.analyze(snap(listOf(period(0), period(1)), gov = DataPoint.Unavailable("No filings feed")), null, now,
            peers = DataPoint.Unavailable("No peer feed"))
        assertTrue(a.missingFields.any { it.startsWith("Governance and filings") })
        assertTrue(a.industry is DataPoint.Unavailable)
        assertTrue(a.industry is DataPoint.Unavailable)
        assertEquals(0, a.governanceChecks)
    }

    @Test fun reportFromUnavailableSourceHasNoAnalysisAndAReason() = runBlocking {
        val r = FundamentalService.report("T.NS", UnavailableFundamentalSource, 500.0, Freshness.MARKET_CLOSED, null, now)
        assertNull(r.analysis)
        assertTrue((r.snapshot as DataPoint.Unavailable).reason.isNotBlank())
    }

    @Test fun reportFromAvailableSourceKeepsProvenance() = runBlocking {
        val src = object : FundamentalDataSource {
            override val name = "Test feed"
            override suspend fun snapshot(symbol: String) = DataPoint.Available(snap(listOf(period(0), period(1)), sector = Sector.IT), prov)
            override suspend fun peers(symbol: String, sector: Sector?) = DataPoint.Unavailable("No peers in this fixture")
        }
        val r = FundamentalService.report("T.NS", src, 500.0, Freshness.LIVE, 42L, now)
        assertNotNull(r.analysis)
        assertEquals(Freshness.LIVE, r.provenance!!.freshness)
        assertEquals(42L, r.provenance!!.fetchedAt)
        assertEquals(Sector.IT, r.analysis!!.sector)
    }
}
