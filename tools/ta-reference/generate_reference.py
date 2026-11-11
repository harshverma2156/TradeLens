"""
Generates reference fixtures for TradeLens' technical-indicator tests.

  candles.csv    synthetic OHLCV (seeded, reproducible) with gaps, a flat stretch and volume spikes
  reference.csv  indicator values computed by the open-source `ta` library (bukosabino/ta, v0.11.0)

Synthetic data is used on purpose: it exercises edge cases deliberately, and committing real exchange
prices to a public repo would raise data-licensing questions.

Run:  python -m venv venv && venv/bin/pip install ta pandas numpy
      venv/bin/python generate_reference.py ../../app/src/test/resources/ta
"""
import sys, os
import numpy as np
import pandas as pd
from ta import trend, momentum, volatility, volume

out = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(out, exist_ok=True)

rng = np.random.default_rng(20261110)
n = 3000
close = np.empty(n); open_ = np.empty(n); high = np.empty(n); low = np.empty(n); vol = np.empty(n)
px = 1000.0
for i in range(n):
    gap = 0.0
    if i > 0 and i % 250 == 0:
        gap = rng.choice([-0.08, 0.08])                  # overnight gaps
    o = px * (1 + gap + rng.normal(0, 0.003))
    c = o * np.exp(rng.normal(0.0003, 0.015))
    h = max(o, c) * (1 + abs(rng.normal(0, 0.006)))
    l = min(o, c) * (1 - abs(rng.normal(0, 0.006)))
    v = float(int(rng.lognormal(13, 0.4)))
    if i % 333 == 0:
        v *= 8                                          # volume spikes
    if 1500 <= i < 1525:                                # flat market: O=H=L=C
        o = h = l = c = round(px, 2)
    open_[i], high[i], low[i], close[i], vol[i] = o, h, l, c, v
    px = c

# Weekday timestamps at 09:15 IST (03:45 UTC), epoch millis.
days = pd.bdate_range("2014-01-01", periods=n)                       # midnight UTC, weekdays
times = days.as_unit("ms").asi8 + (3 * 3600 + 45 * 60) * 1000     # + 03:45 UTC = 09:15 IST, epoch ms

df = pd.DataFrame({"time": times, "open": open_, "high": high, "low": low, "close": close, "volume": vol})
df.to_csv(os.path.join(out, "candles.csv"), index=False, float_format="%.10f")

H, L, C, V = df.high, df.low, df.close, df.volume
ref = pd.DataFrame({"time": df.time})
for p in (9, 20, 21, 50, 100, 200):
    ref[f"ema{p}"] = trend.EMAIndicator(C, p).ema_indicator()
for p in (50, 100, 200):
    ref[f"sma{p}"] = trend.SMAIndicator(C, p).sma_indicator()
m = trend.MACD(C, 26, 12, 9)
ref["macd"], ref["macd_signal"], ref["macd_hist"] = m.macd(), m.macd_signal(), m.macd_diff()
a = trend.ADXIndicator(H, L, C, 14)
ref["adx"], ref["plus_di"], ref["minus_di"] = a.adx(), a.adx_pos(), a.adx_neg()
ich = trend.IchimokuIndicator(H, L, 9, 26, 52, visual=False)
ref["tenkan"], ref["kijun"], ref["span_a"], ref["span_b"] = ich.ichimoku_conversion_line(), ich.ichimoku_base_line(), ich.ichimoku_a(), ich.ichimoku_b()
ref["psar"] = trend.PSARIndicator(H, L, C, 0.02, 0.2).psar()
ref["cci"] = trend.CCIIndicator(H, L, C, 20, 0.015).cci()
ref["rsi"] = momentum.RSIIndicator(C, 14).rsi()
st = momentum.StochasticOscillator(H, L, C, 14, 3)
ref["stoch_k_fast"], ref["stoch_d"] = st.stoch(), st.stoch_signal()
sr = momentum.StochRSIIndicator(C, 14, 3, 3)
ref["stochrsi"], ref["stochrsi_k"], ref["stochrsi_d"] = sr.stochrsi(), sr.stochrsi_k(), sr.stochrsi_d()
ref["williams_r"] = momentum.WilliamsRIndicator(H, L, C, 14).williams_r()
ref["atr"] = volatility.AverageTrueRange(H, L, C, 14).average_true_range()
ref["atr10"] = volatility.AverageTrueRange(H, L, C, 10).average_true_range()
bb = volatility.BollingerBands(C, 20, 2)
ref["bb_mid"], ref["bb_upper"], ref["bb_lower"] = bb.bollinger_mavg(), bb.bollinger_hband(), bb.bollinger_lband()
kc = volatility.KeltnerChannel(H, L, C, 20, 10, original_version=False, multiplier=2)
ref["kc_mid"], ref["kc_upper"], ref["kc_lower"] = kc.keltner_channel_mband(), kc.keltner_channel_hband(), kc.keltner_channel_lband()
dc = volatility.DonchianChannel(H, L, C, 20)
ref["dc_upper"], ref["dc_lower"], ref["dc_mid"] = dc.donchian_channel_hband(), dc.donchian_channel_lband(), dc.donchian_channel_mband()
ref["obv"] = volume.OnBalanceVolumeIndicator(C, V).on_balance_volume()
ref["adl"] = volume.AccDistIndexIndicator(H, L, C, V).acc_dist_index()
ref["mfi"] = volume.MFIIndicator(H, L, C, V, 14).money_flow_index()
# Anchored VWAP from bar 2000, computed independently with pandas cumulative sums.
tp = (H + L + C) / 3
mask = df.index >= 2000
ref["avwap2000"] = np.where(mask, (tp * V).where(mask, 0).cumsum() / V.where(mask, 0).cumsum(), np.nan)

ref.to_csv(os.path.join(out, "reference.csv"), index=False, float_format="%.10f", na_rep="")
print("wrote", len(df), "candles and", len(ref.columns) - 1, "reference series to", out)
