package com.tradelens.marketdata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.min
import kotlin.random.Random

/**
 * Token-bucket rate limiter: up to [burst] calls at once, refilled at [permitsPerSecond].
 * [now] and [sleep] are injectable so tests can run on virtual time.
 */
class RateLimiter(
    private val permitsPerSecond: Double,
    private val burst: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var tokens = burst.toDouble()
    private var last = now()

    suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock {
                val t = now()
                tokens = min(burst.toDouble(), tokens + (t - last) / 1000.0 * permitsPerSecond)
                last = t
                if (tokens >= 1.0) { tokens -= 1.0; 0L } else ceil((1.0 - tokens) / permitsPerSecond * 1000).toLong().coerceAtLeast(1)
            }
            if (wait == 0L) return
            sleep(wait)
        }
    }
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    val factor: Double = 2.0,
    val jitter: Double = 0.2,
) {
    /** Delay before attempt [attempt] (1-based retry count), with ±jitter. */
    fun delayFor(attempt: Int, random: Random): Long {
        var d = initialDelayMs.toDouble()
        repeat(attempt - 1) { d *= factor }
        d = min(d, maxDelayMs.toDouble())
        return (d * (1 + jitter * (random.nextDouble() * 2 - 1))).toLong().coerceAtLeast(0)
    }

    companion object { val NONE = RetryPolicy(maxAttempts = 1) }
}

/** What the caller's classifier says about a failure. */
sealed interface RetryDecision {
    data object Fail : RetryDecision
    data object Retry : RetryDecision
    /** Server asked us to wait (e.g. HTTP 429 Retry-After). */
    data class RetryAfter(val ms: Long) : RetryDecision
}

/** Runs [block], retrying with exponential backoff while [classify] allows it. Never retries cancellation. */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    classify: (Throwable) -> RetryDecision,
    random: Random = Random.Default,
    sleep: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val decision = classify(e)
            if (decision == RetryDecision.Fail || attempt >= policy.maxAttempts) throw e
            val wait = when (decision) {
                is RetryDecision.RetryAfter -> min(decision.ms, policy.maxDelayMs)
                else -> policy.delayFor(attempt, random)
            }
            sleep(wait)
            attempt++
        }
    }
}

/**
 * Collapses concurrent requests for the same key into one call. A second caller asking for the same
 * thing while the first is in flight gets the same result instead of hitting the provider again.
 */
class InFlight<K, V>(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val running = HashMap<K, Deferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val d = mutex.withLock {
            running[key] ?: scope.async {
                try { block() } finally { mutex.withLock { running.remove(key) } }
            }.also { running[key] = it }
        }
        return d.await()
    }
}
