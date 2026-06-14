/*
 * Settings script — infinite-specs-xr-core
 *
 * Declares the project name and all included build modules.
 * The version catalog (gradle/libs.versions.toml) is automatically
 * picked up by Gradle's default catalog resolution.
 */

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

rootProject.name = "infinite-specs-xr-core"

include(":app")
