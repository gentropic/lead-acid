// app — the dev / kitchen-sink harness: core + EVERY plugin module, hosting any
// artifact (`--es artifact <name>.html` on debug builds) and carrying the bench
// page. This is our development + on-device-test app, not an instrument.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.gentropic.leadacid.spike"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1-m2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-sensor"))
    implementation(project(":plugin-share"))
    implementation(project(":plugin-attest"))
    implementation(project(":plugin-intake"))
    implementation(project(":plugin-gnss"))
    implementation(project(":plugin-camera"))
    implementation(project(":plugin-intent"))
}
