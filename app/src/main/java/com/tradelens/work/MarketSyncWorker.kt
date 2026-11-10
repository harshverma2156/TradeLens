package com.tradelens.work

import android.content.Context
import androidx.work.*
import com.tradelens.TradeLensApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background refresh: incrementally syncs daily bars and quotes for the watchlist, holdings and NIFTY 50
 * into the local store, so screens open instantly and work offline. Also prunes old intraday bars.
 */
class MarketSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as TradeLensApp).container
        if (c.settings.settings.value.demoMode) return Result.success()   // nothing real to sync
        val symbols = c.user.watchlist.first().map { it.symbol } + c.user.holdings.first().map { it.symbol }
        c.market.backgroundSync(symbols)
        return Result.success()
    }
}

object MarketSyncScheduler {
    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<MarketSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("market_sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
