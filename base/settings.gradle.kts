pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kuroka"
// :api is the contract the UI talks to (plain Kotlin models and interfaces).
include(":api")
// :sources answers searches and episode pages, and finds the streams (AniList, the providers, AniSkip).
include(":sources")
// :host-android is the Android-only host: playback service, downloads, library, account, Cast.
include(":host-android")
// :app is the Compose UI, in its Classic and Immersive looks.
include(":app")
