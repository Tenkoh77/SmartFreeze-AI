import json

import numpy as np
import pandas as pd

from smartfreeze_ml.pipeline import run
from smartfreeze_ml.research import label_horizon, run_early_warning_experiment
from smartfreeze_ml.synthetic import generate, generate_slowdown_events


def test_horizon_labels_look_forward_only():
    feats = pd.DataFrame({"session_id": "a", "timestamp_ms": np.arange(0, 120_000, 5000)})
    events = pd.DataFrame({"session_id": ["a"], "timestamp_ms": [60_000]})
    y = label_horizon(feats, events, 30).to_numpy()
    t = feats["timestamp_ms"].to_numpy()
    assert (y[(t >= 30_000) & (t < 60_000)] == 1).all()
    assert (y[t < 30_000] == 0).all()
    assert (y[(t >= 60_000) & (t <= 70_000)] == -1).all()       # during event: excluded
    assert (y[t > 70_000] == 0).all()


def test_experiment_refuses_with_too_few_events(clean_small):
    from smartfreeze_ml.features import compute_features
    from smartfreeze_ml.training import chronological_split
    feats = compute_features(clean_small, 60)
    split = chronological_split(feats)
    events = generate_slowdown_events(clean_small)
    res = run_early_warning_experiment(feats, split, events)
    for h in res["horizons"].values():
        assert h["status"].startswith("insufficient data")
        assert "models" not in h


def test_pipeline_end_to_end(tmp_path):
    raw = generate(n_sessions=12, minutes_per_session=10, seed=5)
    rep = run(raw, generate_slowdown_events(raw), synthetic=True, out_dir=tmp_path / "m",
              report_dir=tmp_path / "r", windows=(30, 60))
    bundle = json.loads((tmp_path / "m" / "smartfreeze_model.json").read_text())
    assert bundle["format"] == "smartfreeze-iforest" and bundle["synthetic_training_data"]
    assert "general" in bundle["models"]
    assert (tmp_path / "r" / "report.md").read_text().count("SYNTHETIC") >= 1
    assert rep["chosen_window_seconds"] in (30, 60)
    fx = json.loads((tmp_path / "m" / "parity_fixtures.json").read_text())
    assert fx["sessions"] and len(fx["sessions"][0]["scores"]) == len(fx["sessions"][0]["samples"])


def test_training_fixture_for_kotlin_trainer(tmp_path):
    from smartfreeze_ml.export.training_fixture import write_training_fixture
    from smartfreeze_ml.features import compute_features
    from smartfreeze_ml.preprocessing import validate_samples
    from smartfreeze_ml.training import chronological_split
    clean, _ = validate_samples(generate(n_sessions=12, minutes_per_session=12, seed=7))
    feats = compute_features(clean, 60)
    split = chronological_split(feats)
    fx = write_training_fixture(feats, split, tmp_path / "t.json", n_train=300, n_test_normal=100, n_test_abnormal=30)
    assert len(fx["train"]) == 300 and len(fx["test"]) == len(fx["test_labels"]) == len(fx["sklearn_scores"])
    assert 0.5 < fx["sklearn_roc_auc"] <= 1.0
    assert all(len(r) == 16 for r in fx["train"])
