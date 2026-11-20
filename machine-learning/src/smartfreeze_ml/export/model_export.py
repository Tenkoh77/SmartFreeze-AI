"""Export fitted Isolation Forests to a compact, dependency-free JSON bundle.

The Android app (Kotlin `IsolationForestModel`) reads this file from
app/src/main/assets/model/smartfreeze_model.json. Format (version 1):

{
  "format": "smartfreeze-iforest", "format_version": 1,
  "model_version": str, "created_utc": str, "sklearn_version": str,
  "synthetic_training_data": bool,          # true -> must be shown as a DEMO model in the app
  "feature_schema_version": int, "feature_names": [str], "window_seconds": int,
  "score_orientation": "anomaly_score = -score_samples; anomalous if anomaly_score >= score_threshold",
  "models": {
    "general": MODEL, "activity:<label>": MODEL, ...
  }
}
MODEL = {
  "n_estimators": int, "max_samples": int,       # max_samples_ actually used for the tree denominator
  "score_threshold": float, "threshold_method": str, "n_train": int,
  "imputation": [float]  (training medians, feature order),
  "baseline": {"q25": [..], "median": [..], "q75": [..]},
  "trees": [ {"left": [int], "right": [int], "feature": [int], "threshold": [float], "n": [int]} ]
}
Tree arrays are scikit-learn's own arrays: node i is a leaf when left[i] == -1. Feature
indices are remapped to GLOBAL feature indices (sklearn stores per-tree feature subsets
when max_features < 1). A sample goes LEFT when float32(x[feature]) <= threshold, which
matches scikit-learn, which casts inputs to float32 inside trees.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import json
from pathlib import Path

import numpy as np
import sklearn

from ..schema import FEATURE_NAMES, load_feature_schema

MODEL_FORMAT = "smartfreeze-iforest"
MODEL_FORMAT_VERSION = 1


def forest_to_dict(detector) -> dict:
    model = detector.model
    trees = []
    for est, feat_subset in zip(model.estimators_, model.estimators_features_):
        t = est.tree_
        subset = np.asarray(feat_subset)
        leaf = t.children_left == -1
        global_feature = np.where(leaf, -1, subset[np.clip(t.feature, 0, None)])
        trees.append({
            "left": t.children_left.astype(int).tolist(),
            "right": t.children_right.astype(int).tolist(),
            "feature": global_feature.astype(int).tolist(),
            "threshold": [float(v) if not lf else 0.0 for v, lf in zip(t.threshold, leaf)],
            "n": t.n_node_samples.astype(int).tolist(),
        })
    return {
        "n_estimators": len(trees),
        "max_samples": int(model.max_samples_),
        "score_threshold": float(detector.threshold),
        "threshold_method": detector.threshold_method,
        "n_train": int(detector.n_train),
        "params": {k: (v if isinstance(v, (int, float, str)) else str(v)) for k, v in detector.params.items()},
        "imputation": [float(v) for v in detector.imputer.medians],
        "baseline": detector.baseline,
        "trees": trees,
    }


def export_bundle(detectors: dict, out_path: Path, *, window_seconds: int, synthetic: bool,
                  extra_metadata: dict | None = None) -> dict:
    """Write the bundle. `detectors` maps "general" / "activity:<label>" to TrainedDetector."""
    if "general" not in detectors:
        raise ValueError("a 'general' model is required (it is the fallback for every activity)")
    models = {name: forest_to_dict(det) for name, det in detectors.items()}
    body = json.dumps(models, sort_keys=True).encode()
    created = _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0)
    bundle = {
        "format": MODEL_FORMAT,
        "format_version": MODEL_FORMAT_VERSION,
        "model_version": f"if-{created:%Y%m%d}-{hashlib.sha256(body).hexdigest()[:8]}",
        "created_utc": created.isoformat(),
        "sklearn_version": sklearn.__version__,
        "synthetic_training_data": bool(synthetic),
        "feature_schema_version": load_feature_schema()["schema_version"],
        "feature_names": FEATURE_NAMES,
        "window_seconds": int(window_seconds),
        "score_orientation": "anomaly_score = -score_samples; anomalous if anomaly_score >= score_threshold",
        "metadata": extra_metadata or {},
        "models": models,
    }
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(bundle, fh, separators=(",", ":"))
    return bundle
