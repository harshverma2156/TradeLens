package com.tradelens.data.remote

import com.tradelens.data.model.Candle
import com.tradelens.data.model.SearchItem
import com.tradelens.marketdata.CandleSeries
import com.tradelens.marketdata.CorporateAction
import com.tradelens.marketdata.CorporateActionType
import com.tradelens.marketdata.DataMode
import com.tradelens.marketdata.ProviderCapabilities
import com.tradelens.marketdata.ProviderInfo
import com.tradelens.marketdata.ProviderQuote
import com.tradelens.marketdata.RateLimit
import com.tradelens.marketdata.Timeframe
import com.tradelens.marketdata.UnsupportedCapabilityException
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Client for the TradeLens backend (BFF). The backend holds every licensed-provider credential
 * (Kite Connect api_secret, Upstox client secret, exchange-vendor keys); the APK holds none.
 * Authentication is a short-lived user token supplied by [token]. See docs/05-market-data.md §7 for the contract.
 */
interface BackendApi {
    @GET("v1/provider") suspend fun provider(): ProviderDto
    @GET("v1/quotes") suspend fun quotes(@Query("symbols") symbols: String): List<QuoteDto>
    @GET("v1/candles") suspend fun candles(
        @Query("symbol") symbol: String,
        @Query("timeframe") timeframe: String,
        @Query("from") from: Long,
        @Query("to") to: Long,
    ): CandlesDto
    @GET("v1/corporate-actions") suspend fun corporateActions(@Query("symbol") symbol: String, @Query("from") from: Long, @Query("to") to: Long): List<CorporateActionDto>
    @GET("v1/search") suspend fun search(@Query("q") q: String): List<SearchDto>
}

@Serializable data class ProviderDto(
    val id: String, val name: String, val mode: String, val licensedForDisplay: Boolean,
    /** Timeframe code ("1m","5m","15m","30m","1h","1d","1w","1mo") → max lookback in ms (null = none). */
    val timeframes: Map<String, Long?>,
    val batchQuotes: Int, val adjustedClose: Boolean, val splitAdjustedOhlc: Boolean, val corporateActions: Boolean,
    val indices: Boolean, val sectorIndices: Boolean, val search: Boolean, val marketDepth: Boolean,
    val permitsPerSecond: Double, val burst: Int, val note: String = "",
)
@Serializable data class QuoteDto(
    val symbol: String, val name: String? = null, val currency: String? = null, val price: Double,
    val previousClose: Double? = null, val dayHigh: Double? = null, val dayLow: Double? = null, val volume: Long? = null,
    val high52: Double? = null, val low52: Double? = null, val asOf: Long? = null,
)
@Serializable data class BarDto(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Long, val adj: Double? = null)
@Serializable data class CandlesDto(val symbol: String, val timeframe: String, val splitAdjusted: Boolean, val bars: List<BarDto>)
@Serializable data class CorporateActionDto(val symbol: String, val type: String, val exDate: Long, val ratio: Double? = null, val amount: Double? = null, val source: String)
@Serializable data class SearchDto(val symbol: String, val name: String, val exchange: String, val type: String)

class BackendMarketDataProvider(private val api: BackendApi) : MarketDataProvider {

    companion object {
        fun code(tf: Timeframe) = when (tf) {
            Timeframe.M1 -> "1m"; Timeframe.M5 -> "5m"; Timeframe.M15 -> "15m"; Timeframe.M30 -> "30m"
            Timeframe.H1 -> "1h"; Timeframe.D1 -> "1d"; Timeframe.W1 -> "1w"; Timeframe.MN1 -> "1mo"
        }
        fun parseTf(code: String) = Timeframe.entries.firstOrNull { code(it) == code }

        /** Until the backend says otherwise, assume the least: daily bars only, no batching. */
        val CONSERVATIVE = ProviderInfo(
            id = "backend", name = "TradeLens server", mode = DataMode.DELAYED, licensedForDisplay = false,
            capabilities = ProviderCapabilities(mapOf(Timeframe.D1 to null), 1, false, false, false, true, false, true, false),
            rateLimit = RateLimit(2.0, 4), note = "Capabilities not loaded yet.",
        )
    }

    @Volatile override var info: ProviderInfo = CONSERVATIVE
        private set

    /** Loads what the backend's upstream provider really supports. Call once at start-up. */
    suspend fun refreshInfo(): ProviderInfo {
        val p = api.provider()
        info = ProviderInfo(
            id = "backend:${p.id}", name = p.name,
            mode = runCatching { DataMode.valueOf(p.mode) }.getOrDefault(DataMode.DELAYED),
            licensedForDisplay = p.licensedForDisplay,
            capabilities = ProviderCapabilities(
                timeframes = p.timeframes.mapNotNull { (k, v) -> parseTf(k)?.let { it to v } }.toMap(),
                batchQuotes = p.batchQuotes.coerceAtLeast(1), adjustedClose = p.adjustedClose, splitAdjustedOhlc = p.splitAdjustedOhlc,
                corporateActions = p.corporateActions, indices = p.indices, sectorIndices = p.sectorIndices,
                search = p.search, marketDepth = p.marketDepth,
            ),
            rateLimit = RateLimit(p.permitsPerSecond, p.burst), note = p.note,
        )
        return info
    }

    override suspend fun quotes(symbols: List<String>) = api.quotes(symbols.joinToString(",")).map {
        ProviderQuote(it.symbol, it.name, it.currency, it.price, it.previousClose, it.dayHigh, it.dayLow, it.volume, it.high52, it.low52, it.asOf)
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe, fromMs: Long, toMs: Long): CandleSeries {
        if (!info.supports(timeframe)) throw UnsupportedCapabilityException("${info.name} has no ${timeframe.label} bars")
        val r = api.candles(symbol, code(timeframe), fromMs, toMs)
        val bars = r.bars
        return CandleSeries(
            symbol, timeframe,
            bars.map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) },
            adjClose = if (bars.any { it.adj != null }) bars.map { it.adj } else null,
            splitAdjusted = r.splitAdjusted,
        )
    }

    override suspend fun corporateActions(symbol: String, fromMs: Long, toMs: Long): List<CorporateAction> {
        if (!info.capabilities.corporateActions) return emptyList()
        return api.corporateActions(symbol, fromMs, toMs).mapNotNull { a ->
            val type = runCatching { CorporateActionType.valueOf(a.type) }.getOrNull() ?: return@mapNotNull null
            CorporateAction(a.symbol, type, a.exDate, a.ratio, a.amount, a.source)
        }
    }

    override suspend fun search(query: String) = api.search(query).map { SearchItem(it.symbol, it.name, it.exchange, it.type) }
}
