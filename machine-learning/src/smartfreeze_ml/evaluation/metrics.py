"""Evaluation metrics for anomaly alarms on time-ordered monitoring data.

Point metrics treat every sample independently. Episode metrics treat each
contiguous run of label_anomaly == 1 within a session as one ground-truth event:

  detection rate     = episodes with at least one alarm inside the episode / episodes
  detection latency  = time from episode start to the first alarm inside it (detected episodes only)
  false alerts/hour  = alarm ONSETS (0 -> 1 transitions) that begin on a normal sample,
                       divided by hours of normal monitoring time
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score


def point_metrics(y_true: np.ndarray, y_pred: np.ndarray, scores: np.ndarray | None = None) -> dict:
    y_true = np.asarray(y_true).astype(int)
    y_pred = np.asarray(y_pred).astype(int)
    tp = int(((y_pred == 1) & (y_true == 1)).sum())
    fp = int(((y_pred == 1) & (y_true == 0)).sum())
    fn = int(((y_pred == 0) & (y_true == 1)).sum())
    tn = int(((y_pred == 0) & (y_true == 0)).sum())
    precision = tp / (tp + fp) if tp + fp else float("nan")
    recall = tp / (tp + fn) if tp + fn else float("nan")
    f1 = 2 * precision * recall / (precision + recall) if tp else (0.0 if (tp + fp + fn) else float("nan"))
    out = {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "precision": precision, "recall": recall, "f1": f1,
           "false_positive_rate": fp / (fp + tn) if fp + tn else float("nan")}
    if scores is not None and y_true.sum() > 0 and (y_true == 0).sum() > 0:
        out["pr_auc"] = float(average_precision_score(y_true, scores))
    return out


def episode_metrics(frame: pd.DataFrame, pred_col: str = "pred") -> dict:
    """frame needs session_id, timestamp_ms, label_anomaly, sample_gap_s and the prediction column."""
    n_ep = detected = onsets_on_normal = 0
    latencies = []
    normal_seconds = 0.0
    for _, s in frame.groupby("session_id", sort=False):
        s = s.sort_values("timestamp_ms")
        y = s["label_anomaly"].fillna(0).astype(int).to_numpy()
        p = s[pred_col].astype(int).to_numpy()
        t = s["timestamp_ms"].to_numpy()
        gaps = s["sample_gap_s"].fillna(0).to_numpy()
        normal_seconds += float(gaps[y == 0].sum())
        onset = np.r_[p[0] == 1, (p[1:] == 1) & (p[:-1] == 0)]
        onsets_on_normal += int((onset & (y == 0)).sum())
        i = 0
        while i < len(y):
            if y[i] == 1:
                j = i
                while j + 1 < len(y) and y[j + 1] == 1:
                    j += 1
                n_ep += 1
                hits = np.flatnonzero(p[i:j + 1] == 1)
                if len(hits):
                    detected += 1
                    latencies.append((t[i + hits[0]] - t[i]) / 1000.0)
                i = j + 1
            else:
                i += 1
    hours = normal_seconds / 3600.0
    return {
        "episodes": n_ep,
        "episodes_detected": detected,
        "detection_rate": detected / n_ep if n_ep else float("nan"),
        "median_detection_latency_s": float(np.median(latencies)) if latencies else float("nan"),
        "false_alerts_per_hour": onsets_on_normal / hours if hours > 0 else float("nan"),
        "normal_hours": hours,
    }


def evaluate_alarms(frame: pd.DataFrame, pred: np.ndarray, scores: np.ndarray | None = None) -> dict:
    f = frame.copy()
    f["pred"] = np.asarray(pred).astype(int)
    y = f["label_anomaly"].fillna(0).astype(int).to_numpy()
    res = {"n": len(f), "point": point_metrics(y, f["pred"].to_numpy(), scores), "episode": episode_metrics(f)}
    per_act = {}
    for act, g in f.groupby("activity_label"):
        ya = g["label_anomaly"].fillna(0).astype(int).to_numpy()
        per_act[act] = {"n": len(g), "alarm_rate_on_normal": float(g.loc[ya == 0, "pred"].mean()) if (ya == 0).any() else float("nan"),
                        **{k: v for k, v in point_metrics(ya, g["pred"].to_numpy()).items() if k in ("precision", "recall", "f1")}}
    res["per_activity"] = per_act
    return res
