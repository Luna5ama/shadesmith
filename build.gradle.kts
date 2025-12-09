import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

group = "dev.luna5ama"
version = "0.0.1-SNAPSHOT"

plugins {
    kotlin("jvm") version libs.versions.kotlin
    alias(libs.plugins.kotlinxSerialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}


repositories {
    mavenCentral()
    google()
    maven("https://maven.luna5ama.dev")
}

dependencies {
    implementation(libs.kotlinxSerializationCore)
    implementation(libs.kotlinxSerializationJson)

    implementation(libs.fastutil)

    implementation(libs.bundles.kotlinEcosystem)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xcontext-parameters"
        )
    }
}