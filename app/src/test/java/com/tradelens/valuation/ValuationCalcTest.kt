package com.tradelens.valuation

import com.tradelens.data.model.Candle
import com.tradelens.flow.DataPoint
import com.tradelens.flow.Provenance
import com.tradelens.fundamentals.Basis
import com.tradelens.fundamentals.FinancialPeriod
import com.tradelens.fundamentals.FundamentalSnapshot
import com.tradelens.fundamentals.Sector
import com.tradelens.fundamentals.UnavailableFundamentalSource
import com.tradelens.marketdata.Freshness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Independent checks. Where a value is recomputed, the test re-states the arithmetic step by step rather than calling
 * the code under test. Expected numbers are given in comments so they can be checked by hand.
 */
class ValuationCalcTest {
    private val e = 1e-9
    private val now = 1_780_000_000_000L
    private val day = 86_400_000L
    private val cfg = ValuationConfig()
    private val gov = DataPoint.Unavailable("Not supplied in this fixture")
    private val unavailablePeers = DataPoint.Unavailable("No peers in this fixture")

    private fun snap(annual: List<FinancialPeriod>, sector: Sector? = Sector.MANUFACTURING, marketPrice: Double? = null) =
        FundamentalSnapshot("T.NS", sector, Basis.CONSOLIDATED, "INR", annual, emptyList(), null, null, gov, DataPoint.Unavailable("n/a"), now, marketPrice)

    /**
     * Manufacturing fixture: revenue grows 10% a year for five years (₹1,000 crore in year 0), EBIT margin 15%, EBITDA
     * margin 20% (so depreciation 5%), capex 4% of revenue, working capital absorbs 10% of each rupee of new revenue,
     * 10 crore shares, debt 500, cash 300 (net debt 200), interest 40 (cost of debt 8%).
     */
    private fun mfg(capexKnown: Boolean = true): List<FinancialPeriod> = (0..4).map { k ->
        val rev = 1000.0 * Math.pow(1.1, k.toDouble())
        val prevRev = 1000.0 * Math.pow(1.1, (k - 1).toDouble())
        FinancialPeriod(
            periodEnd = now - (4 - k) * 365 * day, months = 12,
            revenue = rev, ebit = rev * 0.15, ebitda = rev * 0.20, netProfit = rev * 0.15 * 0.75,
            eps = rev * 0.15 * 0.75 * 1e7 / 1e8, shares = 1e8, equity = 2_000.0,
            totalDebt = 500.0, cash = 300.0, interestExpense = 40.0,
            totalAssets = 4_000.0, currentAssets = 1_000.0, currentLiabilities = 500.0,
            capex = if (capexKnown) rev * 0.04 else null,
            operatingCashFlow = rev * 0.13,
            workingCapitalChange = if (k == 0) null else -0.10 * (rev - prevRev),
        )
    }

    /** Independent re-statement of the base-case DCF per share for a given WACC and starting growth. */
    private fun referencePerShare(wacc: Double, g0: Double = 0.10, gT: Double = 0.05): Double {
        val margin = 0.15; val tax = 0.2517; val da = 0.05; val capex = 0.04; val wcI = 0.10
        var prev = 1000.0 * Math.pow(1.1, 4.0)
        var pv = 0.0
        var lastFcf = 0.0
        for (t in 1..5) {
            val g = g0 + (gT - g0) * (t - 1) / 4.0
            val rev = prev * (1 + g)
            lastFcf = rev * margin * (1 - tax) + rev * da - rev * capex - wcI * (rev - prev)
            pv += lastFcf / Math.pow(1 + wacc, t.toDouble())
            prev = rev
        }
        val tv = lastFcf * (1 + gT) / (wacc - gT)
        val ev = pv + tv / Math.pow(1 + wacc, 5.0)
        return (ev - 200.0) * 1e7 / 1e8
    }

    /** WACC at a price for the manufacturing fixture: market-value weights, cost of debt 8%, tax 25.17%. */
    private fun referenceWacc(price: Double): Double {
        val mcap = price * 1e8 / 1e7
        val ke = 0.068 + 1.0 * 0.055
        return (mcap * ke + 500.0 * 0.08 * (1 - 0.2517)) / (mcap + 500.0)
    }

    private fun build(s: FundamentalSnapshot, price: Double?, c: ValuationConfig = cfg, peers: DataPoint<List<FundamentalSnapshot>> = unavailablePeers,
                      candles: List<Candle> = emptyList(), quality: Int? = null, segments: List<Segment> = emptyList()) =
        ValuationCalc.build("T.NS", s, price, c, peers, candles, quality, now, segments)

    // ------------------------------------------------------------------ DCF

    @Test fun dcfMatchesAnIndependentRecalculation() {
        val r = build(snap(mfg()), price = 300.0)
        val dcf = r.dcf!!
        assertEquals(referenceWacc(300.0), dcf.wacc, e)
        assertEquals(referencePerShare(dcf.wacc), dcf.perShare, 1e-6)
        assertEquals(0.10, dcf.startGrowth, e)                                     // 10% five-year CAGR, inside the 3–15% bounds
        assertEquals(0.15, dcf.margin, e)                                          // average of the last three EBIT margins
        assertEquals(0.05, dcf.terminalGrowth, e)
    }

    @Test fun growthFadesFromStartToTerminalGrowth() {
        val years = build(snap(mfg()), price = 300.0).dcf!!.years
        assertEquals(5, years.size)
        assertEquals(0.10, years.first().growth, e)
        assertEquals(0.05, years.last().growth, e)
        assertEquals(0.0875, years[1].growth, e)                                   // 10% − 1.25 pts a year
    }

    @Test fun projectionOnRoundNumbersByHand() {
        // Revenue 1,000, flat margin 20%, tax 25%, depreciation 5%, capex 4%, working capital 10% of new revenue,
        // growth 5% a year, WACC 10%, terminal 5%, three years. Year 1: revenue 1,050, FCF = 1,050 × (0.15 + 0.05 − 0.04)
        // − 0.10 × 50 = 168 − 5 = 163. Year 2: 1,102.5 → 176.4 − 5.25 = 171.15. Year 3: 1,157.625 → 185.22 − 5.5125 = 179.7075.
        val p = ValuationCalc.DcfParams(rev0 = 1000.0, da = 0.05, capex = 0.04, wcIntensity = 0.10, tax = 0.25,
            netDebt = 0.0, shares = 1e7, years = 3)
        val c = ValuationCalc.project(p, Scenario.BASE, start = 0.05, margin = 0.20, wacc = 0.10, terminal = 0.05)!!
        assertEquals(163.0, c.years[0].fcf, 1e-6)
        assertEquals(171.15, c.years[1].fcf, 1e-6)
        assertEquals(179.7075, c.years[2].fcf, 1e-6)
        val pv = 163.0 / 1.1 + 171.15 / 1.21 + 179.7075 / 1.331
        val tv = 179.7075 * 1.05 / 0.05
        assertEquals(pv + tv / 1.331, c.enterpriseValue, 1e-6)
        assertEquals(c.enterpriseValue, c.perShare, 1e-6)                            // 10^7 shares: ₹ per share = ₹ crore
        assertEquals(tv / 1.331 / c.enterpriseValue, c.terminalShare, 1e-9)
    }

    @Test fun netDebtIsDeductedFromEnterpriseValue() {
        val p = ValuationCalc.DcfParams(1000.0, 0.05, 0.04, 0.10, 0.25, 0.0, 1e7, 3)
        val p2 = p.copy(netDebt = 250.0)
        val a = ValuationCalc.project(p, Scenario.BASE, 0.05, 0.20, 0.10, 0.05)!!
        val b = ValuationCalc.project(p2, Scenario.BASE, 0.05, 0.20, 0.10, 0.05)!!
        assertEquals(a.enterpriseValue - 250.0, b.equityValue, 1e-6)
    }

    @Test fun discountRateMustExceedTerminalGrowthByTwoPoints() {
        val p = ValuationCalc.DcfParams(1000.0, 0.05, 0.04, 0.10, 0.25, 0.0, 1e7, 5)
        assertNull(ValuationCalc.project(p, Scenario.BASE, 0.05, 0.20, wacc = 0.069, terminal = 0.055))
        assertNotNull(ValuationCalc.project(p, Scenario.BASE, 0.05, 0.20, wacc = 0.0751, terminal = 0.055))
    }

    @Test fun thinSpreadMakesDcfUnavailableWithAReason() {
        val thin = ValuationConfig(riskFreeRate = 0.03, beta = 0.5, equityRiskPremium = 0.02)
        val dcf = build(snap(mfg()), price = 300.0, c = thin).models.first { it.model == ValuationModel.DCF }
        assertNull(dcf.base)
        assertTrue(dcf.reason.contains("too close to terminal growth"))
    }

    @Test fun missingCapexGivesNoDcfNotAZeroCapex() {
        val r = build(snap(mfg(capexKnown = false)), price = 300.0)
        assertNull(r.dcf)
        assertTrue(r.models.first { it.model == ValuationModel.DCF }.reason.contains("capital expenditure"))
    }

    // ------------------------------------------------------------------ reverse DCF and sensitivity

    @Test fun reverseDcfSolvesTheGrowthThatMatchesThePrice() {
        // The implied growth, put back into the independent recalculation at the same WACC, must give the market price.
        val price = 300.0
        val r = build(snap(mfg()), price = price)
        val implied = r.impliedGrowth!!
        assertEquals(price, referencePerShare(referenceWacc(price), g0 = implied), 1e-4)
        assertEquals(0.10, r.baseGrowth!!, e)
    }

    @Test fun sensitivityMovesTheRightWay() {
        val rows = build(snap(mfg()), price = 300.0).sensitivity.associateBy { it.driver }
        val growth = rows.getValue("Revenue growth")
        assertTrue(growth.fairHigh!! > growth.fairLow!!)
        val margin = rows.getValue("Operating margin")
        assertTrue(margin.fairHigh!! > margin.fairLow!!)
        val wacc = rows.getValue("Discount rate (WACC)")
        assertTrue(wacc.fairHigh!! > wacc.fairLow!!)                                   // fairHigh is at the lower discount rate
        val terminal = rows.getValue("Terminal growth")
        assertTrue(terminal.fairHigh!! > terminal.fairLow!!)
    }

    // ------------------------------------------------------------------ banks and lenders

    /**
     * Bank fixture: net profit 150 on average equity 950 → ROE 15.79%. Latest equity 1,000 over 10 crore shares →
     * book value ₹100. Ke = 6.8% + 5.5% = 12.3%, g = 5% → justified P/B = (15.79 − 5) ÷ (12.3 − 5) = 1.478 → ₹147.79.
     */
    private fun bank(): List<FinancialPeriod> = listOf(
        FinancialPeriod(now - 365 * day, 12, equity = 900.0, shares = 1e8),
        FinancialPeriod(now, 12, revenue = 1_000.0, netProfit = 150.0, equity = 1_000.0, shares = 1e8),
    )

    @Test fun bankValuedOnJustifiedPriceToBook() {
        val r = build(snap(bank(), sector = Sector.BANK), price = 100.0)
        val roe = 150.0 / 950.0
        assertEquals(ValuationModel.JUSTIFIED_PB, r.primary)
        assertEquals((roe - 0.05) / (0.123 - 0.05) * 100.0, r.fairBase!!, 1e-6)      // ≈ 147.79
        assertEquals((roe - 0.02 - 0.04) / (0.123 - 0.04) * 100.0, r.fairBear!!, 1e-6)  // ROE −2 pts, g 4%
        assertEquals((roe + 0.02 - 0.055) / (0.123 - 0.055) * 100.0, r.fairBull!!, 1e-6)
        assertTrue(r.fairBear!! < r.fairBase!! && r.fairBase!! < r.fairBull!!)
    }

    @Test fun bankPremiumAndMarginOfSafety() {
        val r = build(snap(bank(), sector = Sector.BANK), price = 100.0)
        val fair = r.fairBase!!
        assertEquals((100.0 - fair) / fair * 100, r.premiumPct!!, 1e-9)              // negative: below fair value
        assertEquals((fair - 100.0) / fair * 100, r.marginOfSafetyPct!!, 1e-9)        // about 32%
        assertTrue(r.interpretation.valuation.contains("Appears undervalued"))
        assertEquals(fair * 0.75, r.buyBelow!!, 1e-9)
    }

    @Test fun bankDcfAndEvModelsAreNotUsedAndSayWhy() {
        val r = build(snap(bank(), sector = Sector.BANK), price = 100.0)
        assertNull(r.dcf)
        val dcf = r.models.first { it.model == ValuationModel.DCF }
        assertTrue(dcf.reason.startsWith("Not used"))
        assertTrue(r.models.first { it.model == ValuationModel.EV_EBITDA }.reason.startsWith("Not used"))
        assertTrue(r.models.first { it.model == ValuationModel.FCF_YIELD }.reason.startsWith("Not used"))
    }

    @Test fun bankRoeBelowGrowthGivesNoJustifiedValue() {
        // Equity 1,000 and profit 30 on 950 average → ROE 3.2%, below the 5% growth assumption: P/B is not positive.
        val weak = listOf(FinancialPeriod(now - 365 * day, 12, equity = 900.0, shares = 1e8),
            FinancialPeriod(now, 12, netProfit = 30.0, equity = 1_000.0, shares = 1e8))
        val r = build(snap(weak, sector = Sector.BANK), price = 100.0)
        assertNull(r.models.first { it.model == ValuationModel.JUSTIFIED_PB }.base)
    }

    // ------------------------------------------------------------------ relative models

    @Test fun industryPeRunsOnPeerMedianTimesEps() {
        // Peer P/Es: 200/10 = 20, 300/10 = 30, 100/10 = 10 → median 20. Company EPS = 1,464.1 × 0.01125 = ₹16.47.
        val peers = listOf(200.0, 300.0, 100.0).map { pr ->
            FundamentalSnapshot("P$pr", Sector.MANUFACTURING, Basis.CONSOLIDATED, "INR",
                listOf(FinancialPeriod(now, 12, eps = 10.0, shares = 1e8)), emptyList(), null, null, gov, DataPoint.Unavailable("n/a"), now, pr)
        }
        val r = build(snap(mfg()), price = 300.0, peers = DataPoint.Available(peers, Provenance("Test peers", null, null, Freshness.LIVE, true)))
        val eps = mfg().last().eps!!
        assertEquals(20.0 * eps, r.models.first { it.model == ValuationModel.INDUSTRY_PE }.base!!, 1e-9)
    }

    @Test fun industryPeNeedsThreePeersAndSaysSo() {
        val two = listOf(200.0, 300.0).map { pr ->
            FundamentalSnapshot("P$pr", Sector.MANUFACTURING, Basis.CONSOLIDATED, "INR",
                listOf(FinancialPeriod(now, 12, eps = 10.0, shares = 1e8)), emptyList(), null, null, gov, DataPoint.Unavailable("n/a"), now, pr)
        }
        val r = build(snap(mfg()), price = 300.0, peers = DataPoint.Available(two, Provenance("Test peers", null, null, Freshness.LIVE, true)))
        val m = r.models.first { it.model == ValuationModel.INDUSTRY_PE }
        assertNull(m.base)
        assertTrue(m.reason.contains("Needs at least 3 peers"))
    }

    @Test fun historicalPeUsesYearEndPricesAndSkipsLossYears() {
        // FY end prices 150, 300, 500 with EPS 10, 20, 25 → P/E 15, 15, 20. The year with EPS −5 is skipped, not counted.
        val ends = listOf(now - 3 * 365 * day, now - 2 * 365 * day, now - 365 * day, now)
        val annual = listOf(
            FinancialPeriod(ends[0], 12, eps = 10.0), FinancialPeriod(ends[1], 12, eps = 20.0),
            FinancialPeriod(ends[2], 12, eps = 25.0), FinancialPeriod(ends[3], 12, eps = -5.0),
        )
        val candles = listOf(Candle(ends[0], 150.0, 150.0, 150.0, 150.0, 1), Candle(ends[1], 300.0, 300.0, 300.0, 300.0, 1),
            Candle(ends[2], 500.0, 500.0, 500.0, 500.0, 1), Candle(ends[3], 90.0, 90.0, 90.0, 90.0, 1))
        assertEquals(listOf(15.0, 15.0, 20.0), ValuationCalc.historicalPe(annual, candles))
    }

    @Test fun historicalPeIgnoresPricesFarFromTheYearEnd() {
        val annual = listOf(FinancialPeriod(now, 12, eps = 10.0))
        val stale = listOf(Candle(now - 30 * day, 150.0, 150.0, 150.0, 150.0, 1))
        assertTrue(ValuationCalc.historicalPe(annual, stale).isEmpty())
    }

    @Test fun pegUsesEpsGrowthBoundedBetween5And25() {
        // EPS grows 10% a year, so the growth figure is 10: fair P/E 10 × latest EPS.
        val r = build(snap(mfg()), price = 300.0)
        val eps = mfg().last().eps!!
        assertEquals(eps * 10.0, r.models.first { it.model == ValuationModel.PEG }.base!!, 1e-6)
    }

    @Test fun sumOfPartsByHand() {
        // Segments: 100 × 10 = 1,000 and 50 × 8 = 400 → EV 1,400; net debt 200 → equity 1,200; 1 crore shares → ₹1,200 a share.
        assertEquals(1_200.0, ValuationCalc.sumOfParts(listOf(Segment("A", 100.0, 10.0), Segment("B", 50.0, 8.0)), 200.0, 1e7)!!, e)
        assertNull(ValuationCalc.sumOfParts(emptyList(), 200.0, 1e7))
    }

    // ------------------------------------------------------------------ zones and configuration

    @Test fun accumulationZonesAreOrderedAndClassify() {
        // Fair 100, bear 80, bull 130, margin 25%: deep line 62.5 (≤ bear), buy line 75, fair band to 112.5, bull 130.
        val z = ValuationCalc.accumulationZones(100.0, 80.0, 130.0, 0.25)
        val bounds = z.map { it.upTo ?: Double.MAX_VALUE }
        assertTrue(bounds.zipWithNext().all { (a, b) -> a < b })
        assertEquals(AccumulationZone.VERY_ATTRACTIVE, ValuationCalc.zoneOf(60.0, z))
        assertEquals(AccumulationZone.ATTRACTIVE, ValuationCalc.zoneOf(70.0, z))
        assertEquals(AccumulationZone.FAIR, ValuationCalc.zoneOf(100.0, z))
        assertEquals(AccumulationZone.EXPENSIVE, ValuationCalc.zoneOf(120.0, z))
        assertEquals(AccumulationZone.HIGHLY_EXPENSIVE, ValuationCalc.zoneOf(140.0, z))
    }

    @Test fun zonesStayOrderedWhenBearIsAboveTheBuyLine() {
        // Bear 95 is above the buy line (75) and the bull case (110) is below the fair band's top (112.5): the expensive
        // band is then empty, which is allowed, but the boundaries never run backwards.
        val z = ValuationCalc.accumulationZones(100.0, 95.0, 110.0, 0.25)
        val bounds = z.map { it.upTo ?: Double.MAX_VALUE }
        assertTrue(bounds.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(AccumulationZone.VERY_ATTRACTIVE, ValuationCalc.zoneOf(62.0, z))
        assertEquals(AccumulationZone.ATTRACTIVE, ValuationCalc.zoneOf(70.0, z))
    }

    @Test fun marginOfSafetyPreferenceIsValidated() {
        assertThrows(IllegalArgumentException::class.java) { ValuationConfig(marginOfSafety = 0.7) }
        assertThrows(IllegalArgumentException::class.java) { ValuationConfig(taxRate = 0.9) }
    }

    @Test fun assumptionsAreListedWithTheirSource() {
        val r = build(snap(mfg()), price = 300.0)
        assertTrue(r.assumptions.any { it.name == "Revenue growth, year 1 (base)" && it.source == AssumptionSource.DERIVED })
        assertTrue(r.assumptions.any { it.name == "Cost of equity" && it.source == AssumptionSource.ASSUMED })
        assertTrue(r.assumptions.all { it.why.isNotBlank() })
    }

    @Test fun unavailableFundamentalsGiveAReasonNotAReport() = runBlocking {
        val out = ValuationService.report("T.NS", UnavailableFundamentalSource, 300.0, emptyList(), cfg, now, null)
        assertNull(out.report)
        assertTrue(out.unavailableReason!!.isNotBlank())
    }
}
