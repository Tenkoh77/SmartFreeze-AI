package com.buse.smartfreeze.ml

/**
 * Kotlin copy of machine-learning/data/schemas/feature_schema.json.
 * The model loader refuses any bundle whose feature_names differ from this list,
 * so a model trained on a different feature layout can never be used silently.
 */
object FeatureSchema {
    const val VERSION = 1

    val NAMES = listOf(
        "mem_avail_ratio", "mem_margin_ratio", "low_memory", "thermal_status", "thermal_headroom",
        "battery_level", "is_charging", "battery_temp_c", "storage_free_ratio",
        "mem_ratio_mean_w", "mem_ratio_std_w", "mem_ratio_slope_w", "thermal_delta_w",
        "pressure_fraction_w", "sample_gap_s", "completeness_w",
    )
    val SIZE = NAMES.size

    // Indices used by domain logic (explanations, severity, rule-based warnings).
    const val MEM_AVAIL_RATIO = 0
    const val MEM_MARGIN_RATIO = 1
    const val LOW_MEMORY = 2
    const val THERMAL_STATUS = 3
    const val THERMAL_HEADROOM = 4
    const val BATTERY_LEVEL = 5
    const val IS_CHARGING = 6
    const val BATTERY_TEMP_C = 7
    const val STORAGE_FREE_RATIO = 8
    const val MEM_RATIO_MEAN_W = 9
    const val MEM_RATIO_STD_W = 10
    const val MEM_RATIO_SLOPE_W = 11
    const val THERMAL_DELTA_W = 12
    const val PRESSURE_FRACTION_W = 13
    const val SAMPLE_GAP_S = 14
    const val COMPLETENESS_W = 15

    /** Same constants as features/engineering.py. */
    const val PRESSURE_MARGIN = 0.05
    const val PRESSURE_THERMAL = 3
}
