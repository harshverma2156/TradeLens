package com.tradelens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.tradelens.data.local.AppDatabase
import com.tradelens.data.local.MIGRATION_1_2
import com.tradelens.data.remote.BackendApi
import com.tradelens.data.remote.BackendMarketDataProvider
import com.tradelens.data.remote.DemoMarketDataProvider
import com.tradelens.data.remote.MarketDataProvider
import com.tradelens.data.remote.YahooApi
import com.tradelens.data.remote.YahooFinanceProvider
import com.tradelens.data.repository.MarketRepository
import com.tradelens.data.repository.SettingsRepository
import com.tradelens.data.repository.UserRepository
import com.tradelens.work.AlertScheduler
import com.tradelens.work.MarketSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        MarketSyncScheduler.schedulePeriodic(this)
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

    private fun retrofit(baseUrl: String) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "tradelens.db")
        .addMigrations(MIGRATION_1_2)
        // Never silently wipe watchlist/portfolio/alerts on upgrade: every version bump needs a Migration.
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    val settings = SettingsRepository(context)
    val user = UserRepository(db)

    private val yahoo = YahooFinanceProvider(retrofit("https://query1.finance.yahoo.com/").create(YahooApi::class.java))
    private val demo = DemoMarketDataProvider()

    /**
     * Your own server, configured with -Ptradelens.backendUrl=https://… at build time (not a secret).
     * When set, it replaces Yahoo; the server holds the licensed-provider credentials.
     */
    private val backend: BackendMarketDataProvider? = BuildConfig.BACKEND_URL.takeIf { it.isNotBlank() }?.let { url ->
        BackendMarketDataProvider(retrofit(url.trimEnd('/') + "/").create(BackendApi::class.java)).also { p ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { p.refreshInfo() } }
        }
    }

    /** Demo mode wins (always labelled); then the backend if configured; otherwise Yahoo. */
    private fun currentProvider(): MarketDataProvider = when {
        settings.settings.value.demoMode -> demo
        backend != null -> backend
        else -> yahoo
    }

    val market = MarketRepository(::currentProvider, db.marketDao())
}
