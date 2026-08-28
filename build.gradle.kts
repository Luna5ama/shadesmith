import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile

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
    implementation(libs.simpleLogger)

    testImplementation(kotlin("test"))
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
    withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    test {
        useJUnitPlatform()
    }

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
        "dev.luna5ama.shadesmith", "kotlin.reflect", "org.slf4j"
    )
    optimizeFatJar.configure {
        doLast {
            val output = archiveFile.get().asFile
            val entries = mutableListOf<Pair<String, ByteArray?>>()
            ZipFile(output).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    val bytes: ByteArray? = if (entry.isDirectory) {
                        null
                    } else {
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                    entries += entry.name to bytes
                }
            }

            val temporary = createTempFile(output.parentFile.toPath(), output.name, ".tmp").toFile()
            try {
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    zip.setLevel(Deflater.BEST_COMPRESSION)
                    entries.sortedBy { it.first }.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                        bytes?.let { zip.write(it) }
                        zip.closeEntry()
                    }
                }
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                temporary.delete()
            }
        }
    }

    artifacts {
        archives(optimizeFatJar)
    }
}
