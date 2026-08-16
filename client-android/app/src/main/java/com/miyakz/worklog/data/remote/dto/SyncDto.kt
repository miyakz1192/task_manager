package com.miyakz.worklog.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ClientRecordDto(
    val task_id: String,
    val text: String,
    val created_at: String,
    val updated_at: String,
    val is_deleted: Boolean,
)

@Serializable
data class PushRequestDto(
    val records: List<ClientRecordDto>,
)

@Serializable
data class PushResponseDto(
    val accepted: List<String>,
)

@Serializable
data class ServerRecordDto(
    val task_id: String,
    val text: String,
    val created_at: String,
    val updated_at: String,
    val is_deleted: Boolean,
    val change_seq: Long,
)

@Serializable
data class PullResponseDto(
    val records: List<ServerRecordDto>,
    val max_change_seq: Long,
)
