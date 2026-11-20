import java.util.Properties

// The app: the Compose UI in its Classic and Immersive looks, the player and its sheets
// (subtitles, audio, episodes, seasons, servers, Cast), the library, the downloads, the
// settings and the first-run setup. Builds the APKs the CI publishes.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

/** The ABIs a release has an APK for, and how they are named in it (UpdateChecker reads the same names). */
val RELEASE_ABIS = listOf("arm64-v8a", "armeabi-v7a")
val ABI_NAMES = mapOf("arm64-v8a" to "ARMv8", "armeabi-v7a" to "ARMv7")
/** The first word of a release's APK names (UpdateChecker reads it from BuildConfig.APK_NAME). */
val APK_NAME = "Kuroka"

android {
    namespace = "app.kuroka"
    compileSdk = 37

    // Two channels that install side by side: betas ("1.1.0-beta.2") stay "app.kuroka.beta" as
    // "Kuroka Beta"; releases are "app.kuroka" as "Kuroka". A local build (no version given) is
    // on the beta side, like the phone test builds.
    val version = (findProperty("appVersionName") as String?) ?: "1.0.0"
    val beta = findProperty("appVersionName") == null || version.contains("-beta")
    defaultConfig {
        applicationId = if (beta) "app.kuroka.beta" else "app.kuroka"
        manifestPlaceholders["appLabel"] = if (beta) "Kuroka Beta" else "Kuroka"
        minSdk = 29
        targetSdk = 36
        // CI release builds pass the tag's version (-PappVersionName / -PappVersionCode).
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 100
        versionName = version
        // GitHub "owner/repo" whose releases the app checks for updates; empty = off (local builds).
        buildConfigField("String", "UPDATE_REPO", "\"${(findProperty("updateRepo") as String?) ?: ""}\"")
        buildConfigField("String", "APK_NAME", "\"$APK_NAME\"")
        // The AniList sign-in (Settings, Account). Empty id: the app offers the token sign-in only.
        buildConfigField("String", "ANILIST_CLIENT_ID", "\"${(findProperty("anilistClientId") as String?) ?: ""}\"")
        buildConfigField("String", "ANILIST_REDIRECT_URI", "\"${(findProperty("anilistRedirectUri") as String?) ?: "kuroka://anilist/callback"}\"")
        // -Pabis=arm64-v8a: one APK for one phone (the test builds). Without it, the ABI splits below.
        (findProperty("abis") as String?)?.let { ndk { abiFilters += it.split(",") } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release key from the CI secrets (env), else keystore/keystore.properties.
    val envStore = System.getenv("KUROKA_KEYSTORE")
    val hasReleaseKey = envStore != null || keystorePropertiesFile.exists()
    val stableRelease = !beta
    if (stableRelease && !hasReleaseKey &&
        gradle.startParameter.taskNames.any { Regex("(assemble|bundle|package|install)Release").containsMatchIn(it) }
    ) throw GradleException(
        "A stable release needs the release key: set KUROKA_KEYSTORE (CI) or add keystore/keystore.properties."
    )
    signingConfigs {
        create("release") {
            if (envStore != null) {
                storeFile = file(envStore)
                storePassword = System.getenv("KUROKA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KUROKA_KEY_ALIAS")
                keyPassword = System.getenv("KUROKA_KEY_PASSWORD")
            } else if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release apps (own data, own sign-in).
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Kuroka dev"
            buildConfigField("String", "UPDATE_REPO", "\"\"")
        }
        release {
            // The real key when provided (CI releases); otherwise the debug key, so a local
            // release build installs over the debug one while testing.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Betas are release builds too, but their log reports read Log.v/d/i.
            if (!beta) proguardFiles("proguard-strip-logs.pro")
        }
    }
    // One build, every APK a release carries (named by collectApks below): one per ABI, and the
    // universal one (both ABIs), which is also the Android TV APK.
    splits {
        abi {
            isEnable = findProperty("abis") == null
            reset()
            include(*RELEASE_ABIS.toTypedArray())
            isUniversalApk = true
        }
    }
    packaging { jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The Android host (player service, downloads, library, account, Cast) and, through it, :api.
    implementation(project(":host-android"))
    // The providers, for their names and order in Settings, Sources.
    implementation(project(":sources"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.profileinstaller)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // The player: Media3 (ExoPlayer, the session and its controller, the subtitle view).
    implementation(libs.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * `./gradlew :app:collectApks` after assembleRelease: the APKs under clear names in
 * build/release-apks/: Kuroka-1.0.0-ARMv8.apk (64-bit phones, almost all of them),
 * Kuroka-1.0.0-ARMv7.apk (32-bit), Kuroka-1.0.0-universal.apk (both) and Kuroka-1.0.0-TV.apk
 * (Android TV: the universal APK, since many TVs run 32-bit apps on a 64-bit chip).
 * Betas are Kuroka-Beta-…
 */
tasks.register("collectApks") {
    mustRunAfter("assembleRelease")
    val version = (findProperty("appVersionName") as String?) ?: "1.0.0"
    val beta = findProperty("appVersionName") == null || version.contains("-beta")
    val app = if (beta) "$APK_NAME-Beta" else APK_NAME
    // Copies: the configuration cache cannot keep a reference to the script's own values.
    val abis = RELEASE_ABIS.toList()
    val abiNames = ABI_NAMES.toMap()
    val from = layout.buildDirectory.dir("outputs/apk/release")
    val into = layout.buildDirectory.dir("release-apks")
    doLast {
        val src = from.get().asFile
        val dst = into.get().asFile.apply { deleteRecursively(); mkdirs() }
        fun copy(name: String, to: String) {
            val f = src.resolve(name)
            if (!f.exists()) throw GradleException("$name missing: run assembleRelease without -Pabis first")
            f.copyTo(dst.resolve(to), overwrite = true)
        }
        abis.forEach { abi -> copy("app-$abi-release.apk", "$app-$version-${abiNames.getValue(abi)}.apk") }
        copy("app-universal-release.apk", "$app-$version-universal.apk")
        copy("app-universal-release.apk", "$app-$version-TV.apk")
        dst.listFiles()?.sorted()?.forEach { println("${it.name}  ${it.length() / 1024 / 1024} MB") }
    }
}
