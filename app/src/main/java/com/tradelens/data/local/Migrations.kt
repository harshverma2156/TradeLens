package com.tradelens.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: adds the market-data store. Purely additive; watchlist, holdings, trades and alerts are untouched.
 * SQL copied from Room's exported schema (app/schemas/.../2.json) and verified by MigrationTest.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `candles` (`source` TEXT NOT NULL, `symbol` TEXT NOT NULL, `exchange` TEXT NOT NULL, `timeframe` TEXT NOT NULL, `time` INTEGER NOT NULL, `open` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, `close` REAL NOT NULL, `adjClose` REAL, `volume` INTEGER NOT NULL, `quality` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`source`, `symbol`, `timeframe`, `time`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_candles_source_timeframe_time` ON `candles` (`source`, `timeframe`, `time`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`source` TEXT NOT NULL, `symbol` TEXT NOT NULL, `timeframe` TEXT NOT NULL, `firstTime` INTEGER, `lastTime` INTEGER, `lastAttemptAt` INTEGER NOT NULL, `lastSuccessAt` INTEGER, `lastError` TEXT, `qualityScore` INTEGER, `qualitySummary` TEXT, PRIMARY KEY(`source`, `symbol`, `timeframe`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `quotes` (`source` TEXT NOT NULL, `symbol` TEXT NOT NULL, `exchange` TEXT NOT NULL, `name` TEXT, `currency` TEXT, `price` REAL NOT NULL, `previousClose` REAL, `dayHigh` REAL, `dayLow` REAL, `volume` INTEGER, `high52` REAL, `low52` REAL, `asOf` INTEGER, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`source`, `symbol`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `corporate_actions` (`source` TEXT NOT NULL, `symbol` TEXT NOT NULL, `type` TEXT NOT NULL, `exDate` INTEGER NOT NULL, `ratio` REAL, `amount` REAL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`source`, `symbol`, `type`, `exDate`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `market_holidays` (`exchange` TEXT NOT NULL, `date` TEXT NOT NULL, `description` TEXT, `source` TEXT NOT NULL, PRIMARY KEY(`exchange`, `date`))")
    }
}
