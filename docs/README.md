# TradeLens engineering docs

| Doc | Contents |
|---|---|
| [01-audit.md](01-audit.md) | Phase 1 audit: modules, stack, features (working, partial, broken, missing), dependencies, APIs, data reliability |
| [02-target-architecture.md](02-target-architecture.md) | Layered and modular target architecture, the 12 components, key decisions |
| [03-data-models.md](03-data-models.md) | Reusable domain models and how they map to today's types |
| [04-development-plan.md](04-development-plan.md) | DB schema, API plan, licensing, dependencies, security, testing, roadmap |
| [05-market-data.md](05-market-data.md) | Phase 2: data flow, timeframes/sessions, validation, freshness, performance, provider evaluation, backend contract, limitations |
| [06-technical-analysis.md](06-technical-analysis.md) | Phase 3: calculation services, formulas and variants, interpretation and family voting, look-ahead, caching, validation, screen |
| [07-chart-patterns.md](07-chart-patterns.md) | Phase 4: swing/zigzag detection, pattern rules, lifecycle, no look-ahead guarantee, confidence, validation on real NSE history |
| [08-smart-entry-exit.md](08-smart-entry-exit.md) | Phase 5: levels and zones, three entry strategies, exits, risk and sizing, statuses and gates, signal stability |

## Changes made in Phase 1 (safe, behaviour-preserving)
- `MarketDataProvider` interface + `YahooFinanceProvider`; `MarketRepository` now depends on the interface. Its public API is unchanged.
- `Quote.asOf`, taken from Yahoo's `regularMarketTime`. The detail screen shows "As of … IST · source, may be delayed".
- Room: schema export to `app/schemas/`; `fallbackToDestructiveMigrationOnDowngrade()` replaces `fallbackToDestructiveMigration()`, so an upgrade can't wipe user data.
- Realised P&L is the all-time sum, not the last 100 trades.
- Alert notifications use the clean ticker for BSE symbols.
- `.gitignore` no longer excludes `gradle-wrapper.properties`. CI runs the unit tests through the wrapper.
- New `MarketRepositoryTest` (9 tests). All 19 tests pass.

## Changes made in Phase 2 (market data infrastructure)
- `marketdata/`: pure-Kotlin timeframes (1m–monthly), provider capabilities, NSE/BSE session calendar with inferred/official holidays, candle validator, resampler, rate limiter, retry with backoff, request coalescing.
- Providers: capability-driven `MarketDataProvider`. `YahooFinanceProvider` adds batch quotes, intraday, weekly/monthly, adjusted close and corporate actions. `BackendMarketDataProvider` is the path to Kite, Upstox or vendors with no keys in the app. `DemoMarketDataProvider` is synthetic and always labelled.
- Room v2 (`MIGRATION_1_2`, additive): `candles`, `sync_state`, `quotes`, `corporate_actions`, `market_holidays`, all keyed by source.
- `MarketRepository`: incremental sync, caching, offline fallback, batching, validation on write and read, `DataStatus` for every result. `MarketSyncWorker` refreshes hourly in the background.
- UI: freshness badge, as-of time, provider, staleness explanation, errors with Retry, range + interval chips (1m–1M), a Data source & quality card, sector indices, a demo banner and a demo toggle in Settings.
- 67 unit tests, including a Robolectric migration test. See 05-market-data.md §10.

## Changes made in Phase 3 (technical analysis engine)
- `ta/`: pure-Kotlin calculation services (`Rolling`, `TrendCalc`, `MomentumCalc`, `VolatilityCalc`, `VolumeCalc`, `LevelsCalc`) covering 30+ indicators, plus `TechnicalEngine` (interpretation and summary) and `TechnicalCache`. `analysis/Ta` now delegates to them.
- Each reading reports value, timeframe, bias, strength, explanation, formula, warm-up, bar used and calculation time. Correlated indicators vote once per family; Buy/Sell needs 3+ agreeing families.
- Only closed bars feed signals; the forming bar is charted but excluded. Each timeframe is analysed from its own bars.
- New Technical analysis screen: timeframe chips, summary, candlestick chart with 16 overlays, oscillator panel, filterable indicator cards with expandable maths.
- 62 indicator tests: cross-checked against the `ta` library, hand-calculated fixtures, and look-ahead, flat, gap, missing-candle and extreme-value cases. 129 unit tests in total. See 06-technical-analysis.md §5.

## Changes made in Phase 4 (chart pattern recognition)
- `patterns/`: deterministic engine that walks forward one closed bar at a time. 16 chart patterns (H&S and inverse, double/triple tops and bottoms, flags, triangles, cup and handle, rectangle, wedges, channels) and 9 candlestick patterns.
- Each pattern reports name, direction, timeframe, detection date, confidence (with breakdown), key levels, breakout status, volume confirmation, higher-timeframe trend, entry, stop-loss, three targets and invalidation level. Lifecycle: forming, confirmed, failed or invalidated, with a timestamped event log.
- Patterns are drawn on the Technical screen's candlestick chart; tapping one shows its swing points, breakout, stop and targets.
- 71 tests, including a prefix-equality test proving no future bar is used, and six real NSE examples verified by hand. Real history is fetched by `tools/pattern-history/fetch_history.py` (git-ignored). 200 unit tests in total. See 07-chart-patterns.md.

## Changes made in Phase 5 (smart entry, exit and price targets)
- `trade/`: `SmartEntryEngine` combines Phase 2 data status, Phase 3 indicators and Phase 4 patterns into aggressive, conservative and breakout plans. Stops come from structure (0.75–3.5 ATR), targets from resistance, pattern measured moves and Fibonacci extensions (never fixed percentages), with trailing stop, partial exits, invalidation and exit conditions.
- Statuses: BUY NOW, WAIT FOR CORRECTION / BREAKOUT / CONFIRMATION, HOLD, PARTIAL PROFIT BOOKING, EXIT, AVOID. No buy signal on stale data, missing candles, event risk, failed patterns, a bearish picture, poor reward/risk or no logical stop.
- `SignalStabilizer`: cooldown, hysteresis and level tolerance; risk-reducing changes apply at once.
- Stock detail: the Smart entry & exit card replaces the old best-entry and trade-plan sections; there is a separate long-term card, a new "max position size" setting, and live quotes (polled every 5 s while the market is open and the page is visible; Markets and Watchlist every 10 s).
- 42 tests; 242 unit tests in total. See 08-smart-entry-exit.md.
