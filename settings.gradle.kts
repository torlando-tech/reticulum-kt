pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reticulum-kt"

include(":rns-core")
include(":rns-interfaces")
include(":rns-test")
include(":rns-cli")
include(":lxmf-core")
include(":lxmf-examples")

// Android module for battery-optimized mobile deployment
include(":rns-android")
