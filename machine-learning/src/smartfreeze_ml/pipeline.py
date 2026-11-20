"""End-to-end SmartFreeze AI training pipeline.

Usage (from the machine-learning/ folder):

    # Real data exported from the phone (Settings > Export data):
    python -m smartfreeze_ml.pipeline --samples data/raw/samples_*.csv --events data/raw/slowdown_events_*.csv

    # Software test run on clearly labelled synthetic data (NOT research results):
    python -m smartfreeze_ml.pipeline --synthetic-demo

Outputs:
    models/smartfreeze_model.json      exported forests for the Android app
    models/smartfreeze_model.joblib    the scikit-learn objects (for notebooks)
    reports/report.json, report.md     metrics, tuning log, data-cleaning report
    reports/*.png                      figures
    ../android-app/app/src/main/assets/model/smartfreeze_model.json      (with --install)
    ../android-app/app/src/test/resources/parity/*.json                  (with --install)
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import shutil
import sys
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from .evaluation import ThresholdBaseline, evaluate_alarms
from .evaluation import plots
from .export import export_bundle
from .export.fixtures import write_parity_fixtures
from .export.training_fixture import write_training_fixture
from .features import compute_features
from .preprocessing import load_samples, validate_samples
from .research import run_early_warning_experiment
from .schema import FEATURE_NAMES, ML_ROOT
from .synthetic import generate, generate_slowdown_events
from .training import chronological_split, train_activity_models, train_detector

ANDROID_APP = ML_ROOT.parent / "android-app" / "app"


def _jsonable(o):
    if isinstance(o, dict):
        return {str(k): _jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_jsonable(v) for v in o]
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating, float)):
        return None if not math.isfinite(float(o)) else float(o)
    return o


def run(samples: pd.DataFrame, events: pd.DataFrame | None, *, synthetic: bool, out_dir: Path,
        report_dir: Path, windows=(30, 60, 120), seed: int = 42, target_fpr: float = 0.01,
        threshold_method: str = "fpr", install: bool = False) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    clean, vrep = validate_samples(samples)
    report: dict = {"synthetic_data": synthetic, "validation": vrep.as_dict(), "seed": seed,
                    "target_fpr": target_fpr, "threshold_method": threshold_method}
    if synthetic:
        report["WARNING"] = "Synthetic data: these numbers test the software only and must not be reported as findings."

    # 1. Window-length comparison on validation data.
    window_rows, cache = [], {}
    for w in windows:
        feats = compute_features(clean, w)
        split = chronological_split(feats, gap_seconds=max(120, w))
        det, log = train_detector(feats, split, seed=seed, tune=True, threshold_method=threshold_method,
                                  target_fpr=target_fpr)
        best = max(log, key=lambda e: e.get("val_pr_auc", -e.get("val_normal_p99", 0)))
        window_rows.append({"window_seconds": w, **{k: v for k, v in best.items() if k != "params"},
                            "params": best["params"]})
        cache[w] = (feats, split, det, log)
    if all("val_pr_auc" in r for r in window_rows):
        chosen = max(window_rows, key=lambda r: r["val_pr_auc"])["window_seconds"]
        report["window_selection"] = "highest validation PR-AUC"
    else:
        chosen = 60 if 60 in cache else windows[0]
        report["window_selection"] = "no labelled abnormal validation data: default 60 s window"
    report["window_comparison"] = window_rows
    report["chosen_window_seconds"] = chosen
    feats, split, general, tuning_log = cache[chosen]
    report["tuning_log"] = tuning_log
    report["split_counts"] = split.value_counts().to_dict()
    report["split_sessions"] = {k: sorted(feats.loc[split == k, "session_id"].unique().tolist())
                                for k in ("train", "val", "test")}

    # 2. Activity-aware models (Approach A) with fallback to the general model.
    activity_models, skipped = train_activity_models(feats, split, seed=seed, threshold_method=threshold_method,
                                                     target_fpr=target_fpr)
    report["activity_models"] = {a: {"n_train": d.n_train, "threshold": d.threshold} for a, d in activity_models.items()}
    report["activity_models_skipped"] = skipped

    # 3. Test-set evaluation: IF general vs activity-aware vs fixed-threshold baselines.
    te = (split == "test").to_numpy()
    test = feats[te]
    X_te = test[FEATURE_NAMES].to_numpy(dtype=np.float64)
    s_gen = general.score(X_te)
    p_gen = (s_gen >= general.threshold).astype(int)
    s_act, p_act = s_gen.copy(), p_gen.copy()
    for act, det in activity_models.items():
        m = (test["activity_label"] == act).to_numpy()
        if m.any():
            s_act[m] = det.score(X_te[m])
            p_act[m] = (s_act[m] >= det.threshold).astype(int)
    fixed = ThresholdBaseline()
    tuned = ThresholdBaseline.tuned(feats[(split == "val").to_numpy()], target_fpr)
    report["test"] = {
        "isolation_forest_general": evaluate_alarms(test, p_gen, s_gen),
        "isolation_forest_activity_aware": evaluate_alarms(test, p_act, s_act),
        "threshold_baseline_fixed": {"rule": vars(fixed), **evaluate_alarms(test, fixed.predict(test), fixed.score(test))},
        "threshold_baseline_tuned": {"rule": vars(tuned), **evaluate_alarms(test, tuned.predict(test), tuned.score(test))},
    }

    # 4. Experimental early-slowdown warning.
    report["early_warning"] = run_early_warning_experiment(feats, split, events, seed=seed)

    # 5. Export.
    detectors = {"general": general, **{f"activity:{a}": d for a, d in activity_models.items()}}
    bundle = export_bundle(detectors, out_dir / "smartfreeze_model.json", window_seconds=chosen, synthetic=synthetic,
                           extra_metadata={"train_sessions": report["split_sessions"]["train"],
                                           "target_fpr": target_fpr})
    joblib.dump(detectors, out_dir / "smartfreeze_model.joblib")
    write_parity_fixtures(clean, bundle, out_dir / "parity_fixtures.json")
    if feats["label_anomaly"].fillna(0).astype(int)[(split == "test").to_numpy()].sum() > 0:
        tf = write_training_fixture(feats, split, out_dir / "training_fixture.json", seed=seed)
        report["training_fixture_sklearn_roc_auc"] = tf["sklearn_roc_auc"]
    report["model_version"] = bundle["model_version"]
    report["model_file_bytes"] = (out_dir / "smartfreeze_model.json").stat().st_size

    # 6. Figures.
    y_te = test["label_anomaly"].fillna(0).astype(int).to_numpy()
    plots.score_timeline(test, s_gen, general.threshold, report_dir / "score_timeline.png", synthetic)
    plots.pr_curves(y_te, {"Isolation Forest (general)": s_gen, "Isolation Forest (activity-aware)": s_act,
                           "Threshold baseline (1 - mem ratio)": fixed.score(test)},
                    report_dir / "pr_curves.png", synthetic)
    plots.window_comparison(window_rows, report_dir / "window_comparison.png", synthetic)

    report = _jsonable(report)
    with open(report_dir / "report.json", "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    (report_dir / "report.md").write_text(render_markdown(report), encoding="utf-8")

    if install:
        assets = ANDROID_APP / "src" / "main" / "assets" / "model"
        parity = ANDROID_APP / "src" / "test" / "resources" / "parity"
        assets.mkdir(parents=True, exist_ok=True)
        parity.mkdir(parents=True, exist_ok=True)
        # The app ignores synthetic models at runtime, so only a model trained on REAL recorded data is
        # shipped in the APK (as a "team starter model" used until the phone's personal model exists).
        if not synthetic:
            shutil.copy(out_dir / "smartfreeze_model.json", assets / "smartfreeze_model.json")
        elif (assets / "smartfreeze_model.json").exists():
            (assets / "smartfreeze_model.json").unlink()
        shutil.copy(out_dir / "smartfreeze_model.json", parity / "smartfreeze_model.json")
        shutil.copy(out_dir / "parity_fixtures.json", parity / "parity_fixtures.json")
        if (out_dir / "training_fixture.json").exists():
            shutil.copy(out_dir / "training_fixture.json", parity / "training_fixture.json")
    return report


def _fmt(v, pct=False):
    if v is None:
        return "n/a"
    return f"{v * 100:.1f}%" if pct else (f"{v:.3f}" if isinstance(v, float) else str(v))


def render_markdown(r: dict) -> str:
    lines = ["# SmartFreeze AI - training and evaluation report", ""]
    if r.get("synthetic_data"):
        lines += ["> **SYNTHETIC TEST DATA.** These numbers only show that the pipeline runs end to end.",
                  "> They are not research findings and must not appear in the dissertation results chapter.", ""]
    v = r["validation"]
    lines += [f"Model version: `{r.get('model_version')}`  ", f"Rows in/out: {v['rows_in']} / {v['rows_out']}, "
              f"sessions: {v['sessions']}, dropped: {v['dropped'] or 'none'}  ",
              f"Chosen window: {r['chosen_window_seconds']} s ({r['window_selection']})", "",
              "## Test split comparison", "",
              "| Detector | Precision | Recall | F1 | FPR | PR-AUC | Episode detection | Median latency (s) | False alerts/h |",
              "|---|---|---|---|---|---|---|---|---|"]
    for name, res in r["test"].items():
        p, e = res["point"], res["episode"]
        lines.append(f"| {name} | {_fmt(p['precision'])} | {_fmt(p['recall'])} | {_fmt(p['f1'])} | "
                     f"{_fmt(p['false_positive_rate'])} | {_fmt(p.get('pr_auc'))} | {_fmt(e['detection_rate'])} | "
                     f"{_fmt(e['median_detection_latency_s'])} | {_fmt(e['false_alerts_per_hour'])} |")
    lines += ["", "## Activity-aware models", "", f"Trained: {', '.join(r['activity_models']) or 'none'}  ",
              f"Skipped (fall back to general): {r['activity_models_skipped'] or 'none'}", "",
              "## Early slowdown warning (experimental)", "", f"Status: {r['early_warning'].get('status')}", ""]
    for h, e in r["early_warning"].get("horizons", {}).items():
        lines.append(f"* Horizon {h} s: {e.get('status')}")
        for m, res in e.get("models", {}).items():
            lines.append(f"  * {m}: precision {_fmt(res['precision'])}, recall {_fmt(res['recall'])}, F1 {_fmt(res['f1'])}, "
                         f"PR-AUC {_fmt(res.get('pr_auc'))}, event detection {_fmt(res['event_detection_rate'])}, "
                         f"median lead {_fmt(res['median_lead_time_s'])} s, alerts/h {_fmt(res['alerts_per_hour'])}")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--samples", nargs="*", default=[], help="sample CSV files (globs allowed)")
    ap.add_argument("--events", nargs="*", default=[], help="slowdown event CSV files")
    ap.add_argument("--synthetic-demo", action="store_true", help="use generated SYNTHETIC data (software test only)")
    ap.add_argument("--out", type=Path, default=ML_ROOT / "models")
    ap.add_argument("--reports", type=Path, default=ML_ROOT / "reports")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--target-fpr", type=float, default=0.01)
    ap.add_argument("--threshold-method", choices=["fpr", "f1"], default="fpr")
    ap.add_argument("--install", action="store_true", help="copy model + parity fixtures into the Android project")
    a = ap.parse_args(argv)

    if a.synthetic_demo:
        samples = generate(seed=a.seed)
        events = generate_slowdown_events(samples)
    else:
        files = [p for pat in a.samples for p in sorted(glob.glob(pat))]
        if not files:
            ap.error("no sample files found; pass --samples or --synthetic-demo")
        samples = load_samples([Path(f) for f in files])
        ev_files = [p for pat in a.events for p in sorted(glob.glob(pat))]
        events = pd.concat([pd.read_csv(f) for f in ev_files], ignore_index=True) if ev_files else None
        if events is not None:
            events["session_id"] = events["session_id"].astype(str)
    rep = run(samples, events, synthetic=a.synthetic_demo, out_dir=a.out, report_dir=a.reports, seed=a.seed,
              target_fpr=a.target_fpr, threshold_method=a.threshold_method, install=a.install)
    print((a.reports / "report.md").read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
