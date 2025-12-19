plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":rns-core"))
    implementation(project(":rns-interfaces"))

    // CLI parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Python pickle for RPC compatibility
    implementation("net.razorvine:pickle:1.5")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
}

tasks {
    shadowJar {
        archiveBaseName.set("rnsd-kt")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = "network.reticulum.cli.RnsdKt"
        }
    }

    build {
        dependsOn(shadowJar)
    }
}
