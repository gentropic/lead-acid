// plugin-gnss — raw GNSS off the platform LocationManager (no Play Services).
// THE FIRST PERMISSION-BEARING PLUGIN: its manifest requests
// ACCESS_FINE_LOCATION (+ COARSE, which API 31+ requires alongside), so an
// instrument gets that permission iff it depends on this module (SPEC §5).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.gnss"
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
