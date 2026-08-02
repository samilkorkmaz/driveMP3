import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Short commit the build was cut from, for the Settings build-info line. Resolved at
// configuration time; falls back to "unknown" when git is absent (e.g. a source zip) or
// the command fails, so a build never breaks over a missing hash.
val gitHash: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

// Wall-clock time of this build, device-local. Changing every build is the point — it
// intentionally re-stamps BuildConfig each time, so it always reflects the current APK.
val buildTime: String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())

android {
    namespace = "com.drivemp3.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drivemp3.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"

        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Google Drive REST v3 is called directly — the google-api-services-drive
    // Java client is heavy and blocking, and only three endpoints are needed.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    // Local index: renders the library instantly and offline, and does the sorting
    // in SQL rather than holding 1,000+ items sorted in memory.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // Media3 rather than MediaPlayer: HTTP Range-based seeking, a pluggable
    // DataSource for the bearer token, and the CacheDataSource that v0.5 needs.
    implementation(libs.androidx.media3.exoplayer)
    // The session layer is what earns background playback: it supplies the
    // notification, the media-button routing, and the process-lifetime player.
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    // Declared explicitly rather than leaned on transitively: the extractor flags are
    // what make the seek bar work on MP3s with no Xing header.
    implementation(libs.androidx.media3.extractor)
    // SimpleCache keeps its span index in a SQLite database of its own, separate from
    // the Room library index.
    implementation(libs.androidx.media3.database)
}
