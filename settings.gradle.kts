pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "traccar-client-sdk"

include(":core")
// The sample app only when this repository is built on its own: an app that builds the SDK from source
// (Gradle includeBuild, TransyTrail) needs only :core.
if (gradle.parent == null) {
    include(":samples:android")
}
