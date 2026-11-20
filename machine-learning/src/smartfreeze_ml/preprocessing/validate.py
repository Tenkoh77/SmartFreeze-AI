"""Load and validate raw sample CSVs exported from the SmartFreeze AI Android app.

Validation is deliberately conservative: rows that cannot be trusted are dropped
and *counted*, so the data-cleaning step can be reported in the dissertation.
Missing optional measurements stay NaN; they are never converted to zero.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from ..schema import RAW_COLUMNS, RAW_OPTIONAL, RAW_REQUIRED


@dataclass
class ValidationReport:
    rows_in: int = 0
    rows_out: int = 0
    dropped: dict[str, int] = field(default_factory=dict)
    sessions: int = 0
    missing_fraction: dict[str, float] = field(default_factory=dict)

    def drop(self, reason: str, n: int) -> None:
        if n:
            self.dropped[reason] = self.dropped.get(reason, 0) + int(n)

    def as_dict(self) -> dict:
        return {
            "rows_in": self.rows_in,
            "rows_out": self.rows_out,
            "dropped": self.dropped,
            "sessions": self.sessions,
            "missing_fraction": self.missing_fraction,
        }


def load_samples(paths: list[Path] | Path) -> pd.DataFrame:
    """Read one or more exported CSV files into a single DataFrame."""
    if isinstance(paths, (str, Path)):
        paths = [Path(paths)]
    frames = [pd.read_csv(p, keep_default_na=True, na_values=["", "NA", "null", "NaN"]) for p in paths]
    if not frames:
        raise ValueError("no input files")
    df = pd.concat(frames, ignore_index=True)
    missing = [c for c in RAW_REQUIRED if c not in df.columns]
    if missing:
        raise ValueError(f"input is missing required columns: {missing}")
    for col in RAW_OPTIONAL:
        if col not in df.columns:
            df[col] = np.nan
    return df[RAW_COLUMNS].copy()


def validate_samples(df: pd.DataFrame) -> tuple[pd.DataFrame, ValidationReport]:
    """Return (clean_df, report). Sorted by session then timestamp; duplicates removed."""
    rep = ValidationReport(rows_in=len(df))
    df = df.copy()
    df["session_id"] = df["session_id"].astype(str)
    df["activity_label"] = df["activity_label"].fillna("unlabelled").astype(str)

    numeric = [c for c in RAW_COLUMNS if c not in ("session_id", "activity_label")]
    for col in numeric:
        df[col] = pd.to_numeric(df[col], errors="coerce")

    bad = df[RAW_REQUIRED].isna().any(axis=1)
    rep.drop("missing_required_value", bad.sum())
    df = df[~bad]

    bad = (df["mem_total_bytes"] <= 0) | (df["mem_avail_bytes"] < 0) | (df["mem_avail_bytes"] > df["mem_total_bytes"])
    rep.drop("implausible_memory", bad.sum())
    df = df[~bad]

    bad = ~df["battery_level_pct"].between(0, 100) | ~df["low_memory"].isin([0, 1]) | ~df["is_charging"].isin([0, 1])
    rep.drop("out_of_range_battery_or_flags", bad.sum())
    df = df[~bad]

    bad = df["interval_s"] <= 0
    rep.drop("invalid_interval", bad.sum())
    df = df[~bad]

    # Out-of-range optional values become missing rather than dropping the row.
    df.loc[~df["thermal_status"].between(0, 6), "thermal_status"] = np.nan
    df.loc[(df["thermal_headroom"] < 0) | (df["thermal_headroom"] > 10), "thermal_headroom"] = np.nan
    df.loc[~df["battery_temp_c"].between(-20, 80), "battery_temp_c"] = np.nan
    bad_storage = (df["storage_total_bytes"] <= 0) | (df["storage_avail_bytes"] < 0) | (
        df["storage_avail_bytes"] > df["storage_total_bytes"])
    df.loc[bad_storage, ["storage_total_bytes", "storage_avail_bytes"]] = np.nan

    before = len(df)
    df = df.drop_duplicates(subset=["session_id", "timestamp_ms"], keep="first")
    rep.drop("duplicate_session_timestamp", before - len(df))

    df = df.sort_values(["session_id", "timestamp_ms"], kind="mergesort").reset_index(drop=True)
    rep.rows_out = len(df)
    rep.sessions = df["session_id"].nunique()
    for col in ("thermal_status", "thermal_headroom", "battery_temp_c", "storage_avail_bytes"):
        rep.missing_fraction[col] = float(df[col].isna().mean()) if len(df) else 0.0
    return df, rep
