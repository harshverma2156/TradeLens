package com.tradelens.data.model

/** Symbols use Yahoo Finance format: NSE = ".NS", BSE = ".BO", indices start with "^". */
object Universe {
    val INDICES = linkedMapOf(
        "^NSEI" to "NIFTY 50",
        "^BSESN" to "SENSEX",
        "^NSEBANK" to "BANK NIFTY",
        "^CNXIT" to "NIFTY IT",
    )

    // Edit this list freely; symbols that fail to load are skipped by the scanner.
    val NIFTY_50 = listOf(
        "RELIANCE", "HDFCBANK", "ICICIBANK", "BHARTIARTL", "TCS", "INFY", "SBIN", "ITC",
        "LT", "HINDUNILVR", "BAJFINANCE", "KOTAKBANK", "AXISBANK", "MARUTI", "SUNPHARMA",
        "HCLTECH", "M&M", "TITAN", "ULTRACEMCO", "NTPC", "ASIANPAINT", "POWERGRID", "ONGC",
        "NESTLEIND", "BAJAJFINSV", "ADANIENT", "ADANIPORTS", "COALINDIA", "JSWSTEEL",
        "TATASTEEL", "WIPRO", "GRASIM", "TECHM", "HINDALCO", "CIPLA", "DRREDDY", "EICHERMOT",
        "BAJAJ-AUTO", "HEROMOTOCO", "BRITANNIA", "APOLLOHOSP", "SBILIFE", "HDFCLIFE",
        "TATACONSUM", "SHRIRAMFIN", "TRENT", "BEL", "INDUSINDBK", "JIOFIN",
    ).map { "$it.NS" }

    val POPULAR = NIFTY_50.take(14)
}
