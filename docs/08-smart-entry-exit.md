# Phase 5: smart entry, exit and price-target engine

## 1. Structure

```
Stock detail page (StockDetailViewModel)
  ├─ daily bars + DataStatus + corporate actions        (Phase 2, MarketRepository)
  ├─ TechnicalCache → TechnicalReport                    (Phase 3, closed bars)
  ├─ PatternCache   → PatternReport                      (Phase 4, same closed bars)
  ├─ live quote (polled every 5 s while the market is open, only while the page is visible)
  ├─ paper holding (Room)  +  RiskSettings (capital, risk %, max position %)
  ▼
SmartEntryEngine.evaluate  (trade/, pure Kotlin)
  ├─ gates           stale data · missing candles · history · event risk · bearish picture · failed pattern
  ├─ LevelMap        every level, tagged and weighted  →  Zones.cluster  →  support / resistance zones
  ├─ strategies      AGGRESSIVE · CONSERVATIVE · BREAKOUT  (each: entry, Exits, RiskCalc, rationale)
  ├─ holder advice   HOLD · PARTIAL PROFIT BOOKING · EXIT
  └─ LongTermView    separate; no stop-loss, no buy signal
  ▼
SignalStabilizer.publish  (cooldown + hysteresis + level tolerance)  →  Recommendation  →  UI
```

The live price only decides where price sits relative to the levels. Every confirmation (reversal close, breakout close, pattern status) uses closed bars.

## 2. Levels and zones

| Source | Weight | From |
|---|---|---|
| Demand / supply zone | 2.5, −0.5 per retest | Base candle (body ≤ 0.8 ATR) followed by a ≥ 1.5 ATR impulse within 3 bars; dies once a later close goes through it |
| Historical support / resistance | 1.0 + 0.5 × touches (max 3) | Clustered ±3-bar swing points, last 250 bars (Phase 3) |
| Previous swing high / low | 1.0 | 5-bar fractals (Phase 4 `Swings.pivots`) |
| EMA 20 / 50 / 200 | 1.5 / 2.0 / 2.5 | Phase 3 |
| Fibonacci 38.2 / 50 / 61.8 / 78.6% | 1.2 / 1.2 / 1.5 / 0.8 | Dominant 126-bar swing |
| Volume profile POC / VAH / VAL | 2.0 / 1.0 / 1.0 | 126 bars, 70% value area |
| Session VWAP / anchored VWAP | 1.0 / 1.5 | Intraday only / from the dominant swing |
| Chart pattern level, breakout retest | 2.0 | Phase 4 (bullish confirmed → retest support; forming → trigger; bearish → overhead resistance) and resistances closed through in the last 5 bars |
| 52-week high | 1.5 | Target candidate |
| Fibonacci extensions, pattern measured moves | — | **Targets only**, never support |

Levels within 0.6 ATR chain into a zone. Its score is the summed weights × (1 + 0.15 per extra distinct source). A zone within 0.25 ATR of the price, or one the latest bar traded through and closed within 0.5 ATR of, is *at* the price: it is both the support being tested and the resistance a breakout must clear.

## 3. Strategies

| | Entry | Status | Stop structure |
|---|---|---|---|
| **Aggressive** | Strongest support zone within 4 ATR (score ≥ 2.5; preference falls 20% per ATR of distance). At the zone: buy now. Above it: limit at the zone top. | BUY NOW / WAIT FOR CORRECTION | Zone low and the lows of bars testing it |
| **Conservative** | Same zone. It must have been touched in the last 3 bars, then a close above the touching bar's high + 0.1 ATR (or a confirmed bullish Phase 4 candlestick), with ≥ 2 of: MACD histogram rising, RSI rising and ≥ 40, Stochastic %K > %D, close > EMA 20, MFI rising. | BUY NOW / WAIT FOR CONFIRMATION / WAIT FOR CORRECTION | Reaction low |
| **Breakout** | A resistance closed through in the last 5 bars, or a just-confirmed bullish pattern. Within 1.5 ATR of the level: buy now. Further: wait for the retest. Not broken yet: nearest resistance zone or forming bullish pattern within 4 ATR; buy on a close above it. Volume ≥ 1.3× is preferred (score and risk note) but not required. | BUY NOW / WAIT FOR CORRECTION / WAIT FOR BREAKOUT | Breakout level / bar low; before the break, the higher of the 10-bar base and the nearest support |

A close back below a breakout level by 0.25 ATR is a **failed breakout**. It blocks all three plans.

## 4. Exits, risk and position size

- **Stop-loss:** the structure minus 0.3 ATR. If that's tighter than 0.75 ATR it is widened. If it's wider than 3.5 ATR, **no logical stop** exists and the plan is rejected.
- **Targets:** come only from structure: resistance zones (placed just under them), pattern measured moves, Fibonacci extensions and the 52-week high, each ≥ 0.5 ATR beyond the entry and 0.6 ATR apart. Only when no structure is left are remaining slots filled with **labelled** 2-ATR volatility projections. There are no fixed-percentage targets anywhere: the same stock at twice the volatility gets targets twice as far away (tested).
- **Trailing stop:** chandelier exit, the 22-bar high − 3 ATR, or the Supertrend line if higher. It is applied after T1 and only ever moves up.
- **Partial profit:** book ⅓ at T1 and move the stop to entry; ⅓ at T2 and raise the stop to T1; exit the rest at T3 or keep trailing.
- **Invalidation and exit conditions:** a close below the zone, reaction low or breakout level before entry cancels the order. After entry the stop-loss governs. Other exit conditions:
  - a time stop of 20 sessions without reaching T1
  - a confirmed bearish pattern, or the indicator summary turning to Sell
- **Viability:** T1 ≥ 1.0R and T2 ≥ 2.0R, otherwise the plan is rejected as **poor risk/reward**.
- **Sizing:** quantity = min(capital × risk % ÷ risk per share, capital × max position % ÷ entry, capital ÷ entry), rounded down. The card shows:
  - risk per share and stop distance %
  - reward and R:R to each target, with each target's distance %
  - quantity, capital required, exposure % and the maximum loss at the stop
  - which limit was binding
- **Prices:** entries round to the ₹0.05 tick; stops and targets round down.

## 5. Status and market conditions

| Holder? | Status |
|---|---|
| No | **AVOID** if any blocker applies. Otherwise BUY NOW if any viable plan is actionable now (highest score wins), else the best viable plan's WAIT status. |
| Yes (paper portfolio) | **EXIT**: the latest close went below the support that was holding before it (zone low − 0.3 ATR), a bearish pattern is active, or the Sell vote coincides with a close below EMA 50. **PARTIAL PROFIT BOOKING**: in profit and having *rallied* into resistance, or RSI ≥ 75 after a > 2 ATR gain. Otherwise **HOLD**. The chandelier is shown as the trailing level for after T1; it doesn't trigger EXIT by itself. |

**Blockers (no buy signal):**
- **Stale data:** the series or quote is STALE/OFFLINE.
- **Too little history or missing candles:**
  - fewer than 200 daily bars
  - a gap of more than 3 weekdays in the last 60 bars, or Phase 2 missing-bar warnings in that window
- **Unresolved major event:**
  - a split or bonus ex-date in the next 10 days or the last 5 bars
  - a gap ≥ 3 ATR or a range ≥ 4 ATR in the last 3 sessions, which counts as resolved after 3 sessions
  - an upcoming dividend is a warning only
- **Bearish picture:**
  - the indicator families vote Sell
  - a long-term downtrend: below a falling 200-day EMA with the weekly trend down
  - a bearish chart pattern confirmed in the last 20 sessions with price still below its breakdown level
- **Failed pattern:** a bullish pattern failed in the last 10 sessions, or a breakout failed.
- **Poor reward/risk, no logical stop, no valid setup:** when no plan qualifies.

Every recommendation carries a rationale:
- why this entry was chosen
- what supports it: the zone's member levels, bullish indicator readings, confirmations and patterns
- the price levels that matter
- what would invalidate it
- the remaining risks: bearish readings, events, thin volume, nearby resistance, projected targets, rejections

## 6. Updates and signal stability

- **Recalculation:** the detail page polls the quote every 5 s (2 s for a real-time provider) while the market is open and the page is visible. Each price change re-runs the engine. Indicator and pattern reports come from the closed-bar caches, so only the cheap level placement is redone. Daily bars refresh every 5 minutes.
- **`SignalStabilizer`** (`StabilityConfig`):
  - Changes **into** AVOID, EXIT or PARTIAL PROFIT BOOKING apply at once.
  - Any other status change needs both **15 minutes** since the last change *and* price **0.15 ATR** past the decision boundary (each plan reports its margin). Otherwise the old status is held and the pending one is shown with the reason.
  - Entry/stop moves under **0.2 ATR** keep the published levels.
  - Changing capital, risk % or max position resets the stabilizer, so sizing always follows settings.

## 7. Long-term view

`LongTermView` is a separate card: the long-term trend (200-day average and weekly trend) and context (distance from the 200-day average and the 52-week range). It never sets a stop-loss and never produces a buy signal. **No valuation-based accumulation advice is given**, because the data source has no fundamentals; the card says so.

## 8. Tests (42)

| Suite | Tests | Covers |
|---|---|---|
| `RiskAndExitTest` | 15 | Sizing by risk budget, exposure cap and cash; rounding down and zero quantity; percentages and R:R; tick rounding; stop placement (buffer, widening, too wide, invalid); structural targets (ordering, minimum gap, clustering, tick rounding, labelled volatility fill, scaling with ATR); chandelier; partial exits; zone clustering and scoring |
| `SmartEntryEngineTest` | 18 | Aggressive at and above a zone; multi-source zones; conservative touch → confirmation → buy; breakout wait → buy → no-chase → failed; every gate (stale, offline, short history, missing candles, price shock and settling, split vs dividend, bearish picture, poor reward/risk, no logical stop); HOLD / PARTIAL / EXIT; plan invariants over 100+ random market states; long-term view separation |
| `SignalStabilizerTest` | 9 | Cooldown, hysteresis, immediate risk-reducing changes, slow recovery from AVOID, cooldown measured from real changes, level tolerance, reset, configuration |

Replaying the engine every 15 sessions over 2023–2025 for 14 NSE stocks gave this distribution (descriptive only, not a performance claim):

| Status | Share |
|---|---|
| AVOID | 60% (mostly genuine Sell votes in that period's IT/FMCG downtrends) |
| WAIT FOR CORRECTION | 21% |
| BUY NOW | 16% |
| WAIT FOR CONFIRMATION / BREAKOUT | 3% |

## 9. Limitations

- **No back-testing yet.** The rules are not validated against outcomes; the roadmap's out-of-sample test is still to do.
- **No results calendar.** Event risk is inferred from price shocks and corporate actions; earnings dates can be passed in as `extraEvents` once a source exists.
- **Not tick-by-tick.** "Live" means polling: Yahoo has no free streaming feed. Real tick data needs a broker WebSocket through the backend.
- **Long only.** Indian retail cash-market delivery can't be shorted, so there are no short setups.
