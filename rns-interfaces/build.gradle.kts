plugins {
    kotlin("jvm")
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

dependencies {
    api(project(":rns-core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}
