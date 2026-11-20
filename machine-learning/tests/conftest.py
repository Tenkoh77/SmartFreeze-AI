import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from smartfreeze_ml.synthetic import generate  # noqa: E402
from smartfreeze_ml.preprocessing import validate_samples  # noqa: E402


@pytest.fixture(scope="session")
def raw_small():
    return generate(n_sessions=12, minutes_per_session=12, seed=3)


@pytest.fixture(scope="session")
def clean_small(raw_small):
    clean, _ = validate_samples(raw_small)
    return clean
