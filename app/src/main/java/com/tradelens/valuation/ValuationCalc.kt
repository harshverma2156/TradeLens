package com.tradelens.valuation

import com.tradelens.data.model.Candle
import com.tradelens.flow.DataPoint
import com.tradelens.fundamentals.FinancialPeriod
import com.tradelens.fundamentals.FundamentalDataSource
import com.tradelens.fundamentals.FundamentalSnapshot
import com.tradelens.fundamentals.Sector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Valuation models. Every per-share figure is a model estimate built from reported statements and the assumptions
 * listed with it. Null means the model is not used for the sector or lacks an input; the reason is always given.
 */
object ValuationCalc {

    private const val CRORE = 1e7
    private const val DAY = 86_400_000L
    private const val MIN_PEERS = 3
    /** The discount rate must exceed the terminal growth rate by at least this much, or the terminal value explodes. */
    private const val MIN_SPREAD = 0.02

    /** Terminal growth per scenario. India's long-run nominal growth is about 9–11%, so these are below GDP. */
    private val TERMINAL = mapOf(Scenario.BEAR to 0.04, Scenario.BASE to 0.05, Scenario.BULL to 0.055)

    private val BANK_LIKE = setOf(Sector.BANK, Sector.NBFC)
    private val DIVIDEND_SECTORS = setOf(Sector.BANK, Sector.NBFC, Sector.ENERGY_UTILITY, Sector.FMCG)

    // ------------------------------------------------------------------ small helpers

    private fun avg(a: Double?, b: Double?): Double? = if (a != null && b != null) (a + b) / 2 else a

    /** Median of a peer statistic, or null when fewer than [MIN_PEERS] peers supply it. Never an empty-list crash. */
    private fun medianOrNull(xs: List<Double>): Double? = if (xs.size < MIN_PEERS) null else median(xs)

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** Revenue CAGR over up to five years as a decimal, or null without a positive start and end. */
    fun revenueCagr(a: List<FinancialPeriod>): Double? {
        val i = a.lastIndex
        val k = min(5, i)
        if (k < 2) return null
        val start = a[i - k].revenue ?: return null
        val end = a[i].revenue ?: return null
        if (start <= 0 || end <= 0) return null
        return (end / start).pow(1.0 / k) - 1
    }

    /** Return on equity per year, as a decimal: net profit ÷ average equity. */
    fun roeHistory(a: List<FinancialPeriod>): List<Double> = a.indices.mapNotNull { i ->
        val p = a[i]
        val np = p.netProfit ?: return@mapNotNull null
        val eq = avg(p.equity, a.getOrNull(i - 1)?.equity) ?: return@mapNotNull null
        if (eq <= 0) null else np / eq
    }

    /**
     * Historical P/E: the price on or just before each fiscal year end ÷ that year's EPS. Years without a price within
     * ten days, or with EPS ≤ 0, are left out rather than counted as zero.
     */
    fun historicalPe(annual: List<FinancialPeriod>, candles: List<Candle>): List<Double> = annual.mapNotNull { p ->
        val eps = p.eps ?: return@mapNotNull null
        if (eps <= 0) return@mapNotNull null
        val c = candles.lastOrNull { it.time <= p.periodEnd } ?: return@mapNotNull null
        if (p.periodEnd - c.time > 10 * DAY) null else c.close / eps
    }

    // ------------------------------------------------------------------ DCF

    internal data class DcfParams(
        val rev0: Double, val da: Double, val capex: Double, val wcIntensity: Double, val tax: Double,
        val netDebt: Double, val shares: Double, val years: Int,
    )

    /**
     * Free-cash-flow-to-firm projection. Growth fades linearly from [start] in year 1 to [terminal] in the last year.
     * FCF = EBIT × (1 − tax) + depreciation − capex − increase in working capital. Terminal value is a Gordon growth
     * value on the final-year FCF. Returns null when the discount rate does not exceed terminal growth by [MIN_SPREAD].
     */
    internal fun project(p: DcfParams, scenario: Scenario, start: Double, margin: Double, wacc: Double, terminal: Double): DcfCase? {
        if (wacc - terminal < MIN_SPREAD) return null
        var prev = p.rev0
        var pvSum = 0.0
        val years = ArrayList<DcfYear>()
        for (t in 1..p.years) {
            val g = if (p.years == 1) start else start + (terminal - start) * (t - 1) / (p.years - 1)
            val rev = prev * (1 + g)
            val ebit = rev * margin
            val fcf = ebit * (1 - p.tax) + rev * p.da - rev * p.capex - p.wcIntensity * (rev - prev)
            val pv = fcf / (1 + wacc).pow(t.toDouble())
            pvSum += pv
            years += DcfYear(t, g, rev, ebit, fcf, pv)
            prev = rev
        }
        val lastFcf = years.last().fcf
        val tv = lastFcf * (1 + terminal) / (wacc - terminal)
        val pvTv = tv / (1 + wacc).pow(p.years.toDouble())
        val ev = pvSum + pvTv
        val equity = ev - p.netDebt
        return DcfCase(scenario, start, terminal, margin, wacc, years, tv, pvTv, ev, p.netDebt, equity, equity * CRORE / p.shares)
    }

    // ------------------------------------------------------------------ zones

    /** Accumulation bands from the fair value, the bear and bull cases and the margin-of-safety preference. */
    fun accumulationZones(fair: Double, bear: Double?, bull: Double?, marginOfSafety: Double): List<ZoneBand> {
        // Very attractive: at or below the bear case AND at least 1.5× the margin of safety below fair value.
        // Attractive: at or below the buy line (fair × (1 − margin)). The bands are always in order.
        val deepLine = fair * (1 - 1.5 * marginOfSafety)
        val buyLine = fair * (1 - marginOfSafety)
        val z1 = min(bear ?: deepLine, deepLine)
        val z2 = buyLine
        val z3 = fair * (1 + marginOfSafety / 2)
        val z4 = max(bull ?: z3, z3)
        return listOf(
            ZoneBand(AccumulationZone.VERY_ATTRACTIVE, z1),
            ZoneBand(AccumulationZone.ATTRACTIVE, z2),
            ZoneBand(AccumulationZone.FAIR, z3),
            ZoneBand(AccumulationZone.EXPENSIVE, z4),
            ZoneBand(AccumulationZone.HIGHLY_EXPENSIVE, null),
        )
    }

    fun zoneOf(price: Double, zones: List<ZoneBand>): AccumulationZone =
        zones.firstOrNull { it.upTo == null || price <= it.upTo }?.zone ?: AccumulationZone.HIGHLY_EXPENSIVE

    // ------------------------------------------------------------------ sum of the parts

    /** EV = Σ(segment EBITDA × multiple); equity = EV − net debt; per share on [shares]. */
    fun sumOfParts(segments: List<Segment>, netDebt: Double, shares: Double): Double? {
        if (segments.isEmpty() || shares <= 0) return null
        val ev = segments.sumOf { it.ebitda * it.multiple }
        return (ev - netDebt) * CRORE / shares
    }

    // ------------------------------------------------------------------ report

    fun build(
        symbol: String,
        s: FundamentalSnapshot,
        price: Double?,
        cfg: ValuationConfig,
        peers: DataPoint<List<FundamentalSnapshot>>,
        candles: List<Candle>,
        qualityScore: Int?,
        now: Long,
        segments: List<Segment> = emptyList(),
    ): ValuationReport {
        val sector = s.sector
        val annual = s.annual
        val latest = annual.lastOrNull()
        val assumptions = mutableListOf<Assumption>()
        val notUsed = mutableMapOf<ValuationModel, String>()

        val shares = latest?.shares?.takeIf { it > 0 }
        val debt = latest?.totalDebt
        val cash = latest?.cash
        val netDebt = if (debt != null && cash != null) debt - cash else null
        val mcap = if (price != null && shares != null) price * shares / CRORE else null
        val eps = latest?.eps?.takeIf { it > 0 }
        val ke = cfg.riskFreeRate + cfg.beta * cfg.equityRiskPremium
        assumptions += Assumption("Cost of equity", pct(ke), AssumptionSource.ASSUMED,
            "Risk-free ${pct(cfg.riskFreeRate)} + beta ${fmt(cfg.beta)} × equity risk premium ${pct(cfg.equityRiskPremium)}. Beta is not measured by the app.")

        // ---- sector applicability
        if (sector in BANK_LIKE) {
            notUsed[ValuationModel.DCF] = "Not used for ${sector!!.label.lowercase()}: a bank's free cash flow mixes lending, deposit and funding flows, so an enterprise-value DCF misstates it."
            notUsed[ValuationModel.EV_EBITDA] = "Not used for banks and lenders: debt is their raw material, so enterprise value and EBITDA do not describe them."
            notUsed[ValuationModel.FCF_YIELD] = "Not used for banks and lenders: their operating cash flow follows loan growth, not earnings power."
        }
        if (sector !in BANK_LIKE) notUsed[ValuationModel.JUSTIFIED_PB] = "Not used here: book value describes a bank's or lender's earning assets, not an asset-light business's earning power."
        if (sector !in DIVIDEND_SECTORS) notUsed[ValuationModel.DDM] = "Not used for this sector: dividends are not the main way it creates value for shareholders."
        if (segments.isEmpty()) notUsed[ValuationModel.SOTP] = "Needs segment-level EBITDA and multiples. Segment data is not in the connected feed."

        // ---- WACC
        val kd = latest?.interestExpense?.let { i -> if (debt != null && debt > 0 && i >= 0) (i / debt).coerceIn(0.05, 0.15) else null }
        val kdUsed = kd ?: cfg.costOfDebtFallback
        if (kd == null) assumptions += Assumption("Cost of debt", pct(kdUsed), AssumptionSource.ASSUMED, "Interest expense and debt are not both reported.")
        else assumptions += Assumption("Cost of debt", pct(kd), AssumptionSource.DERIVED, "Interest ÷ total debt, bounded to 5–15%.")
        val wacc: Double? = when {
            mcap == null -> null
            debt == null -> null
            mcap + debt <= 0 -> null
            else -> (mcap * ke + debt * kdUsed * (1 - cfg.taxRate)) / (mcap + debt)
        }
        if (wacc != null) assumptions += Assumption("Discount rate (WACC)", pct(wacc), AssumptionSource.DERIVED,
            "Market-value weights: equity ₹${fmt0(mcap!!)} Cr, debt ₹${fmt0(debt!!)} Cr; tax ${pct(cfg.taxRate)} applied to debt.")

        // ---- operating drivers from history
        val cagr = revenueCagr(annual)
        val g0 = cagr?.coerceIn(0.03, 0.15)
        if (cagr != null) assumptions += Assumption("Revenue growth, year 1 (base)", pct(g0!!), AssumptionSource.DERIVED,
            "${pct(cagr)} five-year revenue CAGR, bounded to 3–15%. It fades to terminal growth by the final year. Bear: −5 pts. Bull: +3 pts, max 18%.")
        val margins = annual.takeLast(3).mapNotNull { p -> val e = p.ebit; val r = p.revenue; if (e != null && r != null && r > 0) e / r else null }
        val m0 = if (margins.isNotEmpty()) margins.average() else null
        if (m0 != null) assumptions += Assumption("Operating (EBIT) margin (base)", pct(m0), AssumptionSource.DERIVED,
            "Average of the last ${margins.size} reported years. Bear: −2 pts. Bull: +2 pts.")
        assumptions += Assumption("Tax rate", pct(cfg.taxRate), AssumptionSource.ASSUMED, "Indian corporate rate incl. surcharge and cess. Not derived from reported tax.")

        val capexRatios = annual.takeLast(3).mapNotNull { p -> val c = p.capex; val r = p.revenue; if (c != null && r != null && r > 0) c / r else null }
        val capex = if (capexRatios.isNotEmpty()) capexRatios.average() else null
        if (capex != null) assumptions += Assumption("Capital expenditure, % of revenue", pct(capex), AssumptionSource.DERIVED, "Average of the last ${capexRatios.size} reported years.")
        val daRatios = annual.takeLast(3).mapNotNull { p -> val e = p.ebitda; val b = p.ebit; val r = p.revenue; if (e != null && b != null && r != null && r > 0) (e - b) / r else null }
        val da = if (daRatios.isNotEmpty()) daRatios.average() else null
        if (da != null) assumptions += Assumption("Depreciation, % of revenue", pct(da), AssumptionSource.DERIVED, "EBITDA − EBIT as a share of revenue, averaged over ${daRatios.size} years.")
        val wcRatios = annual.zipWithNext().mapNotNull { (a, b) ->
            val wc = b.workingCapitalChange; val dRev = (b.revenue ?: return@mapNotNull null) - (a.revenue ?: return@mapNotNull null)
            if (wc != null && dRev > 0) -wc / dRev else null
        }
        val wcI = if (wcRatios.isNotEmpty()) wcRatios.average() else cfg.workingCapitalIntensity
        assumptions += if (wcRatios.isNotEmpty()) Assumption("Working capital, % of revenue growth", pct(wcI), AssumptionSource.DERIVED,
            "Cash absorbed per rupee of extra revenue, averaged over ${wcRatios.size} years.")
        else Assumption("Working capital, % of revenue growth", pct(wcI), AssumptionSource.ASSUMED, "No reported working-capital history with rising revenue. Default used.")
        assumptions += Assumption("Projection", "${cfg.projectionYears} years, then Gordon terminal value", AssumptionSource.ASSUMED, "Standard two-stage DCF.")
        assumptions += Assumption("Terminal growth", TERMINAL.entries.joinToString(" / ") { "${it.key.label.lowercase()} ${pct(it.value)}" }, AssumptionSource.ASSUMED,
            "Long-run nominal growth below India's expected nominal GDP growth.")

        // ---- DCF
        val missingDcf = listOfNotNull(
            if (wacc == null) "discount rate (needs a price, share count and total debt)" else null,
            if (cagr == null) "revenue history (needs five years, or three with positive ends)" else null,
            if (m0 == null) "EBIT and revenue" else null,
            if (capex == null) "capital expenditure" else null,
            if (da == null) "EBITDA and EBIT (for depreciation)" else null,
            if (netDebt == null) "total debt and cash" else null,
            if (shares == null) "share count" else null,
            if (latest?.revenue == null) "latest revenue" else null,
        )
        val dcfCases = mutableMapOf<Scenario, DcfCase>()
        var dcfReason: String? = notUsed[ValuationModel.DCF]
        if (dcfReason == null) {
            if (missingDcf.isNotEmpty()) dcfReason = "Missing: ${missingDcf.joinToString(", ")}."
            else {
                val params = DcfParams(latest!!.revenue!!, da!!, capex!!, wcI, cfg.taxRate, netDebt!!, shares!!.toDouble(), cfg.projectionYears)
                for (sc in Scenario.values()) {
                    val start = when (sc) {
                        Scenario.BEAR -> max(0.0, g0!! - 0.05)
                        Scenario.BASE -> g0!!
                        Scenario.BULL -> min(0.18, g0!! + 0.03)
                    }
                    val margin = when (sc) { Scenario.BEAR -> m0!! - 0.02; Scenario.BASE -> m0!!; Scenario.BULL -> m0!! + 0.02 }
                    project(params, sc, start, margin, wacc!!, TERMINAL.getValue(sc))?.let { dcfCases[sc] = it }
                }
                if (dcfCases.size < Scenario.values().size) dcfReason = "The discount rate is too close to terminal growth (needs a ${pct(MIN_SPREAD)} gap in every scenario)."
            }
        }

        val dcfBase = dcfCases[Scenario.BASE]
        val models = mutableListOf<ModelValue>()
        models += if (dcfReason != null) ModelValue(ValuationModel.DCF, null, null, null, dcfReason, "")
        else ModelValue(ValuationModel.DCF, dcfCases[Scenario.BEAR]?.perShare, dcfBase?.perShare, dcfCases[Scenario.BULL]?.perShare, "",
            "Enterprise value ₹${fmt0(dcfBase!!.enterpriseValue)} Cr, equity ₹${fmt0(dcfBase.equityValue)} Cr. Terminal value is ${pct(dcfBase.terminalShare)} of enterprise value.")

        // ---- justified P/B (banks and lenders)
        val roeHist = roeHistory(annual)
        val roe0 = if (roeHist.isNotEmpty()) roeHist.takeLast(3).average() else null
        val bvps = if (latest?.equity != null && shares != null) latest.equity * CRORE / shares else null
        models += modelFor(ValuationModel.JUSTIFIED_PB, notUsed) {
            when {
                latest?.equity == null -> ValueReason("Equity not reported.")
                shares == null -> ValueReason("Share count not reported.")
                latest.equity <= 0 -> ValueReason("Negative equity: justified P/B is not meaningful.")
                roe0 == null -> ValueReason("Return on equity needs reported net profit and equity.")
                else -> {
                    val values = Scenario.values().associateWith { sc ->
                        val roe = roe0 + when (sc) { Scenario.BEAR -> -0.02; Scenario.BASE -> 0.0; Scenario.BULL -> 0.02 }
                        val g = TERMINAL.getValue(sc)
                        if (roe <= g || ke - g < MIN_SPREAD) null else (roe - g) / (ke - g) * bvps!!
                    }
                    ValueReason(null, values, "Justified P/B = (ROE − g) ÷ (Ke − g), with ROE ${pct(roe0)} (average of ${min(3, roeHist.size)} years). Book value per share ₹${fmt(bvps!!)}.")
                }
            }
        }
        assumptions += Assumption("Sustainable ROE (banks and lenders)", roe0?.let { pct(it) } ?: "unavailable", AssumptionSource.DERIVED,
            "Net profit ÷ average equity, averaged over up to three years. Bear −2 pts, bull +2 pts.")

        // ---- DDM
        models += modelFor(ValuationModel.DDM, notUsed) {
            val dps = s.dividendPerShare
            when {
                dps == null -> ValueReason("Dividend per share not reported.")
                eps == null -> ValueReason("Payout ratio needs positive EPS.")
                roe0 == null -> ValueReason("Sustainable growth needs reported ROE.")
                else -> {
                    val payout = dps / eps
                    val sustainable = roe0 * (1 - payout)
                    val values = Scenario.values().associateWith { sc ->
                        val g = min(TERMINAL.getValue(sc), sustainable)
                        if (g >= ke - MIN_SPREAD) null else dps * (1 + g) / (ke - g)
                    }
                    ValueReason(null, values, "Payout ${pct(payout)}; growth = min(terminal growth, ROE × (1 − payout) = ${pct(sustainable)}).")
                }
            }
        }

        // ---- relative models
        val peerList = (peers as? DataPoint.Available)?.value.orEmpty()
        val peerSource = peers
        val peerPe = peerList.mapNotNull { p -> val pr = p.marketPrice; val e = p.annual.lastOrNull()?.eps; if (pr != null && e != null && e > 0) pr / e else null }
        val peerEv = peerList.mapNotNull { p ->
            val last = p.annual.lastOrNull() ?: return@mapNotNull null
            val pr = p.marketPrice ?: return@mapNotNull null
            val sh = last.shares ?: return@mapNotNull null
            val d = last.totalDebt ?: return@mapNotNull null
            val c = last.cash ?: return@mapNotNull null
            val e = last.ebitda ?: return@mapNotNull null
            if (e <= 0) null else (pr * sh / CRORE + d - c) / e
        }
        val peerPb = peerList.mapNotNull { p ->
            val last = p.annual.lastOrNull() ?: return@mapNotNull null
            val pr = p.marketPrice ?: return@mapNotNull null
            val eq = last.equity ?: return@mapNotNull null
            val sh = last.shares ?: return@mapNotNull null
            if (eq <= 0) null else pr / (eq * CRORE / sh)
        }
        val peerReason = when (peerSource) {
            is DataPoint.Unavailable -> peerSource.reason
            is DataPoint.Available -> if (peerList.size < MIN_PEERS) "Needs at least $MIN_PEERS peers; ${peerList.size} supplied." else null
        }
        val peerPeReason = peerReason ?: if (peerPe.size < MIN_PEERS) "Needs at least $MIN_PEERS peers with a share price and positive EPS; ${peerPe.size} usable." else null

        val historicalPes = historicalPe(annual, candles)
        models += if (historicalPes.size < 3) ModelValue(ValuationModel.HISTORICAL_PE, null, null, null,
            "Needs at least three fiscal years with a price and positive EPS; ${historicalPes.size} available.", "")
        else if (eps == null) ModelValue(ValuationModel.HISTORICAL_PE, null, null, null, "Latest EPS is zero or negative.", "")
        else {
            val med = median(historicalPes)
            ModelValue(ValuationModel.HISTORICAL_PE, null, med * eps, null, "",
                "Median of ${historicalPes.size} year-end P/Es (${fmt(historicalPes.min())}–${fmt(historicalPes.max())}) × latest EPS ₹${fmt(eps)}.")
        }

        models += when {
            peerPeReason != null -> ModelValue(ValuationModel.INDUSTRY_PE, null, null, null, peerPeReason, "")
            eps == null -> ModelValue(ValuationModel.INDUSTRY_PE, null, null, null, "Latest EPS is zero or negative.", "")
            else -> ModelValue(ValuationModel.INDUSTRY_PE, null, median(peerPe) * eps, null, "",
                "Median peer P/E ${fmt(median(peerPe))} from ${peerPe.size} peers with a price, × latest EPS.")
        }

        models += modelFor(ValuationModel.EV_EBITDA, notUsed) {
            when {
                peerReason != null -> ValueReason(peerReason)
                peerEv.size < MIN_PEERS -> ValueReason("Needs at least $MIN_PEERS peers with price, debt, cash and EBITDA; ${peerEv.size} usable.")
                netDebt == null -> ValueReason("Total debt and cash not reported.")
                shares == null -> ValueReason("Share count not reported.")
                latest?.ebitda == null || latest.ebitda <= 0 -> ValueReason("Latest EBITDA is missing or not positive.")
                else -> {
                    val med = median(peerEv)
                    ValueReason(null, base = (med * latest.ebitda - netDebt) * CRORE / shares,
                        note = "Median peer EV/EBITDA ${fmt(med)} × EBITDA ₹${fmt0(latest.ebitda)} Cr, less net debt.")
                }
            }
        }

        models += modelFor(ValuationModel.PEG, notUsed) {
            val epsCagr = cagrOf(annual) { it.eps }
            when {
                eps == null -> ValueReason("Latest EPS is zero or negative.")
                epsCagr == null -> ValueReason("EPS growth needs three years of positive EPS.")
                epsCagr <= 0 -> ValueReason("EPS is not growing: PEG is not meaningful.")
                else -> {
                    val growthPct = (epsCagr * 100).coerceIn(5.0, 25.0)
                    ValueReason(null, base = eps * growthPct,
                        note = "PEG of 1: P/E set equal to EPS growth of ${fmt(growthPct)}% (bounded 5–25%).")
                }
            }
        }

        models += modelFor(ValuationModel.FCF_YIELD, notUsed) {
            val fcf = latest?.operatingCashFlow?.let { o -> latest.capex?.let { c -> o - c } }
            when {
                fcf == null -> ValueReason("Operating cash flow or capex not reported.")
                shares == null -> ValueReason("Share count not reported.")
                ke - 0.05 <= 0 -> ValueReason("Cost of equity too low for the yield model.")
                else -> {
                    val fcfPs = fcf * CRORE / shares
                    val values = Scenario.values().associateWith { sc ->
                        val g = TERMINAL.getValue(sc)
                        if (ke - g < MIN_SPREAD) null else fcfPs * (1 + g) / (ke - g)
                    }
                    ValueReason(null, values, "FCF per share ₹${fmt(fcfPs)} capitalised at Ke − g (a perpetuity, so it ignores the fade in growth).")
                }
            }
        }

        models += if (segments.isEmpty()) ModelValue(ValuationModel.SOTP, null, null, null, notUsed.getValue(ValuationModel.SOTP), "")
        else {
            val v = if (netDebt != null && shares != null) sumOfParts(segments, netDebt, shares.toDouble()) else null
            ModelValue(ValuationModel.SOTP, null, v, null, if (v == null) "Needs net debt and share count." else "",
                "Sum of ${segments.size} segments at supplied multiples.")
        }

        // ---- primary model and fair values
        val primary = if (sector in BANK_LIKE) ValuationModel.JUSTIFIED_PB else ValuationModel.DCF
        val primaryValue = models.first { it.model == primary }
        val fairBear = primaryValue.bear
        val fairBase = primaryValue.base
        val fairBull = primaryValue.bull
        val primaryNote = if (primary == ValuationModel.DCF) "Primary model: DCF, because the business generates free cash flow that can be separated from its financing."
        else "Primary model: justified P/B, because the sector's value is set by return on equity against book value."

        val crossValues = models.filter { it.model != primary }.mapNotNull { it.base } + listOfNotNull(fairBear, fairBull)
        val allValues = crossValues + listOfNotNull(fairBase)
        val rangeLow = allValues.minOrNull()
        val rangeHigh = allValues.maxOrNull()

        val premium = if (price != null && fairBase != null && fairBase > 0) (price - fairBase) / fairBase * 100 else null
        val mos = if (price != null && fairBase != null && fairBase > 0) (fairBase - price) / fairBase else null
        val buyBelow = fairBase?.takeIf { it > 0 }?.let { it * (1 - cfg.marginOfSafety) }
        val zones = fairBase?.takeIf { it > 0 }?.let { accumulationZones(it, fairBear, fairBull, cfg.marginOfSafety) } ?: emptyList()
        val currentZone = if (price != null && zones.isNotEmpty()) zoneOf(price, zones) else null

        // ---- sensitivity and reverse DCF on the base DCF
        val sensitivity = mutableListOf<SensitivityRow>()
        var implied: Double? = null
        val baseGrowth = g0
        if (dcfReason == null && dcfBase != null) {
            val params = DcfParams(latest!!.revenue!!, da!!, capex!!, wcI, cfg.taxRate, netDebt!!, shares!!.toDouble(), cfg.projectionYears)
            val gb = g0!!; val mb = m0!!; val wb = wacc!!; val tb = TERMINAL.getValue(Scenario.BASE)
            fun v(start: Double = gb, margin: Double = mb, w: Double = wb, t: Double = tb): Double? =
                project(params, Scenario.BASE, start, margin, w, t)?.perShare
            fun row(driver: String, lo: String, hi: String, a: Double?, b: Double?) {
                val vals = listOfNotNull(a, b)
                sensitivity += SensitivityRow(driver, lo, hi, vals.minOrNull(), vals.maxOrNull(), dcfBase.perShare)
            }
            row("Revenue growth", "−2 pts", "+2 pts", v(start = max(0.0, gb - 0.02)), v(start = gb + 0.02))
            row("Operating margin", "−2 pts", "+2 pts", v(margin = mb - 0.02), v(margin = mb + 0.02))
            row("Discount rate (WACC)", "+1 pt", "−1 pt", v(w = wb + 0.01), v(w = wb - 0.01))
            row("Terminal growth", "−1 pt", "+1 pt", v(t = tb - 0.01), v(t = tb + 0.01))
            if (price != null) implied = impliedStartGrowth(price) { g -> v(start = g) }
        }

        // ---- peers
        val peerRows = if (peerReason == null) {
            buildList {
                add(PeerComparisonRow("P/E (trailing)", price?.let { p -> eps?.let { p / it } }, medianOrNull(peerPe), peerPe.size))
                add(PeerComparisonRow("P/B", if (price != null && latest?.equity != null && shares != null && latest.equity > 0) price / (latest.equity * CRORE / shares) else null, medianOrNull(peerPb), peerPb.size))
                add(PeerComparisonRow("EV/EBITDA", if (mcap != null && netDebt != null && latest?.ebitda != null && latest.ebitda > 0) (mcap + netDebt) / latest.ebitda else null, medianOrNull(peerEv), peerEv.size))
            }.let { DataPoint.Available(it, peerSource.let { p -> (p as DataPoint.Available).source }) }
        } else DataPoint.Unavailable(peerReason)

        val interp = interpretation(price, fairBase, mos, cfg.marginOfSafety, implied, baseGrowth, qualityScore,
            dcfBase, sensitivity.sortedByDescending { it.swing ?: 0.0 }, models, notUsed, assumptions)

        return ValuationReport(
            symbol = symbol, computedAt = now, sector = sector, price = price, primary = primary, primaryNote = primaryNote,
            assumptions = assumptions, dcf = dcfBase, models = models,
            fairBear = fairBear, fairBase = fairBase, fairBull = fairBull, rangeLow = rangeLow, rangeHigh = rangeHigh,
            premiumPct = premium, marginOfSafetyPct = mos?.let { it * 100 }, marginOfSafetyPreference = cfg.marginOfSafety,
            buyBelow = buyBelow, zones = zones, currentZone = currentZone,
            sensitivity = sensitivity.sortedByDescending { it.swing ?: 0.0 }, impliedGrowth = implied, baseGrowth = baseGrowth,
            peerComparison = peerRows, interpretation = interp,
            dataNote = "Model estimates from reported statements (₹ crore) and the assumptions above. The market price is from the quote feed at its time. " +
                "No analyst consensus or management guidance is included.",
        )
    }

    // ------------------------------------------------------------------ model plumbing

    private class ValueReason(val reason: String?, val values: Map<Scenario, Double?>? = null, val note: String = "", val base: Double? = null)

    /** Wraps a model: a not-used reason short-circuits; otherwise the calculation runs and its scenarios are read back. */
    private fun modelFor(model: ValuationModel, notUsed: Map<ValuationModel, String>, calc: () -> ValueReason): ModelValue {
        notUsed[model]?.let { return ModelValue(model, null, null, null, it, "") }
        val r = calc()
        if (r.reason != null) return ModelValue(model, null, null, null, r.reason, "")
        val values = r.values
        return ModelValue(model, values?.get(Scenario.BEAR), values?.get(Scenario.BASE) ?: r.base, values?.get(Scenario.BULL), "", r.note)
    }

    private fun cagrOf(a: List<FinancialPeriod>, f: (FinancialPeriod) -> Double?): Double? {
        val i = a.lastIndex
        val k = min(5, i)
        if (k < 2) return null
        val start = f(a[i - k]) ?: return null
        val end = f(a[i]) ?: return null
        if (start <= 0 || end <= 0) return null
        return (end / start).pow(1.0 / k) - 1
    }

    /**
     * Reverse DCF: the starting growth at which the base case equals the market price. Bisection on a bounded range;
     * returns null when the price lies outside what growth between −15% and +45% can justify.
     */
    private fun impliedStartGrowth(price: Double, value: (Double) -> Double?): Double? {
        var lo = -0.15
        var hi = 0.45
        val vLo = value(lo) ?: return null
        val vHi = value(hi) ?: return null
        if (price < vLo || price > vHi) return null
        repeat(80) {
            val mid = (lo + hi) / 2
            val vMid = value(mid) ?: return null
            if (vMid < price) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun interpretation(
        price: Double?, fair: Double?, mos: Double?, pref: Double, implied: Double?, baseGrowth: Double?, quality: Int?,
        dcf: DcfCase?, sensitivity: List<SensitivityRow>, models: List<ModelValue>, notUsed: Map<ValuationModel, String>,
        assumptions: List<Assumption>,
    ): Interpretation {
        val valuation = when {
            price == null || fair == null || mos == null -> "A fair value could not be estimated, so no view on valuation is given."
            mos >= pref -> "Appears undervalued: the price is ${pct(mos)} below base-case fair value, more than the ${pct(pref)} margin of safety you require."
            mos >= 0 -> "Below base-case fair value by ${pct(mos)}, but not by the ${pct(pref)} margin of safety you require before buying."
            else -> "Trades ${pct(-mos)} above base-case fair value. It is not undervalued on these assumptions."
        }
        val growth = when {
            implied == null || baseGrowth == null -> "The growth the price implies could not be solved within a plausible range (or the DCF is unavailable)."
            implied > baseGrowth + 0.005 -> "The price already assumes starting growth of ${pct(implied)}, above the base case of ${pct(baseGrowth)}. Growth is priced in: the company must beat the base case to earn a return from here."
            implied < baseGrowth - 0.005 -> "The price assumes only ${pct(implied)} starting growth, below the base case of ${pct(baseGrowth)}. The market expects less than the base case."
            else -> "The price and the base case agree on growth (${pct(implied)} implied, ${pct(baseGrowth)} assumed)."
        }
        val fundamentals = when {
            quality == null -> "The fundamental report is unavailable, so business quality cannot be checked against the valuation."
            mos != null && mos >= 0 && quality >= 55 -> "Business quality is good (score $quality): the price sits below fair value, so the fundamentals support the valuation."
            mos != null && mos < 0 && quality >= 55 -> "Quality is good (score $quality), but the price is above fair value: the premium needs growth that the reported numbers do not yet show."
            mos != null && mos < 0 && quality < 35 -> "Quality is weak (score $quality) and the price is above fair value: the reported fundamentals do not justify the valuation."
            else -> "Quality score $quality: the fundamentals are neither a strong support nor a clear objection to the valuation."
        }
        val wrong = buildList {
            dcf?.let { if (it.terminalShare > 0.75) add("About ${pct(it.terminalShare)} of DCF enterprise value sits in the terminal value, so long-run growth and the discount rate decide most of the answer.") }
            sensitivity.firstOrNull()?.let { s ->
                if (s.swing != null) add("The largest swing is from ${s.driver.lowercase()}: ₹${fmt(s.fairLow!!)}–₹${fmt(s.fairHigh!!)} per share across the test range.")
            }
            add("Historical growth and margins may not repeat. The base case extends the reported five-year revenue CAGR.")
            add("The discount rate uses an assumed risk-free rate, beta and equity risk premium, not measured values.")
            val unavailable = models.filter { it.base == null && it.bear == null && it.bull == null }
                .map { it.model.label }
            if (unavailable.isNotEmpty()) add("Not available or not used: ${unavailable.joinToString(", ")}.")
            add("The balance sheet is as reported at the latest annual date. Later changes in debt or cash are not captured.")
        }
        return Interpretation(valuation, growth, fundamentals, wrong)
    }

    // ------------------------------------------------------------------ formatting

    private fun pct(x: Double): String = String.format(java.util.Locale.US, "%.1f%%", x * 100)
    private fun fmt(x: Double): String = String.format(java.util.Locale.US, "%.2f", x)
    private fun fmt0(x: Double): String = String.format(java.util.Locale.US, "%,.0f", x)
}

/**
 * Loads the fundamentals and peers for a symbol and builds the valuation report.
 * Nothing here is a market-consensus figure: every number is a model output from reported data.
 */
object ValuationService {
    suspend fun report(
        symbol: String,
        source: FundamentalDataSource,
        price: Double?,
        candles: List<Candle>,
        cfg: ValuationConfig,
        now: Long,
        qualityScore: Int?,
    ): ValuationOutcome {
        val snap = source.snapshot(symbol)
        return when (snap) {
            is DataPoint.Unavailable -> ValuationOutcome(null, snap.reason)
            is DataPoint.Available -> {
                val peers = source.peers(symbol, snap.value.sector)
                ValuationOutcome(ValuationCalc.build(symbol, snap.value, price, cfg, peers, candles, qualityScore, now), null)
            }
        }
    }
}

data class ValuationOutcome(val report: ValuationReport?, val unavailableReason: String?)
