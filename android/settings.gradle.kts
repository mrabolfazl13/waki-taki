pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "SecureTalk"

include(":app")
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:security")
include(":core:audio")
include(":core:design")
include(":core:permissions")
include(":feature:onboarding")
include(":feature:authentication")
include(":feature:home")
include(":feature:channels")
include(":feature:channel")
include(":feature:members")
include(":feature:local_network")
include(":feature:security")
include(":feature:settings")
include(":feature:devices")
include(":feature:diagnostics")
