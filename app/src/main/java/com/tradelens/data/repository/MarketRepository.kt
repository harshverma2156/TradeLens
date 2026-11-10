package com.tradelens.data.repository

import com.tradelens.data.local.CandleEntity
import com.tradelens.data.local.CorporateActionEntity
import com.tradelens.data.local.HolidayEntity
import com.tradelens.data.local.MarketDao
import com.tradelens.data.local.QuoteEntity
import com.tradelens.data.local.SyncStateEntity
import com.tradelens.data.model.*
import com.tradelens.data.remote.MarketDataProvider
import com.tradelens.marketdata.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class DataException(message: String) : Exception(message)

/** Candles plus everything the UI needs to be honest about them. */
data class CandleResult(
    val symbol: String,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    val adjClose: List<Double?>?,
    val flags: IntArray,
    val actions: List<CorporateAction>,
    val status: DataStatus,
)

/**
 * Market data for the app: provider → validation → Room → UI.
 *
 *  - Room is the source of truth and the offline fallback; the network only fills and refreshes it.
 *  - Incremental sync: only bars after the last stored one are fetched (with a small overlap).
 *  - Every provider call goes through its rate limiter and retry-with-backoff; identical concurrent
 *    requests are coalesced; quotes are fetched in batches of the provider's maximum size.
 *  - Data from different providers (including DEMO) is stored separately and never mixed.
 */
class MarketRepository(
    private val providerOf: () -> MarketDataProvider,
    private val dao: MarketDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val BENCHMARK = "^NSEI"
        const val DAY = 86_400_000L
        const val ANALYSIS_LOOKBACK_MS = 740 * DAY      // 2 years + warm-up
        private const val QUOTE_TTL_REALTIME_MS = 15_000L
        private const val QUOTE_TTL_DELAYED_MS = 60_000L
        private const val STALE_REALTIME_MS = 2 * 60_000L
        private const val STALE_DELAYED_MS = 20 * 60_000L

        /** How long to keep bars on the device. Daily and longer are kept indefinitely. */
        val RETENTION_MS = mapOf(
            Timeframe.M1 to 30 * DAY, Timeframe.M5 to 90 * DAY, Timeframe.M15 to 90 * DAY,
            Timeframe.M30 to 180 * DAY, Timeframe.H1 to 2 * 365 * DAY,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val inflight = InFlight<String, Unit>(scope)
    private val limiters = ConcurrentHashMap<String, RateLimiter>()
    private val quoteMem = ConcurrentHashMap<String, Pair<Quote, Long>>()   // "source|symbol" → (quote, fetchedAt)
    private val calendarMutex = Mutex()
    @Volatile private var calendarLoaded = false

    @Volatile var calendar: MarketCalendar = MarketCalendar()
        private set

    /** The provider currently in use, so the UI can label data honestly. */
    val source: ProviderInfo get() = providerOf().info

    // ------------------------------------------------------------------ quotes

    suspend fun quote(symbol: String, forceRefresh: Boolean = false): Quote {
        val (map, errors) = loadQuotes(listOf(symbol), forceRefresh)
        return map[symbol] ?: throw (errors.firstOrNull() ?: DataException("Symbol not found. Check the ticker (NSE stocks end in .NS, BSE in .BO)."))
    }

    /** Quotes in provider-sized batches. Symbols that fail with no cached copy are dropped. */
    suspend fun quotes(symbols: Collection<String>, forceRefresh: Boolean = false): List<Quote> {
        val (map, _) = loadQuotes(symbols.distinct(), forceRefresh)
        return symbols.mapNotNull { map[it] }
    }

    private suspend fun loadQuotes(symbols: List<String>, force: Boolean): Pair<Map<String, Quote>, List<Throwable>> = withContext(io) {
        ensureCalendar()
        val p = providerOf()
        val info = p.info
        val now = clock()
        val out = ConcurrentHashMap<String, Quote>()
        val errors = mutableListOf<Throwable>()
        val need = symbols.filter { s ->
            val m = quoteMem["${info.id}|$s"]
            if (!force && m != null && quoteFresh(m.second, now, info)) { out[s] = m.first.withStatus(info, now, m.second, null); false } else true
        }
        coroutineScope {
            need.chunked(info.capabilities.batchQuotes.coerceAtLeast(1)).map { chunk ->
                async {
                    try {
                        val got = call(p) { p.quotes(chunk) }.filter { it.symbol in chunk }
                        val fetchedAt = clock()
                        dao.upsertQuotes(got.map { it.toEntity(info.id, fetchedAt) })
                        for (pq in got) {
                            val q = pq.toQuote().withStatus(info, fetchedAt, fetchedAt, null)
                            quoteMem["${info.id}|${pq.symbol}"] = q to fetchedAt
                            out[pq.symbol] = q
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        synchronized(errors) { errors += e }
                        // Offline fallback: the last quote we stored, clearly marked as such.
                        dao.quotes(info.id, chunk).forEach { row ->
                            out[row.symbol] = row.toQuote().withStatus(info, now, row.fetchedAt, e.friendlyMessage())
                        }
                    }
                }
            }.forEach { it.await() }
        }
        out to errors
    }

    private fun quoteFresh(fetchedAt: Long, now: Long, info: ProviderInfo): Boolean =
        if (calendar.sessionState(now) == SessionState.OPEN) now - fetchedAt < if (info.realtime) QUOTE_TTL_REALTIME_MS else QUOTE_TTL_DELAYED_MS
        else fetchedAt > calendar.lastSessionClose(now) + 10 * 60_000L || now - fetchedAt < 5 * 60_000L

    private fun Quote.withStatus(info: ProviderInfo, now: Long, fetchedAt: Long, error: String?) =
        copy(status = DataStatus(info.name, info.mode, freshness(asOf, now, info, error), asOf, fetchedAt, error = error))

    /** Live / delayed / closed / stale, from the exchange time of the data and the session clock. */
    fun freshness(asOf: Long?, now: Long, info: ProviderInfo, error: String? = null): Freshness {
        if (info.mode == DataMode.DEMO) return Freshness.DEMO
        if (error != null) return Freshness.OFFLINE
        if (asOf == null) return Freshness.STALE
        return when (calendar.sessionState(now)) {
            SessionState.OPEN -> {
                val limit = if (info.realtime) STALE_REALTIME_MS else STALE_DELAYED_MS
                when {
                    now - asOf > limit -> Freshness.STALE
                    info.realtime -> Freshness.LIVE
                    else -> Freshness.DELAYED
                }
            }
            else -> if (asOf >= calendar.lastSessionClose(now) - 30 * 60_000L) Freshness.MARKET_CLOSED else Freshness.STALE
        }
    }

    // ------------------------------------------------------------------ candles

    /**
     * Bars for [symbol] at [tf] from [fromMs] to now. Serves from Room when fresh, otherwise syncs
     * incrementally. If the provider fails, returns what's stored with an OFFLINE status; throws only
     * when there is nothing at all to show.
     */
    suspend fun candles(symbol: String, tf: Timeframe, fromMs: Long, forceRefresh: Boolean = false): CandleResult = withContext(io) {
        ensureCalendar()
        val p = providerOf()
        val info = p.info
        val now = clock()
        val src = Resampler.sourceFor(tf, info.capabilities.timeframes.keys)
            ?: throw UnsupportedCapabilityException("${info.name} can't provide ${tf.label} bars")
        val notes = mutableListOf<String>()
        if (src != tf) notes += "${tf.label} bars are built from ${src.label} bars; ${info.name} has no native ${tf.label} series."
        var from = fromMs
        info.lookbackLimit(src)?.let { limit ->
            if (now - limit > fromMs) {
                from = now - limit
                notes += "${info.name} only provides about ${limit / DAY} days of ${src.label} bars; older history isn't available at this interval."
            }
        }
        if (!info.capabilities.adjustedClose && !tf.intraday) notes += "No dividend-adjusted close from this provider."

        val state = dao.syncState(info.id, symbol, tf.name)
        val covered = state?.firstTime != null && state.firstTime <= from + slack(tf)
        val fresh = !forceRefresh && covered && state?.lastSuccessAt != null && seriesFresh(state.lastSuccessAt, tf, now)
        var error: String? = null
        if (!fresh) {
            try {
                inflight.run("c|${info.id}|$symbol|$tf|${if (covered) "inc" else from}") { sync(p, symbol, tf, src, from, now, if (covered) state else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsupportedCapabilityException) {
                throw e
            } catch (e: Throwable) {
                error = e.friendlyMessage()
                dao.upsertSyncState((state ?: SyncStateEntity(info.id, symbol, tf.name, null, null, now, null, null, null, null)).copy(lastAttemptAt = now, lastError = error))
            }
        }

        val rows = dao.candles(info.id, symbol, tf.name, from, now)
        if (rows.isEmpty() && error != null) throw DataException(error)
        val actions = dao.corporateActions(info.id, symbol).map { it.toModel(info.name) }
        // Re-check what we're about to show (cheap, and catches issues across sync boundaries).
        val series = CandleSeries(symbol, tf, rows.map { it.toCandle() }, rows.takeIf { r -> r.any { it.adjClose != null } }?.map { it.adjClose }, splitAdjusted = true)
        val v = CandleValidator.validate(series, calendar, actions, now)
        val stored = rows.associate { it.time to it.quality }
        val flags = IntArray(v.candles.size) { k -> (stored[v.candles[k].time] ?: 0) or v.flags[k] }
        val st = dao.syncState(info.id, symbol, tf.name)
        // Same rule as quotes: daily+ is stale if any expected bar is missing at the end; intraday only when the
        // lag exceeds what this provider's delay allows (a delayed feed is always a few bars behind).
        val behind = v.report.issues.filter { it.type == IssueType.STALE }.sumOf { it.count }
        val stale = behind > 0 && (!tf.intraday || behind * tf.millis > if (info.realtime) STALE_REALTIME_MS else STALE_DELAYED_MS)
        val fresh2 = when {
            info.mode == DataMode.DEMO -> Freshness.DEMO
            error != null -> Freshness.OFFLINE
            stale -> Freshness.STALE
            calendar.sessionState(now) == SessionState.OPEN -> if (info.realtime) Freshness.LIVE else Freshness.DELAYED
            else -> Freshness.MARKET_CLOSED
        }
        if (calendar.holidaysUnknown(MarketCalendar.date(now)) && info.mode != DataMode.DEMO)
            notes += "Holiday calendar is ${calendar.holidaySource ?: "not loaded"}${calendar.knownThrough?.let { " (through $it)" } ?: ""}; later weekdays are assumed to be trading days."
        CandleResult(
            symbol, tf, v.candles, v.adjClose, flags, actions,
            DataStatus(info.name, info.mode, fresh2, v.candles.lastOrNull()?.time, st?.lastSuccessAt, v.report, notes, error),
        )
    }

    private suspend fun sync(p: MarketDataProvider, symbol: String, tf: Timeframe, src: Timeframe, from: Long, now: Long, state: SyncStateEntity?) {
        val info = p.info
        // Incremental: re-fetch a small overlap before the last stored bar so a bar that was still forming gets finalised.
        var fetchFrom = state?.lastTime?.let { max(from, it - overlap(tf)) } ?: from
        if (src != tf) fetchFrom = calendar.bucketStart(fetchFrom, tf) ?: fetchFrom   // never write a partial first bucket
        val raw = call(p) { p.candles(symbol, src, fetchFrom, now) }
        val actions = if (!tf.intraday) corporateActions(p, symbol, now) else dao.corporateActions(info.id, symbol).map { it.toModel(info.name) }
        val v = CandleValidator.validate(raw, calendar, actions, now)
        var candles = v.candles
        var flags = v.flags
        var adj = v.adjClose
        if (src != tf) {
            candles = Resampler.aggregate(candles, tf, calendar)
            flags = IntArray(candles.size) { QualityFlag.RESAMPLED }
            adj = null
        }
        val fetchedAt = clock()
        val ex = Exchange.of(symbol).name
        dao.upsertCandles(candles.mapIndexed { k, c ->
            CandleEntity(info.id, symbol, ex, tf.name, c.time, c.open, c.high, c.low, c.close, adj?.getOrNull(k), c.volume, flags[k], fetchedAt)
        })
        val first = listOfNotNull(state?.firstTime, candles.firstOrNull()?.time, if (candles.isEmpty()) from else null).minOrNull()
        val last = listOfNotNull(state?.lastTime, candles.lastOrNull()?.time).maxOrNull()
        dao.upsertSyncState(SyncStateEntity(info.id, symbol, tf.name, first, last, now, fetchedAt, null, v.report.score, v.report.summary))
        raw.meta?.let { m -> dao.upsertQuotes(listOf(m.toEntity(info.id, fetchedAt))) }
        if (symbol == BENCHMARK && tf == Timeframe.D1 && info.mode != DataMode.DEMO) updateHolidays(info)
    }

    /** Splits/bonuses/dividends, refreshed at most once a day per symbol. */
    private suspend fun corporateActions(p: MarketDataProvider, symbol: String, now: Long): List<CorporateAction> {
        val info = p.info
        if (!info.capabilities.corporateActions) return emptyList()
        val st = dao.syncState(info.id, symbol, "CA")
        if (st?.lastSuccessAt == null || now - st.lastSuccessAt > DAY) {
            try {
                val got = call(p) { p.corporateActions(symbol, now - 10 * 365 * DAY, now) }
                dao.upsertCorporateActions(got.map { CorporateActionEntity(info.id, symbol, it.type.name, it.exDate, it.ratio, it.amount, now) })
                dao.upsertSyncState(SyncStateEntity(info.id, symbol, "CA", null, null, now, now, null, null, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Not fatal: candles are still usable; the validator just has less context.
            }
        }
        return dao.corporateActions(info.id, symbol).map { it.toModel(info.name) }
    }

    private fun slack(tf: Timeframe) = when (tf) { Timeframe.W1 -> 14 * DAY; Timeframe.MN1 -> 40 * DAY; Timeframe.D1 -> 7 * DAY; else -> DAY }
    private fun overlap(tf: Timeframe) = when (tf) { Timeframe.D1 -> 5 * DAY; Timeframe.W1 -> 14 * DAY; Timeframe.MN1 -> 62 * DAY; else -> 3 * tf.millis }

    private fun seriesFresh(lastSuccessAt: Long, tf: Timeframe, now: Long): Boolean {
        if (calendar.sessionState(now) == SessionState.OPEN) {
            val ttl = when (tf) {
                Timeframe.M1 -> 60_000L; Timeframe.M5 -> 2 * 60_000L; Timeframe.M15 -> 5 * 60_000L
                else -> 10 * 60_000L
            }
            return now - lastSuccessAt < ttl
        }
        return lastSuccessAt > calendar.lastSessionClose(now) + 15 * 60_000L || now - lastSuccessAt < 30 * 60_000L
    }

    // ------------------------------------------------------------------ app-level helpers (API kept from Phase 1)

    /** Chart data for a range at a chosen bar size. */
    suspend fun chart(symbol: String, range: ChartRange, tf: Timeframe, forceRefresh: Boolean = false): CandleResult {
        val r = candles(symbol, tf, clock() - range.lookbackDays * DAY, forceRefresh)
        if (!range.lastSessionOnly || r.candles.isEmpty()) return r
        val lastDay = MarketCalendar.date(r.candles.last().time)
        val keep = r.candles.indices.filter { MarketCalendar.date(r.candles[it].time) == lastDay }
        return r.copy(candles = keep.map { r.candles[it] }, adjClose = r.adjClose?.let { a -> keep.map { a[it] } }, flags = keep.map { r.flags[it] }.toIntArray())
    }

    /** Two years of daily candles + a quote, for the analysis engine. */
    suspend fun analysisHistory(symbol: String, forceRefresh: Boolean = false): StockData = coroutineScope {
        val q = async { runCatchingCancellable { quote(symbol, forceRefresh) } }
        val c = candles(symbol, Timeframe.D1, clock() - ANALYSIS_LOOKBACK_MS, forceRefresh)
        val quote = q.await().getOrElse { fallbackQuote(symbol, c) }
        StockData(quote, c.candles, c.status)
    }

    /** NIFTY 50 daily bars (2 years) from the store; null if unavailable. */
    suspend fun benchmark(): List<Candle>? =
        runCatchingCancellable { candles(BENCHMARK, Timeframe.D1, clock() - ANALYSIS_LOOKBACK_MS).candles }.getOrNull()?.takeIf { it.size > 100 }

    suspend fun search(query: String): List<SearchItem> = withContext(io) {
        val p = providerOf()
        if (!p.info.capabilities.search) return@withContext emptyList()
        call(p) { p.search(query) }
    }

    /** Background refresh: daily bars and quotes for [symbols] (+ the benchmark), incrementally. */
    suspend fun backgroundSync(symbols: Collection<String>): Int {
        var ok = 0
        val all = (symbols + BENCHMARK).distinct()
        for (s in all) {
            if (runCatchingCancellable { candles(s, Timeframe.D1, clock() - ANALYSIS_LOOKBACK_MS) }.isSuccess) ok++
        }
        quotes(all, forceRefresh = true)
        prune()
        return ok
    }

    /** Drops intraday bars older than [RETENTION_MS]. */
    suspend fun prune() = withContext(io) {
        val id = providerOf().info.id
        val now = clock()
        RETENTION_MS.forEach { (tf, keep) -> dao.deleteCandlesBefore(id, tf.name, now - keep) }
    }

    private fun fallbackQuote(symbol: String, c: CandleResult): Quote {
        val last = c.candles.lastOrNull() ?: throw DataException("No data for $symbol")
        val prev = c.candles.getOrNull(c.candles.size - 2)?.close ?: last.close
        return Quote(symbol, Universe.INDICES[symbol] ?: symbol, last.close, prev, last.high, last.low, last.volume, null, null, "INR", last.time, c.status)
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun <T> call(p: MarketDataProvider, block: suspend () -> T): T {
        val info = p.info
        val limiter = limiters.getOrPut(info.id) { RateLimiter(info.rateLimit.permitsPerSecond, info.rateLimit.burst, clock, sleep) }
        return withRetry(retryPolicy, ::classify, sleep = sleep) {
            limiter.acquire()
            block()
        }
    }

    private fun classify(e: Throwable): RetryDecision = when (e) {
        is UnsupportedCapabilityException, is DataException -> RetryDecision.Fail
        is HttpException -> when (e.code()) {
            429 -> e.response()?.headers()?.get("Retry-After")?.toLongOrNull()?.let { RetryDecision.RetryAfter(it * 1000) } ?: RetryDecision.Retry
            408, 500, 502, 503, 504 -> RetryDecision.Retry
            else -> RetryDecision.Fail
        }
        is IOException -> RetryDecision.Retry
        else -> RetryDecision.Fail
    }

    /** Holidays = weekdays NIFTY 50 didn't trade, between its first and last stored bar. */
    private suspend fun updateHolidays(info: ProviderInfo) {
        val rows = dao.candles(info.id, BENCHMARK, Timeframe.D1.name, 0, Long.MAX_VALUE)
        if (rows.size < 20) return
        val inferred = MarketCalendar.inferHolidays(rows.map { it.toCandle() })
        val src = "inferred:$BENCHMARK@${info.id}"
        dao.upsertHolidays(inferred.flatMap { d -> Exchange.entries.map { HolidayEntity(it.name, d.toString(), null, src) } })
        reloadCalendar(MarketCalendar.date(rows.last().time))
    }

    private suspend fun ensureCalendar() {
        if (calendarLoaded) return
        calendarMutex.withLock {
            if (calendarLoaded) return
            reloadCalendar(null)
            calendarLoaded = true
        }
    }

    private suspend fun reloadCalendar(knownThrough: LocalDate?) {
        val rows = dao.holidays(Exchange.NSE.name)
        val through = knownThrough ?: dao.syncState(providerOf().info.id, BENCHMARK, Timeframe.D1.name)?.lastTime?.let { MarketCalendar.date(it) }
        calendar = MarketCalendar(
            holidays = rows.map { LocalDate.parse(it.date) },
            holidaySource = when {
                rows.isEmpty() -> null
                rows.all { it.source.startsWith("official") } -> "official"
                else -> "inferred from NIFTY 50 trading days"
            },
            knownThrough = through,
        )
    }

    // ------------------------------------------------------------------ mapping

    private fun ProviderQuote.toQuote() = Quote(
        symbol = symbol,
        name = Universe.INDICES[symbol] ?: Universe.SECTORS[symbol] ?: name ?: symbol,
        price = price,
        previousClose = previousClose ?: price,
        dayHigh = dayHigh, dayLow = dayLow, volume = volume, high52 = high52, low52 = low52,
        currency = currency ?: "INR", asOf = asOf,
    )

    private fun ProviderQuote.toEntity(source: String, fetchedAt: Long) =
        QuoteEntity(source, symbol, Exchange.of(symbol).name, name, currency, price, previousClose, dayHigh, dayLow, volume, high52, low52, asOf, fetchedAt)

    private fun QuoteEntity.toQuote() = Quote(
        symbol, Universe.INDICES[symbol] ?: Universe.SECTORS[symbol] ?: name ?: symbol, price, previousClose ?: price,
        dayHigh, dayLow, volume, high52, low52, currency ?: "INR", asOf,
    )

    private fun CandleEntity.toCandle() = Candle(time, open, high, low, close, volume)

    private fun CorporateActionEntity.toModel(provider: String) =
        CorporateAction(symbol, CorporateActionType.valueOf(type), exDate, ratio, amount, provider)
}
