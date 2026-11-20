import math

import numpy as np
import pandas as pd
import pytest

from smartfreeze_ml.features import compute_features, compute_session_features
from smartfreeze_ml.preprocessing import validate_samples
from smartfreeze_ml.schema import FEATURE_NAMES

GiB = 1024 ** 3


def _row(t, avail=4 * GiB, **kw):
    r = {"session_id": "S", "timestamp_ms": t, "interval_s": 5, "mem_total_bytes": 8 * GiB,
         "mem_avail_bytes": avail, "mem_threshold_bytes": int(0.5 * GiB), "low_memory": 0,
         "battery_level_pct": 50, "is_charging": 0, "activity_label": "idle", "thermal_status": 0,
         "thermal_headroom": np.nan, "battery_temp_c": 30.0, "storage_total_bytes": 100,
         "storage_avail_bytes": 40, "app_pss_kb": np.nan, "label_anomaly": 0, "is_simulated": 0}
    r.update(kw)
    return r


def test_validation_drops_bad_rows_and_duplicates():
    df = pd.DataFrame([_row(0), _row(0), _row(5000, avail=9 * GiB), _row(10000, battery_level_pct=150),
                       _row(15000, mem_total_bytes=np.nan), _row(20000, thermal_status=9)])
    clean, rep = validate_samples(df)
    assert len(clean) == 2
    assert rep.dropped == {"duplicate_session_timestamp": 1, "implausible_memory": 1,
                           "out_of_range_battery_or_flags": 1, "missing_required_value": 1}
    assert math.isnan(clean["thermal_status"].iloc[-1])   # out-of-range optional value -> missing, row kept


def test_missing_values_stay_missing_not_zero():
    df = pd.DataFrame([_row(0, battery_temp_c=np.nan, thermal_status=np.nan)])
    clean, _ = validate_samples(df)
    f = compute_session_features(clean, 60)[0]
    assert math.isnan(f[FEATURE_NAMES.index("battery_temp_c")])
    assert math.isnan(f[FEATURE_NAMES.index("thermal_status")])
    assert math.isnan(f[FEATURE_NAMES.index("thermal_delta_w")])
    assert math.isnan(f[FEATURE_NAMES.index("sample_gap_s")])


def test_window_features_hand_computed():
    rows = [_row(i * 5000, avail=int((0.5 - 0.01 * i) * 8 * GiB), thermal_status=min(i, 2)) for i in range(4)]
    clean, _ = validate_samples(pd.DataFrame(rows))
    f = compute_session_features(clean, 10)                # window holds 2 samples at 5 s spacing
    last = dict(zip(FEATURE_NAMES, f[-1]))
    assert last["mem_ratio_mean_w"] == pytest.approx((0.48 + 0.47) / 2, abs=1e-9)
    assert last["mem_ratio_std_w"] == pytest.approx(0.005, abs=1e-9)
    assert last["mem_ratio_slope_w"] == pytest.approx(-0.01 / (5 / 60), abs=1e-6)   # per minute
    assert last["thermal_delta_w"] == 0.0                   # window thermal [2, 2]
    assert dict(zip(FEATURE_NAMES, f[2]))["thermal_delta_w"] == 1.0
    assert last["completeness_w"] == 1.0
    assert last["sample_gap_s"] == 5.0


def test_features_never_use_future_samples(clean_small):
    sess = clean_small[clean_small["session_id"] == clean_small["session_id"].iloc[0]]
    full = compute_session_features(sess, 60)
    k = len(sess) // 2
    prefix = compute_session_features(sess.iloc[:k], 60)
    np.testing.assert_array_equal(full[:k], prefix)


def test_windows_do_not_cross_sessions(clean_small):
    feats = compute_features(clean_small, 120)
    firsts = feats.groupby("session_id").head(1)
    assert firsts["sample_gap_s"].isna().all()
    assert (firsts["mem_ratio_std_w"] == 0).all()
