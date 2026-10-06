// template — the copy-me instrument. The five-minute recipe (PLAN M2):
//   1. copy this directory, rename it
//   2. set applicationId + label below
//   3. drop your built artifact at src/main/assets/artifact.html
//   4. list the plugins your artifact needs in InstrumentActivity (and depend on
//      their modules here — the dependency is what carries their permissions)
//   5. gradlew :<yours>:assembleDebug
// Nothing else to touch. core brings lead-acid.js and the posture.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.gentropic.leadacid.template"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.gentropic.leadacid.template"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
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
    // One plugin, to show the shape. Add :plugin-sensor / :plugin-attest / …
    // here AND in InstrumentActivity.plugins; remove both to drop a capability.
    implementation(project(":plugin-share"))
}
