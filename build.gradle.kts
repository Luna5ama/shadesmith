group = "dev.luna5ama"
version = "0.0.1-SNAPSHOT"

plugins {
    kotlin("jvm") version libs.versions.kotlin
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.jarOptimizer)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}


repositories {
    mavenLocal()
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

tasks {
    val mainClassRef = "dev.luna5ama.shadesmith.Main"
    jar {
        manifest {
            attributes["Main-Class"] = mainClassRef
        }
    }

    val fatJar by registering(Jar::class) {
        group = "build"

        from(jar.get().archiveFile.map { zipTree(it) })
        from(configurations.runtimeClasspath.get().elements.map { set ->
            set.map {
                if (it.asFile.isDirectory) it else zipTree(
                    it
                )
            }
        })

        manifest {
            attributes["Main-Class"] = mainClassRef
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        archiveClassifier.set("fatjar")
    }

    val optimizeFatJar = jarOptimizer.register(
        fatJar,
        "dev.luna5ama.shadesmith"
    )

    artifacts {
        archives(optimizeFatJar)
    }
}