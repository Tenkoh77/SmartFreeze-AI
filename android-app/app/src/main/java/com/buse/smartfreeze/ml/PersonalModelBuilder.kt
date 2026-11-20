package com.buse.smartfreeze.ml

import com.buse.smartfreeze.domain.model.RawSample
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds a personal (on-device) model bundle from the phone's own recorded samples:
 *  1. recompute leakage-free features per session with [FeatureProcessor] (same code as live detection);
 *  2. train the general forest with [IsolationForestTrainer] (chronological split + validated threshold);
 *  3. train activity-specific forests (Approach A) for activities with enough rows; others fall back
 *     to the general model at runtime;
 *  4. return a [ModelBundle] with origin "on_device" or "on_device_demo" and training metadata.
 * Pure Kotlin: unit-tested on the JVM.
 */
class PersonalModelBuilder(
    private val trainer: IsolationForestTrainer = IsolationForestTrainer(),
    private val windowSeconds: Int = 60,
    private val minActivityRows: Int = 500,
) {
    /** A stored sample with its session and (optional) manual activity label. */
    class LabelledSample(val sessionId: Long, val activity: String?, val sample: RawSample)

    class Result(val bundle: ModelBundle, val featureRows: Int, val totalMillis: Long)

    fun featureRows(samples: List<LabelledSample>): List<IsolationForestTrainer.Row> =
        samples.groupBy { it.sessionId }.flatMap { (sid, list) ->
            val fp = FeatureProcessor(windowSeconds)
            list.sortedBy { it.sample.timestampMs }
                .distinctBy { it.sample.timestampMs }
                .map { IsolationForestTrainer.Row(sid, it.sample.timestampMs, it.activity, fp.next(it.sample)) }
        }

    fun build(samples: List<LabelledSample>, demo: Boolean, nowMs: Long = System.currentTimeMillis(),
              extraMetadata: Map<String, Any?> = emptyMap()): Result {
        val started = System.nanoTime()
        val rows = featureRows(samples)
        if (rows.size < trainer.minTrainRows + trainer.minValidationRows) {
            throw IsolationForestTrainer.NotEnoughDataException("only ${rows.size} usable samples")
        }
        val general = trainer.train(rows, "general")

        val activityModels = LinkedHashMap<String, IsolationForest>()
        val skipped = ArrayList<String>()
        if (!demo) {
            rows.filter { it.activity != null && it.activity != "unlabelled" }.groupBy { it.activity!! }
                .toSortedMap().forEach { (act, r) ->
                    if (r.size < minActivityRows) { skipped += act; return@forEach }
                    try {
                        activityModels[act] = trainer.train(r, "activity:$act").forest
                    } catch (e: IsolationForestTrainer.NotEnoughDataException) {
                        skipped += act
                    }
                }
        }
        val totalMs = (System.nanoTime() - started) / 1_000_000
        val created = Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.UTC)
        val origin = if (demo) ModelBundle.ORIGIN_ON_DEVICE_DEMO else ModelBundle.ORIGIN_ON_DEVICE
        val metadata = linkedMapOf<String, Any?>(
            "trained_at_ms" to nowMs,
            "training_samples" to samples.size,
            "feature_rows" to rows.size,
            "train_rows" to general.nTrain,
            "validation_rows" to general.nValidation,
            "split_method" to general.splitMethod,
            "fit_ms" to general.fitMillis,
            "total_training_ms" to totalMs,
            "sessions" to samples.map { it.sessionId }.distinct().size,
            "activity_models_skipped" to skipped.joinToString(","),
        ).apply { putAll(extraMetadata) }
        val bundle = ModelBundle(
            modelVersion = (if (demo) "demo-" else "dev-") + created.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
            createdUtc = created.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            syntheticTrainingData = false,
            windowSeconds = windowSeconds,
            general = general.forest,
            activityModels = activityModels,
            origin = origin,
            metadata = metadata,
        )
        return Result(bundle, rows.size, totalMs)
    }
}
