# Phase 1 audit: TradeLens as it exists

Audited on 10 Nov 2026 against the code in this repository. "Verified" means checked by running the
app on an Android 14 emulator, running the test suite, or querying the live data source.

## 1. Project at a glance

| Area | What's there |
|---|---|
| Modules | One Gradle module, `:app`. No feature or library modules. |
| Language and UI | Kotlin 2.0.20, Jetpack Compose with Material 3 (BOM 2024.09.00). Single activity, no XML layouts. |
| Build | AGP 8.5.2, Gradle 8.7 wrapper, compileSdk/targetSdk 34, minSdk 26, JDK 17. KSP for Room. No version catalog. |
| Architecture | Layered MVVM. `ui/screens/*` holds each screen's composable and its ViewModel; `data/*` holds repositories, Room and Retrofit; `analysis/*` is pure Kotlin. Dependencies are wired by hand in `AppContainer` (`TradeLensApp.kt`). |
| Navigation | Navigation-Compose (`ui/navigation/AppNav.kt`) with five bottom tabs (Markets, Watchlist, Scanner, Portfolio, Alerts) and three pushed routes (`search`, `settings`, `stock/{symbol}`). |
| Local storage | Room `tradelens.db` v1: `watchlist`, `holdings`, `trades`, `alerts`. SharedPreferences `settings` for capital, risk % and the India-only search switch. |
| Market data | Yahoo Finance public endpoints via Retrofit + kotlinx.serialization: `v8/finance/chart/{symbol}` and `v1/finance/search`. No API key. |
| Indicators | `analysis/Indicators.kt`: SMA, EMA, Wilder smoothing, RSI, MACD, ATR, Bollinger Bands, ADX/DI, swing support/resistance, Supertrend, OBV, MFI, ROC, weekly resampling, volume profile, last swing, anchored VWAP. |
| Buy/sell logic | `analysis/AnalysisEngine.kt`: six weighted pillars, guardrails, confluence-based entry zone, ATR stop and R-multiple targets, position sizing, in-sample backtest, confidence score. |
| Authentication | None. The app is single-user and offline-first; nothing is synced. |
| Background work | WorkManager `AlertWorker`, periodic every 15 minutes (Android's minimum) when online, plus "Check now". |
| Notifications | One channel, `price_alerts`. Runtime `POST_NOTIFICATIONS` request on Android 13+. |
| Tests | JVM unit tests only. Before this phase: 10 (`AnalysisEngineTest`). After: 19 (adds `MarketRepositoryTest`). No instrumented, UI or migration tests. |
| CI | GitHub Actions `build-apk.yml`. Before: built the APK with a system Gradle and ran no tests. After: runs the tests, then builds, using the wrapper. |

## 2. Feature inventory

### Working (verified)
- **Markets:** index cards (NIFTY 50, SENSEX, BANK NIFTY, NIFTY IT), 14 popular stocks, Popular/Top gainers/Top losers sort, refresh.
- **Search:** NSE/BSE search with 350 ms debounce and an India-only filter.
- **Stock detail:**
  - candle or line chart for 1D–5Y with a crosshair and entry/SL/T2 lines;
  - key stats, then the Rating card (signal, score, confidence, pillar bars);
  - the Best entry card;
  - the trade plan (zone, stop, three targets, quantity, holding period);
  - the backtest card, the indicator breakdown, support/resistance and warnings.
- **Watchlist:** star or unstar, live prices, remove.
- **Scanner:** runs the engine over the NIFTY list or your watchlist, with Buy/Sell/All filters ranked by score and then confidence.
- **Paper portfolio:** buy/sell with average price, unrealised and realised P&L, the last 100 trades.
- **Alerts:** above/below price alerts, the background check, a notification on a cross, auto-disable after firing, pause/delete.
- **Settings:** capital, risk % (0–10), the India-only search switch. Saved values re-size the trade plan live.

### Partially implemented
| Feature | Gap |
|---|---|
| Paper portfolio | No cash balance, so you can "buy" without limit. No brokerage, STT or slippage. Trades fill at the price loaded when the page opened, not a fresh quote. |
| Alerts | The 15-minute floor means they aren't intraday-precise. Checks run around the clock, including market holidays. Re-enabling an alert whose condition is still true fires it again straight away. |
| Scanner universe | `Universe.NIFTY_50` is a hard-coded list of 49 symbols. Index constituents change twice a year, and the list has no update mechanism. |
| Index analysis | Indices report no volume and are not compared with themselves, so the volume, relative-strength and market pillars are dropped. Only three pillars remain. |
| Currency | The Portfolio and the alert notifications format every amount as ₹, even for non-Indian symbols when India-only search is off. |
| Offline | Room keeps user data, but market data isn't cached, so every screen needs the network. |
| Launcher icon | A single vector drawable, with no adaptive or monochrome icon. |

### Broken: found and fixed in this phase
| Issue | Impact | Fix |
|---|---|---|
| Room used `fallbackToDestructiveMigration()` with `exportSchema = false`. | The first schema change (needed for Phase 2) would silently **delete every user's watchlist, holdings, trades and alerts**. | Now `fallbackToDestructiveMigrationOnDowngrade()` only. The schema is exported to `app/schemas/` so migrations can be written and tested. |
| "Realised P&L" summed only `trades LIMIT 100`. | The all-time realised P&L was wrong after 100 trades. | New `SUM(realizedPnl)` DAO query. |
| Prices showed no timestamp or source. | Previous-session or delayed prices looked live. Verified: on 10 Nov the data was from 9 Nov 15:28 IST. | The detail header shows "As of dd MMM, HH:mm IST · Yahoo Finance, may be delayed". |
| `.gitignore` excluded `gradle-wrapper.properties`. | A fresh clone couldn't run `./gradlew`. | The line is removed. |
| Alert notification titles stripped only `.NS`. | BSE symbols showed as "XYZ.BO". | Now uses `Fmt.ticker`. |

### Missing
- Chart pattern recognition: flags, triangles, head and shoulders, double tops and bottoms, candlestick patterns.
- Fundamentals (P/E, ROE, debt, growth), financial statements, valuation (DCF, relative multiples).
- News, corporate actions, results calendar.
- AI research summaries.
- Market depth (order book). This needs a licensed Level-2 feed.
- Intraday analysis: the engine is daily-only.
- Real order placement through a broker.
- Login, cloud sync, backup and export.
- Instrumented, Compose UI and Room migration tests. Crash reporting.
- A record of past ratings that would allow honest out-of-sample tracking.

## 3. Dependencies (from `app/build.gradle.kts`)

| Group | Library | Version |
|---|---|---|
| Plugins | Android Gradle Plugin | 8.5.2 |
| | Kotlin Android / Compose compiler / Serialization | 2.0.20 |
| | KSP | 2.0.20-1.0.25 |
| AndroidX | core-ktx | 1.13.1 |
| | activity-compose | 1.9.2 |
| | lifecycle runtime / runtime-compose / viewmodel-compose | 2.8.5 |
| | navigation-compose | 2.8.0 |
| | Compose BOM (ui, material3, material-icons-extended, tooling) | 2024.09.00 |
| | Room runtime / ktx / compiler | 2.6.1 |
| | WorkManager | 2.9.1 |
| Network | Retrofit + kotlinx-serialization converter | 2.11.0 |
| | OkHttp | 4.12.0 |
| | kotlinx-serialization-json | 1.7.1 |
| | kotlinx-coroutines-android | 1.8.1 |
| Test | JUnit | 4.13.2 |

No analytics, crash reporting, DI framework or image loading. There are no API keys or secrets anywhere in the source tree (verified by inspection).

## 4. API integrations

| Integration | Endpoint | Used by | Auth |
|---|---|---|---|
| Yahoo chart | `GET https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range&interval` | Quotes (5d/1d), charts (1d–5y), analysis (2y/1d), benchmark `^NSEI` (2y, cached 15 min) | None; browser-style User-Agent |
| Yahoo search | `GET …/v1/finance/search?q` | Search screen | None |

From this phase on, both are reached only through the `MarketDataProvider` interface (`data/remote/MarketDataProvider.kt`), implemented by `YahooFinanceProvider`.

## 5. Data reliability issues

1. **Unofficial source.** Yahoo's endpoints are undocumented, have no SLA and can change or block clients at any time. HTTP 429 (rate limit) is already shown to users with a friendly message.
2. **Delay.** NSE/BSE quotes from Yahoo may be delayed. The app now says so on the detail screen; the Markets list doesn't show a timestamp yet.
3. **Stale sessions.** Before the open, and on holidays, the "change" shown is the previous session's. The timestamp now makes this visible.
4. **Symbol changes.** Delisted or renamed symbols return 404. Verified: `TATAMOTORS.NS` is gone after the demerger. Watchlist entries for such symbols show "—" without explaining why.
5. **Corporate actions.** Yahoo's daily candles are split-adjusted (verified: HDFCBANK's 2:1 split in Aug 2025 shows no price gap). They are not dividend-adjusted, so returns slightly understate total return.
6. **Missing bars.** Bars with a null OHLC value are dropped, not interpolated. This is deliberate: the app never fabricates data.
7. **Indices have no volume**, so volume-based factors are skipped for them.
8. **Benchmark dependency.** If NIFTY fails to load, the relative-strength and market pillars are dropped and the weights re-normalised. The rating then rests on fewer inputs, which lowers confidence indirectly.
9. **Backtest bias.** The backtest is in-sample, ignores costs and only covers about 340 days. It is a sanity check, not evidence of an edge.
10. **Licensing.** Yahoo's terms don't grant a licence to redistribute exchange data in a published app. See `04-development-plan.md §3`.
