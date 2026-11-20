# Database documentation (Room, `smartfreeze.db`, schema version 1)

| # | Table | Purpose | Key columns / constraints |
|---|---|---|---|
| 1 | `device_info` | Device model, SDK, RAM, headroom support | single row `id = 1` |
| 2 | `sessions` | Monitoring sessions | `id`, `startedAtMs`, `endedAtMs` (null while running), `intervalS`, `mode` (foreground/background), `isResearch` |
| 3 | `samples` | One row per measurement | FK `sessionId` → sessions (CASCADE); **UNIQUE (`sessionId`, `timestampMs`)** prevents duplicates; nullable columns for unavailable metrics; `availability` bit mask; `isSimulated` |
| 4 | `feature_windows` | Feature vector used for each detection | FK `sampleId` → samples (CASCADE), unique; `featuresCsv` (NaN = empty field) |
| 5 | `activity_labels` | Labelled activity periods (research) | FK `sessionId` (CASCADE); `startMs`, `endMs` |
| 6 | `anomalies` | Non-normal detection results | FK `sessionId` (CASCADE); `source` (ISOLATION_FOREST / RULE_BASED_COLD_START), `score`, `threshold`, `category`, `severity`, `explanation`, `deviations`, `isSimulated` |
| 7 | `slowdown_events` | Ground-truth responsiveness events | `eventType` (latency_probe / frame / tap_latency / manual), `latencyMs`, `source` |
| 8 | `alerts` | Alert history | `category`, `severity`, `message`, `affectedIndicators`, `recommendation`, `isPrediction`, `acknowledged`, `notified`, `isSimulated` |
| 9 | `model_metadata` | Each loaded model version | `modelVersion` PK, `syntheticTrainingData`, `windowSeconds`, `generalThreshold` |
| 10 | `settings` | User settings | single row `id = 1`; values are clamped in `SettingsRepository` |

**Availability bit mask** (`samples.availability`): 1 thermal status, 2 thermal headroom, 4 battery temperature, 8 storage, 16 app self-stats, 32 battery level.

**Retention:** `RetentionWorker` (WorkManager, daily, battery-not-low) deletes samples, ended sessions, alerts and events older than the configured number of days. Settings has *Clean up now* and *Delete all monitoring data*.

**Migrations:** schemas are exported to `android-app/app/schemas/`. Every schema change increases the version and adds a `Migration` to `AppDatabase.MIGRATIONS`. There is no destructive fallback.

**Export:** `CsvExporter` writes `samples_*.csv`, `slowdown_events_*.csv`, `detections_*.csv` and `features_*.csv` to the private cache. They are shared only through the Android share sheet. The samples format is exactly the input of the Python pipeline (`SchemaContractTest`).

ER diagram: `diagrams/erd.mmd`.
