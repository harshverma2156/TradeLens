package com.tradelens.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.tradelens.MainActivity
import com.tradelens.R
import com.tradelens.TradeLensApp
import com.tradelens.data.local.AlertEntity
import com.tradelens.ui.components.Fmt
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Checks active price alerts and posts a notification when a target is crossed. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TradeLensApp).container
        val alerts = container.user.activeAlerts()
        for (alert in alerts) {
            val quote = try {
                container.market.quote(alert.symbol)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val hit = if (alert.above) quote.price >= alert.targetPrice else quote.price <= alert.targetPrice
            if (hit) {
                notify(alert, quote.price)
                container.user.setAlertActive(alert.id, false)
            }
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun notify(alert: AlertEntity, price: Double) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val direction = if (alert.above) "rose above" else "fell below"
        val notification = NotificationCompat.Builder(ctx, TradeLensApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("${alert.symbol.removeSuffix(".NS")} $direction ${Fmt.price(alert.targetPrice)}")
            .setContentText("Now trading at ${Fmt.price(price)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(alert.id.toInt(), notification)
    }
}

object AlertScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Android allows periodic work every 15 minutes at most. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("price_alerts", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AlertWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
