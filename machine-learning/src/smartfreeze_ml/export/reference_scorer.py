"""Pure-Python scorer that reads ONLY the exported JSON bundle.

It is a line-by-line twin of the Kotlin `IsolationForestModel`. Tests check:
  1. ReferenceForest == scikit-learn score_samples (proves the export is faithful)
  2. Kotlin == fixture values produced here (proves the Kotlin port is faithful)
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

EULER_GAMMA = 0.5772156649015329


def average_path_length(n: int) -> float:
    """c(n): expected path length of an unsuccessful BST search (Liu et al., 2008), as in sklearn."""
    if n <= 1:
        return 0.0
    if n == 2:
        return 1.0
    return 2.0 * (math.log(n - 1.0) + EULER_GAMMA) - 2.0 * (n - 1.0) / n


def load_bundle(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


class ReferenceForest:
    def __init__(self, model: dict):
        self.trees = model["trees"]
        self.max_samples = int(model["max_samples"])
        self.threshold = float(model["score_threshold"])
        self.imputation = [float(v) for v in model["imputation"]]
        self.denominator = len(self.trees) * average_path_length(self.max_samples)

    def impute(self, x: list[float]) -> list[float]:
        return [self.imputation[i] if (v is None or not math.isfinite(v)) else float(v) for i, v in enumerate(x)]

    def path_length(self, tree: dict, x: list[float]) -> float:
        node, depth = 0, 0
        left, right, feat, thr = tree["left"], tree["right"], tree["feature"], tree["threshold"]
        while left[node] != -1:
            xv = float(np.float32(x[feat[node]]))      # sklearn trees compare float32 inputs
            node = left[node] if xv <= thr[node] else right[node]
            depth += 1
        return depth + average_path_length(tree["n"][node])

    def score(self, x: list[float]) -> float:
        x = self.impute(x)
        total = sum(self.path_length(t, x) for t in self.trees)
        return 2.0 ** (-total / self.denominator)

    def score_many(self, X: np.ndarray) -> np.ndarray:
        return np.array([self.score(list(row)) for row in np.asarray(X, dtype=np.float64)])
