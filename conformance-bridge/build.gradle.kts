plugins {
    kotlin("jvm") version "2.3.0"
    application
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

application {
    mainClass.set("KotlinBridgeKt")
}

// settings.gradle.kts uses PREFER_PROJECT mode, so the JitPack repo for
// LXMF-kt must be declared here. LXMF-kt is an arms-length sibling repo
// published via JitPack; pinning by tag avoids surprise regressions in
// the conformance bridge if the JitPack build changes.
repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation(project(":rns-core"))
    implementation(project(":rns-interfaces"))

    // LXMF-kt: the Kotlin port of LXMF. Only used by the conformance
    // bridge's lxmf_* commands (see Lxmf.kt); rns-core / rns-interfaces
    // do not depend on LXMF. Pinned to v0.0.8 (latest published tag as
    // of this PR). JitPack collapsed the multi-module project into the
    // root artifact, so the coordinate is the repo itself rather than
    // com.github.torlando-tech.LXMF-kt:lxmf-core.
    implementation("com.github.torlando-tech:LXMF-kt:v0.0.8")

    // Coroutines — Lxmf.kt calls LXMRouter.handleOutbound (suspend) and
    // needs runBlocking to adapt it to the synchronous bridge protocol.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

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
