"""SmartFreeze AI machine-learning pipeline.

Packages:
    preprocessing  - loading and validating raw sample CSVs exported by the Android app
    features       - leakage-free rolling feature engineering (mirrors Kotlin FeatureProcessor)
    training       - Isolation Forest training, threshold tuning, activity-aware models
    evaluation     - metrics, fixed-threshold baseline, plots and reports
    export         - JSON model export and the pure-Python reference scorer used for parity
    research       - slowdown labelling and the experimental early-warning classifier
"""

__version__ = "0.1.0"
