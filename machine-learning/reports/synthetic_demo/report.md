# SmartFreeze AI - training and evaluation report

> **SYNTHETIC TEST DATA.** These numbers only show that the pipeline runs end to end.
> They are not research findings and must not appear in the dissertation results chapter.

Model version: `if-20261106-1142c7cf`  
Rows in/out: 8640 / 8640, sessions: 24, dropped: none  
Chosen window: 60 s (highest validation PR-AUC)

## Test split comparison

| Detector | Precision | Recall | F1 | FPR | PR-AUC | Episode detection | Median latency (s) | False alerts/h |
|---|---|---|---|---|---|---|---|---|
| isolation_forest_general | 0.683 | 0.560 | 0.615 | 0.007 | 0.598 | 1.000 | 109.998 | 2.888 |
| isolation_forest_activity_aware | 0.650 | 0.780 | 0.709 | 0.012 | 0.868 | 1.000 | 39.998 | 5.776 |
| threshold_baseline_fixed | 0.750 | 0.660 | 0.702 | 0.006 | 0.306 | 1.000 | 84.988 | 4.126 |
| threshold_baseline_tuned | 0.688 | 0.660 | 0.673 | 0.009 | 0.306 | 1.000 | 84.988 | 4.539 |

## Activity-aware models

Trained: app_switching, charging, gaming, idle, video  
Skipped (fall back to general): {'browsing': "no validation rows for 'activity:browsing'"}

## Early slowdown warning (experimental)

Status: completed

* Horizon 30 s: insufficient data: need >= 20 train events and >= 5 test events with positives in train and validation; model NOT trained
* Horizon 60 s: insufficient data: need >= 20 train events and >= 5 test events with positives in train and validation; model NOT trained
