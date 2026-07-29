package com.drivemp3.player

import android.content.Context
import com.drivemp3.player.auth.DriveAuthManager
import com.drivemp3.player.data.DriveApi
import com.drivemp3.player.data.DriveRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Hand-wired dependencies. A DI framework is not worth the ceremony at this size;
 * revisit if the graph grows past a handful of objects.
 */
object ServiceLocator {

    private val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    // Deliberately BASIC, not HEADERS: HEADERS would print the
                    // Authorization header, leaking access tokens into logcat.
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()
    }

    private val driveApi: DriveApi by lazy {
        Retrofit.Builder()
            .baseUrl(DriveApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApi::class.java)
    }

    val driveRepository: DriveRepository by lazy { DriveRepository(driveApi) }

    @Volatile
    private var authManagerInstance: DriveAuthManager? = null

    fun authManager(context: Context): DriveAuthManager =
        authManagerInstance ?: synchronized(this) {
            authManagerInstance ?: DriveAuthManager(context).also { authManagerInstance = it }
        }
}
