# Data collection protocol (Samsung Galaxy S23 Ultra)

Purpose: collect labelled normal and controlled-abnormal monitoring data for training and evaluating the Isolation Forest, and responsiveness ground truth for the experimental early-warning study.

## Before each session

* Fill one row of the session log (`research/experiments/session_log_template.csv`): date, start time, battery %, charging, ambient conditions, activity, notes.
* In SmartFreeze AI: **Research** → select the activity label → start monitoring on the Dashboard → **Start probe**.
* Use the same sampling interval for a given experiment (default 10 s; repeat a subset at 5 s and 15 s for the interval comparison).

## Normal data (label_anomaly = 0)

At least **3 sessions × 30 minutes per activity**, spread over at least 5 different days, so the chronological split has unseen days in validation and test:

| Activity label | How |
|---|---|
| idle | Screen on, app open, phone untouched |
| browsing | Browse news and social sites normally |
| video | Stream a video (fixed resolution) |
| app_switching | Switch between about 8 apps every few seconds |
| charging | Normal light use while charging |
| gaming | Play a 3D game (normal operation, heat is expected) |
| daily_use | Your ordinary use, not scripted |

## Controlled abnormal episodes (label_anomaly = 1)

Within some sessions (never in every session), start a controlled condition and **note the start and end times** in the episode log (`research/experiments/episode_log_template.csv`):

| Episode | How (safe, reversible) |
|---|---|
| memory_pressure | Research › Memory pressure workload (2 min) and/or open many heavy apps quickly |
| thermal_stress | Research › CPU load workload while gaming (stop if the phone reports SEVERE) |
| combined | Both together |
| storage_low | (optional) temporarily fill storage with a large file to < 10 % free, then delete it |

`scripts/label_episodes.py` copies these intervals into the exported `label_anomaly` column.

## Slowdown ground truth (definition fixed BEFORE collecting evaluation data)

A **slowdown event** is recorded when (a) the main-thread probe runs > 250 ms late, (b) a research-screen frame takes > 250 ms, (c) the tap test exceeds 250 ms, or (d) you press **I noticed lag now**. Events within 30 s of each other are merged. These measure SmartFreeze's own responsiveness, which is a proxy for system responsiveness. State this limitation in the dissertation.

## Optional: Perfetto traces for selected sessions

For 2–3 representative sessions, record a system trace (Developer options › System tracing, or `adb shell perfetto`) and inspect it at ui.perfetto.dev to confirm the slowdown events with frame and scheduling data.

## Ethics

If anyone other than you uses a phone for data collection, obtain written consent (`research/consent/participant-consent-template.md`) and keep their exports anonymised (session IDs only).
