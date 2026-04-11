plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val serializationVersion: String by project

dependencies {
    implementation(project(":rns-core"))
    implementation(project(":rns-interfaces"))

    // CLI parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // JSON for pipe peer protocol
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

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
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    register("printPipePeerRuntimeClasspath") {
        dependsOn("classes")
        doLast {
            println(sourceSets.main.get().runtimeClasspath.asPath)
        }
    }

    // Pipe peer jar for conformance testing
    register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("pipePeerJar") {
        archiveBaseName.set("kt-pipe-peer")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = "network.reticulum.cli.PipePeerKt"
        }
        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.runtimeClasspath.get())
        mergeServiceFiles()
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    build {
        dependsOn(shadowJar)
    }
}
