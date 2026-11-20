"""EXPERIMENTAL early-slowdown warning analysis (research component, Module E).

Three concepts are kept separate throughout:
  * anomaly detection    - Isolation Forest, unsupervised, "unusual conditions"
  * slowdown detection   - an independently MEASURED responsiveness event (ground truth)
  * early slowdown warning - a supervised model asked: "will a slowdown event begin
                           within the next H seconds?"

Future events are used ONLY to build training/evaluation labels. Features at time t
come from features/engineering.py, which never sees samples after t.

The experiment refuses to train (and reports why) when the labelled data cannot
support a defensible model. In that case the freeze-risk feature stays EXPERIMENTAL.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score

from ..evaluation.metrics import point_metrics
from ..schema import FEATURE_NAMES
from ..training.isolation_forest import Imputer

SLOWDOWN_DEFINITION = (
    "A slowdown event is recorded when, in the SmartFreeze research responsiveness probe, "
    "(a) a scheduled main-thread probe completes more than 250 ms late, or (b) the research "
    "screen reports a frame with total duration above 250 ms (from Android FrameMetrics), or "
    "(c) the participant presses 'I noticed lag' in research mode. Events closer than 30 s "
    "to a previous event in the same session are merged into that event."
)

MIN_EVENTS = 20          # positives (distinct events) needed in the training split
MIN_EVENTS_TEST = 5      # events needed in the test split to report anything


def merge_events(events: pd.DataFrame, merge_seconds: int = 30) -> pd.DataFrame:
    out = []
    for sid, g in events.sort_values("timestamp_ms").groupby("session_id"):
        last = None
        for t in g["timestamp_ms"]:
            if last is None or t - last > merge_seconds * 1000:
                out.append({"session_id": str(sid), "timestamp_ms": int(t)})
            last = t
    return pd.DataFrame(out, columns=["session_id", "timestamp_ms"])


def label_horizon(feats: pd.DataFrame, events: pd.DataFrame, horizon_s: int) -> pd.Series:
    """y(t) = 1 if an event starts in (t, t + horizon]. Rows where an event is happening
    right now are labelled -1 ("during event") and excluded, because warning about a
    slowdown that is already visible is detection, not early warning."""
    y = pd.Series(0, index=feats.index, dtype=int)
    ev = merge_events(events)
    h = horizon_s * 1000
    for sid, g in feats.groupby("session_id"):
        et = np.sort(ev.loc[ev["session_id"] == str(sid), "timestamp_ms"].to_numpy())
        if len(et) == 0:
            continue
        t = g["timestamp_ms"].to_numpy()
        idx = np.searchsorted(et, t, side="right")            # first event strictly after t
        nxt = np.where(idx < len(et), et[np.minimum(idx, len(et) - 1)], np.iinfo(np.int64).max)
        lab = ((nxt - t) <= h).astype(int)
        has_prev = idx > 0
        prev = et[np.maximum(idx - 1, 0)]
        lab[has_prev & ((t - prev) <= 10_000)] = -1            # within 10 s after an event start
        y[g.index] = lab
    return y


def _event_warning_stats(frame: pd.DataFrame, events: pd.DataFrame, pred: np.ndarray, horizon_s: int) -> dict:
    ev = merge_events(events)
    f = frame.assign(pred=pred)
    warned, leads = 0, []
    n = 0
    for _, e in ev.iterrows():
        g = f[(f["session_id"] == e["session_id"]) & (f["timestamp_ms"] < e["timestamp_ms"])
              & (f["timestamp_ms"] >= e["timestamp_ms"] - horizon_s * 1000)]
        if g.empty:
            continue
        n += 1
        hits = g[g["pred"] == 1]
        if len(hits):
            warned += 1
            leads.append((e["timestamp_ms"] - hits["timestamp_ms"].min()) / 1000.0)
    hours = f["sample_gap_s"].fillna(0).sum() / 3600.0
    onsets = int(((f["pred"].to_numpy()[1:] == 1) & (f["pred"].to_numpy()[:-1] == 0)).sum()) if len(f) > 1 else 0
    return {"events_evaluated": n, "event_detection_rate": warned / n if n else float("nan"),
            "median_lead_time_s": float(np.median(leads)) if leads else float("nan"),
            "alerts_per_hour": onsets / hours if hours else float("nan")}


def run_early_warning_experiment(feats: pd.DataFrame, split: pd.Series, events: pd.DataFrame,
                                 horizons=(30, 60), seed: int = 42) -> dict:
    """Return a results dict per horizon, or a documented refusal when data is insufficient."""
    results = {"definition": SLOWDOWN_DEFINITION, "horizons": {}}
    if events is None or events.empty:
        results["status"] = "not_run: no slowdown events recorded"
        return results
    X_all = feats[FEATURE_NAMES].to_numpy(dtype=np.float64)
    for h in horizons:
        y = label_horizon(feats, events, h).to_numpy()
        keep = y >= 0
        tr = keep & (split.to_numpy() == "train")
        va = keep & (split.to_numpy() == "val")
        te = keep & (split.to_numpy() == "test")
        ev = merge_events(events)
        sess_split = feats.groupby("session_id").first().index.to_series().map(
            lambda s: split[feats["session_id"] == s].mode().iat[0])
        n_ev_train = int(ev["session_id"].map(sess_split).eq("train").sum())
        n_ev_test = int(ev["session_id"].map(sess_split).eq("test").sum())
        entry = {"train_rows": int(tr.sum()), "train_positive_rows": int(y[tr].sum()),
                 "train_events": n_ev_train, "test_events": n_ev_test}
        if n_ev_train < MIN_EVENTS or n_ev_test < MIN_EVENTS_TEST or y[tr].sum() == 0 or y[va].sum() == 0:
            entry["status"] = (f"insufficient data: need >= {MIN_EVENTS} train events and >= {MIN_EVENTS_TEST} "
                               "test events with positives in train and validation; model NOT trained")
            results["horizons"][str(h)] = entry
            continue
        imp = Imputer.fit(X_all[tr])
        Xtr, Xva, Xte = imp.transform(X_all[tr]), imp.transform(X_all[va]), imp.transform(X_all[te])
        models = {
            "logistic_regression": LogisticRegression(max_iter=2000, class_weight="balanced"),
            "random_forest": RandomForestClassifier(n_estimators=200, min_samples_leaf=5, class_weight="balanced",
                                                    random_state=seed, n_jobs=1),
        }
        entry["models"] = {}
        for name, clf in models.items():
            if name == "logistic_regression":
                mu, sd = Xtr.mean(axis=0), Xtr.std(axis=0)
                sd[sd == 0] = 1.0
                fit_tr, fit_va, fit_te = (Xtr - mu) / sd, (Xva - mu) / sd, (Xte - mu) / sd
            else:
                fit_tr, fit_va, fit_te = Xtr, Xva, Xte
            clf.fit(fit_tr, y[tr])
            p_va = clf.predict_proba(fit_va)[:, 1]
            # threshold: maximise F1 on validation
            best_t, best_f = 0.5, -1
            for t in np.unique(np.quantile(p_va, np.linspace(0.5, 0.995, 100))):
                f = point_metrics(y[va], (p_va >= t).astype(int))["f1"]
                if f == f and f > best_f:
                    best_t, best_f = float(t), f
            p_te = clf.predict_proba(fit_te)[:, 1]
            pred = (p_te >= best_t).astype(int)
            m = point_metrics(y[te], pred, p_te)
            m["pr_auc"] = float(average_precision_score(y[te], p_te)) if 0 < y[te].sum() < len(y[te]) else float("nan")
            m.update(_event_warning_stats(feats[te], events, pred, h))
            m["decision_threshold"] = best_t
            entry["models"][name] = m
        entry["status"] = "trained"
        results["horizons"][str(h)] = entry
    results["status"] = "completed"
    return results
