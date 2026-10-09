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
include(":core", ":plugin-sensor", ":plugin-share", ":plugin-attest", ":plugin-intake", ":plugin-gnss", ":plugin-camera", ":plugin-tree", ":plugin-intent", ":plugin-speech", ":plugin-tts", ":app", ":template", ":wuffle", ":gnsslog", ":micro", ":lamina", ":ep")
