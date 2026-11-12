package com.tradelens.patterns

import com.tradelens.data.model.Candle
import org.junit.Assume.assumeTrue

/**
 * Real NSE daily bars, 2015–2025, fetched by tools/pattern-history/fetch_history.py into
 * src/test/resources/patterns/history (git-ignored: see the script for why). Tests that need them are SKIPPED
 * when the folder is missing, never silently passed.
 */
object History {
    val symbols = listOf("RELIANCE.NS", "TCS.NS", "INFY.NS", "HDFCBANK.NS", "ICICIBANK.NS", "SBIN.NS", "ITC.NS", "LT.NS",
        "BHARTIARTL.NS", "AXISBANK.NS", "MARUTI.NS", "SUNPHARMA.NS", "HINDUNILVR.NS", "TITAN.NS", "^NSEI")

    private val cache = HashMap<String, List<Candle>?>()

    fun loadOrNull(symbol: String): List<Candle>? = cache.getOrPut(symbol) {
        val name = symbol.replace("^", "IDX_")
        History::class.java.getResourceAsStream("/patterns/history/$name.csv")?.bufferedReader()?.readLines()?.drop(1)?.filter { it.isNotBlank() }?.map { l ->
            val p = l.split(",")
            Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toLong())
        }
    }

    /** Skips the calling test (JUnit assumption) when the history hasn't been fetched. */
    fun load(symbol: String): List<Candle> {
        val c = loadOrNull(symbol)
        assumeTrue("Real history missing: run python tools/pattern-history/fetch_history.py", c != null)
        return c!!
    }

    fun available() = loadOrNull(symbols.first()) != null
}
