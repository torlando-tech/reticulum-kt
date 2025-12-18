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
    environment("PYTHON_RNS_PATH", System.getenv("PYTHON_RNS_PATH") ?: "${rootProject.projectDir}/../Reticulum")
}
