# Test plan and results register

Status legend: ✅ executed and passed (date, environment) · ⏳ written, not yet executed · ❌ failed.
Record **only** results you actually observed. Never fill a row from expectation.

## A. Automated tests executed in the development workspace (27 Oct 2026)

Environment: Linux, Python 3.13.17, scikit-learn 1.9.1, pandas 3.0.5, numpy 2.5.3; Kotlin 2.1.0 on OpenJDK 21. No Android SDK was available there.

| ID | Test | Status |
|---|---|---|
| ML-01..19 | `pytest` (machine-learning/tests): validation drops/keeps rows, missing stays NaN, hand-computed window features, no future leakage, session-disjoint chronological split, time-gap fallback, normal-only training, imputer, FPR threshold, c(n) equals scikit-learn, **exported JSON reproduces scikit-learn exactly (atol 1e-12, including feature subsampling)**, metrics, episode latency, baseline rule, horizon labels look forward only, experiment refuses on insufficient data, end-to-end pipeline | ✅ 19/19 passed |
| KT-01 | `ModelParityTest.featuresMatchPython`: Kotlin `FeatureProcessor` vs Python on 720 samples × 16 features (tolerance 1e-9) | ✅ passed |
| KT-02 | `ModelParityTest.scoresAndClassificationsMatchPython`: 720 scores, max abs difference **3.9 × 10⁻¹⁶**, identical classifications, 0 near-threshold cases | ✅ passed |
| KT-03 | `ModelLoaderTest`: valid bundle hand-scored; rejects wrong format, feature-layout mismatch, threshold out of range, cyclic tree, bad feature index, truncated JSON, non-JSON, excessive nesting | ✅ 11/11 passed |
| KT-04 | `DomainLogicTest`: severity uses more than the score; sustained pressure without a model; alert cooldown and escalation; honest explanation wording; cold start gives no ML result; out-of-order samples rejected | ✅ 6/6 passed |
| KT-06 | `IsolationForestTrainerTest` (checkpoint 2, 6 Nov 2026): Kotlin on-device trainer vs scikit-learn on a shared fixture (ROC-AUC 0.879 vs 0.855, Spearman ≥ 0.94), determinism, subsample/depth limits, missing-feature imputation, leakage-free split, ~1 % validated false-alarm threshold, exact save/load round trip, demo labelling, refusal on too little data, training policy | ✅ 12/12 passed |
| KT-05 | `SchemaContractTest`: Kotlin feature order equals the shared schema; CSV header covers the Python input columns | ✅ 2/2 passed |

KT tests were run with `scripts/verify_kotlin_core.sh` (plain `kotlinc` plus a minimal JUnit stand-in). **Re-run them in Android Studio with real JUnit** (`testDebugUnitTest`) and record that run as well.

## B. Android build and instrumented tests (to execute on your laptop/phone)

| ID | Test | Procedure | Expected | Status |
|---|---|---|---|---|
| B-01 | Gradle sync | Open `android-app` | Sync succeeds | ⏳ |
| B-02 | Unit tests (real JUnit) | `gradlew testDebugUnitTest` | All pass | ⏳ |
| B-03 | `DatabaseTest` | Run androidTest on the S23 Ultra | 3 pass | ⏳ |
| B-04 | `MonitoringEngineTest` | Run androidTest | Pass; a score is produced | ⏳ |
| B-04b | `PersonalTrainingTest` | Run androidTest | Pass; prints on-device training time | ⏳ |
| B-04c | DB migration v1→v2 on a phone that already has data | Install the new build over the old one | App opens; old samples kept | ⏳ |
| B-05 | Release build | `gradlew assembleRelease` | APK produced | ⏳ |
| B-06 | Lint | `gradlew lint` | No errors | ⏳ |

## C. Functional tests on the Samsung Galaxy S23 Ultra

| ID | Feature | Steps | Expected | Status / observation |
|---|---|---|---|---|
| F-01 | Install & launch without root | Install APK, open | Onboarding appears | ⏳ |
| F-02 | Notification permission refused | Begin monitoring → Don't allow | Monitoring runs; alerts in-app only | ⏳ |
| F-03 | Metrics availability | Dashboard after 30 s | Record which tiles show values; headroom available? | ⏳ |
| F-04 | Pause on leaving app | Home button for 60 s, return | Live screen shows a gap; "Paused: app not on screen" while away | ⏳ |
| F-05 | Background session | Settings › Start background session, lock phone 5 min | Notification with Stop; samples continue | ⏳ |
| F-06 | Stop from notification | Tap Stop | Notification gone; session ended | ⏳ |
| F-07 | Interval setting | Set 5 s, new session | Sample spacing ≈ 5 s | ⏳ |
| F-08 | Battery-aware sampling | Below 20 %, unplugged | Interval doubles; "battery saver" shown | ⏳ |
| F-09 | Simulated alert | Research › Inject simulated episode | Alert labelled SIMULATION | ⏳ |
| F-10 | Controlled workload | Research › Memory pressure, 2 min | Available memory decreases on charts | ⏳ |
| F-11 | Alert acknowledge | Alerts › Acknowledge | Badge count decreases | ⏳ |
| F-12 | History filters | History › Today / 7 days / activity | Lists and chart update | ⏳ |
| F-13 | CSV export | Settings › Export CSV | Share sheet with 4 CSV files; Python loads them | ⏳ |
| F-14 | Delete all data | Settings › Delete | Counts go to 0 | ⏳ |
| F-15 | Theme | Settings › Dark / Light | UI switches, text readable | ⏳ |
| F-16 | Rotation / process death | Rotate; force-stop while monitoring | No crash; dangling session closed on restart | ⏳ |
| F-18 | Learning progress | Fresh install, monitor 10 min | Dashboard "Learning your phone" bar increases | ⏳ |
| F-19 | Quick demo baseline | Monitor ≥ 30 min, Research › Train quick demo model | Chip "Demo-quality AI"; record training time from Settings › AI model | ⏳ |
| F-20 | Full personal model | Monitor ≥ 6 h over 3 days | Trains automatically; chip "Personal AI" | ⏳ |
| F-21 | Stress samples excluded | Run a controlled workload during monitoring | Baseline sample count does not include those samples | ⏳ |
| F-22 | Delete personal model | Settings › Delete personal model | Back to "Learning your phone"; data kept | ⏳ |
| F-17 | Model error handling | Replace model with invalid JSON, rebuild | "Model error" chip, baseline mode, no crash | ⏳ |

## D. Performance / overhead (populate with measurements)

| Measure | Method | Result |
|---|---|---|
| App PSS while monitoring (10 s interval) | `scripts/adb_overhead.ps1` (dumpsys meminfo), 30 min | |
| CPU attributable to app | `dumpsys cpuinfo` / `appCpuMs` delta per hour (from exported samples) | |
| Battery: monitoring vs idle | Two 1-hour screen-on sessions at the same brightness, no charging; `adb shell dumpsys batterystats --reset` before each, compare % drop | |
| Database growth | DB file size after 1 h at 5/10/15 s (Settings stored samples × bytes/row) | |
| Inference time | Live screen "Last inference time" (µs), median of 100 | |
| Model load time | Research › Model › Load time | |
| On-device training time | Settings › AI model › Training time (and `PersonalTrainingTest` output) | |

## E. Machine-learning evaluation (populate from `machine-learning/reports/report.md` after training on REAL data)

Compare the general Isolation Forest, the activity-aware Isolation Forest and the fixed and tuned threshold baselines on the same held-out sessions: precision, recall, F1, FPR, false alerts per hour, episode detection rate, median detection latency, per-activity false alarms. Copy the table from the report. Do not use the synthetic demo report.

## F. Compatibility

| Device | Android | RAM | Result |
|---|---|---|---|
| Samsung Galaxy S23 Ultra | | 8/12 GB | ⏳ |
| Second device (preferably ≤ 4 GB RAM; borrow if possible) | | | ⏳ (if not possible, state this limitation) |

## G. Security and privacy

| ID | Check | Method | Status |
|---|---|---|---|
| S-01 | No unnecessary permissions | `aapt dump permissions app-release.apk` → only POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE (+ WorkManager's library permissions) | ⏳ |
| S-02 | No network access | No INTERNET permission in the merged manifest | ⏳ |
| S-03 | No backups of monitoring data | `allowBackup=false` and data extraction rules | ⏳ |
| S-04 | Malformed model rejected | KT-03 | ✅ (JVM) |
| S-05 | Export only on user action | Code review: `CsvExporter` called only from the Settings/History buttons | ⏳ |
| S-06 | Delete works | F-14 | ⏳ |
