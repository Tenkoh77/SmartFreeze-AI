package com.buse.smartfreeze.data.repository

import com.buse.smartfreeze.data.db.SettingsDao
import com.buse.smartfreeze.data.db.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsRepository(private val dao: SettingsDao) {
    private val mutex = Mutex()

    val settings: Flow<SettingsEntity> = dao.observe().map { it ?: SettingsEntity() }

    suspend fun current(): SettingsEntity = dao.get() ?: SettingsEntity()

    /** Read-modify-write under a mutex so concurrent edits from different screens are not lost. */
    suspend fun update(transform: (SettingsEntity) -> SettingsEntity) = mutex.withLock {
        val next = transform(current()).let {
            it.copy(
                intervalS = it.intervalS.coerceIn(MIN_INTERVAL_S, MAX_INTERVAL_S),
                cooldownMinutes = it.cooldownMinutes.coerceIn(1, 240),
                retentionDays = it.retentionDays.coerceIn(1, 365),
            )
        }
        dao.upsert(next)
    }

    companion object {
        const val MIN_INTERVAL_S = 5
        const val MAX_INTERVAL_S = 60
        val INTERVAL_CHOICES = listOf(5, 10, 15, 30)
        val RETENTION_CHOICES = listOf(7, 14, 30, 90)
    }
}
