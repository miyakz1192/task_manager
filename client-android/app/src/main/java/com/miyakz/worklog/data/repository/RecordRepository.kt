package com.miyakz.worklog.data.repository

import com.miyakz.worklog.data.local.RecordDao
import com.miyakz.worklog.data.local.RecordEntity
import com.miyakz.worklog.util.TaskIdGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * Local-only CRUD for work records. Every write here is immediate and
 * independent of network/sync state — the app must stay fully usable
 * whether or not the server is ever reachable.
 */
class RecordRepository(private val recordDao: RecordDao) {

    fun observeTodayRecords(): Flow<List<RecordEntity>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startOfDay = today.atStartOfDay(zone).toInstant().toString()
        val endOfDay = today.plusDays(1).atStartOfDay(zone).toInstant().toString()
        return recordDao.observeRecordsBetween(startOfDay, endOfDay)
    }

    suspend fun addRecord(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val now = Instant.now().toString()
        recordDao.insertNew(
            RecordEntity(
                taskId = TaskIdGenerator.generate(),
                text = trimmed,
                createdAt = now,
                updatedAt = now,
                isDeleted = false,
                dirty = true,
            )
        )
    }

    suspend fun deleteRecord(taskId: String) {
        recordDao.softDelete(taskId, Instant.now().toString())
    }

    suspend fun suggestTexts(prefix: String): List<String> {
        if (prefix.isBlank()) return emptyList()
        return recordDao.suggestTexts(prefix)
    }
}
