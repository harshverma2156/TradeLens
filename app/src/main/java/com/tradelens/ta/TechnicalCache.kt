package com.tradelens.ta

import com.tradelens.data.model.Candle
import com.tradelens.marketdata.Timeframe

/**
 * Memoises [TechnicalEngine.analyze] per (source, symbol, timeframe).
 *
 * The key is the identity of the CLOSED bars: count, first and last open time, and a hash of the last closed
 * bar's OHLCV. So:
 *  - a new closed bar, or a revised one, changes the key → full recompute (O(n), a few ms for ~1,000 bars);
 *  - ticks on the still-forming bar don't change the indicators (they're computed on closed bars only), so
 *    the cached report is reused with the new forming bar attached;
 *  - switching timeframe and back is instant.
 * LRU-bounded so memory stays small.
 */
class TechnicalCache(private val maxEntries: Int = 24) {
    data class Key(val source: String, val symbol: String, val tf: Timeframe, val count: Int, val first: Long, val last: Long, val lastHash: Int)

    private val map = object : LinkedHashMap<Key, TechnicalReport>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TechnicalReport>?) = size > maxEntries
    }

    var hits = 0; private set
    var misses = 0; private set

    @Synchronized
    fun get(source: String, symbol: String, tf: Timeframe, candles: List<Candle>, nowMs: Long, isIndex: Boolean = symbol.startsWith("^")): TechnicalReport {
        val sorted = candles.sortedBy { it.time }
        val forming = sorted.lastOrNull()?.takeIf { nowMs < TechnicalEngine.barEnd(it.time, tf) }
        val closed = if (forming != null) sorted.dropLast(1) else sorted
        val last = closed.lastOrNull()
        val key = Key(source, symbol, tf, closed.size, closed.firstOrNull()?.time ?: 0, last?.time ?: 0, last?.hashCode() ?: 0)
        map[key]?.let { hits++; return if (it.formingBar == forming) it else it.copy(formingBar = forming) }
        misses++
        return TechnicalEngine.analyze(symbol, tf, sorted, nowMs, isIndex).also { map[key] = it }
    }

    @Synchronized fun clear() = map.clear()
}
