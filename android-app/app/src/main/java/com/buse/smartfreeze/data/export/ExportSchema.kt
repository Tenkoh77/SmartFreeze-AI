package com.buse.smartfreeze.data.export

/**
 * Column layout of the exported samples CSV. It must contain every column read by
 * machine-learning/src/smartfreeze_ml/schema.py (checked by SchemaContractTest).
 * label_anomaly is exported empty: it is filled in during analysis from the research protocol log.
 */
object ExportSchema {
    const val SAMPLE_HEADER = "session_id,timestamp_ms,interval_s,mem_total_bytes,mem_avail_bytes,mem_threshold_bytes," +
        "low_memory,battery_level_pct,is_charging,activity_label,thermal_status,thermal_headroom,battery_temp_c," +
        "storage_total_bytes,storage_avail_bytes,app_pss_kb,label_anomaly,is_simulated"
}
