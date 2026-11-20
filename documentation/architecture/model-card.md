# Model card: SmartFreeze AI anomaly detector

| Item | Value |
|---|---|
| Model type | Isolation Forest (Liu, Ting & Zhou, 2008), scikit-learn implementation, unsupervised |
| Purpose | Flag device operating conditions that differ from the device's learned normal pattern |
| **Not** intended for | Predicting the probability that the phone will freeze; diagnosing causes |
| Inputs | 16 features (`machine-learning/data/schemas/feature_schema.json`): memory, thermal, battery, storage, rolling window statistics |
| Output | `anomaly_score` in (0, 1], higher means more unusual; anomalous if ≥ the validated threshold |
| Training data | Normal-labelled rows of the chronological training sessions only |
| Threshold | Chosen on validation data: (1 − target FPR) quantile of normal validation scores (default target 1 %), or the F1-optimal threshold when labelled abnormal validation data exists |
| Hyperparameters searched | `n_estimators` {100, 200}, `max_samples` {128, 256}, `max_features` {0.75, 1.0}; `random_state` = 42 |
| Window length | 30, 60 and 120 s compared on validation data; the best by PR-AUC is chosen (60 s default) |
| Activity awareness | Separate forests per activity with ≥ 500 normal training rows; general model fallback |
| On-device implementation | Kotlin re-implementation reading exported tree arrays; parity verified against scikit-learn |
| Explanations | Robust z-scores against training quartiles. **Descriptive, not causal, and not model attribution** |

## Personal (on-device) model: the model users actually get

| Item | Value |
|---|---|
| Where trained | On the user's phone, by `IsolationForestTrainer` (Kotlin), no data leaves the phone |
| When | Baseline complete (≥ 6 h monitored, ≥ 2,000 samples, ≥ 3 days); retrained every 7 days if ≥ 500 new samples |
| Training data | The phone's own samples from the last 30 days (max 20,000), excluding simulated and controlled-workload samples |
| Algorithm | scikit-learn's Isolation Forest procedure: 100 trees, subsample min(256, n) without replacement, depth limit ceil(log2 n), uniform random split between node min/max, float32 inputs |
| Threshold | 99th percentile of scores on a chronologically later validation split (whole sessions when ≥ 5 sessions, else a time cut with a 60 s gap) |
| Equivalence with scikit-learn | Not tree-for-tree (different random generators). Checked statistically on a shared fixture: sklearn ROC-AUC 0.855; Kotlin 0.861–0.891 over 5 seeds (mean 0.879); Spearman rank correlation of scores with sklearn ≥ 0.94 (synthetic test data) |
| Demo-quality variant | Research mode "Quick demo baseline" (≥ 30 min), labelled DEMO-QUALITY in the app, replaced automatically by the full personal model |

## Shipped model in the APK

The synthetic demo model is **no longer shipped**; the app ignores any synthetic model at runtime. If the team trains on real recorded data with `scripts/train_model.ps1`, that model is copied into the APK as a *team starter model*, used only until the phone's personal model exists.

## The synthetic test model (checkpoint 1)

The model trained on **SYNTHETIC data** has been removed from the APK. It remains only as a test fixture (`app/src/test/resources/parity/`) for the Python/Kotlin parity tests. **Its metrics are not research results.**

## Evaluation (to be completed with real data)

| Metric | Isolation Forest (general) | IF (activity-aware) | Threshold baseline (fixed) | Threshold baseline (tuned) |
|---|---|---|---|---|
| Precision | | | | |
| Recall | | | | |
| F1 | | | | |
| False-positive rate | | | | |
| False alerts per hour | | | | |
| Episode detection rate | | | | |
| Median detection latency (s) | | | | |

Fill this from `machine-learning/reports/report.md` after training on recorded data.

## Known limitations

* Only system-level signals that are accessible without privileges (see the compatibility matrix). No CPU load and no per-app data.
* Ground-truth "abnormal" labels come from controlled episodes run by the researcher. Natural anomalies may look different.
* A single device at first; transfer to other devices is untested.
* A reading that is extreme in only a few features while exactly typical in all the others may NOT be isolated early (observed identically in scikit-learn: a 2-of-16-feature outlier scored 0.455 vs a normal 99th percentile of 0.567). This is a known Isolation Forest weakness; the rule-based hard signals in severity classification partly compensate.
* A personal model learns whatever the phone did during the baseline; if the phone was already unhealthy then, that becomes "normal". Retraining and the rule-based hard signals mitigate this.
* Thermal headroom may be unavailable on some devices, in which case the feature is constant (imputed).
