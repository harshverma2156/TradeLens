# Target architecture

## Principles
1. **Evolve, don't rewrite.** Today's layering (UI → repository → data source, with pure-Kotlin analysis) is sound. We extract it into modules step by step, and each step keeps the app shippable.
2. **The analysis code stays pure Kotlin.** Every engine (indicators, patterns, entry/exit, fundamentals, valuation, backtest) is a plain JVM library with no Android imports. That makes it fast to unit-test, reusable on a backend, and deterministic.
3. **Data sources sit behind interfaces.** UI and engines never know whether the data came from Yahoo, a broker or our own server. This was done in Phase 1 for market data with `MarketDataProvider`.
4. **No secrets on the device.** Anything needing an API key or secret (licensed feeds, news, LLM, broker token exchange) goes through our own backend. See the security plan.
5. **Never fabricate.** Every number shown carries its source and time. Missing data is shown as missing, never estimated silently. The AI layer may only summarise data we fetched, and must cite it.

## Layers and modules

```
┌──────────────────────── :app (presentation) ────────────────────────┐
│ Compose screens · ViewModels · navigation · theme · AppContainer/DI  │
└───────────────┬──────────────────────────────────────┬──────────────┘
                │ uses                                  │ uses
┌───────────────▼──────────────┐      ┌────────────────▼──────────────┐
│ :core:data (Android library) │      │ :core:analysis (pure Kotlin)  │
│ Repositories                 │      │ indicators   patterns         │
│  Market · Fundamentals       │      │ signal/entry-exit             │
│  News · Research · User      │─────▶│ fundamentals  valuation       │
│ Room DB · caching            │      │ backtest                      │
│ Provider interfaces + impls  │      └────────────────┬──────────────┘
└───────────────┬──────────────┘                       │
                │                     ┌────────────────▼──────────────┐
                └────────────────────▶│ :core:model (pure Kotlin)     │
                                      │ Stock, Candle, Quote, Signal… │
                                      └───────────────────────────────┘
        │ HTTPS (no secrets in app)
┌───────▼────────────── TradeLens backend (BFF) ──────────────────────┐
│ licensed market-data relay · fundamentals · news ingestion          │
│ LLM research (Claude API) · broker OAuth token exchange · auth      │
└─────────────────────────────────────────────────────────────────────┘
```

**Module plan, in order of extraction:**
1. `:core:model`: move `data/model/*` and the analysis result types (`Analysis`, `TradePlan`, `EntryAdvice`, …).
2. `:core:analysis`: move `analysis/*`. It is already pure Kotlin with no Android imports, so this is mostly a cut-and-paste.
3. `:core:data`: repositories, Room, Retrofit, the provider interfaces and implementations.
4. Optional `:feature:*` modules (detail, scanner, portfolio, research), once each screen grows big enough to justify one.

**DI:** keep the manual `AppContainer` until there are about 3 more repositories, then move to Hilt. The constructors already take their dependencies, so the switch is mechanical.

## The twelve components

| # | Component | Lives in | Builds on | Notes |
|---|---|---|---|---|
| 1 | Android presentation layer | `:app` | Existing Compose screens and ViewModels | Move ViewModels out of the screen files into their own files. Add a "Data source & time" chip on every price. One UI state type per screen. |
| 2 | Stock data layer | `:core:data` | `MarketRepository` | Add a Room-backed candle and quote cache (stale-while-revalidate), request de-duplication, and backoff on HTTP 429. |
| 3 | Market data provider abstraction | `:core:data` | **Done in Phase 1:** `MarketDataProvider`, `ProviderInfo`, `YahooFinanceProvider` | Next implementations: `BackendProvider` (our licensed relay) and `FakeProvider` for tests and demo mode. `ProviderInfo.realtime`/`licensedForDisplay` drive UI labels and release gating. |
| 4 | Technical indicator engine | `:core:analysis` | `Ta` object | Add Stochastic, CCI, Ichimoku, Keltner, pivot points and VWAP (intraday). Verify against published reference values. Expose indicator series for chart overlays. |
| 5 | Chart pattern recognition | `:core:analysis` (new `patterns/`) | Swing detection in `Ta.levels` / `Ta.lastSwing` | Rule-based detectors (candlesticks, double top/bottom, triangles, flags, head and shoulders, cup and handle). Each returns a `ChartPattern` with breakout and invalidation levels and a confidence. Validated by the backtest before it may affect the rating. |
| 6 | Smart entry and exit | `:core:analysis` | `entryAdvice` / `buildPlan` in `AnalysisEngine` | Split them into an `EntryEngine` and an `ExitEngine`. Add structure-based targets (next resistance), ATR and chandelier trailing stops, time stops, and partial-exit rules. |
| 7 | Fundamental analysis | `:core:analysis` + `FundamentalsRepository` | New | Inputs are `FundamentalMetrics` and `FinancialStatement` from a licensed provider via the backend. It outputs quality, growth and balance-sheet scores, and can become a 7th pillar. |
| 8 | Valuation | `:core:analysis` | New | Relative valuation (P/E, EV/EBITDA, P/B vs sector and own history), plus a reverse DCF ("what growth is priced in"). Every input is shown with its source and date. |
| 9 | News intelligence | Backend + `NewsRepository` | New | Ingest from licensed feeds and exchange announcements (NSE/BSE corporate filings). Tag by symbol, de-duplicate, classify events (results, orders, ratings, regulatory). Sentiment is shown as a label, never a fabricated number. |
| 10 | AI research | Backend (Claude API) + `ResearchRepository` | New | Generates a `StockResearchReport` **only** from the technical, fundamental and news data passed in, with citations. Server-side key. Responses are cached per symbol and day. The UI labels it as AI-generated and not advice. |
| 11 | Portfolio and watchlist | `:core:data` + UI | Room `watchlist`/`holdings`/`trades`/`alerts`, `UserRepository` | Add a cash ledger, costs (brokerage, STT, GST, stamp duty), multiple watchlists, alert re-arm logic and market-hours awareness. Later, broker holdings import. |
| 12 | Backtesting | `:core:analysis` | `backtest()` in `AnalysisEngine` | Extract a `BacktestEngine` that takes a strategy, a universe and a date range, with walk-forward / out-of-sample splits, costs and slippage. It reports CAGR, drawdown, win rate, profit factor and exposure. Ratings are logged to `signal_log` for live tracking. |

## Key decisions

| Decision | Why | Alternative considered |
|---|---|---|
| Keep one `:app` module for now and extract modules in Phase 2 | Modularising before the boundaries are proven costs more than it saves. The package structure already mirrors the target modules. | Split everything now: high churn and no user benefit. |
| Analysis engines are pure Kotlin | Testable on the JVM in milliseconds, portable to a backend, and deterministic for backtests. | Android-dependent engines: slow tests, no reuse. |
| A backend (BFF) is required for licensed data, news, AI and broker login | Secrets can't be protected in an APK, and licensed data usually can't be fetched directly by every end user's device. | Calling vendors directly from the app: leaks keys and breaks licence terms. |
| Room stays the local store, now with exported schemas and mandatory migrations | It's already in use. Exported schemas allow `MigrationTestHelper` tests, so user data is never wiped. | DataStore/Realm: a migration for no gain. |
| Rule-based patterns and signals first, ML later (if ever) | Rules are explainable, testable and auditable. ML needs a large labelled, survivorship-free dataset that we don't have yet. | An ML model now: opaque, overfit-prone and hard to justify to users or regulators. |
| An LLM is used only for summarising, never for producing numbers | LLMs can invent figures. Restricting them to cited summaries of supplied data keeps the "never fabricate" rule. | LLM price predictions: unreliable, and a likely regulatory problem. |
