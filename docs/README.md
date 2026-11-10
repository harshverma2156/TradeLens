# TradeLens engineering docs

| Doc | Contents |
|---|---|
| [01-audit.md](01-audit.md) | Phase 1 audit: modules, stack, features (working, partial, broken, missing), dependencies, APIs, data reliability |
| [02-target-architecture.md](02-target-architecture.md) | Layered and modular target architecture, the 12 components, key decisions |
| [03-data-models.md](03-data-models.md) | Reusable domain models and how they map to today's types |
| [04-development-plan.md](04-development-plan.md) | DB schema, API plan, licensing, dependencies, security, testing, roadmap |

## Changes made in Phase 1 (safe, behaviour-preserving)
- `MarketDataProvider` interface + `YahooFinanceProvider`; `MarketRepository` now depends on the interface. Its public API is unchanged.
- `Quote.asOf`, taken from Yahoo's `regularMarketTime`. The detail screen shows "As of … IST · source, may be delayed".
- Room: schema export to `app/schemas/`; `fallbackToDestructiveMigrationOnDowngrade()` replaces `fallbackToDestructiveMigration()`, so an upgrade can't wipe user data.
- Realised P&L is the all-time sum, not the last 100 trades.
- Alert notifications use the clean ticker for BSE symbols.
- `.gitignore` no longer excludes `gradle-wrapper.properties`. CI runs the unit tests through the wrapper.
- New `MarketRepositoryTest` (9 tests). All 19 tests pass.
