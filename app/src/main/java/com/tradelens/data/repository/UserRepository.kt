package com.tradelens.data.repository

import androidx.room.withTransaction
import com.tradelens.data.local.*

class UserRepository(private val db: AppDatabase) {
    private val dao = db.dao()

    val watchlist = dao.watchlist()
    val holdings = dao.holdings()
    val trades = dao.trades()
    val alerts = dao.alerts()

    fun isWatched(symbol: String) = dao.isWatched(symbol)

    suspend fun toggleWatch(symbol: String, name: String) {
        if (dao.isWatchedNow(symbol)) dao.removeWatch(symbol)
        else dao.addWatch(WatchlistEntity(symbol, name, System.currentTimeMillis()))
    }

    suspend fun removeWatch(symbol: String) = dao.removeWatch(symbol)

    suspend fun buy(symbol: String, name: String, qty: Int, price: Double) = db.withTransaction {
        val existing = dao.holding(symbol)
        val updated = if (existing == null) HoldingEntity(symbol, name, qty, price)
        else {
            val total = existing.quantity + qty
            existing.copy(quantity = total, avgPrice = (existing.avgPrice * existing.quantity + price * qty) / total)
        }
        dao.upsertHolding(updated)
        dao.insertTrade(TradeEntity(symbol = symbol, side = "BUY", quantity = qty, price = price, realizedPnl = null, time = System.currentTimeMillis()))
    }

    /** Returns false if you don't hold enough shares. */
    suspend fun sell(symbol: String, qty: Int, price: Double): Boolean = db.withTransaction {
        val existing = dao.holding(symbol) ?: return@withTransaction false
        if (qty > existing.quantity) return@withTransaction false
        if (qty == existing.quantity) dao.deleteHolding(symbol)
        else dao.upsertHolding(existing.copy(quantity = existing.quantity - qty))
        val pnl = (price - existing.avgPrice) * qty
        dao.insertTrade(TradeEntity(symbol = symbol, side = "SELL", quantity = qty, price = price, realizedPnl = pnl, time = System.currentTimeMillis()))
        true
    }

    suspend fun addAlert(symbol: String, name: String, target: Double, above: Boolean) =
        dao.insertAlert(AlertEntity(symbol = symbol, name = name, targetPrice = target, above = above, createdAt = System.currentTimeMillis()))

    suspend fun activeAlerts() = dao.activeAlerts()
    suspend fun setAlertActive(id: Long, active: Boolean) = dao.setAlertActive(id, active)
    suspend fun deleteAlert(id: Long) = dao.deleteAlert(id)
}
