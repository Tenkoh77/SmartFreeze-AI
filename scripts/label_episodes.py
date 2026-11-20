#!/usr/bin/env python3
"""Fill label_anomaly in exported sample CSVs from the researcher's episode log.

Usage:
    python scripts/label_episodes.py --samples machine-learning/data/raw/samples_X.csv \
        --episodes research/experiments/episode_log.csv --tz Africa/Harare \
        --out machine-learning/data/raw/samples_X_labelled.csv

Episode log columns: episode_id, session_id, start_iso, end_iso, episode_type, notes
(start_iso / end_iso in local time, e.g. 2026-11-02T14:05:00). Rows of a listed session that
fall inside [start, end] get label_anomaly = 1; every other row gets 0.
"""

from __future__ import annotations

import argparse
import sys

import pandas as pd


def label(samples: pd.DataFrame, episodes: pd.DataFrame, tz: str) -> pd.DataFrame:
    out = samples.copy()
    out["label_anomaly"] = 0
    out["session_id"] = out["session_id"].astype(str)
    for _, e in episodes.iterrows():
        start = pd.Timestamp(e["start_iso"], tz=tz).value // 1_000_000
        end = pd.Timestamp(e["end_iso"], tz=tz).value // 1_000_000
        if end <= start:
            raise ValueError(f"episode {e['episode_id']}: end is not after start")
        m = (out["session_id"] == str(e["session_id"])) & out["timestamp_ms"].between(start, end)
        if not m.any():
            print(f"warning: episode {e['episode_id']} matched no samples", file=sys.stderr)
        out.loc[m, "label_anomaly"] = 1
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--samples", required=True)
    ap.add_argument("--episodes", required=True)
    ap.add_argument("--tz", default="Africa/Harare", help="time zone of the times in the episode log")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    res = label(pd.read_csv(a.samples), pd.read_csv(a.episodes), a.tz)
    res.to_csv(a.out, index=False)
    print(f"{int(res['label_anomaly'].sum())} of {len(res)} rows labelled abnormal -> {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
