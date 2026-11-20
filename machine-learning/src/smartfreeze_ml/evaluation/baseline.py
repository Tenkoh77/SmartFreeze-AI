"""Conventional fixed-threshold detector used as the comparison baseline.

Rule: alarm when low_memory == 1, OR mem_avail_ratio < mem_ratio_cutoff,
OR thermal_status >= thermal_cutoff. This is the kind of rule an ordinary
monitoring app would use. It is NOT machine learning and the app labels it
"rule-based" wherever it is shown (for example in the cold-start state).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd


@dataclass
class ThresholdBaseline:
    mem_ratio_cutoff: float = 0.15
    thermal_cutoff: int = 3   # THERMAL_STATUS_SEVERE

    def predict(self, feats: pd.DataFrame) -> np.ndarray:
        thermal = feats["thermal_status"].to_numpy(dtype=float)
        alarm = (feats["low_memory"].to_numpy() == 1) | (feats["mem_avail_ratio"].to_numpy() < self.mem_ratio_cutoff)
        alarm |= np.nan_to_num(thermal, nan=-1) >= self.thermal_cutoff
        return alarm.astype(int)

    def score(self, feats: pd.DataFrame) -> np.ndarray:
        """Monotonic 'badness' for PR curves: lower available memory -> higher score."""
        return 1.0 - feats["mem_avail_ratio"].to_numpy(dtype=float)

    @classmethod
    def tuned(cls, val_feats: pd.DataFrame, target_fpr: float = 0.01) -> "ThresholdBaseline":
        """Choose the memory cutoff on validation normals with the same false-alarm target as the model."""
        y = val_feats["label_anomaly"].fillna(0).astype(int).to_numpy()
        normal_ratios = val_feats.loc[y == 0, "mem_avail_ratio"].to_numpy()
        cutoff = float(np.quantile(normal_ratios, target_fpr)) if len(normal_ratios) else 0.15
        return cls(mem_ratio_cutoff=cutoff)
