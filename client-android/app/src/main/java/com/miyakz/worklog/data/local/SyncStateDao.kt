package com.miyakz.worklog.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setValue(entity: SyncStateEntity)

    suspend fun getSinceChangeSeq(): Long =
        getValue(SyncStateKeys.SINCE_CHANGE_SEQ)?.toLongOrNull() ?: 0L

    suspend fun setSinceChangeSeq(value: Long) =
        setValue(SyncStateEntity(SyncStateKeys.SINCE_CHANGE_SEQ, value.toString()))
}
