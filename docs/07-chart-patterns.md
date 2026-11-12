# Phase 4: chart pattern recognition

## 1. Structure

```
Closed bars of one timeframe (validated, Phase 2)
   │  PatternCache.get(source, symbol, tf, candles, now)      ── LRU, same closed-bar key as TechnicalCache
   ▼
PatternEngine.detect: walks forward ONE CLOSED BAR AT A TIME (t = 0 … n−1)
   ├─ Swings.pivots          fractal swing highs/lows, window w (default 5); a swing at j becomes known at j + w
   ├─ Swings.ZigZag          alternating swings, legs < minSwingAtr × ATR dropped; fed only swings known by t
   ├─ ChartDetectors         reversal (zigzag tail) · trendline family (fitted lines) · flags · cup and handle
   ├─ Candlesticks           9 patterns completed at the close of bar t
   ├─ Tracker (per pattern)  lifecycle FORMING → CONFIRMED → (targets | FAILED) or → INVALIDATED; every change is an
   │                         event stamped with the bar at which it became knowable
   └─ Context                ATR(14), relative volume, prior trend, higher-timeframe trend (completed bars only)
   ▼
PatternReport → Technical screen: patterns drawn on the candlestick chart + filterable pattern cards
```

All of it is pure Kotlin with no Android imports, and it is independent of the indicator engine (it reuses `ta.Rolling` and `ta.VolatilityCalc` only).

## 2. Detection algorithm

The ten steps from the brief map to the code as follows.

| # | Step | Implementation |
|---|---|---|
| 1 | Swing highs/lows | `Swings.pivots`: bar j is a swing high if its high is strictly above the w highs before and ≥ the w highs after (lows mirrored) |
| 2 | Configurable pivot windows | `PatternConfig.pivotWindow` (default 5); `minSwingAtr` (1.5) filters zigzag noise |
| 3 | ATR normalisation | Every price distance is in ATR(14) units, so the same settings work for any price and timeframe (a test checks that scaling prices ×0.01 and ×100 gives identical results) |
| 4 | Geometry | Equal peaks within `equalityAtr`; trendline least-squares fits within `trendlineFitAtr`; slopes in ATR over the pattern; converging (end width ≤ 75% of start) vs parallel; H&S head prominence, shoulder symmetry (time ratio 0.4–2.5), neckline slope; cup roundness |
| 5 | Support/resistance boundaries | Necklines, flat tops and bottoms, the fitted upper and lower lines, flag envelopes, cup rims |
| 6 | Duration | `minPatternBars` 15 to `maxPatternBars` 300; every leg ≥ `minLegBars` 3 (no one-day spikes); poles ≤ 25 bars; flags 5–25 bars; cups ≥ 25 bars; handle ≤ ⅓ of the cup |
| 7 | Breakout/breakdown levels | Each pattern defines a bullish and a bearish trigger as a function of the bar, so sloping lines are projected to the bar being tested |
| 8 | Close confirmation | Only a **close** beyond the trigger by `confirmBufferAtr` (0.1 ATR) confirms; a wick does not |
| 9 | Relative volume | Breakout-bar volume ÷ mean of the 20 bars before it; ≥ 1.3× = volume-confirmed. Formation volume trend (second half ÷ first half) is also reported |
| 10 | Higher timeframe | Intraday → daily, daily → weekly, weekly → monthly, built by resampling the same bars. Trend = close vs EMA10, EMA10 slope, EMA10 vs EMA30, using **completed** higher-timeframe bars only |

### Pattern rules (beyond the shared steps)

| Pattern | Defining rules | Breakout | Stop (≥ 0.5 ATR past the level) | Targets |
|---|---|---|---|---|
| Double top / bottom | 2 peaks within 1 ATR, height ≥ 2.5 ATR, 15–300 bars apart, prior move into peak 1 ≥ 0.75 × height | Neckline (the trough) | Beyond the higher top + 0.25 ATR | Neckline ∓ {0.618, 1, 1.618} × height |
| Triple top / bottom | 3 peaks within 1 ATR of each other | Lower trough (higher for bottoms) | Beyond the extreme peak | Measured move |
| Head & shoulders (and inverse) | Head ≥ 1 ATR beyond both shoulders; shoulders within 2 ATR of each other and ≥ 40% of the height above the neckline; neckline through the two troughs, slope ≤ max(1.5 ATR, 35% of height) | Neckline projected to the bar | Beyond the right shoulder | Measured move (head to neckline) |
| Triangles, wedges, rectangle, channels | 4–7 alternating swings; both lines fit within 0.75 ATR; no close outside during formation; classification by slope (flat = < 1 ATR over the pattern) and convergence | Upper / lower line | Beyond the last opposite swing | Measured move (start width; mean width for parallel shapes) |
| Bull / bear flag | Pole ≥ 4 ATR in ≤ 25 bars; flag 5–25 bars, retrace ≤ 50%, no new extreme, envelope lines flat or against the pole, width ≤ ½ pole | Upper (lower) flag envelope | Beyond the flag low (high) | Breakout ± {0.618, 1, 1.618} × pole |
| Cup and handle | Rims within 1.5 ATR, nothing above the rim in between, depth ≥ 3 ATR and ≤ 50%, ≥ 30% of closes in the lower half, bottom in the middle 60%, prior uptrend; handle in the upper half, ≥ 1 ATR deep | Right rim | Below the handle low | Rim + {0.618, 1, 1.618} × depth |
| Candlesticks | Nison definitions with sizes in ATR and the right prior trend (≥ 1 ATR over 10 bars) | Pattern high (bullish) / low (bearish) | Beyond the opposite extreme | 1R, 2R, 3R from the entry |

**Directional vs two-sided.** Symmetrical triangles and rectangles confirm on either side and take the breakout's direction (the prior trend is shown while forming). Ascending and descending channels are bullish and bearish continuations: a close beyond the far line confirms acceleration, while a close through the near line invalidates the pattern (the channel broke).

**Documented variants.**
- Piercing, dark cloud and star patterns only need the open beyond the prior *close*, not beyond the prior low/high or body (Nison's gap), because NSE daily gaps are small.
- A doji is reported only inside a trend, where it signals possible exhaustion.
- Two-point Fibonacci-style measured moves are used. Bulkowski-style success-rate targets are not.

## 3. Lifecycle

| Status | Meaning |
|---|---|
| **Forming** | All swing points confirmed; waiting for a close through the breakout level |
| **Confirmed** | A candle closed through the breakout level. Entry = that close; stop and targets are frozen at that moment |
| **Failed** | Confirmed, then a close through the stop-loss before the first target |
| **Invalidated** | Never confirmed: a close through the invalidation level, no breakout within the pattern's own width (apex for triangles, 3 bars for candlesticks), the structure evolved (a double top becoming a triple top; a rectangle becoming a triangle), or a flag/handle stopped fitting |

After confirmation the engine keeps tracking: each target touch is a `TARGET_HIT` event. Tracking closes on the final target, on a stop after a target, or after max(30, 3 × pattern width) bars.

A pattern that was already dead when it could first be known is never reported. This happens when a swing is confirmed only after price has broken the wrong way.

## 4. No look-ahead

What is known at bar t:
- swings confirmed by t (j + w ≤ t)
- the zigzag built from them
- closes, volumes and ATR of bars ≤ t
- higher-timeframe bars that **finished** before t's own higher-timeframe bar began

There are no exceptions. The forming bar is dropped before analysis, and index "volume" is ignored, as in Phase 3.

Every state change is an event `(type, index, knownIndex, knownAt, detail)`. **The defining test (`NoLookAheadTest`):** running the engine on bars 0..t must reproduce exactly the full run's events with `knownIndex ≤ t`, matching in type, bar, level and text. This is checked at about 70 cut points on a synthetic series and 5 cut points on each of the 15 real histories. A second test replaces every bar after t with a different future and checks that no earlier event changes.

`detectedAt` can be later than the last swing: a right shoulder is only knowable 5 bars after it. A breakout during those bars is reported as confirmed *at the bar it became known*. The breakout bar itself is kept for drawing.

## 5. Confidence (0–100)

| Part | Max | Rule |
|---|---|---|
| Geometry | 35 | 10 + 25 × fit, where fit (0–1) measures how cleanly the definition is met: peak equality, line residuals, symmetry, flag tightness, cup roundness |
| Size | 10 | Height in ATR above the minimum (0 at the minimum, full at twice it) |
| Breakout | 20 | Confirmed by a close |
| Volume | 20 | Breakout ≥ 1.3×: 12–20. Below: ≤ 6. Forming continuation patterns: contracting volume 8, steady 5, expanding 2. No volume data: a neutral 10 |
| Higher timeframe | 15 | Aligned 15, neutral or unknown 7, against 0 |

Failed and invalidated patterns are capped at 30. The weights are judgement, **not fitted to outcomes**; they rank the quality of the evidence, not a probability of success.

## 6. AI explanations

`DetectedPattern.facts()` is the only payload an AI explainer may receive (Phase 6). It holds the pattern, direction, status, levels, volume and higher-timeframe facts produced by this engine. The explainer may rephrase them but may not add, remove or alter patterns or levels. Today the explanation is deterministic template text.

## 7. Validation (71 tests)

| Suite | Tests | What it proves |
|---|---|---|
| `ChartPatternTest` | 34 | Every chart pattern built from waypoints, bearish cases as exact price mirrors of the bullish ones. Checks levels, measured moves, stops and volume confirmation, and each lifecycle: forming, confirmed, failed, invalidated, expired, targets hit, superseded. Near-misses are rejected: unequal tops, a head that doesn't stand out, one-bar legs, V-bottoms, a pullback through rising support. Also covers the configurable pivot window and scale invariance |
| `CandlestickTest` | 19 | All 9 candlesticks on hand-specified bars with their bearish mirrors. Covers confirmation, invalidation, expiry and failure; patterns without the right prior trend are not reported; bodies that don't engulf or stars that don't close past the midpoint are rejected |
| `NoLookAheadTest` | 8 | Prefix runs equal the full run (synthetic and all 15 real histories); a rewritten future doesn't change past events; swing confirmation lag; higher timeframe uses completed bars only; the forming bar is excluded; zigzag behaviour |
| `HistoricalPatternTest` | 10 | Six real examples found by the engine and **checked by hand against the raw bars**, plus invariants over every detection in 11 years × 15 symbols, coverage of all 25 types in real data, index volume ignored, and speed |

**Hand-verified real examples:**
- TCS head and shoulders, confirmed 11 Feb 2025
- RELIANCE cup and handle, 7 Jul 2017
- RELIANCE inverse head and shoulders, 12 Jan 2018
- Infosys rectangle breakdown on results day, 24 Apr 2015, at 5× volume
- TCS descending triangle, 11 Jul 2025
- RELIANCE double bottom, 24 Apr 2018

**Real-history data.** `tools/pattern-history/fetch_history.py` downloads fixed-range daily bars (2015–2025) for 14 NIFTY stocks and the index. The output folder is **git-ignored** because Yahoo's terms are unclear on redistribution. Without it the 10 historical tests and the real-data part of `NoLookAheadTest` are *skipped*, not passed, and CI runs only the synthetic suites. To run them in CI, commit the folder (if licensing allows) or fetch it in a CI step.

## 8. What a run over real data shows (15 symbols, 2015–2025, daily)

Every type occurs. Outcomes differ widely by type:
- Double bottoms: 72 confirmed vs 14 failed.
- Symmetrical triangles: 69 confirmed vs 88 failed.
- Channels: most end invalidated, because the channel breaks against its slope.

These counts are descriptive only. Whether any pattern earns a vote in the rating must be decided by the roadmap's out-of-sample, after-costs test against a random baseline, which has not been done.

## 9. Limitations

- Patterns are detected on one timeframe's bars at a time. Very large patterns need enough history in the analysed window (3 years of daily bars in the app).
- Detection waits for swing confirmation (5 bars by default), so patterns are reported late by design. Shrinking `pivotWindow` reports them sooner but noisier.
- The volume profile of a breakout uses whole-bar volume; intraday participation isn't known from daily bars.
- Thresholds are textbook-inspired defaults, not optimised. Confidence is a quality score, not a probability.
