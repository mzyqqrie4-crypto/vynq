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
        // LiveKit's audio routing library is published only through JitPack, pinned to a commit.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}

rootProject.name = "vynq"
include(":app")
include(":core:designsystem")

// Phase 6: standalone multi-path experiment (docs/06). Not part of the vynq app.
include(":experiments:fusion")
project(":experiments:fusion").projectDir = file("../experiments/fusion")
