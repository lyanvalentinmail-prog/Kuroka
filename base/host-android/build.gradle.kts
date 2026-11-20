// The Android host: the playback service (Media3 session, notification, lock screen), the
// downloads (Media3 offline, with their own HTTP data source), the library (list, favourites,
// stars, history, in SQLite), the account (AniList tokens in the keystore), Cast and the
// phone's small media server a Chromecast fetches a downloaded episode from.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.kuroka.host"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":api"))
    implementation(project(":sources"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.palette)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    // Media3: the player, HLS/DASH, the session and the offline downloads.
    api(libs.media3.exoplayer)
    api(libs.media3.exoplayer.hls)
    api(libs.media3.exoplayer.dash)
    api(libs.media3.session)
    api(libs.media3.datasource)
    api(libs.media3.database)

    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
