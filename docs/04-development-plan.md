# Development plan

## 1. Database schema proposal (Room v2)

All changes are **additive**. They ship as `Migration(1, 2)` and are verified against the exported
`app/schemas/.../1.json` with `MigrationTestHelper`. No existing table is dropped.

| Table | Columns | Key / index | Purpose |
|---|---|---|---|
| `instruments` | symbol, exchange, isin, name, sector, industry, is_index, updated_at | PK symbol; idx isin | Stable identity; survives renames via ISIN |
| `candles` | symbol, timeframe, time, open, high, low, close, volume, source, fetched_at | PK (symbol, timeframe, time) | Offline charts and fewer network calls; trimmed to 5 years |
| `quote_cache` | symbol, price, prev_close, day_high, day_low, volume, as_of, source, fetched_at | PK symbol | Stale-while-revalidate quotes |
| `fundamentals` | symbol, period, metrics_json, source, as_of | PK (symbol, period) | `FundamentalMetrics` |
| `financial_statements` | symbol, type, period_end, period_type, consolidated, line_items_json, unit, currency, source | PK (symbol, type, period_end, consolidated) | `FinancialStatement` |
| `news` / `news_symbols` | id, headline, summary, url, publisher, published_at, category, sentiment, source / news_id, symbol | PK id / PK (news_id, symbol), idx symbol | News feed per stock; trimmed to 90 days |
| `research_reports` | symbol, generated_at, model, report_json | PK (symbol, generated_at) | Cached AI reports (one per symbol per day) |
| `signal_log` | id, symbol, generated_at, engine_version, rating, score, confidence, price, entry, stop, t1, t2, t3, outcome, exit_price, exit_at | PK id; idx (symbol, generated_at) | **Out-of-sample tracking** of every rating actually shown |
| `watchlists` / `watchlist_items` | id, name, sort / watchlist_id, symbol, added_at | — | Multiple watchlists. The migration copies the existing `watchlist` rows into a "Default" list and keeps the old table until v3. |
| `cash_ledger` | id, time, amount_paise, kind (DEPOSIT, BUY, SELL, FEE), trade_id | PK id | Paper-trading cash balance |
| `trades` (altered) | + fees_paise | — | Brokerage, STT, GST and stamp duty |
| `alerts` (altered) | + last_triggered_at, + rearm | — | Re-arm logic; no repeat fire on re-enable |

New money columns are stored as integer paise to avoid floating-point drift. The existing `Double`
columns are left as they are.

## 2. API integration plan

| Step | Integration | How | Gate |
|---|---|---|---|
| Now | Yahoo (existing) | `YahooFinanceProvider` behind `MarketDataProvider` | Dev and personal use only. `ProviderInfo.licensedForDisplay = false`. |
| P2 | Own backend (BFF) | `BackendProvider` → `https://api.<yourdomain>/v1/...`. Endpoints: `/quotes`, `/candles`, `/search`, `/fundamentals`, `/statements`, `/news`, `/research`, `/broker/session` | The backend holds every vendor key. The app authenticates with a short-lived user token. |
| P2 | Licensed market data | Backend relays one of: an NSE/BSE-authorised data vendor, **or** per-user broker APIs (Zerodha Kite Connect, Upstox, Angel SmartAPI, Dhan) | A written licence permitting display in your app. Broker data is normally limited to that user's own account. |
| P4 | Fundamentals and statements | Licensed vendor, or parsing exchange XBRL filings server-side | Each value stored with its filing or vendor reference |
| P5 | News and announcements | Licensed news API plus NSE/BSE corporate announcements; headline + link + publisher | Respect publisher copyright: show summaries, link out |
| P6 | AI research | Backend → Claude API (`claude-opus-5-5`), with structured input (signal, fundamentals, news) and JSON output with citations | Server-side key; per-user rate limit; reports cached |
| P8 (optional) | Broker orders | Broker OAuth via Custom Tabs → backend exchanges `request_token` using the API **secret** → app gets a session token | SEBI retail-algo rules and broker approval; explicit user confirmation for every order |

**Client rules** for every provider:
- OkHttp timeouts and a retry with exponential backoff on 429 and 5xx (at most 2 retries);
- one request in flight per symbol and range (de-duplication);
- the cache is served first and refreshed in the background;
- the UI always shows `asOf` and the provider name.

## 3. Licensing requirements (market data and advice)

These are not legal advice. Confirm the current terms with each party before a public release.

| Item | Requirement |
|---|---|
| **NSE / BSE prices** (real-time or delayed) | Displaying exchange data in a distributed app generally requires a data licence from the exchange (NSE's data products arm / BSE's data services), directly or through an authorised vendor. Fees and display rules (delay, attribution, per-user reporting) apply. |
| **Index values** (NIFTY 50, BANK NIFTY…) | Index data and names are owned by NSE Indices Ltd (SENSEX by Asia Index). Display and derived use can require a separate licence. |
| **Yahoo Finance** | The endpoints are unofficial and Yahoo's terms are for personal, non-commercial use. **Not acceptable for a public or paid release.** |
| **Broker APIs** | Licensed to the account holder; redistributing one account's data to other users is typically prohibited. Fits a "connect your own broker" model only. Order placement through APIs falls under SEBI's retail algo-trading framework, implemented by brokers. |
| **Fundamentals and news** | Licensed vendors or publishers. Exchange filings are public, but bulk automated collection must follow exchange website terms. |
| **Recommendations** | Publishing buy/sell ratings or target prices to the public in India generally requires SEBI registration as a Research Analyst (SEBI RA Regulations, 2014) or Investment Adviser, plus disclosures. Until then, keep the app as a personal analysis tool with clear "not investment advice" labelling, and don't market its ratings as recommendations. |
| **AI provider** | Anthropic's commercial terms; don't send personal data that isn't needed. |

## 4. Dependency list

**Keep:** everything listed in `01-audit.md §3`.

**Add when the phase needs it** (proposed; nothing is added in Phase 1):

| Phase | Dependency | Why |
|---|---|---|
| P2 | Gradle version catalog (`gradle/libs.versions.toml`) | One place for versions across modules |
| P2 | `androidx.room:room-testing` | Migration tests against the exported schemas |
| P2 | `kotlinx-coroutines-test`, `app.cash.turbine` | ViewModel and Flow tests |
| P2 | `okhttp3:mockwebserver` | Provider tests from recorded JSON fixtures |
| P2 | `androidx.compose.ui:ui-test-junit4` | Screen tests |
| P2–P3 | Hilt (`dagger.hilt.android`) | DI once repositories multiply |
| P2 | `androidx.datastore:datastore-preferences` | Replaces SharedPreferences for settings |
| P8 | `androidx.browser` (Custom Tabs) | Broker OAuth login |
| P8 | Google Tink (or Android Keystore directly) | Encrypting broker session tokens at rest |

## 5. Security plan

1. **No secrets in the APK.** Vendor, news, LLM and broker API secrets live only on the backend. The app never ships a key. Phase 1 verified that none exist today.
2. **Authentication** (when the backend exists): sign-in with an OAuth/OIDC provider, then short-lived access tokens with refresh, held in memory and in encrypted storage. Server-side rate limits apply per user.
3. **Broker login:** Custom Tabs and redirect. The token exchange happens on the backend, and the session token is stored with Keystore-backed encryption. Users can revoke it in Settings.
4. **Transport:** HTTPS only (cleartext is already blocked for targetSdk ≥ 28). Add an explicit `network_security_config` that blocks cleartext. Consider certificate pinning for the backend domain, with a backup pin.
5. **Device storage:** paper-trading data isn't sensitive. Once broker tokens or account data are stored, encrypt them and exclude them from Android backup (`dataExtractionRules`).
6. **Release build:** enable R8 (`isMinifyEnabled = true`) with keep rules for serialization models (the existing `proguard-rules.pro` already covers `data.remote`). Sign with Play App Signing. Remove debug logging.
7. **Orders (if added):** an explicit confirmation screen for every order, with no auto-execution of signals; idempotency keys; a full audit log.
8. **Supply chain:** Dependabot or Renovate on Gradle, a CI dependency audit and lint, and a pinned Gradle wrapper checksum.
9. **AI:** prompt inputs are our own structured data, never raw user text concatenated into system prompts. Outputs are validated against a JSON schema, and the UI flags them as AI-generated.

## 6. Testing strategy

| Layer | Tests | Tooling | Status |
|---|---|---|---|
| Indicators | Cross-check against the `ta` reference library, hand-calculated fixtures, look-ahead and edge-case tests | JUnit | Done: 62 tests (see 06-technical-analysis.md §5) |
| Engine | Synthetic trend, chop and reversal series; guardrails; plan invariants (stop below entry, qty × risk ≤ budget) | JUnit | 10 tests |
| Repository / providers | Fake provider (done) plus recorded-JSON fixtures through MockWebServer (no live network in CI) | JUnit, MockWebServer | 9 tests |
| Database | DAO tests; **migration test for every version bump** | Room testing, Robolectric or instrumented | To do (P2), prerequisite for any schema change |
| ViewModels | State transitions and error handling | coroutines-test, Turbine | To do |
| UI | Detail screen sections render for buy, hold and sell fixtures; accessibility labels | Compose UI test | To do |
| Strategy quality | Walk-forward, out-of-sample backtest across the NIFTY 50 with costs; regression thresholds so rule changes can't silently degrade results | Custom JVM harness, run on demand or nightly | To do (P3) |
| Live tracking | `signal_log` outcomes reviewed monthly | — | To do |

CI (updated in Phase 1) runs `testDebugUnitTest` before `assembleDebug` on every push and uploads the test reports.

## 7. Phased roadmap

| Phase | Scope | Exit criteria |
|---|---|---|
| **1 — Audit (done)** | Audit; provider abstraction; price timestamps; Room schema export and safe migration policy; P&L fix; CI tests | 19/19 tests green; app verified on the emulator; docs in `docs/` |
| **2 — Foundation** | Version catalog; `:core:model`/`:core:analysis`/`:core:data` modules; Room v2 migration (cache, `signal_log`, watchlists, cash ledger); candle cache; retry and backoff; ViewModels in their own files; test tooling | Migration test passes on a v1 database with real rows; the app works offline from cache; no behaviour regressions |
| **3 — Technical depth** | More indicators; chart pattern engine; separate entry and exit engines with trailing and time stops; walk-forward backtest harness across the universe | Each pattern and rule beats its random baseline out-of-sample, after costs, before it may affect the rating |
| **4 — Fundamentals and valuation** | Backend + licensed fundamentals; statements; quality and growth scores; relative valuation and reverse DCF; Fundamentals tab | Every figure shows its source and period; no field defaults to 0 |
| **5 — News intelligence** | News ingestion, symbol tagging, event classification, results calendar, alerts on corporate actions | De-duplicated feed with publisher attribution; licence in place |
| **6 — AI research** | Claude-generated research report from technical, fundamental and news inputs, with citations; cached daily | Every claim in a report cites a supplied input; spot checks find no invented numbers |
| **7 — Portfolio and backtest product** | Cash ledger, costs, multiple watchlists, portfolio analytics (allocation, drawdown), a user-facing strategy backtester | P&L reconciles to the ledger to the paisa |
| **8 — Broker (optional)** | Connect a broker account; holdings sync; order placement with confirmation | Compliance review (SEBI RA/IA and algo framework), broker approval, security review |
