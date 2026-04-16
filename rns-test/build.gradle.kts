plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    `maven-publish`
}

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}

val coroutinesVersion: String by project
val junitVersion: String by project
val kotestVersion: String by project
val serializationVersion: String by project

dependencies {
    api(project(":rns-core"))
    api(project(":rns-interfaces"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // JSON for Python bridge communication
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // JUnit for InteropTestBase (used in main source for base class annotations)
    api("org.junit.jupiter:junit-jupiter-api:$junitVersion")

    // Kotest for assertions in interop base classes
    api("io.kotest:kotest-assertions-core:$kotestVersion")

    // MessagePack for resource tests
    testImplementation("org.msgpack:msgpack-core:0.9.8")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

tasks.test {
    // Set environment variable for Python RNS path
    // Try env var first, then common absolute paths, then relative path
    val rnsPath = System.getenv("PYTHON_RNS_PATH")
        ?: listOf(
            File(System.getProperty("user.home"), "repos/Reticulum"),
            File("${rootProject.projectDir}/../../../Reticulum"),
            File("${rootProject.projectDir}/../Reticulum")
        ).find { it.exists() && File(it, "RNS").exists() }?.absolutePath
        ?: "${rootProject.projectDir}/../../../Reticulum"

    environment("PYTHON_RNS_PATH", rnsPath)
    useJUnitPlatform()
}
