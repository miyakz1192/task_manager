package com.miyakz.worklog.data.repository

import com.miyakz.worklog.data.local.RecordDao
import com.miyakz.worklog.data.local.SyncStateDao
import com.miyakz.worklog.data.remote.ServerAddress
import com.miyakz.worklog.data.remote.ServerDiscovery
import com.miyakz.worklog.data.remote.SyncApi
import com.miyakz.worklog.data.remote.dto.ClientRecordDto
import com.miyakz.worklog.data.remote.dto.PushRequestDto
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

private const val CONNECT_TIMEOUT_SECONDS = 3L

sealed interface SyncResult {
    data object ServerNotFound : SyncResult
    data class Success(val pushedCount: Int, val pulledCount: Int) : SyncResult
    data class Failed(val error: Throwable) : SyncResult
}

/**
 * Best-effort LAN sync: discover the server via mDNS, then Push before
 * Pull (always in that order — see CLAUDE.md sync protocol). Any failure
 * here (server offline, discovery timeout, network error) is expected,
 * routine behavior, never a user-facing error: the app is fully usable
 * offline.
 */
class SyncRepository(
    private val recordDao: RecordDao,
    private val syncStateDao: SyncStateDao,
    private val discovery: ServerDiscovery,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun syncNow(): SyncResult {
        val address = discovery.discover() ?: return SyncResult.ServerNotFound
        return try {
            val api = buildApi(address)
            val pushedCount = pushDirtyRecords(api)
            val pulledCount = pullServerChanges(api)
            SyncResult.Success(pushedCount, pulledCount)
        } catch (e: Exception) {
            SyncResult.Failed(e)
        }
    }

    private fun buildApi(address: ServerAddress): SyncApi {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("http://${address.host}:${address.port}/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SyncApi::class.java)
    }

    private suspend fun pushDirtyRecords(api: SyncApi): Int {
        val dirty = recordDao.getDirtyRecords()
        if (dirty.isEmpty()) return 0

        val request = PushRequestDto(
            records = dirty.map {
                ClientRecordDto(
                    task_id = it.taskId,
                    text = it.text,
                    created_at = it.createdAt,
                    updated_at = it.updatedAt,
                    is_deleted = it.isDeleted,
                )
            }
        )
        val response = api.push(request)
        recordDao.clearDirty(response.accepted)
        return response.accepted.size
    }

    private suspend fun pullServerChanges(api: SyncApi): Int {
        val since = syncStateDao.getSinceChangeSeq()
        val response = api.pull(since)
        for (record in response.records) {
            recordDao.upsertFromServer(
                taskId = record.task_id,
                text = record.text,
                createdAtIso = record.created_at,
                updatedAtIso = record.updated_at,
                isDeleted = record.is_deleted,
            )
        }
        syncStateDao.setSinceChangeSeq(response.max_change_seq)
        return response.records.size
    }
}
