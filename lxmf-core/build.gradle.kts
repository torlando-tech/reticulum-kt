plugins {
    kotlin("jvm")
}

val coroutinesVersion: String by project
val junitVersion: String by project
val kotestVersion: String by project

dependencies {
    // Depend on rns-core for Reticulum functionality
    implementation(project(":rns-core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Test dependencies for live networking tests
    testImplementation(project(":rns-interfaces"))

    // MessagePack for serialization (already in rns-core, but explicit)
    implementation("org.msgpack:msgpack-core:0.9.8")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")

    // Interop testing - reuse Python bridge infrastructure from rns-test
    testImplementation(project(":rns-test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Compression - Apache Commons Compress for BZ2 interop tests
    testImplementation("org.apache.commons:commons-compress:1.26.0")
}
