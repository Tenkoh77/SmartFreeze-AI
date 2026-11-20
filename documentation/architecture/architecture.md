# Software architecture and design decisions

## Layers

| Layer | Package / folder | Responsibility | Android-free? |
|---|---|---|---|
| Presentation | `ui/` (screens, components, theme, navigation, `ViewModels.kt`) | Compose screens, UI state via `StateFlow`, navigation | no |
| Domain | `domain/` (`AnomalyDetector`, `SeverityClassifier`, `AlertPolicy`, `ExplanationEngine`, models) | Detection pipeline, severity, alert suppression, explanations, recommendations | **yes** |
| ML inference | `ml/` (`FeatureProcessor`, `IsolationForestModel`, `FeatureSchema`, `json/MiniJson`) | Feature extraction and Isolation Forest scoring identical to Python | **yes** |
| Data | `data/` (collectors, Room DB, repositories, CSV export, retention worker) | Android measurements, persistence, model loading, export | no |
| Monitoring runtime | `monitoring/` (`MonitoringEngine`, `MonitoringService`, `AlertNotifier`) | Sampling loop, lifecycle rules, notifications | no |
| Research tools | `research/` (`ResponsivenessProbe`, `ControlledWorkload`) | Ground-truth slowdown labels, controlled workloads (own process only) | no |
| ML training | `machine-learning/src/smartfreeze_ml` | Validation, features, training, evaluation, export, slowdown research | (Python) |

The domain and ML packages contain **no Android imports**. `scripts/verify_kotlin_core.sh` enforces this. They are compiled and unit-tested on a plain JVM, so the core logic can be tested without a phone.

## Key design decisions

1. **MVVM with a single monitoring engine.** `MonitoringEngine` lives in the application container, not in a ViewModel. All screens and the optional foreground service observe one `StateFlow`, so there is one source of truth for "is monitoring running".
2. **Manual dependency injection (`AppContainer`) instead of Hilt.** The brief allows Hilt "where justified". With about ten long-lived objects, a single explicit container is easier to explain and adds no generated code. Every class still receives dependencies through its constructor (dependency inversion), for example `MetricsSource`, which makes simulated metrics possible in `MonitoringEngineTest`.
3. **Personal model trained ON THE PHONE (added in checkpoint 2).** Each phone learns its own normal baseline: after ≥ 6 h of monitoring over ≥ 3 days (≥ 2,000 samples), `PersonalModelTrainer` recomputes features from the stored samples, trains an Isolation Forest in Kotlin (`IsolationForestTrainer`, scikit-learn's algorithm), picks the threshold on a chronologically later validation split, saves `files/model/personal_model.json` in the same format as the offline model, and the engine switches to it immediately. Training is checked after every session and daily (WorkManager), and the model is retrained weekly when ≥ 500 new samples exist. Samples from simulations and controlled workloads (`excludeFromTraining`, DB v2) are never used as "normal" data. A research-only *quick demo baseline* (≥ 30 min) produces a model labelled demo-quality. Users never export anything; the Python pipeline is used for research evaluation and, optionally, a *team starter model* trained on real data.
3b. **Offline Isolation Forest exported as JSON trees and re-implemented in Kotlin.** No Python runtime or TensorFlow Lite on the phone. scikit-learn's own tree arrays are exported. Kotlin repeats the traversal, the `c(n)` leaf correction and the float32 comparison that scikit-learn uses. **Verified:** max |Kotlin − Python| = 3.9 × 10⁻¹⁶ over 720 scores, identical classifications (`ModelParityTest`).
4. **One feature definition, two implementations, tested against each other.** `feature_schema.json` is the contract. Python (`features/engineering.py`) and Kotlin (`FeatureProcessor`) use the same per-sample loop, and `parity_fixtures.json` checks them against each other (tolerance 1e-9, passed).
5. **Score semantics.** `anomaly_score = −score_samples(x)` in (0, 1]; anomalous if `score ≥ threshold`. The threshold is chosen on validation data (default: the 99th percentile of normal validation scores), not taken from `contamination`. The score is never presented as a freeze probability.
6. **Missing data stays missing.** Nullable Room columns plus an availability bit mask. NaN features are imputed only inside the model, with training medians exported alongside it.
7. **Cold start is explicit.** Without a model (`ModelStatus.NotAvailable`), the detector runs a clearly labelled rule-based check (`DetectionSource.RULE_BASED_COLD_START`). The UI shows "Baseline collection" and "not machine learning".
8. **Severity uses more than the score.** Points come from the margin above the threshold, persistence, sustained pressure, and the Android low-memory or severe-thermal flags.
9. **Three separate concepts.** Anomaly (Isolation Forest), slowdown (measured by the research probe), and early-warning prediction (a separate supervised experiment, trained only if the data supports it). `AlertCategory.ELEVATED_SLOWDOWN_RISK` exists but is never produced unless a validated model is installed.
10. **Lifecycle-respecting monitoring.** By default sampling pauses when the app is not visible. Background sampling happens only in an explicitly started, time-limited foreground service.
11. **Privacy by design.** No `INTERNET` permission, backups excluded, export only through the system share sheet, delete-all in Settings.

## Runtime flow (one sampling tick)

See `diagrams/sequence-monitoring-tick.mmd`. Briefly: `MetricsSource.read()` → `MonitoringRepository.insertSample()` (unique `(sessionId, timestampMs)` prevents duplicates) → `AnomalyDetector.process()` (FeatureProcessor → Isolation Forest → SeverityClassifier → ExplanationEngine) → `saveDetection()` → `AlertPolicy.decide()` → alert row plus optional notification → `StateFlow` update → Compose recomposes.

## Model selection at runtime (activity-aware, Approach A)

`ModelBundle.forActivity(label)` returns the activity-specific forest when one was trained with enough data (≥ 500 normal training rows by default). Otherwise it returns the general forest. The UI shows which model was used.
