// plugin-camera — level 1 of docs/camera-plugin.md: this module's manifest
// brings the CAMERA permission into an instrument (so the shell's getUserMedia
// wiring can grant a web preview/capture) and enumerates the cameras over
// Camera2. The CameraX/RAW capture tier (the transparent-WebView preview
// composite, DNG) is the dedicated camera app's job — not built here.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.camera"
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
