package com.tradelens.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Free Yahoo Finance endpoints (no API key). They are unofficial and can change or rate-limit.
 * Verified behaviour (Nov 2026): 1m bars ≤ ~8 days back, 5m–30m ≤ 60 days, 60m ≤ 2 years (HTTP 422 beyond);
 * adjclose only for 1d/1wk/1mo; spark takes ≤ 20 symbols; v7/quote needs auth (401) so it is not used.
 */
interface YahooApi {
    @GET("v8/finance/chart/{symbol}")
    suspend fun chart(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("period1") period1: Long? = null,
        @Query("period2") period2: Long? = null,
        @Query("range") range: String? = null,
        @Query("events") events: String? = null,
        @Query("includePrePost") includePrePost: Boolean = false,
    ): ChartResponse

    /** Batch: several symbols' recent bars and quote meta in one call. */
    @GET("v7/finance/spark")
    suspend fun spark(
        @Query("symbols") symbols: String,
        @Query("range") range: String = "5d",
        @Query("interval") interval: String = "1d",
    ): SparkResponse

    @GET("v1/finance/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("quotesCount") quotesCount: Int = 15,
        @Query("newsCount") newsCount: Int = 0,
    ): SearchResponse
}

@Serializable data class ChartResponse(val chart: ChartWrapper)
@Serializable data class ChartWrapper(val result: List<ChartResult>? = null, val error: ChartError? = null)
@Serializable data class ChartError(val code: String? = null, val description: String? = null)

@Serializable
data class ChartResult(
    val meta: ChartMeta,
    val timestamp: List<Long>? = null,
    val indicators: ChartIndicators = ChartIndicators(),
    val events: ChartEvents? = null,
)

@Serializable
data class ChartMeta(
    val symbol: String,
    val currency: String? = null,
    val exchangeName: String? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val regularMarketPrice: Double? = null,
    val chartPreviousClose: Double? = null,
    val previousClose: Double? = null,
    val regularMarketDayHigh: Double? = null,
    val regularMarketDayLow: Double? = null,
    val regularMarketVolume: Long? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val regularMarketTime: Long? = null,   // epoch seconds
)

@Serializable data class ChartIndicators(val quote: List<QuoteSeries> = emptyList(), val adjclose: List<AdjCloseSeries> = emptyList())
@Serializable data class AdjCloseSeries(val adjclose: List<Double?> = emptyList())

@Serializable
data class QuoteSeries(
    val open: List<Double?> = emptyList(),
    val high: List<Double?> = emptyList(),
    val low: List<Double?> = emptyList(),
    val close: List<Double?> = emptyList(),
    val volume: List<Long?> = emptyList(),
)

@Serializable data class ChartEvents(val splits: Map<String, SplitEvent>? = null, val dividends: Map<String, DividendEvent>? = null)
@Serializable data class SplitEvent(val date: Long, val numerator: Double? = null, val denominator: Double? = null, val splitRatio: String? = null)
@Serializable data class DividendEvent(val date: Long, val amount: Double? = null)

@Serializable data class SparkResponse(val spark: SparkWrapper)
@Serializable data class SparkWrapper(val result: List<SparkResult>? = null, val error: ChartError? = null)
@Serializable data class SparkResult(val symbol: String, val response: List<ChartResult>? = null)

@Serializable data class SearchResponse(val quotes: List<SearchQuote> = emptyList())

@Serializable
data class SearchQuote(
    val symbol: String? = null,
    val shortname: String? = null,
    val longname: String? = null,
    val exchange: String? = null,
    val exchDisp: String? = null,
    val quoteType: String? = null,
)
