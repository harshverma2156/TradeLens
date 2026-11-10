package com.tradelens.data.remote

import com.tradelens.data.model.SearchItem
import com.tradelens.marketdata.CandleSeries
import com.tradelens.marketdata.CorporateAction
import com.tradelens.marketdata.ProviderInfo
import com.tradelens.marketdata.ProviderQuote
import com.tradelens.marketdata.Timeframe

/**
 * Source-agnostic market data. The app only talks to this interface, so a licensed source can replace
 * Yahoo without touching the UI, storage or analysis.
 *
 * Contract for implementations:
 *  - Declare only what you really support in [ProviderInfo.capabilities]; callers check before asking.
 *  - Throw on failure. Never return made-up, interpolated or cached-as-new data.
 *  - Candles oldest first, epoch-millis timestamps, prices in the instrument's currency.
 *
 * Implementations: [YahooFinanceProvider] (unofficial, delayed; dev and personal use),
 * [BackendMarketDataProvider] (your server, which can hold Kite Connect / Upstox / vendor credentials),
 * [DemoMarketDataProvider] (synthetic, always labelled DEMO).
 */
interface MarketDataProvider {
    val info: ProviderInfo

    /** At most [com.tradelens.marketdata.ProviderCapabilities.batchQuotes] symbols per call. Unknown symbols are omitted. */
    suspend fun quotes(symbols: List<String>): List<ProviderQuote>

    /** Only for timeframes in [ProviderInfo.capabilities]; [fromMs] must respect its lookback limit. */
    suspend fun candles(symbol: String, timeframe: Timeframe, fromMs: Long, toMs: Long): CandleSeries

    /** Empty when unsupported (check capabilities to tell "none" from "unknown"). */
    suspend fun corporateActions(symbol: String, fromMs: Long, toMs: Long): List<CorporateAction>

    suspend fun search(query: String): List<SearchItem>
}
