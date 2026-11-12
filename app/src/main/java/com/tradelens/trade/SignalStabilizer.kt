package com.tradelens.trade

import kotlin.math.abs

/**
 * Keeps published recommendations from flickering on tiny price moves.
 *
 *  1. Changes INTO a risk-reducing status (EXIT, AVOID, PARTIAL PROFIT BOOKING) apply immediately: safety first.
 *  2. Any other status change needs BOTH the cooldown since the last change AND the new state's margin (how far price
 *     is past the decision boundary, in ATR) to be at least the hysteresis. Otherwise the previous status is held and
 *     the pending one is reported in [Recommendation.stability].
 *  3. With the status unchanged, a plan whose entry and stop moved less than the level tolerance keeps its previous
 *     levels, so a few paise of drift doesn't rewrite the numbers.
 * Position sizing always follows the latest settings (callers [reset] after a settings change).
 */
class SignalStabilizer(private val cfg: StabilityConfig = StabilityConfig()) {
    private data class Published(val rec: Recommendation, val since: Long)
    private val last = HashMap<String, Published>()

    @Synchronized
    fun publish(next: Recommendation, now: Long = next.computedAt): Recommendation {
        val prev = last[next.symbol]
        val out = if (prev == null) next else decide(prev.rec, prev.since, next, now, cfg)
        last[next.symbol] = if (prev == null || out.status != prev.rec.status) Published(out, now) else Published(out, prev.since)
        return out
    }

    @Synchronized fun reset(symbol: String) { last.remove(symbol) }

    companion object {
        fun decide(prev: Recommendation, since: Long, next: Recommendation, now: Long, cfg: StabilityConfig): Recommendation {
            if (next.status != prev.status) {
                if (next.status.riskReducing) return next
                val waited = now - since
                val margin = next.primaryPlan?.marginAtr ?: 1.0
                if (waited >= cfg.cooldownMs && margin >= cfg.hysteresisAtr) return next
                val why = buildList {
                    if (waited < cfg.cooldownMs) add("cooldown: ${(cfg.cooldownMs - waited + 59_999) / 60_000} min left")
                    if (margin < cfg.hysteresisAtr) add("price only ${String.format(java.util.Locale.US, "%.2f", margin)} ATR past the boundary (needs ${cfg.hysteresisAtr})")
                }.joinToString("; ")
                return prev.copy(
                    price = next.price, computedAt = next.computedAt, position = next.position, dataAsOf = next.dataAsOf,
                    stability = StabilityNote(prev.status, next.status, "Holding ${prev.status.label}; ${next.status.label} not confirmed yet ($why)."),
                )
            }
            // Same status: keep plans whose levels barely moved.
            val tol = cfg.levelToleranceAtr * next.atr
            val plans = next.strategies.map { np ->
                val pp = prev.strategies.firstOrNull { it.type == np.type } ?: return@map np
                val same = pp.status == np.status && pp.viable == np.viable && abs(pp.entry - np.entry) < tol &&
                    abs((pp.exit?.stopLoss ?: 0.0) - (np.exit?.stopLoss ?: 0.0)) < tol
                if (same) pp else np
            }
            val keptPrimary = next.primary == prev.primary && plans.firstOrNull { it.type == next.primary } === prev.primaryPlan
            return next.copy(strategies = plans, headline = if (keptPrimary) prev.headline else next.headline,
                rationale = if (keptPrimary) prev.rationale else next.rationale, stability = null)
        }
    }
}
