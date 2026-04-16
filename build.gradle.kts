plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.7.6"
}

allprojects {
    group = "com.github.torlando-tech.reticulum-kt"
    version = System.getenv("VERSION")?.removePrefix("v") ?: "0.1.0-SNAPSHOT"
}

dependencies {
    kover(project(":rns-core"))
    kover(project(":rns-interfaces"))
    // Aggregate rns-test execution data so coverage of rns-core classes that
    // can only be reached via two-node / interop tests (e.g. Link.rttPacket
    // owner-callback flow) is counted in the root koverXmlReport.
    kover(project(":rns-test"))
}

subprojects {
    // Apply JVM 21 target only to non-Android modules
    // Android modules use Java 17 for compatibility
    afterEvaluate {
        if (!plugins.hasPlugin("com.android.library") && !plugins.hasPlugin("com.android.application")) {
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                    freeCompilerArgs.add("-Xjsr305=strict")
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
