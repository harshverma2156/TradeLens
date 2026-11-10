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
| Stock detail | Candle/line chart (1D–5Y) with crosshair and entry/SL/target lines; key stats; verdict and score; trade plan; indicator breakdown; support/resistance; warnings; paper Buy/Sell; price alert |
| Watchlist | Starred stocks with live prices |
| Scanner | Runs the analysis on all Nifty 50 stocks (or your watchlist) and ranks buy and sell setups |
| Portfolio | Paper trading with virtual money: holdings, unrealised and realised P&L, trade history |
| Alerts | Background price alerts via WorkManager (about every 15 min, Android's minimum) |
| Settings | Capital and risk % per trade, used for position sizing |

## How the analysis works (`analysis/AnalysisEngine.kt`)
Uses one year of daily candles. Nine factors each add or subtract points; the total (−100 to +100)
maps to Strong buy (≥40), Buy (≥15), Wait, Sell (≤−15), Strong sell (≤−40).

1. Trend: price vs 20 and 50 EMA (±15)
2. Long-term trend: price vs 200 EMA (±10)
3. RSI 14: momentum, overbought >75, oversold <30
4. MACD 12/26/9: position vs signal, fresh crossovers score higher (±10/15)
5. ADX 14: trend strength and direction (+DI vs −DI)
6. Bollinger Bands 20/2: stretch and squeeze
7. Volume vs 20-day average on up/down days (±10)
8. Price structure: 20-day breakout/breakdown, near support or resistance (±8)
9. Position in the 52-week range (±5)

Trade plan (long; short is mirrored):
- Buy zone: from max(price − 0.75×ATR, nearest support) up to current price; entry is its midpoint
- Stop-loss: below support by 0.5×ATR, at least 1.5×ATR and at most 3×ATR from entry
- Targets: 1.5R, 2.5R and 4R, where R is entry minus stop-loss
- Quantity: (capital × risk%) ÷ R, capped at what your capital can buy, so a stop-loss hit costs your chosen risk
- Support/resistance: swing highs/lows from the last 6 months, clustered within 1.5%
- Prices round to the NSE tick size of ₹0.05

All thresholds are plain constants in that file; tune them and backtest before relying on them.

## Project layout
```
data/remote      Yahoo Finance API (Retrofit + kotlinx.serialization)
data/local       Room DB: watchlist, paper holdings, trades, alerts
data/repository  MarketRepository, UserRepository, SettingsRepository
analysis         Indicators (EMA, RSI, MACD, ATR, Bollinger, ADX, S/R) and AnalysisEngine
work             AlertWorker + scheduler
ui               Compose theme, chart, screens and ViewModels
```

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
Backtesting the rules on historical data; fundamentals (P/E, ROE, debt) from a data provider;
news and results calendar; multi-timeframe confirmation (weekly trend); unit tests for `Ta` and
`AnalysisEngine`; Hilt for DI; a proper adaptive launcher icon.
