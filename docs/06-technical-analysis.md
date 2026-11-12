# Phase 3: technical analysis engine

## 1. Structure

```
Room candles (validated, Phase 2)
   │  MarketRepository.candles(symbol, tf, from)
   ▼
TechnicalCache.get(source, symbol, tf, candles, now)        ── LRU, keyed by the identity of the CLOSED bars
   ▼
TechnicalEngine.analyze(...)
   ├─ splits off the forming bar (barEnd(t, tf) > now): charted, never used for signals
   ├─ calculation services (pure Kotlin, no Android, independently reusable)
   │     Rolling        SMA · EMA · RMA (Wilder) · σ · mean deviation · rolling max/min
   │     TrendCalc      DMI/ADX · Supertrend · Ichimoku · Parabolic SAR
   │     MomentumCalc   RSI · MACD · Stochastic · Stochastic RSI · CCI · Williams %R
   │     VolatilityCalc True range · ATR · Bollinger · Keltner · Donchian
   │     VolumeCalc     OBV · A/D · MFI · session VWAP · anchored VWAP · volume profile (POC, VAH, VAL)
   │     LevelsCalc     dominant swing · Fibonacci retracement/extension · pivots · support/resistance
   ├─ interpretation → IndicatorReading per indicator
   └─ family votes → TechnicalSummary (Buy / Sell / Neutral)
   ▼
TechnicalReport → TechnicalScreen (cards, chart overlays, oscillator panel, timeframe chips, summary)
```

`analysis/Indicators.kt` (`Ta`) is a thin facade over the same services for the older rating engine, so each formula exists exactly once.

**Series conventions (whole `ta` package):** output index *i* describes bar *i* and uses only bars 0..*i*. `null` means warm-up or undefined, never a 0-fill. Volumes are summed as `Double`, so they cannot overflow.

## 2. Indicators, parameters and formula variants

Warm-up is the number of bars until the first value appears. Before that, the reading is marked *not ready*. It then shows "needs about N bars, has M", carries strength 0, and does not vote.

| Indicator | Parameters | Warm-up | Definition used | Documented variants |
|---|---|---|---|---|
| EMA | 9, 20, 21, 50, 100, 200 | n | α = 2/(n+1), seeded with the SMA of the first n values (TA-Lib) | pandas `ewm(adjust=False)` seeds with the first value; the gap decays as (1−α)^k |
| SMA | 50, 100, 200 | n | arithmetic mean of n closes | — |
| Supertrend | ATR 10, ×3 | 11 | (H+L)/2 ± k·ATR; final bands ratchet; flips when the close crosses the current final band | TradingView tests against the previous bar's band (flips can be one bar later) |
| ADX / DMI | 14 | 2n−1 | Wilder: +DM/−DM, RMA-smoothed DI, ADX = RMA(DX) | Wilder's running sums give identical ratios |
| Ichimoku | 9 / 26 / 52, disp. 26 | 78 for the full score | midpoints of HH/LL; spans shifted forward 26; the cloud for bar t is the spans from t−26 | — |
| Parabolic SAR | 0.02 / 0.2 | 3 | Wilder; SAR clamped by the prior two lows/highs | Initialisation differs by library; ours starts long at bar 2, as `ta` does |
| RSI | 14 | n+1 | Wilder RMA of gains/losses, SMA-seeded; no losses → 100; no movement → 50 | pandas/`ta` seed via ewm; values converge |
| MACD | 12 / 26 / 9 | 34 | EMA12 − EMA26; signal EMA9; histogram = difference | — |
| Stochastic | 14 / 3 / 3 (slow) | 18 | fast %K, %K = SMA3, %D = SMA3; flat range → 50 | `ta` returns NaN on a flat range |
| Stochastic RSI | 14 / 14 / 3 / 3 | 33 | stochastic of RSI, scaled 0–100; flat → 50 | `ta` scales 0–1 |
| CCI | 20, 0.015 | n | (TP − SMA)/(0.015·mean deviation); flat → 0 | — |
| Williams %R | 14 | n | −100·(HH−C)/(HH−LL); flat → −50 | — |
| ATR | 14 | n | Wilder RMA of true range, seeded with the mean of the first n | — |
| Bollinger | 20, 2 | n | SMA ± 2·population σ (ddof 0) | some tools use sample σ (ddof 1) |
| Keltner | EMA 20, ATR 10, ×2 | 20 | EMA ± k·ATR (Raschke) | Keltner's 1960 original: SMA(TP) ± SMA(H−L) |
| Donchian | 20 | n (+1 for the breakout test) | highest high / lowest low; breakout compares with the previous bar's channel | — |
| OBV | — | 30 for the signal | Granville: an unchanged close adds 0; OBV₀ = 0 | `ta` adds +V on an unchanged close and starts at V₀ |
| A/D | — | 21 for the signal | Chaikin CLV × V, cumulative; H = L → CLV 0 | — |
| MFI | 14 | n+1 | TP·V split by TP direction; no negative flow → 100; no flow → 50 | — |
| VWAP (session) | — | 1 | Σ(TP·V)/ΣV, reset each IST session; intraday only | — |
| Anchored VWAP | anchor = start of the dominant 126-bar swing | — | Σ(TP·V)/ΣV from the anchor | — |
| Volume profile | 126 bars, 40 bins, 70% | — | each bar's volume spread evenly over the bins its range touches; POC = busiest bin; value area grows from the POC, larger neighbour first (CBOT) | tick-level profiles are more precise (needs tick data) |
| Fibonacci | 126-bar swing | 10 | retracements 0–100% (23.6, 38.2, 50, 61.8, 78.6); two-point extensions 127.2–261.8% | three-point (ABC) extension, not used |
| Pivots | classic | 2 | previous completed period: intraday → day, daily → week, weekly → month, monthly → year | Fibonacci and Camarilla are implemented, not displayed |
| Support / resistance | ±3-bar swings, 1.5% clusters, 120 bars | 7 | clustered swing points; touches = strength | — |

The same formula text, with parameters, is attached to each reading and shown under "Tap for the maths" on its card.

## 3. Interpretation and summary

Every `IndicatorReading` carries: id, name, category, family, **timeframe**, **current value** (plus components such as signal or bands), **bias** (bullish / bearish / neutral), **strength** 0–100, **explanation**, the formula, parameters, warm-up, whether it is ready, **the open time of the bar used**, and **the calculation timestamp**.

Overbought and oversold readings (RSI ≥ 70, Stochastic ≥ 80, and so on) are scored **neutral**, not as a reversal call. Stretched momentum is common in strong trends, so fading it is a separate strategy, not an indicator reading.

**Avoiding double counting.** Indicators that measure nearly the same thing form one family, and each family casts one vote: the average of its ready members.

| Family | Weight | Members |
|---|---|---|
| Moving averages | 20% | EMA ×6, SMA ×3 |
| Trend systems | 15% | Supertrend, PSAR, Ichimoku |
| Trend strength | 10% | ADX/DMI |
| Momentum oscillators | 15% | RSI, Stochastic, Stoch RSI, CCI, Williams %R (five oscillators, one vote) |
| MACD | 10% | MACD |
| Volume flow | 15% | OBV, A/D, MFI |
| VWAP | 5% | session VWAP, anchored VWAP |
| Volatility channels | 10% | Bollinger, Keltner, Donchian |
| Context (no vote) | 0% | ATR, volume profile, pivots, Fibonacci, support/resistance |

The summary score is the weighted average of the family scores. Families with no ready member drop out and the weights re-normalise. **Buy** needs all three of:
- a score of at least +20
- at least 3 bullish families
- bullish families outnumbering bearish ones by 2 or more

**Sell** is the mirror image; everything else is **Neutral**. One indicator, or one family, can never produce a Buy or Sell on its own (`oneFamilyCannotProduceABuy`). The summary text says so and states that the signals are technical only, not investment advice.

## 4. Look-ahead, timeframes, updates and caching

- **No future leakage.** Every series is causal, and a test recomputes 21 series on prefixes of 150, 333 and 700 bars and requires identical values. Ichimoku's cloud for bar t reads the spans computed at t−26. A swing point only counts once the 3 bars after it exist. Pivots use only the previous completed period.
- **Forming bar.** `barEnd` knows the session close (a short last hourly bar, Friday for weekly bars, month-end for monthly bars). A bar whose end is after `now` is kept out of every calculation and drawn faded on the chart.
- **Per timeframe.** Each timeframe is analysed only from its own bars, fetched or resampled by Phase 2. Lookback per timeframe is sized for EMA 200 / Ichimoku within provider limits (for example 45 days of 15m bars, 3 years of daily).
- **Updates.** While the market is open, the screen re-polls every 60 s. The repository's TTLs decide whether that hits the network.
- **Cache.** The key is source, symbol, timeframe, closed-bar count, first and last time, and a hash of the last closed bar. Ticks on the forming bar reuse the cached indicators (only the forming bar is swapped in). A newly closed or revised bar forces a full recompute. That takes a few milliseconds per 1,000 bars, which is cheaper and safer than maintaining incremental state for 30 indicators. The cache is LRU with 24 entries.

## 5. Validation

| Suite | Tests | What it proves |
|---|---|---|
| `ReferenceImplementationTest` | 19 | Cross-checked against `ta` 0.11.0 (bukosabino) on 3,000 synthetic bars that include ±8% gaps, a 25-bar flat stretch and 8× volume spikes. **Exact (≤ 1e-6 relative):** SMA, ATR, Bollinger, Donchian, Stochastic, Williams %R, CCI, Ichimoku, PSAR, MFI, A/D, ADX/+DI/−DI, anchored VWAP. **Converged after 1,200 bars** (seed convention only): EMA, RSI, MACD, Keltner, Stochastic RSI. **OBV:** every bar-to-bar change matches, except on unchanged closes, where the definitions differ (§2). |
| `IndicatorFixturesTest` | 27 | Hand-calculated values, with the working in comments: SMA/EMA/RMA, RSI, Stochastic, %R, CCI, MACD, ATR with gaps, Bollinger, Keltner, Donchian, DMI, PSAR steps and reversal, Ichimoku, **Supertrend through two flips**, OBV, A/D, MFI, session VWAP reset, **POC / VAH / VAL**, classic/Fibonacci/Camarilla pivots, weekly pivot basis, Fibonacci levels, unconfirmed swings. |
| `EngineRobustnessTest` | 16 | No look-ahead; Ichimoku cloud timing; forming bar excluded; `barEnd` per timeframe; warm-up and 0–3-bar inputs; **flat market** (RSI 50, Neutral, all finite); **gaps and extreme prices** (1e-4 to 1e9, huge volumes); **missing candles** (VWAP and pivots still pick the right session); timeframes independent; required reading fields; trending series give Buy/Sell; one family can't decide; cache hit/miss behaviour; speed (1,000 bars < 500 ms). |

Supertrend, volume profile, pivots, Fibonacci and S/R have no `ta` equivalent, so hand-calculated fixtures validate them.

The reference data is synthetic on purpose: it can exercise edge cases on demand, and committing real exchange prices raises licensing questions. Regenerate it with `tools/ta-reference/generate_reference.py` (instructions are in the file).

## 6. Screen

Stock detail → **Technical analysis**:
- **Timeframe chips.** 1m to 1M; a timeframe the provider cannot serve is shown as "n/a".
- **Data status bar.** As in Phase 2.
- **Summary card.** Buy, Sell or Neutral; the score; a gauge; each family's vote and weight; an explanation.
- **Candlestick chart.** 16 toggleable overlays: EMA 20/50/200, SMA 200, Bollinger, Keltner, Donchian, Supertrend, PSAR, Ichimoku cloud, VWAP, anchored VWAP, pivots, Fibonacci, POC/value area, S/R.
- **Oscillator panel.** RSI, MACD, Stochastic, Stochastic RSI, CCI, %R, ADX/DMI, MFI, OBV, A/D, with reference lines.
- **Indicator cards.** Filterable by category; tap a card to expand the formula, parameters, warm-up, components, bar used and calculation time.

## 7. Limitations

- The bias rules and family weights are reasonable defaults, **not optimised or proven predictive**. Per the roadmap, they must beat a random baseline out of sample before they can affect the rating.
- The volume profile is built from OHLCV bars, so it approximates where volume traded inside each bar.
- Indices (`^` symbols) and bars with no volume skip every volume-based reading.
- The `ta` cross-check compares two implementations of the same textbook definitions. It catches coding errors, not definitional disagreements between vendors (for example the TradingView Supertrend flip timing).
