package com.tradelens.fundamentals

import com.tradelens.flow.DataPoint
import com.tradelens.marketdata.Freshness

/**
 * Source of company financial statements, filings and shareholding. Implementations return [DataPoint.Unavailable]
 * with the reason when they do not carry an item. Nothing is ever filled in with zero.
 *
 * Yahoo's fundamentals endpoints need an authenticated session (they answer 401 without one), so the app ships with
 * [UnavailableFundamentalSource]. A licensed financial-results feed, via the backend, implements this interface.
 */
interface FundamentalDataSource {
    val name: String
    suspend fun snapshot(symbol: String): DataPoint<FundamentalSnapshot>

    /** Snapshots of companies in the same [sector], excluding [symbol], for the industry comparison. */
    suspend fun peers(symbol: String, sector: Sector?): DataPoint<List<FundamentalSnapshot>>
}

object UnavailableFundamentalSource : FundamentalDataSource {
    override val name = "No financial-results feed connected"
    private const val WHY = "Financial statements, filings and shareholding are not provided by the current price source " +
        "(Yahoo Finance's fundamentals endpoints need an authenticated session). Connect a licensed NSE/BSE financial-results feed through the backend to enable this."

    override suspend fun snapshot(symbol: String): DataPoint<FundamentalSnapshot> = DataPoint.Unavailable(WHY)
    override suspend fun peers(symbol: String, sector: Sector?): DataPoint<List<FundamentalSnapshot>> = DataPoint.Unavailable(WHY)
}

object FundamentalService {
    /**
     * Builds the report. [price] is the latest traded price (valuation is null without it). [providerName] is the source
     * shown to the reader; the provenance for the snapshot comes from the source itself.
     */
    suspend fun report(
        symbol: String,
        source: FundamentalDataSource,
        price: Double?,
        freshness: Freshness,
        fetchedAt: Long?,
        now: Long,
        cfg: FundamentalConfig = FundamentalConfig(),
    ): FundamentalReport {
        val snap = source.snapshot(symbol)
        return when (snap) {
            is DataPoint.Unavailable -> FundamentalReport(symbol, now, snap, analysis = null, provenance = null)
            is DataPoint.Available -> {
                val peers = source.peers(symbol, snap.value.sector)
                val prov = snap.source.copy(freshness = freshness, fetchedAt = fetchedAt)
                FundamentalReport(symbol, now, snap, FundamentalCalc.analyze(snap.value, price, now, cfg, peers), prov)
            }
        }
    }
}
