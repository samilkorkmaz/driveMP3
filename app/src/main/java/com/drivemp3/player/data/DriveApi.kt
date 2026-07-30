package com.drivemp3.player.data

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * The slice of Google Drive REST v3 this app uses.
 *
 * The bearer token is passed per call rather than injected by an OkHttp
 * interceptor: token retrieval is a suspending call, and interceptors are
 * blocking, so passing it explicitly avoids a `runBlocking` inside the network
 * stack.
 */
interface DriveApi {

    @GET("drive/v3/about")
    suspend fun about(
        @Header("Authorization") bearer: String,
        @Query("fields") fields: String,
    ): AboutResponse

    @GET("drive/v3/files")
    suspend fun listFiles(
        @Header("Authorization") bearer: String,
        @Query("q") query: String,
        @Query("fields") fields: String,
        @Query("pageSize") pageSize: Int,
        @Query("orderBy") orderBy: String?,
        @Query("pageToken") pageToken: String?,
    ): DriveFileListResponse

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"

        /**
         * The binary-content URL for a file, fetched by the player rather than by
         * Retrofit — ExoPlayer needs the URL so it can issue its own ranged GETs.
         *
         * No percent-encoding: Drive file ids are URL-safe base64 (`[A-Za-z0-9_-]`).
         */
        fun mediaUrl(fileId: String): String = "${BASE_URL}drive/v3/files/$fileId?alt=media"
    }
}
