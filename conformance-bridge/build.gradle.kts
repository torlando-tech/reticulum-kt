plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

application {
    mainClass.set("KotlinBridgeKt")
}

dependencies {
    implementation(project(":rns-core"))
    implementation(project(":rns-interfaces"))

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // MessagePack (already in rns-core, but explicit for bridge use)
    implementation("org.msgpack:msgpack-core:0.9.8")

    // BZ2 compression (already in rns-core)
    implementation("org.apache.commons:commons-compress:1.26.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("ConformanceBridge")
    archiveClassifier.set("")
    archiveVersion.set("")
}
