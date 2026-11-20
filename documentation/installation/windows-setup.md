# Setup, build and install on Windows + Samsung Galaxy S23 Ultra

## A. One-time setup on the Windows laptop

1. Install **Android Studio** (current stable) from developer.android.com/studio. Accept the default SDK. In *SDK Manager*, make sure **Android 15 (API 35) SDK Platform** and **Android SDK Platform-Tools** are installed.
2. Install **Git for Windows** and **Python 3.11–3.13** (tick "Add python.exe to PATH").
3. Unzip or clone the repository, for example to `C:\Projects\SmartFreeze-AI`.

## B. Build the Android app

1. Android Studio → **File › Open** → choose `SmartFreeze-AI\android-app` (the folder with `settings.gradle.kts`).
2. Wait for **Gradle sync**. Android Studio uses `gradle/wrapper/gradle-wrapper.properties` and downloads Gradle 8.11.1 and all libraries automatically.
   * If Android Studio asks to create the Gradle wrapper, accept. To create `gradlew.bat` for command-line builds, run once in a terminal inside `android-app`: `gradle wrapper --gradle-version 8.11.1` (requires a local Gradle), or use Android Studio's *Gradle* tool window.
   * If sync fails on a version, accept Android Studio's upgrade suggestion for **that one library**, then sync again.
3. Run the unit tests: open the *Gradle* tool window → `app › Tasks › verification › testDebugUnitTest`, or in the terminal run `.\gradlew.bat testDebugUnitTest`. These include the Python/Kotlin parity tests. **Record the result** for Chapter 4.

## C. Install on the Samsung Galaxy S23 Ultra

1. On the phone: **Settings › About phone › Software information** → tap **Build number** 7 times → enter PIN → Developer options are enabled.
2. **Settings › Developer options** → turn on **USB debugging**.
3. Connect the phone with a USB-C data cable. On the phone, tap **Allow** on the "Allow USB debugging?" prompt (tick "Always allow from this computer").
4. In Android Studio, select the phone in the device dropdown (for example "samsung SM-S918B") and press **Run ▶**.
5. Instrumented tests (Room + engine): right-click `app/src/androidTest` → **Run 'All Tests'** with the phone connected.

### Building an installable APK

* Debug APK: `.\gradlew.bat assembleDebug` → `android-app\app\build\outputs\apk\debug\app-debug.apk`
* Release APK (minified, signed with the debug key for the demonstration): `.\gradlew.bat assembleRelease` → `app\build\outputs\apk\release\app-release.apk`
* Install from the command line: `adb install -r app\build\outputs\apk\release\app-release.apk`

### First launch checks (record the results in the test plan, section C)

1. The onboarding screen appears → tap **Begin monitoring** → choose Allow or Don't allow for notifications.
2. The Dashboard shows **Monitoring** and the chip **Demo model**. RAM, thermal, battery and storage tiles fill within one interval.
3. **Settings › Model and device** shows "samsung SM-S918…", API level, and the model version.
4. Check which metrics are available: if thermal headroom shows "Headroom not reported", record that in the compatibility matrix.

## D. Offline training in Python (research evaluation; optional team starter model)

> Ordinary users do **not** need this: since checkpoint 2 every phone trains its own personal model automatically (Dashboard › "Learning your phone"). For a demonstration, use Research › *Train quick demo model* after 30 minutes of monitoring. Part D is for your **dissertation evaluation** (Isolation Forest vs thresholds on labelled data) and, optionally, to ship a team starter model.

1. Collect sessions following `research/protocols/data-collection-protocol.md`.
2. In the app: **Settings › Export CSV** → save the files to Google Drive or send them to the laptop by USB. Copy `samples_*.csv` and `slowdown_events_*.csv` into `machine-learning\data\raw\`. That folder is git-ignored, so personal data is never committed.
3. Fill in `label_anomaly` for controlled abnormal episodes using your protocol log (`scripts\label_episodes.py` does this from a small CSV of episode start and end times).
4. Train (PowerShell, from the repository root):

   ```powershell
   .\scripts\train_model.ps1 -Samples "machine-learning\data\raw\samples_*.csv" -Events "machine-learning\data\raw\slowdown_events_*.csv"
   ```

   This creates a virtual environment, runs `pytest`, trains, evaluates, writes `machine-learning\reports\report.md`, and copies the new model and parity fixtures into the Android project.
5. Rebuild and reinstall the app. Until a phone has its personal model, the chip reads **Team model**.
6. Re-run the unit tests, so `ModelParityTest` checks the new model.

## E. Measure SmartFreeze's overhead (Chapter 4 performance evaluation)

`scripts\adb_overhead.ps1` samples `dumpsys meminfo` and `dumpsys cpuinfo` for the app via ADB into a CSV. Battery comparisons: see `documentation/testing/test-plan.md` section D.
