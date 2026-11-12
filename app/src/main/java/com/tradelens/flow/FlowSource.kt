package com.tradelens.flow

/**
 * Source of the data that is NOT in the price history: delivery, order book, reported institutional activity.
 *
 * Implementations must return [DataPoint.Unavailable] (with the reason) rather than an empty or zero value when a
 * feed doesn't carry the item. [UnavailableFlowSource] is the default: Yahoo's free endpoints provide none of these.
 * A licensed feed (NSE/BSE data via the backend, or a broker's market-data API) implements this interface.
 */
interface FlowDataSource {
    val name: String
    suspend fun delivery(symbol: String): DataPoint<List<DeliveryDay>>
    suspend fun depth(symbol: String): DataPoint<DepthSnapshot>
    suspend fun fiiDii(): DataPoint<List<InstitutionalFlowDay>>
    suspend fun shareholding(symbol: String): DataPoint<List<ShareholdingQuarter>>
    suspend fun deals(symbol: String): DataPoint<List<Deal>>
    suspend fun disclosures(symbol: String): DataPoint<List<Disclosure>>
}

object UnavailableFlowSource : FlowDataSource {
    override val name = "No institutional-data feed connected"
    private const val WHY = "Not provided by the current price source (Yahoo Finance has no delivery, order-book or reported-holdings data). " +
        "Connect a licensed NSE/BSE feed through the backend to enable it."

    override suspend fun delivery(symbol: String): DataPoint<List<DeliveryDay>> = DataPoint.Unavailable(WHY)
    override suspend fun depth(symbol: String): DataPoint<DepthSnapshot> = DataPoint.Unavailable("Market depth needs a licensed real-time feed. $WHY")
    override suspend fun fiiDii(): DataPoint<List<InstitutionalFlowDay>> = DataPoint.Unavailable(WHY)
    override suspend fun shareholding(symbol: String): DataPoint<List<ShareholdingQuarter>> = DataPoint.Unavailable(WHY)
    override suspend fun deals(symbol: String): DataPoint<List<Deal>> = DataPoint.Unavailable(WHY)
    override suspend fun disclosures(symbol: String): DataPoint<List<Disclosure>> = DataPoint.Unavailable(WHY)
}

/** Assembles a [FlowReport]: computes the volume analytics from closed bars and gathers the rest from [source]. */
object FlowService {
    suspend fun report(
        symbol: String,
        candles: List<com.tradelens.data.model.Candle>,
        source: FlowDataSource,
        providerName: String,
        freshness: com.tradelens.marketdata.Freshness,
        fetchedAt: Long?,
        now: Long,
        sessionFraction: Double? = null,
    ): FlowReport {
        val asOf = candles.lastOrNull()?.time
        fun prov(official: Boolean = false, note: String = "") = Provenance(providerName, asOf, fetchedAt, freshness, official, note)  // official = exchange-published

        val vol = FlowCalc.volume(candles, sessionFraction)?.let { DataPoint.Available(it, prov(note = "Computed from ${candles.size} daily bars.")) }
            ?: DataPoint.Unavailable("Volume is not available for this instrument or history is too short (needs 21 bars with volume).")

        val delivery = when (val d = source.delivery(symbol)) {
            is DataPoint.Available -> FlowCalc.delivery(d.value)?.let { DataPoint.Available(it, Provenance(source.name, d.value.lastOrNull()?.date, fetchedAt, freshness, true)) }
                ?: DataPoint.Unavailable("Fewer than 20 days of delivery data.")
            is DataPoint.Unavailable -> d
        }
        val depthPoint = source.depth(symbol)
        val depthAnalytics = when (depthPoint) {
            is DataPoint.Available -> FlowCalc.depth(depthPoint.value)?.let { DataPoint.Available(it, Provenance(source.name, depthPoint.value.timestamp, fetchedAt, freshness, false, "Snapshot, not a history.")) }
                ?: DataPoint.Unavailable("The order book has no two-sided quotes (or is crossed).")
            is DataPoint.Unavailable -> DataPoint.Unavailable(depthPoint.reason)
        }
        val depthSnap = (depthPoint as? DataPoint.Available)?.value
        val d = delivery.valueOrNull
        val assessment = FlowCalc.assess(candles, vol.valueOrNull, d, depthAnalytics.valueOrNull, depthSnap?.timestamp)

        return FlowReport(
            symbol, now, vol, delivery, depthPoint, depthAnalytics,
            source.fiiDii(), source.shareholding(symbol), source.deals(symbol), source.disclosures(symbol),
            assessment.copy(reportedNotes = reportedNotes(source)),
        )
    }

    private fun reportedNotes(source: FlowDataSource): List<String> = buildList {
        if (source === UnavailableFlowSource) add("Reported institutional activity (FII/DII flows, shareholding, bulk and block deals, disclosures) is unavailable: no official feed is connected.")
    }
}
