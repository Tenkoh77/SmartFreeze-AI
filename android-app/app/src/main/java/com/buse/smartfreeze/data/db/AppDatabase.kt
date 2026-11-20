package com.buse.smartfreeze.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeviceInfoEntity::class, SessionEntity::class, SampleEntity::class, FeatureWindowEntity::class,
        ActivityLabelEntity::class, AnomalyEntity::class, SlowdownEventEntity::class, AlertEntity::class,
        ModelMetadataEntity::class, SettingsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun samples(): SampleDao
    abstract fun features(): FeatureWindowDao
    abstract fun activityLabels(): ActivityLabelDao
    abstract fun anomalies(): AnomalyDao
    abstract fun slowdownEvents(): SlowdownEventDao
    abstract fun alerts(): AlertDao
    abstract fun modelMetadata(): ModelMetadataDao
    abstract fun settings(): SettingsDao
    abstract fun deviceInfo(): DeviceInfoDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        const val NAME = "smartfreeze.db"

        /**
         * Migration policy: every schema change increments `version`, Room exports the new schema
         * to app/schemas/, and a Migration is added here and tested with MigrationTestHelper.
         * No destructive fallback: user monitoring data must never be silently deleted.
         */
        /** v1 -> v2: flag samples that must not be used as normal training data (on-device training). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE samples ADD COLUMN excludeFromTraining INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE samples SET excludeFromTraining = 1 WHERE activityLabel = 'stress_test'")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
