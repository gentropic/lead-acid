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

rootProject.name = "lead-acid"
include(":core", ":plugin-sensor", ":plugin-share", ":plugin-attest", ":plugin-intake", ":plugin-gnss", ":app", ":template", ":wuffle")
