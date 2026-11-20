// Where the app learns about anime and finds its streams: AniList (search, pages, seasons, the
// account), the video providers behind one contract, the id mappers and AniSkip (intro/outro).
// Plain Kotlin with no Android widget in it, so its parsing is testable on a desktop.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.kuroka.sources"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    // Unit tests read the providers' answers as JSON: android.jar only has stubs of org.json.
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
