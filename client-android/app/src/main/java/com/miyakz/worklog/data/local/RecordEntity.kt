package com.miyakz.worklog.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "records",
    indices = [Index(value = ["task_id"], unique = true)],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "db_id")
    val dbId: Long = 0,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
    // Marks records the server hasn't accepted yet (own writes only:
    // creation and deletion). Server-origin records are always dirty=false.
    @ColumnInfo(name = "dirty")
    val dirty: Boolean = true,
)
