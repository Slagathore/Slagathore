import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM library - NO Android dependencies, so it's fast to test and reusable (CLI, app, a server later).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Generates JSON (de)serializers for @Serializable classes, so the app can save a plan to disk.
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // `api` (not `implementation`): our public classes are @Serializable, so anything using them needs the runtime.
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
