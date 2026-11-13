package com.tradelens.recommend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One saved recommendation. Records are immutable and append-only: new market data produces a new record, and an old
 * one is never edited. [inputAsOf] says which market data it was based on.
 */
@Serializable
data class RecommendationRecord(
    val id: String,
    val symbol: String,
    val createdAt: Long,
    val horizon: String,
    val action: String,
    val price: Double?,
    val entryLow: Double?,
    val entryHigh: Double?,
    val stop: Double?,
    val targets: List<Double?>,
    val scores: Map<String, Double?>,
    val overall: Double?,
    val confidenceRaw: Double,
    val confidence: Double?,
    val modelVersion: String,
    val inputAsOf: Long?,
    val summary: String,
)

fun HorizonDecision.toRecord(symbol: String, price: Double?): RecommendationRecord = RecommendationRecord(
    id = "$symbol-${computedAt}-${horizon.name}-${action.name}".replace('.', '_'),
    symbol = symbol,
    createdAt = computedAt,
    horizon = horizon.name,
    action = action.name,
    price = price,
    entryLow = plan.preferredEntry?.low,
    entryHigh = plan.preferredEntry?.high,
    stop = plan.stop,
    targets = plan.targets.map { it.price },
    scores = components.associate { it.component.name to it.score },
    overall = overall,
    confidenceRaw = confidenceRaw,
    confidence = confidence,
    modelVersion = modelVersion,
    inputAsOf = inputAsOf,
    summary = explanation,
)

/**
 * Append-only JSON-lines store. [append] refuses to replace an existing id, so an old recommendation can never be
 * rewritten by a later calculation.
 */
class RecommendationHistory(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun append(record: RecommendationRecord): Boolean {
        if (all().any { it.id == record.id }) return false
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(RecommendationRecord.serializer(), record) + "\n", Charsets.UTF_8)
        return true
    }

    @Synchronized
    fun all(): List<RecommendationRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null else try {
                json.decodeFromString(RecommendationRecord.serializer(), line)
            } catch (e: Exception) {
                null // a damaged line is skipped, not repaired, so the rest of the history stays intact
            }
        }
    }

    fun forSymbol(symbol: String): List<RecommendationRecord> = all().filter { it.symbol == symbol }.sortedByDescending { it.createdAt }
}
