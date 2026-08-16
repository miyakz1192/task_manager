package com.miyakz.worklog.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val key: String,
    val value: String,
)

object SyncStateKeys {
    // Server-issued high-water mark this client has already pulled;
    // named to match the server's change_seq / since_change_seq vocabulary.
    const val SINCE_CHANGE_SEQ = "since_change_seq"
}
