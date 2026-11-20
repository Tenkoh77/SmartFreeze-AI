# API and permission compatibility matrix

Phase 1 feasibility analysis: what SmartFreeze AI can and cannot observe on Android without root.
Minimum SDK 30 (Android 11), target SDK 35 (Android 15). Samsung Galaxy S23 Ultra ships with Android 13 and is upgradable to Android 14/15 (One UI 6/7).

The "S23 Ultra" column is an **expectation from documentation**. It becomes a result only after you run the device checks in `documentation/testing/test-plan.md` (section C) and record what you see.

## What the app measures

| Measurement | Android API | Min API | Permission | Notes and limitations | Fallback when unavailable | S23 Ultra (to verify) |
|---|---|---|---|---|---|---|
| Total RAM, available RAM, low-memory threshold, low-memory flag | `ActivityManager.getMemoryInfo()` | 1 | none | **System-wide**. `availMem` is memory the kernel can make available (free plus reclaimable cache). It is *not* "unused RAM". Samsung's RAM Plus (virtual RAM) is not included in `totalMem`. | none needed (always available) | expected available |
| Thermal status (0 NONE … 6 SHUTDOWN) | `PowerManager.getCurrentThermalStatus()` | 29 | none | Coarse levels decided by the device's thermal HAL. OEMs map sensors differently. | stored as `null` | expected available |
| Thermal headroom (1.0 = throttling) | `PowerManager.getThermalHeadroom(10)` | 30 | none | Returns NaN if unsupported **or if called more often than about once per second**. The app rate-limits to ≥ 1.5 s. | stored as `null`; imputed with the training median for the model | uncertain, must be checked |
| Battery level, charging state, health, temperature | sticky `ACTION_BATTERY_CHANGED` broadcast; `BatteryManager.BATTERY_PROPERTY_CAPACITY` | 1 / 21 | none | Temperature is the **battery** sensor, not CPU temperature. | level from `BatteryManager`; others `null` | expected available |
| Free / total storage of the app data partition | `StatFs(filesDir)` | 18 | none | Measures `/data` (user storage), the partition that matters for app performance. | `null` | expected available |
| SmartFreeze's own memory (PSS) | `Debug.getMemoryInfo()` | 1 | none | Own process only; used for **overhead measurement**, not as a model feature. | `null` | expected available |
| SmartFreeze's own CPU time | `Process.getElapsedCpuTime()` | 1 | none | Own process only (overhead measurement). | `null` | expected available |
| Own UI responsiveness (research) | `Handler` lateness probe, `Window.addOnFrameMetricsAvailableListener`, `withFrameNanos` | 24 | none | Measures SmartFreeze's own main thread and frames only. | — | expected available |
| Own past ANRs / crashes / low-memory kills | `ActivityManager.getHistoricalProcessExitReasons()` | 30 | none | Own package only. | empty list | expected available |
| Notifications | `NotificationManagerCompat` | — | `POST_NOTIFICATIONS` (runtime, API 33+) | Optional. If refused, alerts stay inside the app. | in-app alert list | to verify |
| Background monitoring session | Foreground service type `specialUse` | 34 (type) | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Only when the user taps *Start background session*. Visible notification with Stop, 2-hour limit, not sticky, never auto-started. | foreground-only monitoring | to verify |

## What the app deliberately does NOT measure (and why)

| Not measured | Reason |
|---|---|
| System-wide CPU utilisation | `/proc/stat` is not readable by apps since Android 8 (SELinux). No public API exists. |
| Other apps' CPU, memory or process list | `ActivityManager.getRunningAppProcesses()` returns only the caller's own processes since Android 5.1. Per-app memory of others requires privileged permissions. |
| System logs (logcat) | `READ_LOGS` is a signature/privileged permission. |
| Other apps' frame rate / jank | No public API; only the app's own window is observable. |
| GPU load, CPU frequency, CPU temperature | Not exposed by public APIs (sysfs paths are blocked or vendor-specific). |
| App usage context (`UsageStatsManager`) | Possible with the special `PACKAGE_USAGE_STATS` access. **Not implemented in this phase** to keep permissions minimal; the activity label is instead chosen manually in research mode. Candidate for future work. |

## Why the foreground-service type is `specialUse`

Android 14 requires every foreground service to declare a type. `dataSync` is meant for transfers, `health` for fitness sensors, and `systemExempted` is reserved for system apps. None describes user-initiated performance monitoring. Android documents `specialUse` for valid use cases that no other type covers. It requires a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation, which the manifest provides. The service is bounded: started only by an explicit tap, a visible ongoing notification, a Stop action, a 2-hour automatic limit, and `START_NOT_STICKY`. Google Play review would apply only if the app were published there. It is distributed as a local APK for this project.

## Library versions (chosen in Phase 1)

| Component | Version | Reason |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | Stable with Gradle 8.11, compileSdk 35 |
| Kotlin / Compose compiler plugin | 2.1.0 | Compose compiler is bundled with Kotlin 2.x |
| KSP | 2.1.0-1.0.29 | Matches Kotlin 2.1.0 (for the Room compiler) |
| Compose BOM | 2024.12.01 | Material 3 1.3 (segmented buttons, surface containers) |
| Room | 2.6.1 | `@Upsert`, KSP, schema export |
| Navigation Compose | 2.8.5 | Stable |
| WorkManager | 2.10.0 | Retention cleanup |
| Lifecycle | 2.8.7 | `collectAsStateWithLifecycle`, `ProcessLifecycleOwner` |

Android Studio may suggest newer versions. Upgrading is fine. Upgrade one library at a time and re-run `gradlew test`.
