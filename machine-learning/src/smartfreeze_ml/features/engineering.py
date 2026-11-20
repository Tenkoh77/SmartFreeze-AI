"""Leakage-free rolling feature engineering.

This module is the reference implementation of the feature vector defined in
data/schemas/feature_schema.json. The Kotlin class `FeatureProcessor` on the
phone implements exactly the same algorithm; `export/fixtures.py` writes test
fixtures so the two implementations can be checked against each other.

The code uses an explicit per-sample loop (rather than pandas rolling helpers)
on purpose: it is easy to read, easy to port line-by-line to Kotlin, and makes it
obvious that each feature only uses the current and *earlier* samples.
"""

from __future__ import annotations

import math
from collections import deque

import numpy as np
import pandas as pd

from ..schema import FEATURE_NAMES

PRESSURE_MARGIN = 0.05   # mem_margin_ratio below this counts as memory pressure
PRESSURE_THERMAL = 3     # PowerManager.THERMAL_STATUS_SEVERE


def _nan(x) -> float:
    return float("nan") if x is None or (isinstance(x, float) and math.isnan(x)) else float(x)


def _is_pressure(low_memory: float, margin: float, thermal: float) -> bool:
    return low_memory == 1 or margin < PRESSURE_MARGIN or (not math.isnan(thermal) and thermal >= PRESSURE_THERMAL)


def _slope_per_minute(times_ms: list[int], values: list[float]) -> float:
    n = len(values)
    if n < 2:
        return 0.0
    t_now = times_ms[-1]
    xs = [(t - t_now) / 60000.0 for t in times_ms]
    mx = sum(xs) / n
    my = sum(values) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0.0:
        return 0.0
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, values))
    return sxy / sxx


def compute_session_features(session: pd.DataFrame, window_seconds: int) -> np.ndarray:
    """Feature matrix (n_samples x n_features) for ONE session sorted by timestamp."""
    window_ms = window_seconds * 1000
    rows = session.to_dict("records")
    out = np.full((len(rows), len(FEATURE_NAMES)), np.nan, dtype=np.float64)

    win: deque[dict] = deque()
    prev_t = None
    for i, r in enumerate(rows):
        t = int(r["timestamp_ms"])
        total = float(r["mem_total_bytes"])
        ratio = float(r["mem_avail_bytes"]) / total
        margin = (float(r["mem_avail_bytes"]) - float(r["mem_threshold_bytes"])) / total
        low = float(r["low_memory"])
        thermal = _nan(r.get("thermal_status"))
        headroom = _nan(r.get("thermal_headroom"))
        batt = float(r["battery_level_pct"]) / 100.0
        charging = float(r["is_charging"])
        btemp = _nan(r.get("battery_temp_c"))
        st_total = _nan(r.get("storage_total_bytes"))
        st_avail = _nan(r.get("storage_avail_bytes"))
        storage = st_avail / st_total if not (math.isnan(st_total) or math.isnan(st_avail)) and st_total > 0 else float("nan")

        win.append({"t": t, "ratio": ratio, "thermal": thermal,
                    "pressure": _is_pressure(low, margin, thermal)})
        while win and win[0]["t"] <= t - window_ms:
            win.popleft()

        ratios = [w["ratio"] for w in win]
        n = len(ratios)
        mean = sum(ratios) / n
        std = math.sqrt(sum((v - mean) ** 2 for v in ratios) / n)
        slope = _slope_per_minute([w["t"] for w in win], ratios)
        thermals = [w["thermal"] for w in win if not math.isnan(w["thermal"])]
        tdelta = float("nan") if math.isnan(thermal) else thermal - min(thermals)
        pfrac = sum(1 for w in win if w["pressure"]) / n
        gap = float("nan") if prev_t is None else (t - prev_t) / 1000.0
        expected = max(1, window_seconds // int(r["interval_s"]))
        completeness = min(1.0, n / expected)

        out[i] = [ratio, margin, low, thermal, headroom, batt, charging, btemp, storage,
                  mean, std, slope, tdelta, pfrac, gap, completeness]
        prev_t = t
    return out


def compute_features(df: pd.DataFrame, window_seconds: int = 60) -> pd.DataFrame:
    """Features for a validated multi-session DataFrame.

    Returns a DataFrame with identifying columns (session_id, timestamp_ms,
    activity_label, label_anomaly, is_simulated) followed by FEATURE_NAMES.
    Windows never cross session boundaries.
    """
    parts = []
    for sid, sess in df.groupby("session_id", sort=False):
        sess = sess.sort_values("timestamp_ms", kind="mergesort")
        feats = compute_session_features(sess, window_seconds)
        part = pd.DataFrame(feats, columns=FEATURE_NAMES)
        part.insert(0, "session_id", sid)
        part.insert(1, "timestamp_ms", sess["timestamp_ms"].to_numpy())
        part.insert(2, "activity_label", sess["activity_label"].to_numpy())
        part.insert(3, "label_anomaly", sess["label_anomaly"].to_numpy())
        part.insert(4, "is_simulated", sess["is_simulated"].to_numpy())
        parts.append(part)
    if not parts:
        return pd.DataFrame(columns=["session_id", "timestamp_ms", "activity_label",
                                     "label_anomaly", "is_simulated", *FEATURE_NAMES])
    return pd.concat(parts, ignore_index=True)
