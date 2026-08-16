package com.miyakz.worklog.data.repository

import com.miyakz.worklog.data.local.RecordDao
import com.miyakz.worklog.data.local.RecordEntity
import com.miyakz.worklog.util.TaskIdGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * Local-only CRUD for work records. Every write here is immediate and
 * independent of network/sync state — the app must stay fully usable
 * whether or not the server is ever reachable.
 */
class RecordRepository(private val recordDao: RecordDao) {

    fun observeRecordsForDate(date: LocalDate): Flow<List<RecordEntity>> {
        val zone = ZoneId.systemDefault()
        val startOfDay = date.atStartOfDay(zone).toInstant().toString()
        val endOfDay = date.plusDays(1).atStartOfDay(zone).toInstant().toString()
        return recordDao.observeRecordsBetween(startOfDay, endOfDay)
    }

    /**
     * Adds a record dated to [date] (defaults to today, but the UI lets the
     * user pick an earlier day to log work they forgot to note at the time).
     * The time-of-day is always "now" — only the calendar day is adjustable —
     * so entries added in the same backfill session still sort by entry order.
     */
    suspend fun addRecord(text: String, date: LocalDate = LocalDate.now()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val timestamp = date.atTime(LocalTime.now())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toString()
        recordDao.insertNew(
            RecordEntity(
                taskId = TaskIdGenerator.generate(),
                text = trimmed,
                createdAt = timestamp,
                updatedAt = timestamp,
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
