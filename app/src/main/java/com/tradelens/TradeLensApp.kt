package com.tradelens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.tradelens.data.local.AppDatabase
import com.tradelens.data.remote.YahooApi
import com.tradelens.data.repository.MarketRepository
import com.tradelens.data.repository.SettingsRepository
import com.tradelens.data.repository.UserRepository
import com.tradelens.work.AlertScheduler
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class TradeLensApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val channel = NotificationChannel(ALERT_CHANNEL_ID, "Price alerts", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        AlertScheduler.schedulePeriodic(this)
    }

    companion object {
        const val ALERT_CHANNEL_ID = "price_alerts"
    }
}

/** Simple manual dependency container (swap for Hilt if the app grows). */
class AppContainer(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) TradeLens/1.0")
                    .build()
            )
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val api: YahooApi = Retrofit.Builder()
        .baseUrl("https://query1.finance.yahoo.com/")
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(YahooApi::class.java)

    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "tradelens.db")
        .fallbackToDestructiveMigration()
        .build()

    val market = MarketRepository(api)
    val user = UserRepository(db)
    val settings = SettingsRepository(context)
}
