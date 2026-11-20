# Development checkpoint 2 (6 November 2026): on-device personal training

**What changed:** every phone now trains its OWN Isolation Forest on the device. Users never export a CSV.

| Item | Status |
|---|---|
| `IsolationForestTrainer` (Kotlin, scikit-learn's algorithm), chronological validation split, 1 % threshold | ✅ compiled and tested on the JVM |
| Equivalence with scikit-learn (shared fixture): ROC-AUC 0.879 (Kotlin, mean of 5 seeds) vs 0.855 (sklearn); Spearman ≥ 0.94 | ✅ (synthetic fixture data) |
| `ModelBundleWriter` save/load round trip (exact), `PersonalModelBuilder` (general + activity models, demo label) | ✅ tested |
| `TrainingPolicy`: 6 h / 2,000 samples / 3 days; 30-min demo; weekly retrain with ≥ 500 new samples | ✅ tested |
| DB v2 migration (`excludeFromTraining`), training queries, `PersonalModelTrainer`, daily `ModelTrainingWorker`, model hot-swap, UI (learning progress, AI model settings, quick demo baseline) | ✍️ written, ⏳ **not compiled here**; build in Android Studio |
| `PersonalTrainingTest` (instrumented, measures training time on the phone) | ⏳ run on the S23 Ultra |
| Synthetic model removed from the APK (kept only as a test fixture) | ✅ |

Test totals in the workspace: **Python 20/20, Kotlin core 34/34.**

After installing this version over the old one, the database migrates from v1 to v2 automatically and existing samples are kept and count towards the baseline.

---

# Development checkpoint 1 (27 October 2026)

## How this was built and what was verified

The development workspace had Python, a Kotlin compiler and Java, but **no Android SDK and no access to Google's Maven repository**. So:

* **Python ML pipeline:** built and **run**; 19/19 pytest tests pass.
* **Pure-Kotlin core** (Isolation Forest inference, feature processor, model loader, severity, alert policy, explanations): **compiled with kotlinc 2.1.0 and run**; 22/22 tests pass, including Python/Kotlin parity (max score difference 3.9 × 10⁻¹⁶ on 720 samples, identical classifications).
* **Android-specific code** (Gradle build, Room, collectors, engine, service, Compose UI): **written but NOT compiled or run.** It must be built in Android Studio. Expect possibly a few compile fixes on the first sync; report any errors and they can be corrected.

## Progress checklist

| Phase | Item | Status |
|---|---|---|
| 1 | Telemetry feasibility, permission/API matrix | ✅ `documentation/architecture/android-compatibility-matrix.md` (device column to verify on S23) |
| 1 | Architecture, design decisions, DB schema, feature schema | ✅ docs + `feature_schema.json` |
| 1 | Analyse BUSE proposal | ❌ **Proposal not received.** Only the master prompt was attached. Send the proposal to align objectives and chapter structure. |
| 2 | Gradle project, manifest, theme, navigation, 9 screens, ViewModels, Room (10 tables) | ✍️ written, ⏳ unverified build |
| 3 | Metrics collection, sessions, persistence, lifecycle rules, battery-aware sampling, duplicate prevention | ✍️ written, ⏳ unverified on device |
| 4 | Dataset schema, validation, leakage-free features (30/60/120 s), chronological split, IF training + grid search, threshold tuning, threshold baselines, evaluation, plots, JSON export | ✅ verified (synthetic data only) |
| 5 | Kotlin inference, parity test, cold-start mode, model status, anomaly storage | ✅ core verified on JVM; ⏳ Android integration unverified |
| 6 | Activity-aware models (Approach A + fallback), explanations, recommendations, severity, alert policy, notifications | ✅ logic verified; ⏳ notifications unverified |
| 7 | Responsiveness probe, frame metrics, tap test, manual lag, controlled workloads, exit reasons, slowdown labelling, early-warning experiment with data-sufficiency gate | ✍️ Android parts unverified; ✅ Python research code tested |
| 8 | History/charts, settings, CSV export, retention, theme | ✍️ written, ⏳ unverified |
| 9 | Unit tests (Python + Kotlin core) | ✅ run · Android unit/instrumented tests ⏳ run in Android Studio |
| 10 | README, setup guide, test plan, model card, DB docs, 8 diagrams, protocol, consent template, scripts | ✅ written |
| 10 | Dissertation chapters 1–5, SRS, SDD, user manual, demonstration guide | ⏳ next checkpoint (needs the proposal) |
| 10 | APK | ❌ not built here (no Android SDK). Build with `gradlew assembleRelease`. |

## Research results

**None yet.** All numbers in `machine-learning/reports/synthetic_demo/` come from synthetic data and only show that the pipeline runs. Real results require the data collection in `research/protocols/data-collection-protocol.md`.

## Exact next steps for you

1. Open `android-app` in Android Studio, sync, run `testDebugUnitTest`, then run on the S23 Ultra (`documentation/installation/windows-setup.md`). Send me any build errors.
2. Work through test plan section C (functional checks) and fill in the device column of the compatibility matrix.
3. Start collecting real sessions following the protocol, then export and retrain.
4. Send the BUSE proposal so the dissertation chapters, SRS and SDD can follow its structure.

## Next development checkpoint (planned)

* Fix any compile issues you report; add Compose UI tests.
* SRS, SDD, user manual, demonstration script (Scenarios A–H), dissertation chapters 1–3 aligned with the proposal, Chapter 2 with verifiable references.
* Optional `UsageStatsManager` context (with consent) if your supervisor wants automatic activity context.
