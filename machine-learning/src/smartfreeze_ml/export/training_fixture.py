"""Reference data for testing the ON-DEVICE (Kotlin) Isolation Forest trainer against scikit-learn.

The Kotlin trainer grows its own random trees, so it cannot match scikit-learn tree-for-tree.
Instead this fixture lets the Kotlin test check statistical equivalence: both implementations
are trained on the same normal rows, then scored on the same labelled test rows, and their
ranking quality (ROC-AUC) and score agreement (Spearman correlation) are compared.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.metrics import roc_auc_score

from ..schema import FEATURE_NAMES
from ..training.isolation_forest import Imputer


def _round(v: float):
    return None if not math.isfinite(v) else float(f"{v:.7g}")


def write_training_fixture(feats: pd.DataFrame, split: pd.Series, out_path: Path, *, n_train: int = 1200,
                           n_test_normal: int = 700, n_test_abnormal: int = 300, seed: int = 42) -> dict:
    rng = np.random.default_rng(seed)
    lab = feats["label_anomaly"].fillna(0).astype(int).to_numpy()
    X = feats[FEATURE_NAMES].to_numpy(dtype=np.float64)
    tr_idx = np.flatnonzero((split.to_numpy() == "train") & (lab == 0))
    te = split.to_numpy() == "test"
    te_norm = np.flatnonzero(te & (lab == 0))
    te_abn = np.flatnonzero(te & (lab == 1))
    pick = lambda a, k: np.sort(rng.choice(a, size=min(k, len(a)), replace=False)) if len(a) else a  # noqa: E731
    tr_idx, te_norm, te_abn = pick(tr_idx, n_train), pick(te_norm, n_test_normal), pick(te_abn, n_test_abnormal)
    if len(te_abn) == 0:
        raise ValueError("training fixture needs labelled abnormal test rows")

    to_rounded = np.vectorize(lambda v: np.nan if not math.isfinite(v) else float(f"{v:.7g}"))
    X_train = to_rounded(X[tr_idx])
    te_idx = np.concatenate([te_norm, te_abn])
    X_test = to_rounded(X[te_idx])
    y_test = np.concatenate([np.zeros(len(te_norm), int), np.ones(len(te_abn), int)])

    imp = Imputer.fit(X_train)
    model = IsolationForest(n_estimators=100, max_samples=256, random_state=seed, n_jobs=1).fit(imp.transform(X_train))
    scores = -model.score_samples(imp.transform(X_test))
    payload = {
        "description": "Kotlin on-device trainer vs scikit-learn: same training rows, same labelled test rows.",
        "n_estimators": 100, "max_samples": 256,
        "train": [[_round(v) for v in row] for row in X_train],
        "test": [[_round(v) for v in row] for row in X_test],
        "test_labels": y_test.tolist(),
        "sklearn_scores": [float(s) for s in scores],
        "sklearn_roc_auc": float(roc_auc_score(y_test, scores)),
    }
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, separators=(",", ":"))
    return payload
