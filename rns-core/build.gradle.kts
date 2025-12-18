plugins {
    kotlin("jvm")
}

val coroutinesVersion: String by project
val bouncycastleVersion: String by project
val junitVersion: String by project
val kotestVersion: String by project

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Cryptography - BouncyCastle for JVM
    implementation("org.bouncycastle:bcprov-jdk18on:$bouncycastleVersion")

    // MessagePack for serialization
    implementation("org.msgpack:msgpack-core:0.9.8")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}
