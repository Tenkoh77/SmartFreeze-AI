"""Isolation Forest training, threshold selection and activity-aware models.

Score orientation used everywhere in SmartFreeze AI:

    anomaly_score = -IsolationForest.score_samples(x)   in (0, 1]

Higher means more anomalous; about 0.5 is typical of normal data. An observation
is classified ANOMALOUS when anomaly_score >= score_threshold. The threshold
is chosen on validation data (see choose_threshold), NOT taken from
scikit-learn's `contamination` offset. The score is a measure of how easy a
point is to isolate. It is not a probability of the phone freezing.

Isolation Forest is invariant to monotonic per-feature scaling (splits are drawn
uniformly between a node's min and max), so no standardisation is applied.
Only missing-value imputation with training medians is needed.
"""

from __future__ import annotations

import warnings
from dataclasses import dataclass, field
from itertools import product

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.metrics import average_precision_score

from ..schema import FEATURE_NAMES

DEFAULT_PARAMS = {"n_estimators": 100, "max_samples": 256, "max_features": 1.0, "contamination": "auto"}
PARAM_GRID = {"n_estimators": [100, 200], "max_samples": [128, 256], "max_features": [0.75, 1.0]}


@dataclass
class Imputer:
    medians: np.ndarray

    @classmethod
    def fit(cls, X: np.ndarray) -> "Imputer":
        with np.errstate(all="ignore"), warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)   # all-NaN column is expected for unsupported sensors
            med = np.nanmedian(np.where(np.isfinite(X), X, np.nan), axis=0)
        # A feature never observed in training (e.g. headroom unsupported) imputes to 0.0 and
        # is therefore constant, so the forest can never split on it.
        return cls(np.where(np.isnan(med), 0.0, med))

    def transform(self, X: np.ndarray) -> np.ndarray:
        X = np.array(X, dtype=np.float64, copy=True)
        bad = ~np.isfinite(X)
        X[bad] = np.take(self.medians, np.nonzero(bad)[1])
        return X


@dataclass
class TrainedDetector:
    name: str
    model: IsolationForest
    imputer: Imputer
    threshold: float
    threshold_method: str
    params: dict
    n_train: int
    baseline: dict = field(default_factory=dict)   # per-feature median / q25 / q75 of training rows

    def score(self, X: np.ndarray) -> np.ndarray:
        return anomaly_scores(self.model, self.imputer.transform(X))

    def predict(self, X: np.ndarray) -> np.ndarray:
        return (self.score(X) >= self.threshold).astype(int)


def anomaly_scores(model: IsolationForest, X: np.ndarray) -> np.ndarray:
    return -model.score_samples(X)


def choose_threshold(val_scores: np.ndarray, val_labels: np.ndarray | None,
                     method: str = "fpr", target_fpr: float = 0.01) -> float:
    """Pick the decision threshold on VALIDATION data.

    method="fpr": the (1 - target_fpr) quantile of validation scores on normal rows,
                  so roughly target_fpr of normal observations are flagged.
    method="f1":  the threshold maximising F1 on labelled validation data
                  (requires both classes present); falls back to "fpr" otherwise.
    """
    labels = np.zeros(len(val_scores), dtype=int) if val_labels is None else np.nan_to_num(val_labels).astype(int)
    normal = val_scores[labels == 0]
    if len(normal) == 0:
        raise ValueError("validation set contains no normal observations")
    if method == "f1" and labels.sum() > 0:
        best_t, best_f1 = float(np.quantile(normal, 1 - target_fpr)), -1.0
        for t in np.unique(np.quantile(val_scores, np.linspace(0.5, 0.999, 200))):
            pred = val_scores >= t
            tp = int((pred & (labels == 1)).sum())
            fp = int((pred & (labels == 0)).sum())
            fn = int((~pred & (labels == 1)).sum())
            f1 = 2 * tp / (2 * tp + fp + fn) if tp else 0.0
            if f1 > best_f1:
                best_t, best_f1 = float(t), f1
        return best_t
    return float(np.quantile(normal, 1 - target_fpr))


def _baseline_stats(X: np.ndarray) -> dict:
    with np.errstate(all="ignore"):
        q = np.nanquantile(np.where(np.isfinite(X), X, np.nan), [0.25, 0.5, 0.75], axis=0)
    q = np.where(np.isnan(q), 0.0, q)
    return {"q25": q[0].tolist(), "median": q[1].tolist(), "q75": q[2].tolist()}


def _fit(X_train: np.ndarray, params: dict, seed: int) -> IsolationForest:
    p = {**DEFAULT_PARAMS, **params}
    p["max_samples"] = min(p["max_samples"], len(X_train)) if isinstance(p["max_samples"], int) else p["max_samples"]
    return IsolationForest(random_state=seed, n_jobs=1, **p).fit(X_train)


def train_detector(feats: pd.DataFrame, split: pd.Series, name: str = "general", *, seed: int = 42,
                   tune: bool = True, threshold_method: str = "fpr", target_fpr: float = 0.01,
                   min_train_rows: int = 200) -> tuple[TrainedDetector, list[dict]]:
    """Train on NORMAL training rows, tune on validation, return the detector and a tuning log."""
    lab = feats["label_anomaly"].fillna(0).astype(int).to_numpy()
    X_all = feats[FEATURE_NAMES].to_numpy(dtype=np.float64)
    tr = (split.to_numpy() == "train") & (lab == 0)
    va = split.to_numpy() == "val"
    if tr.sum() < min_train_rows:
        raise ValueError(f"only {int(tr.sum())} normal training rows for '{name}' (need {min_train_rows})")
    if va.sum() == 0:
        raise ValueError(f"no validation rows for '{name}'")

    imputer = Imputer.fit(X_all[tr])
    X_tr, X_va, y_va = imputer.transform(X_all[tr]), imputer.transform(X_all[va]), lab[va]

    log: list[dict] = []
    candidates = [dict(zip(PARAM_GRID, v)) for v in product(*PARAM_GRID.values())] if tune else [{}]
    best = None
    for params in candidates:
        model = _fit(X_tr, params, seed)
        s = anomaly_scores(model, X_va)
        entry = {"params": {**DEFAULT_PARAMS, **params}}
        if y_va.sum() > 0 and (y_va == 0).sum() > 0:
            entry["val_pr_auc"] = float(average_precision_score(y_va, s))
            key = entry["val_pr_auc"]
        else:
            # No labelled abnormal validation data: prefer the model with the tightest normal
            # score distribution (smallest 99th-percentile score), a documented heuristic.
            entry["val_normal_p99"] = float(np.quantile(s, 0.99))
            key = -entry["val_normal_p99"]
        log.append(entry)
        if best is None or key > best[0]:
            best = (key, params, model, s)

    _, params, model, s_va = best
    thr = choose_threshold(s_va, y_va, threshold_method, target_fpr)
    det = TrainedDetector(name=name, model=model, imputer=imputer, threshold=thr,
                          threshold_method=f"{threshold_method}(target_fpr={target_fpr})" if threshold_method == "fpr" else threshold_method,
                          params={**DEFAULT_PARAMS, **params}, n_train=int(tr.sum()),
                          baseline=_baseline_stats(X_all[tr]))
    return det, log


def train_activity_models(feats: pd.DataFrame, split: pd.Series, *, min_train_rows: int = 500,
                          seed: int = 42, threshold_method: str = "fpr", target_fpr: float = 0.01
                          ) -> tuple[dict[str, TrainedDetector], dict[str, str]]:
    """Approach A: one model per activity with enough data. Returns (models, skipped_reasons)."""
    models, skipped = {}, {}
    for act in sorted(feats["activity_label"].unique()):
        if act == "unlabelled":
            continue
        m = (feats["activity_label"] == act).to_numpy()
        sub, ssplit = feats[m], split[m]
        try:
            det, _ = train_detector(sub, ssplit, name=f"activity:{act}", seed=seed, tune=False,
                                    threshold_method=threshold_method, target_fpr=target_fpr,
                                    min_train_rows=min_train_rows)
            models[act] = det
        except ValueError as exc:
            skipped[act] = str(exc)
    return models, skipped
