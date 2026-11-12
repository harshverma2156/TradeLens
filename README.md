# TradeLens: automated stock analysis for Android

Native Android app (Kotlin + Jetpack Compose) for Indian stocks (NSE/BSE). Tap any stock and it
runs a full technical analysis, then suggests a buy zone, entry, stop-loss, three targets,
reward-to-risk, position size and holding period.

## Open and run
1. Open the `TradeLens` folder in Android Studio (Koala or newer). Let Gradle sync; if it asks to
   create the Gradle wrapper, accept.
2. Run on a device or emulator with Android 8.0+ (API 26).
3. Allow notifications so price alerts can reach you.

## Features
| Screen | What it does |
|---|---|
| Markets | NIFTY 50, SENSEX, BANK NIFTY, NIFTY IT; popular stocks with Top gainers / Top losers sorting |
| Search | Search NSE/BSE by name or ticker (debounced) |
| Technical analysis | Per-timeframe engine with 30+ indicators, chart overlays, oscillator panel and a Buy/Sell/Neutral summary that needs several independent indicator families to agree; chart and candlestick patterns drawn on the chart with breakout, stop and targets |
| Stock detail | Candle/line chart (1D–5Y) with crosshair and entry/SL/target lines; key stats; verdict and score; trade plan; indicator breakdown; support/resistance; warnings; paper Buy/Sell; price alert |
| Watchlist | Starred stocks with live prices |
| Scanner | Runs the analysis on all Nifty 50 stocks (or your watchlist) and ranks buy and sell setups |
| Portfolio | Paper trading with virtual money: holdings, unrealised and realised P&L, trade history |
| Alerts | Background price alerts via WorkManager (about every 15 min, Android's minimum) |
| Settings | Capital and risk % per trade, used for position sizing |

## How the analysis works (`analysis/AnalysisEngine.kt`)
Uses two years of daily candles plus NIFTY 50 as a benchmark. It runs on the device and needs no AI model or paid API.

**Rating.** Factors are grouped into six pillars. Each pillar scores −100 to +100 and the weighted average is the overall score:

| Pillar | Weight | Factors |
|---|---|---|
| Trend | 30% | 20/50 EMA stack, 200 EMA level and slope, weekly trend (10/30-week EMA), Supertrend (10,3), ADX/DI |
| Momentum | 20% | RSI 14 regime, MACD 12/26/9 (position, zero line, fresh cross), 3- and 6-month return |
| Relative strength | 15% | 3- and 6-month return vs NIFTY 50, RS line vs its 100-day average |
| Volume & money flow | 15% | OBV trend and divergence, MFI 14, 20-day up/down volume ratio, today's volume surge |
| Price location | 10% | 20-day breakout or breakdown, near support or resistance, 52-week position, stretch from 20 EMA in ATRs, Bollinger Bands |
| Market backdrop | 10% | NIFTY vs its 50/200 EMA, NIFTY one-month return |

If a pillar is missing (no benchmark, or an index with no volume), the remaining weights are re-normalised. Score bands are Strong buy ≥45, Buy ≥20, Hold, Sell ≤−20 and Strong sell ≤−45. Then these guardrails apply:
- A Buy inside a weekly downtrend becomes Hold.
- A Strong buy that is more than 3 ATR above the 20 EMA becomes Buy.
- A Strong buy in a weak market becomes Buy.
- A Strong sell in a weekly uptrend becomes Sell.

**Best entry.** The engine gathers candidate levels within 2.5 ATR of the price:
- 20, 50 and 200 EMA
- Supertrend line
- swing support (weighted by how often it was touched)
- volume-profile POC (the most-traded price) and value-area edge
- Fibonacci 38.2%, 50% and 61.8% of the last swing
- VWAP anchored at the swing low
- the breakout retest level

Levels within 0.6 ATR of each other are clustered. The cluster with the most weight, discounted by distance, becomes the entry zone; strong trends are discounted harder, so they favour shallow pullbacks. The result is one of these: Buy now (price is in the zone), Wait for a pullback (place a limit order at the zone), Buy the breakout (fresh breakout on heavy volume), Wait for confirmation (Hold, with a trigger price) or Avoid (Sell).

**Trade plan** (long; short is mirrored):
- Stop-loss: 0.5 ATR below the zone, between 1.5 and 3 ATR from entry.
- Targets: 1.5R, 2.5R and 4R, where R is entry minus stop-loss.
- Quantity: (capital × risk%) ÷ R, capped at what your capital can buy.
- Prices round to the NSE tick size of ₹0.05.

**Backtest and confidence.** The same rules are replayed over about 340 past trading days of the stock, with no look-ahead. Each Buy is entered at the next day's open, with a 1.5 ATR stop, a 3 ATR target and a 30-day limit. The app shows the win rate against a random-entry baseline that uses the same exits on every day, plus profit factor and return vs buy-and-hold.

Confidence (0–100) combines:
- how many pillars agree
- how strong the score is
- whether past signals on this stock beat the baseline and made money

All thresholds are plain constants in that file. The backtest is in-sample and has no costs; treat it as a sanity check, not a promise.

## Project layout
```
data/remote      MarketDataProvider interface; YahooFinanceProvider (Retrofit + kotlinx.serialization)
data/local       Room DB: watchlist, paper holdings, trades, alerts
data/repository  MarketRepository, UserRepository, SettingsRepository
marketdata       Timeframes, NSE/BSE calendar, candle validation, resampling, rate limiting
ta               Technical analysis engine: indicator services, interpretation, family voting, cache
patterns         Chart and candlestick pattern recognition (walk-forward, no look-ahead)
trade            Smart entry/exit engine: levels and zones, three strategies, exits, sizing, signal stability
analysis         Rating engine (AnalysisEngine); Ta facade over the ta services
work             AlertWorker + scheduler
ui               Compose theme, chart, screens and ViewModels
```
Engineering docs (audit, target architecture, data models, roadmap) are in [docs/](docs/README.md).

## Important limitations
- Data source: the free Yahoo Finance endpoints are unofficial, may be delayed, and can rate-limit
  or change without notice. For a real product, use a licensed feed. Broker APIs such as Zerodha Kite
  Connect, Upstox, Angel One SmartAPI or Dhan provide live quotes, historical candles and order placement.
- Buy/Sell are paper trades. To place real orders, add a `BrokerRepository` that wraps your broker's
  order API (login/OAuth, place order, GTT/stop-loss orders, positions).
- The analysis is technical only. It ignores results, news, fundamentals and corporate actions.
- Regulation (India): publishing buy/sell recommendations to the public, especially for a fee,
  generally requires SEBI registration as a Research Analyst or Investment Adviser, and retail algo
  trading has its own SEBI framework through brokers. Check the current rules before releasing publicly.

## Next steps worth adding
Out-of-sample backtesting across the whole universe; fundamentals (P/E, ROE, debt) from a data provider;
news and results calendar; Hilt for DI; a proper adaptive launcher icon.
