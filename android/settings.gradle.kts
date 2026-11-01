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
        // libsignal is published only by Signal itself; nothing else may come from here.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content { includeGroup("org.signal") }
        }
    }
}

rootProject.name = "vynq"
include(":app")
include(":core:designsystem")
