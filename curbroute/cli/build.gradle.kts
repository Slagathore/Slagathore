import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Terminal front-end: `./gradlew :cli:run --args="core/src/test/resources/sheet-2026-09-29.txt"`
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json) // for the --geojson writer
}

application {
    mainClass.set("dev.slagathore.curbroute.cli.MainKt")
}

// Run from the repo root so relative file paths in --args work the way you'd expect.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
