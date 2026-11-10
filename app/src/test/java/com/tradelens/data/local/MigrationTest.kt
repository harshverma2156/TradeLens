package com.tradelens.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1 → v2 must keep every user row and add the market-data tables exactly as Room expects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)   // don't boot TradeLensApp (WorkManager) in tests
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v1ToV2KeepsUserData() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO watchlist (symbol, name, addedAt) VALUES ('TCS.NS', 'TCS', 1)")
            db.execSQL("INSERT INTO holdings (symbol, name, quantity, avgPrice) VALUES ('INFY.NS', 'Infosys', 10, 1500.5)")
            db.execSQL("INSERT INTO trades (symbol, side, quantity, price, realizedPnl, time) VALUES ('INFY.NS', 'BUY', 10, 1500.5, NULL, 2)")
            db.execSQL("INSERT INTO alerts (symbol, name, targetPrice, above, active, createdAt) VALUES ('TCS.NS', 'TCS', 4000.0, 1, 1, 3)")
        }
        // Validates the resulting schema against app/schemas/.../2.json (column types, keys, indexes).
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2).allowMainThreadQueries().build()
        runBlocking {
            assertEquals("TCS.NS", db.dao().watchlist().first().single().symbol)
            assertEquals(1500.5, db.dao().holdings().first().single().avgPrice, 1e-9)
            assertEquals(1, db.dao().trades().first().size)
            assertEquals(4000.0, db.dao().alerts().first().single().targetPrice, 1e-9)
            // New tables work.
            val m = db.marketDao()
            m.upsertCandles(listOf(CandleEntity("yahoo", "TCS.NS", "NSE", "D1", 10, 1.0, 2.0, 0.5, 1.5, 1.4, 100, 0, 11)))
            m.upsertCandles(listOf(CandleEntity("yahoo", "TCS.NS", "NSE", "D1", 10, 1.0, 2.0, 0.5, 1.6, 1.4, 100, 0, 12)))  // upsert, not duplicate
            val rows = m.candles("yahoo", "TCS.NS", "D1", 0, 100)
            assertEquals(1, rows.size)
            assertEquals(1.6, rows.single().close, 1e-9)
            assertTrue(m.candles("demo", "TCS.NS", "D1", 0, 100).isEmpty())
        }
        db.close()
    }
}
