package com.miyakz.worklog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    @Insert
    suspend fun insertNew(record: RecordEntity): Long

    @Query(
        """
        SELECT * FROM records
        WHERE is_deleted = 0 AND created_at >= :startOfDayIso AND created_at < :endOfDayIso
        ORDER BY created_at DESC
        """
    )
    fun observeRecordsBetween(startOfDayIso: String, endOfDayIso: String): Flow<List<RecordEntity>>

    @Query(
        """
        UPDATE records
        SET is_deleted = 1, updated_at = :updatedAtIso, dirty = 1
        WHERE task_id = :taskId
        """
    )
    suspend fun softDelete(taskId: String, updatedAtIso: String)

    @Query("SELECT * FROM records WHERE dirty = 1")
    suspend fun getDirtyRecords(): List<RecordEntity>

    @Query("UPDATE records SET dirty = 0 WHERE task_id IN (:taskIds)")
    suspend fun clearDirty(taskIds: List<String>)

    @Query(
        """
        UPDATE records
        SET text = :text, updated_at = :updatedAtIso, is_deleted = :isDeleted, dirty = 0
        WHERE task_id = :taskId
        """
    )
    suspend fun updateFromServer(
        taskId: String,
        text: String,
        updatedAtIso: String,
        isDeleted: Boolean,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(record: RecordEntity): Long

    /**
     * Upsert-by-task_id for records coming from a server Pull. Room's
     * built-in @Upsert resolves conflicts on the primary key (db_id, which
     * is always unset here), not the task_id unique index, so this needs
     * an explicit update-then-insert-if-missing instead.
     */
    @Transaction
    suspend fun upsertFromServer(
        taskId: String,
        text: String,
        createdAtIso: String,
        updatedAtIso: String,
        isDeleted: Boolean,
    ) {
        val updated = updateFromServer(taskId, text, updatedAtIso, isDeleted)
        if (updated == 0) {
            insertIgnoringConflict(
                RecordEntity(
                    taskId = taskId,
                    text = text,
                    createdAt = createdAtIso,
                    updatedAt = updatedAtIso,
                    isDeleted = isDeleted,
                    dirty = false,
                )
            )
        }
    }

    /** Distinct, active task texts for incremental-prediction suggestions. */
    @Query(
        """
        SELECT DISTINCT text FROM records
        WHERE is_deleted = 0 AND text LIKE :prefix || '%'
        ORDER BY text
        LIMIT 20
        """
    )
    suspend fun suggestTexts(prefix: String): List<String>
}
