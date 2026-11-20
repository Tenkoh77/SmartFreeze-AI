"""Shared data contracts between the Android app and the Python pipeline."""

from __future__ import annotations

import json
from pathlib import Path

ML_ROOT = Path(__file__).resolve().parents[2]
FEATURE_SCHEMA_PATH = ML_ROOT / "data" / "schemas" / "feature_schema.json"

# Columns of the "samples" CSV exported by the Android app (Settings > Export data).
# Optional measurement columns may be empty, meaning "not available on this device",
# which is loaded as NaN. They are never replaced by zero.
RAW_REQUIRED = [
    "session_id",          # str/int, monitoring session identifier
    "timestamp_ms",        # int, wall-clock epoch milliseconds when the sample was taken
    "interval_s",          # int, configured sampling interval for this session
    "mem_total_bytes",     # int
    "mem_avail_bytes",     # int
    "mem_threshold_bytes", # int
    "low_memory",          # 0/1
    "battery_level_pct",   # 0..100
    "is_charging",         # 0/1
]
RAW_OPTIONAL = [
    "activity_label",       # str, manually selected activity (research mode) or empty
    "thermal_status",       # 0..6 or empty
    "thermal_headroom",     # float or empty
    "battery_temp_c",       # float or empty
    "storage_total_bytes",  # int or empty
    "storage_avail_bytes",  # int or empty
    "app_pss_kb",           # SmartFreeze's own memory footprint (overhead measurement only)
    "label_anomaly",        # 0/1 research ground-truth label for controlled abnormal episodes
    "is_simulated",         # 1 if the row was synthetically generated or injected
]
RAW_COLUMNS = RAW_REQUIRED + RAW_OPTIONAL

ACTIVITY_LABELS = [
    "idle", "browsing", "video", "app_switching", "charging",
    "daily_use", "stress_test", "gaming", "unlabelled",
]


def load_feature_schema(path: Path = FEATURE_SCHEMA_PATH) -> dict:
    with open(path, encoding="utf-8") as fh:
        schema = json.load(fh)
    indices = [f["index"] for f in schema["features"]]
    if indices != list(range(len(indices))):
        raise ValueError("feature_schema.json indices must be 0..n-1 in order")
    return schema


def feature_names() -> list[str]:
    return [f["name"] for f in load_feature_schema()["features"]]


FEATURE_NAMES = feature_names()
