plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val coroutinesVersion: String by project
val junitVersion: String by project
val kotestVersion: String by project
val serializationVersion: String by project

dependencies {
    implementation(project(":rns-core"))
    implementation(project(":rns-interfaces"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // JSON for Python bridge communication
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
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
