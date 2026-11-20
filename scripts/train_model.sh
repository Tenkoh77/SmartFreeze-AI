#!/usr/bin/env bash
# Linux/macOS/WSL2 equivalent of train_model.ps1.
#   scripts/train_model.sh --samples 'machine-learning/data/raw/samples_*.csv' --events 'machine-learning/data/raw/slowdown_events_*.csv'
#   scripts/train_model.sh --synthetic-demo --reports reports/synthetic_demo
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/machine-learning"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q -r requirements.txt
.venv/bin/python -m pytest
# Make relative sample/event paths resolve from the repo root.
ARGS=()
for a in "$@"; do
  if [[ "$a" == machine-learning/* ]]; then ARGS+=("$ROOT/$a"); else ARGS+=("$a"); fi
done
PYTHONPATH=src .venv/bin/python -m smartfreeze_ml.pipeline --install "${ARGS[@]}"
