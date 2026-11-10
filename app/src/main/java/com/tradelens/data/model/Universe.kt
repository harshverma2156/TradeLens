package com.tradelens.data.model

/** Symbols use Yahoo Finance format: NSE = ".NS", BSE = ".BO", indices start with "^". */
object Universe {
    val INDICES = linkedMapOf(
        "^NSEI" to "NIFTY 50",
        "^BSESN" to "SENSEX",
        "^NSEBANK" to "BANK NIFTY",
        "^CNXIT" to "NIFTY IT",
    )

    /** NSE sectoral/thematic indices (Yahoo symbols, verified Nov 2026). */
    val SECTORS = linkedMapOf(
        "^CNXIT" to "NIFTY IT",
        "^NSEBANK" to "NIFTY BANK",
        "NIFTY_FIN_SERVICE.NS" to "NIFTY FIN SERVICE",
        "^CNXAUTO" to "NIFTY AUTO",
        "^CNXPHARMA" to "NIFTY PHARMA",
        "^CNXFMCG" to "NIFTY FMCG",
        "^CNXMETAL" to "NIFTY METAL",
        "^CNXENERGY" to "NIFTY ENERGY",
        "^CNXREALTY" to "NIFTY REALTY",
        "^CNXPSUBANK" to "NIFTY PSU BANK",
        "^CNXINFRA" to "NIFTY INFRA",
        "^CNXMEDIA" to "NIFTY MEDIA",
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
