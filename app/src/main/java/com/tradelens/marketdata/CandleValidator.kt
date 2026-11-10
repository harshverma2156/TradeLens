package com.tradelens.marketdata

import com.tradelens.data.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class IssueType(val label: String) {
    MISSING_BARS("Missing bars"),
    DUPLICATE("Duplicate bars"),
    INVALID_OHLC("Invalid OHLC"),
    UNUSUAL_VOLUME("Unusual volume"),
    BAD_TIMESTAMP("Incorrect timestamp"),
    STALE("Stale data"),
    CORPORATE_ACTION("Corporate action"),
    UNEXPLAINED_GAP("Unexplained price gap"),
}

enum class Severity { INFO, WARNING, ERROR }

data class DataIssue(
    val type: IssueType,
    val severity: Severity,
    val message: String,
    val time: Long? = null,
    val count: Int = 1,
)

/** Per-candle flags stored with each bar (bitmask). */
object QualityFlag {
    const val ADJUSTED = 1            // prices back-adjusted locally for a split/bonus
    const val RESAMPLED = 2           // built from smaller bars
    const val UNUSUAL_VOLUME = 4
    const val SPECIAL_SESSION = 8     // bar on a weekend/holiday (e.g. Muhurat trading)
    const val TIME_NORMALISED = 16    // timestamp snapped to the bar boundary
    const val DUPLICATE_RESOLVED = 32 // several bars for this slot; the latest was kept
    const val GAP = 64                // large unexplained gap from the previous bar
}

data class ValidationReport(val received: Int, val kept: Int, val issues: List<DataIssue>) {
    fun count(type: IssueType) = issues.filter { it.type == type }.sumOf { it.count }
    val errors get() = issues.count { it.severity == Severity.ERROR }
    val warnings get() = issues.count { it.severity == Severity.WARNING }

    /** 100 = no issues. Errors cost more than warnings; info items cost nothing. */
    val score: Int get() {
        val penalty: Int = issues.sumOf { i: DataIssue ->
            val p: Int = when (i.severity) { Severity.ERROR -> 15; Severity.WARNING -> 5; Severity.INFO -> 0 }
            p
        }
        return (100 - penalty).coerceIn(0, 100)
    }

    val summary: String get() =
        if (issues.isEmpty()) "All checks passed"
        else issues.groupBy { it.type }.entries.joinToString(" · ") { (t, l) -> "${t.label}: ${l.sumOf { it.count }}" }

    companion object { val EMPTY = ValidationReport(0, 0, emptyList()) }
}

data class ValidationResult(
    val candles: List<Candle>,
    val adjClose: List<Double?>?,
    val flags: IntArray,
    val report: ValidationReport,
)

/**
 * Cleans and checks a candle series. It never invents bars: invalid bars are dropped, missing bars are
 * reported (not filled), and every change is listed in the report.
 */
object CandleValidator {
    const val VOLUME_SPIKE_X = 5.0          // × median of the prior 20 bars
    const val DAILY_GAP_PCT = 20.0          // open vs previous close
    const val INTRADAY_GAP_PCT = 10.0

    fun validate(
        series: CandleSeries,
        calendar: MarketCalendar,
        actions: List<CorporateAction>,
        nowMs: Long,
        isIndex: Boolean = series.symbol.startsWith("^"),
    ): ValidationResult {
        val tf = series.timeframe
        val issues = mutableListOf<DataIssue>()
        val raw = series.candles
        val adjIn = series.adjClose

        // 1. Timestamps and OHLC sanity, per bar.
        data class Row(val c: Candle, val adj: Double?, var flags: Int)
        val rows = ArrayList<Row>(raw.size)
        var future = 0; var outside = 0; var invalid = 0; var normalised = 0; var special = 0
        val maxFuture = nowMs + max(tf.millis, 60_000L)
        raw.forEachIndexed { i, c0 ->
            if (c0.time > maxFuture) { future++; return@forEachIndexed }
            val b = calendar.bucketStart(c0.time, tf)
            if (b == null) { outside++; return@forEachIndexed }
            if (!validOhlc(c0)) { invalid++; return@forEachIndexed }
            var flags = 0
            val c = if (b != c0.time) { normalised++; flags = flags or QualityFlag.TIME_NORMALISED; c0.copy(time = b) } else c0
            if (!tf.intraday || tf == Timeframe.D1) {
                val d = MarketCalendar.date(c.time)
                if (tf == Timeframe.D1 && !calendar.isTradingDay(d)) { special++; flags = flags or QualityFlag.SPECIAL_SESSION }
            }
            rows += Row(c, adjIn?.getOrNull(i), flags)
        }
        if (future > 0) issues += DataIssue(IssueType.BAD_TIMESTAMP, Severity.ERROR, "$future bar(s) dated in the future were dropped", count = future)
        if (outside > 0) issues += DataIssue(IssueType.BAD_TIMESTAMP, Severity.WARNING, "$outside intraday bar(s) outside 09:15–15:30 IST were dropped", count = outside)
        if (normalised > 0) issues += DataIssue(IssueType.BAD_TIMESTAMP, Severity.INFO, "$normalised timestamp(s) snapped to the bar boundary", count = normalised)
        if (invalid > 0) issues += DataIssue(IssueType.INVALID_OHLC, Severity.ERROR, "$invalid bar(s) with impossible OHLC values (e.g. high < low, non-positive price) were dropped", count = invalid)
        if (special > 0) issues += DataIssue(IssueType.BAD_TIMESTAMP, Severity.INFO, "$special bar(s) on a weekend or holiday, kept as special sessions (e.g. Muhurat trading)", count = special)

        // 2. Order and duplicates: one bar per slot, keeping the last one received (providers append a live
        //    partial bar for the current week/month, which duplicates the period's first bar).
        rows.sortBy { it.c.time }
        val dedup = ArrayList<Row>(rows.size)
        var dups = 0
        for (r in rows) {
            val last = dedup.lastOrNull()
            if (last != null && last.c.time == r.c.time) {
                dups++
                dedup[dedup.size - 1] = r.also { it.flags = it.flags or QualityFlag.DUPLICATE_RESOLVED }
            } else dedup += r
        }
        if (dups > 0) issues += DataIssue(IssueType.DUPLICATE, Severity.WARNING, "$dups duplicate bar(s) for the same period; kept the most recent", count = dups)

        // 3. Corporate actions: back-adjust unadjusted splits/bonuses; flag unexplained gaps.
        val splits = actions.filter { (it.type == CorporateActionType.SPLIT || it.type == CorporateActionType.BONUS) && (it.ratio ?: 0.0) > 0 && it.ratio != 1.0 }
        var candles = dedup.map { it.c }
        val flags = dedup.map { it.flags }.toIntArray()
        for (a in splits) {
            val idx = candles.indexOfFirst { it.time >= MarketCalendar.at(MarketCalendar.date(a.exDate), MarketCalendar.OPEN) }
            if (idx <= 0) continue
            val prev = candles[idx - 1].close
            val cur = candles[idx].open
            val r = a.ratio!!
            val observed = prev / cur                      // ≈ r if the series is NOT adjusted
            val looksUnadjusted = abs(observed / r - 1) < 0.25
            val name = "${a.type.name.lowercase().replaceFirstChar { it.uppercase() }} ${fmtRatio(r)} on ${MarketCalendar.date(a.exDate)}"
            if (!series.splitAdjusted && looksUnadjusted) {
                candles = candles.mapIndexed { k, c ->
                    if (k < idx) { flags[k] = flags[k] or QualityFlag.ADJUSTED; Candle(c.time, c.open / r, c.high / r, c.low / r, c.close / r, (c.volume * r).toLong()) } else c
                }
                issues += DataIssue(IssueType.CORPORATE_ACTION, Severity.INFO, "$name: earlier prices back-adjusted so the chart has no artificial gap", a.exDate)
            } else if (looksUnadjusted) {
                issues += DataIssue(IssueType.CORPORATE_ACTION, Severity.WARNING, "$name: the provider says prices are adjusted, but a matching price gap is present", a.exDate)
            } else {
                issues += DataIssue(IssueType.CORPORATE_ACTION, Severity.INFO, "$name: prices are already adjusted", a.exDate)
            }
        }
        val gapLimit = if (tf.intraday) INTRADAY_GAP_PCT else DAILY_GAP_PCT
        var gaps = 0
        var firstGap: Long? = null
        for (k in 1 until candles.size) {
            val pct = abs(candles[k].open / candles[k - 1].close - 1) * 100
            if (pct >= gapLimit) {
                val near = splits.any { abs(MarketCalendar.date(it.exDate).toEpochDay() - MarketCalendar.date(candles[k].time).toEpochDay()) <= 3 }
                if (!near) { gaps++; flags[k] = flags[k] or QualityFlag.GAP; if (firstGap == null) firstGap = candles[k].time }
            }
        }
        if (gaps > 0) issues += DataIssue(IssueType.UNEXPLAINED_GAP, Severity.WARNING,
            "$gaps gap(s) of ${gapLimit.toInt()}%+ with no recorded corporate action. Could be news, or an unrecorded split/bonus.", firstGap, gaps)

        // 4. Unusual volume (indices report no volume).
        if (!isIndex) {
            var spikes = 0
            for (k in candles.indices) {
                if (k < 20) continue
                val med = candles.subList(k - 20, k).map { it.volume }.sorted()[10]
                if (med > 0 && candles[k].volume >= VOLUME_SPIKE_X * med) { spikes++; flags[k] = flags[k] or QualityFlag.UNUSUAL_VOLUME }
            }
            if (spikes > 0) issues += DataIssue(IssueType.UNUSUAL_VOLUME, Severity.INFO, "$spikes bar(s) with volume ≥ ${VOLUME_SPIKE_X.toInt()}× the 20-bar median (real activity or a bad print)", count = spikes)
        }

        // 5. Missing bars between the first and last bar we have.
        if (candles.size >= 2) {
            val have = candles.map { it.time }.toHashSet()
            val expected = calendar.expectedBuckets(candles.first().time, candles.last().time, tf)
            val missing = expected.filter { it !in have }
            if (missing.isNotEmpty()) {
                val unknownHolidays = missing.count { calendar.holidaysUnknown(MarketCalendar.date(it)) }
                val sev = when {
                    tf.intraday -> Severity.INFO                         // illiquid stocks have minutes with no trades
                    unknownHolidays == missing.size -> Severity.INFO     // probably holidays we don't know about
                    else -> Severity.WARNING
                }
                val why = when {
                    tf.intraday -> "intervals with no trades or gaps in the feed"
                    unknownHolidays > 0 -> "some may be holidays not in the calendar"
                    else -> "on known trading days"
                }
                issues += DataIssue(IssueType.MISSING_BARS, sev, "${missing.size} missing ${tf.label} bar(s) ($why); not filled in", missing.first(), missing.size)
            }
        }

        // 6. Staleness: is the newest bar older than the last bar that should exist by now?
        candles.lastOrNull()?.let { last ->
            val due = calendar.expectedBuckets(nowMs - 10L * 86_400_000L, nowMs, tf).lastOrNull { it <= nowMs - tf.millis }
            if (due != null && last.time < due && calendar.sessionState(nowMs) != SessionState.PRE_OPEN) {
                val behind = calendar.expectedBuckets(last.time, due, tf).count { it > last.time }
                if (behind > 0) issues += DataIssue(IssueType.STALE, if (behind > 1) Severity.WARNING else Severity.INFO,
                    "Latest bar is from ${MarketCalendar.date(last.time)}; $behind newer ${tf.label} bar(s) expected", last.time, behind)
            }
        }

        val keptAdj = if (adjIn == null) null else dedup.map { it.adj }
        return ValidationResult(candles, keptAdj, flags, ValidationReport(raw.size, candles.size, issues))
    }

    fun validOhlc(c: Candle): Boolean {
        val vals = doubleArrayOf(c.open, c.high, c.low, c.close)
        if (vals.any { !it.isFinite() || it <= 0 }) return false
        if (c.volume < 0) return false
        val hiBody = max(c.open, c.close)
        val loBody = min(c.open, c.close)
        val eps = 1e-9 * hiBody
        return c.high + eps >= hiBody && c.low - eps <= loBody && c.high + eps >= c.low
    }

    private fun fmtRatio(r: Double): String =
        if (r >= 1) "${trim(r)}:1" else "1:${trim(1 / r)}"

    private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}
