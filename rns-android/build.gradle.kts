plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    `maven-publish`
}

val coroutinesVersion: String by project

android {
    namespace = "network.reticulum.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26  // Android 8.0
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") { from(components["release"]) }
        }
    }
}

dependencies {
    // Project modules
    api(project(":rns-core"))
    api(project(":rns-interfaces"))

    // Android dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    api("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Google Nearby Connections (WiFi Direct + BLE mesh)
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")

    // Android instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
