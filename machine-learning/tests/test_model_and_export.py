import json

import numpy as np
import pandas as pd
import pytest

from smartfreeze_ml.evaluation import ThresholdBaseline, episode_metrics, point_metrics
from smartfreeze_ml.export import ReferenceForest, export_bundle
from smartfreeze_ml.export.reference_scorer import average_path_length
from smartfreeze_ml.features import compute_features
from smartfreeze_ml.schema import FEATURE_NAMES
from smartfreeze_ml.training import Imputer, chronological_split, choose_threshold, train_detector


@pytest.fixture(scope="module")
def trained(clean_small):
    feats = compute_features(clean_small, 60)
    split = chronological_split(feats)
    det, log = train_detector(feats, split, tune=False)
    return feats, split, det


def test_split_is_chronological_and_session_disjoint(trained):
    feats, split, _ = trained
    sets = {k: set(feats.loc[split == k, "session_id"]) for k in ("train", "val", "test")}
    assert not (sets["train"] & sets["val"]) and not (sets["train"] & sets["test"]) and not (sets["val"] & sets["test"])
    last_train = feats.loc[split == "train", "timestamp_ms"].max()
    assert feats.loc[split == "val", "timestamp_ms"].min() > last_train
    assert feats.loc[split == "test", "timestamp_ms"].min() > feats.loc[split == "val", "timestamp_ms"].max()


def test_time_split_fallback_leaves_gap():
    t = np.arange(0, 600_000, 5000)
    feats = pd.DataFrame({"session_id": "only", "timestamp_ms": t})
    s = chronological_split(feats, gap_seconds=60)
    assert (s == "gap").sum() == 24       # 2 cuts x 60 s / 5 s
    assert list(dict.fromkeys(s[s != "gap"])) == ["train", "val", "test"]


def test_training_uses_only_normal_rows(trained):
    feats, split, det = trained
    expected = int(((split == "train") & (feats["label_anomaly"].fillna(0) == 0)).sum())
    assert det.n_train == expected


def test_imputer_uses_training_medians():
    X = np.array([[1.0, np.nan], [3.0, 4.0], [5.0, np.nan]])
    imp = Imputer.fit(X)
    np.testing.assert_allclose(imp.transform(np.array([[np.nan, np.nan]])), [[3.0, 4.0]])
    assert Imputer.fit(np.array([[np.nan], [np.nan]])).medians[0] == 0.0


def test_threshold_fpr_quantile():
    scores = np.linspace(0, 1, 1001)
    assert choose_threshold(scores, None, "fpr", 0.01) == pytest.approx(0.99)


def test_average_path_length_matches_sklearn():
    from sklearn.ensemble._iforest import _average_path_length
    for n in [0, 1, 2, 3, 10, 256]:
        assert average_path_length(n) == pytest.approx(float(_average_path_length(np.array([n]))[0]), abs=1e-12)


def test_exported_json_reproduces_sklearn_exactly(trained, tmp_path):
    feats, split, det = trained
    bundle = export_bundle({"general": det}, tmp_path / "m.json", window_seconds=60, synthetic=True)
    reloaded = json.loads((tmp_path / "m.json").read_text())
    ref = ReferenceForest(reloaded["models"]["general"])
    X = feats[FEATURE_NAMES].to_numpy(dtype=np.float64)[:400]
    np.testing.assert_allclose(ref.score_many(X), det.score(X), rtol=0, atol=1e-12)
    assert bundle["synthetic_training_data"] is True
    assert reloaded["feature_names"] == FEATURE_NAMES


def test_export_with_feature_subsampling(trained, tmp_path):
    feats, split, _ = trained
    from smartfreeze_ml.training import train_detector as td
    import smartfreeze_ml.training.isolation_forest as iso
    old = iso.DEFAULT_PARAMS
    iso.DEFAULT_PARAMS = {**old, "max_features": 0.5}
    try:
        det, _ = td(feats, split, tune=False)
    finally:
        iso.DEFAULT_PARAMS = old
    export_bundle({"general": det}, tmp_path / "m.json", window_seconds=60, synthetic=True)
    ref = ReferenceForest(json.loads((tmp_path / "m.json").read_text())["models"]["general"])
    X = feats[FEATURE_NAMES].to_numpy(dtype=np.float64)[:200]
    np.testing.assert_allclose(ref.score_many(X), det.score(X), rtol=0, atol=1e-12)


def test_injected_episodes_score_higher_than_normal(trained):
    feats, split, det = trained
    te = feats[split == "test"]
    s = det.score(te[FEATURE_NAMES].to_numpy())
    y = te["label_anomaly"].fillna(0).to_numpy() == 1
    if y.any():
        assert s[y].mean() > s[~y].mean()


def test_metrics_and_episodes():
    m = point_metrics(np.array([1, 1, 0, 0]), np.array([1, 0, 1, 0]))
    assert (m["precision"], m["recall"], m["f1"], m["false_positive_rate"]) == (0.5, 0.5, 0.5, 0.5)
    f = pd.DataFrame({"session_id": "a", "timestamp_ms": np.arange(8) * 5000,
                      "label_anomaly": [0, 0, 1, 1, 1, 0, 0, 0], "sample_gap_s": [np.nan] + [5.0] * 7,
                      "pred": [0, 1, 0, 0, 1, 0, 1, 1]})
    e = episode_metrics(f)
    assert e["episodes"] == 1 and e["detection_rate"] == 1.0
    assert e["median_detection_latency_s"] == 10.0
    assert e["false_alerts_per_hour"] == pytest.approx(2 / (20 / 3600))   # two onsets on 20 s of normal time


def test_threshold_baseline_rule():
    f = pd.DataFrame({"low_memory": [0, 1, 0, 0], "mem_avail_ratio": [0.5, 0.5, 0.1, 0.5],
                      "thermal_status": [0, 0, 0, np.nan]})
    assert ThresholdBaseline().predict(f).tolist() == [0, 1, 1, 0]
