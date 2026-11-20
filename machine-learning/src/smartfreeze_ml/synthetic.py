"""SYNTHETIC data generator - for software testing ONLY.

Produces CSV rows in the same format as the Android export so the pipeline,
the exporter and the Kotlin parity tests can be exercised before real phone
data exists. Every generated row has is_simulated = 1.

Nothing produced from this data may be reported as a research result. Metrics
computed on it only show that the code runs end to end. They say nothing about
real Samsung Galaxy S23 Ultra behaviour.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

GiB = 1024 ** 3

# Rough, made-up profiles: (mean available-memory ratio, ratio noise, thermal level, battery temp)
_PROFILES = {
    "idle":          (0.48, 0.010, 0, 29.0),
    "browsing":      (0.40, 0.030, 0, 32.0),
    "video":         (0.42, 0.015, 1, 34.0),
    "app_switching": (0.34, 0.045, 1, 34.0),
    "charging":      (0.45, 0.012, 1, 37.0),
    "gaming":        (0.30, 0.030, 2, 39.0),
}


def generate(n_sessions: int = 24, minutes_per_session: int = 30, interval_s: int = 5,
             anomaly_session_fraction: float = 0.35, seed: int = 7,
             start_ms: int = 1_767_225_600_000) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    total = 12 * GiB
    threshold = int(0.06 * total)
    storage_total = 512 * GiB
    rows = []
    t0 = start_ms
    activities = list(_PROFILES)
    for s in range(n_sessions):
        act = activities[s % len(activities)]
        mu, sd, th, bt = _PROFILES[act]
        n = minutes_per_session * 60 // interval_s
        has_anom = rng.random() < anomaly_session_fraction and s >= 2
        a_start, a_len, kind = -1, 0, None
        if has_anom:
            a_len = int(rng.integers(36, 90))          # 3 to 7.5 minutes at 5 s
            a_start = int(rng.integers(n // 4, n - a_len - 1))
            kind = rng.choice(["memory_leak", "thermal_ramp", "combined"])
        batt = float(rng.uniform(35, 95))
        storage_free = float(rng.uniform(0.25, 0.6))
        ratio = mu
        for i in range(n):
            ratio = 0.85 * ratio + 0.15 * mu + rng.normal(0, sd)
            thermal = th if rng.random() > 0.05 else max(0, th + int(rng.choice([-1, 1])))
            temp = bt + rng.normal(0, 0.6)
            label = 0
            if a_start <= i < a_start + a_len:
                prog = (i - a_start) / a_len
                label = 1
                if kind in ("memory_leak", "combined"):
                    ratio = max(0.02, mu - 0.32 * prog + rng.normal(0, sd / 2))
                if kind in ("thermal_ramp", "combined"):
                    thermal = min(6, th + 1 + int(3 * prog))
                    temp = bt + 9 * prog
            r = float(np.clip(ratio, 0.01, 0.95))
            avail = int(r * total)
            charging = 1 if act == "charging" else 0
            batt = min(100.0, batt + 0.02) if charging else max(1.0, batt - 0.01)
            rows.append({
                "session_id": f"SIM-{s:03d}",
                "timestamp_ms": t0 + i * interval_s * 1000 + int(rng.integers(0, 40)),
                "interval_s": interval_s,
                "mem_total_bytes": total,
                "mem_avail_bytes": avail,
                "mem_threshold_bytes": threshold,
                "low_memory": int(avail < threshold),
                "battery_level_pct": round(batt),
                "is_charging": charging,
                "activity_label": act,
                "thermal_status": thermal,
                # headroom is deliberately missing for ~30% of rows, as on some devices
                "thermal_headroom": np.nan if rng.random() < 0.3 else round(0.35 + 0.18 * thermal + rng.normal(0, 0.03), 4),
                "battery_temp_c": round(temp, 1),
                "storage_total_bytes": storage_total,
                "storage_avail_bytes": int(storage_free * storage_total),
                "app_pss_kb": int(rng.normal(62_000, 1500)),
                "label_anomaly": label,
                "is_simulated": 1,
            })
        t0 += (n * interval_s + 600) * 1000
    return pd.DataFrame(rows)


def generate_slowdown_events(samples: pd.DataFrame, seed: int = 11) -> pd.DataFrame:
    """Synthetic slowdown events that occur near the end of abnormal episodes (test use only)."""
    rng = np.random.default_rng(seed)
    events = []
    for sid, sess in samples.groupby("session_id"):
        lab = sess["label_anomaly"].to_numpy()
        ts = sess["timestamp_ms"].to_numpy()
        idx = np.flatnonzero(lab == 1)
        if len(idx):
            k = idx[int(len(idx) * 0.8)]
            events.append({"session_id": sid, "timestamp_ms": int(ts[k]), "event_type": "latency_probe",
                           "latency_ms": int(rng.integers(250, 900)), "source": "synthetic", "is_simulated": 1})
    return pd.DataFrame(events, columns=["session_id", "timestamp_ms", "event_type", "latency_ms", "source", "is_simulated"])
