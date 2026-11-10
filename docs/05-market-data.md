# Phase 2: market data infrastructure

## 1. How data flows

```
Provider (Yahoo / Backend / Demo)
   │  MarketDataProvider: quotes(batch) · candles(tf, from, to) · corporateActions · search
   ▼
MarketRepository
   ├─ RateLimiter (per provider) → withRetry (exponential backoff + jitter, honours Retry-After)
   ├─ InFlight (coalesces identical concurrent requests)
   ├─ Resampler (builds 30m/1h/W/M from smaller bars when a provider lacks them)
   ├─ CandleValidator (on write, and again on read)
   ▼
Room v2: candles · sync_state · quotes · corporate_actions · market_holidays
   ▼
CandleResult / Quote + DataStatus  →  UI (badge, timestamp, provider, availability, errors)
```

- **Room is the source of truth.** The network only fills and refreshes it. When a refresh fails, the app shows the stored data marked **Offline · cached** and explains the error. It throws only when nothing is stored at all.
- **Every row records its source** (`source` is part of every primary key). Demo data, Yahoo data and future backend data never mix.
- **Incremental sync:** `sync_state` remembers the first and last bar held for each (source, symbol, timeframe). A refresh fetches only from just before the last bar. The overlap is 5 days for daily, 14 for weekly, 62 for monthly and 3 bars for intraday, so a bar that was still forming gets finalised.

## 2. Timeframes and sessions

| Timeframe | Yahoo (verified) | Built locally if missing |
|---|---|---|
| 1m | ≤ ~7 days back (8 days hard limit; HTTP 422 beyond) | no (nothing smaller to build from) |
| 5m / 15m / 30m | ≤ 59 days | from 1m / 5m / 15m |
| 1h | ≤ 729 days (bars at 09:15, 10:15 … 15:15) | from 30m / 15m |
| Daily | full history | from intraday |
| Weekly / Monthly | full history | from daily |

- **Session:** NSE/BSE cash market, 09:15–15:30 IST, Mon–Fri, no DST. Intraday buckets are aligned to 09:15. The last 30m or 1h bar of the day is short (15:15–15:30), exactly as exchanges publish it.
- **Holidays are never hard-coded.** Until an official list is loaded (planned: from the backend, ingested from NSE circulars), holidays are **inferred** from weekdays on which NIFTY 50 didn't trade. They are stored with `source = inferred:^NSEI@<provider>`. Dates after the last known index bar are treated as trading days. The UI says so, and the validator reports gaps on such dates as *info*, not warnings.
- **Special sessions** (e.g. Muhurat trading on a weekend) are kept and flagged, not dropped.

## 3. Validation rules (`CandleValidator`)

| Check | Action | Severity |
|---|---|---|
| Bar dated in the future | Dropped | Error |
| Intraday bar outside 09:15–15:30 | Dropped | Warning |
| Timestamp not on a bar boundary | Snapped to the bucket start, flagged `TIME_NORMALISED` | Info |
| Impossible OHLC (non-finite or ≤ 0, high < max(open, close), low > min(open, close), high < low, negative volume) | Dropped | Error |
| Duplicate bars for one period | Latest kept, flagged `DUPLICATE_RESOLVED`. Yahoo really does this: weekly and monthly series end with the period's bar **and** a live partial bar for the same period | Warning |
| Missing bars between first and last | **Reported, never filled** | Warning (known trading days) / Info (intraday, or holidays unknown) |
| Latest bar older than expected | `STALE` | Warning if more than one bar behind |
| Volume ≥ 5× the 20-bar median | Kept, flagged `UNUSUAL_VOLUME` | Info |
| Split/bonus with matching price gap and unadjusted feed | Earlier bars back-adjusted, flagged `ADJUSTED` | Info |
| Provider says "adjusted" but the gap is present | Kept as is | Warning |
| Gap ≥ 20% daily (10% intraday) with no corporate action within 3 days | Flagged `GAP` | Warning |

Each stored bar keeps its `quality` bitmask. Each series keeps a score out of 100 and a summary in `sync_state`, and both are shown in the **Data source & quality** card.

## 4. Freshness rules (what the badge says)

| Badge | When |
|---|---|
| **DEMO DATA** | Demo provider active (also a purple banner on every screen) |
| **Offline · cached** | The last refresh failed; showing stored data, with the saved time |
| **Live** | Real-time provider, session open, data no more than 2 min old |
| **Delayed** | Delayed provider, session open, data no more than 20 min old |
| **Stale** | Session open and data older than that, or market closed and the data predates the last session's close |
| **Market closed** | Outside the session and the data covers the last session |

Quotes and candle series use the same rule. Intraday series aren't called stale for the normal lag of a delayed feed.

**Cache lifetimes:**
- quotes: 15 s (real-time) or 60 s (delayed) while the session is open; otherwise until the next session;
- candles while the session is open: 1 min (1m bars), 2 min (5m), 5 min (15m), 10 min (others);
- candles while the market is closed: fresh once synced after the close.

**On-device retention:** 1m bars 30 days; 5m and 15m 90 days; 30m 180 days; 1h 2 years; daily and longer kept indefinitely.

## 5. Performance

| Requirement | Implementation |
|---|---|
| Caching | Room (persistent) plus an in-memory quote cache with session-aware lifetimes |
| Rate limiting | Token bucket per provider from `ProviderInfo.rateLimit`. Yahoo: 4/s, burst 8 (Yahoo publishes no limit, so this is deliberately conservative) |
| Batching | Quotes are chunked to the provider's `batchQuotes` (Yahoo spark: 20, verified; 21 returns HTTP 400). Markets loads 30 symbols in 3 calls instead of 30 |
| Retry | 3 attempts, 500 ms → 1 s → 2 s with ±20% jitter. Retries network errors, 408, 429 (honouring `Retry-After`) and 5xx. Never retries 4xx or cancellation |
| Coalescing | Identical concurrent requests share one network call |
| Offline fallback | Stored candles and quotes, marked Offline with the error and the saved time |
| Background refresh | `MarketSyncWorker` runs hourly (network connected, battery not low). It syncs the watchlist, holdings and NIFTY 50 incrementally, refreshes quotes and prunes old intraday bars. Paused in demo mode. Alerts use one batched, cache-bypassing request, and never fire on cached or demo prices |

## 6. Provider evaluation

Only the Yahoo column was **verified** against the live API in this phase. The other columns are based on the providers' published documentation as we understand it and **must be confirmed with each provider** before implementation. Commercial terms and rate limits in particular change often.

| Capability | Yahoo Finance (current) | Zerodha Kite Connect | Upstox API | NSE-authorised data vendor |
|---|---|---|---|---|
| Quote type | Delayed, unofficial | Real-time (account holder) | Real-time (account holder) | Real-time or delayed, by licence |
| Batch quotes | 20 (spark) | Hundreds per call | Hundreds per call | Vendor-specific |
| Streaming | No | WebSocket | WebSocket (protobuf) | Usually |
| 1m / 5m / 15m / 30m / 1h | ✓ (lookback limits above) | Minute intervals incl. 1/5/15/30/60 | Minute and hour intervals | ✓ |
| Daily | ✓ | ✓ | ✓ | ✓ |
| Weekly / monthly | ✓ | ✗ (resample from daily) | ✓ | Vendor-specific |
| Adjusted close | ✓ (daily+) | ✗ / to verify | To verify | Vendor-specific |
| Split-adjusted OHLC | ✓ (verified) | To verify | To verify | Vendor-specific |
| Corporate actions | Splits + dividends via `events` (bonus reported as split) | No dedicated API; use exchange files | To verify | Usually, or NSE files |
| Indices / sector indices | ✓ (17 sector symbols verified) | ✓ | ✓ | ✓ (index licence may apply) |
| Market depth | ✗ | 5 levels (full mode) | Yes, depth varies by plan | L2 by licence |
| Auth | None | API key + **secret** → daily access token; secret must stay on a server | OAuth2 client secret → daily token; secret on a server | Vendor credentials on a server |
| Redistribution to other users | **Not permitted** | Account holder's own data only | Account holder's own data only | Only with an exchange-approved display licence |
| Fit for TradeLens | Development and personal use | "Bring your own broker" for logged-in users, via the backend | Same as Kite | Public app serving everyone the same data |

**Official free sources the backend should ingest** (no redistribution of real-time data involved):
- NSE's published trading-holiday circulars, which make the calendar `official`;
- end-of-day bhavcopy files;
- corporate-action announcements.

Respect the exchange website's terms for automated access.

**Recommendation:**
- Keep Yahoo for development.
- For a public release, either licence data through an NSE-authorised vendor for a market-wide view, or offer "connect your broker" (Kite or Upstox) so each user sees their own licensed data.
- In both cases the provider sits behind the backend, and the app switches with no code change via `-Ptradelens.backendUrl`.

## 7. Backend contract (`BackendMarketDataProvider`)

The backend translates whichever licensed provider it uses into this API. The app never sees provider credentials. It sends only a short-lived user token (a hook is ready; auth arrives with the backend).

| Endpoint | Returns |
|---|---|
| `GET /v1/provider` | `{id, name, mode: REALTIME\|DELAYED\|END_OF_DAY, licensedForDisplay, timeframes: {"1m": maxLookbackMs\|null, …}, batchQuotes, adjustedClose, splitAdjustedOhlc, corporateActions, indices, sectorIndices, search, marketDepth, permitsPerSecond, burst, note}` |
| `GET /v1/quotes?symbols=A,B` | `[{symbol, name, currency, price, previousClose, dayHigh, dayLow, volume, high52, low52, asOf}]`. Unknown symbols are omitted |
| `GET /v1/candles?symbol&timeframe&from&to` | `{symbol, timeframe, splitAdjusted, bars:[{t,o,h,l,c,v,adj}]}`, oldest first, epoch ms |
| `GET /v1/corporate-actions?symbol&from&to` | `[{symbol, type: SPLIT\|BONUS\|DIVIDEND, exDate, ratio, amount, source}]` |
| `GET /v1/search?q` | `[{symbol, name, exchange, type}]` |

Until `/v1/provider` answers, the app assumes the least: daily bars only, no batching. Errors use HTTP status codes, and 429 should send `Retry-After`.

## 8. Known limitations

1. **Yahoo is unofficial and unlicensed for redistribution.** It may be delayed, rate-limited or changed without notice. On 10 Nov 2026 at 18:00 IST its latest data was from 9 Nov 15:31 IST. The app shows **Stale** in that case rather than presenting it as current.
2. **The holiday calendar is inferred** until an official list is loaded. A holiday is only learned once NIFTY trades again afterwards, so on the holiday itself data shows as Stale, with a note explaining that it may be a holiday.
3. **Yahoo intraday history is short:** 1m about 7 days, 5m–30m about 59 days. The app says so instead of showing a shorter chart silently.
4. **Bonus issues** are reported by Yahoo as splits. Both are handled identically for price adjustment.
5. **Dividends are not back-adjusted in OHLC.** The adjusted close is stored separately where available, and the analysis uses split-adjusted OHLC.
6. **Spark returns closes only** (no open/high/low). The previous close is worked out from the close series, never from `chartPreviousClose`, which is the close before the *range*. That bug was caught on the device and is now covered by a test.
7. **No market depth or streaming** with Yahoo. Both need a licensed provider and the backend.
8. **Demo data is synthetic.** It follows session times but no holidays, and has no relation to real prices. It's always labelled, alerts and background sync are paused, and it's stored separately.
9. **The backend provider is untested against a real server** (none exists yet). Its contract is covered by MockWebServer tests only.

## 9. Security

- No API keys, secrets or tokens are in the APK. The backend URL is a build property and is not a secret.
- HTTPS only: `network_security_config` blocks cleartext.
- Kite and Upstox token exchange needs their client **secret**, so it must happen on the backend. See `04-development-plan.md §5`.

## 10. Tests (67, all passing)

| Suite | What it covers |
|---|---|
| `MarketCalendarTest` (6) | Session states; last close across weekends and holidays; bucket alignment for 1h/30m/W/M; expected bars per session; holiday inference; special sessions |
| `CandleValidatorTest` (15) | Missing bars (warning vs info), duplicates incl. Yahoo's partial weekly bar, invalid OHLC, volume spikes, indices, future and out-of-session timestamps, snapping, stale series, split back-adjustment, already-adjusted, adjusted-but-gapped, unexplained gaps, weekend special session |
| `ResamplerAndThrottlingTest` (9) | 1m→5m, 15m→1h short last bar, D→W/M, source selection; token bucket on virtual time; backoff schedule; give-up; non-retryable and Retry-After; request coalescing |
| `ProviderAdaptersTest` (7) | Yahoo daily, adjclose alignment and dropped nulls, interval mapping, corporate actions, spark batching with the real closes-only shape, 422; backend capability negotiation and parsing; demo labelling, determinism, no future bars |
| `MarketRepositoryTest` (19) | Cache hits, incremental sync without duplicates, offline fallback, failure with an empty cache, retries, lookback limits, resampling, unsupported timeframes, quote batching and caching, quote fallback, stale quotes, stale series, delayed intraday, demo/real isolation, corporate actions once a day, holiday inference, retention, analysis history |
| `MigrationTest` (1, Robolectric) | v1→v2 keeps watchlist, holdings, trades and alerts; schema validated against `2.json`; upserts don't duplicate |
| `AnalysisEngineTest` (10) | Unchanged from Phase 1 |

The v1→v2 migration was also verified on the emulator's real database. The existing watchlist and holdings (including an AXISBANK paper position) survived, and the new tables were created.
