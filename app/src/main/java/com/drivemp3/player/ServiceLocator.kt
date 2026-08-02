package com.drivemp3.player

import android.content.Context
import androidx.room.Room
import com.drivemp3.player.auth.DriveAuthManager
import com.drivemp3.player.data.DriveApi
import com.drivemp3.player.data.DriveRepository
import com.drivemp3.player.data.NetworkMonitor
import com.drivemp3.player.data.RetryInterceptor
import com.drivemp3.player.data.SettingsStore
import com.drivemp3.player.data.TrackRepository
import com.drivemp3.player.data.local.DriveMp3Database
import com.drivemp3.player.playback.MediaCache
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Hand-wired dependencies. A DI framework is not worth the ceremony at this size;
 * revisit if the graph grows past a handful of objects.
 *
 * Everything context-dependent is created once, lazily, from the application
 * context — never an Activity, which would leak across rotation.
 */
object ServiceLocator {

    private val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Ahead of logging so a retried request is logged each attempt.
            .addInterceptor(RetryInterceptor())
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

    @Volatile private var database: DriveMp3Database? = null
    @Volatile private var authManagerInstance: DriveAuthManager? = null
    @Volatile private var trackRepositoryInstance: TrackRepository? = null
    @Volatile private var settingsStoreInstance: SettingsStore? = null
    @Volatile private var mediaCacheInstance: MediaCache? = null
    @Volatile private var networkMonitorInstance: NetworkMonitor? = null

    fun networkMonitor(context: Context): NetworkMonitor =
        networkMonitorInstance ?: synchronized(this) {
            networkMonitorInstance ?: NetworkMonitor(context).also { networkMonitorInstance = it }
        }

    /**
     * Necessarily a process-wide singleton: `SimpleCache` locks its directory and
     * throws if a second instance is opened on the same one.
     */
    fun mediaCache(context: Context): MediaCache =
        mediaCacheInstance ?: synchronized(this) {
            mediaCacheInstance ?: MediaCache(
                context = context.applicationContext,
                dao = database(context).cachedFileDao(),
                settingsStore = settingsStore(context),
            ).also { mediaCacheInstance = it }
        }

    fun authManager(context: Context): DriveAuthManager =
        authManagerInstance ?: synchronized(this) {
            authManagerInstance ?: DriveAuthManager(context).also { authManagerInstance = it }
        }

    fun settingsStore(context: Context): SettingsStore =
        settingsStoreInstance ?: synchronized(this) {
            settingsStoreInstance ?: SettingsStore(context).also { settingsStoreInstance = it }
        }

    fun trackRepository(context: Context): TrackRepository =
        trackRepositoryInstance ?: synchronized(this) {
            trackRepositoryInstance ?: TrackRepository(
                driveRepository = driveRepository,
                trackDao = database(context).trackDao(),
                cachedFileDao = database(context).cachedFileDao(),
            ).also { trackRepositoryInstance = it }
        }

    private fun database(context: Context): DriveMp3Database =
        database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                DriveMp3Database::class.java,
                DriveMp3Database.NAME,
            )
                // The index is a cache of Drive, never a source of truth, so
                // throwing it away on a schema change is always safe.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { database = it }
        }
}
