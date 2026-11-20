"""Chronological train/validation/test split without temporal leakage.

Preferred strategy: whole monitoring sessions are assigned to one split, in
order of session start time. Neighbouring (overlapping) windows of a session
therefore never appear in two different splits.

Fallback (fewer than 5 sessions): each session is cut by time, and a gap of
`gap_seconds` (at least the feature window) is discarded at each cut so that no
window in a later split contains samples from an earlier split.
"""

from __future__ import annotations

import numpy as np
import pandas as pd


def chronological_split(feats: pd.DataFrame, train: float = 0.6, val: float = 0.2,
                        gap_seconds: int = 120, min_sessions: int = 5) -> pd.Series:
    """Return a Series of 'train' / 'val' / 'test' / 'gap' aligned with feats.index."""
    if not 0 < train < 1 or not 0 < val < 1 or train + val >= 1:
        raise ValueError("fractions must satisfy 0 < train, val and train + val < 1")
    split = pd.Series("test", index=feats.index, dtype=object)
    starts = feats.groupby("session_id")["timestamp_ms"].min().sort_values(kind="mergesort")
    sessions = list(starts.index)

    if len(sessions) >= min_sessions:
        n = len(sessions)
        n_train = max(1, int(round(n * train)))
        n_val = max(1, int(round(n * val)))
        if n_train + n_val >= n:
            n_train, n_val = n - 2, 1
        train_s = set(sessions[:n_train])
        val_s = set(sessions[n_train:n_train + n_val])
        split[feats["session_id"].isin(train_s)] = "train"
        split[feats["session_id"].isin(val_s)] = "val"
        return split

    gap_ms = gap_seconds * 1000
    for sid in sessions:
        m = feats["session_id"] == sid
        ts = feats.loc[m, "timestamp_ms"].to_numpy()
        t0, t1 = ts.min(), ts.max()
        c1 = t0 + (t1 - t0) * train
        c2 = t0 + (t1 - t0) * (train + val)
        lab = np.where(ts <= c1, "train", np.where(ts <= c2, "val", "test")).astype(object)
        lab[(ts > c1) & (ts <= c1 + gap_ms)] = "gap"
        lab[(ts > c2) & (ts <= c2 + gap_ms)] = "gap"
        split[m] = lab
    return split
