// The Kuroka API contract: models and interfaces only, no Android, no provider details.
// The UI depends on this module alone; :sources and :host-android implement it.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
