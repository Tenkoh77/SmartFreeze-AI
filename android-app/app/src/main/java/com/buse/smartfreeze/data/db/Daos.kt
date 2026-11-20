package com.buse.smartfreeze.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.buse.smartfreeze.domain.BaselineStats
import kotlinx.coroutines.flow.Flow

data class DailyCount(val day: String, val samples: Int, val anomalies: Int)

data class SessionSummary(
    val id: Long, val startedAtMs: Long, val endedAtMs: Long?, val intervalS: Int, val mode: String,
    val isResearch: Boolean, val sampleCount: Int, val anomalyCount: Int,
)

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Query("UPDATE sessions SET endedAtMs = :end WHERE id = :id AND endedAtMs IS NULL") suspend fun end(id: Long, end: Long)
    @Query("UPDATE sessions SET endedAtMs = (SELECT MAX(timestampMs) FROM samples WHERE sessionId = sessions.id) WHERE endedAtMs IS NULL")
    suspend fun closeDangling()
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: Long): SessionEntity?
    @Query("""
        SELECT s.id, s.startedAtMs, s.endedAtMs, s.intervalS, s.mode, s.isResearch,
               (SELECT COUNT(*) FROM samples x WHERE x.sessionId = s.id) AS sampleCount,
               (SELECT COUNT(*) FROM anomalies a WHERE a.sessionId = s.id AND a.isAnomalous = 1) AS anomalyCount
        FROM sessions s WHERE s.startedAtMs BETWEEN :from AND :to ORDER BY s.startedAtMs DESC
    """)
    fun summaries(from: Long, to: Long): Flow<List<SessionSummary>>
    @Query("DELETE FROM sessions WHERE startedAtMs < :before AND endedAtMs IS NOT NULL") suspend fun deleteOlderThan(before: Long): Int
    @Query("DELETE FROM sessions") suspend fun deleteAll()
}

@Dao
interface SampleDao {
    /** IGNORE + unique (sessionId, timestampMs) index prevents duplicate records. Returns -1 on duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(s: SampleEntity): Long
    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC") suspend fun forSession(sessionId: Long): List<SampleEntity>
    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY timestampMs DESC LIMIT :limit")
    fun recent(sessionId: Long, limit: Int): Flow<List<SampleEntity>>
    @Query("SELECT * FROM samples WHERE timestampMs BETWEEN :from AND :to ORDER BY timestampMs ASC")
    fun between(from: Long, to: Long): Flow<List<SampleEntity>>
    @Query("SELECT * FROM samples ORDER BY sessionId ASC, timestampMs ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<SampleEntity>
    @Query("SELECT * FROM samples ORDER BY timestampMs DESC LIMIT 1") fun latest(): Flow<SampleEntity?>
    @Query("SELECT COUNT(*) FROM samples") fun count(): Flow<Int>
    @Query("""
        SELECT strftime('%Y-%m-%d', timestampMs / 1000, 'unixepoch', 'localtime') AS day,
               COUNT(*) AS samples,
               (SELECT COUNT(*) FROM anomalies a WHERE a.isAnomalous = 1 AND
                  strftime('%Y-%m-%d', a.timestampMs / 1000, 'unixepoch', 'localtime') =
                  strftime('%Y-%m-%d', samples.timestampMs / 1000, 'unixepoch', 'localtime')) AS anomalies
        FROM samples WHERE timestampMs >= :from GROUP BY day ORDER BY day DESC
    """)
    fun dailyCounts(from: Long): Flow<List<DailyCount>>
    @Query("DELETE FROM samples WHERE timestampMs < :before") suspend fun deleteOlderThan(before: Long): Int

    // ---- On-device training (only real, normal-use samples: no simulations, no stress tests) ----
    @Query(TRAINABLE_STATS) fun baselineStatsFlow(from: Long): Flow<BaselineStats>
    @Query(TRAINABLE_STATS) suspend fun baselineStats(from: Long): BaselineStats
    @Query("SELECT COUNT(*) FROM samples WHERE $TRAINABLE AND timestampMs > :since") suspend fun countTrainableSince(since: Long): Int
    /** Most recent trainable samples (newest first); the trainer re-sorts them by session and time. */
    @Query("SELECT * FROM samples WHERE $TRAINABLE AND timestampMs >= :from ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun trainingSamples(from: Long, limit: Int): List<SampleEntity>

    companion object {
        const val TRAINABLE = "isSimulated = 0 AND excludeFromTraining = 0 AND (activityLabel IS NULL OR activityLabel != 'stress_test')"
        const val TRAINABLE_STATS = "SELECT COUNT(*) AS samples, " +
            "COUNT(DISTINCT strftime('%Y-%m-%d', timestampMs / 1000, 'unixepoch', 'localtime')) AS days, " +
            "COALESCE(SUM(intervalS), 0) AS monitoredSeconds FROM samples WHERE " + TRAINABLE + " AND timestampMs >= :from"
    }
}

@Dao
interface FeatureWindowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(f: FeatureWindowEntity): Long
    @Query("SELECT * FROM feature_windows ORDER BY sessionId ASC, timestampMs ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<FeatureWindowEntity>
}

@Dao
interface ActivityLabelDao {
    @Insert suspend fun insert(a: ActivityLabelEntity): Long
    @Query("UPDATE activity_labels SET endMs = :end WHERE sessionId = :sessionId AND endMs IS NULL") suspend fun closeOpen(sessionId: Long, end: Long)
    @Query("SELECT * FROM activity_labels ORDER BY startMs ASC") suspend fun all(): List<ActivityLabelEntity>
}

@Dao
interface AnomalyDao {
    @Insert suspend fun insert(a: AnomalyEntity): Long
    @Query("SELECT * FROM anomalies ORDER BY timestampMs DESC LIMIT 1") fun latest(): Flow<AnomalyEntity?>
    @Query("SELECT * FROM anomalies WHERE sessionId = :sessionId ORDER BY timestampMs DESC LIMIT :limit")
    fun recentForSession(sessionId: Long, limit: Int): Flow<List<AnomalyEntity>>
    @Query("SELECT * FROM anomalies WHERE timestampMs BETWEEN :from AND :to AND (:activity IS NULL OR activityLabel = :activity) ORDER BY timestampMs DESC")
    fun between(from: Long, to: Long, activity: String?): Flow<List<AnomalyEntity>>
    @Query("SELECT * FROM anomalies ORDER BY timestampMs ASC LIMIT :limit OFFSET :offset") suspend fun page(limit: Int, offset: Int): List<AnomalyEntity>
    @Query("SELECT DISTINCT activityLabel FROM anomalies WHERE activityLabel IS NOT NULL") fun activities(): Flow<List<String>>
}

@Dao
interface SlowdownEventDao {
    @Insert suspend fun insert(e: SlowdownEventEntity): Long
    @Query("SELECT * FROM slowdown_events ORDER BY timestampMs DESC LIMIT :limit") fun recent(limit: Int): Flow<List<SlowdownEventEntity>>
    @Query("SELECT * FROM slowdown_events ORDER BY timestampMs ASC") suspend fun all(): List<SlowdownEventEntity>
    @Query("SELECT COUNT(*) FROM slowdown_events") fun count(): Flow<Int>
}

@Dao
interface AlertDao {
    @Insert suspend fun insert(a: AlertEntity): Long
    @Query("SELECT * FROM alerts ORDER BY timestampMs DESC LIMIT :limit") fun recent(limit: Int): Flow<List<AlertEntity>>
    @Query("SELECT COUNT(*) FROM alerts WHERE acknowledged = 0") fun unacknowledgedCount(): Flow<Int>
    @Query("UPDATE alerts SET acknowledged = 1 WHERE id = :id") suspend fun acknowledge(id: Long)
    @Query("UPDATE alerts SET acknowledged = 1") suspend fun acknowledgeAll()
    @Query("UPDATE alerts SET notified = 1 WHERE id = :id") suspend fun markNotified(id: Long)
    @Query("DELETE FROM alerts WHERE timestampMs < :before") suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface ModelMetadataDao {
    @Upsert suspend fun upsert(m: ModelMetadataEntity)
    @Query("SELECT * FROM model_metadata ORDER BY loadedAtMs DESC LIMIT 1") fun latest(): Flow<ModelMetadataEntity?>
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1") fun observe(): Flow<SettingsEntity?>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun get(): SettingsEntity?
    @Upsert suspend fun upsert(s: SettingsEntity)
}

@Dao
interface DeviceInfoDao {
    @Upsert suspend fun upsert(d: DeviceInfoEntity)
    @Query("SELECT * FROM device_info WHERE id = 1") fun observe(): Flow<DeviceInfoEntity?>
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM sessions") suspend fun clearSessions()   // cascades to samples, features, labels, anomalies
    @Query("DELETE FROM alerts") suspend fun clearAlerts()
    @Query("DELETE FROM slowdown_events") suspend fun clearSlowdownEvents()
    @Query("DELETE FROM slowdown_events WHERE timestampMs < :before") suspend fun deleteEventsOlderThan(before: Long): Int
}
