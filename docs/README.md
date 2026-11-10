# TradeLens engineering docs

| Doc | Contents |
|---|---|
| [01-audit.md](01-audit.md) | Phase 1 audit: modules, stack, features (working, partial, broken, missing), dependencies, APIs, data reliability |
| [02-target-architecture.md](02-target-architecture.md) | Layered and modular target architecture, the 12 components, key decisions |
| [03-data-models.md](03-data-models.md) | Reusable domain models and how they map to today's types |
| [04-development-plan.md](04-development-plan.md) | DB schema, API plan, licensing, dependencies, security, testing, roadmap |
| [05-market-data.md](05-market-data.md) | Phase 2: data flow, timeframes/sessions, validation, freshness, performance, provider evaluation, backend contract, limitations |

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
