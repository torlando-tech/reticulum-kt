plugins {
    kotlin("jvm") version "1.9.22" apply false
    kotlin("android") version "1.9.22" apply false
    kotlin("plugin.serialization") version "1.9.22" apply false
    id("com.android.library") version "8.2.2" apply false
}

allprojects {
    group = "network.reticulum"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // Apply JVM 21 target only to non-Android modules
    // Android modules use Java 17 for compatibility
    afterEvaluate {
        if (!plugins.hasPlugin("com.android.library") && !plugins.hasPlugin("com.android.application")) {
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                kotlinOptions {
                    jvmTarget = "21"
                    freeCompilerArgs = listOf("-Xjsr305=strict")
                }
            }

            tasks.withType<JavaCompile> {
                sourceCompatibility = "21"
                targetCompatibility = "21"
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
