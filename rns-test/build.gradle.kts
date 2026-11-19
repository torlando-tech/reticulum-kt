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
    // The Python peers ARE the reference implementation: the checkouts chosen
    // by PYTHON_RNS_PATH / PYTHON_LXMF_PATH decide the interop semantics, so a
    // different release pair changes test outcomes. Both paths are declared
    // task inputs — without that, a green result produced against another pin
    // is replayed from the build cache (org.gradle.caching=true), masking
    // upstream drift and making a pin bump look tested when it was not.

    // Try env var first, then common absolute paths, then relative path
    val rnsPath = System.getenv("PYTHON_RNS_PATH")
        ?: listOf(
            File(System.getProperty("user.home"), "repos/Reticulum"),
            File("${rootProject.projectDir}/../../../Reticulum"),
            File("${rootProject.projectDir}/../Reticulum")
        ).find { it.exists() && File(it, "RNS").exists() }?.absolutePath
        ?: "${rootProject.projectDir}/../../../Reticulum"

    val lxmfPath = System.getenv("PYTHON_LXMF_PATH")
        ?: listOf(
            File(System.getProperty("user.home"), "repos/LXMF"),
            File("${rootProject.projectDir}/../../../LXMF"),
            File("${rootProject.projectDir}/../LXMF")
        ).find { it.exists() && File(it, "LXMF").exists() }?.absolutePath
        ?: "${rootProject.projectDir}/../../../LXMF"

    environment("PYTHON_RNS_PATH", rnsPath)
    environment("PYTHON_LXMF_PATH", lxmfPath)
    inputs.property("pythonRnsPath", rnsPath)
    inputs.property("pythonLxmfPath", lxmfPath)
    // A pin bump checks each release out into the SAME directory, so the path
    // properties above stay identical and cannot invalidate the cache. Key the
    // task on the reference sources themselves: a different release pair has
    // different Python files, so a green result from another pin can never be
    // replayed for the new one.
    inputs.files(
        listOf(rnsPath, lxmfPath).map { dir ->
            project.fileTree(dir) { include("**/*.py") }
        },
    ).withPropertyName("pythonReferenceSources")
    useJUnitPlatform()
}
