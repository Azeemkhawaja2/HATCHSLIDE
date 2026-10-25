pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "hatchslide"

include(
    ":app",
    ":core:game",
    ":core:render",
    ":core:audio",
    ":core:data",
    ":core:designsystem",
    ":feature:menu",
    ":feature:map",
    ":feature:game",
    ":feature:journal",
    ":hd_assets",
    ":baselineprofile",
    ":tools:levelgen",
)
