package com.miyakz.worklog.data.remote

import com.miyakz.worklog.data.remote.dto.PullResponseDto
import com.miyakz.worklog.data.remote.dto.PushRequestDto
import com.miyakz.worklog.data.remote.dto.PushResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface SyncApi {
    @POST("/api/v1/sync/push")
    suspend fun push(@Body request: PushRequestDto): PushResponseDto

    @GET("/api/v1/sync/pull")
    suspend fun pull(@Query("since_change_seq") sinceChangeSeq: Long): PullResponseDto
}
