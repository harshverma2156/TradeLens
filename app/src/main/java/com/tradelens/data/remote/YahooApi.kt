package com.tradelens.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Free Yahoo Finance endpoints (no API key). They are unofficial and can change or
 * rate-limit; swap this interface for a licensed feed (broker API, NSE vendor) in production.
 */
interface YahooApi {
    @GET("v8/finance/chart/{symbol}")
    suspend fun chart(
        @Path("symbol") symbol: String,
        @Query("range") range: String,
        @Query("interval") interval: String,
        @Query("includePrePost") includePrePost: Boolean = false,
    ): ChartResponse

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
)

@Serializable data class ChartIndicators(val quote: List<QuoteSeries> = emptyList())

@Serializable
data class QuoteSeries(
    val open: List<Double?> = emptyList(),
    val high: List<Double?> = emptyList(),
    val low: List<Double?> = emptyList(),
    val close: List<Double?> = emptyList(),
    val volume: List<Long?> = emptyList(),
)

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
