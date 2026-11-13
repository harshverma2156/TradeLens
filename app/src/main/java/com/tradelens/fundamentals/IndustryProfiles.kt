package com.tradelens.fundamentals

/** Score bounds for one metric: a value at [bad] scores 0, a value at [good] scores 100, linear in between. */
data class Scale(val bad: Double, val good: Double) {
    init { require(good != bad) { "A scale needs two different bounds" } }
}

/**
 * What "good" means for one industry group. Banks are not judged on debt/equity or gross margin; NBFCs carry
 * structurally high leverage; IT firms run with little debt and high cash conversion; utilities and real estate are
 * capital-heavy. [notApplicable] metrics are shown with the reason and are left out of the score entirely.
 */
data class IndustryProfile(
    val sector: Sector,
    val scales: Map<Metric, Scale>,
    val notApplicable: Map<Metric, String>,
    val rationale: String,
)

object IndustryProfiles {

    private val GENERIC = mapOf(
        Metric.ROE to Scale(5.0, 20.0),
        Metric.ROCE to Scale(6.0, 25.0),
        Metric.ROA to Scale(2.0, 10.0),
        Metric.GROSS_MARGIN to Scale(15.0, 50.0),
        Metric.OPERATING_MARGIN to Scale(5.0, 20.0),
        Metric.NET_MARGIN to Scale(3.0, 15.0),
        Metric.REVENUE_CAGR to Scale(0.0, 15.0),
        Metric.EBITDA_CAGR to Scale(0.0, 18.0),
        Metric.EPS_CAGR to Scale(0.0, 18.0),
        Metric.NET_PROFIT_CAGR to Scale(0.0, 18.0),
        Metric.QUARTERLY_REVENUE_GROWTH to Scale(-5.0, 20.0),
        Metric.ANNUAL_REVENUE_GROWTH to Scale(-5.0, 20.0),
        Metric.DEBT_TO_EQUITY to Scale(2.0, 0.2),
        Metric.INTEREST_COVERAGE to Scale(1.5, 8.0),
        Metric.CURRENT_RATIO to Scale(0.8, 2.0),
        Metric.QUICK_RATIO to Scale(0.5, 1.5),
        Metric.CASH_CONVERSION to Scale(0.5, 1.2),
        Metric.FCF_MARGIN to Scale(-5.0, 12.0),
    )

    private const val BANK_NOTE = "Not meaningful for banks: deposits are their raw material, so debt, interest-cost and inventory ratios do not describe them. Judge them on ROE, ROA and P/B."
    private const val NBFC_NOTE = "Not meaningful for lenders: interest is their main operating cost and their balance sheets are not classified into current items."
    private const val IT_NOTE = "Not used for IT: working capital and inventory are negligible, so the figure carries no signal."

    val OTHER = IndustryProfile(
        sector = Sector.OTHER,
        scales = GENERIC,
        notApplicable = emptyMap(),
        rationale = "No sector supplied: generic thresholds are used, so the score is less precise than for a classified company.",
    )

    private val profiles: Map<Sector, IndustryProfile> = mapOf(
        Sector.MANUFACTURING to IndustryProfile(
            Sector.MANUFACTURING, GENERIC, emptyMap(),
            "Manufacturing: generic thresholds, weighted towards margins, returns on capital and working-capital discipline.",
        ),
        Sector.BANK to IndustryProfile(
            Sector.BANK,
            GENERIC + mapOf(
                Metric.ROE to Scale(8.0, 20.0),
                Metric.ROA to Scale(0.5, 2.0),
                Metric.NET_MARGIN to Scale(10.0, 30.0),
                Metric.REVENUE_CAGR to Scale(0.0, 20.0),
                Metric.NET_PROFIT_CAGR to Scale(0.0, 20.0),
            ),
            listOf(Metric.GROSS_MARGIN, Metric.OPERATING_MARGIN, Metric.ROCE, Metric.DEBT_TO_EQUITY, Metric.INTEREST_COVERAGE,
                Metric.CURRENT_RATIO, Metric.QUICK_RATIO, Metric.EBITDA_CAGR, Metric.CASH_CONVERSION, Metric.FCF_MARGIN,
                Metric.EV_EBITDA, Metric.FCF_YIELD, Metric.PRICE_TO_SALES).associateWith { BANK_NOTE },
            "Bank: judged on ROE, ROA, net margin on total income, growth and P/B. Leverage and cash-flow ratios are excluded.",
        ),
        Sector.NBFC to IndustryProfile(
            Sector.NBFC,
            GENERIC + mapOf(
                Metric.DEBT_TO_EQUITY to Scale(6.0, 2.5),
                Metric.ROE to Scale(8.0, 20.0),
                Metric.ROA to Scale(1.0, 4.0),
                Metric.NET_MARGIN to Scale(10.0, 40.0),
            ),
            listOf(Metric.GROSS_MARGIN, Metric.OPERATING_MARGIN, Metric.ROCE, Metric.EBITDA_CAGR, Metric.INTEREST_COVERAGE,
                Metric.CURRENT_RATIO, Metric.QUICK_RATIO, Metric.CASH_CONVERSION, Metric.FCF_MARGIN, Metric.EV_EBITDA, Metric.FCF_YIELD)
                .associateWith { NBFC_NOTE },
            "Lender or insurer: leverage is tolerated up to about 2.5× equity, and returns on equity and assets carry the weight.",
        ),
        Sector.IT to IndustryProfile(
            Sector.IT,
            GENERIC + mapOf(
                Metric.GROSS_MARGIN to Scale(25.0, 50.0),
                Metric.OPERATING_MARGIN to Scale(12.0, 28.0),
                Metric.NET_MARGIN to Scale(8.0, 22.0),
                Metric.ROE to Scale(12.0, 35.0),
                Metric.ROCE to Scale(15.0, 40.0),
                Metric.DEBT_TO_EQUITY to Scale(0.5, 0.0),
                Metric.CASH_CONVERSION to Scale(0.7, 1.2),
            ),
            listOf(Metric.INTEREST_COVERAGE, Metric.CURRENT_RATIO, Metric.QUICK_RATIO).associateWith { IT_NOTE },
            "IT services: high margins, near-zero debt and high cash conversion are normal, so the bar for each is higher.",
        ),
        Sector.FMCG to IndustryProfile(
            Sector.FMCG,
            GENERIC + mapOf(
                Metric.GROSS_MARGIN to Scale(30.0, 60.0),
                Metric.OPERATING_MARGIN to Scale(12.0, 25.0),
                Metric.ROCE to Scale(15.0, 45.0),
                Metric.ROE to Scale(12.0, 30.0),
            ),
            emptyMap(),
            "FMCG and consumer: brand-driven gross margins and returns on capital are the main test.",
        ),
        Sector.PHARMA to IndustryProfile(
            Sector.PHARMA,
            GENERIC + mapOf(
                Metric.GROSS_MARGIN to Scale(35.0, 70.0),
                Metric.OPERATING_MARGIN to Scale(10.0, 30.0),
                Metric.ROCE to Scale(8.0, 30.0),
            ),
            emptyMap(),
            "Pharma and healthcare: high gross margins, with R&D spending depressing operating margins.",
        ),
        Sector.ENERGY_UTILITY to IndustryProfile(
            Sector.ENERGY_UTILITY,
            GENERIC + mapOf(
                Metric.DEBT_TO_EQUITY to Scale(3.0, 0.8),
                Metric.INTEREST_COVERAGE to Scale(1.5, 5.0),
                Metric.ROCE to Scale(4.0, 15.0),
                Metric.ROE to Scale(6.0, 18.0),
                Metric.OPERATING_MARGIN to Scale(10.0, 30.0),
            ),
            emptyMap(),
            "Energy and utilities: capital-heavy, so higher debt is accepted but interest cover and returns on capital must hold up.",
        ),
        Sector.REAL_ESTATE to IndustryProfile(
            Sector.REAL_ESTATE,
            GENERIC + mapOf(
                Metric.DEBT_TO_EQUITY to Scale(3.0, 0.8),
                Metric.ROE to Scale(4.0, 15.0),
                Metric.ROCE to Scale(5.0, 15.0),
            ),
            mapOf(Metric.CASH_CONVERSION to "Not meaningful here: project-based sales and advances make operating cash timing lumpy."),
            "Real estate and infrastructure: project cash timing is irregular, so cash conversion is not scored and leverage is judged loosely.",
        ),
    )

    /** The profile for a sector, or the generic profile when the sector is not known. */
    fun of(sector: Sector?): IndustryProfile = if (sector == null) OTHER else profiles[sector] ?: OTHER
}
