// core — the jig. An Android LIBRARY an instrument depends on: the activity base,
// the bridge, the port lanes, and the always-present fs + shell plugins. Its
// manifest requests nothing (SPEC §8); permissions arrive only through plugin
// modules an instrument chooses to depend on.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // lead-acid.js (SPEC §4.5) ships as a library ASSET: the root file stays the
    // single source of truth (the monorepo vendors it from there), and the asset
    // merge puts /assets/lead-acid.js in front of every instrument's artifact.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/leadacid-assets"))
}

val copyShim by tasks.registering(Copy::class) {
    from(rootProject.file("lead-acid.js"))
    into(layout.buildDirectory.dir("generated/leadacid-assets"))
}
tasks.matching { it.name.startsWith("generate") || it.name.startsWith("merge") }.configureEach {
    dependsOn(copyShim)
}

dependencies {
    // api: LeadAcidActivity IS a ComponentActivity and the host hands plugins
    // androidx types — instruments compile against them through core.
    api(libs.androidx.webkit)
    api(libs.androidx.core)
    api(libs.androidx.activity)
}
