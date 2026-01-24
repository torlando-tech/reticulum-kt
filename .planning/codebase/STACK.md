# Technology Stack

**Analysis Date:** 2026-01-23

## Languages

**Primary:**
- Kotlin 1.9.22 - Core protocol implementation, interfaces, CLI utilities
- Java 21 (JVM modules) / Java 17 (Android modules) - Target runtime compatibility

**Secondary:**
- Python 3.8+ - Reference implementation for interop testing (located at ~/repos/Reticulum)

## Runtime

**Environment:**
- JVM (Java Virtual Machine) for JVM modules
- Android Runtime (ART) for Android-specific modules
- Requires JDK 21+ for development

**Package Manager:**
- Gradle 8.5 with wrapper (`gradlew`)
- Lockfile: Gradle wrapper provides version pinning

## Frameworks

**Core:**
- Kotlin Coroutines 1.8.0 - Asynchronous networking and concurrent operations
- Kotlinx Serialization 1.6.2 - JSON serialization for interface configs (Android sample app)

**Android:**
- Android Jetpack (AndroidX 1.x) - Core AndroidX libraries
  - `androidx.core:core-ktx` 1.12.0 - Context and lifecycle extensions
  - `androidx.lifecycle` 2.7.0 - Service, ViewModel, RuntimeCompose
  - `androidx.work:work-runtime-ktx` 2.9.0 - WorkManager for background tasks
  - `androidx.datastore:datastore-preferences` 1.0.0 - Key-value preferences
- Jetpack Compose 2024.02.00 - Modern UI toolkit (rns-sample-app)
- Navigation Compose 2.7.7 - Navigation framework for Compose
- Accompanist 0.34.0 - Permission handling utilities

**Testing:**
- JUnit 5 (Jupiter) 5.10.1 - Test framework for JVM modules
- Kotest 5.8.0 - BDD assertions and test DSL
- Robolectric 4.11.1 - Android unit test framework
- AndroidX Test 1.5.x - Instrumented testing

**Build/Dev:**
- Shadow JAR 8.1.1 - Fat JAR generation for CLI applications
- Kotlin Gradle Plugin 1.9.22 - Kotlin compilation

## Key Dependencies

**Critical (Cryptography & Protocol):**
- BouncyCastle 1.77 (`org.bouncycastle:bcprov-jdk18on`) - Cryptographic algorithms
  - X25519 key agreement (elliptic curve)
  - Ed25519 signing
  - AES encryption (CBC mode with PKCS7 padding)
  - SHA256/SHA512 hashing
  - HMAC operations
- MessagePack 0.9.8 (`org.msgpack:msgpack-core`) - Binary serialization for wire protocol
  - Used in: Identity, Destination, Packet, Link, Channel, Resource serialization
  - Provides compact binary format for network packets

**Infrastructure:**
- Apache Commons Compress 1.26.0 - BZ2 compression/decompression
  - Used in: Channel Buffer and Resource compression
- Kotlin Logging JVM 3.0.5 - Logging facade with SLF4J backend
- SLF4J Simple 2.0.9 - Simple logging implementation

**CLI & RPC:**
- Clikt 4.2.2 - Command-line argument parsing (rnsd-kt daemon)
- Python Pickle 1.5 (`net.razorvine:pickle`) - Python pickle format serialization
  - Enables RPC compatibility with Python RNS clients
  - Used in: RPC server for daemon communication

**Android-specific:**
- Google Accompanist 0.34.0 - Android permission management

## Configuration

**Environment:**
- Gradle properties file: `gradle.properties`
  - JVM heap: `-Xmx2048m`
  - Kotlin style: `official`
  - Android settings: AndroidX enabled, Jetifier disabled
- Android SDK: compileSdk 34, targetSdk 34, minSdk 26 (Android 8.0+)
- Kotlin compiler: JSR305 strict mode enabled (`-Xjsr305=strict`)

**Build:**
- Root build file: `./build.gradle.kts`
- Module build files: Each subproject has `build.gradle.kts`
- Gradle wrapper: 8.5 binary distribution
- Local properties: `local.properties` (not in repo, user-specific)

**Modules (build targets):**
- `rns-core` - JVM 21, Core protocol
- `rns-interfaces` - JVM 21, Network interfaces
- `rns-cli` - JVM 21, CLI daemon
- `rns-test` - JVM 21, Integration tests
- `lxmf-core` - JVM 21, LXMF protocol
- `lxmf-examples` - JVM 21, Example applications
- `rns-android` - Android, Library
- `rns-sample-app` - Android, Application

## Platform Requirements

**Development:**
- JDK 21+ (for JVM modules)
- Android SDK with API 34 (for Android modules)
- Gradle 8.5+
- Python 3.8+ (optional, for running interop tests)

**Production:**
- **JVM Deployment:** JDK 21 runtime
- **Android Deployment:** Android 8.0+ (API 26+) devices
- **Fat JAR Distribution:** Self-contained JAR with all dependencies (shadow JAR build)

---

*Stack analysis: 2026-01-23*
