plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val coroutinesVersion: String by project

dependencies {
    implementation(project(":rns-core"))
    implementation(project(":rns-interfaces"))
    implementation(project(":lxmf-core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // Testing
    testImplementation(kotlin("test"))
}

tasks {
    shadowJar {
        archiveBaseName.set("lxmf-node")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = "network.reticulum.lxmf.examples.LxmfNodeKt"
        }
    }

    build {
        dependsOn(shadowJar)
    }

    register<JavaExec>("runPropagationTest") {
        group = "application"
        description = "Run propagation sync test"
        mainClass.set("network.reticulum.lxmf.examples.PropagationSyncTestKt")
        classpath = sourceSets["main"].runtimeClasspath
    }
}
