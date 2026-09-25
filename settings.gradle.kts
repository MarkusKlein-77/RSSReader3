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

rootProject.name = "kotlin-multiplatform-hello"

include(":shared")
include(":sharedLogic")
include(":androidApp")
include(":desktopApp")
include(":webApp")
include(":nodeApp")

