"""Report figures (PNG). All titles carry a SYNTHETIC banner when the data is synthetic."""

from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from sklearn.metrics import precision_recall_curve  # noqa: E402


def _title(text: str, synthetic: bool) -> str:
    return f"[SYNTHETIC TEST DATA - not a research result]\n{text}" if synthetic else text


def score_timeline(frame: pd.DataFrame, scores: np.ndarray, threshold: float, out: Path,
                   synthetic: bool, max_sessions: int = 3) -> None:
    f = frame.assign(score=scores)
    sids = list(dict.fromkeys(f["session_id"]))[:max_sessions]
    fig, axes = plt.subplots(len(sids), 1, figsize=(10, 2.6 * len(sids)), squeeze=False)
    for ax, sid in zip(axes[:, 0], sids):
        g = f[f["session_id"] == sid]
        mins = (g["timestamp_ms"] - g["timestamp_ms"].min()) / 60000
        ax.plot(mins, g["score"], lw=1, color="#2b6cb0", label="anomaly score")
        ax.axhline(threshold, color="#c53030", ls="--", lw=1, label="threshold")
        lab = g["label_anomaly"].fillna(0).to_numpy() == 1
        if lab.any():
            ax.fill_between(mins, 0, 1, where=lab, color="#f6ad55", alpha=0.3, transform=ax.get_xaxis_transform(),
                            label="labelled abnormal episode")
        ax.set_ylabel("score")
        ax.set_title(f"session {sid} ({g['activity_label'].iat[0]})", fontsize=9)
        ax.legend(fontsize=7, loc="upper left")
    axes[-1, 0].set_xlabel("minutes since session start")
    fig.suptitle(_title("Isolation Forest anomaly score on held-out test sessions", synthetic), fontsize=10)
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


def pr_curves(y: np.ndarray, curves: dict[str, np.ndarray], out: Path, synthetic: bool) -> None:
    if y.sum() == 0 or (y == 0).sum() == 0:
        return
    fig, ax = plt.subplots(figsize=(5.5, 4.5))
    for name, s in curves.items():
        p, r, _ = precision_recall_curve(y, s)
        ax.plot(r, p, label=name, lw=1.4)
    ax.set_xlabel("recall")
    ax.set_ylabel("precision")
    ax.set_ylim(0, 1.02)
    ax.legend(fontsize=8)
    ax.set_title(_title("Precision-recall on test split", synthetic), fontsize=9)
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


def window_comparison(rows: list[dict], out: Path, synthetic: bool) -> None:
    if not rows or "val_pr_auc" not in rows[0]:
        return
    fig, ax = plt.subplots(figsize=(5, 3.5))
    ax.bar([str(r["window_seconds"]) + " s" for r in rows], [r["val_pr_auc"] for r in rows], color="#2c7a7b")
    ax.set_ylabel("validation PR-AUC")
    ax.set_title(_title("Rolling window length comparison", synthetic), fontsize=9)
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)
