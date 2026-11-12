"""
Downloads real NSE daily history for TradeLens' chart-pattern tests.

  app/src/test/resources/patterns/history/<SYMBOL>.csv   time,open,high,low,close,volume  (epoch ms, 09:15 IST)

The range is FIXED (2015-01-01 .. 2025-12-31) so test expectations stay reproducible. The data comes from
Yahoo Finance's unofficial chart endpoint, whose terms don't clearly allow redistribution, so the output folder
is git-ignored by default. Tests that need it are skipped (not failed) when it is missing.

Run (standard library only):  python tools/pattern-history/fetch_history.py
"""
import csv, json, os, sys, time, urllib.parse, urllib.request
from datetime import datetime, timezone

SYMBOLS = ["RELIANCE.NS", "TCS.NS", "INFY.NS", "HDFCBANK.NS", "ICICIBANK.NS", "SBIN.NS", "ITC.NS", "LT.NS",
           "BHARTIARTL.NS", "AXISBANK.NS", "MARUTI.NS", "SUNPHARMA.NS", "HINDUNILVR.NS", "TITAN.NS", "^NSEI"]
START = int(datetime(2015, 1, 1, tzinfo=timezone.utc).timestamp())
END = int(datetime(2026, 1, 1, tzinfo=timezone.utc).timestamp())
IST_OPEN_MS = (3 * 3600 + 45 * 60) * 1000      # 09:15 IST = 03:45 UTC

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "app", "src", "test", "resources", "patterns", "history")
os.makedirs(out, exist_ok=True)

for sym in SYMBOLS:
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(sym)}?period1={START}&period2={END}&interval=1d&events=split"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (research fixture fetcher)"})
    data = json.load(urllib.request.urlopen(req, timeout=30))["chart"]["result"][0]
    q = data["indicators"]["quote"][0]
    rows = []
    for k, ts in enumerate(data["timestamp"]):
        o, h, l, c, v = q["open"][k], q["high"][k], q["low"][k], q["close"][k], q["volume"][k]
        if None in (o, h, l, c):
            continue                                   # Yahoo leaves holes on some holidays
        day_ms = (ts + 19800) // 86400 * 86400 * 1000  # IST calendar day, midnight UTC
        rows.append((day_ms + IST_OPEN_MS, round(o, 4), round(h, 4), round(l, 4), round(c, 4), int(v or 0)))
    name = sym.replace("^", "IDX_")
    with open(os.path.join(out, f"{name}.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["time", "open", "high", "low", "close", "volume"])
        w.writerows(rows)
    print(f"{sym}: {len(rows)} bars")
    time.sleep(0.5)
