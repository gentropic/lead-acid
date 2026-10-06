// plugin-share — the system share sheet. Owns the FileProvider (manifest +
// file_paths.xml) it needs to hand cache files to other apps: an instrument
// that doesn't include this module has no provider in its merged manifest —
// the Phase-1 witness that per-module manifest contributions merge.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.share"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
}
