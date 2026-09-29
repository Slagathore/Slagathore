pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CurbRoute"

// :core = pure Kotlin brains (parsing, optimizing, link building). Runs anywhere a JVM runs.
// :cli  = terminal front-end for :core, so you can test a sheet from your PC without a phone.
include(":core", ":cli")

// :app = the Android app. Only included when an Android SDK is present, so the CLI still builds
// on a machine that has only a JDK. (Android Studio always writes local.properties, so it'll be included there.)
val hasAndroidSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { System.getenv(it) != null } ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
} else {
    println("CurbRoute: no Android SDK found - building :core and :cli only.")
}
