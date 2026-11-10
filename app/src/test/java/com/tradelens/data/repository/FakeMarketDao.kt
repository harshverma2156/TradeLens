package com.tradelens.data.repository

import com.tradelens.data.local.*

/** In-memory [MarketDao] with the same keys and ordering as the Room implementation. */
class FakeMarketDao : MarketDao {
    val candles = sortedMapOf<String, CandleEntity>()
    val sync = HashMap<String, SyncStateEntity>()
    val quotes = HashMap<String, QuoteEntity>()
    val actions = HashMap<String, CorporateActionEntity>()
    val holidays = HashMap<String, HolidayEntity>()

    private fun ck(c: CandleEntity) = "${c.source}|${c.symbol}|${c.timeframe}|${"%020d".format(c.time)}"

    override suspend fun candles(source: String, symbol: String, tf: String, from: Long, to: Long) =
        candles.values.filter { it.source == source && it.symbol == symbol && it.timeframe == tf && it.time in from..to }.sortedBy { it.time }

    override suspend fun upsertCandles(rows: List<CandleEntity>) { rows.forEach { candles[ck(it)] = it } }

    override suspend fun deleteCandlesBefore(source: String, tf: String, before: Long): Int {
        val k = candles.filterValues { it.source == source && it.timeframe == tf && it.time < before }.keys
        k.forEach { candles.remove(it) }
        return k.size
    }

    override suspend fun countCandles(source: String, symbol: String, tf: String) =
        candles.values.count { it.source == source && it.symbol == symbol && it.timeframe == tf }

    override suspend fun syncState(source: String, symbol: String, tf: String) = sync["$source|$symbol|$tf"]
    override suspend fun upsertSyncState(row: SyncStateEntity) { sync["${row.source}|${row.symbol}|${row.timeframe}"] = row }

    override suspend fun quotes(source: String, symbols: List<String>) = symbols.mapNotNull { quotes["$source|$it"] }
    override suspend fun upsertQuotes(rows: List<QuoteEntity>) { rows.forEach { quotes["${it.source}|${it.symbol}"] = it } }

    override suspend fun corporateActions(source: String, symbol: String) =
        actions.values.filter { it.source == source && it.symbol == symbol }.sortedBy { it.exDate }
    override suspend fun upsertCorporateActions(rows: List<CorporateActionEntity>) { rows.forEach { actions["${it.source}|${it.symbol}|${it.type}|${it.exDate}"] = it } }

    override suspend fun holidays(exchange: String) = holidays.values.filter { it.exchange == exchange }.sortedBy { it.date }
    override suspend fun upsertHolidays(rows: List<HolidayEntity>) { rows.forEach { holidays["${it.exchange}|${it.date}"] = it } }
}
