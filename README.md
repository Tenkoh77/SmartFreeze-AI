# SmartFreeze AI

**Design and Development of an Intelligent Android Smartphone Performance Anomaly Detection and Freeze-Risk Warning System Using Machine Learning**

SmartFreeze AI is an Android app that samples the performance indicators Android makes available to ordinary apps (memory, thermal state, battery, storage). It learns the phone's normal pattern with an **Isolation Forest** and flags unusual conditions with an explanation and safe recommendations. It works without root and without internet access, and all data stays on the phone. A research mode collects responsiveness ground truth for an **experimental** early-slowdown-warning study. Anomaly detection and freeze prediction are treated as different problems.

## Repository

```
android-app/        Kotlin + Jetpack Compose app (MVVM, Room, WorkManager)
  app/src/main/java/com/buse/smartfreeze/
    ml/             Isolation Forest inference + feature processor (pure Kotlin)
    domain/         detection pipeline, severity, alert policy, explanations (pure Kotlin)
    data/           metric collectors, Room DB, repositories, CSV export
    monitoring/     sampling engine, foreground service, notifications
    research/       responsiveness probe, controlled workloads
    ui/             9 screens, theme, charts, ViewModels, navigation
  app/src/test/     JVM unit tests incl. Python/Kotlin parity test
  app/src/androidTest/  Room + engine instrumented tests
machine-learning/   Python pipeline: validate, features, train, evaluate, export, research
diagrams/           Mermaid / PlantUML sources (8 diagrams)
documentation/      architecture, compatibility matrix, DB, model card, test plan, setup
research/           data-collection protocol, logs, consent template, results
scripts/            training, labelling, overhead measurement, Kotlin-core verification
```

## Quick start

* **Build and run on the phone:** `documentation/installation/windows-setup.md`
* **Train on your own data:** `scripts\train_model.ps1` (Windows) or `scripts/train_model.sh`
* **Run Python tests:** `cd machine-learning && pip install -r requirements.txt && python -m pytest`

## Status

See **`CHECKPOINT.md`** for the verified/unverified status of every component and the next steps.

> Each phone trains its **own personal model on the device** after about 6 hours of monitoring over 3 days (no export needed). Until then the app shows "Learning your phone" and uses labelled rule-based checks. Research mode has a 30-minute *quick demo baseline* for demonstrations.
