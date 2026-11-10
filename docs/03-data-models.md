# Data models

These go into `:core:model` as plain Kotlin. Several already exist and are **kept and extended,
not replaced**:

| Proposed | Existing type today |
|---|---|
| `Candle` | `data/model/Candle`. Unchanged. |
| `Quote` | `data/model/Quote`. Phase 1 added `asOf`. |
| `TechnicalIndicator` | Values inside `analysis/Factor`. |
| `TradeSignal` | `analysis/Analysis` (signal, score, confidence). |
| `EntryZone` | `analysis/EntryAdvice`. |
| `StopLoss` / `PriceTarget` | Fields of `analysis/TradePlan`. |

Conventions:
- Money is `Double` in the instrument's currency for analysis, and `Long` paise in storage (see the schema).
- Time is epoch millis UTC. Display converts to IST.
- Every externally sourced model carries a `DataSource` (provider, as-of time, delay) so the UI can always show where a number came from.
- Optional fields are `null` when the source doesn't supply them. They are never defaulted to 0.

```kotlin
// ---------- shared
enum class Exchange { NSE, BSE }
data class DataSource(val provider: String, val asOf: Long, val delayedBySec: Int?, val licence: String?)

// ---------- instruments and prices
data class Stock(
    val symbol: String,            // provider-neutral, e.g. "RELIANCE"
    val exchange: Exchange,
    val isin: String?,             // stable ID across renames and demergers
    val name: String,
    val sector: String?,
    val industry: String?,
    val lotSize: Int?,             // F&O
    val tickSize: Double = 0.05,
    val isIndex: Boolean = false,
)

data class Candle(                 // OHLCV. Unchanged from today.
    val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long,
)

enum class Timeframe { M5, M15, H1, D1, W1 }

data class Quote(                  // Existing fields kept; source added.
    val symbol: String, val name: String, val price: Double, val previousClose: Double,
    val dayHigh: Double?, val dayLow: Double?, val volume: Long?,
    val high52: Double?, val low52: Double?, val currency: String,
    val asOf: Long?,
    val source: DataSource? = null,
)

data class MarketDepth(            // Needs a licensed Level-2 feed. Hidden when the provider lacks it.
    val symbol: String,
    val bids: List<DepthLevel>,    // best first
    val asks: List<DepthLevel>,
    val totalBuyQty: Long?, val totalSellQty: Long?,
    val source: DataSource,
)
data class DepthLevel(val price: Double, val quantity: Long, val orders: Int?)

// ---------- technical
data class TechnicalIndicator(
    val id: String,                // "RSI_14", "EMA_20", "SUPERTREND_10_3"
    val timeframe: Timeframe,
    val value: Double?,            // latest value; null while warming up
    val series: List<Double?>? = null,   // aligned to candles, for chart overlays
    val reading: Verdict,          // BULLISH / BEARISH / NEUTRAL (existing enum)
    val explanation: String,
)

enum class PatternType { DOUBLE_TOP, DOUBLE_BOTTOM, HEAD_SHOULDERS, INV_HEAD_SHOULDERS,
    ASC_TRIANGLE, DESC_TRIANGLE, SYM_TRIANGLE, BULL_FLAG, BEAR_FLAG, CUP_HANDLE,
    BULL_ENGULFING, BEAR_ENGULFING, HAMMER, SHOOTING_STAR, DOJI }
data class ChartPattern(
    val type: PatternType,
    val timeframe: Timeframe,
    val startTime: Long, val endTime: Long,
    val keyPoints: List<Pair<Long, Double>>,  // for drawing
    val breakoutLevel: Double?, val invalidationLevel: Double?, val measuredTarget: Double?,
    val status: PatternStatus,     // FORMING, CONFIRMED, FAILED
    val confidence: Int,           // 0..100, from the shape fit, volume and backtested hit rate
)
enum class PatternStatus { FORMING, CONFIRMED, FAILED }

// ---------- signals and trade plan
data class TradeSignal(
    val symbol: String,
    val generatedAt: Long,
    val engineVersion: String,     // so logged signals stay comparable across rule changes
    val rating: Signal,            // existing enum: STRONG_BUY … STRONG_SELL
    val score: Int, val confidence: Int,
    val pillars: List<PillarScore>,
    val entry: EntryZone, val stopLoss: StopLoss, val targets: List<PriceTarget>,
    val patterns: List<ChartPattern> = emptyList(),
    val warnings: List<String>,
    val inputs: DataSource,        // the data the signal was computed from
)

enum class EntryType { BUY_NOW, PULLBACK, BREAKOUT, WAIT, AVOID }   // existing
data class EntryZone(
    val type: EntryType, val price: Double, val low: Double, val high: Double,
    val confluence: List<EntryLevel>,          // existing EntryLevel(name, price)
    val trigger: String?, val validUntil: Long?,
)

enum class StopType { STRUCTURE, ATR, TRAILING_ATR, CHANDELIER, TIME }
data class StopLoss(val price: Double, val type: StopType, val pctFromEntry: Double, val rationale: String)

data class PriceTarget(
    val label: String, val price: Double, val pct: Double,
    val rMultiple: Double, val basis: String,  // "2.5R", "Resistance 1,420", "Measured move"
    val bookPct: Int?,                          // suggested % of position to exit there
)

// ---------- fundamentals and valuation
data class FundamentalMetrics(
    val symbol: String, val period: String,   // "TTM" or "FY2026"
    val marketCap: Double?, val pe: Double?, val pb: Double?, val evToEbitda: Double?,
    val dividendYield: Double?, val roe: Double?, val roce: Double?,
    val debtToEquity: Double?, val interestCoverage: Double?,
    val revenueGrowth3y: Double?, val profitGrowth3y: Double?,
    val promoterHolding: Double?, val promoterPledge: Double?,
    val source: DataSource,
)

enum class StatementType { INCOME, BALANCE_SHEET, CASH_FLOW }
data class FinancialStatement(
    val symbol: String, val type: StatementType,
    val periodEnd: Long, val periodType: String,        // "Q" / "FY"
    val consolidated: Boolean,
    val lineItems: Map<String, Double?>,                // standardised keys, e.g. "revenue", "netIncome"
    val currency: String, val unit: Long,               // 1e7 = crore
    val source: DataSource,                             // filing reference
)

// ---------- news and research
data class NewsArticle(
    val id: String, val symbols: List<String>,
    val headline: String, val summary: String?, val url: String,
    val publisher: String, val publishedAt: Long,
    val category: NewsCategory, val sentiment: Sentiment?,  // null if not classified
    val source: DataSource,
)
enum class NewsCategory { RESULTS, CORPORATE_ACTION, ORDER_WIN, RATING, REGULATORY, MANAGEMENT, MACRO, OTHER }
enum class Sentiment { POSITIVE, NEGATIVE, NEUTRAL }

data class StockResearchReport(
    val symbol: String, val generatedAt: Long, val model: String,   // e.g. "claude-opus-5-5"
    val summary: String,
    val bullCase: List<Citation>, val bearCase: List<Citation>, val risks: List<Citation>,
    val technical: TradeSignal?, val fundamentals: FundamentalMetrics?,
    val news: List<NewsArticle>,
    val disclaimer: String,
)
data class Citation(val text: String, val sourceRef: String)   // every claim points at a supplied input
```
