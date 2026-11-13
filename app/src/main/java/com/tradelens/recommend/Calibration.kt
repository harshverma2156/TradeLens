package com.tradelens.recommend

/**
 * Maps the raw confidence to the share of past calls that were right, measured out of sample.
 *
 * A sample is one past decision with its raw confidence and whether its outcome hit the goal set for its horizon
 * (for example, a forward return beyond a threshold within the holding window). The history is split in time: bins
 * are fitted on the earlier part and scored on the later part. A table is produced only when there are enough
 * out-of-sample decisions; otherwise confidence stays uncalibrated and the reason is given.
 */
data class CalibrationSample(val time: Long, val confidence: Double, val hit: Boolean)

data class CalibrationBin(val lo: Double, val hi: Double, val samples: Int, val hitRate: Double)

data class CalibrationTable(
    val bins: List<CalibrationBin>,
    val outOfSample: Int,
    /** Mean absolute error of the calibrated probability against the 0/1 outcome, on the later part of the history. */
    val calibrationError: Double,
    /** The same error if every decision were predicted at the overall hit rate: the bar the calibration has to beat. */
    val baselineError: Double,
) {
    /** Calibrated hit rate for a raw confidence, or null when its bin had too few past decisions. */
    fun predict(confidence: Double): Double? {
        val c = confidence.coerceIn(0.0, 1.0)
        return bins.firstOrNull { c >= it.lo && (c < it.hi || (it.hi >= 1.0 && c <= 1.0)) }?.hitRate
    }
}

data class CalibrationResult(val table: CalibrationTable?, val reason: String)

object Calibration {
    const val MIN_BIN_SAMPLES = 30
    const val MIN_OUT_OF_SAMPLE = 60
    const val TRAIN_SHARE = 0.7

    fun fit(samples: List<CalibrationSample>, bins: Int = 5): CalibrationResult {
        if (samples.size < MIN_BIN_SAMPLES * 2) return CalibrationResult(null, "Only ${samples.size} past decisions; calibration needs at least ${MIN_BIN_SAMPLES * 2}.")
        val sorted = samples.sortedBy { it.time }
        val cut = (sorted.size * TRAIN_SHARE).toInt()
        val train = sorted.take(cut)
        val test = sorted.drop(cut)
        if (test.size < MIN_OUT_OF_SAMPLE) return CalibrationResult(null, "Only ${test.size} out-of-sample decisions; at least $MIN_OUT_OF_SAMPLE are needed.")

        val width = 1.0 / bins
        val table = (0 until bins).map { k ->
            val lo = k * width
            val hi = if (k == bins - 1) 1.0 + 1e-9 else (k + 1) * width
            val inBin = train.filter { it.confidence.coerceIn(0.0, 1.0) >= lo && it.confidence.coerceIn(0.0, 1.0) < hi }
            CalibrationBin(lo, minOf(hi, 1.0), inBin.size, if (inBin.isEmpty()) 0.0 else inBin.count { it.hit }.toDouble() / inBin.size)
        }.filter { it.samples >= MIN_BIN_SAMPLES }

        if (table.size < 3) return CalibrationResult(null, "Fewer than three confidence bins have ${MIN_BIN_SAMPLES}+ past decisions.")
        val usable = CalibrationTable(table, 0, 0.0, 0.0)
        val scored = test.mapNotNull { s -> usable.predict(s.confidence)?.let { p -> p to (if (s.hit) 1.0 else 0.0) } }
        if (scored.size < MIN_OUT_OF_SAMPLE / 2) return CalibrationResult(null, "Too few out-of-sample decisions fall in a calibrated bin (${scored.size}).")
        val overall = test.count { it.hit }.toDouble() / test.size
        val error = scored.sumOf { (p, y) -> kotlin.math.abs(p - y) } / scored.size
        val baseline = test.sumOf { s -> kotlin.math.abs(overall - (if (s.hit) 1.0 else 0.0)) } / test.size
        return CalibrationResult(CalibrationTable(table, scored.size, error, baseline), "Calibrated on ${train.size} earlier and ${scored.size} later decisions.")
    }
}
