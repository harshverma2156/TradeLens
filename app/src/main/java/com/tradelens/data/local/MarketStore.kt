package com.tradelens.data.local

import androidx.room.*

/**
 * Historical market data store (Room v2). Every row records which provider it came from, so demo and
 * real data, or two different providers, never mix. Keys and indexes are designed for the one hot query:
 * "bars for (source, symbol, timeframe) between t1 and t2, in time order".
 */
@Entity(
    tableName = "candles",
    primaryKeys = ["source", "symbol", "timeframe", "time"],
    indices = [Index(value = ["source", "timeframe", "time"])],   // retention sweeps by age
)
data class CandleEntity(
    val source: String,          // provider id, e.g. "yahoo", "demo", "backend:kite"
    val symbol: String,
    val exchange: String,        // NSE / BSE
    val timeframe: String,       // Timeframe.name
    val time: Long,              // bar start, epoch millis UTC
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val adjClose: Double?,       // dividend + split adjusted, when the provider supplies it
    val volume: Long,
    val quality: Int,            // QualityFlag bitmask
    val fetchedAt: Long,         // freshness: when we got this bar
)

/** One row per series: what we hold and when it was last synchronised. Drives incremental sync. */
@Entity(tableName = "sync_state", primaryKeys = ["source", "symbol", "timeframe"])
data class SyncStateEntity(
    val source: String,
    val symbol: String,
    val timeframe: String,
    val firstTime: Long?,
    val lastTime: Long?,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val qualityScore: Int?,
    val qualitySummary: String?,
)

@Entity(tableName = "quotes", primaryKeys = ["source", "symbol"])
data class QuoteEntity(
    val source: String,
    val symbol: String,
    val exchange: String,
    val name: String?,
    val currency: String?,
    val price: Double,
    val previousClose: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Long?,
    val high52: Double?,
    val low52: Double?,
    val asOf: Long?,
    val fetchedAt: Long,
)

@Entity(tableName = "corporate_actions", primaryKeys = ["source", "symbol", "type", "exDate"])
data class CorporateActionEntity(
    val source: String,
    val symbol: String,
    val type: String,            // SPLIT / BONUS / DIVIDEND
    val exDate: Long,
    val ratio: Double?,
    val amount: Double?,
    val fetchedAt: Long,
)

@Entity(tableName = "market_holidays", primaryKeys = ["exchange", "date"])
data class HolidayEntity(
    val exchange: String,
    val date: String,            // ISO yyyy-MM-dd
    val description: String?,
    val source: String,          // "official:<provider>" or "inferred:<benchmark>"
)

@Dao
interface MarketDao {
    @Query("SELECT * FROM candles WHERE source = :source AND symbol = :symbol AND timeframe = :tf AND time BETWEEN :from AND :to ORDER BY time")
    suspend fun candles(source: String, symbol: String, tf: String, from: Long, to: Long): List<CandleEntity>

    @Upsert suspend fun upsertCandles(rows: List<CandleEntity>)

    @Query("DELETE FROM candles WHERE source = :source AND timeframe = :tf AND time < :before")
    suspend fun deleteCandlesBefore(source: String, tf: String, before: Long): Int

    @Query("SELECT COUNT(*) FROM candles WHERE source = :source AND symbol = :symbol AND timeframe = :tf")
    suspend fun countCandles(source: String, symbol: String, tf: String): Int

    @Query("SELECT * FROM sync_state WHERE source = :source AND symbol = :symbol AND timeframe = :tf")
    suspend fun syncState(source: String, symbol: String, tf: String): SyncStateEntity?

    @Upsert suspend fun upsertSyncState(row: SyncStateEntity)

    @Query("SELECT * FROM quotes WHERE source = :source AND symbol IN (:symbols)")
    suspend fun quotes(source: String, symbols: List<String>): List<QuoteEntity>

    @Upsert suspend fun upsertQuotes(rows: List<QuoteEntity>)

    @Query("SELECT * FROM corporate_actions WHERE source = :source AND symbol = :symbol ORDER BY exDate")
    suspend fun corporateActions(source: String, symbol: String): List<CorporateActionEntity>

    @Upsert suspend fun upsertCorporateActions(rows: List<CorporateActionEntity>)

    @Query("SELECT * FROM market_holidays WHERE exchange = :exchange ORDER BY date")
    suspend fun holidays(exchange: String): List<HolidayEntity>

    @Upsert suspend fun upsertHolidays(rows: List<HolidayEntity>)
}
