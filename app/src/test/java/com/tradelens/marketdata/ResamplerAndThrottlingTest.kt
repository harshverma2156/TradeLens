package com.tradelens.marketdata

import com.tradelens.marketdata.TestTime.ist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ResamplerAndThrottlingTest {
    private val cal = MarketCalendar()

    @Test fun fiveMinuteBarsFromOneMinute() {
        val m1 = TestTime.sessionBars(LocalDate.of(2026, 11, 10), Timeframe.M1)
        val m5 = Resampler.aggregate(m1, Timeframe.M5, cal)
        assertEquals(75, m5.size)
        assertEquals(m1[0].open, m5[0].open, 1e-9)
        assertEquals(m1[4].close, m5[0].close, 1e-9)
        assertEquals(m1.take(5).maxOf { it.high }, m5[0].high, 1e-9)
        assertEquals(m1.take(5).sumOf { it.volume }, m5[0].volume)
    }

    @Test fun hourlyLastBarIsShortLikeTheExchange() {
        val m15 = TestTime.sessionBars(LocalDate.of(2026, 11, 10), Timeframe.M15)
        val h1 = Resampler.aggregate(m15, Timeframe.H1, cal)
        assertEquals(7, h1.size)
        assertEquals(ist(2026, 11, 10, 15, 15), h1.last().time)
        assertEquals(m15.last().close, h1.last().close, 1e-9)
    }

    @Test fun weeklyAndMonthlyFromDaily() {
        val d = TestTime.dailyBars(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30))
        val w = Resampler.aggregate(d, Timeframe.W1, cal)
        val m = Resampler.aggregate(d, Timeframe.MN1, cal)
        assertEquals(2, m.size)
        assertEquals(ist(2026, 10, 1), m[0].time)
        assertTrue(w.all { java.time.Instant.ofEpochMilli(it.time).atZone(MarketCalendar.IST).dayOfWeek == java.time.DayOfWeek.MONDAY })
        assertEquals(d.sumOf { it.volume }, w.sumOf { it.volume })
    }

    @Test fun sourceTimeframeSelection() {
        assertEquals(Timeframe.M15, Resampler.sourceFor(Timeframe.M30, setOf(Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.D1)))
        assertEquals(Timeframe.D1, Resampler.sourceFor(Timeframe.W1, setOf(Timeframe.D1)))
        assertEquals(Timeframe.M30, Resampler.sourceFor(Timeframe.M30, setOf(Timeframe.M30)))
        assertNull(Resampler.sourceFor(Timeframe.M1, setOf(Timeframe.M5, Timeframe.D1)))  // can't make smaller bars
    }

    // ---------------- rate limiting, retry, coalescing (virtual time)

    @Test fun rateLimiterSpacesCallsAfterBurst() = runTest {
        val limiter = RateLimiter(permitsPerSecond = 2.0, burst = 2, now = { testScheduler.currentTime }, sleep = { delay(it) })
        val times = mutableListOf<Long>()
        repeat(5) { limiter.acquire(); times += testScheduler.currentTime }
        assertEquals(listOf(0L, 0L, 500L, 1000L, 1500L), times)
    }

    @Test fun retryBacksOffExponentiallyThenSucceeds() = runTest {
        val calls = AtomicInteger()
        val waits = mutableListOf<Long>()
        val policy = RetryPolicy(maxAttempts = 4, initialDelayMs = 100, factor = 2.0, jitter = 0.0)
        val v = withRetry(policy, { RetryDecision.Retry }, Random(1), sleep = { waits += it }) {
            if (calls.incrementAndGet() < 4) throw IOException("flaky") else "ok"
        }
        assertEquals("ok", v)
        assertEquals(listOf(100L, 200L, 400L), waits)
    }

    @Test fun retryGivesUpAfterMaxAttempts() = runTest {
        val calls = AtomicInteger()
        try {
            withRetry(RetryPolicy(maxAttempts = 3, jitter = 0.0), { RetryDecision.Retry }, sleep = {}) { calls.incrementAndGet(); throw IOException("down") }
            fail("should throw")
        } catch (e: IOException) { assertEquals(3, calls.get()) }
    }

    @Test fun nonRetryableFailsImmediatelyAndRetryAfterIsHonoured() = runTest {
        val calls = AtomicInteger()
        try {
            withRetry(RetryPolicy(), { RetryDecision.Fail }, sleep = {}) { calls.incrementAndGet(); throw IllegalStateException("404") }
            fail()
        } catch (e: IllegalStateException) { assertEquals(1, calls.get()) }
        val waits = mutableListOf<Long>()
        var n = 0
        withRetry(RetryPolicy(maxDelayMs = 60_000), { RetryDecision.RetryAfter(30_000) }, sleep = { waits += it }) { if (n++ == 0) throw IOException("429") }
        assertEquals(listOf(30_000L), waits)
    }

    @Test fun concurrentIdenticalRequestsAreCoalesced() = runBlocking {
        val inflight = InFlight<String, Int>(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val calls = AtomicInteger()
        val results = (1..10).map { async { inflight.run("same") { calls.incrementAndGet(); delay(100); 42 } } }.awaitAll()
        assertEquals(List(10) { 42 }, results)
        assertEquals(1, calls.get())
        inflight.run("same") { calls.incrementAndGet(); 1 }        // finished → a new call goes through
        assertEquals(2, calls.get())
    }
}
