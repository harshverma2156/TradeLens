package com.tradelens.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watchlist")
data class WatchlistEntity(@PrimaryKey val symbol: String, val name: String, val addedAt: Long)

/** Paper-trading holdings (virtual money). */
@Entity(tableName = "holdings")
data class HoldingEntity(@PrimaryKey val symbol: String, val name: String, val quantity: Int, val avgPrice: Double)

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: String,            // "BUY" or "SELL"
    val quantity: Int,
    val price: Double,
    val realizedPnl: Double?,
    val time: Long,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val name: String,
    val targetPrice: Double,
    val above: Boolean,
    val active: Boolean = true,
    val createdAt: Long,
)

@Dao
interface TradeLensDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun watchlist(): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE symbol = :symbol)")
    fun isWatched(symbol: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE symbol = :symbol)")
    suspend fun isWatchedNow(symbol: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addWatch(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun removeWatch(symbol: String)

    @Query("SELECT * FROM holdings ORDER BY symbol")
    fun holdings(): Flow<List<HoldingEntity>>

    @Query("SELECT * FROM holdings WHERE symbol = :symbol")
    suspend fun holding(symbol: String): HoldingEntity?

    @Upsert
    suspend fun upsertHolding(holding: HoldingEntity)

    @Query("DELETE FROM holdings WHERE symbol = :symbol")
    suspend fun deleteHolding(symbol: String)

    @Insert
    suspend fun insertTrade(trade: TradeEntity)

    @Query("SELECT * FROM trades ORDER BY time DESC LIMIT 100")
    fun trades(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM alerts ORDER BY createdAt DESC")
    fun alerts(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE active = 1")
    suspend fun activeAlerts(): List<AlertEntity>

    @Insert
    suspend fun insertAlert(alert: AlertEntity)

    @Query("UPDATE alerts SET active = :active WHERE id = :id")
    suspend fun setAlertActive(id: Long, active: Boolean)

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun deleteAlert(id: Long)
}

@Database(
    entities = [WatchlistEntity::class, HoldingEntity::class, TradeEntity::class, AlertEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TradeLensDao
}
